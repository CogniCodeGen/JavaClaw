package com.javaclaw.builtin.extensions;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ItemId;
import com.javaclaw.api.ThreadId;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryConflictRecoveryTest {
    @Test
    void laterProposalCanReplaceCurrentMemoryWithoutReintroducingSupersededParticipants() throws Exception {
        var fixture = new Fixture();
        fixture.memory.command(fixture.decision("office", MemoryV3Contracts.Resolution.REPLACE, Map.of("old", 1L)));
        fixture.memory.command(fixture.support.request(
                "proposal/submit",
                new MemoryContracts.LearningProposalRequest(
                        "next",
                        MemoryContracts.MemoryKind.FACT,
                        "workspace",
                        "办公地点杭州",
                        Set.of("office"),
                        new MemoryContracts.Source(
                                fixture.support.workspaceId, ThreadId.random(), ItemId.random(), "办公地点杭州")),
                Optional.of("next-proposal"),
                0));
        assertEquals(
                Map.of("resolved-office", 1), fixture.values("conflict-next").get("expectedMemoryRevisions"));
        fixture.memory.command(
                fixture.decision("conflict-next", MemoryV3Contracts.Resolution.REPLACE, Map.of("resolved-office", 1L)));
        assertEquals(
                Set.of("resolved-conflict-next"),
                fixture.search().matches().stream()
                        .map(value -> value.memory().id())
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void pendingConflictBlocksDeletionWithoutChangingEitherRevision() throws Exception {
        var fixture = new Fixture();
        long head = fixture.search().memoryRevision();
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.memory.command(fixture.support.request(
                        "tombstone", new MemoryContracts.Key("old"), Optional.of("delete"), 1)));
        assertTrue(failure.getMessage().contains("冲突"));
        assertEquals(head, fixture.search().memoryRevision());
        assertEquals(Map.of("old", 1), fixture.values("office").get("expectedMemoryRevisions"));
    }

    @Test
    void preexistingConflictCanCloseAfterAnotherDecisionSupersededItsParticipant() throws Exception {
        var fixture = new Fixture();
        fixture.memory.command(fixture.support.request(
                "proposal/submit",
                new MemoryContracts.LearningProposalRequest(
                        "parallel",
                        MemoryContracts.MemoryKind.FACT,
                        "workspace",
                        "办公地点杭州",
                        Set.of("office"),
                        new MemoryContracts.Source(
                                fixture.support.workspaceId, ThreadId.random(), ItemId.random(), "办公地点杭州")),
                Optional.of("parallel-proposal"),
                0));
        fixture.memory.command(fixture.decision("office", MemoryV3Contracts.Resolution.REPLACE, Map.of("old", 1L)));
        assertTrue(fixture.values("conflict-parallel")
                .get("participants")
                .toString()
                .contains("已被替代"));
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.memory.command(fixture.decision(
                        "conflict-parallel", MemoryV3Contracts.Resolution.REPLACE, Map.of("old", 2L))));
        fixture.memory.command(
                fixture.decision("conflict-parallel", MemoryV3Contracts.Resolution.KEEP_EXISTING, Map.of("old", 2L)));
        assertEquals(
                "resolved-office",
                fixture.search().matches().getFirst().memory().id());
    }

    @Test
    void historicalTombstoneCanBeInspectedAndExplicitlyClosedWithoutRestoringMemory() throws Exception {
        var fixture = new Fixture();
        fixture.legacyDelete(1);
        assertEquals(Map.of("old", 2), fixture.values("office").get("expectedMemoryRevisions"));
        assertTrue(fixture.values("office").get("participants").toString().contains("已删除"));
        var request = fixture.decision("office", MemoryV3Contracts.Resolution.KEEP_EXISTING, Map.of("old", 2L));
        fixture.memory.command(request);
        fixture.memory.command(request);
        assertTrue(fixture.search().matches().isEmpty());
        assertEquals(
                MemoryV3Contracts.ConflictState.REJECTED,
                fixture.conflict("office").state());
        assertEquals(
                MemoryContracts.ProposalState.REJECTED,
                fixture.support
                        .decode(
                                fixture.memory.query(fixture.support.request(
                                        "proposal/read", new MemoryContracts.Key("candidate"), Optional.empty(), 0)),
                                MemoryContracts.Proposal.class)
                        .state());
    }

    @Test
    void tombstoneRequiresItsStorageRevisionAndCannotParticipateInReplacement() throws Exception {
        var fixture = new Fixture();
        fixture.legacyDelete(1);
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.memory.command(
                        fixture.decision("office", MemoryV3Contracts.Resolution.KEEP_EXISTING, Map.of("old", 1L))));
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.memory.command(
                        fixture.decision("office", MemoryV3Contracts.Resolution.REPLACE, Map.of("old", 2L))));
        assertEquals(
                MemoryV3Contracts.ConflictState.PENDING,
                fixture.conflict("office").state());
    }

    @Test
    void restoringAfterEditorReadInvalidatesTheDecisionAndRefreshPreservesRestoredMemory() throws Exception {
        var fixture = new Fixture();
        fixture.legacyDelete(1);
        var stale = fixture.decision("office", MemoryV3Contracts.Resolution.KEEP_EXISTING, Map.of("old", 2L));
        fixture.memory.command(fixture.support.request(
                "restore", new MemoryContracts.RestoreRequest("old", 1), Optional.of("restore"), 2));
        assertThrows(IllegalArgumentException.class, () -> fixture.memory.command(stale));
        fixture.memory.command(
                fixture.decision("office", MemoryV3Contracts.Resolution.KEEP_EXISTING, Map.of("old", 3L)));
        assertEquals("办公地点北京", fixture.search().matches().getFirst().memory().content());
    }

    @Test
    void tombstoneLookupReadsPastTheFirstHistoryPage() throws Exception {
        var fixture = new Fixture();
        fixture.support.store.inTransaction(MemoryStoreAccess.ID, transaction -> {
            var original = fixture.support.payloads.decode(
                    transaction
                            .get(MemoryCollectionNames.memories(fixture.support.workspaceId), "old")
                            .orElseThrow()
                            .payload(),
                    MemoryContracts.Memory.class);
            for (int revision = 2; revision <= 503; revision++) {
                transaction.put(
                        MemoryCollectionNames.memories(fixture.support.workspaceId),
                        "old",
                        revision - 1,
                        fixture.support.payloads.encode(
                                MemoryStoreAccess.copy(original, revision, false, BuiltinExtensionTestSupport.NOW)));
            }
            return null;
        });
        fixture.legacyDelete(503);
        assertEquals(Map.of("old", 504), fixture.values("office").get("expectedMemoryRevisions"));
        fixture.memory.command(
                fixture.decision("office", MemoryV3Contracts.Resolution.KEEP_EXISTING, Map.of("old", 504L)));
        assertFalse(fixture.support
                .store
                .get(MemoryCollectionNames.memories(fixture.support.workspaceId), "old")
                .isPresent());
    }

    private static final class Fixture {
        private final BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        private final BuiltinExtensionTestSupport.Started memory = support.start(new MemoryExtension());
        private int identity;

        private Fixture() throws Exception {
            MemoryV3BehaviorTest.create(support, memory, "old", "办公地点北京");
            MemoryV3BehaviorTest.seedConflict(support);
        }

        private ExtensionRequest decision(
                String id, MemoryV3Contracts.Resolution resolution, Map<String, Long> revisions) throws Exception {
            return support.request(
                    "conflict/resolve",
                    new MemoryV3Contracts.ConflictDecision(
                            id,
                            resolution,
                            revisions,
                            search().memoryRevision(),
                            "办公地点上海",
                            Optional.empty(),
                            Optional.empty(),
                            ""),
                    Optional.of("decision-" + identity++),
                    1);
        }

        private Map<?, ?> values(String id) throws Exception {
            var result = support.decode(
                    memory.query(support.request(
                            "view.conflict",
                            new ViewQueryRequest(
                                    "conflict", Map.of("id", id, "revision", "1"), "", 1, Optional.empty()),
                            Optional.empty(),
                            0)),
                    ViewQueryResult.class);
            return support.payloads.decode(result.values(), Map.class);
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

        private MemoryV3Contracts.Conflict conflict(String id) {
            return support.payloads.decode(
                    support.store
                            .get(MemorySemantics.conflicts(support.workspaceId), id)
                            .orElseThrow()
                            .payload(),
                    MemoryV3Contracts.Conflict.class);
        }

        private void legacyDelete(long revision) throws Exception {
            // 模拟修复前合法留下的墓碑；不能通过新版删除保护绕过旧数据兼容路径。
            support.store.inTransaction(MemoryStoreAccess.ID, transaction -> {
                new MemorySemantics(support.payloads, new MemoryStoreAccess(support.payloads))
                        .lock(transaction, support.workspaceId);
                transaction.delete(MemoryCollectionNames.memories(support.workspaceId), "old", revision);
                return null;
            });
        }
    }
}
