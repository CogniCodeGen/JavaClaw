package com.javaclaw.nativehost.coding;

import java.util.List;
import java.util.Objects;

/**
 * 受限扫描的完整文件摘要，不携带源文件内容或对文件系统一致性的承诺。
 *
 * @param entries 已完整读取且读取前后身份一致的文件；路径相对冻结 Workspace 根
 * @param scannedBytes 实际读取的累计字节数，包括最终因变更丢弃的文件
 * @param truncated 是否因预算、深度、链接、权限或并发变更留下未覆盖范围
 * @param excludedDirectories 按目录排除规则实际跳过的相对路径，不包含未遍历到的目录
 */
public record WorkspaceInventory(
        List<Entry> entries, long scannedBytes, boolean truncated, List<String> excludedDirectories) {
    /** 构造不可变结果，累计读取量不得为负数。 */
    public WorkspaceInventory {
        entries = List.copyOf(entries);
        excludedDirectories = List.copyOf(excludedDirectories);
        if (scannedBytes < 0) {
            throw new IllegalArgumentException("scannedBytes must be non-negative");
        }
    }

    /**
     * 单个普通文件的完整 SHA-256。
     *
     * @param path 根内相对路径，不可空
     * @param sizeBytes 完整文件字节数，非负
     * @param sha256 64 位小写十六进制摘要，不可空
     */
    public record Entry(String path, long sizeBytes, String sha256) {
        /** 校验路径和完整摘要，防止部分读取伪装成文件证据。 */
        public Entry {
            WorkspaceFileProtocol.requireRelative(path, false);
            Objects.requireNonNull(sha256, "sha256");
            if (sizeBytes < 0 || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid inventory file evidence");
            }
        }
    }
}
