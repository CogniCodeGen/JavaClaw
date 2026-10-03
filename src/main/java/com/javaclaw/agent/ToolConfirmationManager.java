package com.javaclaw.agent;

import com.javaclaw.api.interaction.ConfirmDecision;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ChoiceOption;
import com.javaclaw.api.interaction.ChoiceRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.ToolReviewMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 传入——托管任务来源可命中本任务的「同意全部」白名单（{@link #TASK_ALLOW_ALL}）、
     * 确认超时放宽（60s→600s）；交互/定时/未知来源一律逐次确认。归属是构造期
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
     * 才配得上这种升级；AUTO 总闸/授权窗/只读命令的自动放行落库等于替用户做了从未作出的授权。
     */
    public enum ConfirmOutcome {
        /** 拒绝（人工拒绝 / 超时无应答 / 端口未就绪）。 */
        DENIED,
        /** 策略自动放行（AUTO 总闸 / 定时授权窗 / 任务白名单 / 只读命令 / NOTIFY 直放）。 */
        ALLOWED_AUTO,
        /** 用户在确认弹窗中人工点击允许。 */
        ALLOWED_HUMAN;

        public boolean isAllow() {
            return this != DENIED;
        }
    }

    /** 全局开关：是否启用确认机制 */
    private static volatile boolean enabled = true;
    private static volatile AgentConfig settings;

    /** 由根 Spring Context 注入当前工作区配置；切换工作区时同一实例会重新加载。 */
    public static void configure(AgentConfig config) {
        settings = java.util.Objects.requireNonNull(config, "config");
    }

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
     * {@link #beginAuthorizedScheduledRun} 逐 run 全新构造并交给统一 Schedule Run 适配器
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
     * <p>返回的令牌须随本次 Schedule Run 提交——授权窗与工具
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
     * <p>只要工具在注册表内（任意等级）即返回 true，
     * 由 {@link #requestConfirmation(ToolCallOrigin, String, String)} 内部按等级分派处理。</p>
     */
    public static boolean requiresConfirmation(String toolName) {
        if (ToolRiskRegistry.isKnownHostReadOnly(toolName)) return false;
        if (!ToolRiskRegistry.isManaged(toolName)
                && !ToolRiskRegistry.isKnownHostTool(toolName)) return true;
        if (!enabled) return false;
        return reviewMode() != ToolReviewMode.AUTO;
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
        return requestConfirmationOutcome(origin, toolName, description).isAllow();
    }

    /** Runs the normal confirmation policy while retaining whether a human explicitly approved. */
    public static ConfirmOutcome requestConfirmationOutcome(
            ToolCallOrigin origin, String toolName, String description) {
        return confirmInternal(origin == null ? ToolCallOrigin.UNKNOWN : origin,
                toolName, description, false);
    }

    public static ConfirmOutcome requestConfirmationOutcome(
            ToolCallOrigin origin, String toolName, String description,
            java.util.Map<String, String> operationParameters) {
        return confirmInternal(origin == null ? ToolCallOrigin.UNKNOWN : origin,
                toolName, description, false, operationParameters);
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

    public static ConfirmOutcome requestHighRiskCommandConfirmation(
            ToolCallOrigin origin, String toolName, String description,
            String command, String workingDirectory) {
        return confirmInternal(origin == null ? ToolCallOrigin.UNKNOWN : origin,
                toolName, description, true,
                java.util.Map.of("command", command == null ? "" : command,
                        "workDir", workingDirectory == null ? "" : workingDirectory));
    }

    /**
     * 独立确认（阻塞）：与任何托管任务<b>无关</b>的一次性授权（如循环启动前的验证命令确认），
     * 不吃任务白名单、不提供「同意全部」选项。
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
     * 请求一次不可被审核模式、任务白名单或“关闭工具确认”开关跳过的显式用户答复。
     *
     * <p>这不是风险审核入口，而是业务流程本身必须等待用户完成的交互，例如“请在已打开的
     * 浏览器中登录，完成后点击同意”或“是否保存刚取得的登录会话”。因此即使用户选择了
     * AUTO 审核，也不能把该答复推断为同意。</p>
     *
     * @param actionName    展示给用户的动作名称
     * @param description   操作说明
     * @param timeoutSeconds 等待秒数；小于等于 0 时由 UI 使用其兜底值
     * @return true=用户明确同意，false=拒绝、超时或 UI 不可用
     */
    public static boolean requestExplicitUserConfirmation(
            String actionName, String description, int timeoutSeconds) {
        UserInteractionPort p = port;
        if (p == null || !p.isAvailable()) {
            log.warn("UserInteractionPort 未就绪，无法等待用户答复: {}", actionName);
            return false;
        }
        try {
            ConfirmDecision decision = p.confirmEx(new ConfirmRequest(
                    actionName,
                    "需要用户操作",
                    description,
                    ConfirmKind.CONFIRM,
                    timeoutSeconds,
                    "",
                    false));
            return decision.isAllow();
        } catch (Exception e) {
            log.warn("等待用户显式答复失败 [{}]: {}", actionName, e.getMessage());
            return false;
        }
    }

    /**
     * 请求一次不可被审核模式或任务授权跳过的显式单选。
     *
     * @return 选项稳定 ID；取消、超时或 UI 不支持时返回 {@code null}
     */
    public static String requestExplicitUserChoice(
            String title, String description, List<ChoiceOption> options, int timeoutSeconds) {
        UserInteractionPort p = port;
        if (p == null || !p.isAvailable()) {
            log.warn("UserInteractionPort 未就绪，无法等待用户选择: {}", title);
            return null;
        }
        try {
            return p.choose(new ChoiceRequest(title, description, options, timeoutSeconds));
        } catch (Exception e) {
            log.warn("等待用户显式选择失败 [{}]: {}", title, e.getMessage());
            return null;
        }
    }

    /**
     * 确认核心（唯一实现，所有公开入口收敛于此）。
     *
     * <p>SMART 模式下的放行漏斗，按序命中即定：AUTO 总闸 → 本任务「同意全部」白名单
     * （仅托管任务令牌）→ NOTIFY Toast 直放 → 确定性只读命令直放（仅托管任务令牌的
     * 目录作用域工具）→ 人工弹窗。</p>
     *
     * @param humanGateInAuto true 表示 AUTO 总闸对本次确认不生效（高风险 shell 命令的人工底线，
     *                        见 {@link #requestHighRiskCommandConfirmation}），漏斗其余环节照常
     */
    private static ConfirmOutcome confirmInternal(ToolCallOrigin origin, String toolName,
                                                  String description, boolean humanGateInAuto) {
        return confirmInternal(origin, toolName, description, humanGateInAuto, java.util.Map.of());
    }

    private static ConfirmOutcome confirmInternal(ToolCallOrigin origin, String toolName,
            String description, boolean humanGateInAuto,
            java.util.Map<String, String> operationParameters) {
        if (ToolRiskRegistry.isKnownHostReadOnly(toolName)) return ConfirmOutcome.ALLOWED_AUTO;
        ToolRiskLevel level = ToolRiskRegistry.levelOf(toolName);
        if (level == null && !ToolRiskRegistry.isKnownHostTool(toolName)) {
            log.warn("拒绝未登记的工具调用: {}", toolName);
            return ConfirmOutcome.DENIED;
        }
        var frameworkGrant = com.javaclaw.framework.api.ToolApprovalScope.current()
                .filter(grant -> grant.approved()
                        && grant.tool().equals(toolName));
        if (frameworkGrant.isPresent()) {
            return frameworkGrant.get().humanApproved()
                    ? ConfirmOutcome.ALLOWED_HUMAN : ConfirmOutcome.ALLOWED_AUTO;
        }
        if (!enabled) return ConfirmOutcome.ALLOWED_AUTO;
        if (level == null) level = ToolRiskLevel.CONFIRM;

        ToolReviewMode reviewMode = reviewMode();
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

        ConfirmKind kind = (effectiveLevel == ToolRiskLevel.DOUBLE_CONFIRM)
                ? ConfirmKind.DOUBLE_CONFIRM : ConfirmKind.CONFIRM;
        // 「同意全部」选项只对托管任务令牌展示：授权登记落到令牌自带的 taskId，
        // 其它来源展示该按钮会让用户点了却被静默丢弃，比多弹几次确认更糟
        boolean offerAllowAll = managedTask && !manualReview;
        ConfirmDecision decision = p.confirmEx(new ConfirmRequest(
                toolName, riskLabel(effectiveLevel), description,
                kind, timeoutSeconds(origin),
                kind == ConfirmKind.DOUBLE_CONFIRM ? DOUBLE_CONFIRM_KEYWORD : "",
                offerAllowAll, operationParameters));

        if (decision == ConfirmDecision.ALLOW_ALL && offerAllowAll) {
            recordAllowAll(origin.taskId());
            log.info("[同意全部] taskId={} 已开启全部放行，后续高风险工具调用不再弹窗", origin.taskId());
            notifyToast(toolName, "已开启本任务「全部放行」，所有后续高风险操作将自动执行");
        }
        return decision.isAllow() ? ConfirmOutcome.ALLOWED_HUMAN : ConfirmOutcome.DENIED;
    }

    /** Command details are display only; no authorization decision parses this text. */
    private static final String CMD_DESC_PREFIX = "命令: ";
    /** cmd_execute 确认描述中命令与目录的分隔标记。 */
    private static final String CMD_DESC_DIR_SEP = " | 目录: ";

    /** Format a human-readable command description. */
    public static String buildCommandDescription(String command, String dir) {
        return CMD_DESC_PREFIX + command + CMD_DESC_DIR_SEP + dir;
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
        AgentConfig config = settings;
        if (config == null) {
            return origin.isManagedTask() ? 600 : 60;
        }
        return origin.isManagedTask()
                ? config.getConfirmationTimeoutManaged()
                : config.getConfirmationTimeoutDefault();
    }

    private static ToolReviewMode reviewMode() {
        AgentConfig config = settings;
        return config == null ? ToolReviewMode.SMART : config.getToolReviewMode();
    }

}
