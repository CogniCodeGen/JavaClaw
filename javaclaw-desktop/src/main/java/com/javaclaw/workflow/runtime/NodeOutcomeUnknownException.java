package com.javaclaw.workflow.runtime;

/** 副作用可能已经发生，必须终止当前图执行并由用户核对结果。 */
public final class NodeOutcomeUnknownException extends RuntimeException {
    public NodeOutcomeUnknownException(String message, Throwable cause) {
        super("UNKNOWN_OUTCOME：" + message + "；请检查业务记录后再决定后续操作", cause);
    }
}
