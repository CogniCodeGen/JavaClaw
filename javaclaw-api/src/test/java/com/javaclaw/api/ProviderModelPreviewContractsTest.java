package com.javaclaw.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderModelPreviewContractsTest {
    private static final Instant NOW = Instant.parse("2026-09-20T01:00:00Z");

    @Test
    void 预览结果复制候选且严格限制数量和重复标识() {
        ArrayList<ProviderModelDiscoveryCandidate> candidates = new ArrayList<>(List.of(candidate("first")));
        var result = new ProviderModelPreviewResult("draft-main", 1, candidates, false, NOW);
        candidates.clear();

        assertEquals(1, result.candidates().size());
        assertThrows(
                UnsupportedOperationException.class, () -> result.candidates().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderModelPreviewResult(
                        "draft-main", 1, List.of(candidate("duplicate"), candidate("duplicate")), false, NOW));
        List<ProviderModelDiscoveryCandidate> maximum = IntStream.range(0, 1000)
                .mapToObj(index -> candidate("model-" + index))
                .toList();
        assertEquals(
                1000,
                new ProviderModelPreviewResult("draft-main", 1, maximum, true, NOW)
                        .candidates()
                        .size());
        ArrayList<ProviderModelDiscoveryCandidate> excessive = new ArrayList<>(maximum);
        excessive.add(candidate("overflow"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderModelPreviewResult("draft-main", 1, excessive, true, NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderModelPreviewResult("draft-main", 0, List.of(), false, NOW));
        assertThrows(
                NullPointerException.class,
                () -> new ProviderModelPreviewResult("draft-main", 1, List.of(), false, null));
    }

    @Test
    void 预览成功失败运行中和取消均具有明确的结果形状() {
        var running = operation(ProviderModelDiscoveryOperationState.RUNNING, Optional.empty(), Optional.empty());
        var succeeded =
                operation(ProviderModelDiscoveryOperationState.SUCCEEDED, Optional.of(result()), Optional.empty());
        var failed = operation(
                ProviderModelDiscoveryOperationState.FAILED, Optional.empty(), Optional.of("DISCOVERY_FAILED"));
        var cancelled = operation(ProviderModelDiscoveryOperationState.CANCELLED, Optional.empty(), Optional.empty());

        assertFalse(running.terminal());
        assertTrue(succeeded.terminal());
        assertTrue(failed.terminal());
        assertTrue(cancelled.terminal());
        assertEquals(result(), succeeded.result().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> operation(ProviderModelDiscoveryOperationState.SUCCEEDED, Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> operation(ProviderModelDiscoveryOperationState.RUNNING, Optional.of(result()), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> operation(ProviderModelDiscoveryOperationState.FAILED, Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> operation(
                        ProviderModelDiscoveryOperationState.CANCELLED,
                        Optional.empty(),
                        Optional.of("DISCOVERY_FAILED")));
    }

    @Test
    void 操作不能冒用别的草稿结果或旧代次结果() {
        var otherDraft = new ProviderModelPreviewResult("draft-other", 2, List.of(), false, NOW);
        var older = new ProviderModelPreviewResult("draft-main", 1, List.of(), false, NOW);

        assertThrows(
                IllegalArgumentException.class,
                () -> operation(
                        ProviderModelDiscoveryOperationState.SUCCEEDED, Optional.of(otherDraft), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> operation(ProviderModelDiscoveryOperationState.SUCCEEDED, Optional.of(older), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderModelPreviewOperation(
                        "preview-main",
                        0,
                        "draft-main",
                        2,
                        ProviderModelDiscoveryOperationState.RUNNING,
                        Optional.empty(),
                        Optional.empty(),
                        NOW,
                        NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderModelPreviewOperation(
                        "preview-main",
                        1,
                        "draft-main",
                        0,
                        ProviderModelDiscoveryOperationState.RUNNING,
                        Optional.empty(),
                        Optional.empty(),
                        NOW,
                        NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderModelPreviewOperation(
                        "preview-main",
                        1,
                        "draft-main",
                        2,
                        ProviderModelDiscoveryOperationState.RUNNING,
                        Optional.empty(),
                        Optional.empty(),
                        NOW,
                        NOW.minusSeconds(1)));
    }

    @Test
    void 操作拒绝空状态空容器和包含自由文本的错误码() {
        assertThrows(NullPointerException.class, () -> operation(null, Optional.empty(), Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () -> operation(ProviderModelDiscoveryOperationState.RUNNING, null, Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () -> operation(ProviderModelDiscoveryOperationState.RUNNING, Optional.empty(), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> operation(
                        ProviderModelDiscoveryOperationState.FAILED,
                        Optional.empty(),
                        Optional.of("request failed with secret")));
        assertThrows(
                NullPointerException.class,
                () -> new ProviderModelPreviewOperation(
                        "preview-main",
                        1,
                        "draft-main",
                        2,
                        ProviderModelDiscoveryOperationState.RUNNING,
                        Optional.empty(),
                        Optional.empty(),
                        null,
                        NOW));
        assertThrows(
                NullPointerException.class,
                () -> new ProviderModelPreviewOperation(
                        "preview-main",
                        1,
                        "draft-main",
                        2,
                        ProviderModelDiscoveryOperationState.RUNNING,
                        Optional.empty(),
                        Optional.empty(),
                        NOW,
                        null));
    }

    private static ProviderModelPreviewOperation operation(
            ProviderModelDiscoveryOperationState state,
            Optional<ProviderModelPreviewResult> result,
            Optional<String> failure) {
        return new ProviderModelPreviewOperation("preview-main", 1, "draft-main", 2, state, result, failure, NOW, NOW);
    }

    private static ProviderModelPreviewResult result() {
        return new ProviderModelPreviewResult("draft-main", 2, List.of(candidate("chat")), false, NOW);
    }

    private static ProviderModelDiscoveryCandidate candidate(String id) {
        return new ProviderModelDiscoveryCandidate(id, id, Set.of(), OptionalInt.empty());
    }
}
