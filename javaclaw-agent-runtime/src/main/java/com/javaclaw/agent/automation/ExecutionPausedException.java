package com.javaclaw.agent.automation;

/** 领域执行已保存检查点并暂停；Runtime 应标记 INTERRUPTED，不能伪装完成或自动重发副作用。 */
public final class ExecutionPausedException extends Exception {
    /** 使用面向用户的剩余条件结束当前 Turn；显式恢复会创建新 Turn。 */
    public ExecutionPausedException(String message) {
        super(message);
    }
}
