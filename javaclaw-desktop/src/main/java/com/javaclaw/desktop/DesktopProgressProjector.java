package com.javaclaw.desktop;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.javaclaw.sdk.ManagementDocuments;
import com.javaclaw.sdk.model.ApprovalItemContent;
import com.javaclaw.sdk.model.ArtifactItemContent;
import com.javaclaw.sdk.model.CheckpointItemContent;
import com.javaclaw.sdk.model.CommandItemContent;
import com.javaclaw.sdk.model.EffectReceiptItemContent;
import com.javaclaw.sdk.model.ErrorItemContent;
import com.javaclaw.sdk.model.EvaluationItemContent;
import com.javaclaw.sdk.model.EventInfo;
import com.javaclaw.sdk.model.FileChangeItemContent;
import com.javaclaw.sdk.model.ImageItemContent;
import com.javaclaw.sdk.model.ItemInfo;
import com.javaclaw.sdk.model.McpItemContent;
import com.javaclaw.sdk.model.PlanItemContent;
import com.javaclaw.sdk.model.SubagentItemContent;
import com.javaclaw.sdk.model.ThreadSnapshot;
import com.javaclaw.sdk.model.TurnInfo;
import com.javaclaw.sdk.model.UserInputItemContent;

/** 将 ThreadSnapshot 和持久事件投影成不泄露原始载荷的处理进度。 */
final class DesktopProgressProjector {
    private static final int MAX_ENTRIES = 10;
    private static final int MAX_DETAIL = 120;

    private DesktopProgressProjector() {}

    static DesktopProgressSnapshot project(ThreadSnapshot snapshot, List<EventInfo> events) {
        Objects.requireNonNull(snapshot, "snapshot");
        TurnInfo current = snapshot.turns().stream()
                .max(Comparator.comparing(TurnInfo::startedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(TurnInfo::id, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
        if (current == null) {
            return new DesktopProgressSnapshot("等待任务", "当前会话尚未开始处理", List.of(), usage(events), false);
        }
        List<ItemInfo> items = snapshot.items().stream()
                .filter(value -> Objects.equals(current.id(), value.turnId()))
                .sorted(Comparator.comparingLong(ItemInfo::ordinal))
                .toList();
        List<DesktopProgressSnapshot.Entry> projectedEntries = items.stream()
                .map(DesktopProgressProjector::entry)
                .filter(Objects::nonNull)
                .toList();
        List<DesktopProgressSnapshot.Entry> entries = projectedEntries.stream()
                .skip(Math.max(0, projectedEntries.size() - MAX_ENTRIES))
                .toList();
        boolean active = !isTerminal(current.status());
        return new DesktopProgressSnapshot(phase(current, items), status(current), entries, usage(events), active);
    }

    private static DesktopProgressSnapshot.Entry entry(ItemInfo item) {
        var content = item.content();
        String state = itemState(item.state());
        if (content instanceof CommandItemContent command) {
            return entry(DesktopProgressSnapshot.Kind.TOOL, "终端命令", String.join(" ", command.argv()), state);
        }
        if (content instanceof FileChangeItemContent file) {
            return entry(DesktopProgressSnapshot.Kind.FILE, "文件变更", file.change() + " · " + file.path(), state);
        }
        if (content instanceof McpItemContent mcp) {
            return entry(DesktopProgressSnapshot.Kind.MCP, "MCP 调用", mcp.server() + " · " + mcp.tool(), state);
        }
        if (content instanceof SubagentItemContent subtask) {
            return entry(DesktopProgressSnapshot.Kind.SUBTASK, "子任务", subtask.task(), state);
        }
        if (content instanceof PlanItemContent plan) {
            return entry(DesktopProgressSnapshot.Kind.PLAN, "执行计划", plan.goal(), state);
        }
        if (content instanceof ArtifactItemContent artifact) {
            return entry(
                    DesktopProgressSnapshot.Kind.ARTIFACT, "产物", artifact.category() + " · " + artifact.name(), state);
        }
        if (content instanceof ImageItemContent image) {
            return entry(DesktopProgressSnapshot.Kind.ARTIFACT, "图像", image.description(), state);
        }
        if (content instanceof ApprovalItemContent approval) {
            return entry(DesktopProgressSnapshot.Kind.INTERACTION, "等待批准", approval.reason(), state);
        }
        if (content instanceof UserInputItemContent input) {
            return entry(DesktopProgressSnapshot.Kind.INTERACTION, "等待回答", input.prompt(), state);
        }
        if (content instanceof ErrorItemContent error) {
            return entry(DesktopProgressSnapshot.Kind.ERROR, "处理错误", error.message(), state);
        }
        if (content instanceof CheckpointItemContent checkpoint) {
            return entry(DesktopProgressSnapshot.Kind.OTHER, "恢复点", checkpoint.summary(), state);
        }
        if (content instanceof EvaluationItemContent evaluation) {
            return entry(DesktopProgressSnapshot.Kind.OTHER, "验收", evaluation.summary(), state);
        }
        if (content instanceof EffectReceiptItemContent receipt) {
            return entry(DesktopProgressSnapshot.Kind.TOOL, "执行凭据", receipt.tool() + " · " + receipt.summary(), state);
        }
        if (content == null && "STARTED".equalsIgnoreCase(item.state())) {
            return entry(DesktopProgressSnapshot.Kind.OTHER, "处理中", "等待持久化摘要", state);
        }
        return null;
    }

    private static DesktopProgressSnapshot.Entry entry(
            DesktopProgressSnapshot.Kind kind, String title, String detail, String state) {
        return new DesktopProgressSnapshot.Entry(kind, title, bounded(detail), state);
    }

    private static String phase(TurnInfo turn, List<ItemInfo> items) {
        String status = Objects.toString(turn.status(), "").toUpperCase(java.util.Locale.ROOT);
        if (status.contains("APPROVAL") || status.contains("INPUT") || status.contains("WAIT")) {
            return "等待交互";
        }
        if ("FAILED".equals(status)) {
            return "处理失败";
        }
        if ("INTERRUPTED".equals(status) || "CANCELLED".equals(status)) {
            return "已停止";
        }
        if ("COMPLETED".equals(status) || "SUCCEEDED".equals(status)) {
            return "已完成";
        }
        for (int index = items.size() - 1; index >= 0; index--) {
            var content = items.get(index).content();
            if (content instanceof ApprovalItemContent || content instanceof UserInputItemContent) {
                return "等待交互";
            }
            if (content instanceof CommandItemContent
                    || content instanceof FileChangeItemContent
                    || content instanceof McpItemContent
                    || content instanceof SubagentItemContent
                    || content instanceof EffectReceiptItemContent) {
                return "执行";
            }
            if (content instanceof PlanItemContent) {
                return "规划";
            }
            if (content instanceof ArtifactItemContent || content instanceof ImageItemContent) {
                return "整理结果";
            }
        }
        return "思考";
    }

    private static String status(TurnInfo turn) {
        String value = Objects.toString(turn.status(), "UNKNOWN").toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "QUEUED", "PENDING" -> "任务已排队";
            case "STARTED", "RUNNING", "IN_PROGRESS" -> "正在处理";
            case "WAITING", "WAITING_FOR_INPUT", "AWAITING_APPROVAL" -> "需要你的操作";
            case "COMPLETED", "SUCCEEDED" -> "处理完成";
            case "FAILED" -> "处理失败";
            case "INTERRUPTED", "CANCELLED" -> "已停止";
            default -> "状态：" + bounded(value);
        };
    }

    private static String itemState(String state) {
        return switch (Objects.toString(state, "UNKNOWN").toUpperCase(java.util.Locale.ROOT)) {
            case "STARTED", "RUNNING", "IN_PROGRESS" -> "进行中";
            case "COMPLETED", "SUCCEEDED" -> "已完成";
            case "FAILED" -> "失败";
            case "INTERRUPTED", "CANCELLED" -> "已停止";
            default -> "未知";
        };
    }

    private static DesktopProgressSnapshot.TokenUsage usage(List<EventInfo> events) {
        if (events == null) {
            return DesktopProgressSnapshot.TokenUsage.unavailable();
        }
        return events.stream()
                .filter(value -> "usage/updated".equals(value.type()))
                .max(Comparator.comparingLong(EventInfo::sequence))
                .map(EventInfo::payload)
                .map(DesktopProgressProjector::usage)
                .orElseGet(DesktopProgressSnapshot.TokenUsage::unavailable);
    }

    private static DesktopProgressSnapshot.TokenUsage usage(com.javaclaw.sdk.model.JsonDocument document) {
        try {
            Map<String, String> values = ManagementDocuments.stringMap(document);
            return new DesktopProgressSnapshot.TokenUsage(
                    true,
                    nonNegative(values.get("inputTokens")),
                    nonNegative(values.get("outputTokens")),
                    nonNegative(values.get("reasoningTokens")));
        } catch (RuntimeException invalid) {
            return DesktopProgressSnapshot.TokenUsage.unavailable();
        }
    }

    private static long nonNegative(String value) {
        return Math.max(0, Long.parseLong(Objects.toString(value, "0")));
    }

    private static boolean isTerminal(String status) {
        return List.of("COMPLETED", "SUCCEEDED", "FAILED", "INTERRUPTED", "CANCELLED")
                .contains(Objects.toString(status, "").toUpperCase(java.util.Locale.ROOT));
    }

    private static String bounded(String value) {
        String text = Objects.toString(value, "").strip().replaceAll("\\s+", " ");
        return text.length() <= MAX_DETAIL ? text : text.substring(0, MAX_DETAIL - 1) + "…";
    }
}
