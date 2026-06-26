package com.javaclaw.task.sdd.spec;

import java.util.List;

/**
 * {@code tasks.md} 中的一个可勾选实现项 —— 步骤状态的<b>真相载体</b>。
 *
 * <p>对应 markdown 行：{@code - [ ] N. 动作（涉及文件）— 判据：xxx}。复选框
 * {@code [ ]}/{@code [x]} 就是步骤进度，没有独立的状态字段；执行循环取首个未勾项推进、
 * 谓词成立后把 {@code [ ]} 改写为 {@code [x]} 落盘。本 record 是从 markdown 折叠出的
 * 派生只读视图，写回一律经 {@code SpecStore} 改 markdown。</p>
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
