package com.javaclaw.desktop;

import java.time.Instant;
import java.util.List;

import com.javaclaw.sdk.model.ItemContent;

/** Desktop 的持久 Transcript 展示块；保留来源 Turn、状态、时间和类型化内容，支持内联操作而无需解析原始 JSON。 */
record TranscriptBlock(
        String id,
        Category category,
        String kind,
        String title,
        String text,
        List<String> itemIds,
        String turnId,
        String state,
        Instant createdAt,
        ItemContent source) {
    TranscriptBlock {
        itemIds = List.copyOf(itemIds);
        turnId = java.util.Objects.toString(turnId, "");
        state = java.util.Objects.toString(state, "");
    }

    TranscriptBlock(String id, Category category, String kind, String title, String text, List<String> itemIds) {
        this(id, category, kind, title, text, itemIds, "", "", null, null);
    }

    boolean pendingInteraction() {
        return category == Category.INTERACTION
                && ("STARTED".equalsIgnoreCase(state)
                        || "PENDING".equalsIgnoreCase(state)
                        || "WAITING".equalsIgnoreCase(state));
    }

    /** 决定消息气泡、计划卡片或执行面板的表现，不改变来源 Item kind。 */
    enum Category {
        USER_MESSAGE,
        AGENT_MESSAGE,
        PLAN,
        EXECUTION,
        ARTIFACT,
        INTERACTION,
        ERROR
    }
}
