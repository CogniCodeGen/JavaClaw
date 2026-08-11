package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.Snapshot;

/** 记忆中心分区 Controller 的最小刷新协议。 */
interface MemorySectionController {
    void apply(Snapshot snapshot, String query);
    default void activated() {}
}
