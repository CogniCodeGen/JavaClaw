package com.javaclaw.extension.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Extension Job 的稳定 keyset 分页结果。
 *
 * @param jobs 当前页
 * @param nextCursor 下一页游标；到达末尾时为空
 */
public record ExtensionJobPage(List<ExtensionJob> jobs, Optional<ExtensionJobCursor> nextCursor) {
    /** 校验页内容与游标。 */
    public ExtensionJobPage {
        jobs = List.copyOf(jobs);
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        if (jobs.isEmpty() && nextCursor.isPresent()) {
            throw new IllegalArgumentException("empty Job page cannot have a next cursor");
        }
    }
}
