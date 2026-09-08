package com.javaclaw.builtin.contracts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryV3ContractsTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void effectivityDistinguishesUnconditionalHalfOpenAndUnmatchableConditions() {
        var legacy = MemoryV3Contracts.Effectivity.legacy("m");
        assertTrue(legacy.unconditional());
        assertTrue(legacy.effectiveAt(NOW));
        var time = effect(Optional.of(NOW), Optional.of(NOW.plusSeconds(10)), "");
        assertFalse(time.unconditional());
        assertFalse(time.effectiveAt(NOW.minusSeconds(1)));
        assertTrue(time.effectiveAt(NOW));
        assertFalse(time.effectiveAt(NOW.plusSeconds(10)));
        assertTrue(effect(Optional.empty(), Optional.of(NOW.plusSeconds(1)), "").effectiveAt(NOW));
        assertTrue(effect(Optional.of(NOW), Optional.empty(), "").effectiveAt(NOW));
        assertFalse(effect(Optional.empty(), Optional.empty(), "人工条件").unconditional());
        assertFalse(effect(Optional.empty(), Optional.empty(), "人工条件").effectiveAt(NOW));
        var superseded = new MemoryV3Contracts.Effectivity(
                "m",
                1,
                MemoryV3Contracts.SemanticState.SUPERSEDED,
                Optional.empty(),
                Optional.empty(),
                "",
                Set.of("replacement"));
        assertFalse(superseded.effectiveAt(NOW));
        assertThrows(IllegalArgumentException.class, () -> effect(Optional.of(NOW), Optional.of(NOW), ""));
        assertThrows(
                IllegalArgumentException.class, () -> effect(Optional.of(NOW), Optional.of(NOW.minusSeconds(1)), ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MemoryV3Contracts.Effectivity(
                        "m",
                        -1,
                        MemoryV3Contracts.SemanticState.ACTIVE,
                        Optional.empty(),
                        Optional.empty(),
                        "",
                        Set.of()));
    }

    @Test
    void graphWindowsAndPagesEnforceAllBoundarySizesAndImmutableCollections() {
        for (int limit : List.of(1, 200)) {
            assertEquals(limit, new MemoryV3Contracts.GraphRequest("", limit).limit());
        }
        for (int limit : List.of(0, 201)) {
            assertThrows(IllegalArgumentException.class, () -> new MemoryV3Contracts.GraphRequest("", limit));
        }
        assertEquals("m", new MemoryV3Contracts.GraphNeighbors("m", "", true, 50).id());
        for (int limit : List.of(0, 51)) {
            assertThrows(
                    IllegalArgumentException.class, () -> new MemoryV3Contracts.GraphNeighbors("m", "", false, limit));
        }
        assertEquals("query", new MemoryV3Contracts.GraphFilter(" query ", false).query());
        assertThrows(IllegalArgumentException.class, () -> new MemoryV3Contracts.GraphFilter("x".repeat(501), true));
        var ids = new ArrayList<>(List.of("m"));
        var window = new MemoryV3Contracts.GraphWindow("", true, ids);
        ids.clear();
        assertEquals(List.of("m"), window.nodeIds());
        assertThrows(
                IllegalArgumentException.class, () -> new MemoryV3Contracts.GraphWindow("", false, List.of("m", "m")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MemoryV3Contracts.GraphWindow(
                        "",
                        false,
                        java.util.stream.IntStream.range(0, 501)
                                .mapToObj(Integer::toString)
                                .toList()));
        assertThrows(IllegalArgumentException.class, () -> new MemoryV3Contracts.GraphWindow("", false, List.of(" ")));
        var graph = new MemoryV3Contracts.Graph(
                List.of(new MemoryV3Contracts.GraphNode(
                        "m", "记忆", 1, MemoryV3Contracts.SemanticState.ACTIVE, true, "")),
                List.of(new MemoryV3Contracts.GraphEdge("m", "n", "REPLACED_BY")),
                2,
                true);
        assertTrue(new MemoryV3Contracts.GraphPage(graph, "next", true).hasMore());
        assertThrows(UnsupportedOperationException.class, () -> graph.nodes().clear());
    }

    @Test
    void learningConflictAndSearchContractsPreserveVersionedMeaning() {
        var definition = new MemoryV3Contracts.LearningDefinition(
                "learning", 1, "学习", true, ExecutionOverrides.empty(), NOW.minusSeconds(30 * 86400), NOW);
        assertTrue(new MemoryV3Contracts.LearningSave(true, definition.execution(), false).enabled());
        assertEquals(1, definition.revision());
        var conflict = new MemoryV3Contracts.Conflict(
                "case", 1, "proposal", Set.of("m"), "digest", MemoryV3Contracts.ConflictState.PENDING, NOW);
        var decision = new MemoryV3Contracts.ConflictDecision(
                conflict.id(),
                MemoryV3Contracts.Resolution.COEXIST,
                Map.of("m", 1L),
                1,
                "正文",
                Optional.of(NOW),
                Optional.empty(),
                "");
        assertEquals(Map.of("m", 1L), decision.expectedMemoryRevisions());
        assertEquals("m", new MemoryV3Contracts.EffectivityUpdate("m", Optional.empty(), Optional.empty(), "条件").id());
        var memory = new MemoryContracts.Memory(
                "m",
                1,
                MemoryContracts.MemoryKind.FACT,
                "workspace",
                "正文",
                Set.of(),
                false,
                Optional.empty(),
                NOW,
                NOW);
        var match = new MemoryV3Contracts.SearchMatch(memory, MemoryV3Contracts.Effectivity.legacy("m"));
        assertEquals(List.of(match), new MemoryV3Contracts.SearchResult(List.of(match), NOW, 1).matches());
        assertThrows(
                IllegalArgumentException.class,
                () -> new MemoryV3Contracts.SearchMatch(memory, MemoryV3Contracts.Effectivity.legacy("other")));
        assertThrows(NullPointerException.class, () -> new MemoryV3Contracts.LearningSave(true, null, false));
    }

    @Test
    void confirmedGraphContractsRejectReservedRelationsAndInvalidEvidenceDigests() {
        var entity = new MemoryGraphContracts.Entity("m", 1, 2, "正文");
        var version = new MemoryGraphContracts.EntityVersion(entity.id(), entity.memoryRevision());
        var statement = new MemoryGraphContracts.Assertion("statement", 1, version, "REMEMBERS", version, "正文");
        var evidence = new MemoryGraphContracts.EvidenceReference(
                "source", "m", 2, WorkspaceId.random(), ThreadId.random(), ItemId.random(), "a".repeat(64));
        assertEquals(
                2,
                new MemoryGraphContracts.Projection(entity, List.of(statement), List.of(evidence), 2).memoryRevision());
        assertEquals("USES", new MemoryGraphContracts.ConfirmRelation("m", "n", "USES").predicate());
        for (String predicate : List.of("REMEMBERS", "REPLACED_BY", "x".repeat(65))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new MemoryGraphContracts.ConfirmRelation("m", "n", predicate));
        }
        assertThrows(IllegalArgumentException.class, () -> new MemoryGraphContracts.ConfirmRelation("m", "m", "USES"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MemoryGraphContracts.Assertion("a", 1, version, "USES", version, "模型猜测不能作为已确认关系正文"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MemoryGraphContracts.Assertion("a", 1, version, "x".repeat(65), version, ""));
        assertEquals("", new MemoryGraphContracts.Assertion("a", 1, version, "USES", version, "").text());
        for (String digest : java.util.Arrays.asList(null, "short")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new MemoryGraphContracts.EvidenceReference(
                            "e", "m", 1, WorkspaceId.random(), ThreadId.random(), ItemId.random(), digest));
        }
    }

    private static MemoryV3Contracts.Effectivity effect(
            Optional<Instant> from, Optional<Instant> until, String condition) {
        return new MemoryV3Contracts.Effectivity(
                "m", 1, MemoryV3Contracts.SemanticState.ACTIVE, from, until, condition, Set.of());
    }
}
