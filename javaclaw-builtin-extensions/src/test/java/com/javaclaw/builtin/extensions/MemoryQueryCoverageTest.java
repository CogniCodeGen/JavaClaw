package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ItemId;
import com.javaclaw.api.ThreadId;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryQueryCoverageTest {
    @Test
    void directQueriesReadListSearchHistorySettingsStatsAndProposals() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new MemoryExtension());
        create(support, started, "alpha", "Alpha Java", true, Optional.of(source(support, "Alpha Java")));
        create(support, started, "beta", "Beta Kotlin", false, Optional.empty());
        submitProposal(support, started, "proposal", "Gamma Rust");

        MemoryContracts.Memory read = support.decode(
                started.query(support.request("read", new MemoryContracts.Key("alpha"), Optional.empty(), 0)),
                MemoryContracts.Memory.class);
        DocumentContracts.Page memories = support.decode(
                started.query(support.request("list", new DocumentContracts.PageRequest("", 10), Optional.empty(), 0)),
                DocumentContracts.Page.class);
        MemoryContracts.SearchResult search = support.decode(
                started.query(support.request(
                        "search",
                        new MemoryContracts.SearchRequest("java", Set.of("workspace"), Set.of("language"), 10),
                        Optional.empty(),
                        0)),
                MemoryContracts.SearchResult.class);
        MemoryContracts.HistoryPage history = support.decode(
                started.query(support.request(
                        "history", new MemoryContracts.HistoryRequest("alpha", 0, 1), Optional.empty(), 0)),
                MemoryContracts.HistoryPage.class);
        MemoryContracts.LearningSettings settings = support.decode(
                started.query(support.request("settings/read", Map.of(), Optional.empty(), 0)),
                MemoryContracts.LearningSettings.class);
        MemoryContracts.Stats stats = support.decode(
                started.query(support.request("stats", Map.of(), Optional.empty(), 0)), MemoryContracts.Stats.class);
        MemoryContracts.Proposal proposal = support.decode(
                started.query(
                        support.request("proposal/read", new MemoryContracts.Key("proposal"), Optional.empty(), 0)),
                MemoryContracts.Proposal.class);
        DocumentContracts.Page proposals = support.decode(
                started.query(support.request(
                        "proposal/list", new DocumentContracts.PageRequest("", 10), Optional.empty(), 0)),
                DocumentContracts.Page.class);

        assertEquals("alpha", read.id());
        assertEquals(2, memories.documents().size());
        assertEquals(
                List.of("alpha"),
                search.matches().stream().map(MemoryContracts.Memory::id).toList());
        assertEquals(1, history.entries().size());
        assertFalse(history.hasMore());
        assertEquals(MemoryContracts.LearningPolicy.SUGGEST, settings.policy());
        assertEquals(2, stats.active());
        assertEquals(1, stats.pinned());
        assertEquals(1, stats.pendingProposals());
        assertEquals(MemoryContracts.ProposalState.PENDING, proposal.state());
        assertEquals(1, proposals.documents().size());
    }

    @Test
    void memoryListViewUsesStableCursorAndPreservesOptionalSourceIdentity() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new MemoryExtension());
        MemoryContracts.Source source = source(support, "Alpha Java");
        create(support, started, "alpha", "Alpha Java", true, Optional.of(source));
        create(support, started, "beta", "Beta Kotlin", false, Optional.empty());

        ViewQueryResult first = query(
                support, started, "view.memories", new ViewQueryRequest("memories", Map.of(), "", 1, Optional.empty()));
        ViewQueryResult second = query(
                support,
                started,
                "view.memories",
                new ViewQueryRequest("memories", Map.of(), first.nextCursor(), 1, Optional.empty()));
        Map<?, ?> firstRow = support.payloads.decode(first.rows().getFirst(), Map.class);
        Map<?, ?> secondRow = support.payloads.decode(second.rows().getFirst(), Map.class);

        assertTrue(first.hasMore());
        assertEquals("alpha", first.nextCursor());
        assertEquals(source.threadId().toString(), firstRow.get("sourceThreadId"));
        assertEquals(source.itemId().toString(), firstRow.get("sourceItemId"));
        assertFalse(second.hasMore());
        assertEquals("", second.nextCursor());
        assertEquals("", secondRow.get("sourceThreadId"));
        assertThrows(
                IllegalArgumentException.class,
                () -> query(
                        support,
                        started,
                        "view.memories",
                        new ViewQueryRequest("memories", Map.of("unexpected", "x"), "", 10, Optional.empty())));
    }

    @Test
    void exactEditorRejectsStaleMalformedAndIncompleteSelections() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new MemoryExtension());
        create(support, started, "alpha", "Alpha", false, Optional.empty());

        ViewQueryResult editor = query(
                support,
                started,
                "view.memory",
                new ViewQueryRequest("memory", Map.of("id", "alpha", "revision", "1"), "", 10, Optional.of("alpha")));
        MemoryContracts.Memory current = support.payloads.decode(editor.values(), MemoryContracts.Memory.class);

        assertEquals("alpha", current.id());
        assertEquals(1, editor.revision());
        assertThrows(
                IllegalArgumentException.class,
                () -> query(
                        support,
                        started,
                        "view.memory",
                        new ViewQueryRequest("memory", Map.of("id", "alpha"), "", 10, Optional.empty())));
        assertThrows(
                IllegalArgumentException.class,
                () -> query(
                        support,
                        started,
                        "view.memory",
                        new ViewQueryRequest(
                                "memory", Map.of("id", "alpha", "revision", "0"), "", 10, Optional.empty())));
        assertThrows(
                IllegalArgumentException.class,
                () -> query(
                        support,
                        started,
                        "view.memory",
                        new ViewQueryRequest(
                                "memory", Map.of("id", "alpha", "revision", "abc"), "", 10, Optional.empty())));
        assertThrows(
                IllegalArgumentException.class,
                () -> query(
                        support,
                        started,
                        "view.memory",
                        new ViewQueryRequest(
                                "memory", Map.of("id", "alpha", "revision", "2"), "", 10, Optional.empty())));
    }

    @Test
    void historyAndTombstoneViewsExposeRestorableSourceRevisionAndValidateCursor() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new MemoryExtension());
        create(support, started, "alpha", "first", false, Optional.empty());
        started.command(support.request(
                "update",
                new MemoryContracts.UpdateRequest(
                        "alpha",
                        MemoryContracts.MemoryKind.FACT,
                        "workspace",
                        "second",
                        Set.of("language"),
                        false,
                        Optional.empty()),
                Optional.of("update-alpha"),
                1));

        ViewQueryResult first = query(
                support,
                started,
                "view.history",
                new ViewQueryRequest("history", Map.of("id", "alpha"), "", 1, Optional.empty()));
        ViewQueryResult second = query(
                support,
                started,
                "view.history",
                new ViewQueryRequest("history", Map.of("id", "alpha"), first.nextCursor(), 1, Optional.empty()));
        assertTrue(first.hasMore());
        assertEquals("1", first.nextCursor());
        assertFalse(second.hasMore());
        assertThrows(
                IllegalArgumentException.class,
                () -> query(
                        support,
                        started,
                        "view.history",
                        new ViewQueryRequest("history", Map.of("id", "alpha"), "-1", 10, Optional.empty())));
        assertThrows(
                IllegalArgumentException.class,
                () -> query(
                        support,
                        started,
                        "view.history",
                        new ViewQueryRequest("history", Map.of("id", "alpha"), "bad", 10, Optional.empty())));

        started.command(support.request("tombstone", new MemoryContracts.Key("alpha"), Optional.of("delete-alpha"), 2));
        ViewQueryResult tombstones = query(
                support,
                started,
                "view.tombstones",
                new ViewQueryRequest("tombstones", Map.of(), "", 10, Optional.empty()));
        Map<?, ?> row = support.payloads.decode(tombstones.rows().getFirst(), Map.class);
        assertEquals(3, ((Number) row.get("revision")).longValue());
        assertEquals(2, ((Number) row.get("sourceRevision")).longValue());
        assertThrows(
                IllegalArgumentException.class,
                () -> query(
                        support,
                        started,
                        "view.tombstones",
                        new ViewQueryRequest("tombstones", Map.of("unexpected", "x"), "", 10, Optional.empty())));
    }

    @Test
    void newStatsSettingsAndProposalViewsUseTypedValuesAndRejectArguments() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new MemoryExtension());
        create(support, started, "alpha", "Alpha", true, Optional.empty());
        submitProposal(support, started, "proposal", "Gamma");

        ViewQueryResult empty = query(
                support,
                started,
                "view.new-memory",
                new ViewQueryRequest("newMemory", Map.of(), "", 10, Optional.empty()));
        ViewQueryResult stats = query(
                support, started, "view.stats", new ViewQueryRequest("stats", Map.of(), "", 10, Optional.empty()));
        ViewQueryResult settings = query(
                support,
                started,
                "view.settings",
                new ViewQueryRequest("settings", Map.of(), "", 10, Optional.empty()));
        ViewQueryResult proposals = query(
                support,
                started,
                "view.proposals",
                new ViewQueryRequest("proposals", Map.of(), "", 10, Optional.empty()));

        assertTrue(empty.rows().isEmpty());
        assertEquals(1, stats.rows().size());
        assertEquals(
                MemoryContracts.LearningPolicy.SUGGEST,
                support.payloads
                        .decode(settings.values(), MemoryContracts.LearningSettings.class)
                        .policy());
        assertEquals(1, proposals.rows().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> query(
                        support,
                        started,
                        "view.new-memory",
                        new ViewQueryRequest("wrong", Map.of(), "", 10, Optional.empty())));
        assertThrows(
                IllegalArgumentException.class,
                () -> query(
                        support,
                        started,
                        "view.stats",
                        new ViewQueryRequest("stats", Map.of(), "cursor", 10, Optional.empty())));
        assertThrows(
                IllegalArgumentException.class,
                () -> query(
                        support,
                        started,
                        "view.settings",
                        new ViewQueryRequest("settings", Map.of("unexpected", "x"), "", 10, Optional.empty())));
        assertThrows(
                IllegalArgumentException.class,
                () -> query(
                        support,
                        started,
                        "view.proposals",
                        new ViewQueryRequest("proposals", Map.of("unexpected", "x"), "", 10, Optional.empty())));
    }

    private static ViewQueryResult query(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            String operation,
            ViewQueryRequest request)
            throws Exception {
        return support.decode(
                started.query(support.request(operation, request, Optional.empty(), 0)), ViewQueryResult.class);
    }

    private static void create(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            String id,
            String content,
            boolean pinned,
            Optional<MemoryContracts.Source> source)
            throws Exception {
        started.command(support.request(
                "create",
                new MemoryContracts.CreateRequest(
                        id, MemoryContracts.MemoryKind.FACT, "workspace", content, Set.of("language"), pinned, source),
                Optional.of("create-" + id),
                0));
    }

    private static void submitProposal(
            BuiltinExtensionTestSupport support, BuiltinExtensionTestSupport.Started started, String id, String content)
            throws Exception {
        started.command(support.request(
                "proposal/submit",
                new MemoryContracts.LearningProposalRequest(
                        id,
                        MemoryContracts.MemoryKind.PERSONA,
                        "workspace",
                        content,
                        Set.of("language"),
                        source(support, content)),
                Optional.of("submit-" + id),
                0));
    }

    private static MemoryContracts.Source source(BuiltinExtensionTestSupport support, String content) {
        return new MemoryContracts.Source(support.workspaceId, ThreadId.random(), ItemId.random(), content);
    }
}
