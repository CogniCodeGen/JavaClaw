package com.javaclaw.builtin.contracts;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Knowledge App Server 与独立 Worker 之间的私有二进制 framing 契约。 */
public final class KnowledgeWorkerProtocol {
    /** 当前唯一私有协议版本。 */
    public static final int VERSION = 1;

    /** 单个原始附件最大字节数。 */
    public static final int MAXIMUM_CONTENT_BYTES = 16 * 1024 * 1024;

    /** 单个 JSON 帧最大字节数。 */
    public static final int MAXIMUM_JSON_BYTES = 4 * 1024 * 1024;

    private KnowledgeWorkerProtocol() {}

    /**
     * JSON 命令帧；其后紧跟一帧原始附件字节。
     *
     * @param version 固定为 {@value #VERSION}
     * @param mediaType MIME 类型
     * @param digest 预期附件 SHA-256
     * @param contentBytes 后续二进制帧字节数
     * @param maxCharacters 最大提取字符数
     */
    public record Request(int version, String mediaType, String digest, int contentBytes, int maxCharacters) {
        /** 校验版本、摘要和边界。 */
        public Request {
            if (version != VERSION) {
                throw new IllegalArgumentException("Knowledge Worker protocol version is unsupported");
            }
            mediaType = ContractValidation.text(mediaType, "mediaType").toLowerCase(Locale.ROOT);
            digest = sha256(digest);
            if (contentBytes < 1 || contentBytes > MAXIMUM_CONTENT_BYTES) {
                throw new IllegalArgumentException("contentBytes exceeds Knowledge Worker limit");
            }
            if (maxCharacters < 1 || maxCharacters > 2_000_000) {
                throw new IllegalArgumentException("maxCharacters must be between 1 and 2000000");
            }
        }
    }

    /**
     * Worker JSON 响应。
     *
     * @param result 成功结果
     * @param error 脱敏稳定错误码
     */
    public record Response(Optional<KnowledgeContracts.ExtractionResult> result, Optional<String> error) {
        /** 保证结果与错误恰有一个。 */
        public Response {
            result = Objects.requireNonNull(result, "result");
            error = Objects.requireNonNull(error, "error").map(value -> ContractValidation.text(value, "error"));
            if (result.isPresent() == error.isPresent()) {
                throw new IllegalArgumentException("response must contain exactly one of result or error");
            }
        }

        /** 创建成功响应。 */
        public static Response success(KnowledgeContracts.ExtractionResult result) {
            return new Response(Optional.of(result), Optional.empty());
        }

        /** 创建失败响应。 */
        public static Response failure(String error) {
            return new Response(Optional.empty(), Optional.of(error));
        }
    }

    private static String sha256(String value) {
        String normalized = ContractValidation.text(value, "digest").toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("digest must be SHA-256 hex");
        }
        return normalized;
    }
}
