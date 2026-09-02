package com.javaclaw.api;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalAndSandboxContractsTest {
    @Test
    void approvalLifecycleRequiresAReasonExactlyAtTerminalStates() {
        ApprovalRequest request = request();
        ApprovalRecord pending =
                new ApprovalRecord(request, ApprovalState.PENDING, 1, Optional.empty(), ApiFixtures.NOW);
        ApprovalRecord approved = new ApprovalRecord(
                request, ApprovalState.APPROVED, 2, Optional.of(" approved "), ApiFixtures.NOW.plusSeconds(1));

        assertEquals(ApiFixtures.DIGEST, request.requestDigest());
        assertTrue(pending.pending());
        assertFalse(approved.pending());
        assertEquals("approved", approved.resolutionReason().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApprovalRequest(
                        "id",
                        TurnId.random(),
                        "call",
                        new ToolIdentity("core", "read", 1),
                        ToolRisk.READ_ONLY,
                        "why",
                        "bad",
                        ApiFixtures.NOW,
                        ApiFixtures.NOW.plusSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApprovalRequest(
                        "id",
                        TurnId.random(),
                        "call",
                        new ToolIdentity("core", "read", 1),
                        ToolRisk.READ_ONLY,
                        "why",
                        ApiFixtures.DIGEST,
                        ApiFixtures.NOW,
                        ApiFixtures.NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApprovalRecord(request, ApprovalState.PENDING, 1, Optional.of("reason"), ApiFixtures.NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApprovalRecord(request, ApprovalState.DENIED, 1, Optional.empty(), ApiFixtures.NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApprovalRecord(
                        request, ApprovalState.DENIED, 1, Optional.of("no"), ApiFixtures.NOW.minusSeconds(1)));
    }

    @Test
    void effectReceiptNormalizesDigestsAndRejectsMalformedEvidence() {
        EffectReceipt receipt = new EffectReceipt(
                " key ",
                " write ",
                ApiFixtures.DIGEST.toUpperCase(),
                ApiFixtures.DIGEST.toUpperCase(),
                ApiFixtures.NOW);

        assertEquals("key", receipt.idempotencyKey());
        assertEquals(ApiFixtures.DIGEST, receipt.requestDigest());
        assertEquals(ApiFixtures.DIGEST, receipt.resultDigest());
        assertThrows(
                IllegalArgumentException.class,
                () -> new EffectReceipt("key", "tool", "bad", ApiFixtures.DIGEST, ApiFixtures.NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EffectReceipt("key", "tool", ApiFixtures.DIGEST, "bad", ApiFixtures.NOW));
    }

    @Test
    void sandboxCommandAndResultsOwnAllMutableBytes() {
        byte[] input = {1, 2};
        SandboxCommand command = new SandboxCommand(
                " command ",
                List.of("java", "-version"),
                Path.of("."),
                Map.of("LANG", "C"),
                input,
                SandboxMode.BATCH,
                Duration.ofSeconds(1));
        input[0] = 9;
        byte[] output = {3};
        byte[] error = {4};
        SandboxResult result = new SandboxResult(0, output, error, false, false, Duration.ZERO);
        output[0] = 8;
        error[0] = 8;
        SandboxFrame frame = new SandboxFrame(" terminal ", new byte[] {5}, ApiFixtures.NOW);

        assertEquals("command", command.id());
        assertTrue(command.workingDirectory().isAbsolute());
        assertArrayEquals(new byte[] {1, 2}, command.standardInput());
        byte[] returnedInput = command.standardInput();
        returnedInput[0] = 7;
        assertArrayEquals(new byte[] {1, 2}, command.standardInput());
        assertArrayEquals(new byte[] {3}, result.standardOutput());
        assertArrayEquals(new byte[] {4}, result.standardError());
        assertArrayEquals(new byte[] {5}, frame.bytes());
        byte[] returnedFrame = frame.bytes();
        returnedFrame[0] = 6;
        assertArrayEquals(new byte[] {5}, frame.bytes());
    }

    @Test
    void sandboxContractsRejectAmbiguousCommandsAndDurations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxCommand(
                        "x", List.of(), Path.of("."), Map.of(), new byte[0], SandboxMode.BATCH, Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxCommand(
                        "x",
                        List.of(" "),
                        Path.of("."),
                        Map.of(),
                        new byte[0],
                        SandboxMode.BATCH,
                        Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxCommand(
                        "x", List.of("x"), Path.of("."), Map.of(), new byte[0], SandboxMode.BATCH, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxCommand(
                        "x", List.of("x"), Path.of("."), Map.of(), new byte[0], SandboxMode.BATCH, Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxCommand(
                        "x",
                        List.of("x"),
                        Path.of("."),
                        Map.of(),
                        new byte[0],
                        SandboxMode.BATCH,
                        Duration.ofSeconds(-1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxResult(0, new byte[0], new byte[0], false, false, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxResult(0, new byte[0], new byte[0], false, false, Duration.ofSeconds(-1)));
    }

    @Test
    void cancellationPublishesOnlyFirstReasonAndThrowsAtCheckpoints() {
        CancellationSource source = new CancellationSource();
        source.throwIfCancelled();

        assertFalse(source.isCancelled());
        assertTrue(source.reason().isEmpty());
        assertTrue(source.cancel(" first "));
        assertFalse(source.cancel("second"));
        assertEquals("first", source.reason().orElseThrow());
        TurnCancelledException failure = assertThrows(TurnCancelledException.class, source::throwIfCancelled);
        assertEquals("first", failure.getMessage());
        CancellationToken withoutReason = new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return true;
            }

            @Override
            public Optional<String> reason() {
                return Optional.empty();
            }
        };
        assertEquals(
                "cancelled",
                assertThrows(TurnCancelledException.class, withoutReason::throwIfCancelled)
                        .getMessage());
        assertThrows(IllegalArgumentException.class, () -> new TurnCancelledException(" "));
    }

    private static ApprovalRequest request() {
        return new ApprovalRequest(
                "approval",
                TurnId.random(),
                "call",
                new ToolIdentity("core", "read", 1),
                ToolRisk.READ_ONLY,
                "read data",
                ApiFixtures.DIGEST.toUpperCase(),
                ApiFixtures.NOW,
                ApiFixtures.NOW.plusSeconds(10));
    }
}
