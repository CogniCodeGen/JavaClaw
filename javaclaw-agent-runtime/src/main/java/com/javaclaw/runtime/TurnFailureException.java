package com.javaclaw.runtime;

import java.util.Objects;

/** 可安全持久化为稳定错误码的 Turn 失败。 */
public final class TurnFailureException extends RuntimeException {
    private final String code;

    /**
     * 创建失败。
     *
     * @param code 稳定错误码
     * @param message 不包含 Secret 的用户可见说明
     */
    public TurnFailureException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code").strip();
        if (this.code.isEmpty()) {
            throw new IllegalArgumentException("code must not be blank");
        }
    }

    /**
     * 返回稳定错误码。
     *
     * @return 错误码
     */
    public String code() {
        return code;
    }
}
