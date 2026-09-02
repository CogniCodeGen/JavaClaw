package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionJobCursor;

/**
 * Extension Job 的不可变 keyset 分页状态。
 *
 * @param jobs 当前页摘要
 * @param starts 已访问页面的起始游标，第一个元素必须为空
 * @param index 当前页从零开始的下标
 * @param nextCursor 下一页游标
 * @param selected 当前选择
 */
public record AutomationJobPage(
        List<ExtensionExecutionReceipt> jobs,
        List<Optional<ExtensionJobCursor>> starts,
        int index,
        Optional<ExtensionJobCursor> nextCursor,
        Optional<ExtensionExecutionReceipt> selected) {
    /** 取得集合所有权并校验分页和选择不变量。 */
    public AutomationJobPage {
        List<ExtensionExecutionReceipt> copiedJobs = List.copyOf(Objects.requireNonNull(jobs, "jobs"));
        jobs = copiedJobs;
        starts = List.copyOf(Objects.requireNonNull(starts, "starts"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        selected = Objects.requireNonNull(selected, "selected");
        if (starts.isEmpty() || starts.getFirst().isPresent() || index < 0 || index >= starts.size()) {
            throw new IllegalArgumentException("Job page history is invalid");
        }
        if (selected.filter(
                        value -> copiedJobs.stream().noneMatch(job -> job.id().equals(value.id())))
                .isPresent()) {
            throw new IllegalArgumentException("selected Job is not in current page");
        }
    }

    /** @return 当前页请求使用的游标 */
    public Optional<ExtensionJobCursor> currentStart() {
        return starts.get(index);
    }

    /** @return 空的第一页 */
    public static AutomationJobPage first() {
        return new AutomationJobPage(List.of(), List.of(Optional.empty()), 0, Optional.empty(), Optional.empty());
    }
}
