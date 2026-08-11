package com.javaclaw.ui.javafx.memory;

/** 需要向所属记忆窗口提交操作结果的分区。 */
interface MemoryHostedSection extends MemorySectionController {
    void configure(MemorySectionHost host);
}
