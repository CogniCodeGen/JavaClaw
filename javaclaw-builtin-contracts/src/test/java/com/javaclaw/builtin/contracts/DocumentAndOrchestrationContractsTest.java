package com.javaclaw.builtin.contracts;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.CanonicalPayload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentAndOrchestrationContractsTest {

    @Test
    void documentContractsNormalizeCursorsAndCopyPages() {
        DocumentContracts.Key key = new DocumentContracts.Key(" item ");
        DocumentContracts.PageRequest first = new DocumentContracts.PageRequest(null, 1);
        ArrayList<CanonicalPayload> source = new ArrayList<>(List.of(BuiltinContractsFixtures.payload()));
        DocumentContracts.Page page = new DocumentContracts.Page(source, null);

        source.clear();
        assertEquals("item", key.id());
        assertEquals("", first.afterKey());
        assertEquals(1, page.documents().size());
        assertThrows(IllegalArgumentException.class, () -> new DocumentContracts.PageRequest("", 0));
    }

    @Test
    void planDecisionIsBoundToCurrentQuestionHashAndGraphIsAcyclic() {
        String prompt = "Choose database";
        String hash = ContractDigests.sha256(prompt);
        PlanContracts.Decision decision = new PlanContracts.Decision(hash, "H2");
        PlanContracts.OpenQuestion question =
                new PlanContracts.OpenQuestion("database", prompt, hash, Optional.of(decision));
        PlanContracts.Definition plan = new PlanContracts.Definition(
                "plan",
                1,
                "Upgrade",
                "All gates pass",
                "Core only",
                List.of("rollback"),
                List.of(question),
                List.of(step("build", List.of()), step("verify", List.of("build"))),
                BuiltinContractsFixtures.NOW);

        assertTrue(plan.decisionsComplete());
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlanContracts.OpenQuestion(
                        "database",
                        "Choose another database",
                        ContractDigests.sha256("Choose another database"),
                        Optional.of(decision)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlanContracts.Definition(
                        "cycle",
                        1,
                        "Cycle",
                        "Done",
                        "All",
                        List.of(),
                        List.of(),
                        List.of(step("a", List.of("b")), step("b", List.of("a"))),
                        BuiltinContractsFixtures.NOW));
    }

    @Test
    void loopAcceptsOnlyExplicitUserOrToolEvidenceRules() {
        LoopContracts.VerificationRule confirmation = new LoopContracts.VerificationRule(
                LoopContracts.VerificationKind.USER_CONFIRMATION,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        LoopContracts.Definition loop = new LoopContracts.Definition(
                "loop", 1, "Improve", "Verified", "Work", 3, 2, confirmation, BuiltinContractsFixtures.NOW);

        assertEquals(3, loop.maximumIterations());
        assertThrows(
                IllegalArgumentException.class,
                () -> new LoopContracts.VerificationRule(
                        LoopContracts.VerificationKind.TOOL_EXIT_CODE,
                        Optional.empty(),
                        Optional.of(0),
                        Optional.empty(),
                        Optional.empty()));
    }

    @Test
    void sddTaskDigestUsesUnambiguousStructuralFraming() {
        SddContracts.Definition first = sdd("a\nb", List.of("c"));
        SddContracts.Definition second = sdd("a", List.of("b", "c"));

        assertNotEquals(first.taskDigest(), second.taskDigest());
    }

    @Test
    void workflowRequiresReachableSafeGraphAndRejectsScriptConfiguration() {
        WorkflowContracts.Node start = node("start", WorkflowContracts.NodeKind.START, Optional.empty());
        WorkflowContracts.Node turn = node("turn", WorkflowContracts.NodeKind.TURN, Optional.of("verify"));
        WorkflowContracts.Node end = node("end", WorkflowContracts.NodeKind.END, Optional.empty());
        WorkflowContracts.Definition workflow = new WorkflowContracts.Definition(
                "workflow",
                1,
                "Release",
                List.of(start, turn, end),
                List.of(edge("start", "turn"), edge("turn", "end")),
                10,
                BuiltinContractsFixtures.NOW);

        assertEquals(3, workflow.nodes().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowContracts.Node(
                        "bad",
                        WorkflowContracts.NodeKind.OUTPUT,
                        "Bad",
                        Optional.empty(),
                        WorkflowContracts.NodeConfig.empty()));
    }

    @Test
    void orchestrationAcceptsOnlyDefinitionProfileAndBoundedBudgetFromClient() {
        var budget = new OrchestrationContracts.ExecutionBudget(5, 1000, 500, 20);
        var request = new OrchestrationContracts.StartRequest(
                "definition", AutomationV6Fixtures.selection(new AgentRoleRef("profile", 1)), budget);
        var checkpoint = new OrchestrationContracts.ExecutionCheckpoint(
                BuiltinContractsFixtures.payload(), new OrchestrationContracts.ExecutionConsumption(2, 10, 20, 1));

        assertEquals(5, request.budget().maximumTurns());
        assertEquals(2, checkpoint.consumption().turns());
        assertThrows(
                IllegalArgumentException.class,
                () -> new OrchestrationContracts.StartRequest(
                        "definition",
                        AutomationV6Fixtures.selection(new AgentRoleRef("profile", 1)),
                        new OrchestrationContracts.ExecutionBudget(0, 1, 1, 1)));
    }

    private static PlanContracts.Step step(String id, List<String> dependencies) {
        return new PlanContracts.Step(id, id, "execute " + id, "verify " + id, dependencies);
    }

    private static WorkflowContracts.Node node(
            String id, WorkflowContracts.NodeKind kind, Optional<String> instruction) {
        return new WorkflowContracts.Node(id, kind, id, instruction, WorkflowContracts.NodeConfig.empty());
    }

    private static WorkflowContracts.Edge edge(String from, String to) {
        return new WorkflowContracts.Edge(from, to, Optional.empty());
    }

    private static SddContracts.Definition sdd(String design, List<String> tasks) {
        return new SddContracts.Definition(
                "sdd",
                1,
                "规格",
                new SddContracts.Content("验收", design, tasks),
                new SddContracts.VerificationRule(
                        SddContracts.VerificationKind.TOOL_EXIT_CODE,
                        "verify",
                        Optional.of(0),
                        Optional.empty(),
                        Optional.empty()),
                1,
                BuiltinContractsFixtures.NOW);
    }
}
