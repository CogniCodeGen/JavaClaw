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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryV3BehaviorTest {
    @Test
    void legacySearchExcludesConditionalMemoryWhileV2UsesHalfOpenTimeInterval() throws Exception {
        var support = new BuiltinExtensionTestSupport();
        var memory = support.start(new MemoryExtension());
        create(support, memory, "timed", "临时办公室在上海");
        memory.command(support.request(
                "effectivity/update",
                new MemoryV3Contracts.EffectivityUpdate(
                        "timed",
                        Optional.of(BuiltinExtensionTestSupport.NOW),
                        Optional.of(BuiltinExtensionTestSupport.NOW.plusSeconds(60)),
                        ""),
                Optional.of("effect"),
                1));
        var search = new MemoryContracts.SearchRequest("workspace", Set.of(), Set.of(), 100);
        var legacy = support.decode(
                memory.query(support.request("search", search, Optional.empty(), 0)),
                MemoryContracts.SearchResult.class);
        var modern = support.decode(
                memory.query(support.request("search/v2", search, Optional.empty(), 0)),
                MemoryV3Contracts.SearchResult.class);
        assertTrue(legacy.matches().isEmpty());
        assertEquals(1, modern.matches().size());
        var effect = modern.matches().getFirst().effectivity();
        assertTrue(effect.effectiveAt(BuiltinExtensionTestSupport.NOW));
        assertFalse(effect.effectiveAt(BuiltinExtensionTestSupport.NOW.plusSeconds(60)));
        assertThrows(
                IllegalArgumentException.class,
                () -> memory.command(support.request(
                        "update/content",
                        new MemoryContracts.ContentUpdateRequest(
                                "timed", MemoryContracts.MemoryKind.FACT, "workspace", "抹去条件"),
                        Optional.of("legacy-update"),
                        2)));
    }

    @Test
    void naturalLanguageConditionNeverEntersAutomaticSearchAndGraphSelectionIsRevisionBound() throws Exception {
        var support = new BuiltinExtensionTestSupport();
        var memory = support.start(new MemoryExtension());
        create(support, memory, "condition", "使用方案甲");
        memory.command(support.request(
                "effectivity/update",
                new MemoryV3Contracts.EffectivityUpdate("condition", Optional.empty(), Optional.empty(), "客户同意后"),
                Optional.of("condition"),
                1));
        var query = new MemoryContracts.SearchRequest("workspace", Set.of(), Set.of(), 10);
        assertTrue(support.decode(
                        memory.query(support.request("search/v2", query, Optional.empty(), 0)),
                        MemoryV3Contracts.SearchResult.class)
                .matches()
                .isEmpty());
        var graph = support.decode(
                memory.query(
                        support.request("graph/read", new MemoryV3Contracts.GraphRequest("", 20), Optional.empty(), 0)),
                MemoryV3Contracts.Graph.class);
        assertEquals("客户同意后", graph.nodes().getFirst().condition());
        assertFalse(graph.nodes().getFirst().effective());
        assertThrows(
                IllegalArgumentException.class,
                () -> memory.query(support.request(
                        "view.graph.memory",
                        new ViewQueryRequest(
                                "graphMemory", Map.of("id", "condition", "revision", "1"), "", 1, Optional.empty()),
                        Optional.empty(),
                        0)));
        var selected = support.decode(
                memory.query(support.request(
                        "view.graph.memory",
                        new ViewQueryRequest(
                                "graphMemory", Map.of("id", "condition", "revision", "2"), "", 1, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
        assertEquals(2, selected.revision());
    }

    @Test
    void replacementAtomicallySupersedesOldMemoryAndPreservesLegacyHistory() throws Exception {
        var support = new BuiltinExtensionTestSupport();
        var memory = support.start(new MemoryExtension());
        create(support, memory, "old", "办公地点北京");
        assertConflicts(support, memory, 0);
        seedConflict(support);
        assertConflicts(support, memory, 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> memory.command(support.request(
                        "proposal/accept",
                        new MemoryContracts.ProposalDecision("candidate"),
                        Optional.of("bypass"),
                        1)));
        var decision = new MemoryV3Contracts.ConflictDecision(
                "office",
                MemoryV3Contracts.Resolution.REPLACE,
                Map.of("old", 1L),
                1,
                "办公地点上海",
                Optional.empty(),
                Optional.empty(),
                "");
        memory.command(support.request("conflict/resolve", decision, Optional.of("resolve"), 1));
        memory.command(support.request("conflict/resolve", decision, Optional.of("resolve"), 1));
        assertConflicts(support, memory, 0);
        var results = support.decode(
                memory.query(support.request(
                        "search",
                        new MemoryContracts.SearchRequest("workspace", Set.of(), Set.of(), 100),
                        Optional.empty(),
                        0)),
                MemoryContracts.SearchResult.class);
        assertEquals(
                List.of("resolved-office"),
                results.matches().stream().map(MemoryContracts.Memory::id).toList());
        var graph = support.decode(
                memory.query(
                        support.request("graph/read", new MemoryV3Contracts.GraphRequest("", 20), Optional.empty(), 0)),
                MemoryV3Contracts.Graph.class);
        assertEquals(List.of(new MemoryV3Contracts.GraphEdge("old", "resolved-office", "REPLACED_BY")), graph.edges());
        assertThrows(
                IllegalArgumentException.class,
                () -> memory.command(support.request(
                        "restore", new MemoryContracts.RestoreRequest("old", 1), Optional.of("resurrect"), 2)));
    }

    private static void assertConflicts(
            BuiltinExtensionTestSupport support, BuiltinExtensionTestSupport.Started memory, int count)
            throws Exception {
        var result = support.decode(
                memory.query(support.request(
                        "view.conflicts",
                        new ViewQueryRequest("conflicts", Map.of(), "", 100, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
        assertEquals(count, result.rows().size());
        assertTrue(result.values().json().contains(count == 0 ? "当前没有" : "1 项"));
        assertTrue(MemoryGraphView.create().nodes().stream()
                .anyMatch(node -> node.id().equals("graph-conflict-notice")));
        assertTrue(MemoryGraphView.create().nodes().stream()
                .anyMatch(node -> node.id().equals("conflict-resolution")));
    }

    static void seedConflict(BuiltinExtensionTestSupport support) throws Exception {
        var candidate = new MemoryContracts.LearningProposalRequest(
                "candidate",
                MemoryContracts.MemoryKind.FACT,
                "workspace",
                "办公地点上海",
                Set.of("office"),
                new MemoryContracts.Source(
                        support.workspaceId,
                        com.javaclaw.api.ThreadId.random(),
                        com.javaclaw.api.ItemId.random(),
                        "办公地点上海"));
        var now = BuiltinExtensionTestSupport.NOW;
        support.store.inTransaction(MemoryStoreAccess.ID, transaction -> {
            transaction.put(
                    MemoryCollectionNames.proposals(support.workspaceId),
                    "candidate",
                    0,
                    support.payloads.encode(new MemoryContracts.Proposal(
                            "candidate",
                            1,
                            candidate,
                            Set.of(MemoryContracts.ProposalConcern.CONFLICT),
                            MemoryContracts.ProposalState.PENDING,
                            Optional.empty(),
                            now,
                            now)));
            transaction.put(
                    MemorySemantics.conflicts(support.workspaceId),
                    "office",
                    0,
                    support.payloads.encode(new MemoryV3Contracts.Conflict(
                            "office",
                            1,
                            "candidate",
                            Set.of("old"),
                            "a".repeat(64),
                            MemoryV3Contracts.ConflictState.PENDING,
                            now)));
            return null;
        });
    }

    static void create(
            BuiltinExtensionTestSupport support, BuiltinExtensionTestSupport.Started memory, String id, String text)
            throws Exception {
        memory.command(support.request(
                "create",
                new MemoryContracts.CreateRequest(
                        id,
                        MemoryContracts.MemoryKind.FACT,
                        "workspace",
                        text,
                        Set.of("office"),
                        false,
                        Optional.empty()),
                Optional.of("create-" + id),
                0));
    }
}
