package com.javaclaw.runtime;

import java.util.Objects;

import com.javaclaw.api.CorePayloads;

/**
 * 上下文压缩后的窗口与可审计元数据。
 *
 * @param window 新窗口
 * @param item 持久化的压缩说明
 */
public record CompactionOutcome(ConversationWindow window, CorePayloads.Compaction item) {
    /** 校验结果。 */
    public CompactionOutcome {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(item, "item");
    }
}
