package com.javaclaw.extension.spi;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.TurnId;

/** 携带已持久 Turn 证据的编排单元失败，便于 Job 失败关闭后诊断。 */
public final class OrchestratedTurnFailureException extends Exception {
    private static final long serialVersionUID = 1L;

    private final TurnId turnId;
    private final String errorCode;
    private final Optional<String> effectReceiptKey;

    /**
     * 创建不可继续的 Turn 失败。
     *
     * @param turnId 已保留恢复证据的 Turn
     * @param errorCode 脱敏稳定错误码
     * @param effectReceiptKey 已提交的最后一个副作用幂等键
     */
    public OrchestratedTurnFailureException(TurnId turnId, String errorCode, Optional<String> effectReceiptKey) {
        super("编排 Turn 未完成: " + requireCode(errorCode));
        this.turnId = Objects.requireNonNull(turnId, "turnId");
        this.errorCode = requireCode(errorCode);
        this.effectReceiptKey = Objects.requireNonNull(effectReceiptKey, "effectReceiptKey")
                .map(value -> {
                    String normalized = value.strip();
                    if (normalized.isEmpty() || normalized.length() > 500) {
                        throw new IllegalArgumentException("effectReceiptKey length is invalid");
                    }
                    return normalized;
                });
    }

    /**
     * 返回证据 Turn。
     *
     * @return Turn ID
     */
    public TurnId turnId() {
        return turnId;
    }

    /**
     * 返回稳定错误码。
     *
     * @return 错误码
     */
    public String errorCode() {
        return errorCode;
    }

    /**
     * 返回已提交副作用的最后一个幂等键。
     *
     * @return 无副作用凭据时为空
     */
    public Optional<String> effectReceiptKey() {
        return effectReceiptKey;
    }

    private static String requireCode(String value) {
        String normalized = Objects.requireNonNull(value, "errorCode").strip();
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,159}")) {
            throw new IllegalArgumentException("errorCode contains unsupported characters");
        }
        return normalized;
    }
}
