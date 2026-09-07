package com.javaclaw.nativehost.coding;

import java.util.List;

/**
 * 有界搜索的实际扫描结果。
 *
 * @param matches 不可空的匹配行列表
 * @param truncated 是否因字节、匹配数或目录遍历上限未扫描完整范围
 * @param scannedBytes 实际读取的文件内容字节数，非负
 */
public record WorkspaceSearchPage(List<WorkspaceFileAccess.Match> matches, boolean truncated, long scannedBytes) {
    /** 复制匹配结果并校验计数。 */
    public WorkspaceSearchPage {
        matches = List.copyOf(matches);
        if (scannedBytes < 0) {
            throw new IllegalArgumentException("scannedBytes must be non-negative");
        }
    }
}
