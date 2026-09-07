package com.javaclaw.server.persistence;

/** data-v6 用例失败；显式类别避免 RPC 层从自然语言猜测错误语义。 */
public final class PersistenceException extends RuntimeException {
    private final Kind kind;

    /** 持久化失败对外可观察的稳定类别。 */
    public enum Kind {
        /** 服务端存储、映射或外部执行异常。 */
        INTERNAL,
        /** 请求引用不存在的资源或违反用例前置条件。 */
        INVALID_REQUEST,
        /** expected revision 与当前状态冲突。 */
        REVISION_CONFLICT,
        /** 幂等键被另一个请求占用。 */
        IDEMPOTENCY_CONFLICT
    }

    /**
     * 创建持久化异常。
     *
     * @param message 脱敏错误说明
     * @param cause 原因
     */
    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
        kind = Kind.INTERNAL;
    }

    /**
     * 创建不含底层异常的持久化错误。
     *
     * @param message 脱敏错误说明
     */
    public PersistenceException(String message) {
        super(message);
        kind = Kind.INTERNAL;
    }

    private PersistenceException(Kind kind, String message) {
        super(message);
        this.kind = java.util.Objects.requireNonNull(kind, "kind");
    }

    /**
     * 创建请求前置条件错误。
     *
     * @param message 脱敏说明
     * @return 分类异常
     */
    public static PersistenceException invalidRequest(String message) {
        return new PersistenceException(Kind.INVALID_REQUEST, message);
    }

    /**
     * 创建乐观锁冲突。
     *
     * @param message 脱敏说明
     * @return 分类异常
     */
    public static PersistenceException revisionConflict(String message) {
        return new PersistenceException(Kind.REVISION_CONFLICT, message);
    }

    /**
     * 创建幂等身份冲突。
     *
     * @param message 脱敏说明
     * @return 分类异常
     */
    public static PersistenceException idempotencyConflict(String message) {
        return new PersistenceException(Kind.IDEMPOTENCY_CONFLICT, message);
    }

    /**
     * 返回无需解析文本的稳定类别。
     *
     * @return 失败类别
     */
    public Kind kind() {
        return kind;
    }
}
