package com.javaclaw.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** MCP 契约使用的稳定摘要函数。 */
public final class McpHashes {
    private McpHashes() {}

    /**
     * 计算 UTF-8 文本 SHA-256。
     *
     * @param value 输入文本
     * @return 小写十六进制摘要
     */
    public static String sha256(String value) {
        try {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
