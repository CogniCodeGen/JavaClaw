package com.javaclaw.extension.spi;

import java.util.Objects;

import com.javaclaw.api.TurnId;

/**
 * Extension Job 等待平台 InputRequest 时提交的持久关联身份。
 *
 * @param requestId 权威 InputRequest ID
 * @param turnId InputRequest 所属 Turn；必须与权威记录一致
 */
public record ExtensionJobInputWait(String requestId, TurnId turnId) {
    /** 校验关联身份，禁止把自由文本带入平台关联表。 */
    public ExtensionJobInputWait {
        String normalized = Objects.requireNonNull(requestId, "requestId").strip();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("requestId contains unsupported characters");
        }
        requestId = normalized;
        Objects.requireNonNull(turnId, "turnId");
    }
}
