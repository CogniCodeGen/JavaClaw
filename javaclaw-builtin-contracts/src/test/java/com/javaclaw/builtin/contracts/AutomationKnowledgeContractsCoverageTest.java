package com.javaclaw.builtin.contracts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.TurnId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationKnowledgeContractsCoverageTest {
    private static final Instant NOW = BuiltinContractsFixtures.NOW;
    private static final String DIGEST = "a".repeat(64);

    @Test
    void loopSupportsOnlyUserExitCodeOrFieldEvidenceAndBoundedIterations() {
        LoopContracts.VerificationRule confirmation = loopRule(
                LoopContracts.VerificationKind.USER_CONFIRMATION,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        LoopContracts.VerificationRule exitCode = loopRule(
                LoopContracts.VerificationKind.TOOL_EXIT_CODE,
                Optional.of("test"),
                Optional.of(0),
                Optional.empty(),
                Optional.empty());
        LoopContracts.VerificationRule field = loopRule(
                LoopContracts.VerificationKind.TOOL_FIELD_ASSERTION,
                Optional.of("inspect"),
                Optional.empty(),
                Optional.of("/passed"),
                Optional.of(BuiltinContractsFixtures.payload()));
        LoopContracts.Definition definition =
                new LoopContracts.Definition("loop", 1, "Loop", "Pass", "Run", 10, 3, field, NOW);
        LoopContracts.ManagementSaveRequest management = new LoopContracts.ManagementSaveRequest(
                "loop",
                "Loop",
                "Pass",
                "Run",
                10,
                3,
                LoopContracts.VerificationKind.TOOL_EXIT_CODE,
                Optional.of("test"),
                Optional.of(0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        assertEquals(LoopContracts.VerificationKind.USER_CONFIRMATION, confirmation.kind());
        assertEquals(0, exitCode.expectedExitCode().orElseThrow());
        assertEquals(10, definition.maximumIterations());
        assertEquals("test", management.toolName().orElseThrow());
        assertTrue(new LoopContracts.Confirmation("job", 1, true).confirmed());
    }

    @Test
    void loopRejectsMismatchedEvidenceAndUnsafeIterationBounds() {
        LoopContracts.VerificationRule field = loopRule(
                LoopContracts.VerificationKind.TOOL_FIELD_ASSERTION,
                Optional.of("inspect"),
                Optional.empty(),
                Optional.of("/passed"),
                Optional.of(BuiltinContractsFixtures.payload()));
        assertThrows(
                IllegalArgumentException.class,
                () -> loopRule(
                        LoopContracts.VerificationKind.USER_CONFIRMATION,
                        Optional.of("tool"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> loopRule(
                        LoopContracts.VerificationKind.TOOL_FIELD_ASSERTION,
                        Optional.of("tool"),
                        Optional.empty(),
                        Optional.of("not-a-pointer"),
                        Optional.of(BuiltinContractsFixtures.payload())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LoopContracts.Definition("loop", 1, "Loop", "Pass", "Run", 101, 1, field, NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LoopContracts.Definition("loop", 1, "Loop", "Pass", "Run", 5, 6, field, NOW));
        assertThrows(IllegalArgumentException.class, () -> new LoopContracts.Confirmation("job", 0, false));
    }

    @Test
    void planManagementRequiresStableUniqueRowsAndExecutableDependencyGraph() {
        PlanContracts.ManagementRisk risk = new PlanContracts.ManagementRisk("risk-1", "可能失败");
        PlanContracts.ManagementOpenQuestion question =
                new PlanContracts.ManagementOpenQuestion("question-1", "database", "使用哪个数据库？", Optional.empty());
        PlanContracts.ManagementStep step =
                new PlanContracts.ManagementStep("step-1", "build", "Build", "执行构建", "构建通过", List.of());
        PlanContracts.ManagementSaveRequest request = new PlanContracts.ManagementSaveRequest(
                "plan", "Upgrade", "Pass", "Core", List.of(risk), List.of(question), List.of(step));
        String prompt = "使用哪个数据库？";
        String hash = ContractDigests.sha256(prompt);
        PlanContracts.OpenQuestion unresolved =
                new PlanContracts.OpenQuestion("database", prompt, hash, Optional.empty());
        PlanContracts.Definition definition = new PlanContracts.Definition(
                "plan",
                1,
                "Upgrade",
                "Pass",
                "Core",
                List.of("可能失败"),
                List.of(unresolved),
                List.of(new PlanContracts.Step("build", "Build", "执行构建", "构建通过", List.of())),
                NOW);

        assertEquals(1, request.steps().size());
        assertFalse(definition.decisionsComplete());
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlanContracts.ManagementSaveRequest(
                        "plan", "Upgrade", "Pass", "Core", List.of(), List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlanContracts.ManagementSaveRequest(
                        "plan", "Upgrade", "Pass", "Core", List.of(risk, risk), List.of(), List.of(step)));
        assertThrows(IllegalArgumentException.class, () -> new PlanContracts.Decision("not-a-digest", "answer"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlanContracts.Definition(
                        "plan",
                        1,
                        "Upgrade",
                        "Pass",
                        "Core",
                        List.of(),
                        List.of(),
                        List.of(new PlanContracts.Step("build", "Build", "Run", "Pass", List.of("missing"))),
                        NOW));
    }

    @Test
    void sddRequiresTwoDigestBoundApprovalsAndRealToolVerification() {
        SddContracts.ManagementTask task = new SddContracts.ManagementTask("task-1", "实现");
        SddContracts.ManagementSaveRequest management = new SddContracts.ManagementSaveRequest(
                "sdd",
                "Feature",
                "Requirements",
                "Design",
                List.of(task),
                SddContracts.VerificationKind.TOOL_FIELD_ASSERTION,
                "verify",
                Optional.empty(),
                Optional.of("/passed"),
                Optional.of(SddContracts.ManagementValueKind.BOOLEAN),
                Optional.of("true"),
                2);
        SddContracts.VerificationRule exitCode = new SddContracts.VerificationRule(
                SddContracts.VerificationKind.TOOL_EXIT_CODE,
                "verify",
                Optional.of(0),
                Optional.empty(),
                Optional.empty());
        SddContracts.VerificationRule field = new SddContracts.VerificationRule(
                SddContracts.VerificationKind.TOOL_FIELD_ASSERTION,
                "verify",
                Optional.empty(),
                Optional.of("/passed"),
                Optional.of(BuiltinContractsFixtures.payload()));
        SddContracts.Definition definition = new SddContracts.Definition(
                "sdd", 1, "Feature", new SddContracts.Content("Requirements", "Design", List.of("实现")), field, 2, NOW);
        SddContracts.Approval approval = new SddContracts.Approval(
                "job", SddContracts.ApprovalKind.SPECIFICATION, definition.specificationDigest());
        SddContracts.Checkpoint checkpoint = new SddContracts.Checkpoint(
                SddContracts.Phase.IMPLEMENT,
                1,
                Optional.of(definition.specificationDigest()),
                Optional.of(definition.taskDigest()),
                0,
                false);

        assertEquals(2, management.maximumRemediations());
        assertEquals(0, exitCode.expectedExitCode().orElseThrow());
        assertEquals(definition.specificationDigest(), approval.contentDigest());
        assertEquals(SddContracts.Phase.IMPLEMENT, checkpoint.phase());
    }

    @Test
    void sddManagementRejectsMissingOrDuplicateTasks() {
        SddContracts.ManagementTask task = new SddContracts.ManagementTask("task-1", "实现");
        assertThrows(
                IllegalArgumentException.class,
                () -> new SddContracts.ManagementSaveRequest(
                        "sdd",
                        "Feature",
                        "Requirements",
                        "Design",
                        List.of(),
                        SddContracts.VerificationKind.TOOL_EXIT_CODE,
                        "verify",
                        Optional.of(0),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SddContracts.ManagementSaveRequest(
                        "sdd",
                        "Feature",
                        "Requirements",
                        "Design",
                        List.of(task, task),
                        SddContracts.VerificationKind.TOOL_EXIT_CODE,
                        "verify",
                        Optional.of(0),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        0));
    }

    @Test
    void sddDomainRejectsMismatchedEvidenceAndInvalidCheckpoint() {
        SddContracts.VerificationRule field = new SddContracts.VerificationRule(
                SddContracts.VerificationKind.TOOL_FIELD_ASSERTION,
                "verify",
                Optional.empty(),
                Optional.of("/passed"),
                Optional.of(BuiltinContractsFixtures.payload()));
        SddContracts.Definition definition = new SddContracts.Definition(
                "sdd", 1, "Feature", new SddContracts.Content("Requirements", "Design", List.of("实现")), field, 2, NOW);
        assertThrows(
                IllegalArgumentException.class,
                () -> new SddContracts.VerificationRule(
                        SddContracts.VerificationKind.TOOL_EXIT_CODE,
                        "verify",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SddContracts.VerificationRule(
                        SddContracts.VerificationKind.TOOL_FIELD_ASSERTION,
                        "verify",
                        Optional.empty(),
                        Optional.of("invalid"),
                        Optional.of(BuiltinContractsFixtures.payload())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SddContracts.Definition("sdd", 1, "Feature", definition.content(), field, 11, NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SddContracts.Checkpoint(
                        SddContracts.Phase.IMPLEMENT, -1, Optional.empty(), Optional.empty(), 0, false));
    }

    @Test
    void knowledgeChunksPagesAndExtractionEnforceWorkerAndVectorLimits() {
        KnowledgeContracts.Source source = source();
        KnowledgeContracts.Generation generation = keywordGeneration(Optional.empty());
        KnowledgeContracts.Chunk chunk =
                new KnowledgeContracts.Chunk("generation", "guide", 0, "text", Optional.of(List.of(0.1, 0.2)));
        ArrayList<KnowledgeContracts.Source> sources = new ArrayList<>(List.of(source));
        ArrayList<KnowledgeContracts.Generation> generations = new ArrayList<>(List.of(generation));
        KnowledgeContracts.SourcePage sourcePage = new KnowledgeContracts.SourcePage(sources, null);
        KnowledgeContracts.GenerationPage generationPage = new KnowledgeContracts.GenerationPage(generations, null);
        sources.clear();
        generations.clear();

        assertEquals(List.of(0.1, 0.2), chunk.vector().orElseThrow());
        assertEquals(1, sourcePage.values().size());
        assertEquals("", sourcePage.nextKey());
        assertEquals(1, generationPage.values().size());
        assertEquals("", new KnowledgeContracts.PageRequest(null, 20).afterKey());
        assertEquals("guide", new KnowledgeContracts.Key("guide").id());
        assertEquals("job", new KnowledgeContracts.ImportAccepted("job", "guide", 2).jobId());
        assertEquals(100, new KnowledgeContracts.ExtractionRequest(attachment(), 100).maxCharacters());
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeContracts.Chunk("generation", "guide", -1, "text", Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeContracts.Chunk("generation", "guide", 0, "text", Optional.of(List.of(Double.NaN))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeContracts.Chunk("generation", "guide", 0, "text", Optional.of(List.of())));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeContracts.PageRequest("", 0));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeContracts.ExtractionRequest(attachment(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeContracts.ExtractionResult(DIGEST, "worker", "x".repeat(2_000_001)));
    }

    @Test
    void knowledgeGenerationAndSearchRejectInconsistentEmbeddingMetadata() {
        KnowledgeContracts.Generation hybrid = new KnowledgeContracts.Generation(
                "generation",
                1,
                "guide",
                2,
                DIGEST,
                "worker",
                KnowledgeContracts.RetrievalMode.HYBRID,
                Optional.of("b".repeat(64)),
                2,
                1,
                10,
                Optional.empty(),
                NOW);
        KnowledgeContracts.Generation failed =
                keywordGeneration(Optional.of(KnowledgeContracts.FallbackReason.EMBEDDING_FAILED));
        KnowledgeContracts.SearchMatch match = new KnowledgeContracts.SearchMatch(
                source(), hybrid, List.of("excerpt"), 1, KnowledgeContracts.RetrievalMode.HYBRID);

        assertEquals(2, hybrid.embeddingDimensions());
        assertEquals(
                KnowledgeContracts.FallbackReason.EMBEDDING_FAILED,
                failed.fallbackReason().orElseThrow());
        assertEquals(
                1,
                new KnowledgeContracts.SearchResult(List.of(match), false)
                        .matches()
                        .size());
    }

    @Test
    void knowledgeGenerationAndSearchRejectInvalidMetadata() {
        KnowledgeContracts.Generation hybrid = new KnowledgeContracts.Generation(
                "generation",
                1,
                "guide",
                2,
                DIGEST,
                "worker",
                KnowledgeContracts.RetrievalMode.HYBRID,
                Optional.of("b".repeat(64)),
                2,
                1,
                10,
                Optional.empty(),
                NOW);
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeContracts.Generation(
                        "generation",
                        2,
                        "guide",
                        2,
                        DIGEST,
                        "worker",
                        KnowledgeContracts.RetrievalMode.KEYWORD,
                        Optional.empty(),
                        0,
                        1,
                        10,
                        Optional.empty(),
                        NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeContracts.Generation(
                        "generation",
                        1,
                        "guide",
                        2,
                        DIGEST,
                        "worker",
                        KnowledgeContracts.RetrievalMode.KEYWORD,
                        Optional.of("b".repeat(64)),
                        2,
                        1,
                        10,
                        Optional.empty(),
                        NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeContracts.SearchMatch(
                        source(), hybrid, List.of("excerpt"), Double.NaN, KnowledgeContracts.RetrievalMode.HYBRID));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeContracts.SearchMatch(
                        source(), hybrid, List.of("x".repeat(401)), 0.5, KnowledgeContracts.RetrievalMode.HYBRID));
    }

    @Test
    void orchestrationAvailabilityStartAndCountersFailClosed() {
        OrchestrationContracts.Availability available = new OrchestrationContracts.Availability(true, "");
        OrchestrationContracts.Availability unavailable =
                new OrchestrationContracts.Availability(false, " provider unavailable ");
        OrchestrationContracts.StartRequest start = new OrchestrationContracts.StartRequest(
                "definition",
                AutomationV6Fixtures.selection(new AgentRoleRef("worker", 2)),
                new OrchestrationContracts.ExecutionBudget(3, 100, 50, 5));
        OrchestrationContracts.ExecutionConsumption zero = OrchestrationContracts.ExecutionConsumption.zero();
        OrchestrationContracts.UnitResult unit = new OrchestrationContracts.UnitResult(
                "unit", TurnId.parse(UUID.randomUUID().toString()), "done");

        assertTrue(available.executable());
        assertEquals("provider unavailable", unavailable.reason());
        assertEquals(new AgentRoleRef("worker", 2), start.execution().role().orElseThrow());
        assertEquals(0, zero.turns());
        assertEquals("done", unit.summary());
        assertThrows(IllegalArgumentException.class, () -> new OrchestrationContracts.Availability(true, "unexpected"));
        assertThrows(IllegalArgumentException.class, () -> new OrchestrationContracts.Availability(false, ""));
        assertThrows(IllegalArgumentException.class, () -> new OrchestrationContracts.ExecutionBudget(10_001, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new OrchestrationContracts.ExecutionBudget(1, 0, 1, 1));
        assertThrows(
                IllegalArgumentException.class, () -> new OrchestrationContracts.ExecutionConsumption(0, 0, -1, 0));
    }

    private static LoopContracts.VerificationRule loopRule(
            LoopContracts.VerificationKind kind,
            Optional<String> tool,
            Optional<Integer> exitCode,
            Optional<String> pointer,
            Optional<com.javaclaw.api.CanonicalPayload> value) {
        return new LoopContracts.VerificationRule(kind, tool, exitCode, pointer, value);
    }

    private static AttachmentRef attachment() {
        return new AttachmentRef(DIGEST, "text/plain", "guide.txt", 10);
    }

    private static KnowledgeContracts.Source source() {
        return new KnowledgeContracts.Source("guide", 2, "Guide", attachment(), "generation", NOW);
    }

    private static KnowledgeContracts.Generation keywordGeneration(
            Optional<KnowledgeContracts.FallbackReason> fallback) {
        return new KnowledgeContracts.Generation(
                "generation",
                1,
                "guide",
                2,
                DIGEST,
                "worker",
                KnowledgeContracts.RetrievalMode.KEYWORD,
                Optional.empty(),
                0,
                1,
                10,
                fallback,
                NOW);
    }
}
