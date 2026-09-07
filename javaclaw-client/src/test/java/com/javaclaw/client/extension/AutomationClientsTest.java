package com.javaclaw.client.extension;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.LoopContracts;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.PlanContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.builtin.contracts.ScheduleManagementContracts;
import com.javaclaw.builtin.contracts.SddContracts;
import com.javaclaw.builtin.contracts.VersionedExtensionDocument;
import com.javaclaw.builtin.contracts.WorkflowContracts;
import com.javaclaw.builtin.contracts.WorkflowManagementContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AutomationClientsTest {
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("114fdfd7-d4e7-42f4-9563-dd552b61f97d");
    private static final ThreadId PARENT = ThreadId.parse("dc914106-e26f-4ab1-a3c5-001c12cb9ec1");
    private static final AgentRoleRef PROFILE = new AgentRoleRef("profile", 3);
    private static final OrchestrationContracts.ExecutionBudget BUDGET =
            new OrchestrationContracts.ExecutionBudget(5, 2_000, 1_000, 20);
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
    private static final CommandOptions CREATE = new CommandOptions("create", 0);
    private static final CommandOptions REVISION_ONE = new CommandOptions("revision-one", 1);
    private static final CommandOptions REVISION_TWO = new CommandOptions("revision-two", 2);

    @Test
    void planFacadeUsesManagedCrudAndExactDefinitionStart() throws IOException {
        PlanContracts.ManagementSaveRequest request = planRequest();
        PlanContracts.Definition first = plan(1);
        PlanContracts.Definition second = plan(2);
        ScriptedExtensionConnection script = new ScriptedExtensionConnection(WORKSPACE);
        script.expectCrud(BuiltinExtensionIds.PLAN, request, first, second);
        OrchestrationContracts.StartRequest start = start(first.id());
        ExtensionExecutionReceipt receipt = receipt(BuiltinExtensionIds.PLAN, "plan-job", second, 1);
        script.expectCommand(BuiltinExtensionIds.PLAN, "execution/start", start, REVISION_TWO, receipt, 1, PARENT);
        try (ClientFixture fixture = fixture(script)) {
            PlanClient client = fixture.clients().plans();
            verifyCrud(client, request, first, second);
            assertEquals(receipt, client.start(WORKSPACE, Optional.of(PARENT), start, REVISION_TWO));
        }
        script.assertExhausted();
    }

    @Test
    void loopFacadeSeparatesSaveAndEvidenceConfirmation() throws IOException {
        LoopContracts.ManagementSaveRequest request = loopRequest();
        LoopContracts.Definition first = loop(1);
        LoopContracts.Definition second = loop(2);
        ScriptedExtensionConnection script = new ScriptedExtensionConnection(WORKSPACE);
        script.expectCrud(BuiltinExtensionIds.LOOP, request, first, second, "definition/save", "definition/save");
        expectStart(script, BuiltinExtensionIds.LOOP, second);
        LoopContracts.Confirmation confirmation = new LoopContracts.Confirmation("loop-job", 2, true);
        ExtensionExecutionReceipt confirmed = receipt(BuiltinExtensionIds.LOOP, confirmation.jobId(), second, 2);
        script.expectCommand(BuiltinExtensionIds.LOOP, "execution/confirm", confirmation, REVISION_ONE, confirmed, 2);
        try (ClientFixture fixture = fixture(script)) {
            LoopClient client = fixture.clients().loops();
            verifyCrud(client, request, first, second);
            assertEquals(startReceipt(BuiltinExtensionIds.LOOP, second), start(client, second));
            assertEquals(confirmed, client.confirm(WORKSPACE, confirmation, REVISION_ONE));
        }
        script.assertExhausted();
    }

    @Test
    void workflowFacadeUsesSafeSaveAndInputContinuation() throws IOException {
        WorkflowManagementContracts.SaveRequest request = workflowRequest();
        WorkflowContracts.Definition first = workflow(1);
        WorkflowContracts.Definition second = workflow(2);
        ScriptedExtensionConnection script = new ScriptedExtensionConnection(WORKSPACE);
        script.expectCrud(BuiltinExtensionIds.WORKFLOW, request, first, second, "definition/save", "definition/save");
        expectStart(script, BuiltinExtensionIds.WORKFLOW, second);
        WorkflowContracts.ContinueInput continuation = new WorkflowContracts.ContinueInput("workflow-job", "input-1");
        ExtensionExecutionReceipt continued = receipt(BuiltinExtensionIds.WORKFLOW, continuation.jobId(), second, 2);
        script.expectCommand(
                BuiltinExtensionIds.WORKFLOW, "execution/input/continue", continuation, REVISION_ONE, continued, 2);
        try (ClientFixture fixture = fixture(script)) {
            WorkflowClient client = fixture.clients().workflows();
            verifyCrud(client, request, first, second);
            assertEquals(startReceipt(BuiltinExtensionIds.WORKFLOW, second), start(client, second));
            assertEquals(continued, client.continueInput(WORKSPACE, continuation, REVISION_ONE));
        }
        script.assertExhausted();
    }

    @Test
    void sddFacadeBindsApprovalAndExecutionRevisions() throws IOException {
        SddContracts.ManagementSaveRequest request = sddRequest();
        SddContracts.Definition first = sdd(1);
        SddContracts.Definition second = sdd(2);
        ScriptedExtensionConnection script = new ScriptedExtensionConnection(WORKSPACE);
        script.expectCrud(BuiltinExtensionIds.SDD, request, first, second);
        expectStart(script, BuiltinExtensionIds.SDD, second);
        SddContracts.Approval approval = new SddContracts.Approval(
                "sdd-job", SddContracts.ApprovalKind.SPECIFICATION, second.specificationDigest());
        ExtensionExecutionReceipt approved = receipt(BuiltinExtensionIds.SDD, approval.jobId(), second, 2);
        script.expectCommand(BuiltinExtensionIds.SDD, "execution/approve", approval, REVISION_ONE, approved, 2);
        try (ClientFixture fixture = fixture(script)) {
            SddClient client = fixture.clients().sdd();
            verifyCrud(client, request, first, second);
            assertEquals(startReceipt(BuiltinExtensionIds.SDD, second), start(client, second));
            assertEquals(approved, client.approve(WORKSPACE, approval, REVISION_ONE));
        }
        script.assertExhausted();
    }

    @Test
    void scheduleFacadeUsesAuthoritativePreviewOccurrenceAndManualRun() throws IOException {
        ScheduleManagementContracts.SaveRequest request = scheduleRequest();
        ScheduleContracts.Definition first = schedule(1);
        ScheduleContracts.Definition second = schedule(2);
        ScriptedExtensionConnection script = new ScriptedExtensionConnection(WORKSPACE);
        script.expectCrud(BuiltinExtensionIds.SCHEDULE, request, first, second);
        ScheduleContracts.PreviewRequest previewRequest = new ScheduleContracts.PreviewRequest("schedule", 2, NOW);
        ScheduleContracts.Preview preview = new ScheduleContracts.Preview(List.of(
                NOW.plusSeconds(60),
                NOW.plusSeconds(120),
                NOW.plusSeconds(180),
                NOW.plusSeconds(240),
                NOW.plusSeconds(300)));
        ScheduleContracts.Occurrence occurrence = occurrence(second);
        ScheduleContracts.OccurrenceQuery query =
                new ScheduleContracts.OccurrenceQuery(Optional.of(second.id()), "", 20);
        script.expectQuery(BuiltinExtensionIds.SCHEDULE, "preview", previewRequest, preview, 0);
        script.expectQuery(
                BuiltinExtensionIds.SCHEDULE,
                "occurrence/list",
                query,
                new ScheduleContracts.OccurrencePage(List.of(occurrence), ""),
                0);
        ScheduleContracts.ManualRun run = new ScheduleContracts.ManualRun(second.id());
        script.expectCommand(BuiltinExtensionIds.SCHEDULE, "occurrence/run", run, REVISION_TWO, occurrence, 1);
        try (ClientFixture fixture = fixture(script)) {
            ScheduleClient client = fixture.clients().schedules();
            verifyCrud(client, request, first, second);
            assertEquals(preview, client.preview(WORKSPACE, previewRequest));
            assertEquals(
                    List.of(occurrence), client.occurrences(WORKSPACE, query).occurrences());
            assertEquals(occurrence, client.run(WORKSPACE, run, REVISION_TWO));
        }
        script.assertExhausted();
    }

    @Test
    void facadeRejectsMissingExpectedRevisionAndMismatchedResponseRevision() throws IOException {
        ScriptedExtensionConnection script = new ScriptedExtensionConnection(WORKSPACE);
        try (ClientFixture fixture = fixture(script)) {
            BuiltinExtensionClients clients = fixture.clients();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> clients.plans().create(WORKSPACE, planRequest(), REVISION_ONE));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> clients.plans().start(WORKSPACE, Optional.empty(), start("plan"), CREATE));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> clients.loops().confirm(WORKSPACE, new LoopContracts.Confirmation("job", 1, true), CREATE));
            ScheduleContracts.PreviewRequest request = new ScheduleContracts.PreviewRequest("schedule", 1, NOW);
            script.expectQuery(BuiltinExtensionIds.SCHEDULE, "preview", request, schedulePreview(), 1);
            assertThrows(IllegalStateException.class, () -> clients.schedules().preview(WORKSPACE, request));
        }
        script.assertExhausted();
    }

    private static void verifyCrud(
            PlanClient client,
            PlanContracts.ManagementSaveRequest request,
            PlanContracts.Definition first,
            PlanContracts.Definition second) {
        assertEquals(first, client.read(WORKSPACE, first.id()));
        assertEquals(List.of(first), client.list(WORKSPACE, "", 10).documents());
        assertEquals(first, client.create(WORKSPACE, request, CREATE));
        assertEquals(second, client.update(WORKSPACE, request, REVISION_ONE));
        assertEquals(new DocumentContracts.Deleted(first.id()), client.delete(WORKSPACE, first.id(), REVISION_TWO));
    }

    private static void verifyCrud(
            LoopClient client,
            LoopContracts.ManagementSaveRequest request,
            LoopContracts.Definition first,
            LoopContracts.Definition second) {
        assertEquals(first, client.read(WORKSPACE, first.id()));
        assertEquals(List.of(first), client.list(WORKSPACE, "", 10).documents());
        assertEquals(first, client.create(WORKSPACE, request, CREATE));
        assertEquals(second, client.update(WORKSPACE, request, REVISION_ONE));
        assertEquals(new DocumentContracts.Deleted(first.id()), client.delete(WORKSPACE, first.id(), REVISION_TWO));
    }

    private static void verifyCrud(
            WorkflowClient client,
            WorkflowManagementContracts.SaveRequest request,
            WorkflowContracts.Definition first,
            WorkflowContracts.Definition second) {
        assertEquals(first, client.read(WORKSPACE, first.id()));
        assertEquals(List.of(first), client.list(WORKSPACE, "", 10).documents());
        assertEquals(first, client.create(WORKSPACE, request, CREATE));
        assertEquals(second, client.update(WORKSPACE, request, REVISION_ONE));
        assertEquals(new DocumentContracts.Deleted(first.id()), client.delete(WORKSPACE, first.id(), REVISION_TWO));
    }

    private static void verifyCrud(
            SddClient client,
            SddContracts.ManagementSaveRequest request,
            SddContracts.Definition first,
            SddContracts.Definition second) {
        assertEquals(first, client.read(WORKSPACE, first.id()));
        assertEquals(List.of(first), client.list(WORKSPACE, "", 10).documents());
        assertEquals(first, client.create(WORKSPACE, request, CREATE));
        assertEquals(second, client.update(WORKSPACE, request, REVISION_ONE));
        assertEquals(new DocumentContracts.Deleted(first.id()), client.delete(WORKSPACE, first.id(), REVISION_TWO));
    }

    private static void verifyCrud(
            ScheduleClient client,
            ScheduleManagementContracts.SaveRequest request,
            ScheduleContracts.Definition first,
            ScheduleContracts.Definition second) {
        assertEquals(first, client.read(WORKSPACE, first.id()));
        assertEquals(List.of(first), client.list(WORKSPACE, "", 10).documents());
        assertEquals(first, client.create(WORKSPACE, request, CREATE));
        assertEquals(second, client.update(WORKSPACE, request, REVISION_ONE));
        assertEquals(new DocumentContracts.Deleted(first.id()), client.delete(WORKSPACE, first.id(), REVISION_TWO));
    }

    private static ExtensionExecutionReceipt start(LoopClient client, LoopContracts.Definition definition) {
        return client.start(WORKSPACE, Optional.empty(), start(definition.id()), REVISION_TWO);
    }

    private static ExtensionExecutionReceipt start(WorkflowClient client, WorkflowContracts.Definition definition) {
        return client.start(WORKSPACE, Optional.empty(), start(definition.id()), REVISION_TWO);
    }

    private static ExtensionExecutionReceipt start(SddClient client, SddContracts.Definition definition) {
        return client.start(WORKSPACE, Optional.empty(), start(definition.id()), REVISION_TWO);
    }

    private static void expectStart(
            ScriptedExtensionConnection script, String extensionId, VersionedExtensionDocument definition) {
        script.expectCommand(
                extensionId,
                "execution/start",
                start(definition.id()),
                REVISION_TWO,
                startReceipt(extensionId, definition),
                1);
    }

    private static ExtensionExecutionReceipt startReceipt(String extensionId, VersionedExtensionDocument definition) {
        return receipt(extensionId, definition.id() + "-job", definition, 1);
    }

    private static ExtensionExecutionReceipt receipt(
            String extensionId, String jobId, VersionedExtensionDocument definition, long revision) {
        return new ExtensionExecutionReceipt(
                jobId,
                new ExtensionId(extensionId),
                WORKSPACE,
                "definition-execution",
                definition.id(),
                definition.revision(),
                ExecutionState.QUEUED,
                revision,
                Optional.empty(),
                NOW,
                NOW);
    }

    private static OrchestrationContracts.StartRequest start(String definitionId) {
        return new OrchestrationContracts.StartRequest(definitionId, AutomationV6Fixtures.selection(PROFILE), BUDGET);
    }

    private static PlanContracts.ManagementSaveRequest planRequest() {
        return new PlanContracts.ManagementSaveRequest(
                "plan",
                "Release",
                "all gates pass",
                "v5",
                List.of(new PlanContracts.ManagementRisk("risk-1", "build failure")),
                List.of(),
                List.of(new PlanContracts.ManagementStep(
                        "step-1", "verify", "Verify", "run verify", "exit code 0", List.of())));
    }

    private static PlanContracts.Definition plan(long revision) {
        return new PlanContracts.Definition(
                "plan",
                revision,
                "Release",
                "all gates pass",
                "v5",
                List.of("build failure"),
                List.of(),
                List.of(new PlanContracts.Step("verify", "Verify", "run verify", "exit code 0", List.of())),
                NOW.plusSeconds(revision));
    }

    private static LoopContracts.ManagementSaveRequest loopRequest() {
        return new LoopContracts.ManagementSaveRequest(
                "loop",
                "Improve",
                "verified",
                "run checks",
                3,
                2,
                LoopContracts.VerificationKind.USER_CONFIRMATION,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static LoopContracts.Definition loop(long revision) {
        LoopContracts.VerificationRule rule = new LoopContracts.VerificationRule(
                LoopContracts.VerificationKind.USER_CONFIRMATION,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        return new LoopContracts.Definition(
                "loop", revision, "Improve", "verified", "run checks", 3, 2, rule, NOW.plusSeconds(revision));
    }

    private static WorkflowManagementContracts.SaveRequest workflowRequest() {
        String empty = "";
        WorkflowManagementContracts.NodeRow start = new WorkflowManagementContracts.NodeRow(
                "start",
                WorkflowContracts.NodeKind.START,
                "Start",
                empty,
                empty,
                empty,
                empty,
                empty,
                empty,
                empty,
                empty,
                empty,
                empty,
                empty,
                empty,
                0,
                empty);
        WorkflowManagementContracts.NodeRow end = new WorkflowManagementContracts.NodeRow(
                "end",
                WorkflowContracts.NodeKind.END,
                "End",
                empty,
                empty,
                empty,
                empty,
                empty,
                empty,
                empty,
                empty,
                empty,
                empty,
                empty,
                empty,
                0,
                empty);
        return new WorkflowManagementContracts.SaveRequest(
                "workflow",
                "Release",
                List.of(start, end),
                List.of(),
                List.of(),
                List.of(new WorkflowManagementContracts.EdgeRow("edge-1", "start", "end", empty)),
                10);
    }

    private static WorkflowContracts.Definition workflow(long revision) {
        WorkflowContracts.Node start = new WorkflowContracts.Node(
                "start",
                WorkflowContracts.NodeKind.START,
                "Start",
                Optional.empty(),
                WorkflowContracts.NodeConfig.empty());
        WorkflowContracts.Node end = new WorkflowContracts.Node(
                "end", WorkflowContracts.NodeKind.END, "End", Optional.empty(), WorkflowContracts.NodeConfig.empty());
        return new WorkflowContracts.Definition(
                "workflow",
                revision,
                "Release",
                List.of(start, end),
                List.of(new WorkflowContracts.Edge("start", "end", Optional.empty())),
                10,
                NOW.plusSeconds(revision));
    }

    private static SddContracts.ManagementSaveRequest sddRequest() {
        return new SddContracts.ManagementSaveRequest(
                "sdd",
                "Release",
                "all checks pass",
                "governed turns",
                List.of(new SddContracts.ManagementTask("task-1", "implement")),
                SddContracts.VerificationKind.TOOL_EXIT_CODE,
                "verify",
                Optional.of(0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                1);
    }

    private static SddContracts.Definition sdd(long revision) {
        SddContracts.VerificationRule rule = new SddContracts.VerificationRule(
                SddContracts.VerificationKind.TOOL_EXIT_CODE,
                "verify",
                Optional.of(0),
                Optional.empty(),
                Optional.empty());
        return new SddContracts.Definition(
                "sdd",
                revision,
                "Release",
                new SddContracts.Content("all checks pass", "governed turns", List.of("implement")),
                rule,
                1,
                NOW.plusSeconds(revision));
    }

    private static ScheduleManagementContracts.SaveRequest scheduleRequest() {
        return new ScheduleManagementContracts.SaveRequest(
                "schedule",
                "Daily",
                true,
                ScheduleContracts.TimingKind.FIXED_INTERVAL,
                ScheduleContracts.TargetKind.TURN_TEMPLATE,
                ScheduleManagementContracts.TURN_TEMPLATE_EXTENSION,
                ScheduleManagementContracts.TURN_TEMPLATE_ID,
                0,
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.of(60L),
                Optional.of(NOW.plusSeconds(60)),
                AutomationV6Fixtures.selection(new AgentRoleRef(PROFILE.id(), PROFILE.revision())),
                "Scheduled task",
                "run checks",
                BUDGET.maximumTurns(),
                BUDGET.inputTokens(),
                BUDGET.outputTokens(),
                BUDGET.toolCalls(),
                List.of());
    }

    private static ScheduleContracts.Definition schedule(long revision) {
        ScheduleContracts.Target target = ScheduleContracts.Target.turn(new ScheduleContracts.TurnTemplate(
                AutomationV6Fixtures.selection(PROFILE), "Scheduled task", "run checks", BUDGET));
        return new ScheduleContracts.Definition(
                "schedule",
                revision,
                "Daily",
                true,
                ScheduleContracts.Timing.fixed(Duration.ofMinutes(60), NOW.plusSeconds(60)),
                target,
                ScheduleContracts.OverlapPolicy.SKIP_IF_RUNNING,
                ScheduleContracts.MisfirePolicy.DO_NOT_CATCH_UP,
                NOW.plusSeconds(revision));
    }

    private static ScheduleContracts.Occurrence occurrence(ScheduleContracts.Definition definition) {
        return new ScheduleContracts.Occurrence(
                new ScheduleContracts.OccurrenceIdentity("occurrence-1", definition.id(), definition.revision()),
                definition,
                NOW,
                new ScheduleContracts.OccurrenceStatus(
                        ScheduleContracts.OccurrenceState.COMPLETED, Optional.empty(), Optional.empty()),
                NOW,
                NOW);
    }

    private static ScheduleContracts.Preview schedulePreview() {
        return new ScheduleContracts.Preview(List.of(
                NOW.plusSeconds(60),
                NOW.plusSeconds(120),
                NOW.plusSeconds(180),
                NOW.plusSeconds(240),
                NOW.plusSeconds(300)));
    }

    private static ClientFixture fixture(ScriptedExtensionConnection script) {
        RpcClientConnection connection = new RpcClientConnection(script, new CanonicalJson(), ignored -> {});
        return new ClientFixture(new BuiltinExtensionClients(new ExtensionClient(connection)), connection);
    }

    private record ClientFixture(BuiltinExtensionClients clients, RpcClientConnection connection)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            connection.close();
        }
    }
}
