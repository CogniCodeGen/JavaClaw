package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryConflictDecisionTest {
    @Test
    void keepExistingRejectsProposalAndFurtherDecisionsRequireAPendingConflict() throws Exception {
        var fixture = new Fixture();
        fixture.resolve(
                decision(MemoryV3Contracts.Resolution.KEEP_EXISTING, 1, "", Optional.empty(), Optional.empty(), ""), 1);
        var proposal = fixture.support.decode(
                fixture.memory.query(fixture.support.request(
                        "proposal/read", new MemoryContracts.Key("candidate"), Optional.empty(), 0)),
                MemoryContracts.Proposal.class);
        assertEquals(MemoryContracts.ProposalState.REJECTED, proposal.state());
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.resolve(
                        decision(
                                MemoryV3Contracts.Resolution.REPLACE, 1, "新正文", Optional.empty(), Optional.empty(), ""),
                        2));
        assertEquals(
                List.of("old"),
                fixture.search().matches().stream()
                        .map(value -> value.memory().id())
                        .toList());
    }

    @Test
    void decisionsRequireEveryCurrentParticipantVersionAndNonemptyConfirmedReplacement() throws Exception {
        var fixture = new Fixture();
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.resolve(
                        new MemoryV3Contracts.ConflictDecision(
                                "office",
                                MemoryV3Contracts.Resolution.REPLACE,
                                Map.of(),
                                1,
                                "新正文",
                                Optional.empty(),
                                Optional.empty(),
                                ""),
                        1));
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.resolve(
                        decision(
                                MemoryV3Contracts.Resolution.REPLACE, 2, "新正文", Optional.empty(), Optional.empty(), ""),
                        1));
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.resolve(
                        decision(MemoryV3Contracts.Resolution.MERGE, 1, "", Optional.empty(), Optional.empty(), ""),
                        1));
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.resolve(
                        decision(MemoryV3Contracts.Resolution.MERGE, 1, "合并正文", Optional.empty(), Optional.empty(), ""),
                        2));
        fixture.resolve(
                decision(MemoryV3Contracts.Resolution.MERGE, 1, "合并正文", Optional.empty(), Optional.empty(), ""), 1);
        assertEquals("合并正文", fixture.search().matches().getFirst().memory().content());
    }

    @Test
    void coexistenceRequiresUnambiguousDisjointTimesOrAnExplicitManualCondition() throws Exception {
        var fixture = new Fixture();
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.resolve(
                        decision(
                                MemoryV3Contracts.Resolution.COEXIST,
                                1,
                                "条件正文",
                                Optional.empty(),
                                Optional.empty(),
                                ""),
                        1));
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.resolve(
                        decision(
                                MemoryV3Contracts.Resolution.COEXIST,
                                1,
                                "条件正文",
                                Optional.of(BuiltinExtensionTestSupport.NOW),
                                Optional.empty(),
                                ""),
                        1));
        fixture.resolve(
                decision(MemoryV3Contracts.Resolution.COEXIST, 1, "条件正文", Optional.empty(), Optional.empty(), "用户同意后"),
                1);
        assertEquals(
                List.of("old"),
                fixture.search().matches().stream()
                        .map(value -> value.memory().id())
                        .toList());
        var graph = fixture.support.decode(
                fixture.memory.query(fixture.support.request(
                        "graph/read", new MemoryV3Contracts.GraphRequest("", 20), Optional.empty(), 0)),
                MemoryV3Contracts.Graph.class);
        assertEquals(2, graph.nodes().size());
        assertTrue(graph.nodes().stream()
                .filter(node -> node.id().equals("resolved-office"))
                .noneMatch(MemoryV3Contracts.GraphNode::effective));
    }

    @Test
    void adjacentHalfOpenIntervalsCanCoexistInEitherTemporalOrder() throws Exception {
        for (boolean earlier : List.of(true, false)) {
            var fixture = new Fixture();
            var boundary = BuiltinExtensionTestSupport.NOW.plusSeconds(60);
            fixture.memory.command(fixture.support.request(
                    "effectivity/update",
                    new MemoryV3Contracts.EffectivityUpdate(
                            "old",
                            earlier ? Optional.of(boundary) : Optional.empty(),
                            earlier ? Optional.empty() : Optional.of(boundary),
                            ""),
                    Optional.of("old-time"),
                    1));
            fixture.resolve(
                    decision(
                            MemoryV3Contracts.Resolution.COEXIST,
                            2,
                            "新时期正文",
                            earlier ? Optional.empty() : Optional.of(boundary),
                            earlier ? Optional.of(boundary) : Optional.empty(),
                            ""),
                    1);
            assertEquals(1, fixture.search().matches().size());
        }
    }

    @Test
    void nativeConflictEditorBindsCurrentVersionsAndRejectsStaleSelections() throws Exception {
        var fixture = new Fixture();
        var query = new ViewQueryRequest("conflict", Map.of("id", "office", "revision", "1"), "", 1, Optional.empty());
        var view = fixture.support.decode(
                fixture.memory.query(fixture.support.request("view.conflict", query, Optional.empty(), 0)),
                ViewQueryResult.class);
        assertEquals(1, view.revision());
        assertTrue(view.values().json().contains("expectedMemoryRevisions"));
        for (Map<String, String> arguments : List.of(Map.of("id", "office"), Map.of("id", "office", "revision", "2"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.memory.query(fixture.support.request(
                            "view.conflict",
                            new ViewQueryRequest("conflict", arguments, "", 1, Optional.empty()),
                            Optional.empty(),
                            0)));
        }
        fixture.memory.command(fixture.support.request(
                "conflict/form/resolve",
                Map.of(
                        "id",
                        "office",
                        "resolution",
                        "KEEP_EXISTING",
                        "expectedMemoryRevisions",
                        Map.of("old", 1),
                        "expectedMemoryRevision",
                        1,
                        "content",
                        "",
                        "validFrom",
                        "",
                        "validUntil",
                        "",
                        "condition",
                        ""),
                Optional.of("native-decision"),
                1));
        assertEquals(
                List.of("old"),
                fixture.search().matches().stream()
                        .map(value -> value.memory().id())
                        .toList());
    }

    @Test
    void addingAnotherMemoryIdAfterEditorReadInvalidatesTheFrozenWorkspaceHead() throws Exception {
        var fixture = new Fixture();
        var stale = decision(MemoryV3Contracts.Resolution.REPLACE, 1, "新办公室", Optional.empty(), Optional.empty(), "");
        MemoryV3BehaviorTest.create(fixture.support, fixture.memory, "concurrent", "其他客户端确认的办公室");
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.memory.command(
                        fixture.support.request("conflict/resolve", stale, Optional.of("stale-head"), 1)));
        assertEquals(2, fixture.search().matches().size());
        assertTrue(fixture.search().matches().stream()
                .noneMatch(value -> value.memory().id().equals("resolved-office")));
    }

    private static MemoryV3Contracts.ConflictDecision decision(
            MemoryV3Contracts.Resolution resolution,
            long revision,
            String text,
            Optional<java.time.Instant> from,
            Optional<java.time.Instant> until,
            String condition) {
        return new MemoryV3Contracts.ConflictDecision(
                "office", resolution, Map.of("old", revision), 1, text, from, until, condition);
    }

    private static final class Fixture {
        private final BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        private final BuiltinExtensionTestSupport.Started memory;
        private int identity;

        private Fixture() throws Exception {
            memory = support.start(new MemoryExtension());
            MemoryV3BehaviorTest.create(support, memory, "old", "办公地点北京");
            MemoryV3BehaviorTest.seedConflict(support);
        }

        private void resolve(MemoryV3Contracts.ConflictDecision decision, long revision) throws Exception {
            var current = new MemoryV3Contracts.ConflictDecision(
                    decision.id(),
                    decision.resolution(),
                    decision.expectedMemoryRevisions(),
                    search().memoryRevision(),
                    decision.content(),
                    decision.validFrom(),
                    decision.validUntil(),
                    decision.condition());
            memory.command(
                    support.request("conflict/resolve", current, Optional.of("decision-" + identity++), revision));
        }

        private MemoryV3Contracts.SearchResult search() throws Exception {
            return support.decode(
                    memory.query(support.request(
                            "search/v2",
                            new MemoryContracts.SearchRequest("workspace", Set.of(), Set.of(), 100),
                            Optional.empty(),
                            0)),
                    MemoryV3Contracts.SearchResult.class);
        }
    }
}
