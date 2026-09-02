package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.extension.spi.ExtensionContext;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillManagementViewsCoverageTest {
    @Test
    void newDraftViewIsEmptyAndRejectsEveryUnsupportedSelector() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SkillExtension());

        ViewQueryResult result = view(support, started, "view.new-draft", "newDraft", Map.of(), "", 10);

        assertTrue(result.rows().isEmpty());
        assertEquals("{}", result.values().json());
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, "view.new-draft", "wrong", Map.of(), "", 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, "view.new-draft", "newDraft", Map.of("id", "x"), "", 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, "view.new-draft", "newDraft", Map.of(), "cursor", 10));
    }

    @Test
    void draftListPaginatesAndIncludesPublishedRevision() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SkillExtension());
        save(support, started, "alpha", 0, "第一版", "alpha-create");
        SkillContracts.Draft alpha = save(support, started, "alpha", 1, "第二版", "alpha-update");
        save(support, started, "beta", 0, "Beta", "beta-create");
        started.command(support.request(
                "publish",
                new SkillContracts.PublishRequest(alpha.id(), alpha.revision()),
                Optional.of("alpha-publish"),
                0));

        ViewQueryResult first = view(support, started, "view.drafts", "drafts", Map.of(), "", 1);
        ViewQueryResult second = view(support, started, "view.drafts", "drafts", Map.of(), first.nextCursor(), 10);

        assertTrue(first.hasMore());
        assertFalse(first.nextCursor().isBlank());
        assertFalse(second.hasMore());
        assertEquals(1, second.rows().size());
        assertTrue(first.rows().getFirst().json().contains("\"publishedRevision\":1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, "view.drafts", "drafts", Map.of("unsupported", "x"), "", 10));
    }

    @Test
    void exactDraftAndResourceViewsFailClosedOnIncompleteOrStaleSelection() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SkillExtension());
        SkillContracts.Draft draft = save(support, started, "selected", 0, "内容", "selected-create");
        Map<String, String> selected = Map.of("id", draft.id(), "revision", "1");

        ViewQueryResult editor = view(support, started, "view.draft", "draftEditor", selected, "", 10);
        ViewQueryResult resources = view(support, started, "view.resources", "draftResources", selected, "", 10);

        assertEquals(1, editor.revision());
        assertTrue(editor.values().json().contains("\"id\":\"selected\""));
        assertTrue(resources.rows().isEmpty());
        assertEquals(1, resources.revision());
        assertDraftSelectionRejected(support, started, "view.draft", "draftEditor");
        assertDraftSelectionRejected(support, started, "view.resources", "draftResources");
    }

    @Test
    void tombstoneAndHistoryViewsExposeSourceRevisionAndCursorSemantics() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SkillExtension());
        save(support, started, "history", 0, "第一版", "history-create");
        save(support, started, "history", 1, "第二版", "history-update");
        started.command(support.request(
                "draft/tombstone", new SkillContracts.Key("history"), Optional.of("history-delete"), 2));
        ViewQueryResult tombstones = view(support, started, "view.tombstones", "tombstones", Map.of(), "", 1);
        started.command(support.request(
                "draft/restore",
                new SkillContracts.DraftRestoreRequest("history", 1),
                Optional.of("history-restore"),
                3));

        ViewQueryResult first = view(support, started, "view.history", "history", Map.of("id", "history"), "", 1);
        ViewQueryResult second =
                view(support, started, "view.history", "history", Map.of("id", "history"), first.nextCursor(), 20);

        assertEquals(1, tombstones.rows().size());
        assertTrue(tombstones.rows().getFirst().json().contains("\"sourceRevision\":2"));
        assertTrue(first.hasMore());
        assertTrue(second.rows().stream().anyMatch(row -> row.json().contains("\"tombstone\":true")));
        assertEquals(4, second.revision());
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, "view.tombstones", "tombstones", Map.of("id", "x"), "", 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, "view.history", "history", Map.of(), "", 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, "view.history", "history", Map.of("id", "history"), "-1", 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, "view.history", "history", Map.of("id", "history"), "bad", 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, "view.history", "history", Map.of("id", "missing"), "", 10));
    }

    @Test
    void proposalAndPublishedViewsPageAcrossPendingRejectedAndAdoptedStates() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SkillExtension());
        propose(support, started, "adopted", "adopted-draft");
        propose(support, started, "rejected", "rejected-draft");
        propose(support, started, "pending", "pending-draft");
        started.command(support.request(
                "proposal/adopt", new SkillContracts.ProposalDecision("adopted"), Optional.of("adopt"), 1));
        started.command(support.request(
                "proposal/reject", new SkillContracts.ProposalDecision("rejected"), Optional.of("reject"), 1));
        SkillContracts.Draft adopted = support.decode(
                started.query(
                        support.request("draft/read", new SkillContracts.Key("adopted-draft"), Optional.empty(), 0)),
                SkillContracts.Draft.class);
        started.command(support.request(
                "publish",
                new SkillContracts.PublishRequest(adopted.id(), adopted.revision()),
                Optional.of("publish-adopted"),
                0));

        ViewQueryResult proposals = view(support, started, "view.proposals", "proposals", Map.of(), "", 1);
        ViewQueryResult proposalRest =
                view(support, started, "view.proposals", "proposals", Map.of(), proposals.nextCursor(), 10);
        ViewQueryResult published = view(support, started, "view.published", "published", Map.of(), "", 1);

        assertTrue(proposals.hasMore());
        assertEquals(2, proposalRest.rows().size());
        assertEquals(1, published.rows().size());
        assertTrue(published.rows().getFirst().json().contains("\"enabled\":false"));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, "view.proposals", "proposals", Map.of("id", "x"), "", 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, "view.published", "published", Map.of("id", "x"), "", 10));
    }

    @Test
    void viewProjectionRejectsManagedStoreRevisionCorruption() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SkillExtension());
        SkillContracts.Draft corrupt =
                new SkillContracts.Draft("corrupt", 9, "损坏", "revision 不一致", "不得展示", List.of(), NOW, NOW);
        support.store.put("drafts." + support.workspaceId, corrupt.id(), 0, support.payloads.encode(corrupt));

        assertThrows(
                IllegalStateException.class, () -> view(support, started, "view.drafts", "drafts", Map.of(), "", 10));
    }

    @Test
    void extensionLifecycleRejectsDoubleStartAndCallsAfterClose() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SkillExtension extension = new SkillExtension();
        var started = support.start(extension);

        assertThrows(
                IllegalStateException.class,
                () -> extension.start(new ExtensionContext(support.clock, support.payloads)));
        extension.close();
        assertThrows(IllegalStateException.class, extension::schemas);
        assertThrows(
                IllegalStateException.class,
                () -> started.query(
                        support.request("view.new-draft", query("newDraft", Map.of(), "", 10), Optional.empty(), 0)));
    }

    private static void assertDraftSelectionRejected(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            String operation,
            String dataSource) {
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, operation, "wrong", Map.of("id", "selected", "revision", "1"), "", 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(
                        support,
                        started,
                        operation,
                        dataSource,
                        Map.of("id", "selected", "revision", "1"),
                        "cursor",
                        10));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, operation, dataSource, Map.of("id", "selected"), "", 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(
                        support, started, operation, dataSource, Map.of("id", "selected", "revision", "bad"), "", 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, operation, dataSource, Map.of("id", "selected", "revision", "0"), "", 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, operation, dataSource, Map.of("id", "selected", "revision", "2"), "", 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, operation, dataSource, Map.of("id", "missing", "revision", "1"), "", 10));
    }

    private static SkillContracts.Draft save(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            String id,
            long revision,
            String instructions,
            String key)
            throws Exception {
        return support.decode(
                started.command(support.request(
                        "draft/save-content",
                        new SkillContracts.SaveContentRequest(id, id, "说明", instructions),
                        Optional.of(key),
                        revision)),
                SkillContracts.Draft.class);
    }

    private static void propose(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            String proposalId,
            String draftId)
            throws Exception {
        started.command(support.request(
                "proposal/submit",
                new SkillContracts.ProposeRequest(proposalId, draftId, draftId, "说明", "安全指令"),
                Optional.of("propose-" + proposalId),
                0));
    }

    private static ViewQueryResult view(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            String operation,
            String dataSource,
            Map<String, String> arguments,
            String cursor,
            int limit)
            throws Exception {
        return support.decode(
                started.query(
                        support.request(operation, query(dataSource, arguments, cursor, limit), Optional.empty(), 0)),
                ViewQueryResult.class);
    }

    private static ViewQueryRequest query(String dataSource, Map<String, String> arguments, String cursor, int limit) {
        return new ViewQueryRequest(dataSource, arguments, cursor, limit, Optional.empty());
    }
}
