package com.javaclaw.builtin.extensions;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ItemId;
import com.javaclaw.api.ThreadId;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.MemoryGraphContracts;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryGraphProjectionTest {
    @Test
    void confirmedMemoryPersistsStatementAndExactEvidenceWhileProposalRemainsUnpublished() throws Exception {
        var support = new BuiltinExtensionTestSupport();
        var memory = support.start(new MemoryExtension());
        var source = new MemoryContracts.Source(support.workspaceId, ThreadId.random(), ItemId.random(), "项目使用 Java");
        create(support, memory, "java", "项目使用 Java", Optional.of(source));
        var projection = projection(support, memory, "java");
        assertEquals("java", projection.entity().id());
        assertEquals(1, projection.entity().memoryRevision());
        assertEquals(1, projection.assertions().size());
        assertEquals("REMEMBERS", projection.assertions().getFirst().predicate());
        assertEquals("项目使用 Java", projection.assertions().getFirst().text());
        assertEquals(source.itemId(), projection.evidence().getFirst().itemId());
        assertEquals(64, projection.evidence().getFirst().sha256().length());
        memory.command(support.request(
                "proposal/submit",
                new MemoryContracts.LearningProposalRequest(
                        "suggestion",
                        MemoryContracts.MemoryKind.FACT,
                        "workspace",
                        "项目使用数据库",
                        Set.of("project"),
                        source),
                Optional.of("suggest"),
                0));
        assertThrows(IllegalArgumentException.class, () -> projection(support, memory, "learned-suggestion"));
        assertEquals(1, projection(support, memory, "java").assertions().size());
    }

    @Test
    void relationRequiresHeadCasAndIsInvalidatedByEditingEitherPreciseMemoryVersion() throws Exception {
        var support = new BuiltinExtensionTestSupport();
        var memory = support.start(new MemoryExtension());
        create(support, memory, "project", "项目甲", Optional.empty());
        create(support, memory, "language", "Java", Optional.empty());
        long head = projection(support, memory, "project").memoryRevision();
        var relation = new MemoryGraphContracts.ConfirmRelation("project", "language", "USES");
        memory.command(support.request("graph/relation/confirm", relation, Optional.of("relation"), head));
        memory.command(support.request("graph/relation/confirm", relation, Optional.of("relation"), head));
        assertEquals(1, edges(support, memory).rows().size());
        assertTrue(edges(support, memory).rows().getFirst().json().contains("USES"));
        create(support, memory, "concurrent", "另一个用户新增的记忆", Optional.empty());
        assertThrows(
                IllegalArgumentException.class,
                () -> memory.command(support.request(
                        "graph/relation/confirm",
                        new MemoryGraphContracts.ConfirmRelation("project", "language", "TARGETS"),
                        Optional.of("stale"),
                        head + 1)));
        memory.command(support.request(
                "update/content",
                new MemoryContracts.ContentUpdateRequest(
                        "language", MemoryContracts.MemoryKind.FACT, "workspace", "Kotlin"),
                Optional.of("edit"),
                1));
        assertTrue(edges(support, memory).rows().isEmpty());
        assertFalse(projection(support, memory, "project").assertions().stream()
                .anyMatch(value -> value.predicate().equals("USES")));
    }

    @Test
    void changingSourceDoesNotExposeThePreviousEvidenceAsCurrent() throws Exception {
        var support = new BuiltinExtensionTestSupport();
        var memory = support.start(new MemoryExtension());
        var source = new MemoryContracts.Source(support.workspaceId, ThreadId.random(), ItemId.random(), "原始信息");
        create(support, memory, "source", "原始信息", Optional.of(source));
        memory.command(support.request(
                "update",
                new MemoryContracts.UpdateRequest(
                        "source",
                        MemoryContracts.MemoryKind.FACT,
                        "workspace",
                        "人工修订",
                        Set.of(),
                        false,
                        Optional.empty()),
                Optional.of("remove-source"),
                1));
        assertTrue(projection(support, memory, "source").evidence().isEmpty());
        memory.command(support.request(
                "update",
                new MemoryContracts.UpdateRequest(
                        "source",
                        MemoryContracts.MemoryKind.FACT,
                        "workspace",
                        "原始信息",
                        Set.of(),
                        false,
                        Optional.of(source)),
                Optional.of("source-again"),
                2));
        assertEquals(
                3, projection(support, memory, "source").evidence().getFirst().memoryRevision());
    }

    @Test
    void currentAssertionsStopAtTheExclusiveExpiryEvenWithoutFurtherMemoryWrites() throws Exception {
        var support = new BuiltinExtensionTestSupport();
        var memory = support.start(new MemoryExtension());
        create(support, memory, "timed", "临时约定", Optional.empty());
        memory.command(support.request(
                "effectivity/update",
                new MemoryV3Contracts.EffectivityUpdate(
                        "timed",
                        Optional.of(BuiltinExtensionTestSupport.NOW),
                        Optional.of(BuiltinExtensionTestSupport.NOW.plusSeconds(10)),
                        ""),
                Optional.of("time"),
                1));
        var projection = new MemoryGraphProjection(support.payloads, new MemoryStoreAccess(support.payloads));
        var before = support.store.inTransaction(
                MemoryStoreAccess.ID,
                transaction -> projection.currentAssertions(
                        transaction, support.workspaceId, BuiltinExtensionTestSupport.NOW.plusSeconds(9)));
        var atEnd = support.store.inTransaction(
                MemoryStoreAccess.ID,
                transaction -> projection.currentAssertions(
                        transaction, support.workspaceId, BuiltinExtensionTestSupport.NOW.plusSeconds(10)));
        assertEquals(1, before.size());
        assertTrue(atEnd.isEmpty());
    }

    @Test
    void unicodeBoundaryNeverLeavesHalfAnEmojiInEntityOrGraphLabel() throws Exception {
        var support = new BuiltinExtensionTestSupport();
        var memory = support.start(new MemoryExtension());
        String prefix = "文".repeat(199);
        create(support, memory, "emoji", prefix + "😀尾", Optional.empty());
        assertEquals(prefix, projection(support, memory, "emoji").entity().label());
        var graph = support.decode(
                memory.query(
                        support.request("graph/read", new MemoryV3Contracts.GraphRequest("", 10), Optional.empty(), 0)),
                MemoryV3Contracts.Graph.class);
        assertEquals(prefix, graph.nodes().getFirst().label());
        assertEquals("文".repeat(198) + "😀", MemoryGraphLabels.label("文".repeat(198) + "😀尾"));
        assertEquals("", MemoryGraphLabels.label(""));
    }

    @Test
    void legacyProposalCannotAutomaticallyAcceptAssistantTextAndConflictsAppearInGraphNotice() throws Exception {
        var support = new BuiltinExtensionTestSupport();
        var memory = support.start(new MemoryExtension());
        memory.command(support.request(
                "settings/update",
                new MemoryContracts.LearningSettingsUpdate(MemoryContracts.LearningPolicy.AUTO_LOW_RISK),
                Optional.of("auto"),
                0));
        support.userEvidence = false;
        var source = new MemoryContracts.Source(support.workspaceId, ThreadId.random(), ItemId.random(), "助手摘要");
        var proposed = support.decode(
                memory.command(support.request(
                        "proposal/submit",
                        new MemoryContracts.LearningProposalRequest(
                                "assistant",
                                MemoryContracts.MemoryKind.FACT,
                                "workspace",
                                "助手摘要",
                                Set.of("office"),
                                source),
                        Optional.of("assistant"),
                        0)),
                MemoryContracts.LearningResult.class);
        assertEquals(MemoryContracts.LearningAction.PROPOSED, proposed.action());
        assertTrue(proposed.proposal()
                .orElseThrow()
                .concerns()
                .contains(MemoryContracts.ProposalConcern.UNCERTAIN_EVIDENCE));
        support.userEvidence = true;
        MemoryV3BehaviorTest.create(support, memory, "old", "旧办公室");
        memory.command(support.request(
                "proposal/submit",
                new MemoryContracts.LearningProposalRequest(
                        "conflicting",
                        MemoryContracts.MemoryKind.FACT,
                        "workspace",
                        "新办公室",
                        Set.of("office"),
                        new MemoryContracts.Source(support.workspaceId, ThreadId.random(), ItemId.random(), "新办公室")),
                Optional.of("conflict"),
                0));
        var notice = support.decode(
                memory.query(support.request(
                        "view.conflicts",
                        new ViewQueryRequest("conflicts", Map.of(), "", 100, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
        assertEquals(1, notice.rows().size());
        assertTrue(notice.values().json().contains("1 项"));
        assertThrows(
                IllegalArgumentException.class,
                () -> memory.command(support.request(
                        "proposal/accept",
                        new MemoryContracts.ProposalDecision("conflicting"),
                        Optional.of("bypass"),
                        1)));
    }

    private static void create(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started memory,
            String id,
            String text,
            Optional<MemoryContracts.Source> source)
            throws Exception {
        memory.command(support.request(
                "create",
                new MemoryContracts.CreateRequest(
                        id, MemoryContracts.MemoryKind.FACT, "workspace", text, Set.of(), false, source),
                Optional.of("create-" + id),
                0));
    }

    private static MemoryGraphContracts.Projection projection(
            BuiltinExtensionTestSupport support, BuiltinExtensionTestSupport.Started memory, String id)
            throws Exception {
        return support.decode(
                memory.query(support.request("graph/entity", new MemoryContracts.Key(id), Optional.empty(), 0)),
                MemoryGraphContracts.Projection.class);
    }

    private static ViewQueryResult edges(
            BuiltinExtensionTestSupport support, BuiltinExtensionTestSupport.Started memory) throws Exception {
        return support.decode(
                memory.query(support.request(
                        "view.graph.edges",
                        new ViewQueryRequest("graphEdges", Map.of(), "", 200, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
    }
}
