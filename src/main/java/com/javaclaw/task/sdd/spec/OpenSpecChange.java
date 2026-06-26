package com.javaclaw.task.sdd.spec;

import java.util.List;
import java.util.Optional;

/**
 * 一次 OpenSpec 变更 —— 托管任务在 SDD 架构下的<b>聚合根</b>。
 *
 * <p>一个 change 对应工作目录下 {@code .agent/openspec/changes/{slug}/} 一整个目录，含
 * {@link Proposal}（proposal.md）、若干 {@link Capability}（specs/{能力}/spec.md）、可选
 * 设计说明（design.md）、有序可勾选的实现项（tasks.md）。本 record 是从这些 markdown
 * 文件折叠出的<b>派生只读视图</b>，由 {@code SpecStore} 读出；任何写入都改 markdown 后
 * 重新折叠，markdown 始终是唯一真相。</p>
 *
 * <p>没有独立的状态机字段：进度与完成度全部从 {@link #tasks} 的勾选态派生
 * （{@link #progressPercent()} / {@link #allTasksDone()} / {@link #nextPendingTask()}）。</p>
 *
 * @param id           稳定标识（任务 id）
 * @param slug         change 目录名（{@code {YYYYMMDD-短名}} 或 {@code {shortId-标题}}）
 * @param title        变更标题
 * @param proposal     提案（可空——尚未产出 propose 阶段时）
 * @param capabilities 受影响能力及其规格（可空）
 * @param design       设计说明 markdown 原文（可空——简单变更无需设计）
 * @param tasks        有序实现项（可空——尚未产出 tasks 阶段时）
 * @author JavaClaw
 */
public record OpenSpecChange(
        String id,
        String slug,
        String title,
        Proposal proposal,
        List<Capability> capabilities,
        String design,
        List<TaskItem> tasks) {

    public OpenSpecChange {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    /** 取首个未勾选的实现项（执行循环的推进点）；全部完成返回空。 */
    public Optional<TaskItem> nextPendingTask() {
        return tasks.stream().filter(t -> !t.done()).findFirst();
    }

    /** 是否所有实现项都已勾选完成（tasks 为空视为未完成，避免空计划被当成已完成）。 */
    public boolean allTasksDone() {
        return !tasks.isEmpty() && tasks.stream().allMatch(TaskItem::done);
    }

    /** 进度百分比 = 已勾选项 / 总项数 * 100；无任务时为 0。 */
    public int progressPercent() {
        if (tasks.isEmpty()) return 0;
        long done = tasks.stream().filter(TaskItem::done).count();
        return (int) Math.round(done * 100.0 / tasks.size());
    }

    /** 展开本变更所有能力的全部场景（跨能力扁平化），供整体验收逐条求值。 */
    public List<Scenario> allScenarios() {
        return capabilities.stream().flatMap(c -> c.allScenarios().stream()).toList();
    }
}
