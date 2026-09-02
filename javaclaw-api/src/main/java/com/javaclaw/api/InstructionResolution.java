package com.javaclaw.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一次 Turn 启动时解析出的项目约定清单；正文永远不进入管理 RPC。
 *
 * @param sources 按实际 Prompt 顺序排列的来源结果
 * @param digest 来源身份与有效内容的总 SHA-256
 * @param globalIncludedBytes 全局层实际纳入的 UTF-8 字节数
 * @param projectIncludedBytes 项目层实际纳入的 UTF-8 字节数
 * @param warnings 脱敏警告代码
 * @param resolvedAt 解析时间
 */
public record InstructionResolution(
        List<InstructionSourceResolution> sources,
        String digest,
        long globalIncludedBytes,
        long projectIncludedBytes,
        List<String> warnings,
        Instant resolvedAt) {
    /** 校验清单不可变性和汇总字节数。 */
    public InstructionResolution {
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        digest = Preconditions.digest(digest, "digest");
        if (globalIncludedBytes < 0 || projectIncludedBytes < 0) {
            throw new IllegalArgumentException("instruction included bytes must not be negative");
        }
        long actualGlobal = includedBytes(sources, InstructionScope.GLOBAL);
        long actualProject = includedBytes(sources, InstructionScope.PROJECT);
        if (globalIncludedBytes != actualGlobal || projectIncludedBytes != actualProject) {
            throw new IllegalArgumentException("instruction included byte totals do not match sources");
        }
        warnings = Objects.requireNonNull(warnings, "warnings").stream()
                .map(value -> Preconditions.identifier(value, "warning"))
                .distinct()
                .toList();
        Objects.requireNonNull(resolvedAt, "resolvedAt");
    }

    private static long includedBytes(List<InstructionSourceResolution> sources, InstructionScope scope) {
        return sources.stream()
                .filter(source -> source.scope() == scope)
                .mapToLong(InstructionSourceResolution::includedBytes)
                .sum();
    }
}
