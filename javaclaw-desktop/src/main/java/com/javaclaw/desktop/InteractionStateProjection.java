package com.javaclaw.desktop;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.sdk.model.ApprovalItemContent;
import com.javaclaw.sdk.model.EventInfo;
import com.javaclaw.sdk.model.ItemInfo;
import com.javaclaw.sdk.model.ThreadSnapshot;
import com.javaclaw.sdk.model.UserInputItemContent;

/** 从持久事件恢复审批与用户输入的待处理状态；通知仅用于唤醒，不能作为权威结果。 */
record InteractionStateProjection(Set<String> pendingIds, Set<String> resolvedIds) {
    InteractionStateProjection {
        pendingIds = Set.copyOf(pendingIds == null ? Set.of() : pendingIds);
        resolvedIds = Set.copyOf(resolvedIds == null ? Set.of() : resolvedIds);
    }

    static InteractionStateProjection empty() {
        return new InteractionStateProjection(Set.of(), Set.of());
    }

    static InteractionStateProjection project(ThreadSnapshot snapshot, List<EventInfo> events) {
        Objects.requireNonNull(snapshot, "snapshot");
        LinkedHashSet<String> pending = new LinkedHashSet<>();
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        (events == null ? List.<EventInfo>of() : events)
                .stream()
                        .sorted(Comparator.comparingLong(EventInfo::sequence))
                        .forEach(event -> apply(event, pending, resolved));

        // 兼容没有 interaction 事件的旧服务端：等待态 Turn 中最后一个请求仍应提供可操作卡片。
        Set<String> waitingTurns = snapshot.turns().stream()
                .filter(turn -> "WAITING_FOR_APPROVAL".equalsIgnoreCase(turn.status())
                        || "WAITING_FOR_INPUT".equalsIgnoreCase(turn.status())
                        || "WAITING".equalsIgnoreCase(turn.status())
                        || "AWAITING_APPROVAL".equalsIgnoreCase(turn.status()))
                .map(com.javaclaw.sdk.model.TurnInfo::id)
                .collect(java.util.stream.Collectors.toSet());
        snapshot.items().stream()
                .filter(item -> waitingTurns.contains(item.turnId()))
                .filter(item -> interactionId(item) != null)
                .sorted(Comparator.comparingLong(ItemInfo::ordinal).reversed())
                .forEach(item -> {
                    String id = interactionId(item);
                    if (!resolved.contains(id)
                            && pending.stream().noneMatch(existing -> sameTurn(snapshot, existing, item))) {
                        pending.add(id);
                    }
                });
        return new InteractionStateProjection(pending, resolved);
    }

    String state(ItemInfo item) {
        String id = interactionId(item);
        if (id == null) {
            return item.state();
        }
        if (pendingIds.contains(id)) {
            return "PENDING";
        }
        if (resolvedIds.contains(id)) {
            return "RESOLVED";
        }
        return item.state();
    }

    private static void apply(EventInfo event, Set<String> pending, Set<String> resolved) {
        String id = Objects.toString(event.correlationId(), "").strip();
        if (id.isEmpty()) {
            return;
        }
        switch (Objects.toString(event.type(), "")) {
            case "approval/requested", "userInput/requested" -> {
                resolved.remove(id);
                pending.add(id);
            }
            case "approval/resolved", "approval/cancelled", "userInput/resolved", "userInput/cancelled" -> {
                pending.remove(id);
                resolved.add(id);
            }
            default -> {
                // 其他事件不改变交互请求状态。
            }
        }
    }

    private static boolean sameTurn(ThreadSnapshot snapshot, String id, ItemInfo candidate) {
        return snapshot.items().stream()
                .filter(item -> id.equals(interactionId(item)))
                .anyMatch(item -> item.turnId().equals(candidate.turnId()));
    }

    private static String interactionId(ItemInfo item) {
        if (item.content() instanceof ApprovalItemContent approval) {
            return approval.approvalId();
        }
        if (item.content() instanceof UserInputItemContent input) {
            return input.requestId();
        }
        return null;
    }
}
