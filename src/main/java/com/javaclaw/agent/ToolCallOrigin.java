package com.javaclaw.agent;

/**
 * 工具调用来源令牌：高风险工具确认的归属凭据。
 *
 * <p>每条编排路径在<b>装配工具实例时</b>绑定自己的来源令牌，工具发起确认时原样传给
 * {@link ToolConfirmationManager}——归属从此是构造期的<b>事实</b>，而非确认管理器
 * 靠静态计数器/场景栈做的运行时<b>推断</b>。旧推断机制的三面漏风（交互调用借任务
 * 「同意全部」白名单、定时任务路径无登记照借授权、交互在飞时误挂起已授权任务）
 * 均源于「静态状态辨不出调用来源」，令牌化后不复存在。</p>
 *
 * <p>各来源的确认待遇（SMART 审核模式下）：</p>
 * <ul>
 *   <li>{@link Kind#INTERACTIVE} 交互路径（聊天/规划）——用户在场：逐次确认，
 *       无任务白名单、无目录范围自动放行、默认超时；</li>
 *   <li>{@link Kind#MANAGED_TASK} 托管任务（SDD/循环）——半无人值守：可命中本任务的
 *       「同意全部」白名单、可经目录范围评估自动放行（以 {@link #workDir} 为基准）、
 *       确认超时放宽；</li>
 *   <li>{@link Kind#SCHEDULED} 定时任务——无人值守：默认无白名单、无自动放行（确定性只读命令
 *       白名单除外，其放行与来源无关）；但用户可在定时任务设置里对<b>单个任务显式授权</b>
 *       「允许无人值守执行高风险工具」，授权后该任务定时执行期内本次 run 令牌的确认自动放行。
 *       令牌逐 run 全新构造，确认层按<b>令牌实例身份（==）</b>匹配当前授权窗——即便上一次
 *       执行超时后残存僵尸线程存活到下个授权窗（包括同一任务的下个 tick，taskId 完全相同），
 *       其持有的旧实例也对不上，结构上不可能借授权（见
 *       {@code ToolConfirmationManager.beginAuthorizedScheduledRun}）；</li>
 *   <li>{@link Kind#UNKNOWN} 未知来源（未迁移调用点/外部扩展的兜底）——按最保守处理，
 *       待遇同 INTERACTIVE。</li>
 * </ul>
 *
 * @param kind    来源类别
 * @param taskId  托管任务 ID（「同意全部」白名单的归属键）；非托管来源为 null
 * @param workDir 托管任务工作目录（目录范围评估基准）；未声明为 null（不做目录放行）
 */
public record ToolCallOrigin(Kind kind, String taskId, String workDir) {

    /** 来源类别。 */
    public enum Kind {
        /** 交互路径（聊天/规划模式编排器及其子智能体）。 */
        INTERACTIVE,
        /** 托管任务（SDD 任务、循环），带任务 ID 与可选工作目录。 */
        MANAGED_TASK,
        /** 定时任务（无人值守 tick）。 */
        SCHEDULED,
        /** 未知来源（兜底，最保守）。 */
        UNKNOWN
    }

    /** 交互路径单例。 */
    public static final ToolCallOrigin INTERACTIVE = new ToolCallOrigin(Kind.INTERACTIVE, null, null);
    /**
     * 定时任务单例（<b>无 taskId 的兜底</b>：不参与任何授权窗匹配，永远逐次确认）。
     * 定时执行路径应一律用 {@link #scheduled(String)} 逐 run 绑定任务 ID；此单例仅供
     * 与具体某次执行无关的定时基础设施（如完成通知发送）使用。
     */
    public static final ToolCallOrigin SCHEDULED = new ToolCallOrigin(Kind.SCHEDULED, null, null);
    /** 未知来源单例（未迁移调用点的默认值）。 */
    public static final ToolCallOrigin UNKNOWN = new ToolCallOrigin(Kind.UNKNOWN, null, null);

    /**
     * 定时任务来源（逐 run 构建）：绑定本次执行的定时任务 ID。
     *
     * <p>确认层按<b>令牌实例身份（==）</b>匹配当前授权窗自动放行——每次调用本工厂都产生
     * 新实例，上一次执行残存的僵尸线程（旧 run 的实例，即便同任务同 taskId）对不上新授权窗，
     * 结构上无法串染。授权窗令牌统一由 {@code ToolConfirmationManager.beginAuthorizedScheduledRun}
     * 构造并返回给调用方装配工具。</p>
     *
     * @param taskId 本次执行的定时任务 ID（空白退化为无 taskId 的 {@link #SCHEDULED} 单例语义）
     */
    public static ToolCallOrigin scheduled(String taskId) {
        return new ToolCallOrigin(Kind.SCHEDULED,
                (taskId == null || taskId.isBlank()) ? null : taskId, null);
    }

    /**
     * 托管任务来源。
     *
     * @param taskId  任务 ID（不可为空——白名单归属键）
     * @param workDir 工作目录；空白视为未声明（该任务不做目录范围自动放行）
     */
    public static ToolCallOrigin managedTask(String taskId, String workDir) {
        return new ToolCallOrigin(Kind.MANAGED_TASK, taskId,
                (workDir == null || workDir.isBlank()) ? null : workDir);
    }

    /** 是否托管任务来源（白名单/目录放行/放宽超时仅对它开放）。 */
    public boolean isManagedTask() {
        return kind == Kind.MANAGED_TASK && taskId != null;
    }

    /**
     * 浏览器认证隔离作用域。交互聊天会在收到 {@code ConversationRequest.sessionId} 后覆盖为
     * {@code conversation:<id>}；无人值守路径使用稳定任务 ID，便于为该任务显式绑定站点账号。
     */
    public String browserScopeId() {
        return switch (kind) {
            case MANAGED_TASK -> "managed:" + (taskId == null ? "default" : taskId);
            case SCHEDULED -> "scheduled:" + (taskId == null ? "default" : taskId);
            case INTERACTIVE -> "interactive:default";
            case UNKNOWN -> "unknown:default";
        };
    }
}
