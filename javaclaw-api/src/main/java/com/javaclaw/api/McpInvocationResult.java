package com.javaclaw.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * MCP Tool 调用结果。
 *
 * @param successful Tool 是否成功
 * @param payload 规范结果
 * @param remoteRequestId 可选脱敏远端请求标识
 * @param progress 已校验且与本次请求精确关联的进度通知
 */
public record McpInvocationResult(
        boolean successful, CanonicalPayload payload, Optional<String> remoteRequestId, List<McpProgress> progress) {
    /** 校验结果。 */
    public McpInvocationResult {
        Objects.requireNonNull(payload, "payload");
        remoteRequestId = Objects.requireNonNull(remoteRequestId, "remoteRequestId")
                .map(value -> Preconditions.text(value, "remoteRequestId"));
        progress = List.copyOf(progress);
    }
}
