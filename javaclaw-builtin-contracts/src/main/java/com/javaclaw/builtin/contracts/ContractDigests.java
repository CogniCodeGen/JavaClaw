package com.javaclaw.builtin.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** 内置 Definition 用于绑定人工决策内容的 SHA-256 工具。 */
public final class ContractDigests {
    private ContractDigests() {}

    /**
     * 计算 UTF-8 正文 SHA-256。
     *
     * @param content 正文
     * @return 64 位小写十六进制摘要
     */
    public static String sha256(String content) {
        try {
            byte[] bytes = Objects.requireNonNull(content, "content").getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    /**
     * 校验并规范化 SHA-256 十六进制摘要。
     *
     * @param digest 摘要
     * @param name 参数名
     * @return 小写摘要
     */
    public static String requireSha256(String digest, String name) {
        String normalized = Objects.requireNonNull(digest, name).strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
        return normalized;
    }

    static String requireMatch(String digest, String content, String name) {
        String normalized = requireSha256(digest, name);
        if (!normalized.equals(sha256(content))) {
            throw new IllegalArgumentException(name + " does not match content");
        }
        return normalized;
    }
}
