package com.javaclaw.server.turn;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 读取随 App Server 审阅和版本化的 Agent Role 优化说明。 */
public final class PromptOptimizationInstruction {
    /** 持久化到优化任务 provenance 的稳定版本。 */
    public static final String REVISION = "role-optimization-v1";

    private static final String RESOURCE = "/prompts/role-optimization-v1.txt";
    private final String content;
    private final String digest;

    private PromptOptimizationInstruction(String content) {
        this.content = content;
        digest = digest(content);
    }

    /**
     * 读取内置说明并计算摘要。
     *
     * @return 已校验说明
     */
    public static PromptOptimizationInstruction load() {
        try (InputStream input = PromptOptimizationInstruction.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing Prompt optimization instruction: " + RESOURCE);
            }
            String value = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (value.isEmpty()) {
                throw new IllegalStateException("Prompt optimization instruction must not be blank");
            }
            return new PromptOptimizationInstruction(value);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read Prompt optimization instruction", failure);
        }
    }

    /**
     * 构造只引用精确 Role 的用户消息；源正文由正常 Prompt manifest 提供。
     *
     * @param roleId Role 标识
     * @param roleRevision Role revision
     * @return 普通 Turn 用户消息
     */
    public String message(String roleId, long roleRevision) {
        return content + "\n\n目标 Agent Role：" + roleId + "@" + roleRevision + "。";
    }

    /** @return 内置说明 SHA-256 */
    public String digest() {
        return digest;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
