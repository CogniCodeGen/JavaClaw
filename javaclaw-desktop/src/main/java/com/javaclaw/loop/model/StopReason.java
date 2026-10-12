package com.javaclaw.loop.model;

/**
 * 停止原因（仅当 {@link Decision#STOP} 时有意义）。
 *
 * <p>每个原因自带一句人类可读描述，供状态事件与日志直接展示，避免在多处拼消息。</p>
 */
public enum StopReason {
    /** 用户取消或系统关闭。 */
    CANCELLED("用户取消或系统关闭"),
    /** 达到最大迭代轮数上限。 */
    MAX_ITERATIONS("达到最大迭代轮数上限"),
    /** 达到用量额度上限。 */
    TOKEN_BUDGET("达到用量额度上限"),
    /** 超过整体时间上限。 */
    WALLCLOCK_TIMEOUT("超过整体时间上限"),
    /** 连续多轮执行失败。 */
    CONSECUTIVE_FAILURE("连续多轮执行失败"),
    /** 当前子任务缺少有效的 Harness 决策或任务结果。 */
    PROTOCOL_VIOLATION("当前子任务缺少有效的结构化决策或任务结果"),
    /** 模型通过独立控制通道表明无法继续。 */
    HARNESS_BLOCKED("执行体通过结构化决策报告受阻"),
    /** 模型通过独立控制通道请求用户输入。 */
    HARNESS_NEEDS_INPUT("执行体通过结构化决策请求用户输入"),
    /** 连续多轮无进展，判定收敛不了。 */
    NO_PROGRESS("连续多轮无进展，判定收敛不了"),
    /** 循环执行过程中抛出异常。 */
    ERROR("循环执行异常");

    private final String description;

    StopReason(String description) {
        this.description = description;
    }

    /** 人类可读描述。 */
    public String description() {
        return description;
    }
}
