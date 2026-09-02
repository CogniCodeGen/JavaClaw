package com.javaclaw.api;

import java.util.Objects;
import java.util.Optional;

/**
 * 工具治理层返回的规范结果。
 *
 * @param callId 对应调用 ID
 * @param success 是否成功
 * @param output 脱敏后的规范化输出
 * @param receipt 副作用凭据；无副作用时为空
 */
public record ToolCallResult(String callId, boolean success, CanonicalPayload output, Optional<EffectReceipt> receipt) {
    /** 校验调用 ID 与结果。 */
    public ToolCallResult {
        callId = Preconditions.text(callId, "callId");
        Objects.requireNonNull(output, "output");
        receipt = Objects.requireNonNull(receipt, "receipt");
    }
}
