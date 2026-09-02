package com.javaclaw.server.persistence;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.WriteCommand;

/**
 * 持久命令的幂等身份。
 *
 * @param method Protocol v2 方法
 * @param idempotencyKey 全局幂等键
 * @param expectedRevision 客户端期望版本
 * @param requestDigest expected revision 与 payload 的共同摘要
 */
public record CommandIdentity(String method, String idempotencyKey, long expectedRevision, String requestDigest) {
    /** 校验命令身份。 */
    public CommandIdentity {
        method = text(method, "method");
        idempotencyKey = text(idempotencyKey, "idempotencyKey");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        requestDigest = Objects.requireNonNull(requestDigest, "requestDigest").toLowerCase(java.util.Locale.ROOT);
        if (!requestDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestDigest must be SHA-256 hex");
        }
    }

    /**
     * 从 wire command 生成不会忽略 expected revision 的身份。
     *
     * @param method 方法名
     * @param command wire command
     * @param json 规范 JSON codec
     * @return 命令身份
     */
    public static CommandIdentity from(String method, WriteCommand command, CanonicalJson json) {
        Objects.requireNonNull(command, "command");
        CanonicalPayload digestInput = Objects.requireNonNull(json, "json")
                .encode(new DigestInput(command.expectedRevision(), command.payload()));
        return new CommandIdentity(method, command.idempotencyKey(), command.expectedRevision(), digestInput.sha256());
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private record DigestInput(long expectedRevision, CanonicalPayload payload) {}
}
