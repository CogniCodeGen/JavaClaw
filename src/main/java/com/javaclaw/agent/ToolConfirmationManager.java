package com.javaclaw.agent;

import com.javaclaw.api.interaction.ConfirmDecision;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.agent.risk.ReadOnlyCommands;
import com.javaclaw.agent.risk.ScopeVerdict;
import com.javaclaw.agent.risk.ToolScopeAssessor;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.ToolReviewMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 高风险工具操作确认管理器（UI 无关）
 *
 * <p>根据 {@link ToolRiskRegistry} 中登记的风险等级，按强度向 {@link UserInteractionPort}
 * 发起不同类型的用户确认：</p>
 * <ul>
 *   <li>{@link ToolRiskLevel#NOTIFY}       → 非阻塞 Toast，自动放行</li>
 *   <li>{@link ToolRiskLevel#CONFIRM}      → 标准确认对话框</li>
 *   <li>{@link ToolRiskLevel#DOUBLE_CONFIRM} → 需键入"确认"关键词的二次确认</li>
 * </ul>
 *
 * <p><b>归属凭据制</b>：调用来源由 {@link ToolCallOrigin} 令牌在工具装配期绑定、确认时随调用
 * 传入——托管任务来源可命中本任务的「同意全部」白名单（{@link #TASK_ALLOW_ALL}）、可经目录
 * 范围评估自动放行、确认超时放宽（60s→600s）；交互/定时/未知来源一律逐次确认。归属是构造期
 * 事实而非运行时推断，任务授权不可能串染到其它来源的调用（旧的场景栈 + 交互回合计数推断机制
 * 已整体移除，其三面漏风——交互调用借任务授权、定时任务无登记照借、交互在飞误挂起已授权任务
 * ——随之消失）。</p>
 *
 * <p>本类不再直接调用 JavaFX，所有 UI 交互均经 {@link UserInteractionPort}。应用启动时
 * 必须调用 {@link #setPort(UserInteractionPort)} 注入具体实现。</p>
 */
public class ToolConfirmationManager {

    private static final Logger log = LoggerFactory.getLogger(ToolConfirmationManager.class);

    /** 二次确认时要求用户输入的关键词 */
    private static final String DOUBLE_CONFIRM_KEYWORD = "确认";

    /**
     * 确认结果的来源形态：布尔放行之外，调用方（如命令白名单）需要区分「用户人工点击允许」
     * 与「策略自动放行」——白名单是把一次授权升级为跨重启的永久免确认，只有真实人工点击
     * 才配得上这种升级；AUTO 总闸/授权窗/范围评估的自动放行落库等于替用户做了从未作出的授权。
     */
    public enum ConfirmOutcome {
        /** 拒绝（人工拒绝 / 超时无应答 / 端口未就绪）。 */
        DENIED,
        /** 策略自动放行（AUTO 总闸 / 定时授权窗 / 任务白名单 / 只读命令 / 范围评估 / NOTIFY 直放）。 */
        ALLOWED_AUTO,
        /** 用户在确认弹窗中人工点击允许。 */
        ALLOWED_HUMAN;

        public boolean isAllow() {
            return this != DENIED;
        }
    }

    /** 全局开关：是否启用确认机制 */
    private static volatile boolean enabled = true;

    /**
     * 风险评估智能体：判定目录作用域高风险工具的影响范围是否限于任务工作目录。
     *
     * <p>由应用层注入（{@link #setScopeAssessor}）。未注入时该机制整体失效，回退为人工确认。</p>
     */
    private static volatile ToolScopeAssessor scopeAssessor;

    /**
     * 任务级"同意全部"白名单：包含 taskId 表示该任务下所有高风险工具一律放行。
     *
     * <p>用户在弹窗里选择一次"同意全部"后，本任务内所有后续高风险工具调用直接放行
     * 不再弹窗（包括不同工具名、不同参数）。命中只认调用自带的 {@link ToolCallOrigin}
     * 托管任务令牌，其它来源（交互/定时/未知）永远不吃此白名单。仅在任务真正终结
     * （COMPLETED / FAILED / CANCELLED / 删除）时由 SddTaskManager / LoopService 显式调用
     * {@link #clearTaskAllowlist(String)} 清空——续跑态（NEEDS_HUMAN → resume）需要保留授权，
     * 否则用户会被反复弹窗骚扰。</p>
     */
    private static final Set<String> TASK_ALLOW_ALL = ConcurrentHashMap.newKeySet();

    /**
     * 当前正在无人值守执行、且已获用户<b>显式授权</b>的定时执行令牌<b>实例</b>；
     * 无（未授权 / 非定时执行期）为 null。
     *
     * <p>定时执行由 {@code ScheduleManager} 的<b>单线程串行执行器</b>驱动——同一时刻至多一个定时任务
     * 在跑。放行按<b>令牌实例身份（{@code ==}）</b>匹配而非 taskId 值匹配：令牌由
     * {@link #beginAuthorizedScheduledRun} 逐 run 全新构造并交给 {@code ScheduledTaskAgent}
     * 装配本次 run 的全部工具，归属是装配期事实。由 {@link #beginAuthorizedScheduledRun}/
     * {@link #endScheduledRun} 在每次定时执行前后成对设置/清除。</p>
     *
     * <p>上一次执行超时被 dispose 后残存的僵尸工具线程（含仍在多轮循环的子智能体）携带的是
     * <b>旧 run 的令牌实例</b>：即便存活到下一个 run 的授权窗——包括<b>同一任务</b>的下一个
     * tick（此时 taskId 完全相同，值匹配会被借道放行）——实例身份也对不上，只会走人工确认
     * （无人应答超时拒绝），结构上不可能借授权。</p>
     */
    private static volatile ToolCallOrigin authorizedScheduledOrigin;

    /** UI 端口；未设置时拒绝所有需要确认的工具调用 */
    private static volatile UserInteractionPort port;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean e) {
        ToolConfirmationManager.enabled = e;
    }

    /** 由应用层在启动时注入 UI 端口（JavaFX / Web 等） */
    public static void setPort(UserInteractionPort p) {
        port = p;
    }

    public static UserInteractionPort getPort() {
        return port;
    }

    /** 注入风险评估智能体（由应用层在持有 ModelFactory 后装配；null 表示禁用目录内自动放行）。 */
    public static void setScopeAssessor(ToolScopeAssessor assessor) {
        scopeAssessor = assessor;
    }

    /** 显式清除某个任务的"同意全部"授权（任务取消/失败/完成/删除时由 SddTaskManager / LoopService 调用） */
    public static void clearTaskAllowlist(String taskId) {
        if (taskId != null) TASK_ALLOW_ALL.remove(taskId);
    }

    /** 把指定任务标记为"同意全部"——该任务后续所有高风险工具调用一律放行 */
    private static void recordAllowAll(String taskId) {
        if (taskId != null) TASK_ALLOW_ALL.add(taskId);
    }

    /**
     * 标记「一次定时任务的无人值守执行开始」并构造<b>本次 run 专属</b>的来源令牌：
     * 已授权时，其间携带<b>该令牌实例</b>的确认自动放行。必须与 {@link #endScheduledRun()}
     * 成对（在定时执行的 finally 里清除），且只由串行定时执行器调用。
     *
     * <p>返回的令牌须交给 {@code ScheduledTaskAgent} 装配本次 run 的全部工具——授权窗与工具
     * 令牌由同一次构造共享同一实例，实例身份匹配才成立；僵尸线程携带的旧实例（即便同任务
     * 同 taskId）永远对不上。</p>
     *
     * @param taskId     定时任务 ID（令牌归属标识，用于日志与工具装配）
     * @param authorized 该任务是否已获用户显式授权（未授权则不开授权窗，照常走确认→无人应答超时拒绝）
     * @return 本次 run 的来源令牌（无论是否授权都返回，供工具装配绑定归属）
     */
    public static ToolCallOrigin beginAuthorizedScheduledRun(String taskId, boolean authorized) {
        ToolCallOrigin origin = ToolCallOrigin.scheduled(taskId);
        authorizedScheduledOrigin = (authorized && origin.taskId() != null) ? origin : null;
        return origin;
    }

    /** 清除定时执行授权标记（定时执行结束的 finally 里调用）。 */
    public static void endScheduledRun() {
        authorizedScheduledOrigin = null;
    }

    /**
     * 检查指定工具是否需要确认。
     *
     * <p>保留用于向后兼容：只要工具在注册表内（任意等级）即返回 true，
     * 由 {@link #requestConfirmation(ToolCallOrigin, String, String)} 内部按等级分派处理。</p>
     */
    public static boolean requiresConfirmation(String toolName) {
        if (!enabled || !ToolRiskRegistry.isManaged(toolName)) return false;
        return AgentConfig.getInstance().getToolReviewMode() != ToolReviewMode.AUTO;
    }

    /**
     * 请求用户确认（阻塞调用线程直到用户响应或超时）。
     *
     * @param origin      调用来源令牌（工具装配期绑定）；null 按 {@link ToolCallOrigin#UNKNOWN} 最保守处理
     * @param toolName    工具名称
     * @param description 操作描述
     * @return true=放行，false=拒绝
     */
    public static boolean requestConfirmation(ToolCallOrigin origin, String toolName, String description) {
        return confirmInternal(origin == null ? ToolCallOrigin.UNKNOWN : origin, toolName, description, false)
                .isAllow();
    }

    /**
     * 请求用户确认（无来源令牌的旧签名，兜底给未迁移调用点/外部扩展）。
     *
     * <p>按 {@link ToolCallOrigin#UNKNOWN} 最保守处理：不吃任何任务白名单、不做目录范围
     * 自动放行、默认超时。项目内工具应一律走带 {@link ToolCallOrigin} 的签名。</p>
     */
    public static boolean requestConfirmation(String toolName, String description) {
        return confirmInternal(ToolCallOrigin.UNKNOWN, toolName, description, false).isAllow();
    }

    /**
     * 高风险 shell 命令专用确认：<b>AUTO 总闸对其不生效</b>，其余漏斗与统一路径一致。
     *
     * <p>任意命令执行的破坏力上不封顶（{@code sudo rm -rf} / 管道拉取执行），与登记在注册表里
     * 影响面可预估的单一工具不同——历史行为也一直是「无视审核模式必弹窗」。AUTO 模式对
     * 注册表工具全放行是用户可预期的授权，但不应顺带把「模型自发的任意破坏性命令」也纳入静默
     * 放行（与 jshell 必须保持 CONFIRM 级同一考量：不给无人工干预的任意代码执行留通道）。
     * 定时任务显式授权窗（taskId 精确匹配）仍可自动放行——那是用户对具体任务的显式授权。</p>
     *
     * @return 区分人工点击与自动放行的确认结果：调用方只应在 {@link ConfirmOutcome#ALLOWED_HUMAN}
     *         时把命令落入「确认即记住」白名单（自动放行落库=把临时授权升级为永久免确认）
     */
    public static ConfirmOutcome requestHighRiskCommandConfirmation(
            ToolCallOrigin origin, String toolName, String description) {
        return confirmInternal(origin == null ? ToolCallOrigin.UNKNOWN : origin, toolName, description, true);
    }

    /**
     * 独立确认（阻塞）：与任何托管任务<b>无关</b>的一次性授权（如循环启动前的验证命令确认），
     * 不吃任务白名单、不走目录自动放行、不提供「同意全部」选项。
     *
     * <p>按 {@link ToolCallOrigin#UNKNOWN} 来源收敛到统一实现，保留此入口只为调用点语义自明。
     * <b>AUTO 总闸对其不生效</b>（同 {@link #requestHighRiskCommandConfirmation} 考量）：
     * 走此入口的是模型生成、且后续将绕过工具确认反复执行的任意命令（如循环验证命令），
     * 破坏力上不封顶，全自动审核下仍保留人工底线；SMART/MANUAL 照常弹窗。</p>
     *
     * @return true=放行，false=拒绝
     */
    public static boolean requestStandaloneConfirmation(String toolName, String description) {
        return confirmInternal(ToolCallOrigin.UNKNOWN, toolName, description, true).isAllow();
    }

    /**
     * 确认核心（唯一实现，所有公开入口收敛于此）。
     *
     * <p>SMART 模式下的放行漏斗，按序命中即定：AUTO 总闸 → 本任务「同意全部」白名单
     * （仅托管任务令牌）→ NOTIFY Toast 直放 → 确定性只读命令直放（仅托管任务令牌的
     * 目录作用域工具）→ 目录范围评估自动放行（仅托管任务令牌）→ 人工弹窗。</p>
     *
     * @param humanGateInAuto true 表示 AUTO 总闸对本次确认不生效（高风险 shell 命令的人工底线，
     *                        见 {@link #requestHighRiskCommandConfirmation}），漏斗其余环节照常
     */
    private static ConfirmOutcome confirmInternal(ToolCallOrigin origin, String toolName,
                                                  String description, boolean humanGateInAuto) {
        if (!enabled) return ConfirmOutcome.ALLOWED_AUTO;
        ToolRiskLevel level = ToolRiskRegistry.levelOf(toolName);
        if (level == null) return ConfirmOutcome.ALLOWED_AUTO;

        ToolReviewMode reviewMode = AgentConfig.getInstance().getToolReviewMode();
        if (reviewMode == ToolReviewMode.AUTO && !humanGateInAuto) {
            log.info("[全自动审核] 默认放行 origin={} tool={} desc={}", origin.kind(), toolName, description);
            return ConfirmOutcome.ALLOWED_AUTO;
        }
        boolean manualReview = reviewMode == ToolReviewMode.MANUAL;
        ToolRiskLevel effectiveLevel = manualReview && level == ToolRiskLevel.NOTIFY
                ? ToolRiskLevel.CONFIRM
                : level;

        // 0. 任务级"同意全部"授权：只认调用自带的托管任务令牌——归属是装配期事实，
        // 其它来源（交互/定时/未知）不可能命中，无需任何归属推断。
        // 手动审核模式要求每次操作都由用户确认，因此不使用任务白名单。
        boolean managedTask = origin.isManagedTask();
        if (!manualReview && managedTask && TASK_ALLOW_ALL.contains(origin.taskId())) {
            log.info("[任务·同意全部] taskId={} tool={} desc={}", origin.taskId(), toolName, description);
            return ConfirmOutcome.ALLOWED_AUTO;
        }

        // 定时任务·显式授权：用户逐任务打开「允许无人值守执行高风险工具」后，其定时执行期间
        // 本次 run 令牌的确认自动放行（否则无人应答只会超时按拒绝、定时自动化静默失败）。
        // 安全边界：按令牌实例身份（==）匹配授权窗（见 authorizedScheduledOrigin）——上一次
        // 执行残存僵尸线程携带旧 run 的令牌实例，即便是同一任务的下个 tick（taskId 相同）
        // 也对不上，借授权在结构上不可能；手动审核模式（MANUAL）不吃此授权，与任务白名单一致
        ToolCallOrigin authorizedOrigin = authorizedScheduledOrigin;
        if (!manualReview && authorizedOrigin != null && origin == authorizedOrigin) {
            log.info("[定时任务·已授权] taskId={} tool={} desc={}（用户已显式授权本定时任务无人值守执行）",
                    authorizedOrigin.taskId(), toolName, description);
            notifyToast(toolName, "已自动放行（本定时任务已获显式授权无人值守执行）");
            return ConfirmOutcome.ALLOWED_AUTO;
        }

        UserInteractionPort p = port;
        if (p == null || !p.isAvailable()) {
            log.warn("UserInteractionPort 未就绪，拒绝工具调用: {}", toolName);
            return ConfirmOutcome.DENIED;
        }

        // 智能审核下 NOTIFY 直接放行（仅 Toast 通知）；手动审核会把 NOTIFY 升级为确认弹窗。
        if (!manualReview && level == ToolRiskLevel.NOTIFY) {
            p.notify(new ToastRequest(toolName, description));
            return ConfirmOutcome.ALLOWED_AUTO;
        }

        // 风险评估智能体「目录内自动放行」：仅托管任务令牌 + 目录作用域工具 + 开关开启时尝试。
        // 评估基准就是令牌自带的工作目录，不存在借并发任务目录的可能。
        if (!manualReview && managedTask && ToolRiskRegistry.isDirScopedTool(toolName)
                && AgentConfig.getInstance().isTaskRiskAutoApproveEnabled()) {
            // 0) 确定性只读命令直接放行：零副作用，越界读取（如 ls ~/.m2）也无需人工，且省一次范围评估调用。
            //    无人值守时这类命令走人工确认只会等满超时按拒绝处理，浪费时间且诱发执行体重试。
            if ("cmd_execute".equals(toolName)) {
                String cmd = extractCommand(description);
                if (cmd != null && ReadOnlyCommands.isReadOnly(cmd)) {
                    log.info("[只读命令·自动放行] cmd={}", cmd);
                    p.notify(new ToastRequest(toolName, "已自动放行（只读命令，无副作用）：" + cmd));
                    return ConfirmOutcome.ALLOWED_AUTO;
                }
            }
            String autoReason = tryAutoApproveByScope(toolName, description, origin.workDir());
            if (autoReason != null) {
                log.info("[风险评估·自动放行] taskId={} tool={} workDir={} desc={} reason={}",
                        origin.taskId(), toolName, origin.workDir(), description, autoReason);
                p.notify(new ToastRequest(toolName, "已自动放行（影响范围限于任务目录）：" + autoReason));
                return ConfirmOutcome.ALLOWED_AUTO;
            }
        }

        ConfirmKind kind = (effectiveLevel == ToolRiskLevel.DOUBLE_CONFIRM)
                ? ConfirmKind.DOUBLE_CONFIRM : ConfirmKind.CONFIRM;
        // 「同意全部」选项只对托管任务令牌展示：授权登记落到令牌自带的 taskId，
        // 其它来源展示该按钮会让用户点了却被静默丢弃，比多弹几次确认更糟
        boolean offerAllowAll = managedTask && !manualReview;
        ConfirmDecision decision = p.confirmEx(new ConfirmRequest(
                toolName, riskLabel(effectiveLevel), description,
                kind, timeoutSeconds(origin),
                kind == ConfirmKind.DOUBLE_CONFIRM ? DOUBLE_CONFIRM_KEYWORD : "",
                offerAllowAll));

        if (decision == ConfirmDecision.ALLOW_ALL && offerAllowAll) {
            recordAllowAll(origin.taskId());
            log.info("[同意全部] taskId={} 已开启全部放行，后续高风险工具调用不再弹窗", origin.taskId());
            notifyToast(toolName, "已开启本任务「全部放行」，所有后续高风险操作将自动执行");
        }
        return decision.isAllow() ? ConfirmOutcome.ALLOWED_HUMAN : ConfirmOutcome.DENIED;
    }

    /** cmd_execute 确认描述的命令前缀（拼接与解析共用单一来源，见 {@link #buildCommandDescription}）。 */
    private static final String CMD_DESC_PREFIX = "命令: ";
    /** cmd_execute 确认描述中命令与目录的分隔标记。 */
    private static final String CMD_DESC_DIR_SEP = " | 目录: ";

    /**
     * 拼装 cmd_execute 的确认描述（与 {@link #extractCommand} 的解析格式配对）。
     *
     * <p>只读命令免确认通道靠 extractCommand 反解析该描述还原命令文本——拼接与解析必须
     * 共用同一份格式定义，调用方自拼字面量的话，任何文案调整都会让解析静默失配：
     * 托管任务的 ls/grep 等只读命令全部退回人工确认、无人值守等满超时按拒绝。
     * 调用方可在返回值之后追加说明文字（解析按「前缀 + 最后一个目录分隔标记」定位，
     * 追加内容只要不含分隔标记即不影响还原）。</p>
     */
    public static String buildCommandDescription(String command, String dir) {
        return CMD_DESC_PREFIX + command + CMD_DESC_DIR_SEP + dir;
    }

    /**
     * 从 cmd_execute 的确认描述中解析出命令文本。
     *
     * <p>描述由 {@link #buildCommandDescription} 固定拼为 {@code "命令: <cmd> | 目录: <dir>"}，
     * 目录后缀在命令之后，故取最后一个分隔符即可无歧义还原命令（命令内部的管道符不受影响）。
     * 格式不符返回 null（回落到范围评估/人工确认）。</p>
     */
    private static String extractCommand(String description) {
        if (description == null || !description.startsWith(CMD_DESC_PREFIX)) return null;
        int sep = description.lastIndexOf(CMD_DESC_DIR_SEP);
        if (sep < 0) return null;
        String cmd = description.substring(CMD_DESC_PREFIX.length(), sep).trim();
        return cmd.isEmpty() ? null : cmd;
    }

    /**
     * 尝试经风险评估智能体「目录内自动放行」。
     *
     * <p>两道关卡缺一不可：① 智能体判定 withinScope=true；② 智能体给出的受影响路径经**确定性**
     * 校验确实全部落在任务工作目录内（防注入/幻觉，模型一句话放行不算数）。命令仅在目录内运行而
     * 无显式路径时（affectedPaths 空）以智能体判定为准。任一关卡不过 → 返回 null 走人工。</p>
     *
     * @param workDir 评估基准目录（来源令牌自带的任务工作目录）；null 表示该任务未声明基准，不放行
     * @return 放行理由（用于日志/Toast）；不放行返回 null
     */
    private static String tryAutoApproveByScope(String toolName, String description, String workDir) {
        ToolScopeAssessor assessor = scopeAssessor;
        if (workDir == null || workDir.isBlank() || assessor == null) return null;
        try {
            ScopeVerdict v = assessor.assess(toolName, description, workDir);
            if (v == null || !v.withinScope()) return null;
            // 确定性兜底：智能体声称的受影响路径若有任一跳出工作目录，一律否决
            if (anyPathEscapes(v.affectedPaths(), workDir)) {
                log.info("[风险评估·否决] 受影响路径越界 tool={} paths={} workDir={}",
                        toolName, v.affectedPaths(), workDir);
                return null;
            }
            return v.reason().isBlank() ? "影响范围限于任务目录" : v.reason();
        } catch (Exception e) {
            log.warn("[风险评估] 自动放行判定异常，转人工: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 确定性判断：受影响路径中是否存在跳出工作目录的项。
     *
     * <p>相对路径相对 workDir 解析；统一 normalize 后做前缀包含判断。任何解析异常按"越界"处理（保守）。</p>
     */
    private static boolean anyPathEscapes(List<String> paths, String workDir) {
        if (paths == null || paths.isEmpty()) return false; // 无显式路径：交由智能体判定（命令仅在目录内运行）
        try {
            Path base = Path.of(workDir).toAbsolutePath().normalize();
            for (String raw : paths) {
                if (raw == null || raw.isBlank()) continue;
                Path target = Path.of(raw.trim());
                Path resolved = (target.isAbsolute() ? target : base.resolve(target))
                        .toAbsolutePath().normalize();
                if (!resolved.startsWith(base)) return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("[风险评估] 路径包含校验异常，按越界处理: {}", e.getMessage());
            return true;
        }
    }

    /** 风险等级的人类可读标签 */
    private static String riskLabel(ToolRiskLevel level) {
        return switch (level) {
            case NOTIFY -> "通知";
            case CONFIRM -> "高风险";
            case DOUBLE_CONFIRM -> "不可逆·高风险";
        };
    }

    /**
     * 发送一条非阻塞 Toast 通知（无阻塞等待）。
     *
     * <p>port 未注入时降级为日志输出。</p>
     */
    private static void notifyToast(String toolName, String description) {
        UserInteractionPort p = port;
        if (p != null) {
            p.notify(new ToastRequest(toolName, description));
        } else {
            log.info("工具通知（端口未就绪）：[{}] {}", toolName, description);
        }
    }

    /** 确认超时：托管任务来源放宽（半无人值守，短超时会被误判为拒绝），其余用默认。 */
    private static int timeoutSeconds(ToolCallOrigin origin) {
        AgentConfig cfg = AgentConfig.getInstance();
        return origin.isManagedTask()
                ? cfg.getConfirmationTimeoutManaged()
                : cfg.getConfirmationTimeoutDefault();
    }
}
