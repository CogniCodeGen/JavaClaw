package com.javaclaw.runtime;

import java.util.Objects;

import com.javaclaw.api.CorePayloads;

/**
 * 上下文压缩后的窗口与可审计元数据。
 *
 * @param window 新窗口
 * @param item 持久化的压缩说明
 * @param usage 本次压缩实际用量；确定性文本压缩为零
 */
public record CompactionOutcome(ConversationWindow window, CorePayloads.Compaction item, ModelUsage usage) {
    /** 校验结果。 */
    public CompactionOutcome {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(usage, "usage");
    }

    /**
     * 创建不调用模型的确定性压缩结果。
     *
     * @param window 新窗口
     * @param item 审计说明
     */
    public CompactionOutcome(ConversationWindow window, CorePayloads.Compaction item) {
        this(window, item, ModelUsage.zero());
    }
}
