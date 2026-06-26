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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>托管任务场景下（通过 {@link #enterManagedTask()} 标记），确认超时从 60s 延长至 600s，
 * 避免长时任务因短超时被误判为拒绝。用户在弹窗里选择一次"同意全部"后，本任务内后续高风险
 * 工具调用直接放行（见 {@link #TASK_ALLOW_ALL}）。</p>
 *
 * <p>本类不再直接调用 JavaFX，所有 UI 交互均经 {@link UserInteractionPort}。应用启动时
 * 必须调用 {@link #setPort(UserInteractionPort)} 注入具体实现。</p>
 */
public class ToolConfirmationManager {

    private static final Logger log = LoggerFactory.getLogger(ToolConfirmationManager.class);

    /** 二次确认时要求用户输入的关键词 */
    private static final String DOUBLE_CONFIRM_KEYWORD = "确认";

    /** 全局开关：是否启用确认机制 */
    private static volatile boolean enabled = true;

    /** 托管任务激活计数，>0 视为处于托管场景（放宽超时） */
    private static final AtomicInteger MANAGED_TASK_DEPTH = new AtomicInteger(0);

    /** 当前托管任务 ID，用于绑定任务级"同意全部"白名单；非托管场景为 null */
    private static volatile String currentTaskId;

    /** 当前托管任务的工作目录绝对路径，作为风险评估"影响范围"的基准；非托管或未提供为 null */
    private static volatile String currentTaskWorkDir;

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
     * 不再弹窗（包括不同工具名、不同参数）。仅在任务真正终结（COMPLETED / FAILED /
     * CANCELLED / 删除）时由 SddTaskManager 显式调用 {@link #clearTaskAllowlist(String)}
     * 清空——续跑态（NEEDS_HUMAN → resume）需要保留授权，否则用户会被反复弹窗骚扰。</p>
     */
    private static final Set<String> TASK_ALLOW_ALL = ConcurrentHashMap.newKeySet();

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

    /** 标记当前进入托管任务场景（SDD 编排器执行前调用） */
    public static void enterManagedTask() {
        MANAGED_TASK_DEPTH.incrementAndGet();
    }

    /**
     * 标记进入托管任务场景并绑定任务 ID，用于绑定任务级"同意全部"白名单。
     *
     * <p>仅最外层调用者绑定，嵌套 {@link #enterManagedTask()} 不覆盖 taskId。</p>
     */
    public static void enterManagedTask(String taskId) {
        enterManagedTask(taskId, null);
    }

    /**
     * 标记进入托管任务场景并绑定任务 ID 与工作目录。
     *
     * <p>workDir 作为风险评估"影响范围"的基准；仅最外层调用者绑定，嵌套不覆盖。</p>
     */
    public static void enterManagedTask(String taskId, String workDir) {
        if (MANAGED_TASK_DEPTH.getAndIncrement() == 0) {
            currentTaskId = taskId;
            currentTaskWorkDir = (workDir == null || workDir.isBlank()) ? null : workDir;
        }
    }

    /**
     * 退出托管任务场景（与 {@link #enterManagedTask()} 成对调用）。
     *
     * <p>仅在 depth 归 0 时清掉 {@link #currentTaskId} 绑定，但**不**清白名单：
     * 任务在 EXECUTING → CHALLENGING → PLANNING → EXECUTING 重规划循环中
     * 会反复进出 EXECUTING（每轮 finally 调用本方法），白名单必须跨轮保留。
     * 真正的清理由 {@link #clearTaskAllowlist(String)} 在任务终结时执行。</p>
     */
    public static void exitManagedTask() {
        int after = MANAGED_TASK_DEPTH.updateAndGet(v -> Math.max(0, v - 1));
        if (after == 0) {
            currentTaskId = null;
            currentTaskWorkDir = null;
        }
    }

    /** 显式清除某个任务的"同意全部"授权（任务取消/失败/完成/删除时由 SddTaskManager 调用） */
    public static void clearTaskAllowlist(String taskId) {
        if (taskId != null) TASK_ALLOW_ALL.remove(taskId);
    }

    /** 当前任务是否已被"同意全部"授权 */
    private static boolean isTaskAllowAll(String taskId) {
        return taskId != null && TASK_ALLOW_ALL.contains(taskId);
    }

    /** 把当前任务标记为"同意全部"——后续所有高风险工具调用一律放行 */
    private static void recordAllowAll(String taskId) {
        if (taskId != null) TASK_ALLOW_ALL.add(taskId);
    }

    /** 是否至少有一个托管任务正在运行 */
    public static boolean isInManagedTask() {
        return MANAGED_TASK_DEPTH.get() > 0;
    }

    /**
     * 检查指定工具是否需要确认。
     *
     * <p>保留用于向后兼容：只要工具在注册表内（任意等级）即返回 true，
     * 由 {@link #requestConfirmation(String, String)} 内部按等级分派处理。</p>
     */
    public static boolean requiresConfirmation(String toolName) {
        return enabled && ToolRiskRegistry.isManaged(toolName);
    }

    /**
     * 请求用户确认（阻塞调用线程直到用户响应或超时）。
     *
     * @param toolName    工具名称
     * @param description 操作描述
     * @return true=放行，false=拒绝
     */
    public static boolean requestConfirmation(String toolName, String description) {
        if (!enabled) return true;
        ToolRiskLevel level = ToolRiskRegistry.levelOf(toolName);
        if (level == null) return true;

        // 0. 任务级"同意全部"授权：命中即静默放行所有工具，连 Toast 都不弹避免打扰
        String taskId = currentTaskId;
        if (isTaskAllowAll(taskId)) {
            log.info("[任务·同意全部] tool={} desc={}", toolName, description);
            return true;
        }

        UserInteractionPort p = port;
        if (p == null || !p.isAvailable()) {
            log.warn("UserInteractionPort 未就绪，拒绝工具调用: {}", toolName);
            return false;
        }

        int timeoutSec = timeoutSeconds();
        boolean managed = isInManagedTask();

        // NOTIFY 直接放行（仅 Toast 通知）；CONFIRM / DOUBLE_CONFIRM 走三态弹窗
        if (level == ToolRiskLevel.NOTIFY) {
            p.notify(new ToastRequest(toolName, description));
            return true;
        }

        // 风险评估智能体「目录内自动放行」：仅在托管任务内、目录作用域工具、评估器就绪、开关开启时尝试。
        // 智能体判定影响范围限于任务工作目录、且其给出的受影响路径经确定性校验确实全部落在目录内 → 免人工。
        if (managed && ToolRiskRegistry.isDirScopedTool(toolName)
                && AgentConfig.getInstance().isTaskRiskAutoApproveEnabled()) {
            // 0) 确定性只读命令直接放行：零副作用，越界读取（如 ls ~/.m2）也无需人工，且省一次范围评估调用。
            //    无人值守时这类命令走人工确认只会等满超时按拒绝处理，浪费时间且诱发执行体重试。
            if ("cmd_execute".equals(toolName)) {
                String cmd = extractCommand(description);
                if (cmd != null && ReadOnlyCommands.isReadOnly(cmd)) {
                    log.info("[只读命令·自动放行] cmd={}", cmd);
                    p.notify(new ToastRequest(toolName, "已自动放行（只读命令，无副作用）：" + cmd));
                    return true;
                }
            }
            String autoReason = tryAutoApproveByScope(toolName, description);
            if (autoReason != null) {
                log.info("[风险评估·自动放行] tool={} workDir={} desc={} reason={}",
                        toolName, currentTaskWorkDir, description, autoReason);
                p.notify(new ToastRequest(toolName, "已自动放行（影响范围限于任务目录）：" + autoReason));
                return true;
            }
        }

        ConfirmKind kind = (level == ToolRiskLevel.DOUBLE_CONFIRM)
                ? ConfirmKind.DOUBLE_CONFIRM : ConfirmKind.CONFIRM;
        ConfirmDecision decision = p.confirmEx(new ConfirmRequest(
                toolName, riskLabel(level), description,
                kind, timeoutSec,
                kind == ConfirmKind.DOUBLE_CONFIRM ? DOUBLE_CONFIRM_KEYWORD : "",
                managed));

        if (decision == ConfirmDecision.ALLOW_ALL && taskId != null) {
            recordAllowAll(taskId);
            log.info("[同意全部] taskId={} 已开启全部放行，后续高风险工具调用不再弹窗", taskId);
            notifyToast(toolName, "已开启本任务「全部放行」，所有后续高风险操作将自动执行");
        }
        return decision.isAllow();
    }

    /**
     * 从 cmd_execute 的确认描述中解析出命令文本。
     *
     * <p>描述由 {@code CommandLineTools.checkBeforeExec} 固定拼为 {@code "命令: <cmd> | 目录: <dir>"}，
     * 目录后缀在末尾，故取最后一个分隔符即可无歧义还原命令（命令内部的管道符不受影响）。
     * 格式不符返回 null（回落到范围评估/人工确认）。</p>
     */
    private static String extractCommand(String description) {
        if (description == null || !description.startsWith("命令: ")) return null;
        int sep = description.lastIndexOf(" | 目录: ");
        if (sep < 0) return null;
        String cmd = description.substring("命令: ".length(), sep).trim();
        return cmd.isEmpty() ? null : cmd;
    }

    /**
     * 尝试经风险评估智能体「目录内自动放行」。
     *
     * <p>两道关卡缺一不可：① 智能体判定 withinScope=true；② 智能体给出的受影响路径经**确定性**
     * 校验确实全部落在任务工作目录内（防注入/幻觉，模型一句话放行不算数）。命令仅在目录内运行而
     * 无显式路径时（affectedPaths 空）以智能体判定为准。任一关卡不过 → 返回 null 走人工。</p>
     *
     * @return 放行理由（用于日志/Toast）；不放行返回 null
     */
    private static String tryAutoApproveByScope(String toolName, String description) {
        String workDir = currentTaskWorkDir;
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

    private static int timeoutSeconds() {
        AgentConfig cfg = AgentConfig.getInstance();
        return isInManagedTask()
                ? cfg.getConfirmationTimeoutManaged()
                : cfg.getConfirmationTimeoutDefault();
    }
}
