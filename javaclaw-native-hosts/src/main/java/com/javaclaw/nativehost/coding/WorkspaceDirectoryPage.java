package com.javaclaw.nativehost.coding;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一个目录的有序内容页；游标只作定位，不授予路径访问权限。
 *
 * @param entries 有序条目，不可空
 * @param nextName 有后续页时为本页最后一个文件名
 */
public record WorkspaceDirectoryPage(List<WorkspaceFileAccess.Entry> entries, Optional<String> nextName) {
    /** 复制条目并固定游标容器。 */
    public WorkspaceDirectoryPage {
        entries = List.copyOf(entries);
        Objects.requireNonNull(nextName, "nextName");
    }
}
