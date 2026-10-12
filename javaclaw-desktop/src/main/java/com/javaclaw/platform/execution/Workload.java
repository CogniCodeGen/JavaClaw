package com.javaclaw.platform.execution;

/** 明确选择后台任务使用的资源池，禁止依赖隐式或默认执行器。 */
public enum Workload {
    IO,
    CPU,
    BROWSER,
    PROCESS
}
