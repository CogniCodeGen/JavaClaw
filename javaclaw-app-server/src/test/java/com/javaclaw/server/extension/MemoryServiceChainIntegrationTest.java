package com.javaclaw.server.extension;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryServiceChainIntegrationTest {
    private static final String MEMORY = BuiltinExtensionIds.MEMORY;
    private static final Instant NOW = Instant.now();
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path temporary;

    @Test
    void scheduleRunsThroughH2SupervisorAndRealTurnThenConflictResolutionUpdatesGraph() throws Exception {
        var model = new MemoryIntegrationModel();
        try (var fixture = new MemoryIntegrationFixture(temporary.resolve("data-v6"), CLOCK, model)) {
            fixture.installProvider();
            var workspace = fixture.workspace("learning");
            var other = fixture.workspace("other");
            fixture.conversation(workspace, "记忆事实：办公地点上海");
            fixture.conversation(other, "记忆事实：隔离工作空间内容");
            createMemory(fixture, workspace, "old", "记忆事实：办公地点北京");
            fixture.save(workspace, true, 0);
            var binding = fixture.awaitBinding(workspace);
            model.blockLearning();
            var occurrence = fixture.fire(workspace, binding.scheduleId(), 1);
            fixture.awaitLearning(workspace);
            var running = learningJob(fixture, workspace);
            assertEquals(ExecutionState.RUNNING, running.state());
            var overlapping = fixture.fire(workspace, binding.scheduleId(), 1);
            assertEquals(
                    ScheduleContracts.OccurrenceState.SKIPPED,
                    overlapping.status().state());
            assertEquals(Optional.of("ALREADY_RUNNING"), overlapping.status().reason());
            assertTrue(fixture.proposals(workspace).isEmpty());
            model.releaseLearning();
            var result = fixture.awaitJob(running.id(), ExecutionState.COMPLETED);
            assertEquals(
                    1,
                    result.units().stream()
                            .filter(value -> value.turnId().isPresent())
                            .count());
            MemoryIntegrationFixture.await(
                    () -> fixture.occurrences(workspace).stream()
                            .anyMatch(value -> value.identity().equals(occurrence.identity())
                                    && value.status().state() == ScheduleContracts.OccurrenceState.COMPLETED),
                    "子学习作业完成后 Occurrence 未收敛");
            assertModelAndEvidence(fixture, workspace, other);
            resolveAndCheckGraph(fixture, workspace);
        }
    }

    @Test
    void immediateLearningStopsAtPublicationWhenDisabledAndExplicitSkipUnblocksIncrementalWork() throws Exception {
        var model = new MemoryIntegrationModel();
        try (var fixture = new MemoryIntegrationFixture(temporary.resolve("data-v6"), CLOCK, model)) {
            fixture.installProvider();
            var workspace = fixture.workspace("immediate");
            fixture.conversation(workspace, "记忆事实：第一条应等待处理");
            fixture.save(workspace, true, 0);
            fixture.awaitBinding(workspace);
            model.blockLearning();
            var started = fixture.run(workspace, 1);
            fixture.awaitLearning(workspace);
            fixture.save(workspace, false, 1);
            assertTrue(fixture.rejectedCommand(workspace, MEMORY, "learning/run", Map.of(), 2)
                    .error()
                    .isPresent());
            model.releaseLearning();
            fixture.awaitJob(started.id(), ExecutionState.WAITING_INPUT);
            assertTrue(fixture.proposals(workspace).isEmpty());
            var batch = fixture.batches(workspace).getFirst();
            assertEquals(
                    "UNKNOWN",
                    fixture.components.json().textField(batch, "state").orElseThrow());
            fixture.command(
                    workspace,
                    MEMORY,
                    "learning/batch/skip",
                    new MemoryContracts.Key(
                            fixture.components.json().textField(batch, "id").orElseThrow()),
                    fixture.components.json().integerField(batch, "revision").orElseThrow(),
                    Object.class);
            fixture.save(workspace, true, 2);
            fixture.conversation(workspace, "记忆事实：第二条可以继续");
            var next = fixture.run(workspace, 3);
            fixture.awaitJob(next.id(), ExecutionState.COMPLETED);
            assertEquals(
                    List.of("记忆事实：第二条可以继续"),
                    fixture.proposals(workspace).stream()
                            .map(value -> value.candidate().content())
                            .toList());
            assertEquals(2, model.learningInvocations.size());
        }
    }

    private static ExtensionExecutionReceipt learningJob(MemoryIntegrationFixture fixture, Workspace workspace) {
        return fixture.jobs(workspace).stream()
                .filter(value -> value.jobType().equals("conversation-learning"))
                .findFirst()
                .orElseThrow();
    }

    private static void assertModelAndEvidence(MemoryIntegrationFixture fixture, Workspace workspace, Workspace other) {
        assertEquals(1, fixture.model.learningInvocations.size());
        var invocation = fixture.model.learningInvocations.getFirst();
        assertTrue(invocation.tools().isEmpty());
        assertEquals(2000, invocation.maximumOutputTokens());
        assertFalse(invocation.messages().toString().contains("隔离工作空间内容"));
        assertEquals(
                new TurnBudget(16000, 2000, 0, 0, Duration.ofSeconds(120)),
                fixture.turn(fixture.model.learningTurns.getFirst()).budget());
        assertTrue(fixture.proposals(other).isEmpty());
        assertTrue(fixture.batches(other).isEmpty());
        assertEquals(1, fixture.proposals(workspace).size());
        var proposal = fixture.proposals(workspace).getFirst();
        assertEquals(MemoryContracts.ProposalState.PENDING, proposal.state());
        assertTrue(proposal.concerns().contains(MemoryContracts.ProposalConcern.CONFLICT));
        assertEquals(workspace.id(), proposal.candidate().source().workspaceId());
        assertEquals("记忆事实：办公地点上海", proposal.candidate().content());
        assertEquals(1, fixture.conflicts(workspace).rows().size());
    }

    private static void resolveAndCheckGraph(MemoryIntegrationFixture fixture, Workspace workspace) {
        var conflict = fixture.decode(fixture.conflicts(workspace).rows().getFirst(), MemoryV3Contracts.Conflict.class);
        var head = fixture.search(workspace).memoryRevision();
        var stale = decision(conflict, head);
        createMemory(fixture, workspace, "concurrent", "另一客户端新增的独立条目");
        assertTrue(fixture.rejectedCommand(workspace, MEMORY, "conflict/resolve", stale, conflict.revision())
                .error()
                .isPresent());
        assertEquals(head + 1, fixture.search(workspace).memoryRevision());
        fixture.command(
                workspace, MEMORY, "conflict/resolve", decision(conflict, head + 1), conflict.revision(), Object.class);
        assertTrue(fixture.conflicts(workspace).rows().isEmpty());
        var current = fixture.search(workspace).matches();
        assertEquals(1, current.size());
        var replacement = current.getFirst().memory();
        assertEquals("记忆事实：办公地点上海", replacement.content());
        var graph = fixture.query(
                workspace,
                MEMORY,
                "graph/read",
                new MemoryV3Contracts.GraphRequest("", 100),
                MemoryV3Contracts.Graph.class);
        assertTrue(graph.edges().contains(new MemoryV3Contracts.GraphEdge("old", replacement.id(), "REPLACED_BY")));
        assertEffectivity(fixture, workspace, replacement);
    }

    private static MemoryV3Contracts.ConflictDecision decision(MemoryV3Contracts.Conflict conflict, long head) {
        return new MemoryV3Contracts.ConflictDecision(
                conflict.id(),
                MemoryV3Contracts.Resolution.REPLACE,
                Map.of("old", 1L),
                head,
                "记忆事实：办公地点上海",
                Optional.empty(),
                Optional.empty(),
                "");
    }

    private static void assertEffectivity(
            MemoryIntegrationFixture fixture, Workspace workspace, MemoryContracts.Memory replacement) {
        var timed = fixture.command(
                workspace,
                MEMORY,
                "effectivity/update",
                new MemoryV3Contracts.EffectivityUpdate(
                        replacement.id(), Optional.of(NOW.minusSeconds(1)), Optional.of(NOW.plusSeconds(60)), ""),
                replacement.revision(),
                MemoryV3Contracts.SearchMatch.class);
        assertEquals(1, fixture.search(workspace).matches().size());
        assertTrue(fixture.query(
                        workspace,
                        MEMORY,
                        "search",
                        new MemoryContracts.SearchRequest("记忆事实", Set.of(), Set.of(), 100),
                        MemoryContracts.SearchResult.class)
                .matches()
                .isEmpty());
        var expired = fixture.command(
                workspace,
                MEMORY,
                "effectivity/update",
                new MemoryV3Contracts.EffectivityUpdate(
                        replacement.id(), Optional.of(NOW.minusSeconds(60)), Optional.of(NOW), ""),
                timed.memory().revision(),
                MemoryV3Contracts.SearchMatch.class);
        assertTrue(fixture.search(workspace).matches().isEmpty());
        fixture.command(
                workspace,
                MEMORY,
                "effectivity/update",
                new MemoryV3Contracts.EffectivityUpdate(
                        replacement.id(), Optional.empty(), Optional.empty(), "仅用户确认后适用"),
                expired.memory().revision(),
                Object.class);
        assertTrue(fixture.search(workspace).matches().isEmpty());
        var nodes = fixture.query(
                workspace,
                MEMORY,
                "view.graph.nodes",
                new ViewQueryRequest("nodes", Map.of(), "", 100, Optional.empty()),
                ViewQueryResult.class);
        assertFalse(nodes.rows().stream().anyMatch(value -> value.json().contains(replacement.id())));
    }

    private static void createMemory(MemoryIntegrationFixture fixture, Workspace workspace, String id, String content) {
        fixture.command(
                workspace,
                MEMORY,
                "create",
                new MemoryContracts.CreateRequest(
                        id,
                        MemoryContracts.MemoryKind.FACT,
                        "workspace",
                        content,
                        Set.of("conversation"),
                        false,
                        Optional.empty()),
                0,
                MemoryContracts.Memory.class);
    }
}
