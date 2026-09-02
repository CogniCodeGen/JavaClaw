package com.javaclaw.api;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionExecutionContractsTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void inputRequest固定Schema有效期并拒绝Secret语义外的空值() {
        InputRequest request = request();

        assertEquals("workflow", request.producerId());
        assertThrows(
                IllegalArgumentException.class,
                () -> new InputRequest(
                        "request", TurnId.random(), "workflow", "问题", new CanonicalPayload("{}"), NOW, NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InputRequest(
                        "request",
                        TurnId.random(),
                        "workflow",
                        "x".repeat(1001),
                        new CanonicalPayload("{}"),
                        NOW,
                        NOW.plusSeconds(1)));
    }

    @Test
    void inputRecord只允许Resolved带响应且取消过期必须有原因() {
        InputRequest request = request();
        InputRequestRecord pending =
                new InputRequestRecord(request, InputRequestState.PENDING, 1, Optional.empty(), Optional.empty(), NOW);
        InputRequestRecord resolved = new InputRequestRecord(
                request,
                InputRequestState.RESOLVED,
                2,
                Optional.of(new CanonicalPayload("{\"answer\":\"yes\"}")),
                Optional.empty(),
                NOW.plusSeconds(1));

        assertTrue(pending.pending());
        assertFalse(resolved.pending());
        assertThrows(
                IllegalArgumentException.class,
                () -> new InputRequestRecord(
                        request, InputRequestState.PENDING, 1, resolved.response(), Optional.empty(), NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InputRequestRecord(
                        request,
                        InputRequestState.CANCELLED,
                        2,
                        Optional.empty(),
                        Optional.empty(),
                        NOW.plusSeconds(1)));
    }

    @Test
    void executionTerminal只包含完成失败和取消() {
        assertTrue(ExecutionState.COMPLETED.terminal());
        assertTrue(ExecutionState.FAILED.terminal());
        assertTrue(ExecutionState.CANCELLED.terminal());
        assertFalse(ExecutionState.RUNNING.terminal());
        assertFalse(ExecutionState.WAITING_INPUT.terminal());
    }

    private static InputRequest request() {
        return new InputRequest(
                "request",
                TurnId.random(),
                "workflow",
                "是否继续？",
                new CanonicalPayload("{\"type\":\"object\"}"),
                NOW,
                NOW.plusSeconds(60));
    }
}
