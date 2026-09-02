package com.javaclaw.runtime;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ToolIdentity;

/**
 * 模型产生的完整工具调用。
 *
 * @param callId Turn 内唯一调用 ID
 * @param tool 冻结工具身份与 revision
 * @param arguments 规范化参数
 */
public record ModelToolCall(String callId, ToolIdentity tool, CanonicalPayload arguments) {
    /** 校验工具调用。 */
    public ModelToolCall {
        callId = Objects.requireNonNull(callId, "callId").strip();
        if (callId.isEmpty()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(arguments, "arguments");
    }
}
