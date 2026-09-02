package com.javaclaw.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Prompt 来源的脱敏身份；不包含项目约定、Skill 或 Context 正文。
 *
 * @param kind 来源类别
 * @param sourceId 相对路径或稳定资源标识
 * @param revision 可选语义版本
 * @param digest 实际纳入内容的 SHA-256；读取失败时为空
 * @param includedBytes 实际纳入 Prompt 的 UTF-8 字节数
 * @param warnings 脱敏警告代码
 */
public record PromptSourceMetadata(
        PromptSourceKind kind,
        String sourceId,
        Optional<String> revision,
        Optional<String> digest,
        long includedBytes,
        List<String> warnings) {
    /** 校验来源身份并复制集合。 */
    public PromptSourceMetadata {
        Objects.requireNonNull(kind, "kind");
        sourceId = text(sourceId, "sourceId");
        revision = Objects.requireNonNull(revision, "revision").map(value -> text(value, "revision"));
        digest = Objects.requireNonNull(digest, "digest").map(value -> Preconditions.digest(value, "digest"));
        if (includedBytes < 0 || (digest.isEmpty() && includedBytes != 0)) {
            throw new IllegalArgumentException("prompt source included bytes are inconsistent");
        }
        warnings = Objects.requireNonNull(warnings, "warnings").stream()
                .map(value -> text(value, "warning"))
                .distinct()
                .toList();
    }

    private static String text(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
