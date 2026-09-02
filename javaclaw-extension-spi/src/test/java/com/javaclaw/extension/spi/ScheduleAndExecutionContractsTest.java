package com.javaclaw.extension.spi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScheduleAndExecutionContractsTest {
    private static final WorkspaceId WORKSPACE_ID = WorkspaceId.random();

    @Test
    void 公开ExecutionReceipt移除恢复Payload并约束失败错误码() {
        ExtensionJob running = job(ExecutionState.RUNNING, Optional.empty(), SpiFixtures.NOW);
        ExtensionExecutionReceipt receipt = ExtensionExecutionReceipt.from(running);
        ExtensionExecutionReceipt failed = new ExtensionExecutionReceipt(
                "job-2",
                new ExtensionId("com.javaclaw.plan"),
                WORKSPACE_ID,
                "plan.execution",
                "definition-1",
                2,
                ExecutionState.FAILED,
                4,
                Optional.of("TOOL_FAILED"),
                SpiFixtures.NOW,
                SpiFixtures.NOW.plusSeconds(1));

        assertEquals(running.id(), receipt.id());
        assertEquals(running.definitionRevision(), receipt.definitionRevision());
        assertEquals("TOOL_FAILED", failed.errorCode().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> receipt(" ", ExecutionState.RUNNING, 1, 1, Optional.empty(), SpiFixtures.NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> receipt("job", ExecutionState.RUNNING, 0, 1, Optional.empty(), SpiFixtures.NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> receipt("job", ExecutionState.RUNNING, 1, 0, Optional.empty(), SpiFixtures.NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> receipt("job", ExecutionState.RUNNING, 1, 1, Optional.of("FAIL"), SpiFixtures.NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> receipt("job", ExecutionState.FAILED, 1, 1, Optional.empty(), SpiFixtures.NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> receipt("job", ExecutionState.RUNNING, 1, 1, Optional.empty(), SpiFixtures.NOW.minusSeconds(1)));
    }

    @Test
    void schedule目录在保存前重新确认Definition与Action精确版本() throws Exception {
        ScheduleTargetCatalogPort.DefinitionOption definition =
                new ScheduleTargetCatalogPort.DefinitionOption("com.javaclaw.plan", "plan-1", 3, "Plan 1");
        ScheduleTargetCatalogPort.ActionField field = new ScheduleTargetCatalogPort.ActionField(
                "scope", "范围", ScheduleTargetCatalogPort.ScalarType.STRING, true);
        ScheduleTargetCatalogPort.ActionOption action = new ScheduleTargetCatalogPort.ActionOption(
                "com.javaclaw.memory", "proposal/review", "审阅提案", List.of(field), 5);
        ScheduleTargetCatalogPort catalog = catalog(List.of(definition), List.of(action));

        assertEquals(definition, catalog.requireDefinition(WORKSPACE_ID, "com.javaclaw.plan", "plan-1", 3));
        assertEquals(action, catalog.requireAction(WORKSPACE_ID, "com.javaclaw.memory", "proposal/review", 5));
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.requireDefinition(WORKSPACE_ID, "com.javaclaw.plan", "plan-1", 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.requireAction(WORKSPACE_ID, "com.javaclaw.memory", "proposal/review", 4));
    }

    @Test
    void schedule目录条目限制标量字段数量唯一性和revision() {
        ScheduleTargetCatalogPort.DefinitionEntry entry =
                new ScheduleTargetCatalogPort.DefinitionEntry("  plan-1  ", 2, "  Plan 1  ");
        ScheduleTargetCatalogPort.DefinitionOption option =
                new ScheduleTargetCatalogPort.DefinitionOption("  com.javaclaw.plan  ", "  plan-1  ", 2, "  Plan 1  ");
        ScheduleTargetCatalogPort.ActionField field = new ScheduleTargetCatalogPort.ActionField(
                "  enabled  ", "  启用  ", ScheduleTargetCatalogPort.ScalarType.BOOLEAN, false);
        ScheduleTargetCatalogPort.ActionField count = new ScheduleTargetCatalogPort.ActionField(
                "count", "次数", ScheduleTargetCatalogPort.ScalarType.NUMBER, true);
        ScheduleTargetCatalogPort.ActionOption action = new ScheduleTargetCatalogPort.ActionOption(
                "  com.javaclaw.memory  ", "  proposal/review  ", "  审阅  ", List.of(field, count), 0);
        ScheduleTargetCatalogPort.ActionOption reordered = new ScheduleTargetCatalogPort.ActionOption(
                "com.javaclaw.memory", "proposal/review", "新标签", List.of(count, field), 0);

        assertEquals("plan-1", entry.definitionId());
        assertEquals("com.javaclaw.plan", option.extensionId());
        assertEquals("enabled", field.name());
        assertEquals("proposal/review", action.operation());
        assertEquals(action.schemaHash(), reordered.schemaHash());
        assertEquals(
                "{\"additionalProperties\":false,\"properties\":{\"count\":{\"type\":\"number\"},"
                        + "\"enabled\":{\"type\":\"boolean\"}},\"required\":[\"count\"],\"type\":\"object\"}",
                action.inputSchema().json());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScheduleTargetCatalogPort.ActionField(
                        "unsafe.name", "不安全", ScheduleTargetCatalogPort.ScalarType.STRING, true));
        assertThrows(
                IllegalArgumentException.class, () -> new ScheduleTargetCatalogPort.DefinitionEntry("plan", 0, "Plan"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScheduleTargetCatalogPort.DefinitionOption("plan", "id", 0, "Plan"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScheduleTargetCatalogPort.ActionOption(
                        "memory", "review", "Review", List.of(field, field), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScheduleTargetCatalogPort.ActionOption(
                        "memory", "review", "Review", java.util.Collections.nCopies(33, field), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScheduleTargetCatalogPort.ActionOption("memory", "review", "Review", List.of(), -1));
    }

    @Test
    void 未装配schedule目录对Definition和Action均失败关闭() {
        ScheduleTargetCatalogPort unavailable = ScheduleTargetCatalogPort.unavailable();

        assertThrows(IllegalStateException.class, () -> unavailable.definitions(WORKSPACE_ID));
        assertThrows(IllegalStateException.class, () -> unavailable.actions(WORKSPACE_ID));
        assertThrows(NullPointerException.class, () -> unavailable.definitions(null));
        assertThrows(NullPointerException.class, () -> unavailable.actions(null));
    }

    @Test
    void 扩展可声明固定参数Action和精确Definition目录() throws Exception {
        ScheduleTargetCatalogPort.ActionField field = new ScheduleTargetCatalogPort.ActionField(
                "count", "次数", ScheduleTargetCatalogPort.ScalarType.NUMBER, true);
        ExtensionContributions.SchedulableAction action = new ExtensionContributions.SchedulableAction(
                "  review  ", "  proposal/review  ", "  审阅提案  ", true, List.of(field), 2);
        ExtensionContributions.SchedulableDefinition definition = new ExtensionContributions.SchedulableDefinition(
                "  plans  ",
                "  Plan  ",
                context -> List.of(new ScheduleTargetCatalogPort.DefinitionEntry("plan-1", 2, "Plan 1")));

        assertEquals(ContributionKind.SCHEDULABLE_ACTION, action.kind());
        assertEquals(ContributionKind.SCHEDULABLE_ACTION, definition.kind());
        assertEquals(
                1, definition.provider().list(SpiFixtures.executionContext()).size());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionContributions.SchedulableAction(
                        "review", "proposal/review", "Review", true, List.of(field, field), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionContributions.SchedulableAction(
                        "review", "proposal/review", "Review", true, java.util.Collections.nCopies(33, field), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionContributions.SchedulableAction(
                        "review", "proposal/review", "Review", true, List.of(), -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionContributions.SchedulableDefinition("plans", " ", context -> List.of()));
    }

    @Test
    void job分页游标和Input等待关联保持稳定身份() {
        ExtensionJob first = job(ExecutionState.COMPLETED, Optional.empty(), SpiFixtures.NOW);
        ExtensionJobCursor cursor = new ExtensionJobCursor(SpiFixtures.NOW, "  job-1  ");
        ExtensionJobPage page = new ExtensionJobPage(List.of(first), Optional.of(cursor));
        ExtensionJobInputWait wait = new ExtensionJobInputWait("  request-1  ", TurnId.random());

        assertEquals("job-1", cursor.id());
        assertEquals(1, page.jobs().size());
        assertEquals("request-1", wait.requestId());
        assertThrows(IllegalArgumentException.class, () -> new ExtensionJobCursor(SpiFixtures.NOW, "bad id"));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionJobPage(List.of(), Optional.of(cursor)));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionJobInputWait("bad id", TurnId.random()));
    }

    @Test
    void job单元结果只允许WAITING_INPUT携带同一Turn关联() {
        TurnId turnId = TurnId.random();
        ExtensionJobInputWait wait = new ExtensionJobInputWait("request-1", turnId);
        ExtensionJobStepResult waiting = new ExtensionJobStepResult(
                SpiFixtures.payload(),
                SpiFixtures.payload(),
                ExecutionState.WAITING_INPUT,
                Optional.of(turnId),
                Optional.empty(),
                Optional.of(wait));
        ExtensionJobStepResult completed = new ExtensionJobStepResult(
                SpiFixtures.payload(),
                SpiFixtures.payload(),
                ExecutionState.COMPLETED,
                Optional.empty(),
                Optional.of("  receipt  "));

        assertEquals(wait, waiting.inputWait().orElseThrow());
        assertEquals("receipt", completed.effectReceiptKey().orElseThrow());
        assertInvalidStep(ExecutionState.QUEUED, Optional.empty(), Optional.empty());
        assertInvalidStep(ExecutionState.FAILED, Optional.empty(), Optional.empty());
        assertInvalidStep(ExecutionState.RUNNING, Optional.of(turnId), Optional.of(wait));
        assertInvalidStep(ExecutionState.WAITING_INPUT, Optional.empty(), Optional.of(wait));
        assertInvalidStep(ExecutionState.WAITING_INPUT, Optional.of(TurnId.random()), Optional.of(wait));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionJobStepResult(
                        SpiFixtures.payload(),
                        SpiFixtures.payload(),
                        ExecutionState.COMPLETED,
                        Optional.empty(),
                        Optional.of(" ")));
    }

    @Test
    void jobRuntimeContext只接收稳定治理端口() {
        ExtensionExecutionContext execution = SpiFixtures.executionContext();
        ExtensionPayloadCodec codec = new ExtensionPayloadCodec() {
            @Override
            public CanonicalPayload encode(Object value) {
                return SpiFixtures.payload();
            }

            @Override
            public <T> T decode(CanonicalPayload payload, Class<T> type) {
                return null;
            }
        };
        ExtensionJobRuntimeContext runtime = new ExtensionJobRuntimeContext(
                SpiFixtures.CLOCK,
                codec,
                execution.turns(),
                execution.managedStore(),
                execution.services(),
                execution.embeddings(),
                AutomationStepPort.unavailable(),
                ScheduledCommandPort.unavailable(),
                ScheduleLifecyclePort.unavailable());

        assertEquals(SpiFixtures.CLOCK, runtime.clock());
        assertEquals(SpiFixtures.payload(), runtime.payloads().encode("value"));
    }

    private static ExtensionExecutionReceipt receipt(
            String id,
            ExecutionState state,
            long definitionRevision,
            long revision,
            Optional<String> errorCode,
            Instant updatedAt) {
        return new ExtensionExecutionReceipt(
                id,
                new ExtensionId("com.javaclaw.plan"),
                WORKSPACE_ID,
                "plan.execution",
                "definition-1",
                definitionRevision,
                state,
                revision,
                errorCode,
                SpiFixtures.NOW,
                updatedAt);
    }

    private static ExtensionJob job(ExecutionState state, Optional<String> errorCode, Instant updatedAt) {
        return new ExtensionJob(
                "job-1",
                new ExtensionId("com.javaclaw.plan"),
                WORKSPACE_ID,
                "plan.execution",
                "definition-1",
                2,
                SpiFixtures.payload(),
                state,
                3,
                SpiFixtures.payload(),
                1,
                Optional.empty(),
                errorCode,
                SpiFixtures.NOW,
                updatedAt);
    }

    private static ScheduleTargetCatalogPort catalog(
            List<ScheduleTargetCatalogPort.DefinitionOption> definitions,
            List<ScheduleTargetCatalogPort.ActionOption> actions) {
        return new ScheduleTargetCatalogPort() {
            @Override
            public List<DefinitionOption> definitions(WorkspaceId workspaceId) {
                return List.copyOf(definitions);
            }

            @Override
            public List<ActionOption> actions(WorkspaceId workspaceId) {
                return List.copyOf(actions);
            }
        };
    }

    private static void assertInvalidStep(
            ExecutionState state, Optional<TurnId> turnId, Optional<ExtensionJobInputWait> wait) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionJobStepResult(
                        SpiFixtures.payload(), SpiFixtures.payload(), state, turnId, Optional.empty(), wait));
    }
}
