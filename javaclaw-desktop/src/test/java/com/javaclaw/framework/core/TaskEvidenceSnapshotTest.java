package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.StoredRun;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TaskEvidenceSnapshotTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void verifiedSnapshotRequiresExactCurrentHostReceiptsAndDurableReferences() {
        Fixture fixture = new Fixture();
        var snapshot = fixture.lifecycle(true).evidence(fixture.id).orElseThrow();
        assertEquals(fixture.id, snapshot.runId());
        assertEquals(fixture.contract, snapshot.contract());
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, snapshot.result().outcome());
        assertEquals(List.of("receipt-1"), snapshot.result().evidenceRefs());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.events().clear());
    }

    @Test
    void staleOrForgedPositiveOutcomesNeverSubstituteForRechecking() {
        for (int scenario = 0; scenario < 7; scenario++) {
            Fixture fixture = new Fixture();
            if (scenario == 0) fixture.events.remove(1);
            if (scenario == 1) fixture.events.set(1, fixture.event(2, "core.tool.receipt", 1,
                    "plugin", fixture.events.get(1).payload()));
            if (scenario == 2) fixture.events.set(1, fixture.event(2, "core.tool.receipt", 1,
                    "framework.core", fixture.events.get(1).payload().deepCopy()
                            .withObject("/").put("target", "other@example.com")));
            if (scenario == 3) fixture.outcome(List.of("another-receipt"), TaskOutcome.VERIFIED_COMPLETE);
            if (scenario == 4) fixture.outcome(List.of(), TaskOutcome.VERIFIED_COMPLETE);
            if (scenario == 5) fixture.outcome(List.of("receipt-1"), TaskOutcome.UNVERIFIED);
            if (scenario == 6) fixture.events.remove(0);
            assertNotEquals(TaskOutcome.VERIFIED_COMPLETE,
                    fixture.lifecycle(true).evidence(fixture.id).orElseThrow().result().outcome(), "scenario=" + scenario);
        }
    }

    @Test
    void unfinishedDescendantAndUnsupportedHarnessFailClosed() {
        Fixture fixture = new Fixture();
        fixture.children.add(new StoredRun(new RunSnapshot(RunId.random(), RunState.RUNNING, "plan", 1,
                Instant.EPOCH, Instant.EPOCH, null, null, 1), fixture.request));
        assertEquals(TaskOutcome.UNVERIFIED,
                fixture.lifecycle(true).evidence(fixture.id).orElseThrow().result().outcome());
        assertTrue(fixture.lifecycle(false).evidence(fixture.id).isEmpty());
    }

    private static final class Fixture {
        final RunId id = RunId.random();
        final RunRequest request = RunRequest.builder().agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "session")).input(InputBlock.text("发送通知")).build();
        final TaskContractV3 contract = new TaskContractV3(3, "发送通知", List.of(new TaskCriterionV3(
                "mail", "发送通知", "email.send", CapabilityMetadata.TargetKind.EMAIL_ADDRESS,
                "person@example.com", EffectReceiptV1.Status.ACCEPTED, "")), true, true, "definition");
        final List<RunEventEnvelope> events = new ArrayList<>();
        final List<StoredRun> children = new ArrayList<>();
        final RunStore runs = (RunStore) Proxy.newProxyInstance(RunStore.class.getClassLoader(),
                new Class<?>[]{RunStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "find" -> Optional.of(new StoredRun(new RunSnapshot(id, RunState.COMPLETED, "plan",
                            events.size(), Instant.EPOCH, Instant.EPOCH, null, null, 1), request));
                    case "eventsAfter" -> List.copyOf(events);
                    case "childRuns" -> List.copyOf(children);
                    case "readable" -> true;
                    default -> throw new AssertionError("不允许验收查询改变运行状态：" + method.getName());
                });

        Fixture() {
            events.add(event(1, "core.task.contract", 3, "framework.core", JSON.valueToTree(contract)));
            var receipt = JSON.createObjectNode().put("invocationId", "call-1").put("tool", "email_send")
                    .put("operation", "send").put("target", "person@example.com").put("status", "ACCEPTED")
                    .put("evidenceRef", "receipt-1").put("observedAt", Instant.EPOCH.toString());
            receipt.putObject("metadata");
            events.add(event(2, "core.tool.receipt", 1, "framework.core", receipt));
            outcome(List.of("receipt-1"), TaskOutcome.VERIFIED_COMPLETE);
        }

        void outcome(List<String> refs, TaskOutcome outcome) {
            events.removeIf(event -> event.type().equals("core.task.outcome"));
            events.add(event(3, "core.task.outcome", 3, "framework.core", JSON.valueToTree(
                    new TaskResult(outcome, List.of(), "", refs, List.of("发送通知")))));
        }

        TaskHarnessLifecycle lifecycle(boolean enabled) {
            var compiler = enabled ? new TaskContractCompiler(task -> {
                throw new AssertionError("重新核验不得调用模型或重做规划");
            }, JSON) : null;
            return new TaskHarnessLifecycle(runs, JSON, compiler);
        }

        RunEventEnvelope event(long sequence, String type, int version, String producer, JsonNode payload) {
            return new RunEventEnvelope(id.value(), sequence, Instant.EPOCH.plusMillis(sequence), type, version,
                    producer, null, null, payload);
        }
    }
}
