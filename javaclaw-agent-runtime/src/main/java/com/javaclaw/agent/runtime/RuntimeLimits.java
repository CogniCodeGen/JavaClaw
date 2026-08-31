package com.javaclaw.agent.runtime;

/**
 * Process-level concurrency ceilings. Configured values may only lower the built-in safety maximums.
 *
 * @param maxSubagentsPerParent 每父 Thread 的子智能体并发上限，范围 1 到 4
 * @param maxActiveTurnsPerWorkspace 每 Workspace 的活动 Turn 上限，范围 1 到 8
 */
public record RuntimeLimits(int maxSubagentsPerParent, int maxActiveTurnsPerWorkspace) {
    public static final int HARD_MAX_SUBAGENTS_PER_PARENT = 4;
    public static final int HARD_MAX_ACTIVE_TURNS_PER_WORKSPACE = 8;

    /** 校验配置只能收窄固定安全上限，不能通过增大数字绕过子智能体或工作区配额。 */
    public RuntimeLimits {
        if (maxSubagentsPerParent < 1 || maxSubagentsPerParent > HARD_MAX_SUBAGENTS_PER_PARENT) {
            throw new IllegalArgumentException(
                    "maxSubagentsPerParent must be between 1 and " + HARD_MAX_SUBAGENTS_PER_PARENT);
        }
        if (maxActiveTurnsPerWorkspace < 1 || maxActiveTurnsPerWorkspace > HARD_MAX_ACTIVE_TURNS_PER_WORKSPACE) {
            throw new IllegalArgumentException(
                    "maxActiveTurnsPerWorkspace must be between 1 and " + HARD_MAX_ACTIVE_TURNS_PER_WORKSPACE);
        }
    }

    /** 返回每父 Thread 最多 4 个子智能体、每 Workspace 最多 8 个活动 Turn 的默认限制。 */
    public static RuntimeLimits defaults() {
        return new RuntimeLimits(HARD_MAX_SUBAGENTS_PER_PARENT, HARD_MAX_ACTIVE_TURNS_PER_WORKSPACE);
    }
}
