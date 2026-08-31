package com.javaclaw.desktop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import com.javaclaw.sdk.model.ApprovalItemContent;
import com.javaclaw.sdk.model.ArtifactItemContent;
import com.javaclaw.sdk.model.ErrorItemContent;
import com.javaclaw.sdk.model.ImageItemContent;
import com.javaclaw.sdk.model.ItemInfo;
import com.javaclaw.sdk.model.PlanItemContent;
import com.javaclaw.sdk.model.PromptDraftItemContent;
import com.javaclaw.sdk.model.TextItemContent;
import com.javaclaw.sdk.model.UserInputItemContent;
import com.javaclaw.sdk.model.UserMessageItemContent;

/** 将 Item 生命周期投影为原桌面可读块；执行记录按 Turn 聚合，消息和 Artifact 保持独立。 */
final class TranscriptProjector {
    private TranscriptProjector() {}

    static List<TranscriptBlock> project(List<ItemInfo> items) {
        return project(items, InteractionStateProjection.empty());
    }

    static List<TranscriptBlock> project(List<ItemInfo> items, InteractionStateProjection interactions) {
        ArrayList<Object> ordered = new ArrayList<>();
        LinkedHashMap<String, ExecutionAccumulator> executions = new LinkedHashMap<>();
        // ordinal 只保证同一 Turn 内的 Item 顺序。跨 Turn 排序会把后创建 Turn 的低 ordinal
        // 提前到旧 Turn 之前，因此这里必须保留 App Server snapshot 已提供的 Thread 顺序。
        items.forEach(item -> {
            TranscriptBlock direct = direct(item, interactions);
            if (direct != null) {
                ordered.add(direct);
                return;
            }
            ExecutionAccumulator accumulator = executions.computeIfAbsent(item.turnId(), turnId -> {
                var created = new ExecutionAccumulator(turnId);
                ordered.add(created);
                return created;
            });
            accumulator.add(item);
        });
        return ordered.stream()
                .map(value -> value instanceof TranscriptBlock block ? block : ((ExecutionAccumulator) value).finish())
                .toList();
    }

    private static TranscriptBlock direct(ItemInfo item, InteractionStateProjection interactions) {
        var content = item.content();
        TranscriptBlock.Category category;
        String title;
        if (content instanceof UserMessageItemContent
                || content instanceof TextItemContent text && "userMessage".equals(text.kind())) {
            category = TranscriptBlock.Category.USER_MESSAGE;
            title = "你";
        } else if (content instanceof TextItemContent text && "agentMessage".equals(text.kind())) {
            category = TranscriptBlock.Category.AGENT_MESSAGE;
            title = "JavaClaw";
        } else if (content instanceof PlanItemContent) {
            category = TranscriptBlock.Category.PLAN;
            title = "执行计划";
        } else if (content instanceof ArtifactItemContent
                || content instanceof PromptDraftItemContent
                || content instanceof ImageItemContent) {
            category = TranscriptBlock.Category.ARTIFACT;
            title = "结果与附件";
        } else if (content instanceof ApprovalItemContent || content instanceof UserInputItemContent) {
            category = TranscriptBlock.Category.INTERACTION;
            title = "需要你的操作";
        } else if (content instanceof ErrorItemContent) {
            category = TranscriptBlock.Category.ERROR;
            title = "执行错误";
        } else {
            return null;
        }
        return new TranscriptBlock(
                item.id(),
                category,
                content.kind(),
                title,
                ItemPresenter.text(item),
                List.of(item.id()),
                item.turnId(),
                interactions.state(item),
                item.createdAt(),
                content);
    }

    private static final class ExecutionAccumulator {
        private final String turnId;
        private final ArrayList<String> itemIds = new ArrayList<>();
        private final LinkedHashSet<String> summaries = new LinkedHashSet<>();
        private boolean usageUpdated;
        private String state = "";
        private java.time.Instant createdAt;

        private ExecutionAccumulator(String turnId) {
            this.turnId = turnId;
        }

        private void add(ItemInfo item) {
            itemIds.add(item.id());
            state = item.state();
            if (createdAt == null
                    || item.createdAt() != null && item.createdAt().isBefore(createdAt)) {
                createdAt = item.createdAt();
            }
            String kind = item.content() == null ? "pending" : item.content().kind();
            if ("contextUsage".equals(kind)) {
                usageUpdated = true;
                return;
            }
            String summary = ItemPresenter.text(item).strip();
            if (!summary.isEmpty()) {
                summaries.add(summary);
            }
        }

        private TranscriptBlock finish() {
            ArrayList<String> lines = new ArrayList<>(summaries);
            if (usageUpdated) {
                lines.add("上下文与用量已更新");
            }
            if (lines.isEmpty()) {
                lines.add("执行状态已更新");
            }
            return new TranscriptBlock(
                    "execution:" + turnId,
                    TranscriptBlock.Category.EXECUTION,
                    "execution",
                    "执行过程",
                    String.join("\n\n", lines),
                    itemIds,
                    turnId,
                    state,
                    createdAt,
                    null);
        }
    }
}
