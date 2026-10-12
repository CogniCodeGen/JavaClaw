package com.javaclaw.application.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.framework.api.RunEventEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationReplyProjectionTest {
    @Test
    void onlyMatchingTrustedDeltasEnterTheCurrentDraft() {
        var projection = new ConversationReplyProjection("segment");
        var sink = new Sink();
        assertTrue(projection.onEvent(event("core.model.reply.delta", "model-1", "早到内容"), sink));
        assertTrue(projection.onEvent(event("core.model.reply.started", "", ""), sink));
        assertTrue(sink.events.isEmpty());
        assertFalse(projection.onEvent(event("core.model.reply.started", "model-1", "", 2, "framework.springai"), sink));
        assertFalse(projection.onEvent(event("core.model.reply.started", "model-1", "", 1, "plugin"), sink));
        assertTrue(projection.onEvent(event("core.model.reply.started", "model-1", ""), sink));
        assertEquals("begin", sink.action(0));
        assertTrue(projection.onEvent(event("core.model.reply.delta", "other", "错步骤"), sink));
        assertTrue(projection.onEvent(event("core.model.reply.delta", "", "空身份"), sink));
        assertTrue(projection.onEvent(event("core.model.reply.delta", "model-1", ""), sink));
        assertTrue(projection.onEvent(event("core.model.reply.delta", "model-1", "草稿"), sink));
        assertEquals(List.of(new ConversationEvent.Reply("草稿")), sink.replies());
        assertEquals(2, sink.events.size());
    }

    @Test
    void everyHostRepairBoundaryClearsTheOldDraftWithoutReplayingIt() {
        for (String type : List.of("core.model.retrying", "core.harness.protocol_violation",
                "core.harness.protocol_repair_requested", "core.task.repair_requested", "core.step.started")) {
            var projection = new ConversationReplyProjection("segment");
            var sink = new Sink();
            assertFalse(projection.onEvent(event(type, "old", ""), sink));
            assertTrue(sink.events.isEmpty());
            projection.onEvent(event("core.model.reply.started", "old", ""), sink);
            projection.onEvent(event("core.model.reply.delta", "old", "旧草稿"), sink);
            assertFalse(projection.onEvent(event(type, "old", ""), sink));
            assertEquals("reset", sink.action(2));
            projection.onEvent(event("core.model.reply.delta", "old", "迟到"), sink);
            projection.onEvent(event("core.model.reply.reset", "other", ""), sink);
            assertEquals(3, sink.events.size());
            projection.onEvent(event("core.model.reply.started", "new", ""), sink);
            projection.onEvent(event("core.model.reply.reset", "new", ""), sink);
            assertEquals("reset", sink.action(4));
            projection.canonical("真实终态答复", sink);
            assertEquals("replace", sink.action(5));
            assertEquals("真实终态答复", ((ConversationEvent.Custom) sink.events.get(5)).payload().path("text").asText());
        }
    }

    @Test
    void canonicalReplyReplacesTheDraftExactlyOnceAndRejectsLateDeltas() {
        var projection = new ConversationReplyProjection("segment");
        var sink = new Sink();
        assertTrue(projection.onEvent(event("core.model.reply.reset", "unknown", ""), sink));
        projection.onEvent(event("core.model.reply.started", "step", ""), sink);
        projection.onEvent(event("core.model.reply.delta", "step", "草稿"), sink);
        projection.canonical(null, sink);
        assertEquals("", ((ConversationEvent.Custom) sink.events.getLast()).payload().path("text").asText());
        projection.canonical("不能重复覆盖", sink);
        assertTrue(projection.onEvent(event("core.model.reply.delta", "step", "迟到内容"), sink));
        assertFalse(projection.onEvent(event("core.run.completed", "step", ""), sink));
        assertEquals(3, sink.events.size());
        assertEquals("segment", ((ConversationEvent.Custom) sink.events.getFirst()).payload().path("segmentId").asText());
    }

    @Test
    void withoutDraftOnlyANonblankCanonicalReplyIsPublishedAndClosePublishesNothing() {
        for (String text : new String[]{null, "", " ", "最终答复"}) {
            var projection = new ConversationReplyProjection("segment");
            var sink = new Sink();
            assertFalse(projection.onEvent(event("core.step.completed", "step", ""), sink));
            projection.canonical(text, sink);
            assertEquals(text != null && !text.isBlank() ? List.of(new ConversationEvent.Reply(text)) : List.of(), sink.events);
        }
        var projection = new ConversationReplyProjection("segment");
        var sink = new Sink();
        projection.close();
        projection.canonical("已关闭", sink);
        assertTrue(projection.onEvent(event("core.model.reply.started", "step", ""), sink));
        assertTrue(sink.events.isEmpty());
    }

    private static RunEventEnvelope event(String type, String step, String text) {
        return event(type, step, text, 1, "framework.springai");
    }

    private static RunEventEnvelope event(String type, String step, String text, int version, String producer) {
        var payload = JsonNodeFactory.instance.objectNode().put("modelStepId", step).put("text", text);
        if (type.equals("core.step.started")) payload.putObject("input").put("phase", "harness.decision_invalid");
        return new RunEventEnvelope("run", 1, Instant.EPOCH, type, version, producer, null, null, payload);
    }

    private static final class Sink implements ConversationCallbacks {
        final List<ConversationEvent> events = new ArrayList<>();
        @Override public void onEvent(ConversationEvent event) { events.add(event); }
        @Override public void onTerminal(ConversationOutcome outcome) { fail("草稿投影不得生成终态"); }
        String action(int index) { return ((ConversationEvent.Custom) events.get(index)).payload().path("action").asText(); }
        List<ConversationEvent.Reply> replies() {
            return events.stream().filter(ConversationEvent.Reply.class::isInstance).map(ConversationEvent.Reply.class::cast).toList();
        }
    }
}
