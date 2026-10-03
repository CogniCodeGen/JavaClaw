package com.javaclaw.task.sdd.spec;

import java.util.List;

/**
 * 结构化任务快照中的一个实现项。
 *
 * <p>{@code tasks.json} 的 {@code done} 是进度状态；{@code tasks.md} 仅为可读投影。
 * 执行循环取首个未完成项推进，状态写回统一由 {@code SpecStore} 完成。</p>
 *
 * @param index     序号（从 1 起，与 markdown 行内编号一致）
 * @param action    动作描述
 * @param files     涉及文件路径（可空）
 * @param criterion 完成判据文本（自然语言；可被进一步结构化为 {@link Criterion}）
 * @param done      是否已勾选完成
 * @author JavaClaw
 */
public record TaskItem(int index, String action, List<String> files, String criterion, boolean done) {

    public TaskItem {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
