package com.javaclaw.agent.prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** UTF-8 内容寻址；摘要只用于版本追踪，不作为权限或来源认证。 */
public final class PromptHashes {
    private PromptHashes() {}

    /** 返回原文（不规范化空白）的 SHA-256，便于保留用户原始内容并检测变化。 */
    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 返回原始字节的 SHA-256；用于记录截断或无效 UTF-8 文件实际采用的字节窗口。 */
    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** 对有序字段作长度前缀编码后求摘要；字段含换行或分隔符也不会发生拼接歧义。 */
    public static String sequence(List<String> fields) {
        StringBuilder encoded = new StringBuilder();
        for (String field : fields) {
            encoded.append(field.length()).append(':').append(field);
        }
        return sha256(encoded.toString());
    }
}
