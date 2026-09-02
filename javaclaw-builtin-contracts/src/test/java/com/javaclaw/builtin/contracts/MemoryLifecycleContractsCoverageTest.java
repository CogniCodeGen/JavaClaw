package com.javaclaw.builtin.contracts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ItemId;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryLifecycleContractsCoverageTest {
    private static final Instant NOW = BuiltinContractsFixtures.NOW;

    @Test
    void explicitMemoryCommandsPreserveSourceTagsAndHistoryIdentity() {
        MemoryContracts.Source source = source("可核验事实");
        MemoryContracts.CreateRequest create = new MemoryContracts.CreateRequest(
                "memory",
                MemoryContracts.MemoryKind.FACT,
                "workspace",
                "可核验事实",
                Set.of("java"),
                true,
                Optional.of(source));
        MemoryContracts.UpdateRequest update = new MemoryContracts.UpdateRequest(
                "memory",
                MemoryContracts.MemoryKind.PERSONA,
                "workspace",
                "用户偏好",
                Set.of("style"),
                false,
                Optional.empty());
        MemoryContracts.Memory memory = memory(Optional.of(source));
        MemoryContracts.HistoryEntry entry = new MemoryContracts.HistoryEntry(1, memory, false, NOW);
        ArrayList<MemoryContracts.HistoryEntry> entries = new ArrayList<>(List.of(entry));
        MemoryContracts.HistoryPage page = new MemoryContracts.HistoryPage(entries, false);
        entries.clear();

        assertEquals(source, create.source().orElseThrow());
        assertEquals(MemoryContracts.MemoryKind.PERSONA, update.kind());
        assertEquals(
                "memory",
                new MemoryContracts.ContentUpdateRequest("memory", MemoryContracts.MemoryKind.FACT, "workspace", "修正事实")
                        .id());
        assertFalse(new MemoryContracts.PinRequest("memory", false).pinned());
        assertEquals("memory", new MemoryContracts.Key("memory").id());
        assertEquals(1, new MemoryContracts.RestoreRequest("memory", 1).sourceRevision());
        assertEquals(20, new MemoryContracts.HistoryRequest("memory", 0, 20).limit());
        assertEquals(1, page.entries().size());
        assertFalse(page.hasMore());
    }

    @Test
    void managementCreateAllowsOneBoundSourceAndRejectsDuplicateOrOversizedRows() {
        MemoryContracts.ManagementTag tag = new MemoryContracts.ManagementTag("tag-1", "java");
        MemoryContracts.ManagementSource source = new MemoryContracts.ManagementSource(
                "source-1", UUID.randomUUID().toString(), UUID.randomUUID().toString(), "可核验事实");
        MemoryContracts.ManagementCreateRequest request = new MemoryContracts.ManagementCreateRequest(
                "memory", MemoryContracts.MemoryKind.FACT, "workspace", "可核验事实", List.of(tag), true, List.of(source));

        assertEquals("java", request.tags().getFirst().value());
        assertEquals(source, request.sources().getFirst());
        assertThrows(
                IllegalArgumentException.class,
                () -> new MemoryContracts.ManagementCreateRequest(
                        "memory",
                        MemoryContracts.MemoryKind.FACT,
                        "workspace",
                        "content",
                        List.of(tag, new MemoryContracts.ManagementTag("tag-2", "java")),
                        false,
                        List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MemoryContracts.ManagementCreateRequest(
                        "memory",
                        MemoryContracts.MemoryKind.FACT,
                        "workspace",
                        "content",
                        List.of(),
                        false,
                        List.of(
                                source,
                                new MemoryContracts.ManagementSource(
                                        "source-2",
                                        UUID.randomUUID().toString(),
                                        UUID.randomUUID().toString(),
                                        "另一条"))));
        List<MemoryContracts.ManagementTag> tooMany = new ArrayList<>();
        for (int index = 0; index < 33; index++) {
            tooMany.add(new MemoryContracts.ManagementTag("tag-" + index, "value-" + index));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new MemoryContracts.ManagementCreateRequest(
                        "memory", MemoryContracts.MemoryKind.FACT, "workspace", "content", tooMany, false, List.of()));
    }

    @Test
    void memoryValidationRejectsInvalidTimeHistorySettingsAndStatistics() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MemoryContracts.Memory(
                        "memory",
                        1,
                        MemoryContracts.MemoryKind.FACT,
                        "workspace",
                        "content",
                        Set.of(),
                        false,
                        Optional.empty(),
                        NOW,
                        NOW.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new MemoryContracts.HistoryRequest("memory", -1, 10));
        assertThrows(IllegalArgumentException.class, () -> new MemoryContracts.HistoryRequest("memory", 0, 101));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MemoryContracts.LearningSettings(-1, MemoryContracts.LearningPolicy.OFF, NOW));
        assertThrows(NullPointerException.class, () -> new MemoryContracts.LearningSettingsUpdate(null));
        assertThrows(IllegalArgumentException.class, () -> new MemoryContracts.Stats(-1, 0, 0, 0));
        assertEquals(4, new MemoryContracts.Stats(4, 2, 1, 3).active());
    }

    @Test
    void learningPolicyAndProposalStatesRequireAuditableResultShapes() {
        MemoryContracts.LearningProposalRequest candidate = new MemoryContracts.LearningProposalRequest(
                "proposal", MemoryContracts.MemoryKind.FACT, "workspace", "可核验事实", Set.of("java"), source("可核验事实"));
        MemoryContracts.Proposal pending = proposal(candidate, MemoryContracts.ProposalState.PENDING, Optional.empty());
        MemoryContracts.Proposal accepted =
                proposal(candidate, MemoryContracts.ProposalState.ACCEPTED, Optional.of("memory"));
        MemoryContracts.Proposal auto =
                proposal(candidate, MemoryContracts.ProposalState.AUTO_ACCEPTED, Optional.of("memory"));
        MemoryContracts.Proposal rejected =
                proposal(candidate, MemoryContracts.ProposalState.REJECTED, Optional.empty());
        MemoryContracts.Memory memory = memory(Optional.of(candidate.source()));

        assertEquals(
                MemoryContracts.LearningPolicy.SUGGEST,
                new MemoryContracts.LearningSettings(0, MemoryContracts.LearningPolicy.SUGGEST, NOW).policy());
        assertEquals(
                MemoryContracts.LearningPolicy.AUTO_LOW_RISK,
                new MemoryContracts.LearningSettingsUpdate(MemoryContracts.LearningPolicy.AUTO_LOW_RISK).policy());
        assertEquals("proposal", new MemoryContracts.ProposalDecision("proposal").id());
        assertEquals(
                MemoryContracts.LearningAction.IGNORED,
                new MemoryContracts.LearningResult(
                                MemoryContracts.LearningAction.IGNORED, Optional.empty(), Optional.empty())
                        .action());
        assertEquals(
                pending,
                new MemoryContracts.LearningResult(
                                MemoryContracts.LearningAction.PROPOSED, Optional.of(pending), Optional.empty())
                        .proposal()
                        .orElseThrow());
        assertEquals(
                memory,
                new MemoryContracts.LearningResult(
                                MemoryContracts.LearningAction.AUTO_ACCEPTED, Optional.of(auto), Optional.of(memory))
                        .memory()
                        .orElseThrow());
        assertEquals(MemoryContracts.ProposalState.ACCEPTED, accepted.state());
        assertEquals(MemoryContracts.ProposalState.REJECTED, rejected.state());
        assertTrue(pending.concerns().contains(MemoryContracts.ProposalConcern.SPECULATIVE));

        assertThrows(
                IllegalArgumentException.class,
                () -> proposal(candidate, MemoryContracts.ProposalState.PENDING, Optional.of("memory")));
        assertThrows(
                IllegalArgumentException.class,
                () -> proposal(candidate, MemoryContracts.ProposalState.ACCEPTED, Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MemoryContracts.LearningResult(
                        MemoryContracts.LearningAction.IGNORED, Optional.of(pending), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MemoryContracts.LearningResult(
                        MemoryContracts.LearningAction.PROPOSED, Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MemoryContracts.LearningResult(
                        MemoryContracts.LearningAction.AUTO_ACCEPTED, Optional.of(auto), Optional.empty()));
    }

    @Test
    void searchAndLearningCollectionsAreDefensivelyCopied() {
        MemoryContracts.Memory memory = memory(Optional.empty());
        ArrayList<MemoryContracts.Memory> matches = new ArrayList<>(List.of(memory));
        MemoryContracts.SearchResult result = new MemoryContracts.SearchResult(matches);
        matches.clear();

        assertEquals(1, result.matches().size());
        assertEquals(
                Set.of("workspace"),
                new MemoryContracts.SearchRequest("fact", Set.of("workspace"), Set.of(), 10).scopes());
        assertThrows(
                IllegalArgumentException.class,
                () -> new MemoryContracts.SearchRequest("fact", Set.of(" "), Set.of(), 10));
    }

    private static MemoryContracts.Source source(String verbatim) {
        return new MemoryContracts.Source(
                WorkspaceId.parse(UUID.randomUUID().toString()),
                ThreadId.parse(UUID.randomUUID().toString()),
                ItemId.parse(UUID.randomUUID().toString()),
                verbatim);
    }

    private static MemoryContracts.Memory memory(Optional<MemoryContracts.Source> source) {
        return new MemoryContracts.Memory(
                "memory",
                1,
                MemoryContracts.MemoryKind.FACT,
                "workspace",
                "可核验事实",
                Set.of("java"),
                true,
                source,
                NOW,
                NOW);
    }

    private static MemoryContracts.Proposal proposal(
            MemoryContracts.LearningProposalRequest candidate,
            MemoryContracts.ProposalState state,
            Optional<String> memoryId) {
        return new MemoryContracts.Proposal(
                "proposal",
                1,
                candidate,
                Set.of(MemoryContracts.ProposalConcern.SPECULATIVE),
                state,
                memoryId,
                NOW,
                NOW);
    }
}
