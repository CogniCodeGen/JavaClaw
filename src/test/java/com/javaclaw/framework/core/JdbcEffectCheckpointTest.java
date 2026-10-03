package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.EffectCheckpointV1;
import com.javaclaw.framework.spi.EffectReconciliationV1;
import com.javaclaw.framework.spi.RunEventDraft;
import com.javaclaw.framework.store.JdbcRunStore;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcEffectCheckpointTest {
    private static final long BASE = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final String OLD = "123e4567-e89b-42d3-a456-426614174000";
    private static final String NEW = "123e4567-e89b-42d3-a456-426614174001";

    @Test
    void partialTaskCheckpointDurablyReconcilesOnlyTheMatchedUnknownClick() {
        Fixture fixture = new Fixture();
        TaskContractV2 contract = contract();
        RunId id = fixture.start();
        long contractSequence = fixture.append(id, "core.task.contract", 2,
                fixture.json.valueToTree(contract)).sequence();
        fixture.append(id, "core.tool.receipt", 1, receipt(1, "open", "ACCEPTED", "", ""));
        fixture.append(id, "core.tool.receipt", 1,
                receipt(2, "observe", "OBSERVED", "月视图", OLD));
        fixture.runs.appendBatch(id, Set.of(RunState.RUNNING), RunState.RUNNING,
                List.of(new RunEventDraft("core.step.started", 1, "framework.core", null, null,
                                JsonNodeFactory.instance.objectNode().put("stepId", "click-step")),
                        new RunEventDraft("core.tool.started", 1, "framework.core", null, null,
                                JsonNodeFactory.instance.objectNode()
                                        .put("tool", "desktop_session_click")
                                        .put("invocationId", "call-click")
                                        .put("effectPolicy", "OBSERVATION_GATED")
                                        .put("resourceKey", "desktop:window-1")
                                        .put("fingerprint", "click-step")))).orElseThrow();
        fixture.append(id, "core.tool.receipt", 1,
                receipt(3, "click", "UNKNOWN", "", OLD));
        fixture.append(id, "core.tool.receipt", 1,
                receipt(4, "observe", "OBSERVED", "日程", NEW));

        var proof = new EffectReconciliationV1("call-click", "session-1", "window-1", OLD, NEW);
        var checkpoint = new EffectCheckpointV1(proof, contractSequence,
                "click-agenda", "agenda", "日程", "frame:4");
        assertEquals(TaskOutcome.PARTIAL, TaskResultEvaluator.evaluateV2(contract,
                fixture.runs.eventsAfter(id, 0), "").outcome());
        assertTrue(fixture.runs.reconcileEffect(id, proof).isEmpty(),
                "a view alone does not authorize another input");
        assertTrue(fixture.runs.verifyEffectCheckpoint(id, new EffectCheckpointV1(proof,
                contractSequence, "click-agenda", "reminders", "日程", "frame:4")).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> fixture.append(id,
                "core.task.checkpoint_verified", 1, JsonNodeFactory.instance.objectNode()));

        var control = new RunControl(RunBudget.UNBOUNDED, Clock.systemUTC());
        var reconciled = VerifiedEffectReconciler.reconcileCheckpoints(fixture.runs, id,
                control, contract, fixture.runs.eventsAfter(id, 0));
        assertEquals(1, reconciled.size());
        assertEquals("core.task.checkpoint_verified", reconciled.getFirst().checkpointEvent().type());
        assertEquals("core.effect.reconciled", reconciled.getFirst().reconciliationEvent().type());
        assertEquals("call-click", reconciled.getFirst().proof().invocationId());
        assertEquals(List.of(), VerifiedEffectReconciler.reconcileCheckpoints(fixture.runs, id,
                control, contract, fixture.runs.eventsAfter(id, 0)));
        assertTrue(fixture.runs.reconcileEffect(id, proof).isEmpty());
        assertEquals("frame:4", fixture.runs.verifyEffectCheckpoint(id, checkpoint)
                .orElseThrow().payload().path("observationEvidenceRef").asText());
    }

    @Test
    void contractDeclaredAfterActionCannotAuthorizeCheckpoint() {
        Fixture fixture = new Fixture();
        RunId id = fixture.start();
        fixture.append(id, "core.tool.receipt", 1, receipt(1, "open", "ACCEPTED", "", ""));
        fixture.append(id, "core.tool.receipt", 1,
                receipt(2, "observe", "OBSERVED", "月视图", OLD));
        fixture.append(id, "core.tool.started", 1,
                JsonNodeFactory.instance.objectNode().put("tool", "desktop_session_click")
                        .put("invocationId", "call-click")
                        .put("effectPolicy", "OBSERVATION_GATED")
                        .put("resourceKey", "desktop:window-1"));
        fixture.append(id, "core.tool.receipt", 1,
                receipt(3, "click", "UNKNOWN", "", OLD));
        long lateContract = fixture.append(id, "core.task.contract", 2,
                fixture.json.valueToTree(contract())).sequence();
        fixture.append(id, "core.tool.receipt", 1,
                receipt(4, "observe", "OBSERVED", "日程", NEW));
        var proof = new EffectReconciliationV1("call-click", "session-1", "window-1", OLD, NEW);
        assertTrue(fixture.runs.verifyEffectCheckpoint(id,
                new EffectCheckpointV1(proof, lateContract, "click-agenda",
                        "agenda", "日程", "frame:4")).isEmpty());
    }

    private static TaskContractV2 contract() {
        return new TaskContractV2(2, "依次查看日程和提醒", "日历", List.of(
                new TaskCriterion("open", "打开", "日历", "open", "ACCEPTED"),
                new TaskCriterion("click-agenda", "点击日程", "日历", "click", "ACCEPTED"),
                new TaskCriterion("agenda", "查看日程", "日历", "observe", "OBSERVED", "日程"),
                new TaskCriterion("click-reminders", "点击提醒", "日历", "click", "ACCEPTED"),
                new TaskCriterion("reminders", "查看提醒", "日历", "observe", "OBSERVED", "提醒")),
                true, true, "definition");
    }

    private static ObjectNode receipt(int step, String operation, String status,
                                      String subject, String observationId) {
        String invocationId = operation.equals("click") ? "call-click" : "call-" + step;
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("invocationId", invocationId)
                .put("tool", "desktop_session_" + operation)
                .put("target", "日历").put("operation", operation)
                .put("status", status).put("subject", subject)
                .put("evidenceRef", "frame:" + step)
                .put("observedAt", Instant.ofEpochMilli(BASE + step * 1000).toString());
        ObjectNode metadata = payload.putObject("metadata")
                .put("sessionId", "session-1").put("targetId", "window-1");
        if (!operation.equals("open")) {
            metadata.put("observationId", observationId)
                    .put("windowGeneration", 4);
        }
        if (operation.equals("observe")) {
            metadata.put("contentRevision", step)
                    .put("capturedAtMillis", BASE + step * 1000)
                    .put("viewEvidence", "heading:1,2,10,10|content:2,20,20,20");
        }
        if (operation.equals("click")) {
            metadata.put("delivery", "MAYBE_SENT")
                    .put("dispatchAttempted", true);
        }
        return payload;
    }

    private static final class Fixture {
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        private final JdbcRunStore runs;

        private Fixture() {
            var source = new DriverManagerDataSource(
                    "jdbc:h2:mem:effect-checkpoint-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                    "sa", "");
            new SchemaInitializer(source).initialize();
            runs = new JdbcRunStore(new JdbcTemplate(source),
                    new DataSourceTransactionManager(source), json, Clock.systemUTC());
        }

        private RunId start() {
            RunId id = RunId.random();
            RunRequest request = RunRequest.builder()
                    .agent(AgentDefinitionRef.latest("test.agent"))
                    .profile(RunProfileRef.latest("test.profile"))
                    .source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", "session"))
                    .input(InputBlock.text("hello"))
                    .permissionCeiling(PermissionSet.UNRESTRICTED)
                    .budget(RunBudget.UNBOUNDED)
                    .build();
            runs.create(id, request, "test-plan", new RunEventDraft(
                    "core.run.created", 1, "framework.core", null, null,
                    JsonNodeFactory.instance.objectNode()));
            append(id, "core.run.started", 1, JsonNodeFactory.instance.objectNode(),
                    Set.of(RunState.CREATED), RunState.RUNNING);
            return id;
        }

        private RunEventEnvelope append(RunId id, String type, int version,
                                        com.fasterxml.jackson.databind.JsonNode payload) {
            return append(id, type, version, payload, Set.of(RunState.RUNNING), RunState.RUNNING);
        }

        private RunEventEnvelope append(RunId id, String type, int version,
                                        com.fasterxml.jackson.databind.JsonNode payload,
                                        Set<RunState> expected, RunState next) {
            return runs.append(id, expected, next,
                    new RunEventDraft(type, version, "framework.core", null, null, payload),
                    null, null).orElseThrow();
        }
    }
}
