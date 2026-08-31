package com.javaclaw.desktop;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.javaclaw.sdk.model.ApprovalItemContent;
import com.javaclaw.sdk.model.EventInfo;
import com.javaclaw.sdk.model.ItemInfo;
import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.ThreadInfo;
import com.javaclaw.sdk.model.ThreadSnapshot;
import com.javaclaw.sdk.model.TurnInfo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionStateProjectionTest {
    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");

    @Test
    void persistentEventsKeepRequestActionableUntilResolutionAndDeduplicateAfterReconnect() {
        ItemInfo request = request("approval-1", "turn-1", 1);
        ThreadSnapshot waiting = snapshot("WAITING_FOR_APPROVAL", List.of(request));
        EventInfo requested = event(1, "approval/requested", "approval-1");

        InteractionStateProjection pending = InteractionStateProjection.project(waiting, List.of(requested));
        TranscriptBlock pendingBlock =
                TranscriptProjector.project(waiting.items(), pending).getFirst();
        assertTrue(pendingBlock.pendingInteraction());
        assertTrue(pending.pendingIds().contains("approval-1"));

        EventInfo resolved = event(2, "approval/resolved", "approval-1");
        InteractionStateProjection completed = InteractionStateProjection.project(
                snapshot("IN_PROGRESS", List.of(request)), List.of(requested, resolved));
        TranscriptBlock completedBlock =
                TranscriptProjector.project(List.of(request), completed).getFirst();
        assertFalse(completedBlock.pendingInteraction());
        assertTrue(completed.resolvedIds().contains("approval-1"));
    }

    @Test
    void waitingTurnIsActionableWhenConnectedToAnOlderServerWithoutInteractionEvents() {
        ItemInfo request = request("approval-legacy", "turn-1", 1);
        InteractionStateProjection projection =
                InteractionStateProjection.project(snapshot("WAITING_FOR_APPROVAL", List.of(request)), List.of());

        assertTrue(TranscriptProjector.project(List.of(request), projection)
                .getFirst()
                .pendingInteraction());
    }

    private static ThreadSnapshot snapshot(String status, List<ItemInfo> items) {
        ThreadInfo thread = new ThreadInfo("thread", "workspace", null, null, "测试", "ACTIVE", 0, 2, 1, NOW, NOW);
        TurnInfo turn = new TurnInfo(
                "turn-1", "thread", status, "attempt", List.of(), JsonDocument.EMPTY_OBJECT, null, NOW, null);
        return new ThreadSnapshot(thread, List.of(turn), items);
    }

    private static ItemInfo request(String id, String turnId, long ordinal) {
        return new ItemInfo(
                "item-" + id,
                "thread",
                turnId,
                ordinal,
                "COMPLETED",
                new ApprovalItemContent("approvalRequest", id, "允许工具", "HIGH", JsonDocument.EMPTY_OBJECT),
                NOW,
                NOW);
    }

    private static EventInfo event(long sequence, String type, String id) {
        return new EventInfo(
                "event-" + sequence, "thread", "turn-1", sequence, type, 1, id, null, JsonDocument.EMPTY_OBJECT, NOW);
    }
}
