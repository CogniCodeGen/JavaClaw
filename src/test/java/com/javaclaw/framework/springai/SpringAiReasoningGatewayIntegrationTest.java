package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.builtin.BuiltinCapabilityExtension;
import com.javaclaw.framework.builtin.BuiltinExtensionCatalog;
import com.javaclaw.framework.builtin.memory.MemoryMutationGateway;
import com.javaclaw.framework.core.*;
import com.javaclaw.framework.extension.ExtensionArtifact;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.*;
import com.javaclaw.framework.store.JdbcAgentDefinitionStore;
import com.javaclaw.framework.store.JdbcExecutionPlanStore;
import com.javaclaw.framework.store.JdbcRunStore;
import com.javaclaw.desktop.agent.DesktopSessionTools;
import com.javaclaw.desktop.api.*;
import com.javaclaw.agent.vision.VisionPreprocessor;
import com.javaclaw.platform.data.SchemaInitializer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SpringAiReasoningGatewayIntegrationTest {

    @Test
    void pendingEffectControlSignalPausesWithoutRetryingProviderOrDispatchingTools() {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        ChatModel model = prompt -> {
            providerCalls.incrementAndGet();
            throw new PendingEffectObservationRequiredException("previous-run", "prior-click",
                    "desktop:window", EffectReceiptV1.Status.UNKNOWN, "MAYBE_SENT",
                    PendingEffectObservationRequiredException.Reason.DELIVERY_UNCERTAIN);
        };
        try (Fixture fixture = new Fixture(model, toolCallBudget(3), toolCalls)) {
            RunHandle handle = fixture.engine.start(fixture.request("continue"));
            var snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals("tool.effect_observation_required", snapshot.output().path("kind").asText());
            assertEquals("previous-run", snapshot.output().path("sourceRunId").asText());
            assertEquals("prior-click", snapshot.output().path("invocationId").asText());
            assertEquals("MAYBE_SENT", snapshot.output().path("delivery").asText());
            assertFalse(snapshot.output().path("dispatchAttempted").asBoolean(true));
            assertEquals(1, providerCalls.get());
            assertEquals(0, toolCalls.get());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .anyMatch(event -> event.type().equals("core.effect.observation_required")));
        }
    }

    @Test
    void continueIsADurableDecisionWithoutClaimingCompletion() throws Exception {
        ChatModel model = prompt -> namedToolCallResponse(HarnessDecisionToolCallback.NAME,
                "{\"decision\":\"CONTINUE\",\"userMessage\":\"work remains\","
                        + "\"unmetCriterionIds\":[]}", 2, 1);
        try (Fixture fixture = new Fixture(model, toolCallBudget(2), new AtomicInteger(),
                new FixtureConfig().harness())) {
            TaskContractV3 contract = new TaskContractV3(3, "work remains",
                    List.of(), false, true, "definition");
            RunHandle handle = fixture.engine.start(fixture.request("work remains",
                    Map.of(TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(contract))));
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals("CONTINUE", outcome.output().path("modelDecision").asText());
            assertEquals(TaskOutcome.UNVERIFIED,
                    fixture.engine.taskResult(handle.id()).orElseThrow().outcome());
            assertEquals(ModelDecisionV1.Decision.CONTINUE,
                    TaskResultEvaluator.latestModelDecision(
                            fixture.runs.eventsAfter(handle.id(), 0)).orElseThrow());
        }
    }

    @Test
    void missingDecisionGetsOneProtocolCorrectionThenPauses() {
        AtomicInteger providerCalls = new AtomicInteger();
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return rawTextResponse("完成\nharness_submit_decision "
                    + "{\"decision\":\"CLAIM_DONE\",\"userMessage\":\"done\"}", 2, 1);
        }, toolCallBudget(3), new AtomicInteger())) {
            RunHandle handle = fixture.engine.start(fixture.request("answer"));

            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(2, providerCalls.get());
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertEquals(1, events.stream().filter(event ->
                    event.type().equals("core.harness.protocol_repair_requested")).count());
            assertTrue(events.stream().anyMatch(event ->
                    event.type().equals("core.harness.protocol_violation")
                            && event.payload().path("code").asText().equals("PROTOCOL_ERROR")));
            assertTrue(events.stream().noneMatch(event ->
                    event.type().equals("core.harness.decision_submitted")));
        }
    }

    @Test
    void invalidDecisionArgumentsGetBoundedDurableRejectionsWithoutSubmission() {
        AtomicInteger providerCalls = new AtomicInteger();
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertEquals(List.of(HarnessDecisionToolCallback.NAME), allToolNames(prompt));
            return namedToolCallResponse(HarnessDecisionToolCallback.NAME,
                    "{\"decision\":\"FINISHED\",\"userMessage\":\"完成\","
                            + "\"unmetCriterionIds\":[]}", 2, 1);
        }, toolCallBudget(0), new AtomicInteger())) {
            RunHandle handle = fixture.engine.start(fixture.request("answer", Map.of(
                    "framework.disableTools", JsonNodeFactory.instance.booleanNode(true))));

            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(2, providerCalls.get());
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertEquals(1, events.stream().filter(event ->
                    event.type().equals("core.harness.protocol_repair_requested")).count());
            assertEquals(2, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText()
                                    .equals("harness.decision_invalid"))
                    .count());
            assertEquals("PROTOCOL_ERROR",
                    fixture.engine.get(handle.id()).output().path("code").asText());
            assertTrue(events.stream().noneMatch(event ->
                    event.type().equals("core.harness.decision_submitted")));
        }
    }

    @Test
    void inventedEvidenceReferenceGetsOneCorrectionWithoutSubmission() {
        AtomicInteger providerCalls = new AtomicInteger();
        ChatModel model = prompt -> {
            providerCalls.incrementAndGet();
            return namedToolCallResponse(HarnessDecisionToolCallback.NAME,
                    "{\"decision\":\"CLAIM_DONE\",\"userMessage\":\"done\","
                            + "\"evidenceRefs\":[\"invented-receipt\"],"
                            + "\"unmetCriterionIds\":[]}", 2, 1);
        };
        try (Fixture fixture = new Fixture(model, toolCallBudget(0), new AtomicInteger())) {
            RunHandle handle = fixture.engine.start(fixture.request("answer", Map.of(
                    "framework.disableTools", JsonNodeFactory.instance.booleanNode(true))));

            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals("PROTOCOL_ERROR",
                    fixture.engine.get(handle.id()).output().path("code").asText());
            assertEquals("UNKNOWN_EVIDENCE_REFERENCE", fixture.engine.get(handle.id())
                    .output().path("decisionErrorCode").asText());
            assertFalse(fixture.engine.get(handle.id()).output()
                    .path("decisionErrorDetail").asText().isBlank());
            assertEquals(2, providerCalls.get());
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertEquals(1, events.stream().filter(event -> event.type()
                    .equals("core.harness.protocol_repair_requested")).count());
            for (var event : events.stream().filter(event -> Set.of(
                    "core.harness.protocol_repair_requested", "core.harness.protocol_violation")
                    .contains(event.type())).toList()) {
                assertEquals("UNKNOWN_EVIDENCE_REFERENCE",
                        event.payload().path("decisionErrorCode").asText());
                assertFalse(event.payload().path("decisionErrorDetail").asText().isBlank());
            }
            assertEquals(2, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText()
                                    .equals("harness.decision_invalid"))
                    .count());
            assertTrue(events.stream().noneMatch(event -> event.type()
                    .equals("core.harness.decision_submitted")));
        }
    }

    @Test
    void blockedDesktopLaunchCanCorrectEvidenceAndRequestInputWithoutRetryingLaunch() {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger launchCalls = new AtomicInteger();
        FixtureConfig config = new FixtureConfig().harness()
                .simulatedDesktopAccessDisabled(launchCalls);
        ChatModel model = prompt -> switch (providerCalls.incrementAndGet()) {
            case 1 -> namedToolCallResponse("desktop_session_launch_application",
                    "{\"application\":\"QQ\"}", 2, 1);
            case 2 -> {
                var result = toolEnvelope(prompt);
                assertTrue(result.toString().contains("请先在设置中开启电脑应用访问"));
                assertTrue(result.path("evidenceRefs").isArray());
                assertEquals(List.of(), jsonStrings(result.path("evidenceRefs")));
                yield namedToolCallResponse(HarnessDecisionToolCallback.NAME,
                        "{\"decision\":\"NEEDS_INPUT\","
                                + "\"userMessage\":\"请开启电脑应用访问后继续查看联系人\","
                                + "\"evidenceRefs\":[\"desktop_session_launch_application(QQ): "
                                + "失败 - 请先在设置中开启电脑应用访问\"],"
                                + "\"unmetCriterionIds\":[\"view\"]}", 2, 1);
            }
            case 3 -> {
                var feedback = toolEnvelope(prompt);
                assertFalse(feedback.path("accepted").asBoolean(true));
                assertEquals("INVALID_DECISION_ARGUMENTS", feedback.path("errorCode").asText());
                assertEquals("UNKNOWN_EVIDENCE_REFERENCE", feedback.path("reasonCode").asText());
                assertFalse(feedback.path("message").asText().isBlank());
                assertTrue(feedback.path("availableEvidenceRefs").isArray());
                assertEquals(List.of(), jsonStrings(feedback.path("availableEvidenceRefs")));
                assertEquals(List.of("view"), jsonStrings(feedback.path("availableCriterionIds")));
                assertTrue(prompt.getInstructions().stream().anyMatch(TaskRepairContext::isRepair));
                assertTrue(TaskRepairContext.latest(prompt.getInstructions()).getText()
                        .contains("UNKNOWN_EVIDENCE_REFERENCE"));
                yield namedToolCallResponse(HarnessDecisionToolCallback.NAME,
                        "{\"decision\":\"NEEDS_INPUT\","
                                + "\"userMessage\":\"请开启电脑应用访问后继续查看联系人\","
                                + "\"evidenceRefs\":[],\"unmetCriterionIds\":[\"view\"]}", 2, 1);
            }
            default -> throw new AssertionError("A corrected input request must end this turn");
        };
        try (Fixture fixture = new Fixture(model, toolCallBudget(4), new AtomicInteger(), config)) {
            TaskContractV3 contract = new TaskContractV3(3, "打开QQ查看联系人", List.of(
                    new TaskCriterionV3("view", "观察QQ联系人", "desktop.observe",
                            CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "QQ",
                            EffectReceiptV1.Status.OBSERVED, "")), true, true, "definition");
            RunHandle handle = fixture.engine.start(fixture.request("打开QQ查看联系人",
                    Map.of(TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(contract))));

            var snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.WAITING_INPUT, snapshot.state(), snapshot.error());
            assertEquals("harness.needs_input", snapshot.output().path("kind").asText());
            assertTrue(snapshot.output().path("text").asText().contains("开启电脑应用访问"));
            assertEquals(3, providerCalls.get());
            assertEquals(1, launchCalls.get());
            assertEquals(0, config.simulatedObserveCalls.get());
            assertEquals(0, config.simulatedClickCalls.get());
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertEquals(1, events.stream().filter(event -> event.type()
                    .equals("core.harness.protocol_repair_requested")).count());
            assertEquals(1, events.stream().filter(event -> event.type()
                    .equals("core.harness.decision_submitted")).count());
            assertTrue(events.stream().noneMatch(event -> event.type()
                    .equals("core.harness.protocol_violation")));
            assertEquals(ModelDecisionV1.Decision.NEEDS_INPUT,
                    TaskResultEvaluator.latestModelDecision(events).orElseThrow());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ModelDecisionV1.Decision.class,
            names = {"NEEDS_INPUT", "BLOCKED", "CLAIM_DONE"})
    void trustedFileReadEvidenceIsVisibleAcceptedAndPreservedAcrossRestart(
            ModelDecisionV1.Decision decision) throws Exception {
        Path file = Files.createTempFile(Path.of("target"), "harness-evidence-", ".txt")
                .toAbsolutePath().normalize();
        Files.writeString(file, "The requested file was observed.");
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<Fixture> activeFixture = new AtomicReference<>();
        AtomicReference<List<String>> deliveredRefs = new AtomicReference<>();
        ChatModel model = prompt -> {
            int call = modelCalls.incrementAndGet();
            if (call == 1) {
                return namedToolCallResponse("sys_file_read", JsonNodeFactory.instance.objectNode()
                        .put("path", file.toString()).toString(), 2, 1);
            }
            assertTrue(call <= 3, "Only a user resume may require another provider decision");
            var response = prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream())
                    .filter(item -> item.name().equals("sys_file_read"))
                    .findFirst().orElseThrow();
            var envelope = toolEnvelope(response);
            assertEquals("SUCCEEDED", envelope.path("status").asText());
            assertTrue(envelope.path("evidenceRefs").isArray());
            List<String> refs = jsonStrings(envelope.path("evidenceRefs"));
            assertEquals(1, refs.size());
            Fixture fixture = activeFixture.get();
            var run = fixture.runs.nonTerminalRuns().getFirst();
            var receipts = fixture.runs.eventsAfter(run.snapshot().id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.receipt")
                            && event.producer().equals("framework.core")
                            && event.payload().path("tool").asText().equals("sys_file_read"))
                    .toList();
            assertEquals(1, receipts.size());
            assertEquals("OBSERVED", receipts.getFirst().payload().path("status").asText());
            assertEquals(file.toString(), receipts.getFirst().payload().path("target").asText());
            assertEquals(refs.getFirst(), receipts.getFirst().payload().path("evidenceRef").asText());
            if (call == 2) deliveredRefs.set(refs);
            else {
                assertEquals(deliveredRefs.get(), refs, "Recovery must retain the host evidence IDs");
                assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                        message instanceof UserMessage && message.getText().contains("确认文件已读取")));
            }
            var arguments = JsonNodeFactory.instance.objectNode()
                    .put("decision", call == 3 ? "CLAIM_DONE" : decision.name())
                    .put("userMessage", call == 2 && decision == ModelDecisionV1.Decision.NEEDS_INPUT
                            ? "文件已读取，请确认下一步" : "文件内容已读取");
            refs.forEach(arguments.putArray("evidenceRefs")::add);
            arguments.putArray("unmetCriterionIds");
            return namedToolCallResponse(HarnessDecisionToolCallback.NAME, arguments.toString(), 2, 1);
        };
        try (Fixture fixture = new Fixture(model, toolCallBudget(3), new AtomicInteger(),
                new FixtureConfig().harness().systemFileRead())) {
            activeFixture.set(fixture);
            var contract = new TaskContractV3(3, "读取文本文件", List.of(new TaskCriterionV3(
                    "read", "读取目标文件", "file.read", CapabilityMetadata.TargetKind.FILE,
                    file.toString(), EffectReceiptV1.Status.OBSERVED, "")), true, true, "definition");
            RunHandle handle = fixture.engine.start(fixture.request("读取文本文件", Map.of(
                    TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(contract))));
            assertEquals(decision == ModelDecisionV1.Decision.NEEDS_INPUT
                    ? RunState.WAITING_INPUT : RunState.COMPLETED,
                    fixture.engine.get(handle.id()).state(), fixture.engine.get(handle.id()).error());
            assertEquals(2, modelCalls.get());
            if (decision == ModelDecisionV1.Decision.NEEDS_INPUT) {
                fixture.restart();
                RunHandle resumed = fixture.engine.resume(handle.id(), new ResumeCommand(
                        "input", JsonNodeFactory.instance.objectNode().put("text", "确认文件已读取")));
                RunOutcome outcome = awaitCompletion(fixture, resumed);
                assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
                assertEquals(3, modelCalls.get());
            }
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertTrue(events.stream().noneMatch(event -> Set.of(
                    "core.harness.protocol_repair_requested", "core.harness.protocol_violation")
                    .contains(event.type())));
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.TOOL
                            && step.input().path("tool").asText().equals("sys_file_read"))
                    .count(), "A resume must replay the read result instead of executing it again");
            if (decision != ModelDecisionV1.Decision.BLOCKED) {
                assertEquals(TaskOutcome.VERIFIED_COMPLETE,
                        fixture.engine.taskResult(handle.id()).orElseThrow().outcome());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void protocolRepairGuidanceKeepsCompleteIdsAndParsableJsonWithinItsBound() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ObjectNode rejection = json.createObjectNode().put("accepted", false)
                .put("errorCode", "INVALID_DECISION_ARGUMENTS")
                .put("message", "Use an exact ID supplied by the host.");
        List<String> criterionIds = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            String prefix = "condition-%02d-".formatted(index);
            criterionIds.add(prefix + "c".repeat(128 - prefix.length()));
        }
        List<String> evidenceRefs = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            String prefix = "receipt-%d-".formatted(index);
            evidenceRefs.add(prefix + "r".repeat(256 - prefix.length()));
        }
        criterionIds.forEach(rejection.putArray("availableCriterionIds")::add);
        evidenceRefs.forEach(rejection.putArray("availableEvidenceRefs")::add);

        for (String reason : List.of("UNKNOWN_CRITERION_ID", "UNKNOWN_EVIDENCE_REFERENCE")) {
            rejection.put("reasonCode", reason);
            String feedback = SpringAiReasoningGateway.protocolRepairFeedback(rejection);
            assertTrue(feedback.length() < 2000, "Repair feedback must fit the durable context bound");
            int objectStart = feedback.indexOf('{');
            assertTrue(objectStart >= 0, "Repair guidance must include a JSON feedback object");
            com.fasterxml.jackson.databind.JsonNode parsed = json.reader().with(
                    com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(feedback.substring(objectStart));
            assertEquals(reason, parsed.path("reasonCode").asText());
            assertEquals("INVALID_DECISION_ARGUMENTS", parsed.path("errorCode").asText());
            List<String> includedCriteria = jsonStrings(parsed.path("availableCriterionIds"));
            List<String> includedRefs = jsonStrings(parsed.path("availableEvidenceRefs"));
            assertTrue(criterionIds.containsAll(includedCriteria), "Criterion IDs must never be truncated");
            assertTrue(evidenceRefs.containsAll(includedRefs), "Evidence refs must never be truncated");
            if (reason.equals("UNKNOWN_CRITERION_ID")) assertFalse(includedCriteria.isEmpty());
            else assertFalse(includedRefs.isEmpty());
        }
    }

    @Test
    void mixedDecisionAndBusinessToolBatchExecutesNeither() {
        AtomicInteger businessCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return response(
                AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("business", "function", "test_mutate",
                                "{\"value\":1}"),
                        new AssistantMessage.ToolCall("decision", "function",
                                HarnessDecisionToolCallback.NAME,
                                "{\"decision\":\"CLAIM_DONE\",\"userMessage\":\"done\","
                                        + "\"unmetCriterionIds\":[]}")))
                        .build(), 2, 1);
        }, toolCallBudget(3), businessCalls)) {
            RunHandle handle = fixture.engine.start(fixture.request("mutate"));

            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals("PROTOCOL_ERROR",
                    fixture.engine.get(handle.id()).output().path("code").asText());
            assertEquals(2, providerCalls.get());
            assertEquals(0, businessCalls.get());
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertEquals(1, events.stream().filter(event -> event.type()
                    .equals("core.harness.protocol_repair_requested")).count());
            assertTrue(events.stream().noneMatch(event ->
                    event.type().equals("core.harness.decision_submitted")));
        }
    }

    @Test
    void correctedMixedDecisionCompletesWithoutRunningRejectedBusinessCall() throws Exception {
        AtomicInteger businessCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        ChatModel model = prompt -> {
            if (providerCalls.incrementAndGet() == 1) {
                return response(AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("business", "function", "test_mutate",
                                "{\"value\":1}"),
                        new AssistantMessage.ToolCall("decision", "function",
                                HarnessDecisionToolCallback.NAME,
                                "{\"decision\":\"CLAIM_DONE\",\"userMessage\":\"done\","
                                        + "\"unmetCriterionIds\":[]}"))).build(), 2, 1);
            }
            assertTrue(prompt.getInstructions().stream().anyMatch(
                    TaskRepairContext::isRepair));
            return textResponse("done", 2, 1);
        };
        try (Fixture fixture = new Fixture(model, toolCallBudget(2), businessCalls)) {
            RunHandle handle = fixture.engine.start(fixture.request("answer"));
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, providerCalls.get());
            assertEquals(0, businessCalls.get());
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertEquals(1, events.stream().filter(event -> event.type()
                    .equals("core.harness.protocol_repair_requested")).count());
            assertEquals(1, events.stream().filter(event -> event.type()
                    .equals("core.harness.decision_submitted")).count());
        }
    }

    @Test
    void multiGenerationControlResponsePausesWithoutAttemptingUnjournaledRepair() {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger businessCalls = new AtomicInteger();
        ChatModel model = prompt -> {
            providerCalls.incrementAndGet();
            AssistantMessage control = AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("decision", "function",
                            HarnessDecisionToolCallback.NAME,
                            "{\"decision\":\"CLAIM_DONE\",\"userMessage\":\"done\","
                                    + "\"unmetCriterionIds\":[]}"))).build();
            AssistantMessage business = AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("business", "function",
                            "test_mutate", "{\"value\":1}"))).build();
            return new ChatResponse(List.of(new Generation(control), new Generation(business)),
                    ChatResponseMetadata.builder().model("unknown-test-model")
                            .usage(new DefaultUsage(2, 1)).build());
        };
        try (Fixture fixture = new Fixture(model, toolCallBudget(2), businessCalls)) {
            RunHandle handle = fixture.engine.start(fixture.request("answer"));

            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals("PROTOCOL_ERROR",
                    fixture.engine.get(handle.id()).output().path("code").asText());
            assertEquals(1, providerCalls.get());
            assertEquals(0, businessCalls.get());
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertTrue(events.stream().noneMatch(event -> event.type().equals(
                    "core.harness.protocol_repair_requested")));
            assertTrue(events.stream().noneMatch(event -> event.type().equals(
                    "core.harness.decision_submitted")));
        }
    }

    @Test
    void duplicateDecisionCallsGetOneCorrectionWithoutExecutingEither() {
        AtomicInteger providerCalls = new AtomicInteger();
        ChatModel model = prompt -> {
            providerCalls.incrementAndGet();
            return response(AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("decision-a", "function",
                            HarnessDecisionToolCallback.NAME,
                            "{\"decision\":\"CLAIM_DONE\",\"userMessage\":\"done\","
                                    + "\"unmetCriterionIds\":[]}"),
                    new AssistantMessage.ToolCall("decision-b", "function",
                            HarnessDecisionToolCallback.NAME,
                            "{\"decision\":\"BLOCKED\",\"userMessage\":\"blocked\","
                                    + "\"unmetCriterionIds\":[]}"))).build(), 2, 1);
        };
        try (Fixture fixture = new Fixture(model, toolCallBudget(1), new AtomicInteger())) {
            RunHandle handle = fixture.engine.start(fixture.request("answer"));

            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals("PROTOCOL_ERROR",
                    fixture.engine.get(handle.id()).output().path("code").asText());
            assertEquals(2, providerCalls.get());
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertEquals(1, events.stream().filter(event -> event.type()
                    .equals("core.harness.protocol_repair_requested")).count());
            assertTrue(events.stream().noneMatch(event -> event.type()
                    .equals("core.harness.decision_submitted")));
        }
    }

    @Test
    void 回复前验收使用已结束子Run的可信收据() throws Exception {
        AtomicReference<Fixture> fixtureRef = new AtomicReference<>();
        AtomicInteger providerCalls = new AtomicInteger();
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            Fixture current = fixtureRef.get();
            var parent = current.runs.nonTerminalRuns().stream()
                    .filter(run -> run.request().source().kind().equals("chat"))
                    .findFirst().orElseThrow();
            RunId childId = RunId.random();
            RunRequest child = RunRequest.builder()
                    .agent(AgentDefinitionRef.latest("test.agent"))
                    .profile(RunProfileRef.latest("test.profile"))
                    .source(InvocationSource.subAgent(parent.snapshot().id().value()))
                    .scope(new RunScope("workspace", "user", "child-session"))
                    .input(InputBlock.text("write child result"))
                    .linkage(new RunLinkage(parent.snapshot().id(), null, null))
                    .permissionCeiling(PermissionSet.UNRESTRICTED).build();
            current.runs.create(childId, child, parent.snapshot().executionPlanId(),
                    new RunEventDraft("core.run.created", 1, "framework.core", null, null,
                            JsonNodeFactory.instance.objectNode()));
            current.runs.append(childId, Set.of(RunState.CREATED), RunState.RUNNING,
                    new RunEventDraft("core.run.started", 1, "framework.core", null, null,
                            JsonNodeFactory.instance.objectNode()), null, null);
            current.runs.append(childId, Set.of(RunState.RUNNING), RunState.RUNNING,
                    new RunEventDraft("core.tool.receipt", 1, "framework.core", null, null,
                            current.json.createObjectNode().put("invocationId", "child-write")
                                    .put("tool", "sys_file_write").put("operation", "write")
                                    .put("target", com.javaclaw.util.ProjectAccessPolicy.projectRoot()
                                            .resolve("child.txt").toString()).put("status", "VERIFIED")
                                    .put("observedAt", "2026-01-01T00:00:00Z")
                                    .put("evidenceRef", "file:child.txt")), null, null);
            current.runs.append(childId, Set.of(RunState.RUNNING), RunState.COMPLETED,
                    new RunEventDraft("core.run.completed", 1, "framework.core", null, null,
                            JsonNodeFactory.instance.objectNode()), null, null);
            return textResponse("子任务已完成", 2, 1);
        }, toolCallBudget(2), new AtomicInteger(), new FixtureConfig().harness())) {
            fixtureRef.set(fixture);
            TaskContractV3 contract = new TaskContractV3(3, "write child result",
                    List.of(new TaskCriterionV3("write", "child result exists", "file.write", CapabilityMetadata.TargetKind.FILE,
                            "child.txt", EffectReceiptV1.Status.VERIFIED, "")),
                    true, true, "definition");
            RunHandle handle = fixture.engine.start(fixture.request("write child result",
                    Map.of(TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(contract))));
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, providerCalls.get());
            assertEquals("子任务已完成", outcome.output().path("text").asText());
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertEquals(TaskOutcome.VERIFIED_COMPLETE,
                    TaskResultEvaluator.latestOutcome(events, fixture.json).orElseThrow().outcome());
            assertTrue(events.stream().anyMatch(event -> event.type().equals("core.task.review")
                    && event.payload().path("outcome").asText().equals("VERIFIED_COMPLETE")));
        }
    }

    @Test
    void 任务验收反馈在同一Run补做且无新证据后如实停止() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        FixtureConfig config = new FixtureConfig().harness();
        try (Fixture fixture = new Fixture(prompt -> {
            delivered.add(prompt);
            providerCalls.incrementAndGet();
            return textResponse("已经查看了目标内容", 2, 1);
        }, toolCallBudget(3), new AtomicInteger(), config)) {
            TaskContractV3 contract = new TaskContractV3(3, "查看目标内容",
                    List.of(new TaskCriterionV3("observe", "观察目标内容", "desktop.observe", CapabilityMetadata.TargetKind.DESKTOP_APPLICATION,
                            "target", EffectReceiptV1.Status.OBSERVED, "")),
                    true, true, "definition");
            ObjectNode value = fixture.json.valueToTree(contract);
            RunHandle handle = fixture.engine.start(fixture.request("查看目标内容",
                    Map.of(TaskContractCompiler.ATTRIBUTE, value)));
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state(), snapshot.error());
            assertEquals(2, providerCalls.get());
            assertTrue(delivered.get(1).getInstructions().stream()
                    .anyMatch(message -> message instanceof UserMessage user
                            && user.getText().contains("任务验收尚未通过")));
            assertTrue(snapshot.output().path("text").asText().contains("尚未验证完成"));
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertEquals(1, events.stream()
                    .filter(event -> event.type().equals("core.task.repair_requested")).count());
            assertEquals(1, events.stream()
                    .filter(event -> event.type().equals("core.task.outcome")
                            && event.payload().path("outcome").asText().equals("UNVERIFIED")).count());
            assertTrue(events.stream().anyMatch(event -> event.type().equals("core.task.stop")
                    && event.payload().path("reasonCode").asText().equals("NO_PROGRESS")));
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void repeatedDesktopFrameAndUndispatchedClickDoNotTriggerAnotherRepair() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger observeCalls = new AtomicInteger();
        AtomicInteger clickCalls = new AtomicInteger();
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1, 3 -> namedToolCallResponse("desktop_session_observe",
                    "{\"sessionId\":\"sample-session\"}", 2, 1);
            case 2 -> textResponse("已经查看设置页面", 2, 1);
            case 4 -> namedToolCallResponse("desktop_session_click",
                    "{\"sessionId\":\"sample-session\",\"observationId\":"
                            + "\"00000000-0000-4000-8000-000000000002\","
                            + "\"elementId\":\"missing-target\"}", 2, 1);
            case 5 -> textResponse("无法确认是否进入设置页面", 2, 1);
            default -> throw new AssertionError("same frame and NOT_SENT click must not cause"
                    + " another repair call");
        };
        FixtureConfig config = new FixtureConfig()
                .simulatedDesktopSession(observeCalls, clickCalls).harness();
        try (Fixture fixture = new Fixture(model, toolCallBudget(5),
                new AtomicInteger(), config)) {
            TaskContractV3 contract = new TaskContractV3(3, "查看 示例应用 设置页面",
                    List.of(new TaskCriterionV3("observe-settings", "观察设置页面",
                            "desktop.observe", CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "示例应用", EffectReceiptV1.Status.OBSERVED,
                            "settings")), true, true, "definition");
            RunHandle handle = fixture.engine.start(fixture.request("查看 示例应用 设置页面",
                    Map.of(TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(contract))));
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state(), snapshot.error());
            assertEquals(5, modelCalls.get());
            assertEquals(2, observeCalls.get());
            assertEquals(0, clickCalls.get(), "invalid target cannot dispatch input");
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertTrue(events.stream().anyMatch(event -> event.type().equals("core.tool.receipt")
                    && event.payload().path("tool").asText().equals("desktop_session_click")
                    && event.payload().path("status").asText().equals("FAILED")
                    && event.payload().path("metadata").path("delivery").asText()
                            .equals("NOT_SENT")));
            assertEquals(1, events.stream().filter(event ->
                    event.type().equals("core.task.repair_requested")).count());
            assertTrue(events.stream().anyMatch(event -> event.type().equals("core.task.stop")
                    && event.payload().path("reasonCode").asText().equals("NO_PROGRESS")));
            assertEquals(TaskOutcome.UNVERIFIED,
                    fixture.engine.taskResult(handle.id()).orElseThrow().outcome());
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void dispatchedActionAfterRepairAllowsASecondRepairForItsResultingView() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger observeCalls = new AtomicInteger();
        AtomicInteger clickCalls = new AtomicInteger();
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1, 5 -> namedToolCallResponse("desktop_session_observe",
                    "{\"sessionId\":\"sample-session\"}", 2, 1);
            case 2, 4 -> textResponse("尚未取得设置页面证据", 2, 1);
            case 3 -> namedToolCallResponse("desktop_session_click",
                    "{\"sessionId\":\"sample-session\",\"observationId\":"
                            + "\"00000000-0000-4000-8000-000000000001\","
                            + "\"elementId\":\"00000000-0000-4000-8000-000000000001:v7\"}",
                    2, 1);
            case 6 -> textResponse("已查看设置页面", 2, 1);
            default -> throw new AssertionError("unexpected repair call");
        };
        FixtureConfig config = new FixtureConfig()
                .simulatedDesktopSession(observeCalls, clickCalls).harness();
        try (Fixture fixture = new Fixture(model, toolCallBudget(5),
                new AtomicInteger(), config)) {
            TaskContractV3 contract = new TaskContractV3(3, "查看 示例应用 设置页面",
                    List.of(new TaskCriterionV3("observe-settings", "观察设置页面",
                            "desktop.observe", CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "示例应用", EffectReceiptV1.Status.OBSERVED,
                            "settings")), true, true, "definition");
            RunHandle handle = fixture.engine.start(fixture.request("查看 示例应用 设置页面",
                    Map.of(TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(contract))));
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(6, modelCalls.get());
            assertEquals(2, observeCalls.get());
            assertEquals(1, clickCalls.get());
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertEquals(2, events.stream().filter(event ->
                    event.type().equals("core.task.repair_requested")).count());
            assertEquals(TaskOutcome.VERIFIED_COMPLETE,
                    fixture.engine.taskResult(handle.id()).orElseThrow().outcome());
        }
    }

    @Test
    void repairStopsBeforeAnotherModelCallWhenInputBudgetCannotFitIt() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel model = prompt -> {
            modelCalls.incrementAndGet();
            return textResponse("目标已经达到", 2, 1);
        };
        RunBudget budget = new RunBudget(Duration.ofMinutes(5), 500, 1_000_000,
                5, new BigDecimal("1000"));
        try (Fixture fixture = new Fixture(model, budget, new AtomicInteger(),
                new FixtureConfig().harness())) {
            TaskContractV3 contract = new TaskContractV3(3, "查看目标内容",
                    List.of(new TaskCriterionV3("observe", "观察目标内容", "desktop.observe", CapabilityMetadata.TargetKind.DESKTOP_APPLICATION,
                            "target", EffectReceiptV1.Status.OBSERVED, "")),
                    true, true, "definition");
            RunHandle handle = fixture.engine.start(fixture.request("查看目标内容",
                    Map.of(TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(contract))));
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state(), snapshot.error());
            assertEquals(1, modelCalls.get());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .anyMatch(event -> event.type().equals("core.task.stop")
                            && event.payload().path("reasonCode").asText()
                                    .equals("BUDGET_EXHAUSTED")));
        }
    }

    @Test
    void applicableContractPausesWhenProviderExhaustsBudgetBeforeDecision() {
        AtomicInteger providerCalls = new AtomicInteger();
        ChatModel model = prompt -> {
            providerCalls.incrementAndGet();
            throw BudgetExceededException.modelInputTokens(2, 1);
        };
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED,
                new AtomicInteger(), new FixtureConfig().harness())) {
            TaskContractV3 contract = new TaskContractV3(3, "observe target",
                    List.of(new TaskCriterionV3("observe", "observe target", "desktop.observe", CapabilityMetadata.TargetKind.DESKTOP_APPLICATION,
                            "target", EffectReceiptV1.Status.OBSERVED, "")),
                    true, true, "definition");
            RunHandle handle = fixture.engine.start(fixture.request("observe target",
                    Map.of(TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(contract))));

            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(1, providerCalls.get());
            assertEquals(TaskOutcome.UNVERIFIED,
                    fixture.engine.taskResult(handle.id()).orElseThrow().outcome());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.harness.decision_submitted")));
        }
    }

    @Test
    void 固定Persona首次经网关读取且重启后每步复用同一用户级快照() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicReference<String> current = new AtomicReference<>("PERSONA_VERSION_ONE");
        List<Prompt> delivered = new ArrayList<>();
        FixedContextSource persona = fixedPersona(reads, current);
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, providerCalls.get() == 0 ? "test_mutate" : "",
                        providerCalls.get() == 0 ? "test" : "");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "test_mutate");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).fixedSource(persona).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            delivered.add(prompt);
            int call = providerCalls.incrementAndGet();
            List<UserMessage> snapshots = prompt.getInstructions().stream()
                    .filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                    .filter(FixedContextSession::isFixed).toList();
            assertEquals(1, snapshots.size());
            assertTrue(snapshots.getFirst().getText().contains("PERSONA_VERSION_ONE"));
            assertFalse(snapshots.getFirst().getText().contains("PERSONA_VERSION_TWO"));
            assertEquals("memory.persona", snapshots.getFirst().getMetadata()
                    .get(FixedContextSession.SOURCE_METADATA));
            return call == 1 ? toolCallResponse(2, 1) : textResponse("done", 2, 1);
        }, toolCallBudget(2), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("use my preference")),
                    Map.of(), PermissionSet.of("tool.read", "tool.execute"));
            RunHandle handle = fixture.engine.start(request);
            assertEquals(RunState.WAITING_APPROVAL, fixture.engine.get(handle.id()).state());
            assertEquals(1, reads.get());
            current.set("PERSONA_VERSION_TWO");
            fixture.restart();
            var approval = JsonNodeFactory.instance.objectNode().put("approved", true)
                    .put("fingerprint", ToolInvocationFingerprint.create("test_mutate",
                            JsonNodeFactory.instance.objectNode().put("value", 1)));
            RunHandle resumed = fixture.engine.resume(handle.id(),
                    new ResumeCommand("tool.approval", approval));
            RunOutcome outcome = awaitCompletion(fixture, resumed);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, providerCalls.get());
            assertEquals(1, reads.get());
            assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.started")).count());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 固定Persona无授权时跳过且共享额度不足时暂停() throws Exception {
        for (boolean unauthorized : List.of(false, true)) {
            AtomicInteger reads = new AtomicInteger();
            AtomicInteger plannerCalls = new AtomicInteger();
            AtomicInteger providerCalls = new AtomicInteger();
            ModelTaskGateway planner = request -> {
                plannerCalls.incrementAndGet();
                ObjectNode selection = JsonNodeFactory.instance.objectNode();
                selection.putArray("historyIds");
                if (request.purpose().endsWith(".select_v2")) {
                    selection.putArray("searches");
                    toolIntent(selection, unauthorized ? "" : "test_mutate",
                            unauthorized ? "" : "test");
                } else {
                    selection.putArray("sourceIds");
                    toolChoice(selection, request, "direct", "test_mutate");
                }
                return CompletableFuture.completedFuture(new ModelTaskResult(
                        selection, "planner", 1, 1, false, Map.of()));
            };
            FixtureConfig config = new FixtureConfig()
                    .context(contextConfiguration(true, 48_000, 16_000, 1))
                    .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                    .planner(planner).fixedSource(fixedPersona(reads,
                            new AtomicReference<>("PERSONA_BODY"))).autoApproveContext();
            try (Fixture fixture = new Fixture(prompt -> {
                providerCalls.incrementAndGet();
                return textResponse("unexpected", 1, 1);
            }, toolCallBudget(1), new AtomicInteger(), config)) {
                PermissionSet ceiling = unauthorized ? PermissionSet.of("tool.execute")
                        : PermissionSet.of("tool.read", "tool.execute");
                RunRequest request = fixture.request(List.of(InputBlock.text("use my preference")),
                        Map.of(), ceiling);
                RunHandle handle = fixture.engine.start(request);
                RunSnapshot snapshot = fixture.engine.get(handle.id());

                if (unauthorized) {
                    assertEquals(RunState.COMPLETED, snapshot.state(), snapshot.error());
                } else {
                    assertEquals(RunState.PAUSED, snapshot.state());
                    assertEquals("context.planning_required", snapshot.output().path("kind").asText());
                    assertTrue(snapshot.output().path("reason").asText().contains("budget"));
                }
                assertEquals(0, reads.get());
                assertEquals(unauthorized ? 1 : 0, providerCalls.get());
                assertEquals(1, plannerCalls.get());
            }
        }
    }

    @Test
    void 无工具且零额度的Run跳过固定Persona并完成模型调用() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger plannerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            assertTrue(request.purpose().endsWith(".select_v2"));
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches");
            selection.putArray("historyIds");
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).fixedSource(fixedPersona(reads,
                        new AtomicReference<>("PERSONA_BODY")));
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertTrue(prompt.getInstructions().stream()
                    .noneMatch(message -> message instanceof UserMessage user
                            && FixedContextSession.isFixed(user)));
            return textResponse("done", 1, 1);
        }, toolCallBudget(0), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request(
                    List.of(InputBlock.text("tool-free request")),
                    Map.of("framework.disableTools", JsonNodeFactory.instance.booleanNode(true)),
                    PermissionSet.NONE));
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(0, reads.get());
            assertEquals(0, plannerCalls.get(),
                    "zero business budget bypasses context and tool planning");
            assertEquals(1, providerCalls.get());
        }
    }

    private static FixedContextSource fixedPersona(AtomicInteger reads,
            AtomicReference<String> current) {
        return new FixedContextSource() {
            @Override public String id() { return "memory.persona"; }
            @Override public String group() { return "memory"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("tool.read");
            }
            @Override public FixedContextSnapshot read(RunRequest request) {
                reads.incrementAndGet();
                String body = current.get();
                return new FixedContextSnapshot(sha256(body), body);
            }
        };
    }

    @Test
    void 最终模型检查点在固定上下文耗尽工具额度后仍可恢复() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicBoolean pauseOnce = new AtomicBoolean();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches");
            selection.putArray("historyIds");
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner)
                .fixedSource(fixedPersona(reads, new AtomicReference<>("PERSONA_BODY")))
                .autoApproveContext().fillerTools(2)
                .outputGuard((output, request, runId) -> {
                    if (pauseOnce.compareAndSet(false, true)) {
                        throw new ToolInputRequiredException(
                                JsonNodeFactory.instance.objectNode().put("kind", "test.pause"),
                                "pause after final model checkpoint");
                    }
                    return output;
                });
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertEquals(List.of(), toolNames(prompt));
            return textResponse("done", 2, 1);
        }, toolCallBudget(1), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("use my preference"));
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            assertEquals(1, reads.get());
            assertEquals(1, providerCalls.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.TOOL).count());

            fixture.restart();
            RunHandle resumed = fixture.engine.resume(handle.id(),
                    new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));
            RunOutcome outcome = awaitCompletion(fixture, resumed);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals("done", outcome.output().path("text").asText());
            assertEquals(1, reads.get());
            assertEquals(1, providerCalls.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL).count());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.model.started")).count());
        }
    }

    @Test
    void 工作区技能与知识正文保留不同用途且均为用户级消息() throws Exception {
        DeferredContextSource skill = contextSource("skills", "SKILL_WORKFLOW_BODY",
                DeferredContextUse.USER_WORKFLOW);
        DeferredContextSource knowledge = contextSource("knowledge", "KNOWLEDGE_EVIDENCE_BODY",
                DeferredContextUse.REFERENCE);
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches").addObject().put("source", "skills")
                        .put("query", "workflow");
                selection.withArray("searches").addObject().put("source", "knowledge")
                        .put("query", "evidence");
                toolIntent(selection, "");
            } else {
                selection.putArray("sourceIds").add("skills:one").add("knowledge:one");
                toolChoice(selection, request, "none");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        List<Prompt> delivered = new ArrayList<>();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(skill).source(knowledge).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            delivered.add(prompt);
            assertTrue(prompt.getInstructions().stream()
                    .filter(org.springframework.ai.chat.messages.SystemMessage.class::isInstance)
                    .noneMatch(message -> message.getText().contains("SKILL_WORKFLOW_BODY")
                            || message.getText().contains("KNOWLEDGE_EVIDENCE_BODY")));
            Map<String, UserMessage> selected = prompt.getInstructions().stream()
                    .filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                    .filter(message -> Boolean.TRUE.equals(message.getMetadata().get(
                            OnDemandContextSession.CONTEXT_METADATA)))
                    .collect(java.util.stream.Collectors.toMap(message -> message.getText().contains(
                            "SKILL_WORKFLOW_BODY") ? "skill" : "knowledge", message -> message));
            assertEquals("USER_WORKFLOW", selected.get("skill").getMetadata().get(
                    OnDemandContextSession.CONTEXT_USE_METADATA));
            assertTrue(selected.get("skill").getText().contains("User-configured workspace skill"));
            assertEquals("REFERENCE", selected.get("knowledge").getMetadata().get(
                    OnDemandContextSession.CONTEXT_USE_METADATA));
            assertTrue(selected.get("knowledge").getText().contains("Untrusted reference material"));
            return textResponse("done", 2, 1);
        }, toolCallBudget(4), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("use workflow evidence")),
                    Map.of(), PermissionSet.of("context.read"));
            RunHandle handle = fixture.engine.start(request);
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    private static DeferredContextSource contextSource(String id, String body,
            DeferredContextUse use) {
        return new DeferredContextSource() {
            @Override public String id() { return id; }
            @Override public String description() { return id; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                return List.of(new DeferredContextCandidate("one", "v1", id + " summary",
                        PermissionSet.NONE, use));
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                return body;
            }
        };
    }

    @Test
    void 超长记忆正文只截取模型上下文且持久读取保留完整证据() throws Exception {
        String body = "Prior conversation evidence turn-1\nUser: 上海亲子游\nAssistant: 先查开放时间"
                + "\nTool evidence: " + "x".repeat(20_000) + "TRACE_END";
        String version = sha256(body);
        DeferredContextSource memory = new DeferredContextSource() {
            @Override public String id() { return "memory"; }
            @Override public String description() { return "Prior conversations"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                return List.of(new DeferredContextCandidate(
                        "one", version, "上海亲子游", PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String id, String selectedVersion) {
                assertEquals("one", id);
                assertEquals(version, selectedVersion);
                return body;
            }
        };
        ModelTaskGateway planner = selectContext("memory");
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode()
                        .put("enabled", true).put("selectedBodyChars", 12_000))
                .planner(planner).source(memory).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            List<UserMessage> selected = deferredMessages(prompt);
            assertEquals(1, selected.size());
            String excerpt = selected.getFirst().getText();
            assertTrue(excerpt.contains("User: 上海亲子游"));
            assertTrue(excerpt.contains("[Reference content truncated; middle omitted]"));
            assertTrue(excerpt.contains("TRACE_END"));
            assertFalse(excerpt.contains(body));
            assertTrue(excerpt.substring(excerpt.indexOf('\n') + 1).length() <= 12_000);
            return textResponse("done", 2, 1);
        }, toolCallBudget(2), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request(
                    List.of(InputBlock.text("上海亲子游")), Map.of(), PermissionSet.of("context.read")));
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            AgentStep read = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.TOOL
                            && step.input().path("tool").asText().startsWith("framework_context_fetch_"))
                    .findFirst().orElseThrow();
            assertEquals(body, read.output().path("rawOutput").path("body").asText());
            assertEquals(body, read.output().path("modelOutput").path("body").asText());
        }
    }

    @Test
    void 多项参考正文共享低预算而工作流超限仍暂停() throws Exception {
        String first = "MEMORY_START" + "a".repeat(1_600) + "MEMORY_END";
        String second = "KNOWLEDGE_START" + "b".repeat(1_600) + "KNOWLEDGE_END";
        FixtureConfig references = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode()
                        .put("enabled", true).put("selectedBodyChars", 1_000))
                .planner(selectContext("memory", "knowledge"))
                .source(contextSource("memory", first, DeferredContextUse.REFERENCE))
                .source(contextSource("knowledge", second, DeferredContextUse.REFERENCE))
                .autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            List<UserMessage> selected = deferredMessages(prompt);
            assertEquals(2, selected.size());
            assertTrue(selected.stream().allMatch(message -> message.getText().contains(
                    "[Reference content truncated; middle omitted]")));
            assertTrue(selected.stream().anyMatch(message -> message.getText().contains("MEMORY_START")));
            assertTrue(selected.stream().anyMatch(message -> message.getText().contains("KNOWLEDGE_START")));
            int selectedChars = selected.stream().mapToInt(message ->
                    message.getText().substring(message.getText().indexOf('\n') + 1).length()).sum();
            assertTrue(selectedChars <= 1_000);
            return textResponse("done", 2, 1);
        }, toolCallBudget(4), new AtomicInteger(), references)) {
            RunOutcome outcome = fixture.engine.start(fixture.request(
                    List.of(InputBlock.text("use memory and knowledge")), Map.of(),
                    PermissionSet.of("context.read"))).completion().toCompletableFuture()
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
        }

        AtomicInteger providerCalls = new AtomicInteger();
        String workflow = "WORKFLOW_START" + "w".repeat(1_600) + "WORKFLOW_END";
        FixtureConfig instructions = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode()
                        .put("enabled", true).put("selectedBodyChars", 1_000))
                .planner(selectContext("skills"))
                .source(contextSource("skills", workflow, DeferredContextUse.USER_WORKFLOW))
                .autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 2, 1);
        }, toolCallBudget(2), new AtomicInteger(), instructions)) {
            RunHandle handle = fixture.engine.start(fixture.request(
                    List.of(InputBlock.text("use workflow")), Map.of(),
                    PermissionSet.of("context.read")));
            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.PAUSED, snapshot.state(), snapshot.error());
            assertEquals("context.planning_required", snapshot.output().path("kind").asText());
            assertTrue(snapshot.output().path("reason").asText().contains("workflow"));
            assertEquals(0, providerCalls.get());
        }
    }

    private static ModelTaskGateway selectContext(String... sources) {
        return request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                var searches = selection.putArray("searches");
                for (String source : sources) {
                    searches.addObject().put("source", source).put("query", source);
                }
                toolIntent(selection, "");
            } else {
                var sourceIds = selection.putArray("sourceIds");
                for (String source : sources) sourceIds.add(source + ":one");
                toolChoice(selection, request, "none");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
    }

    private static List<UserMessage> deferredMessages(Prompt prompt) {
        return prompt.getInstructions().stream().filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .filter(message -> Boolean.TRUE.equals(message.getMetadata().get(
                        OnDemandContextSession.CONTEXT_METADATA))).toList();
    }

    @Test
    void 输入恢复后上下文读取审批不丢失最新用户补充() throws Exception {
        AtomicInteger resumedSelections = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                boolean resumed = request.input().has("latestUserInput");
                if (resumed) {
                    assertTrue(request.input().path("latestUserInput").asText().contains("上海明天"));
                    resumedSelections.incrementAndGet();
                    selection.putArray("searches").addObject()
                            .put("source", "docs").put("query", "family trip");
                    toolIntent(selection, "");
                } else {
                    selection.putArray("searches");
                    toolIntent(selection, "code_target", "code");
                }
            } else {
                var sourceIds = selection.putArray("sourceIds");
                if (request.input().path("candidates").toString().contains("docs:one")) {
                    sourceIds.add("docs:one");
                    toolChoice(selection, request, "none");
                } else {
                    toolChoice(selection, request, "direct", "code_target");
                }
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner)
                .source(contextSource("docs", "family trip evidence", DeferredContextUse.REFERENCE))
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_ACTIVATION_ONCE);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertTrue(prompt.getInstructions().stream()
                    .filter(UserMessage.class::isInstance)
                    .anyMatch(message -> message.getText().contains("上海明天")));
            return textResponse("done", 2, 1);
        }, toolCallBudget(4), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request(
                    List.of(InputBlock.text("准备出游")), Map.of(),
                    PermissionSet.of("context.read", "tool.execute")));
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state(),
                    fixture.engine.get(handle.id()).error());
            assertEquals(0, providerCalls.get());

            handle = fixture.engine.resume(handle.id(), new ResumeCommand("input",
                    JsonNodeFactory.instance.objectNode().put("text", "上海明天")));
            assertEquals(RunState.WAITING_APPROVAL, fixture.engine.get(handle.id()).state());
            assertEquals(1, resumedSelections.get());
            var contextApproval = fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.run.waiting_approval"))
                    .reduce((previous, next) -> next).orElseThrow();
            assertTrue(contextApproval.payload().path("approval").isObject());
            assertTrue(contextApproval.payload().path("approval")
                    .path("trustedContextRead").isBoolean());
            assertTrue(ToolApprovalChallenge.fromEventPayload(
                    contextApproval.payload()).trustedContextRead());
            fixture.restart();
            assertTrue(Set.of(RunState.WAITING_APPROVAL, RunState.PAUSED)
                    .contains(fixture.engine.get(handle.id()).state()));
            for (int attempt = 0; attempt < 3
                    && Set.of(RunState.WAITING_APPROVAL, RunState.PAUSED)
                            .contains(fixture.engine.get(handle.id()).state()); attempt++) {
                var waiting = fixture.runs.eventsAfter(handle.id(), 0).stream()
                        .filter(event -> event.type().equals("core.run.waiting_approval"))
                        .reduce((previous, next) -> next).orElseThrow();
                String fingerprint = ToolApprovalChallenge.fromEventPayload(
                        waiting.payload()).fingerprint();
                handle = fixture.engine.resume(handle.id(), new ResumeCommand("tool.approval",
                        JsonNodeFactory.instance.objectNode().put("approved", true)
                                .put("fingerprint", fingerprint)));
            }
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, resumedSelections.get(),
                    "the persisted selection must be reused after context-tool approval");
            assertEquals(1, providerCalls.get());
        }
    }

    @Test
    void 已耗尽工具额度时跳过目录规划并只允许控制决策() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger plannerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (providerCalls.get() == 0) {
                if (request.purpose().endsWith(".select_v2")) {
                    selection.putArray("searches");
                    toolIntent(selection, "test_mutate", "test");
                } else {
                    selection.putArray("sourceIds");
                    toolChoice(selection, request, "direct", "test_mutate");
                }
            } else if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, "unknown code target search", "code");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "discover");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).autoApproveTestMutate();
        try (Fixture fixture = new Fixture(prompt -> {
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> toolCallResponse(2, 1);
                case 2 -> {
                    assertEquals(List.of(), toolNames(prompt),
                            "no business tool remains in the shared budget");
                    assertEquals(List.of(HarnessDecisionToolCallback.NAME),
                            allToolNames(prompt));
                    yield textResponse("budget exhausted", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        }, toolCallBudget(1), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("call then discover"));
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, providerCalls.get());
            assertEquals(2, plannerCalls.get(),
                    "the control-only final step must bypass tool selection");
            assertEquals(2, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL).count());
        }
    }

    @Test
    void 工具全禁用时规划器要求目录发现不会空目录调用主模型() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, "nonexistent target");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "discover");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("discover target", Map.of(
                    "framework.disableTools", JsonNodeFactory.instance.booleanNode(true))));
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state());
            assertTrue(snapshot.output().path("reason").asText().contains("no authorized tool catalog"));
            assertEquals(0, providerCalls.get());
        }
    }

    @Test
    void successiveApprovalResumesRetainEarlierToolMessagesAndAtomicSteps() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger tools = new AtomicInteger();
        AtomicReference<Prompt> finalPrompt = new AtomicReference<>();
        ChatModel model = prompt -> {
            int number = calls.incrementAndGet();
            if (number <= 2) return namedToolCallResponse("test_mutate", "{\"value\":" + number + "}", 2, 1);
            finalPrompt.set(prompt);
            return textResponse("done", 2, 1);
        };
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, tools)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            for (int number = 1; number <= 2; number++) {
                assertEquals(RunState.WAITING_APPROVAL, fixture.engine.get(handle.id()).state());
                if (number == 2) fixture.restart();
                var approval = JsonNodeFactory.instance.objectNode().put("approved", true)
                        .put("fingerprint", ToolInvocationFingerprint.create("test_mutate",
                                JsonNodeFactory.instance.objectNode().put("value", number)));
                handle = fixture.engine.resume(handle.id(), new ResumeCommand("tool.approval", approval));
            }
            assertEquals(RunState.COMPLETED, awaitCompletion(fixture, handle).state());
            assertEquals(2, tools.get());
            assertEquals(3, calls.get());
            assertEquals(2, finalPrompt.get().getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count());
            var steps = new RunStepQuery(fixture.runs).steps(handle.id());
            assertEquals(3, steps.stream().filter(step -> step.kind() == AgentStep.Kind.MODEL).count());
            assertEquals(2, steps.stream().filter(step -> step.kind() == AgentStep.Kind.TOOL).count());
            assertTrue(steps.stream().allMatch(step -> step.state() == AgentStep.State.COMPLETED));
            org.junit.jupiter.api.Assertions.assertNull(steps.getFirst().causationStepId());
            for (int index = 1; index < steps.size(); index++)
                assertEquals(steps.get(index - 1).id().value(), steps.get(index).causationStepId());
            assertTrue(steps.stream().filter(step -> step.kind() == AgentStep.Kind.TOOL)
                    .allMatch(step -> step.causationStepId() != null && step.output().has("modelOutput")));
            assertTrue(steps.stream().filter(step -> step.kind() == AgentStep.Kind.MODEL)
                    .allMatch(step -> step.input().path("messages").isArray() && step.output().has("message")));
        }
    }

    @Test void controlOnlyRecoveryUsesPersistedFinalResponseWithoutAnotherModelRequest() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        try (Fixture fixture = new Fixture(prompt -> {
            modelCalls.incrementAndGet(); return toolCallResponse(2, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger())) {
            RunHandle turn = fixture.engine.start(fixture.request());
            // Simulate a provider response durably written immediately before the process died.
            var events = StepEvents.durableSink(fixture.runs, turn.id());
            StepId finalStep = StepId.random();
            ObjectNode finalInput = (ObjectNode) new RunStepQuery(fixture.runs).steps(turn.id())
                    .stream().filter(step -> step.kind() == AgentStep.Kind.MODEL)
                    .reduce((previous, current) -> current).orElseThrow().input().deepCopy();
            assertTrue(jsonStrings(finalInput.path("toolNames"))
                    .contains(HarnessDecisionToolCallback.NAME));
            StepEvents.started(events, finalStep, AgentStep.Kind.MODEL, finalInput, null);
            ChatResponse finalResponse = textResponse("already finished", 3, 1);
            StepEvents.completed(events, finalStep, StepMessageCodec.response(finalResponse), StepMessageCodec.usage(finalResponse));
            fixture.runs.append(turn.id(), Set.of(RunState.WAITING_APPROVAL), RunState.PAUSED,
                    new com.javaclaw.framework.spi.RunEventDraft("core.run.paused", 1, "test", null, null,
                            JsonNodeFactory.instance.objectNode().put("reason", "PROCESS_RESTART_REQUIRES_RESUME")), null, null);
            fixture.restart();
            var resumed = fixture.engine.resume(turn.id(), new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));
            assertEquals(RunState.COMPLETED, resumed.completion().toCompletableFuture()
                    .get(10, java.util.concurrent.TimeUnit.SECONDS).state());
            assertEquals(1, modelCalls.get());
            assertEquals("already finished", fixture.engine.get(turn.id()).output().path("text").asText());
        }
    }

    @Test
    void disabledToolsOnDemandRecoversPersistedControlDecisionWithoutCatalog()
            throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, "");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "none");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner)
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_UNAVAILABLE_FEEDBACK_ONCE);
        try (Fixture fixture = new Fixture(prompt -> {
            modelCalls.incrementAndGet();
            assertEquals(List.of(HarnessDecisionToolCallback.NAME), allToolNames(prompt));
            return namedToolCallResponse("web_content", "{}", 2, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle turn = fixture.engine.start(fixture.request("answer without tools", Map.of(
                    "framework.disableTools", JsonNodeFactory.instance.booleanNode(true))));
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(turn.id()).state());
            assertEquals(1, modelCalls.get());
            Prompt pausedPrompt = config.pausedPrompt.get();
            assertNotNull(pausedPrompt);

            ObjectNode input = (ObjectNode) new RunStepQuery(fixture.runs).steps(turn.id())
                    .stream().filter(step -> step.kind() == AgentStep.Kind.MODEL)
                    .reduce((previous, current) -> current).orElseThrow().input().deepCopy();
            input.set("messages", StepMessageCodec.messages(pausedPrompt.getInstructions()));
            assertEquals(List.of(HarnessDecisionToolCallback.NAME),
                    jsonStrings(input.path("toolNames")));
            assertTrue(input.path("toolFingerprints")
                    .has(HarnessDecisionToolCallback.NAME));
            var events = StepEvents.durableSink(fixture.runs, turn.id());
            StepId finalStep = StepId.random();
            StepEvents.started(events, finalStep, AgentStep.Kind.MODEL, input, null);
            ChatResponse finalResponse = textResponse("answer delivered", 3, 1);
            StepEvents.completed(events, finalStep, StepMessageCodec.response(finalResponse),
                    StepMessageCodec.usage(finalResponse));

            fixture.restart();
            RunHandle resumed = fixture.engine.resume(turn.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));
            assertEquals(RunState.COMPLETED, resumed.completion().toCompletableFuture()
                    .get(10, java.util.concurrent.TimeUnit.SECONDS).state());
            assertEquals(1, modelCalls.get(), "the persisted control response must be replayed");
            assertEquals("answer delivered", fixture.engine.get(turn.id()).output()
                    .path("text").asText());
        }
    }

    @Test void lateProviderResponseAfterCancellationSettlesOnceAndItsParentBudgetSurvivesRestart() {
        AtomicReference<Fixture> active = new AtomicReference<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel ignoresCancellation = prompt -> {
            modelCalls.incrementAndGet();
            Fixture fixture = active.get();
            RunId child = fixture.runs.nonTerminalRuns().stream()
                    .filter(run -> run.request().scope().sessionId().equals("late-child"))
                    .findFirst().orElseThrow().snapshot().id();
            fixture.engine.cancel(child, new CancelReason("TEST", "provider still returning"));
            return textResponse("late but billable", 7, 2);
        };
        try (Fixture fixture = new Fixture(ignoresCancellation, RunBudget.UNBOUNDED, new AtomicInteger())) {
            active.set(fixture);
            ManagedTurn parent = fixture.engine.beginTurn(fixture.request());
            RunRequest childRequest = RunRequest.builder()
                    .agent(AgentDefinitionRef.latest("test.agent")).profile(RunProfileRef.latest("test.profile"))
                    .source(new InvocationSource("subagent", "late-child"))
                    .scope(new RunScope("workspace", "user", "late-child"))
                    .linkage(new RunLinkage(parent.id(), null, "late-charge"))
                    .input(InputBlock.text("finish once")).permissionCeiling(PermissionSet.UNRESTRICTED).build();
            RunHandle child = fixture.engine.start(childRequest);
            assertEquals(RunState.CANCELLED, fixture.engine.get(child.id()).state());
            var step = new RunStepQuery(fixture.runs).steps(child.id()).getFirst();
            assertEquals(AgentStep.State.COMPLETED, step.state());
            var cancelledDecision = assertDoesNotThrow(() -> fixture.json.readTree(
                    step.output().path("message").path("toolCalls").get(0)
                            .path("arguments").asText()));
            assertEquals("late but billable", cancelledDecision.path("userMessage").asText());
            assertEquals(7, step.usage().path("inputTokens").asLong());
            assertEquals(7, fixture.ledger.aggregateSnapshot(parent.id()).inputTokens());
            var events = fixture.runs.eventsAfter(child.id(), 0);
            assertEquals(1, events.stream().filter(event -> event.type().equals("core.run.cancelled")).count());
            assertEquals(0, events.stream().filter(event -> event.type().equals("core.run.completed")).count());
            assertEquals(1, events.stream().filter(event -> event.type().equals("core.step.completed")).count());
            assertEquals(0, events.stream().filter(event -> event.type().equals("core.model.usage")).count());
            parent.close();
            fixture.restart();
            assertEquals(7, fixture.ledger.snapshot(child.id()).inputTokens());
            assertEquals(7, fixture.ledger.aggregateSnapshot(parent.id()).inputTokens());
            assertEquals(1, modelCalls.get());
        }
    }

    @Test void redactedPendingArgumentsPauseButCompletedToolsReplayWithoutParsingCredentials() throws Exception {
        for (boolean toolCompleted : List.of(false, true)) {
            AtomicInteger calls = new AtomicInteger(), tools = new AtomicInteger();
            try (Fixture fixture = new Fixture(prompt -> calls.incrementAndGet() == 1
                    ? toolCallResponse(2, 1) : textResponse("done", 2, 1), RunBudget.UNBOUNDED, tools)) {
                RunHandle turn = fixture.engine.start(fixture.request());
                StepId modelStep = StepId.random();
                var events = StepEvents.durableSink(fixture.runs, turn.id());
                ObjectNode modelInput = JsonNodeFactory.instance.objectNode();
                modelInput.set("messages", StepMessageCodec.messages(List.of(
                        new org.springframework.ai.chat.messages.SystemMessage("test"),
                        new UserMessage("task"))));
                modelInput.putArray("toolNames").add("test_mutate");
                StepEvents.started(events, modelStep, AgentStep.Kind.MODEL, modelInput, null);
                ChatResponse response = namedToolCallResponse("test_mutate", "{\"value\":1,\"token\":\"sk-testcredential123456789\"}", 2, 1);
                StepEvents.completed(events, modelStep, StepMessageCodec.response(response), StepMessageCodec.usage(response));
                assertTrue(fixture.runs.eventsAfter(turn.id(), 0).getLast().payload().path("credentialRedacted").asBoolean());
                if (toolCompleted) {
                    StepId tool = StepId.tool(turn.id(), "model/" + modelStep.value() + "/provider-call");
                    StepEvents.started(events, tool, AgentStep.Kind.TOOL, JsonNodeFactory.instance.objectNode(), modelStep.value());
                    StepEvents.completed(events, tool, JsonNodeFactory.instance.objectNode()
                            .set("modelOutput", JsonNodeFactory.instance.objectNode().put("success", true)), null);
                }
                fixture.runs.append(turn.id(), Set.of(RunState.WAITING_APPROVAL), RunState.PAUSED,
                        new RunEventDraft("core.run.paused", 1, "test", null, null,
                                JsonNodeFactory.instance.objectNode().put("reason", "PROCESS_RESTART_REQUIRES_RESUME")), null, null);
                fixture.restart();
                fixture.engine.resume(turn.id(), new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));
                assertEquals(toolCompleted ? RunState.COMPLETED : RunState.PAUSED, fixture.engine.get(turn.id()).state());
                assertEquals(toolCompleted ? 2 : 1, calls.get());
                assertEquals(0, tools.get(), "redacted credentials must never be sent to a tool");
                if (!toolCompleted) assertEquals("tool.recovery_required", fixture.engine.get(turn.id()).output().path("kind").asText());
            }
        }
    }

    @Test
    void interruptedToolWithUnknownOutcomeIsPausedAndNeverAutomaticallyRepeated() {
        AtomicInteger tools = new AtomicInteger();
        try (Fixture fixture = new Fixture(prompt -> toolCallResponse(2, 1), RunBudget.UNBOUNDED, tools)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            AgentStep model = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL).findFirst().orElseThrow();
            String invocation = "model/" + model.id().value() + "/provider-call";
            StepId toolId = StepId.tool(handle.id(), invocation);
            StepEvents.started(StepEvents.durableSink(fixture.runs, handle.id()), toolId, AgentStep.Kind.TOOL,
                    JsonNodeFactory.instance.objectNode().put("tool", "test_mutate").put("invocationId", invocation),
                    model.id().value());
            var approval = JsonNodeFactory.instance.objectNode().put("approved", true)
                    .put("fingerprint", ToolInvocationFingerprint.create("test_mutate",
                            JsonNodeFactory.instance.objectNode().put("value", 1)));
            fixture.engine.resume(handle.id(), new ResumeCommand("tool.approval", approval));
            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(0, tools.get());
            assertEquals("tool.recovery_required", fixture.engine.get(handle.id()).output().path("kind").asText());
            assertEquals(AgentStep.State.RUNNING, new RunStepQuery(fixture.runs).step(handle.id(), toolId).orElseThrow().state());
        }
    }

    @Test
    void approvedCallExecutesExactlyOnceBeforeTheNextModelRequest() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicReference<Prompt> continuedPrompt = new AtomicReference<>();
        ChatModel model = prompt -> {
            int call = modelCalls.incrementAndGet();
            if (call == 1) return toolCallResponse(7, 3);
            assertEquals(1, toolCalls.get(),
                    "the approved invocation must execute before asking the model again");
            continuedPrompt.set(prompt);
            return textResponse("done", 5, 2);
        };

        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, toolCalls)) {
            var handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.WAITING_APPROVAL, fixture.engine.get(handle.id()).state());
            var waiting = fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.run.waiting_approval"))
                    .reduce((previous, next) -> next).orElseThrow();
            assertTrue(waiting.payload().path("approval").isObject());
            assertTrue(waiting.payload().path("approval").path("trustedContextRead").isBoolean());
            ToolApprovalChallenge challenge = ToolApprovalChallenge.fromEventPayload(
                    waiting.payload());
            assertFalse(challenge.trustedContextRead());
            assertEquals("test_mutate", challenge.tool());
            assertEquals(1, challenge.arguments().path("value").asInt());

            ObjectNode approval = JsonNodeFactory.instance.objectNode();
            approval.put("approved", true);
            approval.put("fingerprint", ToolInvocationFingerprint.create(
                    "test_mutate", JsonNodeFactory.instance.objectNode().put("value", 1)));
            var resumed = fixture.engine.resume(
                    handle.id(), new ResumeCommand("tool.approval", approval));
            RunOutcome outcome = awaitCompletion(fixture, resumed);

            assertEquals(RunState.COMPLETED, outcome.state());
            assertEquals(2, modelCalls.get());
            assertEquals(1, toolCalls.get());
            List<org.springframework.ai.chat.messages.Message> instructions =
                    continuedPrompt.get().getInstructions();
            assertInstanceOf(AssistantMessage.class,
                    instructions.get(instructions.size() - 2));
            AssistantMessage assistant = (AssistantMessage) instructions.get(
                    instructions.size() - 2);
            assertEquals("test_mutate", assistant.getToolCalls().getFirst().name());
            assertEquals("{\"value\":1}", assistant.getToolCalls().getFirst().arguments());
            ToolResponseMessage response = assertInstanceOf(
                    ToolResponseMessage.class, instructions.getLast());
            assertEquals(assistant.getToolCalls().getFirst().id(),
                    response.getResponses().getFirst().id());

            assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.model.usage")).count());
            assertEquals(12, outcome.output().path("usage").path("inputTokens").asLong());
            assertEquals(5, outcome.output().path("usage").path("outputTokens").asLong());
            assertEquals(12, fixture.ledger.snapshot(handle.id()).inputTokens());
            assertEquals(5, fixture.ledger.snapshot(handle.id()).outputTokens());
        }
    }

    @Test
    void unadvertisedWebContentCallWithNoCallbacksReturnsUnavailableFeedback() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            assertEquals(List.of(), toolNames(prompt));
            return switch (modelCalls.incrementAndGet()) {
                case 1 -> namedToolCallResponse("web_content", "{\"url\":\"https://example.com\"}", 2, 1);
                case 2 -> {
                    ToolResponseMessage.ToolResponse feedback = lastToolResponse(prompt);
                    assertEquals("provider-call", feedback.id());
                    assertEquals("web_content", feedback.name());
                    var details = assertDoesNotThrow(() ->
                            new ObjectMapper().readTree(feedback.responseData()));
                    assertEquals("tool_not_offered", details.path("error").asText());
                    assertEquals("web_content", details.path("tool").asText());
                    assertFalse(details.path("executed").asBoolean(true));
                    assertEquals(List.of(HarnessDecisionToolCallback.NAME),
                            jsonStrings(details.path("offeredTools")));
                    assertEquals("previous_provider_step", details.path("feedbackScope").asText());
                    assertTrue(details.path("message").asText().contains(
                            "framework_tool_catalog only when currently offered"));
                    yield textResponse("上海", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };

        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, toolCalls)) {
            RunHandle handle = fixture.engine.start(fixture.request("上海", Map.of(
                    "framework.disableTools", JsonNodeFactory.instance.booleanNode(true))));
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals("上海", outcome.output().path("text").asText());
            assertEquals(2, modelCalls.get());
            assertEquals(0, toolCalls.get());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.tool.started")));
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void unadvertisedToolInBatchRejectsBeforeVisibleToolCanExecute() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicReference<List<String>> offeredTools = new AtomicReference<>();
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1 -> {
                offeredTools.set(allToolNames(prompt));
                assertTrue(offeredTools.get().contains("test_mutate"));
                AssistantMessage output = AssistantMessage.builder().content("")
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall("visible", "function",
                                        "test_mutate", "{\"value\":1}"),
                                new AssistantMessage.ToolCall("unadvertised", "function",
                                        "web_content", "{\"url\":\"https://example.com\"}")))
                        .build();
                yield response(output, 2, 1);
            }
            case 2 -> {
                assertEquals(0, toolCalls.get(),
                        "the visible tool must not run before the entire batch is validated");
                ToolResponseMessage.ToolResponse feedback = prompt.getInstructions().stream()
                        .filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast)
                        .flatMap(message -> message.getResponses().stream())
                        .filter(item -> item.id().equals("unadvertised")
                                && item.name().equals("web_content"))
                        .findFirst().orElseThrow();
                var details = assertDoesNotThrow(() ->
                        new ObjectMapper().readTree(feedback.responseData()));
                List<String> actualOffered = new ArrayList<>();
                details.path("offeredTools").forEach(value -> actualOffered.add(value.asText()));
                assertEquals(offeredTools.get(), actualOffered,
                        "feedback must list the exact tools offered to the provider");
                assertFalse(details.path("executed").asBoolean(true));
                yield textResponse("done", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        };
        FixtureConfig config = new FixtureConfig().autoApproveTestMutate();
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, toolCalls, config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, modelCalls.get());
            assertEquals(0, toolCalls.get());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.tool.started")
                            && event.payload().path("tool").asText().equals("test_mutate")));
        }
    }

    @Test
    void rejectedUnadvertisedCallSurvivesRestartBeforeNextProviderRequest() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicReference<String> resumedFeedback = new AtomicReference<>();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_UNAVAILABLE_FEEDBACK_ONCE);
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1 -> namedToolCallResponse("web_content", "{}", 2, 1);
            case 2 -> {
                String feedback = prompt.getInstructions().stream()
                        .filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast)
                        .flatMap(message -> message.getResponses().stream())
                        .filter(item -> item.name().equals("web_content"))
                        .map(ToolResponseMessage.ToolResponse::responseData)
                        .findFirst().orElseThrow();
                resumedFeedback.set(feedback);
                yield textResponse("done", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        };
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, toolCalls, config)) {
            RunHandle handle = fixture.engine.start(fixture.request("上海", Map.of(
                    "framework.disableTools", JsonNodeFactory.instance.booleanNode(true))));
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            assertEquals(1, modelCalls.get());
            assertEquals(0, toolCalls.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL
                            && step.state() == AgentStep.State.COMPLETED).count());
            Prompt paused = config.pausedPrompt.get();
            assertNotNull(paused);
            String persistedFeedback = lastToolResponse(paused).responseData();
            assertTrue(persistedFeedback.contains("web_content"));

            fixture.restart();
            RunHandle resumed = fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));
            RunOutcome outcome = awaitCompletion(fixture, resumed);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals("done", outcome.output().path("text").asText());
            assertEquals(2, modelCalls.get());
            assertEquals(0, toolCalls.get());
            assertEquals(persistedFeedback, resumedFeedback.get());
        }
    }

    @Test
    void repeatedUnadvertisedCallsPauseAfterBoundedFeedback() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        try (Fixture fixture = new Fixture(prompt -> {
            assertEquals(List.of(), toolNames(prompt));
            modelCalls.incrementAndGet();
            return namedToolCallResponse("web_content", "{}", 2, 1);
        }, RunBudget.UNBOUNDED, toolCalls)) {
            RunHandle handle = fixture.engine.start(fixture.request("上海", Map.of(
                    "framework.disableTools", JsonNodeFactory.instance.booleanNode(true))));
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals("tool.recovery_required", snapshot.output().path("kind").asText());
            assertEquals("web_content", snapshot.output().path("requestedTools").get(0).asText());
            assertEquals(List.of(HarnessDecisionToolCallback.NAME),
                    jsonStrings(snapshot.output().path("offeredTools")));
            assertTrue(snapshot.output().path("unfinishedAction").asText().contains("not executed"));
            assertEquals(3, modelCalls.get(), "two feedback batches then stop the next bad batch");
            assertEquals(0, toolCalls.get());
            assertEquals(2, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.output().path("rejectedUnavailableToolBatch").asBoolean())
                    .count());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.tool.started")));
        }
    }

    @Test
    void untrustedBusinessResultDoesNotResetUnavailableBatchLimit() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1, 3, 4, 5 -> namedToolCallResponse("web_content", "{}", 2, 1);
            case 2 -> namedToolCallResponse("test_mutate", "{\"value\":7}", 2, 1);
            default -> throw new AssertionError("unexpected provider call");
        };
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, toolCalls,
                new FixtureConfig().autoApproveTestMutate())) {
            RunHandle handle = fixture.engine.start(fixture.request());
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals(4, modelCalls.get(),
                    "an untrusted business result cannot reset the rejection streak");
            assertEquals(1, toolCalls.get());
            assertEquals(2, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.output().path("rejectedUnavailableToolBatch").asBoolean())
                    .count());
        }
    }

    @Test
    void pausedUnavailableBatchResumesWithDurableFeedbackWithoutExecutingOldCall()
            throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1, 2, 3 -> namedToolCallResponse("web_content", "{}", 2, 1);
            case 4 -> {
                String feedback = lastToolResponse(prompt).responseData();
                assertTrue(feedback.contains("\"error\":\"tool_not_offered\""));
                assertTrue(feedback.contains("\"executed\":false"));
                yield textResponse("done", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        };
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, toolCalls)) {
            RunHandle handle = fixture.engine.start(fixture.request("上海", Map.of(
                    "framework.disableTools", JsonNodeFactory.instance.booleanNode(true))));
            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(2, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.output().path("rejectedUnavailableToolBatch").asBoolean())
                    .count());

            fixture.restart();
            RunHandle resumed = fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));
            RunOutcome outcome = awaitCompletion(fixture, resumed);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals("done", outcome.output().path("text").asText());
            assertEquals(4, modelCalls.get());
            assertEquals(0, toolCalls.get());
            assertEquals(3, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.output().path("rejectedUnavailableToolBatch").asBoolean())
                    .count());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.tool.started")));
        }
    }

    @Test
    void invalidToolArgumentsAreReturnedToModelBeforeCorrectedCallExecutes() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1 -> namedToolCallResponse("test_mutate", "{\"session_ref\":\"wrong-key\"}", 2, 1);
            case 2 -> {
                String feedback = lastToolResponse(prompt).responseData();
                assertTrue(feedback.contains("\"error\":\"invalid_tool_arguments\""));
                assertTrue(feedback.contains("\"executed\":false"));
                assertTrue(feedback.contains("session_ref"));
                assertTrue(feedback.contains("\"requiredProperties\":[\"value\"]"));
                assertTrue(feedback.contains("\"allowedProperties\":[\"value\"]"));
                assertEquals(0, toolCalls.get());
                yield namedToolCallResponse("test_mutate", "{\"value\":7}", 2, 1);
            }
            case 3 -> {
                assertTrue(lastToolResponse(prompt).responseData().contains("\"observed\":7"));
                yield textResponse("done", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        };
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, toolCalls,
                new FixtureConfig().autoApproveTestMutate())) {
            RunHandle handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(3, modelCalls.get());
            assertEquals(1, toolCalls.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.TOOL
                            && step.output().path("validationRejected").asBoolean(false))
                    .count());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.started")).count(),
                    "only the corrected call starts tool execution");
        }
    }

    @Test
    void invalidArgumentsFeedbackSurvivesRestartBeforeNextProviderRequest() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicReference<String> resumedFeedback = new AtomicReference<>();
        FixtureConfig config = new FixtureConfig().autoApproveTestMutate()
                .context(contextConfiguration(true, 48_000, 16_000, 10))
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_ARGUMENT_FEEDBACK_ONCE);
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1 -> namedToolCallResponse("test_mutate", "{\"session_ref\":\"wrong-key\"}", 2, 1);
            case 2 -> {
                String feedback = lastToolResponse(prompt).responseData();
                resumedFeedback.set(feedback);
                yield namedToolCallResponse("test_mutate", "{\"value\":7}", 2, 1);
            }
            case 3 -> textResponse("done", 2, 1);
            default -> throw new AssertionError("unexpected provider call");
        };
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, toolCalls, config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            assertEquals(1, modelCalls.get());
            assertEquals(0, toolCalls.get());
            Prompt paused = config.pausedPrompt.get();
            assertNotNull(paused);
            String persistedFeedback = lastToolResponse(paused).responseData();
            assertTrue(persistedFeedback.contains("invalid_tool_arguments"));

            fixture.restart();
            RunHandle resumed = fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));
            RunOutcome outcome = awaitCompletion(fixture, resumed);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals("done", outcome.output().path("text").asText());
            assertEquals(persistedFeedback, resumedFeedback.get());
            assertEquals(3, modelCalls.get());
            assertEquals(1, toolCalls.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.TOOL
                            && step.output().path("validationRejected").asBoolean(false))
                    .count());
        }
    }

    @Test
    void repeatedInvalidArgumentsPauseAfterBoundedModelFeedback() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        try (Fixture fixture = new Fixture(prompt -> {
            modelCalls.incrementAndGet();
            return namedToolCallResponse("test_mutate", "{\"session_ref\":\"wrong-key\"}", 2, 1);
        }, RunBudget.UNBOUNDED, toolCalls, new FixtureConfig().autoApproveTestMutate())) {
            RunHandle handle = fixture.engine.start(fixture.request());
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals("tool.recovery_required", snapshot.output().path("kind").asText());
            assertEquals(3, modelCalls.get());
            assertEquals(0, toolCalls.get());
            assertEquals(2, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.TOOL
                            && step.output().path("validationRejected").asBoolean(false))
                    .count());
        }
    }

    @Test
    void overBudgetToolCallResponseIsMeteredBeforeAnyToolExecutes() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        ChatModel model = prompt -> {
            modelCalls.incrementAndGet();
            return toolCallResponse(1_007, 3);
        };
        RunBudget budget = new RunBudget(
                Duration.ofMinutes(1), 1_000, 100, 4, BigDecimal.TEN);

        try (Fixture fixture = new Fixture(model, budget, toolCalls)) {
            var handle = fixture.engine.start(fixture.request());
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals("harness.budget_exhausted", snapshot.output().path("kind").asText());
            assertEquals("MODEL_INPUT_TOKENS", snapshot.output().path("budgetKind").asText());
            assertEquals("1007", snapshot.output().path("budgetActual").asText());
            assertEquals("1000", snapshot.output().path("budgetLimit").asText());
            assertEquals(1, modelCalls.get());
            assertEquals(0, toolCalls.get());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.model.usage")).count());
            assertEquals(1_007, fixture.ledger.snapshot(handle.id()).inputTokens());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.tool.started")));
        }
    }

    @Test
    void clearlyUnaffordablePromptStopsBeforeProviderCallAndRecordsNoUsage() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        RunBudget budget = new RunBudget(
                Duration.ofMinutes(1), 1_724, 100, 4, BigDecimal.TEN);
        try (Fixture fixture = new Fixture(prompt -> {
            modelCalls.incrementAndGet();
            return textResponse("unexpected", 21_510, 1);
        }, budget, toolCalls)) {
            RunHandle handle = fixture.engine.start(fixture.request("x".repeat(32_000)));
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals("harness.budget_exhausted", snapshot.output().path("kind").asText());
            assertEquals(0, modelCalls.get());
            assertEquals(0, toolCalls.get());
            assertEquals(0, fixture.ledger.snapshot(handle.id()).inputTokens());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.model.usage")));
        }
    }

    @Test
    void clarificationToolSuspendsWithoutRetryingTheModelOrTool() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        ChatModel model = prompt -> {
            modelCalls.incrementAndGet();
            return namedToolCallResponse(
                    "test_clarify", "{\"question\":\"Which target?\"}", 4, 1);
        };

        try (Fixture fixture = new Fixture(
                model, RunBudget.UNBOUNDED, toolCalls, true)) {
            var handle = fixture.engine.start(fixture.request());
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.WAITING_INPUT, snapshot.state());
            assertEquals("clarify_request", snapshot.output().path("kind").asText());
            assertEquals("Which target?",
                    snapshot.output().path("payload").path("question").asText());
            assertEquals(1, modelCalls.get(), "clarification is a control signal, not a retryable failure");
            assertEquals(1, toolCalls.get(), "one model tool call must yield one clarification request");
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.completed")).count());
            assertEquals(0, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.failed")).count());
        }
    }

    @Test
    void failedManagedInferenceUsageIsRecordedBeforeTheRunFails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = prompt -> {
            calls.incrementAndGet();
            throw new ManagedInferenceChatModel.ManagedInferenceModelException(
                    "inference_error", "failed", false,
                    new com.javaclaw.inference.api.InferenceUsage(6, 2),
                    "deliverance:test", null);
        };

        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger())) {
            var handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.FAILED, outcome.state());
            assertEquals(1, calls.get());
            assertEquals(6, fixture.ledger.snapshot(handle.id()).inputTokens());
            assertEquals(2, fixture.ledger.snapshot(handle.id()).outputTokens());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.model.usage")
                            && event.payload().path("failed").asBoolean()).count());
        }
    }

    @Test
    void catalogCanActivateASeventiethToolWithOneVisibleToolAtATime() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (calls.incrementAndGet()) {
                case 1 -> {
                    assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                    yield namedToolCallResponse("framework_tool_catalog",
                            "{\"query\":\"code_target\"}", 2, 1);
                }
                case 2 -> {
                    assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                    assertTrue(lastToolResponse(prompt).responseData().contains("code_target"));
                    assertTrue(lastToolResponse(prompt).responseData().contains("\"action\":\"list\""));
                    yield namedToolCallResponse("framework_tool_catalog",
                            "{\"names\":[\"code_target\"]}", 2, 1);
                }
                case 3 -> {
                    assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                    assertTrue(lastToolResponse(prompt).responseData()
                            .contains("action=activate is required when names are supplied"));
                    yield namedToolCallResponse("framework_tool_catalog",
                            "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
                }
                case 4 -> {
                    assertEquals(List.of("code_target"), toolNames(prompt));
                    assertTrue(lastToolResponse(prompt).responseData().contains("code_target"));
                    yield namedToolCallResponse("code_target", "{\"value\":42}", 2, 1);
                }
                case 5 -> {
                    assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                    assertEquals("code_target", lastToolResponse(prompt).name());
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .fillerTools(70).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.COMPLETED, awaitCompletion(fixture, handle).state());
            assertEquals(5, calls.get());
            assertEquals(1, codeCalls.get());
            assertEquals(4, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.completed")).count());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .anyMatch(event -> event.type().equals("core.tool.completed")
                            && event.payload().path("tool").asText().equals("framework_tool_catalog")
                            && event.payload().path("output").path("activated").toString()
                                    .contains("code_target")));
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void catalogNaturalLanguageDesktopQueryListsAuthorizedNavigationToolsOnFirstPage() throws Exception {
        Set<String> desktopNames = Set.of("desktop_session_probe", "desktop_session_targets",
                "desktop_session_open", "desktop_session_click");
        Map<String, String> desktopDescriptions = new HashMap<>();
        for (var method : DesktopSessionTools.class.getDeclaredMethods()) {
            var annotation = method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
            if (annotation != null && desktopNames.contains(annotation.name())) {
                desktopDescriptions.put(annotation.name(), annotation.description());
            }
        }
        assertEquals(desktopNames, desktopDescriptions.keySet());
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = prompt -> switch (calls.incrementAndGet()) {
            case 1 -> {
                assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                yield namedToolCallResponse("framework_tool_catalog",
                        "{\"query\":\"桌面会话 探测 打开 示例应用 设置\"}", 2, 1);
            }
            case 2 -> {
                String listed = lastToolResponse(prompt).responseData();
                assertTrue(listed.contains("\"action\":\"list\""), listed);
                assertTrue(listed.contains("desktop_session_probe"), listed);
                assertTrue(listed.contains("desktop_session_targets"), listed);
                assertTrue(listed.contains("desktop_session_open"), listed);
                assertFalse(listed.contains("desktop_session_click"), listed);
                assertFalse(listed.contains("code_target"), listed);
                assertFalse(listed.contains("\"hasNext\":true"), listed);
                yield textResponse("found", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .simulatedDesktopSession(new AtomicInteger(), new AtomicInteger())
                .desktopToolDescriptions(desktopDescriptions)
                .allowedToolNames(Set.of("framework_tool_catalog", "desktop_session_probe",
                        "desktop_session_targets", "desktop_session_open"));
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED,
                new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void continuingAfterAnOldDesktopDenialCanDiscoverAndLaunchWithCurrentAccess() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger launchCalls = new AtomicInteger();
        AtomicBoolean desktopEnabled = new AtomicBoolean(false);
        String oldFailure = "OLD_DESKTOP_DENIAL：请先在设置中开启电脑应用访问";
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            if (request.purpose().equals("task.contract.plan.v3")) {
                assertEquals("继续", request.input().path("request").asText());
                assertTrue(request.input().path("humanHistory").toString().contains("打开QQ查看联系人"));
                assertFalse(request.input().path("humanHistory").toString().contains("OLD_DESKTOP_DENIAL"));
                selection.put("applicable", true).put("intentStatus", "RESOLVED")
                        .put("originalRequest", "打开QQ查看联系人");
                var criteria = selection.putArray("criteria");
                criteria.addObject().put("id", "launch").put("description", "启动QQ")
                        .put("capabilityId", "desktop.launch").put("targetType", "DESKTOP_APPLICATION")
                        .put("target", "QQ").put("requiredEvidence", "ACCEPTED")
                        .put("requiredSubject", "");
                criteria.addObject().put("id", "contacts").put("description", "观察QQ联系人")
                        .put("capabilityId", "desktop.observe").put("targetType", "DESKTOP_APPLICATION")
                        .put("target", "QQ").put("requiredEvidence", "OBSERVED")
                        .put("requiredSubject", "联系人");
            } else {
                assertEquals("context.on_demand.select_v2", request.purpose());
                assertTrue(request.input().path("task").asText().contains("打开QQ查看联系人"));
                var current = request.input().path("runtimeContext");
                assertEquals(1, current.size(), "Current host state is deduplicated across desktop tools");
                assertTrue(current.get(0).path("settingEnabled").asBoolean());
                assertEquals("NOT_CHECKED", current.get(0).path("systemStatus").asText());
                selection.putArray("searches");
                var selected = selection.putArray("historyIds");
                request.input().path("history").forEach(history -> {
                    if (history.path("summary").asText().contains("OLD_DESKTOP_DENIAL")) {
                        selected.add(history.path("id").asText());
                    }
                });
                toolIntent(selection, "");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        ChatModel model = prompt -> switch (providerCalls.incrementAndGet()) {
            case 1 -> {
                assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                        message instanceof org.springframework.ai.chat.messages.SystemMessage
                                && message.getText().contains("\"settingEnabled\":true")
                                && message.getText().contains("NOT_CHECKED")));
                assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                        message.getText() != null && message.getText().contains("打开QQ查看联系人")));
                yield namedToolCallResponse("framework_tool_catalog",
                        "{\"action\":\"activate\",\"names\":[\"desktop_session_probe\"]}", 2, 1);
            }
            case 2 -> {
                assertEquals(List.of("desktop_session_probe"), toolNames(prompt));
                yield namedToolCallResponse("desktop_session_probe", "{}", 2, 1);
            }
            case 3 -> {
                assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                var probe = toolEnvelope(prompt);
                assertEquals("SUCCEEDED", probe.path("status").asText());
                assertTrue(probe.toString().contains("ready"));
                assertFalse(probe.toString().contains("不可用"));
                yield namedToolCallResponse("framework_tool_catalog",
                        "{\"action\":\"activate\",\"names\":[\"desktop_session_launch_application\"]}", 2, 1);
            }
            case 4 -> {
                assertEquals(List.of("desktop_session_launch_application"), toolNames(prompt));
                yield namedToolCallResponse("desktop_session_launch_application",
                        "{\"application\":\"QQ\"}", 2, 1);
            }
            case 5 -> {
                assertEquals("SUCCEEDED", toolEnvelope(prompt).path("status").asText());
                assertTrue(toolNames(prompt).contains("desktop_session_open"));
                yield namedToolCallResponse(HarnessDecisionToolCallback.NAME,
                        "{\"decision\":\"CONTINUE\",\"userMessage\":\"QQ已启动，继续观察联系人\","
                                + "\"evidenceRefs\":[],\"unmetCriterionIds\":[\"contacts\"]}", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        };
        FixtureConfig config = new FixtureConfig().harness()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner)
                .simulatedDesktopLaunchOpenObserve(launchCalls, new AtomicInteger(),
                        new AtomicInteger(), new AtomicInteger())
                .simulatedDesktopDiscovery().simulatedApplication("QQ")
                .simulatedRuntimeContext(() -> List.of(JsonNodeFactory.instance.objectNode()
                        .put("kind", "desktop.access.current")
                        .put("settingEnabled", desktopEnabled.get())
                        .put("systemStatus", "NOT_CHECKED")));
        try (Fixture fixture = new Fixture(model, toolCallBudget(8), new AtomicInteger(), config)) {
            desktopEnabled.set(true);
            RunRequest request = fixture.request(List.of(
                    InputBlock.message("user", "打开QQ查看联系人"),
                    InputBlock.message("assistant", oldFailure), InputBlock.text("继续")), Map.of());
            RunHandle handle = fixture.engine.start(request);
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(5, providerCalls.get());
            assertEquals(1, launchCalls.get());
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertEquals("打开QQ查看联系人",
                    TaskResultEvaluator.latestContractV3(events, fixture.json).orElseThrow().originalRequest());
            assertEquals("继续", TaskContractCompiler.originalRequest(request),
                    "Resolution must preserve the current user input and request permissions");
            assertEquals(ModelDecisionV1.Decision.CONTINUE,
                    TaskResultEvaluator.latestModelDecision(events).orElseThrow());
            assertTrue(events.stream().anyMatch(event -> event.type().equals("core.tool.receipt")
                    && event.payload().path("tool").asText().equals("desktop_session_launch_application")
                    && event.payload().path("status").asText().equals("ACCEPTED")));
            assertTrue(events.stream().noneMatch(event -> event.type().equals("core.task.stop")
                    && event.payload().path("reasonCode").asText().equals("MODEL_BLOCKED")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"fixed", "deferred", "deferred_fetch"})
    void contextApprovalRecoveryReusesSelectionAndRefreshesCurrentHostState(String sourceKind)
            throws Exception {
        AtomicBoolean desktopEnabled = new AtomicBoolean();
        AtomicInteger selectionCalls = new AtomicInteger();
        AtomicInteger refinementCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger fixedReads = new AtomicInteger();
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger observes = new AtomicInteger();
        AtomicInteger clicks = new AtomicInteger();
        String body = "CONTEXT_READ_ONCE_AFTER_APPROVAL";
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selectionCalls.incrementAndGet();
                assertFalse(request.input().path("runtimeContext").get(0)
                        .path("settingEnabled").asBoolean(),
                        "the original settled selection observed the old switch");
                assertTrue(request.input().toString().length() <= 1_000);
                var selectedSearches = selection.putArray("searches");
                if (sourceKind.startsWith("deferred")) {
                    selectedSearches.addObject().put("source", "docs").put("query", "preference");
                }
                toolIntent(selection, "");
            } else {
                assertEquals("context.on_demand.refine_v2", request.purpose());
                refinementCalls.incrementAndGet();
                selection.putArray("sourceIds").add("docs:one");
                toolChoice(selection, request, "none");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode()
                        .put("enabled", true).put("plannerInputChars", 1_000))
                .planner(planner)
                .simulatedDesktopLaunchOpenObserve(launches, opens, observes, clicks)
                .simulatedDesktopDiscovery()
                .simulatedRuntimeContext(() -> {
                    ObjectNode current = JsonNodeFactory.instance.objectNode()
                            .put("kind", "desktop.access.current")
                            .put("settingEnabled", desktopEnabled.get())
                            .put("systemStatus", "NOT_CHECKED");
                    if (desktopEnabled.get()) {
                        current.put("detail", "The saved setting changed while awaiting approval. "
                                .repeat(4));
                    }
                    return List.of(current);
                });
        if (sourceKind.equals("fixed")) {
            config.fixedSource(fixedPersona(fixedReads, new AtomicReference<>(body)));
        } else {
            config.source(new DeferredContextSource() {
                @Override public String id() { return "docs"; }
                @Override public String description() { return "Document evidence"; }
                @Override public PermissionSet requiredPermissions() {
                    return PermissionSet.of("context.read");
                }
                @Override public List<DeferredContextCandidate> search(
                        RunRequest request, String query, int limit) {
                    searches.incrementAndGet();
                    return List.of(new DeferredContextCandidate("one", "v1", "saved preference",
                            PermissionSet.NONE));
                }
                @Override public String fetch(RunRequest request, String id, String version) {
                    fetches.incrementAndGet();
                    return body;
                }
            });
        }
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                    message.getText() != null && message.getText().contains(body)));
            var current = prompt.getInstructions().stream()
                    .filter(org.springframework.ai.chat.messages.SystemMessage.class::isInstance)
                    .filter(message -> message.getText().contains("Current host runtime state"))
                    .findFirst().orElseThrow().getText();
            assertTrue(current.contains("\"settingEnabled\":true"));
            assertFalse(current.contains("\"settingEnabled\":false"));
            assertTrue(current.contains("NOT_CHECKED"));
            assertTrue(current.contains("changed while awaiting approval"));
            assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
            return textResponse("context read completed", 2, 1);
        }, toolCallBudget(6), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request(
                    "Use the saved context. " + "Additional human goal context. ".repeat(30)));
            assertEquals(RunState.WAITING_APPROVAL, fixture.engine.get(handle.id()).state());
            assertEquals(1, selectionCalls.get());
            assertEquals(0, providerCalls.get());
            assertEquals(0, fixedReads.get() + searches.get() + fetches.get());
            int approvals = sourceKind.equals("fixed") ? 1 : 2;
            for (int index = 0; index < approvals; index++) {
                RunSnapshot waitingSnapshot = fixture.engine.get(handle.id());
                assertEquals(RunState.WAITING_APPROVAL, waitingSnapshot.state(),
                        sourceKind + " approval " + index + ": " + waitingSnapshot.error()
                                + "; output=" + waitingSnapshot.output());
                if (!sourceKind.equals("deferred_fetch") || index == 1) {
                    if (sourceKind.equals("deferred_fetch")) {
                        assertEquals(1, searches.get());
                        assertEquals(1, refinementCalls.get(),
                                "refinement settled before the host state changes at fetch approval");
                        assertEquals(0, fetches.get());
                    }
                    desktopEnabled.set(true);
                }
                var waiting = fixture.runs.eventsAfter(handle.id(), 0).stream()
                        .filter(event -> event.type().equals("core.run.waiting_approval"))
                        .reduce((previous, next) -> next).orElseThrow();
                String fingerprint = ToolApprovalChallenge.fromEventPayload(
                        waiting.payload()).fingerprint();
                fixture.restart();
                handle = fixture.engine.resume(handle.id(), new ResumeCommand("tool.approval",
                        JsonNodeFactory.instance.objectNode().put("approved", true)
                                .put("fingerprint", fingerprint)));
            }
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, selectionCalls.get(), "completed select_v2 is replayed, not rerun");
            assertEquals(sourceKind.equals("fixed") ? 0 : 1, refinementCalls.get());
            assertEquals(1, providerCalls.get());
            assertEquals(sourceKind.equals("fixed") ? 1 : 0, fixedReads.get());
            assertEquals(sourceKind.startsWith("deferred") ? 1 : 0, searches.get());
            assertEquals(sourceKind.startsWith("deferred") ? 1 : 0, fetches.get());
            assertEquals(0, launches.get() + opens.get() + observes.get() + clicks.get());
            var steps = new RunStepQuery(fixture.runs).steps(handle.id());
            assertEquals(1, steps.stream().filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                    && step.input().path("phase").asText().equals("select_v2")).count());
            assertEquals(approvals, steps.stream().filter(step -> step.kind() == AgentStep.Kind.TOOL
                    && step.input().path("tool").asText().startsWith("framework_context_")).count());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sys_file_read", "web_content"})
    void emptyLightSelectionStillAllowsAuthorizedFileAndWebDiscovery(String toolName) throws Exception {
        Path file = toolName.equals("sys_file_read")
                ? Files.createTempFile(Path.of("target"), "discovery-file-", ".txt")
                        .toAbsolutePath().normalize() : null;
        if (file != null) Files.writeString(file, "Observed file content through the host callback.");
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = emptyToolSelection();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).allowedToolNames(Set.of("framework_tool_catalog", toolName));
        if (file != null) config.systemFileRead();
        else config.webToolDescriptions(Map.of("web_content", "Read an authorized web page")).autoApproveWeb();
        ChatModel model = prompt -> switch (providerCalls.incrementAndGet()) {
            case 1 -> {
                assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                yield namedToolCallResponse("framework_tool_catalog",
                        "{\"action\":\"activate\",\"names\":[\"" + toolName + "\"]}", 2, 1);
            }
            case 2 -> {
                assertEquals(List.of(toolName), toolNames(prompt));
                yield namedToolCallResponse(toolName, file == null ? "{\"value\":7}"
                        : JsonNodeFactory.instance.objectNode().put("path", file.toString()).toString(), 2, 1);
            }
            case 3 -> {
                assertEquals(toolName, lastToolResponse(prompt).name());
                assertEquals("SUCCEEDED", toolEnvelope(prompt).path("status").asText());
                yield textResponse("已读取", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        };
        try (Fixture fixture = new Fixture(model, toolCallBudget(4), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("Read the authorized source"));
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(3, providerCalls.get());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.started")
                            && event.payload().path("tool").asText().equals(toolName)).count());
        } finally {
            if (file != null) Files.deleteIfExists(file);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "zero_budget", "one_budget", "catalog_denied", "no_authorized_tools"})
    void emptyLightSelectionDoesNotBypassDiscoveryAdmission(String gate) throws Exception {
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(emptyToolSelection());
        if (gate.equals("catalog_denied")) config.allowedToolNames(Set.of("code_target"));
        if (gate.equals("no_authorized_tools")) config.allowedToolNames(Set.of());
        Map<String, com.fasterxml.jackson.databind.JsonNode> attributes = gate.equals("disabled")
                ? Map.of("framework.disableTools", JsonNodeFactory.instance.booleanNode(true)) : Map.of();
        RunBudget budget = toolCallBudget(gate.equals("zero_budget") ? 0 : gate.equals("one_budget") ? 1 : 4);
        AtomicInteger providerCalls = new AtomicInteger();
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertEquals(List.of(HarnessDecisionToolCallback.NAME), allToolNames(prompt));
            return namedToolCallResponse(HarnessDecisionToolCallback.NAME,
                    "{\"decision\":\"BLOCKED\",\"userMessage\":\"当前无法发现业务工具\","
                            + "\"evidenceRefs\":[],\"unmetCriterionIds\":[]}", 2, 1);
        }, budget, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("Find an authorized tool", attributes));
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, providerCalls.get());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.tool.started")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unauthorized", "schema", "slots"})
    void discoveryFallbackStillRejectsUnauthorizedOrOverBudgetActivation(String gate) throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1)
                        .put("maxToolSchemaCharacters", 4_000))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(emptyToolSelection()).codeCalls(codeCalls);
        if (gate.equals("unauthorized")) config.allowedToolNames(Set.of("framework_tool_catalog", "code_target"));
        if (gate.equals("schema")) config.codeDescriptionPadding(4_500);
        ChatModel model = prompt -> switch (providerCalls.incrementAndGet()) {
            case 1 -> {
                assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                String arguments = switch (gate) {
                    case "unauthorized" -> "{\"action\":\"activate\",\"names\":[\"test_mutate\"]}";
                    case "schema" -> "{\"action\":\"activate\",\"names\":[\"code_target\"]}";
                    default -> "{\"action\":\"activate\",\"names\":[\"code_target\",\"test_mutate\"]}";
                };
                yield namedToolCallResponse("framework_tool_catalog", arguments, 2, 1);
            }
            case 2 -> {
                assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                var data = toolEnvelope(prompt).path("data");
                assertFalse(data.path("success").asBoolean(true));
                String error = data.path("error").asText();
                assertTrue(error.contains(gate.equals("unauthorized") ? "unauthorized"
                        : gate.equals("schema") ? "schema" : "tool count budget"), error);
                yield namedToolCallResponse(HarnessDecisionToolCallback.NAME,
                        "{\"decision\":\"BLOCKED\",\"userMessage\":\"工具激活未通过当前约束\","
                                + "\"evidenceRefs\":[],\"unmetCriterionIds\":[]}", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        };
        AtomicInteger mutations = new AtomicInteger();
        try (Fixture fixture = new Fixture(model, toolCallBudget(4), mutations, config)) {
            RunHandle handle = fixture.engine.start(fixture.request("Discover the requested tool"));
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, providerCalls.get());
            assertEquals(0, codeCalls.get());
            assertEquals(0, mutations.get());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.started"))
                    .allMatch(event -> event.payload().path("tool").asText().equals("framework_tool_catalog")));
        }
    }

    private static ModelTaskGateway emptyToolSelection() {
        return request -> {
            assertEquals("context.on_demand.select_v2", request.purpose());
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches");
            selection.putArray("historyIds");
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
    }

    @Test
    void unobservedDesktopSettingsAreNotPersistedAsCompletedWork() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        FixtureConfig config = new FixtureConfig()
                .simulatedDesktopSession(new AtomicInteger(), new AtomicInteger())
                .desktopToolDescriptions(Map.of("desktop_session_targets", "list visible windows"))
                .autoApproveDesktop().harness();
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1 -> {
                assertTrue(toolNames(prompt).contains("desktop_session_targets"));
                yield namedToolCallResponse("desktop_session_targets", "{}", 2, 1);
            }
            case 2, 3 -> textResponse("示例应用 主窗口现在已经在前台正常显示，我已经切到设置页看过。"
                    + "设置列表如下：虚构设置。", 2, 1);
            default -> throw new AssertionError("unexpected provider call");
        };
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED,
                new AtomicInteger(), config)) {
            TaskContractV3 contract = new TaskContractV3(3, "去 app 中心打开 示例应用 查看设置", List.of(
                    new TaskCriterionV3("settings", "观察设置页面", "desktop.observe",
                            CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "示例应用",
                            EffectReceiptV1.Status.OBSERVED, "设置")), true, true, "definition");
            RunHandle handle = fixture.engine.start(fixture.request("去 app 中心打开 示例应用 查看设置",
                    Map.of(TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(contract))));
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state(), snapshot.error());
            String reply = snapshot.output().path("text").asText();
            assertTrue(reply.contains("任务尚未验证完成"), reply);
            assertFalse(reply.contains("虚构设置"), reply);
            assertEquals(reply, fixture.engine.get(handle.id()).output().path("text").asText());
            assertEquals(3, modelCalls.get());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.task.repair_requested"))
                    .count() <= 1,
                    "missing target-page evidence permits at most one attempted repair here");
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .anyMatch(event -> event.type().equals("core.task.stop")
                            && !event.payload().path("reasonCode").asText().isBlank()),
                    "the paused result must record a structured stop reason");
            assertEquals(TaskOutcome.UNVERIFIED,
                    fixture.engine.taskResult(handle.id()).orElseThrow().outcome());
        }
    }

    @Test
    void catalogPagesStayWithinTheResultBudgetAndExposeEveryAuthorizedName() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger pageNumber = new AtomicInteger(1);
        AtomicInteger codeCalls = new AtomicInteger();
        List<String> listed = new ArrayList<>();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            providerCalls.incrementAndGet();
            if (providerCalls.get() == 1) {
                assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                return namedToolCallResponse("framework_tool_catalog",
                        "{\"action\":\"list\",\"page\":1}", 2, 1);
            }
            var response = lastToolResponse(prompt);
            if (response.name().equals("code_target")) {
                assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                return textResponse("done", 2, 1);
            }
            com.fasterxml.jackson.databind.JsonNode output;
            try {
                var envelope = json.readTree(response.responseData());
                assertEquals("SUCCEEDED", envelope.path("status").asText());
                output = envelope.path("data");
            } catch (Exception failure) {
                throw new IllegalStateException("invalid catalog output", failure);
            }
            if (output.path("action").asText().equals("activate")) {
                assertEquals(List.of("code_target"), toolNames(prompt));
                return namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
            }
            assertEquals("list", output.path("action").asText());
            assertTrue(output.toString().length() <= 1_000);
            assertEquals(pageNumber.get(), output.path("page").asInt());
            assertEquals(output.path("tools").size(), output.path("pageSize").asInt());
            assertTrue(output.path("pageSize").asInt() > 0);
            output.path("tools").forEach(tool -> listed.add(tool.path("name").asText()));
            if (output.path("hasNext").asBoolean()) {
                return namedToolCallResponse("framework_tool_catalog",
                        "{\"action\":\"list\",\"page\":" + pageNumber.incrementAndGet() + "}", 2, 1);
            }
            assertEquals(72, listed.size());
            assertEquals(72, Set.copyOf(listed).size());
            assertTrue(listed.contains("code_target"));
            return namedToolCallResponse("framework_tool_catalog",
                    "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 1_000, 1))
                .fillerTools(70).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.COMPLETED, awaitCompletion(fixture, handle).state());
            assertTrue(pageNumber.get() > 1);
            assertEquals(1, codeCalls.get());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void catalogActivationSurvivesRestartBeforeTheTargetProviderCall() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> {
                    assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                    yield namedToolCallResponse("framework_tool_catalog",
                            "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
                }
                case 2 -> {
                    assertEquals(List.of("code_target"), toolNames(prompt));
                    assertTrue(lastToolResponse(prompt).responseData().contains("code_target"));
                    yield namedToolCallResponse("code_target", "{\"value\":3}", 2, 1);
                }
                case 3 -> {
                    assertEquals(List.of(), toolNames(prompt));
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .fillerTools(70).codeCalls(codeCalls)
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_ACTIVATION_ONCE);
        try (Fixture fixture = new Fixture(model, toolCallBudget(2), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            assertEquals(1, providerCalls.get());
            assertEquals(1, config.pauseAfterActivation.get());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .anyMatch(event -> event.type().equals("core.tool.completed")
                            && event.payload().path("tool").asText().equals("framework_tool_catalog")
                            && event.payload().path("output").path("activated").toString()
                                    .contains("code_target")));
            fixture.restart();
            RunHandle resumed = fixture.engine.resume(handle.id(),
                    new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));
            assertEquals(RunState.COMPLETED, awaitCompletion(fixture, resumed).state());
            assertEquals(3, providerCalls.get());
            assertEquals(1, codeCalls.get());
            assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.started")).count());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void completedCatalogStepRestoresActivationWithoutTheLaterToolEvent() throws Exception {
        for (int maximumCalls : List.of(2, 4)) {
            AtomicInteger providerCalls = new AtomicInteger();
            AtomicInteger codeCalls = new AtomicInteger();
            List<Prompt> delivered = new ArrayList<>();
            ChatModel model = prompt -> {
                delivered.add(prompt);
                return switch (providerCalls.incrementAndGet()) {
                    case 1 -> {
                        assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                        yield namedToolCallResponse("framework_tool_catalog",
                                "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
                    }
                    case 2 -> {
                        assertEquals(List.of("code_target"), toolNames(prompt));
                        yield namedToolCallResponse("code_target", "{\"value\":7}", 2, 1);
                    }
                    case 3 -> {
                        assertEquals(maximumCalls == 2 ? List.of()
                                : List.of("framework_tool_catalog"), toolNames(prompt));
                        assertEquals("code_target", lastToolResponse(prompt).name());
                        yield textResponse("done", 2, 1);
                    }
                    default -> throw new AssertionError("unexpected provider call");
                };
            };
            FixtureConfig config = new FixtureConfig()
                    .context(contextConfiguration(true, 48_000, 16_000, 1))
                    .fillerTools(70).codeCalls(codeCalls)
                    .downstreamMutation(DownstreamMutation.PAUSE_AFTER_ACTIVATION_ONCE);
            try (Fixture fixture = new Fixture(model, toolCallBudget(maximumCalls),
                    new AtomicInteger(), config)) {
                RunHandle handle = fixture.engine.start(fixture.request());
                assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
                AgentStep activation = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                        .filter(step -> step.kind() == AgentStep.Kind.TOOL)
                        .findFirst().orElseThrow();
                assertEquals("framework_tool_catalog",
                        new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                                .filter(step -> step.id().equals(activation.id()))
                                .findFirst().orElseThrow().input().path("tool").asText());
                assertTrue(activation.output().path("rawOutput")
                        .path("activated").toString().contains("code_target"));
                long laterToolEvent = fixture.runs.eventsAfter(handle.id(), 0).stream()
                        .filter(event -> event.type().equals("core.tool.completed")
                                && event.payload().path("tool").asText()
                                        .equals("framework_tool_catalog"))
                        .findFirst().orElseThrow().sequence();
                assertTrue(laterToolEvent > activation.lastSequence());
                fixture.jdbc.update("DELETE FROM agent_run_events WHERE run_id=? AND event_sequence=?",
                        handle.id().value(), laterToolEvent);

                fixture.restart();
                RunHandle resumed = fixture.engine.resume(handle.id(),
                        new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));
                RunOutcome outcome = awaitCompletion(fixture, resumed);

                assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
                assertEquals(3, providerCalls.get());
                assertEquals(1, codeCalls.get());
                assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                        .filter(event -> event.type().equals("core.tool.started")).count());
                assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
            }
        }
    }

    @Test
    void unauthorizedToolsCannotBeListedOrActivated() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (calls.incrementAndGet()) {
                case 1 -> {
                    assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                    yield namedToolCallResponse("framework_tool_catalog",
                            "{\"action\":\"list\",\"query\":\"code_target\"}", 2, 1);
                }
                case 2 -> {
                    assertFalse(lastToolResponse(prompt).responseData().contains("code_target"));
                    yield namedToolCallResponse("framework_tool_catalog",
                            "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
                }
                case 3 -> {
                    assertTrue(lastToolResponse(prompt).responseData().contains("false"));
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .fillerTools(70).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.requestWithAllowedGroups("test", "filler"));
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state());
            assertEquals(3, calls.get());
            assertEquals(0, codeCalls.get());
            assertTrue(delivered.stream().noneMatch(prompt -> toolNames(prompt).contains("code_target")));
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.tool.completed")
                            && event.payload().path("output").path("activated").toString()
                                    .contains("code_target")));
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void aSingleAuthorizedToolIsShownDirectlyWhenItsSchemaFits() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        ChatModel model = prompt -> switch (calls.incrementAndGet()) {
            case 1 -> {
                assertEquals(List.of("code_target"), toolNames(prompt));
                yield namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
            }
            case 2 -> {
                assertEquals("code_target", lastToolResponse(prompt).name());
                yield textResponse("done", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 1_000, 1))
                .denyTestGroup().codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.COMPLETED, awaitCompletion(fixture, handle).state());
            assertEquals(2, calls.get());
            assertEquals(1, codeCalls.get());
        }
    }

    @Test
    void aNameAllowlistCanUseTheDirectPathWithoutAuthorizingTheCatalog() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> {
                    assertEquals(List.of("code_target"), toolNames(prompt));
                    yield namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
                }
                case 2 -> {
                    assertEquals(List.of(), toolNames(prompt));
                    assertEquals("code_target", lastToolResponse(prompt).name());
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 1_000, 1))
                .allowedToolNames(Set.of("code_target")).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(model, toolCallBudget(1), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.COMPLETED, awaitCompletion(fixture, handle).state());
            assertEquals(2, providerCalls.get());
            assertEquals(1, codeCalls.get());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.started")).count());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void anAllowlistThatDeniesTheRequiredCatalogFailsBeforeProvider() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        Set<String> allowed = new java.util.LinkedHashSet<>();
        allowed.add("test_mutate");
        allowed.add("code_target");
        for (int index = 0; index < 70; index++) allowed.add("filler_%03d".formatted(index));
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 1_000, 1))
                .fillerTools(70).allowedToolNames(allowed);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.FAILED, outcome.state());
            assertEquals(0, providerCalls.get());
            assertTrue(outcome.error().contains("framework_tool_catalog"));
        }
    }

    @Test
    void catalogNeedsTwoRemainingCallsAndOneStillAllowsAControlDecision() throws Exception {
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 1_000, 1))
                .fillerTools(70);
        AtomicInteger insufficientProviderCalls = new AtomicInteger();
        try (Fixture fixture = new Fixture(prompt -> {
            insufficientProviderCalls.incrementAndGet();
            assertEquals(List.of(), toolNames(prompt));
            assertEquals(List.of(HarnessDecisionToolCallback.NAME), allToolNames(prompt));
            return textResponse("catalog requires another business call", 1, 1);
        }, toolCallBudget(1), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, insufficientProviderCalls.get());
            assertEquals(0, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.started")).count());
        }

        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        FixtureConfig sufficient = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 1_000, 1))
                .fillerTools(70).codeCalls(codeCalls);
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> {
                    assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                    yield namedToolCallResponse("framework_tool_catalog",
                            "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
                }
                case 2 -> {
                    assertEquals(List.of("code_target"), toolNames(prompt));
                    yield namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
                }
                case 3 -> {
                    assertEquals(List.of(), toolNames(prompt));
                    assertEquals("code_target", lastToolResponse(prompt).name());
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        for (int limit : List.of(2, 3)) {
            providerCalls.set(0);
            codeCalls.set(0);
            delivered.clear();
            try (Fixture fixture = new Fixture(model, toolCallBudget(limit), new AtomicInteger(), sufficient)) {
                RunHandle handle = fixture.engine.start(fixture.request());
                assertEquals(RunState.COMPLETED, awaitCompletion(fixture, handle).state());
                assertEquals(3, providerCalls.get());
                assertEquals(1, codeCalls.get());
                assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                        .filter(event -> event.type().equals("core.tool.started")).count());
                assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
            }
        }
    }

    @Test
    void anotherToolInTheActivationResponseDoesNotConsumeTheNextCallActivation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (calls.incrementAndGet()) {
                case 1 -> {
                    assertTrue(toolNames(prompt).contains("framework_tool_catalog"));
                    assertTrue(toolNames(prompt).contains("test_mutate"));
                    assertFalse(toolNames(prompt).contains("code_target"));
                    AssistantMessage output = AssistantMessage.builder().content("")
                            .toolCalls(List.of(
                                    new AssistantMessage.ToolCall("activate", "function",
                                            "framework_tool_catalog",
                                            "{\"action\":\"activate\",\"names\":[\"code_target\"]}"),
                                    new AssistantMessage.ToolCall("mutate", "function",
                                            "test_mutate", "{\"value\":1}")))
                            .build();
                    yield response(output, 2, 1);
                }
                case 2 -> {
                    assertTrue(toolNames(prompt).contains("code_target"));
                    yield namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
                }
                case 3 -> textResponse("done", 2, 1);
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 1_000, 3))
                .autoApproveTestMutate().fillerTools(70).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.COMPLETED, awaitCompletion(fixture, handle).state());
            assertEquals(3, calls.get());
            assertEquals(1, codeCalls.get());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void largeToolResultIsSummarizedOnlyForTheModelAndExecutesOnce() throws Exception {
        String fullResult = "result-" + "x".repeat(12_000);
        AtomicInteger codeCalls = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (calls.incrementAndGet()) {
                case 1 -> namedToolCallResponse("framework_tool_catalog",
                        "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
                case 2 -> namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
                case 3 -> {
                    ToolResponseMessage.ToolResponse latest = lastToolResponse(prompt);
                    assertEquals("code_target", latest.name());
                    assertEquals("provider-call", latest.id());
                    assertTrue(latest.responseData().contains("工具已执行"));
                    assertEquals("SUCCEEDED", toolEnvelope(prompt).path("status").asText());
                    assertTrue(toolEnvelope(prompt).path("data").toString().length() <= 1_000);
                    assertFalse(latest.responseData().contains(fullResult));
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 4_000, 1_000, 1))
                .codeResult(fullResult).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.COMPLETED, awaitCompletion(fixture, handle).state());
            assertEquals(3, calls.get());
            assertEquals(1, codeCalls.get());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .anyMatch(event -> event.type().equals("core.tool.completed")
                            && event.payload().path("tool").asText().equals("code_target")
                            && event.payload().path("output").path("payload").asText()
                                    .equals(fullResult)));
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void requiredTaskThatDoesNotFitStopsBeforeCallingProvider() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 4_000, 1_000, 1));
        try (Fixture fixture = new Fixture(prompt -> {
            calls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("task-" + "x".repeat(5_000)));
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.FAILED, outcome.state());
            assertEquals(0, calls.get());
            assertTrue(outcome.error().contains(LocalContextBudgetExceededException.CODE));
        }
    }

    @Test
    void disabledCompactionKeepsTheCompleteToolCatalogAndResult() throws Exception {
        String fullResult = "untrimmed-" + "x".repeat(12_000);
        AtomicInteger calls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (calls.incrementAndGet()) {
                case 1 -> {
                    assertTrue(toolNames(prompt).size() > 64);
                    assertTrue(toolNames(prompt).contains("code_target"));
                    assertFalse(toolNames(prompt).contains("framework_tool_catalog"));
                    yield namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
                }
                case 2 -> {
                    assertTrue(lastToolResponse(prompt).responseData().contains(fullResult));
                    assertTrue(toolNames(prompt).size() > 64);
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(false, 4_000, 1_000, 1))
                .fillerTools(70).codeResult(fullResult);
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.COMPLETED, awaitCompletion(fixture, handle).state());
            assertEquals(2, calls.get());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void downstreamAdvisorCannotDropTheLatestToolResponse() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 4_000, 1_000, 2))
                .autoApproveTestMutate()
                .downstreamMutation(DownstreamMutation.DROP_TOOL_RESPONSE);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return toolCallResponse(2, 1);
        }, RunBudget.UNBOUNDED, toolCalls, config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.FAILED, outcome.state());
            assertEquals(1, providerCalls.get());
            assertEquals(1, toolCalls.get());
        }
    }

    @Test
    void downstreamAdvisorCannotAddContextBeyondTheProviderBudget() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 4_000, 1_000, 2))
                .downstreamMutation(DownstreamMutation.ADD_OVERSIZE_USER);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.FAILED, outcome.state());
            assertEquals(0, providerCalls.get());
            assertTrue(outcome.error().contains(LocalContextBudgetExceededException.CODE));
        }
    }

    @Test
    void originalTaskSurvivesMultipleApprovalResumesAndRestart() throws Exception {
        String originalTask = "remember-this-original-task-" + "z".repeat(1_000);
        AtomicInteger calls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            assertTrue(prompt.getInstructions().stream()
                    .filter(UserMessage.class::isInstance)
                    .anyMatch(message -> message.getText().contains(originalTask)));
            int number = calls.incrementAndGet();
            return number <= 2
                    ? namedToolCallResponse("test_mutate", "{\"value\":" + number + "}", 2, 1)
                    : textResponse("done", 2, 1);
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 4_000, 1_000, 2));
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request(originalTask));
            for (int number = 1; number <= 2; number++) {
                assertEquals(RunState.WAITING_APPROVAL, fixture.engine.get(handle.id()).state());
                if (number == 2) fixture.restart();
                var approval = JsonNodeFactory.instance.objectNode().put("approved", true)
                        .put("fingerprint", ToolInvocationFingerprint.create("test_mutate",
                                JsonNodeFactory.instance.objectNode().put("value", number)));
                handle = fixture.engine.resume(handle.id(), new ResumeCommand("tool.approval", approval));
            }
            assertEquals(RunState.COMPLETED, awaitCompletion(fixture, handle).state());
            assertEquals(3, calls.get());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void toolCallsUseTheConfiguredObservationRegistry() throws Exception {
        AtomicInteger observations = new AtomicInteger();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<ToolCallingObservationContext>() {
            @Override public boolean supportsContext(Observation.Context context) {
                return context instanceof ToolCallingObservationContext;
            }
            @Override public void onStop(ToolCallingObservationContext context) {
                observations.incrementAndGet();
            }
        });
        AtomicInteger modelCalls = new AtomicInteger();
        FixtureConfig config = new FixtureConfig().observations(registry).autoApproveTestMutate();
        try (Fixture fixture = new Fixture(prompt -> modelCalls.incrementAndGet() == 1
                ? toolCallResponse(2, 1) : textResponse("done", 2, 1),
                RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.COMPLETED, awaitCompletion(fixture, handle).state());
            assertEquals(2, modelCalls.get());
            assertEquals(2, observations.get(),
                    "the business call and the independent decision call are both observed");
        }
    }

    @Test
    void 按需计划直接选择目标工具且只使用一次共享工具预算() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ModelTaskGateway planner = request -> {
            assertEquals(ModelTier.LIGHT, request.tier());
            assertEquals(0, request.maxRetries());
            assertFalse(request.cacheAllowed());
            int number = plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, number == 1 ? "code_target" : "",
                        number == 1 ? "code" : "");
            } else {
                assertEquals("context.on_demand.refine_v2", request.purpose());
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "code_target");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        ChatModel model = prompt -> {
            delivered.add(prompt);
            int number = providerCalls.incrementAndGet();
            assertFalse(prompt.getInstructions().stream().anyMatch(message ->
                    message.getText() != null
                            && message.getText().contains("IRRELEVANT_HISTORY_MARKER")));
            return switch (number) {
                case 1 -> {
                    assertEquals(List.of("code_target"), toolNames(prompt));
                    yield namedToolCallResponse("code_target", "{\"value\":42}", 2, 1);
                }
                case 2 -> {
                    assertEquals(List.of(), toolNames(prompt));
                    assertEquals("code_target", lastToolResponse(prompt).name());
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected recursive provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).fillerTools(70).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(model, toolCallBudget(1),
                new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(
                    InputBlock.message("user", "IRRELEVANT_HISTORY_MARKER"),
                    InputBlock.message("assistant", "unrelated reply"),
                    InputBlock.text("Run code_target once")), Map.of());
            RunHandle handle = fixture.engine.start(request);

            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, plannerCalls.get(),
                    "the control-only final step does not invoke the tool selector");
            assertEquals(2, providerCalls.get());
            assertEquals(1, codeCalls.get());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.completed")).count());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("select_v2"))
                    .count());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 旅行请求误写web_search只能检索并由候选ID映射真实工具() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                assertEquals(0, request.input().path("tools").size(),
                        "the first stage must expose groups rather than names");
                assertTrue(request.input().path("toolGroups").toString().contains("code"));
                assertEquals(142, java.util.stream.StreamSupport.stream(
                                request.input().path("toolGroups").spliterator(), false)
                        .mapToInt(group -> group.path("count").asInt()).sum());
                assertFalse(request.outputSchema().toString().contains("toolNames"));
                selection.putArray("searches");
                toolIntent(selection, "web_search", "code");
            } else {
                assertEquals("context.on_demand.refine_v2", request.purpose());
                assertEquals(1, request.input().path("toolCandidates").size());
                assertEquals("code_target", request.input().path("toolCandidates")
                        .get(0).path("name").asText());
                assertFalse(request.outputSchema().toString().contains("toolNames"));
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "code_target");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).fillerTools(140).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertEquals(List.of("code_target"), toolNames(prompt));
            assertFalse(toolNames(prompt).contains("web_search"));
            return textResponse("done", 2, 1);
        }, toolCallBudget(1), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("计划中秋节的游玩地方"));
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, plannerCalls.get());
            assertEquals(1, providerCalls.get());
            assertEquals(0, codeCalls.get());
            assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL)
                    .allMatch(step -> !step.input().path("toolNames").toString()
                            .contains("web_search")));
        }
    }

    @Test
    void 普通站点密码说明不会中断工具候选检索() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, "site", "web");
            } else {
                assertEquals("context.on_demand.refine_v2", request.purpose());
                assertEquals(Set.of("test_site_login_now", "test_site_fill_password"),
                        java.util.stream.StreamSupport.stream(
                                request.input().path("toolCandidates").spliterator(), false)
                                .map(candidate -> candidate.path("name").asText())
                                .collect(java.util.stream.Collectors.toSet()));
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "test_site_login_now");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner)
                .webToolDescriptions(Map.of(
                        "test_site_login_now", "密码：工具内部仅在站点登录时填写已保存的凭据",
                        "test_site_fill_password", "密码由站点凭据工具管理，不会写入普通日志"));
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertEquals(List.of("test_site_login_now"), toolNames(prompt));
            return textResponse("中秋攻略", 2, 1);
        }, toolCallBudget(1), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("给我中秋节的攻略"));
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, plannerCalls.get());
            assertEquals(1, providerCalls.get());
            AgentStep candidateStep = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("tool_search_v2"))
                    .findFirst().orElseThrow();
            assertEquals(AgentStep.State.COMPLETED, candidateStep.state());
            assertEquals(2, candidateStep.output().path("candidates").size());
            candidateStep.output().path("candidates").forEach(candidate ->
                    assertEquals("<敏感内容已隐藏>", candidate.path("summary").asText()));
            var completion = fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.step.completed")
                            && event.payload().path("stepId").asText()
                                    .equals(candidateStep.id().value()))
                    .findFirst().orElseThrow();
            assertFalse(completion.payload().path("credentialRedacted").asBoolean());
        }
    }

    @Test
    void 伪造候选ID纠错一次后在主模型和业务工具前暂停() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("historyIds");
                selection.putArray("searches");
                toolIntent(selection, "code_target", "code");
            } else {
                if (request.purpose().endsWith(".refine_v2")) {
                    selection.putArray("historyIds");
                    selection.putArray("sourceIds");
                } else {
                    assertEquals("context.on_demand.repair_refine_tools_v2", request.purpose());
                }
                selection.put("toolAction", "direct");
                selection.putArray("toolIds").add("web_search");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("find a code tool"));
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals(3, plannerCalls.get(), "one select, one refine, one repair");
            assertEquals(0, providerCalls.get());
            assertEquals(0, codeCalls.get());
            assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .noneMatch(step -> step.kind() == AgentStep.Kind.MODEL));
        }
    }

    @Test
    void 重复候选ID纠错后仍不会进入主模型() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                selection.putArray("historyIds");
                toolIntent(selection, "code_target", "code");
            } else {
                String id = request.input().path("toolCandidates").get(0)
                        .path("id").asText();
                if (request.purpose().endsWith(".refine_v2")) {
                    selection.putArray("historyIds");
                    selection.putArray("sourceIds");
                } else {
                    assertEquals("context.on_demand.repair_refine_tools_v2", request.purpose());
                }
                selection.put("toolAction", "direct");
                selection.putArray("toolIds").add(id).add(id);
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("run code"));
            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(3, plannerCalls.get());
            assertEquals(0, providerCalls.get());
        }
    }

    @Test
    void 工具检索零命中时只展示目录且检查目录许可与两次调用额度() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            boolean catalogDenied = scenario == 1;
            boolean budgetShort = scenario == 2;
            AtomicInteger plannerCalls = new AtomicInteger();
            AtomicInteger providerCalls = new AtomicInteger();
            ModelTaskGateway planner = request -> {
                plannerCalls.incrementAndGet();
                assertEquals("context.on_demand.select_v2", request.purpose());
                ObjectNode selection = JsonNodeFactory.instance.objectNode();
                selection.putArray("searches");
                selection.putArray("historyIds");
                toolIntent(selection, "__unfindable_tool__");
                return CompletableFuture.completedFuture(new ModelTaskResult(
                        selection, "planner", 1, 1, false, Map.of()));
            };
            FixtureConfig config = new FixtureConfig()
                    .context(contextConfiguration(true, 48_000, 16_000, 1))
                    .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                    .planner(planner);
            if (catalogDenied) config.allowedToolNames(Set.of("code_target"));
            try (Fixture fixture = new Fixture(prompt -> {
                providerCalls.incrementAndGet();
                assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                return textResponse("done", 2, 1);
            }, budgetShort ? toolCallBudget(1) : toolCallBudget(2),
                    new AtomicInteger(), config)) {
                RunHandle handle = fixture.engine.start(fixture.request("find a missing tool"));
                RunSnapshot snapshot = fixture.engine.get(handle.id());
                if (catalogDenied || budgetShort) {
                    assertEquals(RunState.PAUSED, snapshot.state());
                    assertEquals(0, providerCalls.get());
                } else {
                    assertEquals(RunState.COMPLETED, snapshot.state());
                    assertEquals(1, providerCalls.get());
                }
                assertEquals(1, plannerCalls.get(), "zero matches need no refine");
            }
        }
    }

    @Test
    void 工具额度为零仍可选择无工具收尾() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            assertEquals("context.on_demand.select_v2", request.purpose());
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches");
            selection.putArray("historyIds");
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertEquals(List.of(), toolNames(prompt));
            return textResponse("done", 2, 1);
        }, toolCallBudget(0), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("answer without tools"));
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(0, plannerCalls.get(),
                    "zero business budget offers the control callback directly");
            assertEquals(1, providerCalls.get());
        }
    }

    @Test
    void 按需规划选空时仍拒绝未提供的WebContent并保留目录发现() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            assertEquals("context.on_demand.select_v2", request.purpose());
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches");
            selection.putArray("historyIds");
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner);
        ChatModel model = prompt -> {
            delivered.add(prompt);
            assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> namedToolCallResponse("web_content", "{}", 2, 1);
                case 2 -> {
                    ToolResponseMessage.ToolResponse feedback = lastToolResponse(prompt);
                    assertEquals("web_content", feedback.name());
                    assertTrue(feedback.responseData().contains("web_content"));
                    yield textResponse("上海", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        try (Fixture fixture = new Fixture(model, toolCallBudget(2), toolCalls, config)) {
            RunHandle handle = fixture.engine.start(fixture.request("上海"));
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals("上海", outcome.output().path("text").asText());
            assertTrue(plannerCalls.get() >= 1);
            assertEquals(2, providerCalls.get());
            assertEquals(0, toolCalls.get());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 单工具槽激活后先兑现A再允许后续规划选择B() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                if (providerCalls.get() == 0) {
                    toolIntent(selection, "__no_matching_tool__");
                } else {
                    // The activated A must be shown once even when the next plan asks for B.
                    toolIntent(selection, "test_mutate", "test");
                    if (providerCalls.get() == 1) {
                        assertTrue(request.input().path("activatedTools").toString()
                                .contains("code_target"));
                    }
                }
            } else {
                assertEquals(2, providerCalls.get());
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "test_mutate");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(prompt -> switch (providerCalls.incrementAndGet()) {
            case 1 -> {
                assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                yield namedToolCallResponse("framework_tool_catalog",
                        "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
            }
            case 2 -> {
                assertEquals(List.of("code_target"), toolNames(prompt));
                yield namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
            }
            case 3 -> {
                assertEquals(List.of("test_mutate"), toolNames(prompt));
                yield textResponse("done", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        }, toolCallBudget(3), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("activate code, then inspect mutate"));
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(3, providerCalls.get());
            assertEquals(4, plannerCalls.get());
            assertEquals(1, codeCalls.get());
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void 已激活工具与目录有容量时可继续调用目录() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            selection.putArray("searches");
            toolIntent(selection, providerCalls.get() < 2 ? "__no_matching_tool__" : "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 2))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner);
        try (Fixture fixture = new Fixture(prompt -> switch (providerCalls.incrementAndGet()) {
            case 1 -> {
                assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                yield namedToolCallResponse("framework_tool_catalog",
                        "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
            }
            case 2 -> {
                assertEquals(Set.of("code_target", "framework_tool_catalog"),
                        Set.copyOf(toolNames(prompt)));
                yield namedToolCallResponse("framework_tool_catalog",
                        "{\"action\":\"list\",\"query\":\"code_target\"}", 2, 1);
            }
            case 3 -> {
                assertEquals("framework_tool_catalog", lastToolResponse(prompt).name());
                assertTrue(lastToolResponse(prompt).responseData().contains("\"action\":\"list\""));
                yield textResponse("done", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        }, toolCallBudget(3), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("activate code, then inspect catalog"));
            RunOutcome outcome = handle.completion().toCompletableFuture()
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(3, providerCalls.get());
            assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.started")
                            && event.payload().path("tool").asText()
                                    .equals("framework_tool_catalog"))
                    .count());
            assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .noneMatch(step -> step.output() != null
                            && step.output().path("rejectedUnavailableToolBatch").asBoolean()));
        }
    }

    @Test
    void 候选总数为一时来源摘要不会挤掉工具候选ID() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Documents"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                return List.of(new DeferredContextCandidate(
                        "one", "v1", "source summary", PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                fetches.incrementAndGet();
                return "unexpected body";
            }
        };
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches").addObject()
                        .put("source", "docs").put("query", "summary");
                toolIntent(selection, "code_target", "code");
            } else {
                assertEquals(0, request.input().path("candidates").size());
                assertEquals(1, request.input().path("toolCandidates").size());
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "code_target");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true)
                        .put("candidates", 1))
                .planner(planner).source(source).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertEquals(List.of("code_target"), toolNames(prompt));
            return textResponse("done", 2, 1);
        }, toolCallBudget(2), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("inspect a tool and summary")),
                    Map.of(), PermissionSet.of("context.read", "tool.execute"));
            RunHandle handle = fixture.engine.start(request);
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, searches.get());
            assertEquals(0, fetches.get());
            assertEquals(1, providerCalls.get());
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void 按需计划只选部分工具时仍能通过目录发现其余授权工具() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            plannerCalls.incrementAndGet();
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                String target = providerCalls.get() == 0 ? "code_target"
                        : providerCalls.get() == 1 ? "filler_009" : "";
                toolIntent(selection, target, providerCalls.get() == 0 ? "code"
                        : providerCalls.get() == 1 ? "filler" : "");
            } else {
                selection.putArray("sourceIds");
                if (providerCalls.get() == 0) {
                    toolChoice(selection, request, "direct", "code_target");
                } else {
                    toolChoice(selection, request, "discover");
                }
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> {
                    assertEquals(List.of("code_target"), toolNames(prompt));
                    yield namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
                }
                case 2 -> {
                    assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                    assertEquals("code_target", lastToolResponse(prompt).name());
                    yield namedToolCallResponse("framework_tool_catalog",
                            "{\"action\":\"activate\",\"names\":[\"filler_009\"]}", 2, 1);
                }
                case 3 -> {
                    assertEquals(List.of("filler_009"), toolNames(prompt));
                    assertEquals("framework_tool_catalog", lastToolResponse(prompt).name());
                    yield namedToolCallResponse("filler_009", "{\"value\":9}", 2, 1);
                }
                case 4 -> {
                    assertEquals(List.of(), toolNames(prompt));
                    assertEquals("filler_009", lastToolResponse(prompt).name());
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 2))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).fillerTools(10);
        try (Fixture fixture = new Fixture(model, toolCallBudget(3),
                new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("use a hidden filler tool"));
            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertNotEquals(RunState.PAUSED, snapshot.state(), String.valueOf(snapshot.output()));
            RunOutcome outcome = handle.completion().toCompletableFuture()
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertTrue(plannerCalls.get() >= 4);
            assertEquals(4, providerCalls.get());
            assertEquals(3, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.started")).count());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID_JSON", "SCHEMA_MISMATCH"})
    @org.junit.jupiter.api.Timeout(30)
    void malformedAuxiliaryPlanningPreservesAuthorizedGroundedComputerUse(String reason)
            throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger observes = new AtomicInteger();
        AtomicInteger clicks = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ModelTaskGateway planner = request -> {
            assertTrue(Set.of(0, 1, 4, 6).contains(providerCalls.get()),
                    "mandatory open/observe stages must never call the auxiliary planner");
            plannerCalls.incrementAndGet();
            return CompletableFuture.failedFuture(new ModelTaskOutputException(
                    ModelTaskOutputException.Reason.valueOf(reason),
                    "model returned a malformed planning structure", null));
        };
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> {
                    assertEquals(List.of("framework_tool_catalog"), toolNames(prompt),
                            "bootstrap fallback discovers only the Run-authorized tools");
                    assertEquals("BOOTSTRAP", computerUseCursor(prompt).path("phase").asText());
                    yield namedToolCallResponse("framework_tool_catalog",
                            "{\"action\":\"activate\",\"names\":[\"desktop_session_launch_application\"]}", 2, 1);
                }
                case 2 -> {
                    assertTrue(toolNames(prompt).contains("desktop_session_launch_application"));
                    yield namedToolCallResponse("desktop_session_launch_application",
                            "{\"application\":\"示例应用\"}", 2, 1);
                }
                case 3 -> {
                    assertEquals(List.of("desktop_session_open"), toolNames(prompt));
                    assertEquals("OPEN_SESSION", computerUseCursor(prompt).path("phase").asText());
                    assertEquals("sample-target", computerUseCursor(prompt).path("targetId").asText());
                    yield namedToolCallResponse("desktop_session_open",
                            "{\"targetId\":\"sample-target\",\"control\":true}", 2, 1);
                }
                case 4 -> {
                    assertEquals(List.of("desktop_session_observe"), toolNames(prompt));
                    assertEquals("OBSERVE", computerUseCursor(prompt).path("phase").asText());
                    yield namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                }
                case 5 -> {
                    assertEquals(Set.of("desktop_session_click", "desktop_session_observe"),
                            Set.copyOf(toolNames(prompt)));
                    var cursor = computerUseCursor(prompt);
                    assertEquals("READY", cursor.path("phase").asText());
                    assertTrue(cursor.path("inputAllowed").asBoolean());
                    assertEquals("sample-session", cursor.path("sessionId").asText());
                    assertEquals("00000000-0000-4000-8000-000000000001",
                            cursor.path("observationId").asText());
                    assertFalse(cursor.path("evidenceRefs").isEmpty());
                    assertTrue(prompt.getInstructions().stream()
                            .filter(ToolResponseMessage.class::isInstance)
                            .map(ToolResponseMessage.class::cast)
                            .flatMap(responses -> responses.getResponses().stream())
                            .anyMatch(response -> response.name().equals("desktop_session_observe")
                                    && response.responseData().contains("设置标签")
                                    && response.responseData().contains("00000000-0000-4000-8000-000000000001:v7")),
                            "fallback must keep the complete trusted frame used to ground the input");
                    yield namedToolCallResponse("desktop_session_click",
                            "{\"sessionId\":\"sample-session\",\"observationId\":"
                                    + "\"00000000-0000-4000-8000-000000000001\","
                                    + "\"elementId\":\"00000000-0000-4000-8000-000000000001:v7\"}", 2, 1);
                }
                case 6 -> {
                    assertEquals(1, clicks.get());
                    assertEquals(List.of("desktop_session_observe"), toolNames(prompt));
                    assertFalse(computerUseCursor(prompt).path("inputAllowed").asBoolean());
                    yield namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                }
                case 7 -> {
                    assertEquals(2, observes.get());
                    assertEquals("READY", computerUseCursor(prompt).path("phase").asText());
                    assertTrue(lastToolResponse(prompt).responseData().contains("设置页面"));
                    yield textResponse("已取得设置页面的观察结果", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 2))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).simulatedDesktopLaunchOpenObserve(launches, opens, observes, clicks);
        try (Fixture fixture = new Fixture(model, toolCallBudget(7), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("打开 示例应用 查看设置"));
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(4, plannerCalls.get());
            assertEquals(7, providerCalls.get());
            assertEquals(1, launches.get());
            assertEquals(1, opens.get());
            assertEquals(2, observes.get());
            assertEquals(1, clicks.get());
            assertEquals(List.of("framework_tool_catalog", "desktop_session_launch_application",
                            "desktop_session_open", "desktop_session_observe", "desktop_session_click",
                            "desktop_session_observe"),
                    fixture.runs.eventsAfter(handle.id(), 0).stream()
                            .filter(event -> event.type().equals("core.tool.started"))
                            .map(event -> event.payload().path("tool").asText()).toList());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void readOnlyDesktopSessionUpgradesControlAndObservesBeforeAnyInput() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger observes = new AtomicInteger();
        AtomicInteger clicks = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ModelTaskGateway planner = desktopBootstrapPlanner(providerCalls, new AtomicInteger(),
                "desktop_session_open");
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> namedToolCallResponse("desktop_session_open",
                        "{\"targetId\":\"sample-target\",\"control\":false}", 2, 1);
                case 2 -> {
                    assertEquals(List.of("desktop_session_observe"), toolNames(prompt));
                    yield namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                }
                case 3 -> {
                    assertTrue(toolNames(prompt).contains("desktop_session_open"));
                    assertFalse(toolNames(prompt).contains("desktop_session_click"));
                    assertFalse(computerUseCursor(prompt).path("inputAllowed").asBoolean());
                    assertEquals(0, clicks.get(), "read-only navigation must never dispatch input");
                    yield namedToolCallResponse("desktop_session_open",
                            "{\"targetId\":\"sample-target\",\"control\":true}", 2, 1);
                }
                case 4 -> {
                    assertEquals(List.of("desktop_session_observe"), toolNames(prompt),
                            "control upgrade invalidates the pre-upgrade observation");
                    assertEquals("OBSERVE", computerUseCursor(prompt).path("phase").asText());
                    assertFalse(computerUseCursor(prompt).path("inputAllowed").asBoolean());
                    yield namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                }
                case 5 -> {
                    assertTrue(toolNames(prompt).contains("desktop_session_click"));
                    assertTrue(computerUseCursor(prompt).path("inputAllowed").asBoolean());
                    assertEquals(2, observes.get());
                    yield namedToolCallResponse("desktop_session_click",
                            "{\"sessionId\":\"sample-session\",\"observationId\":"
                                    + "\"00000000-0000-4000-8000-000000000002\","
                                    + "\"elementId\":\"00000000-0000-4000-8000-000000000002:v7\"}", 2, 1);
                }
                case 6 -> {
                    assertEquals(1, clicks.get());
                    assertEquals(List.of("desktop_session_observe"), toolNames(prompt));
                    yield namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                }
                case 7 -> textResponse("已取得设置页面的观察结果", 2, 1);
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 2))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).simulatedDesktopLaunchOpenObserve(new AtomicInteger(), opens,
                        observes, clicks);
        try (Fixture fixture = new Fixture(model, toolCallBudget(6), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("打开 示例应用 查看设置"));
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, opens.get());
            assertEquals(3, observes.get());
            assertEquals(1, clicks.get());
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertFalse(events.stream().anyMatch(event -> event.type().equals("core.effect.observation_required")));
            assertEquals(List.of("false", "true"), events.stream()
                    .filter(event -> event.type().equals("core.tool.receipt")
                            && event.payload().path("tool").asText().equals("desktop_session_open"))
                    .map(event -> event.payload().path("metadata").path("controlGranted").asText()).toList());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void unknownDesktopDeliveryRequiresFreshObservationBeforeSubsequentInput() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger observes = new AtomicInteger();
        AtomicInteger clicks = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ModelTaskGateway planner = desktopBootstrapPlanner(providerCalls, plannerCalls,
                "desktop_session_open");
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> namedToolCallResponse("desktop_session_open",
                        "{\"targetId\":\"sample-target\",\"control\":true}", 2, 1);
                case 2 -> namedToolCallResponse("desktop_session_observe",
                        "{\"sessionId\":\"sample-session\"}", 2, 1);
                case 3 -> namedToolCallResponse("desktop_session_click",
                        "{\"sessionId\":\"sample-session\",\"observationId\":"
                                + "\"00000000-0000-4000-8000-000000000001\","
                                + "\"elementId\":\"00000000-0000-4000-8000-000000000001:v7\"}", 2, 1);
                case 4 -> {
                    assertEquals(List.of("desktop_session_observe"), toolNames(prompt));
                    assertFalse(computerUseCursor(prompt).path("pendingInvocationIds").isEmpty());
                    yield namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                }
                case 5 -> {
                    assertEquals(2, observes.get());
                    assertEquals(Set.of("desktop_session_observe", "desktop_session_click"),
                            Set.copyOf(toolNames(prompt)));
                    var cursor = computerUseCursor(prompt);
                    assertEquals("READY", cursor.path("phase").asText());
                    assertTrue(cursor.path("inputAllowed").asBoolean());
                    assertEquals(1, cursor.path("pendingInvocationIds").size());
                    assertEquals(1, cursor.path("observedPendingInvocationIds").size(),
                            "baseline refresh preserves UNKNOWN rather than claiming SATISFIED");
                    yield namedToolCallResponse("desktop_session_click",
                            "{\"sessionId\":\"sample-session\",\"observationId\":"
                                    + "\"00000000-0000-4000-8000-000000000002\","
                                    + "\"elementId\":\"00000000-0000-4000-8000-000000000002:v7\"}", 2, 1);
                }
                case 6 -> {
                    assertEquals(2, clicks.get(), "a paired fresh baseline grounds a subsequent input");
                    assertEquals("OBSERVE", computerUseCursor(prompt).path("phase").asText());
                    assertFalse(toolNames(prompt).contains("desktop_session_click"));
                    assertEquals(2, computerUseCursor(prompt).path("pendingInvocationIds").size());
                    yield namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                }
                case 7 -> {
                    assertEquals("READY", computerUseCursor(prompt).path("phase").asText());
                    assertEquals(2, computerUseCursor(prompt).path("pendingInvocationIds").size());
                    assertEquals(2, computerUseCursor(prompt).path("observedPendingInvocationIds").size());
                    yield textResponse("点击的投递结果尚未确认", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 2))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).simulatedDesktopLaunchOpenObserve(new AtomicInteger(), opens,
                        observes, clicks).simulatedUnknownDesktopDelivery();
        try (Fixture fixture = new Fixture(model, toolCallBudget(6), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("打开 示例应用 查看设置"));
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, opens.get());
            assertEquals(2, clicks.get());
            assertEquals(3, observes.get());
            assertTrue(plannerCalls.get() >= 4);
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertEquals(2, events.stream().filter(event -> event.type().equals("core.tool.started")
                    && event.payload().path("tool").asText().equals("desktop_session_click")).count());
            assertTrue(events.stream().anyMatch(event -> event.type().equals("core.tool.receipt")
                    && event.payload().path("tool").asText().equals("desktop_session_click")
                    && event.payload().path("status").asText().equals("UNKNOWN")
                    && event.payload().path("metadata").path("delivery").asText().equals("MAYBE_SENT")));
            assertFalse(events.stream().anyMatch(event -> event.type().equals("core.effect.reconciled")),
                    "observing a window is not business-effect reconciliation");
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void malformedPlanningFallbackPreservesCatalogDenialAndDiscoveryBudget(boolean catalogDenied)
            throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger observes = new AtomicInteger();
        AtomicInteger clicks = new AtomicInteger();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 2))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(request -> {
                    plannerCalls.incrementAndGet();
                    return CompletableFuture.failedFuture(new ModelTaskOutputException(
                            ModelTaskOutputException.Reason.INVALID_JSON,
                            "invalid auxiliary planning response", null));
                }).simulatedDesktopLaunchOpenObserve(launches, opens, observes, clicks);
        if (catalogDenied) config.allowedToolNames(Set.of("desktop_session_launch_application",
                "desktop_session_open", "desktop_session_observe", "desktop_session_click"));
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return namedToolCallResponse("desktop_session_launch_application",
                    "{\"application\":\"示例应用\"}", 1, 1);
        }, catalogDenied ? RunBudget.UNBOUNDED : toolCallBudget(1), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("打开 示例应用 查看设置"));
            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.PAUSED, snapshot.state(), snapshot.error());
            assertEquals("context.planning_required", snapshot.output().path("kind").asText());
            String reason = snapshot.output().path("reason").asText().toLowerCase();
            assertTrue(reason.contains(catalogDenied ? "catalog" : "budget"), reason);
            assertEquals(1, plannerCalls.get());
            assertEquals(0, providerCalls.get());
            assertEquals(0, launches.get() + opens.get() + observes.get() + clicks.get());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.tool.started")));
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void desktopResumeObservesCompletedInputWithoutDispatchingItAgain() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger observes = new AtomicInteger();
        AtomicInteger clicks = new AtomicInteger();
        ModelTaskGateway planner = desktopBootstrapPlanner(providerCalls, plannerCalls,
                "desktop_session_launch_application");
        ChatModel model = prompt -> switch (providerCalls.incrementAndGet()) {
            case 1 -> namedToolCallResponse("desktop_session_launch_application",
                    "{\"application\":\"示例应用\"}", 2, 1);
            case 2 -> namedToolCallResponse("desktop_session_open",
                    "{\"targetId\":\"sample-target\",\"control\":true}", 2, 1);
            case 3 -> namedToolCallResponse("desktop_session_observe",
                    "{\"sessionId\":\"sample-session\"}", 2, 1);
            case 4 -> namedToolCallResponse("desktop_session_click",
                    "{\"sessionId\":\"sample-session\",\"observationId\":"
                            + "\"00000000-0000-4000-8000-000000000001\","
                            + "\"elementId\":\"00000000-0000-4000-8000-000000000001:v7\"}", 2, 1);
            case 5 -> {
                assertEquals(1, clicks.get());
                assertEquals(List.of("desktop_session_observe"), toolNames(prompt));
                assertEquals("OBSERVE", computerUseCursor(prompt).path("phase").asText());
                yield namedToolCallResponse("desktop_session_observe",
                        "{\"sessionId\":\"sample-session\"}", 2, 1);
            }
            case 6 -> {
                assertEquals(2, observes.get());
                assertTrue(lastToolResponse(prompt).responseData().contains("设置页面"));
                yield textResponse("已观察点击后的设置页面", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 2))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).simulatedDesktopLaunchOpenObserve(launches, opens, observes, clicks)
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_DESKTOP_INPUT_ONCE);
        try (Fixture fixture = new Fixture(model, toolCallBudget(6), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("打开 示例应用 查看设置"));
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            assertEquals(4, providerCalls.get());
            assertEquals(1, clicks.get());
            assertEquals(1, observes.get());
            fixture.restart();
            RunHandle resumed = fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));
            RunOutcome outcome = awaitCompletion(fixture, resumed);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(6, providerCalls.get());
            assertEquals(1, launches.get());
            assertEquals(1, opens.get());
            assertEquals(1, clicks.get());
            assertEquals(2, observes.get());
            assertEquals(6, plannerCalls.get(), "resume must not replan the mandatory observation");
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.started")
                            && event.payload().path("tool").asText().equals("desktop_session_click")).count());
        }
    }

    private static ModelTaskGateway desktopBootstrapPlanner(AtomicInteger providerCalls,
            AtomicInteger plannerCalls, String bootstrap) {
        return request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            String selected = providerCalls.get() == 0 ? bootstrap : "desktop_session_observe";
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, selected, "desktop-session");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", selected);
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
    }

    private static com.fasterxml.jackson.databind.JsonNode computerUseCursor(Prompt prompt) {
        String prefix = "Host computer-use control state:\n";
        String text = prompt.getInstructions().stream()
                .filter(SystemMessage.class::isInstance)
                .map(org.springframework.ai.chat.messages.Message::getText)
                .filter(value -> value.startsWith(prefix)).reduce((first, last) -> last).orElseThrow();
        int end = text.indexOf('\n', prefix.length());
        return assertDoesNotThrow(() -> new ObjectMapper().readTree(text.substring(prefix.length(), end)));
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void desktopClickKeepsLastObservedFrameEvenWhenPlannerOmitsItsHistoryId()
            throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger observeCalls = new AtomicInteger();
        AtomicInteger clickCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                String target = switch (providerCalls.get()) {
                    case 0, 1 -> "desktop_session_click";
                    case 2 -> "desktop_session_observe";
                    default -> "";
                };
                toolIntent(selection, target,
                        target.isBlank() ? "" : "desktop-session");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct",
                        providerCalls.get() < 2
                                ? "desktop_session_click" : "desktop_session_observe");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> {
                    assertTrue(toolNames(prompt).contains("desktop_session_observe"),
                            "without a prior frame, observe must be available before clicking");
                    yield namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                }
                case 2 -> {
                    assertTrue(toolNames(prompt).contains("desktop_session_click"));
                    assertTrue(toolNames(prompt).contains("desktop_session_observe"));
                    List<org.springframework.ai.chat.messages.Message> messages =
                            prompt.getInstructions();
                    boolean completeExchange = false;
                    for (int index = 0; index + 1 < messages.size(); index++) {
                        if (!(messages.get(index) instanceof AssistantMessage assistant)
                                || !(messages.get(index + 1) instanceof ToolResponseMessage responses)) {
                            continue;
                        }
                        completeExchange |= assistant.getToolCalls().stream()
                                .anyMatch(call -> call.name().equals("desktop_session_observe")
                                        && responses.getResponses().stream().anyMatch(item ->
                                                item.id().equals(call.id())
                                                        && item.responseData().contains("概览页面")));
                    }
                    assertTrue(completeExchange,
                            "click must retain the full assistant and tool exchange for the last frame");
                    yield namedToolCallResponse("desktop_session_click",
                            "{\"sessionId\":\"sample-session\",\"observationId\":"
                                    + "\"00000000-0000-4000-8000-000000000001\","
                                    + "\"elementId\":\"00000000-0000-4000-8000-000000000001:v7\"}", 2, 1);
                }
                case 3 -> {
                    assertEquals(1, clickCalls.get());
                    assertTrue(toolNames(prompt).contains("desktop_session_observe"));
                    yield namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                }
                case 4 -> {
                    assertEquals(2, observeCalls.get());
                    yield textResponse("已查看 示例应用 设置页面", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 3))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).simulatedDesktopSession(observeCalls, clickCalls);
        try (Fixture fixture = new Fixture(model, toolCallBudget(3),
                new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("打开 示例应用 查看设置"));
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(4, providerCalls.get());
            assertEquals(2, observeCalls.get());
            assertEquals(1, clickCalls.get());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .anyMatch(event -> event.type().equals("core.tool.receipt")
                            && event.payload().path("tool").asText()
                                    .equals("desktop_session_observe")
                            && event.payload().path("status").asText().equals("OBSERVED")
                            && event.payload().path("target").asText().equals("示例应用")
                            && event.payload().path("subject").asText().equals("settings")),
                    "the final answer needs a target-page observation receipt");
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void observedNavigationTargetOffersClickWhenPlannerRepeatsObserve()
            throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger observes = new AtomicInteger();
        AtomicInteger clicks = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            boolean afterHomeFrame = providerCalls.get() == 3;
            if (afterHomeFrame) {
                String targets = request.input().path("observedDesktopTargets").asText();
                assertTrue(targets.contains("设置标签"),
                        "both LIGHT stages need the compact live-frame navigation target");
                if (request.purpose().endsWith(".select_v2")) {
                    assertTrue(request.input().path("latest").asText().contains("概览页面"),
                            "摘要必须保留辅助功能长列表之后的当前页面描述");
                }
            }
            String selected = providerCalls.get() < 3
                    ? "desktop_session_launch_application" : "desktop_session_observe";
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, selected, "desktop-session");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", selected);
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> namedToolCallResponse("desktop_session_launch_application",
                        "{\"application\":\"示例应用\"}", 2, 1);
                case 2 -> {
                    assertEquals(List.of("desktop_session_open"), toolNames(prompt));
                    yield namedToolCallResponse("desktop_session_open",
                            "{\"targetId\":\"sample-target\",\"control\":true}", 2, 1);
                }
                case 3 -> {
                    assertEquals(List.of("desktop_session_observe"), toolNames(prompt));
                    yield namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                }
                case 4 -> {
                    assertEquals(Set.of("desktop_session_click", "desktop_session_observe"),
                            Set.copyOf(toolNames(prompt)),
                            "a user-named visual target should make click available");
                    assertTrue(prompt.getInstructions().stream()
                            .filter(ToolResponseMessage.class::isInstance)
                            .map(ToolResponseMessage.class::cast)
                            .flatMap(response -> response.getResponses().stream())
                            .anyMatch(response -> response.responseData().contains("设置标签")),
                            "click must retain the complete observed frame");
                    yield namedToolCallResponse("desktop_session_click",
                            "{\"sessionId\":\"sample-session\",\"observationId\":"
                                    + "\"00000000-0000-4000-8000-000000000001\","
                                    + "\"elementId\":\"00000000-0000-4000-8000-000000000001:v7\"}", 2, 1);
                }
                case 5 -> {
                    assertEquals(1, clicks.get());
                    assertEquals(List.of("desktop_session_observe"), toolNames(prompt));
                    yield namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                }
                case 6 -> {
                    assertEquals(2, observes.get());
                    assertTrue(lastToolResponse(prompt).responseData().contains("设置页面"));
                    assertFalse(toolNames(prompt).contains("desktop_session_click"),
                            "a dispatched click must not be suggested again from the same tab label");
                    yield textResponse("已查看设置页面", 2, 1);
                }
                case 7 -> textResponse("设置页面仍需结构化画面证据核验", 2, 1);
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 2))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).simulatedDesktopLaunchOpenObserve(
                        launches, opens, observes, clicks)
                .harness()
                .simulatedVisualNavigationTarget();
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED,
                new AtomicInteger(), config)) {
            TaskContractV3 contract = new TaskContractV3(3, "打开 示例应用 查看设置",
                    List.of(new TaskCriterionV3("open", "打开会话", "desktop.open", CapabilityMetadata.TargetKind.DESKTOP_APPLICATION,
                                    "示例应用", EffectReceiptV1.Status.ACCEPTED, ""),
                            new TaskCriterionV3("click", "点击设置入口", "desktop.click", CapabilityMetadata.TargetKind.DESKTOP_APPLICATION,
                                    "示例应用", EffectReceiptV1.Status.ACCEPTED, ""),
                            new TaskCriterionV3("observe", "观察设置页", "desktop.observe", CapabilityMetadata.TargetKind.DESKTOP_APPLICATION,
                                    "示例应用", EffectReceiptV1.Status.OBSERVED, "设置")),
                    true, true, "definition");
            RunHandle handle = fixture.engine.start(fixture.request("打开 示例应用 查看设置",
                    Map.of(TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(contract))));
            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.PAUSED, snapshot.state(),
                    "provider calls=" + providerCalls.get() + "; " + snapshot.error()
                            + "; output=" + snapshot.output());
            assertNotEquals(RunState.WAITING_APPROVAL, snapshot.state(),
                    "provider calls=" + providerCalls.get() + "; " + snapshot.error());
            assertEquals(7, providerCalls.get());
            assertEquals(1, launches.get());
            assertEquals(1, opens.get());
            assertEquals(2, observes.get());
            assertEquals(1, clicks.get());
            var taskResult = fixture.engine.taskResult(handle.id()).orElseThrow();
            assertEquals(TaskOutcome.PARTIAL, taskResult.outcome(),
                    "open and click are proven, but the final view is not; result="
                            + taskResult + "; receipts=" + fixture.runs.eventsAfter(handle.id(), 0)
                                    .stream().filter(event -> event.type().equals("core.tool.receipt"))
                                    .map(RunEventEnvelope::payload).toList());
            assertTrue(taskResult.unmetCriteria().stream()
                            .anyMatch(item -> item.contains("观察设置页")
                                    && item.contains("OBSERVED")),
                    "plain text without structured active-view evidence cannot verify the view: "
                            + taskResult);
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void 有界补做保留失败条件并通过描述性控件完成页面切换() throws Exception {
        for (String label : List.of("设置入口（齿轮图标）", "Preferences and appearance")) {
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger launches = new AtomicInteger();
            AtomicInteger opens = new AtomicInteger();
            AtomicInteger observes = new AtomicInteger();
            AtomicInteger clicks = new AtomicInteger();
            List<Prompt> delivered = new ArrayList<>();
            ModelTaskGateway planner = request -> {
                ObjectNode selection = JsonNodeFactory.instance.objectNode();
                selection.putArray("historyIds");
                if (calls.get() >= 3) {
                    assertTrue(request.input().path("task").asText().contains("查看设置"),
                            "两个规划阶段都必须知道原始目标");
                }
                if (calls.get() == 3) {
                    assertTrue(request.input().path("latest").asText().contains("desktop_session_"),
                            "phase=" + request.purpose() + "; input=" + request.input());
                }
                if (calls.get() == 3 || calls.get() == 4) {
                    assertTrue(request.input().path("observedDesktopTargets").asText().contains(label),
                            "phase=" + request.purpose() + "; input=" + request.input());
                }
                if (calls.get() >= 4) {
                    assertTrue(request.input().path("taskRepairFeedback").asText().contains("观察设置页"),
                            "补做条件必须传到 select 和 refine，不得重复盲选工具");
                }
                String selected = calls.get() >= 6 ? "" : calls.get() < 3
                        ? "desktop_session_launch_application" : "desktop_session_observe";
                if (request.purpose().endsWith(".select_v2")) {
                    selection.putArray("searches");
                    toolIntent(selection, selected, selected.isBlank() ? "" : "desktop-session");
                } else {
                    selection.putArray("sourceIds");
                    toolChoice(selection, request, "direct", selected);
                }
                return CompletableFuture.completedFuture(new ModelTaskResult(
                        selection, "planner", 1, 1, false, Map.of()));
            };
            ChatModel model = prompt -> {
                delivered.add(prompt);
                int call = calls.incrementAndGet();
                if (call >= 5) {
                    assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                            message.getText() != null && message.getText().contains("任务验收尚未通过")
                                    && message.getText().contains("观察设置页")),
                            "补做反馈必须持续送达主模型直到本轮核验结束");
                }
                return switch (call) {
                    case 1 -> namedToolCallResponse("desktop_session_launch_application",
                            "{\"application\":\"示例应用\"}", 2, 1);
                    case 2 -> namedToolCallResponse("desktop_session_open",
                            "{\"targetId\":\"sample-target\",\"control\":true}", 2, 1);
                    case 3 -> namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                    case 4 -> textResponse("已打开应用，尚未进入目标页面", 2, 1);
                    case 5 -> {
                        assertTrue(toolNames(prompt).contains("desktop_session_click"),
                                "词面不匹配也不能阻断已观察控件的操作能力");
                        var observedResponses = prompt.getInstructions().stream()
                                .filter(ToolResponseMessage.class::isInstance)
                                .map(ToolResponseMessage.class::cast)
                                .flatMap(message -> message.getResponses().stream())
                                .filter(response -> response.name()
                                        .equals("desktop_session_observe"))
                                .toList();
                        assertTrue(observedResponses.stream().anyMatch(response -> {
                                    var frame = historicalToolData(response);
                                    return frame.path("schemaVersion").asInt() == 1
                                            && frame.path("kind").asText().equals("desktop.observation")
                                            && frame.path("sessionId").asText().equals("sample-session")
                                            && frame.path("targetId").asText().equals("sample-target")
                                            && frame.path("observationId").asText().equals(
                                                    "00000000-0000-4000-8000-000000000001")
                                            && java.util.stream.StreamSupport.stream(
                                                    frame.path("visualTargets").spliterator(), false)
                                                    .anyMatch(target -> target.path("id").asText().equals(
                                                            "00000000-0000-4000-8000-000000000001:v7")
                                                            && target.path("label").asText().equals(label));
                                }),
                                "repair must include the trusted observed frame, responses="
                                        + observedResponses);
                        yield namedToolCallResponse("desktop_session_click",
                                "{\"sessionId\":\"sample-session\",\"observationId\":"
                                        + "\"00000000-0000-4000-8000-000000000001\","
                                        + "\"elementId\":\"00000000-0000-4000-8000-000000000001:v7\"}", 2, 1);
                    }
                    case 6 -> namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                    case 7 -> textResponse("已查看设置页面", 2, 1);
                    default -> throw new AssertionError("unexpected provider call");
                };
            };
            FixtureConfig config = new FixtureConfig()
                    .context(contextConfiguration(true, 48_000, 16_000, 2))
                    .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                    .planner(planner).harness().simulatedDesktopLaunchOpenObserve(
                            launches, opens, observes, clicks)
                    .simulatedVisualNavigationTarget(label, true);
            try (Fixture fixture = new Fixture(model, toolCallBudget(5), new AtomicInteger(), config)) {
                TaskContractV3 contract = new TaskContractV3(3, "打开示例应用查看设置",
                        List.of(new TaskCriterionV3("open", "打开会话", "desktop.open", CapabilityMetadata.TargetKind.DESKTOP_APPLICATION,
                                        "示例应用", EffectReceiptV1.Status.ACCEPTED, ""),
                                new TaskCriterionV3("view", "观察设置页", "desktop.observe", CapabilityMetadata.TargetKind.DESKTOP_APPLICATION,
                                        "示例应用", EffectReceiptV1.Status.OBSERVED, "settings")),
                        true, true, "definition");
                RunHandle handle = fixture.engine.start(fixture.request("打开示例应用查看设置",
                        Map.of(TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(contract))));
                RunSnapshot snapshot = fixture.engine.get(handle.id());
                assertEquals(RunState.COMPLETED, snapshot.state(),
                        "calls=" + calls.get() + "; " + snapshot.error() + "; " + snapshot.output());
                RunOutcome outcome = awaitCompletion(fixture, handle);
                assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
                assertEquals(TaskOutcome.VERIFIED_COMPLETE,
                        fixture.engine.taskResult(handle.id()).orElseThrow().outcome());
                assertEquals(7, calls.get());
                assertEquals(1, launches.get());
                assertEquals(1, opens.get());
                assertEquals(1, clicks.get());
                assertEquals(2, observes.get());
                assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                        .filter(event -> event.type().equals("core.task.repair_requested")).count());
                assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
            }
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void oneSelectedToolOffersClickAloneAfterFrameAndObserveAfterClick() throws Exception {
        runBudgetedObservedNavigation(1, 48_000);
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void schemaBudgetKeepsRelevantDesktopToolsWithinTheSafeLimit()
            throws Exception {
        runBudgetedObservedNavigation(2, 4_000);
    }

    private static void runBudgetedObservedNavigation(int selectedTools,
            int schemaBudget) throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger observes = new AtomicInteger();
        AtomicInteger clicks = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            String selected = providerCalls.get() < 3
                    ? "desktop_session_launch_application" : "desktop_session_observe";
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, selected, "desktop-session");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", selected);
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        ChatModel model = prompt -> {
            assertTrue(toolNames(prompt).size() <= selectedTools,
                    "provider tool selection must stay within the configured count");
            var options = assertInstanceOf(ToolCallingChatOptions.class, prompt.getOptions());
            int schemaCharacters = options.getToolCallbacks() == null ? 0
                    : options.getToolCallbacks().stream()
                            .filter(callback -> !callback.getToolDefinition().name()
                                    .equals(HarnessDecisionToolCallback.NAME))
                            .mapToInt(SpringAiToolCatalog::schemaCharacters).sum();
            assertTrue(schemaCharacters <= schemaBudget,
                    "provider schemas must stay within their character budget");
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> namedToolCallResponse("desktop_session_launch_application",
                        "{\"application\":\"示例应用\"}", 2, 1);
                case 2 -> {
                    assertEquals(List.of("desktop_session_open"), toolNames(prompt));
                    yield namedToolCallResponse("desktop_session_open",
                            "{\"targetId\":\"sample-target\",\"control\":true}", 2, 1);
                }
                case 3 -> {
                    assertEquals(List.of("desktop_session_observe"), toolNames(prompt),
                            "an unobserved session must not expose click");
                    yield namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                }
                case 4 -> {
                    if (selectedTools == 1) {
                        assertEquals(List.of("desktop_session_click"), toolNames(prompt));
                    } else {
                        assertEquals(Set.of("desktop_session_observe", "desktop_session_click"),
                                Set.copyOf(toolNames(prompt)),
                                "both trusted desktop schemas fit the minimum safe budget");
                    }
                    yield namedToolCallResponse("desktop_session_click",
                            "{\"sessionId\":\"sample-session\",\"observationId\":"
                                    + "\"00000000-0000-4000-8000-000000000001\","
                                    + "\"elementId\":\"00000000-0000-4000-8000-000000000001:v7\"}", 2, 1);
                }
                case 5 -> {
                    assertEquals(1, clicks.get());
                    assertEquals(List.of("desktop_session_observe"), toolNames(prompt));
                    yield namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                }
                case 6 -> textResponse("已取得设置画面", 2, 1);
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        ObjectNode context = contextConfiguration(true, 48_000, 16_000, selectedTools)
                .put("maxToolSchemaCharacters", schemaBudget);
        ObjectNode onDemand = JsonNodeFactory.instance.objectNode()
                .put("enabled", true).put("selectedTools", selectedTools);
        FixtureConfig config = new FixtureConfig()
                .context(context).onDemand(onDemand).planner(planner)
                .simulatedDesktopLaunchOpenObserve(launches, opens, observes, clicks)
                .simulatedVisualNavigationTarget();
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED,
                new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("打开 示例应用 查看设置"));
            RunOutcome outcome = handle.completion().toCompletableFuture()
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(6, providerCalls.get());
            assertEquals(1, launches.get());
            assertEquals(1, opens.get());
            assertEquals(2, observes.get());
            assertEquals(1, clicks.get());
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void oldRunDesktopClickWithoutObservationIdReobservesInsteadOfReplayingInput()
            throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger observeCalls = new AtomicInteger();
        AtomicInteger clickCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, "desktop_session_click", "desktop-session");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "desktop_session_click");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        ChatModel model = prompt -> switch (providerCalls.incrementAndGet()) {
            case 1 -> {
                assertEquals(List.of("desktop_session_observe"), toolNames(prompt));
                var feedback = assertDoesNotThrow(() -> new ObjectMapper()
                        .readTree(lastToolResponse(prompt).responseData()));
                assertEquals("legacy_desktop_observation_required",
                        feedback.path("error").asText());
                assertFalse(feedback.path("executed").asBoolean(true));
                yield namedToolCallResponse("desktop_session_observe",
                        "{\"sessionId\":\"sample-session\"}", 2, 1);
            }
            case 2 -> {
                assertEquals(1, observeCalls.get());
                assertEquals(0, clickCalls.get());
                var observed = assertDoesNotThrow(() -> new ObjectMapper()
                        .readTree(lastToolResponse(prompt).responseData()));
                assertFalse(observed.path("data").path("observationId").asText().isBlank(),
                        "the current frame identity remains in structured evidence after display compaction");
                yield textResponse("已重新观察，请基于新画面继续", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 2))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).simulatedDesktopSession(observeCalls, clickCalls)
                .downstreamMutation(DownstreamMutation.PAUSE_ON_DESKTOP_OBSERVE_ONCE);
        try (Fixture fixture = new Fixture(model, toolCallBudget(3),
                new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("打开 示例应用 查看设置"));
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state(),
                    String.valueOf(fixture.engine.get(handle.id()).output()) + " "
                            + fixture.engine.get(handle.id()).error());
            Prompt selected = config.pausedPrompt.get();
            assertNotNull(selected);

            ObjectNode oldInput = JsonNodeFactory.instance.objectNode();
            oldInput.set("messages", StepMessageCodec.messages(selected.getInstructions()));
            oldInput.putArray("toolNames").add("desktop_session_click");
            oldInput.putObject("toolFingerprints")
                    .put("desktop_session_click", "pre-observation-token-schema");
            oldInput.put("modelPolicy", "test:model");
            oldInput.put("attempt", 1);
            StepId oldModel = StepId.random();
            var events = StepEvents.durableSink(fixture.runs, handle.id());
            StepEvents.started(events, oldModel, AgentStep.Kind.MODEL, oldInput, null);
            ChatResponse oldResponse = namedToolCallResponse("desktop_session_click",
                    "{\"sessionId\":\"sample-session\",\"generation\":1,\"x\":20,\"y\":30}",
                    2, 1);
            StepEvents.completed(events, oldModel, StepMessageCodec.response(oldResponse),
                    StepMessageCodec.usage(oldResponse));

            fixture.restart();
            fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertTrue(Set.of(RunState.COMPLETED, RunState.PAUSED)
                    .contains(snapshot.state()), snapshot.error());
            assertEquals(0, clickCalls.get());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.tool.started")
                            && event.payload().path("tool").asText()
                                    .equals("desktop_session_click")),
                    "legacy click must never be replayed without an observation ID");
            if (snapshot.state() == RunState.COMPLETED) {
                assertEquals(2, providerCalls.get());
                assertEquals(1, observeCalls.get());
                assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                        .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                                && step.input().path("phase").asText()
                                        .equals("legacy_desktop_reobserve"))
                        .count());
            } else {
                assertEquals(0, observeCalls.get(),
                        "an incompatible persisted Run must stop before desktop execution");
            }
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void trustedFailedDesktopActionRequestsObserveBeforePlannerReopensSession()
            throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger openCalls = new AtomicInteger();
        AtomicInteger observeCalls = new AtomicInteger();
        AtomicInteger clickCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            String target = providerCalls.get() == 2
                    ? "desktop_session_click" : "desktop_session_open";
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, target, "desktop-session");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", target);
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        ChatModel model = prompt -> switch (providerCalls.incrementAndGet()) {
            case 1 -> {
                assertEquals(List.of("desktop_session_open"), toolNames(prompt));
                yield namedToolCallResponse("desktop_session_open",
                        "{\"targetId\":\"sample-target\",\"control\":true}", 2, 1);
            }
            case 2 -> {
                assertEquals(List.of("desktop_session_observe"), toolNames(prompt));
                yield namedToolCallResponse("desktop_session_observe",
                        "{\"sessionId\":\"sample-session\"}", 2, 1);
            }
            case 3 -> {
                assertEquals(Set.of("desktop_session_click", "desktop_session_observe"),
                        Set.copyOf(toolNames(prompt)));
                yield namedToolCallResponse("desktop_session_click",
                        "{\"sessionId\":\"sample-session\",\"observationId\":"
                                + "\"00000000-0000-4000-8000-000000000001\","
                                + "\"elementId\":\"00000000-0000-4000-8000-000000000001:v7\"}", 2, 1);
            }
            case 4 -> {
                assertEquals(1, clickCalls.get());
                assertEquals(List.of("desktop_session_observe"), toolNames(prompt),
                        "trusted nextStep=OBSERVE must take precedence over another open");
                assertEquals("REOBSERVE", toolEnvelope(prompt).path("status").asText());
                yield namedToolCallResponse("desktop_session_observe",
                        "{\"sessionId\":\"sample-session\"}", 2, 1);
            }
            case 5 -> {
                assertEquals(2, observeCalls.get());
                assertTrue(lastToolResponse(prompt).responseData().contains("概览页面"),
                        "an input that was not dispatched cannot change the simulated view");
                yield textResponse("已取得新画面", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 2))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner)
                .simulatedDesktopLaunchOpenObserve(new AtomicInteger(), openCalls,
                        observeCalls, clickCalls)
                .simulatedClickReobserve();
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED,
                new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("打开 示例应用 查看设置"));
            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.COMPLETED, snapshot.state(),
                    String.valueOf(snapshot.output()) + " " + snapshot.error());
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(5, providerCalls.get());
            assertEquals(1, openCalls.get());
            assertEquals(2, observeCalls.get());
            assertEquals(1, clickCalls.get());
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void launchedDesktopReobservesTargetAfterClickWhenPlannerRepeatsEarlierTools()
            throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger launchCalls = new AtomicInteger();
        AtomicInteger openCalls = new AtomicInteger();
        AtomicInteger observeCalls = new AtomicInteger();
        AtomicInteger clickCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            String target = switch (providerCalls.get()) {
                case 0, 1, 2 -> "desktop_session_launch_application";
                case 3, 4 -> "desktop_session_click";
                default -> "";
            };
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, target, target.isBlank() ? "" : "desktop-session");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", target);
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> {
                    assertEquals(List.of("desktop_session_launch_application"), toolNames(prompt));
                    yield namedToolCallResponse("desktop_session_launch_application",
                            "{\"application\":\"示例应用\"}", 2, 1);
                }
                case 2 -> {
                    assertEquals(1, launchCalls.get());
                    assertEquals(List.of("desktop_session_open"), toolNames(prompt),
                            "a launched application must not be launched again");
                    assertTrue(lastToolResponse(prompt).responseData().contains("目标 ID=sample-target"));
                    yield namedToolCallResponse("desktop_session_open",
                            "{\"targetId\":\"sample-target\",\"control\":true}", 2, 1);
                }
                case 3 -> {
                    assertEquals(1, openCalls.get());
                    assertEquals(List.of("desktop_session_observe"), toolNames(prompt),
                            "an opened session must be observed before claiming the target view");
                    yield namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                }
                case 4 -> {
                    assertEquals(1, observeCalls.get());
                    assertTrue(lastToolResponse(prompt).responseData().contains("概览页面"));
                    assertEquals(Set.of("desktop_session_click", "desktop_session_observe"),
                            Set.copyOf(toolNames(prompt)));
                    yield namedToolCallResponse("desktop_session_click",
                            "{\"sessionId\":\"sample-session\",\"observationId\":"
                                    + "\"00000000-0000-4000-8000-000000000001\","
                                    + "\"elementId\":\"00000000-0000-4000-8000-000000000001:v7\"}", 2, 1);
                }
                case 5 -> {
                    assertEquals(1, clickCalls.get());
                    assertEquals(List.of("desktop_session_observe"), toolNames(prompt),
                            "clicking navigation invalidates the previous home-page frame");
                    assertTrue(lastToolResponse(prompt).responseData().contains("已点击"));
                    yield namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                }
                case 6 -> {
                    assertEquals(2, observeCalls.get());
                    assertTrue(lastToolResponse(prompt).responseData().contains("设置页面"));
                    yield textResponse("已查看 示例应用 设置页面", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 2))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).harness().simulatedDesktopLaunchOpenObserve(
                        launchCalls, openCalls, observeCalls, clickCalls);
        try (Fixture fixture = new Fixture(model, toolCallBudget(5),
                new AtomicInteger(), config)) {
            TaskContractV3 contract = new TaskContractV3(3, "打开 示例应用 查看设置",
                    List.of(new TaskCriterionV3("app-window", "打开 示例应用 会话",
                                    "desktop.open", CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "示例应用", EffectReceiptV1.Status.ACCEPTED, ""),
                            new TaskCriterionV3("settings", "观察 示例应用 设置",
                                    "desktop.observe", CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "示例应用", EffectReceiptV1.Status.OBSERVED,
                                    "settings")), true, true, "definition");
            RunHandle handle = fixture.engine.start(fixture.request("打开 示例应用 查看设置",
                    Map.of(TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(contract))));
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(6, providerCalls.get());
            assertEquals(1, launchCalls.get());
            assertEquals(1, openCalls.get());
            assertEquals(2, observeCalls.get());
            assertEquals(1, clickCalls.get());
            var observations = fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.receipt")
                            && event.payload().path("tool").asText()
                                    .equals("desktop_session_observe")
                            && event.payload().path("status").asText().equals("OBSERVED")
                            && !event.payload().path("metadata").path("viewEvidence")
                                    .asText("").isBlank())
                    .toList();
            assertEquals(List.of("overview", "settings"), observations.stream()
                    .map(event -> event.payload().path("subject").asText()).toList());
            assertNotEquals(observations.get(0).payload().path("metadata")
                            .path("observationId").asText(),
                    observations.get(1).payload().path("metadata")
                            .path("observationId").asText());
            assertEquals(TaskOutcome.VERIFIED_COMPLETE,
                    TaskResultEvaluator.latestOutcome(
                            fixture.runs.eventsAfter(handle.id(), 0), fixture.json)
                            .orElseThrow().outcome());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void mandatoryDesktopLifecycleRejectsRepeatedDiscoveryWithoutRepeatingLaunch()
            throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger observes = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ModelTaskGateway planner = request -> {
            String target = switch (providerCalls.get()) {
                case 0, 2, 3 -> "desktop_session_launch_application";
                case 1 -> "desktop_session_targets";
                case 4, 5 -> "desktop_session_open";
                default -> "";
            };
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, target, target.isBlank() ? "" : "desktop-session");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", target);
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> {
                    assertEquals(List.of("desktop_session_launch_application"), toolNames(prompt));
                    yield namedToolCallResponse("desktop_session_probe", "{}", 2, 1);
                }
                case 2 -> {
                    assertEquals(0, launches.get());
                    assertEquals(Set.of("desktop_session_launch_application", "desktop_session_probe",
                            "desktop_session_targets"), Set.copyOf(toolNames(prompt)));
                    yield namedToolCallResponse("desktop_session_launch_application",
                            "{\"application\":\"示例应用\"}", 2, 1);
                }
                case 3 -> {
                    assertEquals(1, launches.get());
                    assertEquals(List.of("desktop_session_open"), toolNames(prompt));
                    yield namedToolCallResponse("desktop_session_targets", "{}", 2, 1);
                }
                case 4 -> {
                    assertEquals(0, opens.get());
                    assertEquals(List.of("desktop_session_open"), toolNames(prompt),
                            "a durable launch target takes precedence over unoffered rediscovery");
                    assertEquals("OPEN_SESSION", computerUseCursor(prompt).path("phase").asText());
                    yield namedToolCallResponse("desktop_session_targets", "{}", 2, 1);
                }
                case 5 -> {
                    assertEquals("sample-target", computerUseCursor(prompt).path("targetId").asText());
                    assertTrue(lastToolResponse(prompt).responseData().contains("tool_not_offered"));
                    assertEquals(1, launches.get());
                    assertEquals(List.of("desktop_session_open"), toolNames(prompt));
                    yield namedToolCallResponse("desktop_session_open",
                            "{\"targetId\":\"sample-target\",\"control\":true}", 2, 1);
                }
                case 6 -> {
                    assertEquals(1, opens.get());
                    assertEquals(List.of("desktop_session_observe"), toolNames(prompt));
                    yield namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                }
                case 7 -> {
                    assertEquals(1, observes.get());
                    assertTrue(lastToolResponse(prompt).responseData().contains("概览页面"));
                    yield textResponse("已打开示例应用并观察概览页面", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 4))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).simulatedDesktopLaunchOpenObserve(
                        launches, opens, observes, new AtomicInteger()).simulatedDesktopDiscovery();
        try (Fixture fixture = new Fixture(model, toolCallBudget(4), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("打开 示例应用 并查看窗口"));
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(7, providerCalls.get());
            assertEquals(1, launches.get());
            assertEquals(1, opens.get());
            assertEquals(1, observes.get());
            assertEquals(List.of("desktop_session_launch_application",
                            "desktop_session_open", "desktop_session_observe"),
                    fixture.runs.eventsAfter(handle.id(), 0).stream()
                            .filter(event -> event.type().equals("core.tool.started"))
                            .map(event -> event.payload().path("tool").asText()).toList(),
                    "rejected calls must never execute or replay");
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void repairedToolKeepsPriorityWhenPreviousOfferingExceedsCountOrSchemaBudget()
            throws Exception {
        for (boolean schemaLimited : List.of(false, true)) {
            AtomicInteger providerCalls = new AtomicInteger();
            AtomicInteger codeCalls = new AtomicInteger();
            ModelTaskGateway planner = directPlannerByProviderStep(providerCalls,
                    "code_target", "filler_000");
            FixtureConfig config = new FixtureConfig()
                    .context(contextConfiguration(true, 48_000, 16_000,
                            schemaLimited ? 2 : 1).put("maxToolSchemaCharacters", 4_000))
                    .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                    .planner(planner).fillerTools(1).codeCalls(codeCalls)
                    .codeDescriptionPadding(schemaLimited ? 3_700 : 0);
            ChatModel model = prompt -> switch (providerCalls.incrementAndGet()) {
                case 1 -> {
                    assertEquals(List.of("code_target"), toolNames(prompt));
                    yield namedToolCallResponse("filler_000", "{\"value\":1}", 2, 1);
                }
                case 2 -> {
                    assertEquals(List.of("filler_000"), toolNames(prompt),
                            "an old optional tool must not displace the requested tool or overflow its budget");
                    yield namedToolCallResponse("filler_000", "{\"value\":2}", 2, 1);
                }
                case 3 -> textResponse("done", 2, 1);
                default -> throw new AssertionError("unexpected provider call");
            };
            try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED,
                    new AtomicInteger(), config)) {
                RunHandle handle = fixture.engine.start(fixture.request("perform the selected operation"));
                RunOutcome outcome = awaitCompletion(fixture, handle);
                assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
                assertEquals(3, providerCalls.get());
                assertEquals(0, codeCalls.get());
                assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                        .filter(event -> event.type().equals("core.tool.started")).count());
            }
        }
    }

    @Test
    void repairDoesNotPromotePreviouslyOfferedToolAfterAuthorizationRevocation()
            throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 2))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(directPlannerByProviderStep(providerCalls, "code_target", "filler_000"))
                .fillerTools(1).codeCalls(codeCalls)
                .allowedToolNames(Set.of("code_target", "filler_000", "framework_tool_catalog"))
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_UNAVAILABLE_FEEDBACK_ONCE);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return namedToolCallResponse("filler_000", "{\"value\":1}", 2, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("perform the selected operation"));
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            config.allowedToolNames(Set.of("filler_000", "framework_tool_catalog"));
            fixture.restart();
            fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));
            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.PAUSED, snapshot.state());
            assertTrue(snapshot.output().path("reason").asText().contains("no longer authorized"),
                    snapshot.output().toString());
            assertEquals(1, providerCalls.get());
            assertEquals(0, codeCalls.get());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.tool.started")));
        }
    }

    private static ModelTaskGateway directPlannerByProviderStep(AtomicInteger providerCalls,
            String first, String following) {
        return request -> {
            String target = providerCalls.get() == 0 ? first : following;
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, target, target.substring(0, target.indexOf('_')));
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", target);
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void rejectedDesktopReopenKeepsMandatoryObserveForTheNextModelStep()
            throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger openCalls = new AtomicInteger();
        AtomicInteger observeCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, providerCalls.get() < 3 ? "desktop_session_open" : "",
                        providerCalls.get() < 3 ? "desktop-session" : "");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "desktop_session_open");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> {
                    assertEquals(List.of("desktop_session_open"), toolNames(prompt));
                    yield namedToolCallResponse("desktop_session_open",
                            "{\"targetId\":\"sample-target\",\"control\":true}", 2, 1);
                }
                case 2 -> {
                    assertEquals(1, openCalls.get());
                    assertEquals(List.of("desktop_session_observe"), toolNames(prompt));
                    assertEquals("SUCCEEDED", toolEnvelope(prompt).path("status").asText());
                    assertEquals("sample-session", toolEnvelope(prompt)
                            .path("data").path("sessionId").asText());
                    yield namedToolCallResponse("desktop_session_open",
                            "{\"targetId\":\"sample-target\",\"control\":true}", 2, 1);
                }
                case 3 -> {
                    assertEquals(1, openCalls.get());
                    assertEquals(0, observeCalls.get(),
                            "the unavailable open call must reject the whole batch");
                    assertEquals(List.of("desktop_session_observe"), toolNames(prompt),
                            "unoffered reopen cannot replace the mandatory first observation");
                    var feedback = assertDoesNotThrow(() -> new ObjectMapper()
                            .readTree(lastToolResponse(prompt).responseData()));
                    assertEquals("tool_not_offered", feedback.path("error").asText());
                    assertEquals("desktop_session_open", feedback.path("tool").asText());
                    assertFalse(feedback.path("executed").asBoolean(true));
                    yield namedToolCallResponse("desktop_session_observe",
                            "{\"sessionId\":\"sample-session\"}", 2, 1);
                }
                case 4 -> {
                    assertEquals(1, observeCalls.get());
                    assertTrue(lastToolResponse(prompt).responseData().contains("概览页面"));
                    yield textResponse("已查看示例应用概览页面", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 2))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).simulatedDesktopOpenObserve(openCalls, observeCalls);
        try (Fixture fixture = new Fixture(model, toolCallBudget(3),
                new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("打开 示例应用 并查看设置"));
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals("已查看示例应用概览页面", outcome.output().path("text").asText());
            assertEquals(4, providerCalls.get());
            assertEquals(1, openCalls.get());
            assertEquals(1, observeCalls.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.output().path("rejectedUnavailableToolBatch").asBoolean())
                    .count());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.run.paused")));
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void rejectedDesktopOpenDoesNotReplacePlannedObserveOnTheNextModelStep()
            throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger openCalls = new AtomicInteger();
        AtomicInteger observeCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            String target = providerCalls.get() == 0 ? "desktop_session_open"
                    : providerCalls.get() < 3 ? "desktop_session_observe" : "";
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, target, target.isBlank() ? "" : "desktop-session");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", target);
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        ChatModel model = prompt -> switch (providerCalls.incrementAndGet()) {
            case 1 -> {
                assertEquals(List.of("desktop_session_open"), toolNames(prompt));
                yield namedToolCallResponse("desktop_session_open",
                        "{\"targetId\":\"sample-target\",\"control\":true}", 2, 1);
            }
            case 2 -> {
                assertEquals(List.of("desktop_session_observe"), toolNames(prompt));
                yield namedToolCallResponse("desktop_session_open",
                        "{\"targetId\":\"sample-target\",\"control\":true}", 2, 1);
            }
            case 3 -> {
                assertEquals(1, openCalls.get(),
                        "the unoffered second open call must not reopen the application");
                assertEquals(List.of("desktop_session_observe"), toolNames(prompt),
                        "the rejected open call cannot alter the host's observation phase");
                assertTrue(lastToolResponse(prompt).responseData()
                        .contains("\"error\":\"tool_not_offered\""));
                yield namedToolCallResponse("desktop_session_observe",
                        "{\"sessionId\":\"sample-session\"}", 2, 1);
            }
            case 4 -> {
                assertEquals(1, observeCalls.get());
                assertTrue(lastToolResponse(prompt).responseData().contains("概览页面"));
                yield textResponse("已查看示例应用概览页面", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 2))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).simulatedDesktopOpenObserve(openCalls, observeCalls);
        try (Fixture fixture = new Fixture(model, toolCallBudget(3),
                new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("打开 示例应用 并查看设置"));
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(4, providerCalls.get());
            assertEquals(1, openCalls.get());
            assertEquals(1, observeCalls.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.output().path("rejectedUnavailableToolBatch").asBoolean())
                    .count());
        }
    }

    @Test
    void 显式工具搜索缺少可用目录时在主模型调用前暂停() throws Exception {
        for (boolean catalogDenied : List.of(false, true)) {
            AtomicInteger plannerCalls = new AtomicInteger();
            AtomicInteger providerCalls = new AtomicInteger();
            ModelTaskGateway planner = request -> {
                plannerCalls.incrementAndGet();
                ObjectNode selection = JsonNodeFactory.instance.objectNode();
                selection.putArray("historyIds");
                if (request.purpose().endsWith(".select_v2")) {
                    selection.putArray("searches");
                    toolIntent(selection, "missing filler query", "filler");
                } else {
                    assertEquals("context.on_demand.refine_v2", request.purpose());
                    assertTrue(request.input().path("toolCandidates").toString()
                            .contains("filler_009"));
                    selection.putArray("sourceIds");
                    toolChoice(selection, request, "discover");
                }
                return CompletableFuture.completedFuture(new ModelTaskResult(
                        selection, "planner", 1, 1, false, Map.of()));
            };
            FixtureConfig config = new FixtureConfig()
                    .context(contextConfiguration(true, 48_000, 16_000,
                            catalogDenied ? 20 : 2))
                    .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                    .planner(planner).fillerTools(10);
            if (catalogDenied) {
                Set<String> business = new java.util.LinkedHashSet<>();
                business.add("test_mutate");
                business.add("code_target");
                for (int index = 0; index < 10; index++) {
                    business.add("filler_%03d".formatted(index));
                }
                config.allowedToolNames(business);
            }
            RunBudget budget = catalogDenied ? RunBudget.UNBOUNDED : toolCallBudget(1);
            try (Fixture fixture = new Fixture(prompt -> {
                providerCalls.incrementAndGet();
                return textResponse("unexpected", 1, 1);
            }, budget, new AtomicInteger(), config)) {
                RunHandle handle = fixture.engine.start(fixture.request("find filler_009"));
                RunSnapshot snapshot = fixture.engine.get(handle.id());

                assertEquals(RunState.PAUSED, snapshot.state());
                assertEquals("context.planning_required", snapshot.output().path("kind").asText());
                String reason = snapshot.output().path("reason").asText().toLowerCase();
                assertTrue(reason.contains(catalogDenied ? "catalog" : "tool-call budget"),
                        reason);
                assertEquals(2, plannerCalls.get());
                assertEquals(0, providerCalls.get());
                assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                        .noneMatch(step -> step.kind() == AgentStep.Kind.MODEL));
            }
        }
    }

    @Test
    void 按需规划结果无效时暂停且不调用主模型() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode invalid = JsonNodeFactory.instance.objectNode();
            invalid.put("searches", "invalid");
            invalid.putArray("historyIds");
            toolIntent(invalid, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    invalid, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());

            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(1, plannerCalls.get());
            assertEquals(0, providerCalls.get());
            assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .noneMatch(step -> step.kind() == AgentStep.Kind.MODEL));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"core.tool.completed:host-evidence", "desktop-session", "t0"})
    void toolOnlyBootstrapIgnoresContextReferencesThenRoutesOpenFromHostReceipt(String wrongRef)
            throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger observes = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            assertEquals(0, providerCalls.get(),
                    "launch-to-open routing must not depend on another LIGHT response");
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            String targetTool = providerCalls.get() == 0
                    ? "desktop_session_launch_application" : "desktop_session_open";
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, targetTool, "desktop-session");
            } else {
                assertEquals("context.on_demand.refine_v2", request.purpose(),
                        "empty context candidates need no corrective model call");
                assertTrue(request.input().path("candidates").isEmpty());
                assertFalse(request.input().path("toolCandidates").isEmpty());
                selection.putArray("sourceIds").add(wrongRef);
                toolChoice(selection, request, "direct", targetTool);
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).simulatedDesktopLaunchOpenObserve(
                        launches, opens, observes, new AtomicInteger());
        try (Fixture fixture = new Fixture(prompt -> switch (providerCalls.incrementAndGet()) {
            case 1 -> {
                assertEquals(List.of("desktop_session_launch_application"), toolNames(prompt));
                yield namedToolCallResponse("desktop_session_launch_application",
                        "{\"application\":\"示例应用\"}", 2, 1);
            }
            case 2 -> {
                assertEquals(List.of("desktop_session_open"), toolNames(prompt));
                yield namedToolCallResponse("desktop_session_open",
                        "{\"targetId\":\"sample-target\",\"control\":true}", 2, 1);
            }
            case 3 -> {
                assertEquals("SUCCEEDED", toolEnvelope(prompt).path("status").asText());
                yield textResponse("desktop session opened", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        }, toolCallBudget(2), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("Launch and open the application window"));
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, plannerCalls.get(),
                    "only bootstrap select/refine use LIGHT; opening uses the durable launch receipt");
            assertEquals(3, providerCalls.get());
            assertEquals(1, launches.get());
            assertEquals(1, opens.get());
            assertEquals(0, observes.get());
            assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .noneMatch(step -> step.kind() == AgentStep.Kind.TOOL
                            && step.input().path("tool").asText().startsWith("framework_context_")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void contextReferenceCorrectionPreservesToolSelectionAndRejectsRepeatedInvalidIds(
            boolean corrected) throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Document evidence"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                return List.of(new DeferredContextCandidate("one", "v1", "saved evidence",
                        PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                fetches.incrementAndGet();
                assertEquals("one", id);
                return "CORRECTED_CONTEXT_BODY";
            }
        };
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("historyIds");
                selection.putArray("searches").addObject()
                        .put("source", "docs").put("query", "saved evidence");
                toolIntent(selection, "code_target", "code");
            } else if (request.purpose().endsWith(".refine_v2")) {
                selection.putArray("historyIds");
                selection.putArray("sourceIds").add("docs");
                toolChoice(selection, request, "direct", "code_target");
            } else {
                assertEquals("context.on_demand.repair_refine_sources_v2", request.purpose());
                assertEquals(1, request.outputSchema().path("properties").size(),
                        "context repair may not replace the selected tool or history");
                JsonNode schema = request.outputSchema().path("properties").path("sourceIds");
                assertTrue(schema.path("uniqueItems").asBoolean());
                assertEquals(1, schema.path("maxItems").asInt());
                assertEquals(List.of("docs:one"), jsonStrings(schema.path("items").path("enum")));
                selection.putArray("sourceIds").add(corrected ? "docs:one" : "docs");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true).put("fetches", 1))
                .planner(planner).source(source).autoApproveContext().codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(prompt -> {
            int call = providerCalls.incrementAndGet();
            if (call == 1) {
                assertEquals(List.of("code_target"), toolNames(prompt));
                assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                        message.getText() != null && message.getText().contains("CORRECTED_CONTEXT_BODY")));
            }
            return call == 1 ? namedToolCallResponse("code_target", "{\"value\":1}", 2, 1)
                    : textResponse("context and tool completed", 2, 1);
        }, toolCallBudget(3), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request(
                    List.of(InputBlock.text("Read the saved evidence and run the code tool")), Map.of(),
                    PermissionSet.of("context.read", "tool.execute")));
            RunSnapshot snapshot = fixture.engine.get(handle.id());
            if (corrected) {
                assertEquals(RunState.COMPLETED, awaitCompletion(fixture, handle).state(), snapshot.error());
                assertEquals(2, providerCalls.get());
                assertEquals(1, fetches.get());
                assertEquals(1, codeCalls.get());
            } else {
                assertEquals(RunState.PAUSED, snapshot.state(), snapshot.error());
                assertEquals(0, providerCalls.get());
                assertEquals(0, fetches.get());
                assertEquals(0, codeCalls.get());
            }
            assertEquals(3, plannerCalls.get());
            assertEquals(1, searches.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("repair_refine_sources_v2"))
                    .count());
        }
    }

    @Test
    void correctedContextSelectionSurvivesFetchApprovalRestartWithoutRepeatingPlanningOrSearch()
            throws Exception {
        AtomicInteger selectCalls = new AtomicInteger();
        AtomicInteger refineCalls = new AtomicInteger();
        AtomicInteger repairCalls = new AtomicInteger();
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Document evidence"; }
            @Override public PermissionSet requiredPermissions() { return PermissionSet.of("context.read"); }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                return List.of(new DeferredContextCandidate("one", "v1", "saved evidence",
                        PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                fetches.incrementAndGet();
                return "REPLAYED_CONTEXT_REPAIR_BODY";
            }
        };
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            if (request.purpose().endsWith(".select_v2")) {
                selectCalls.incrementAndGet();
                selection.putArray("historyIds");
                selection.putArray("searches").addObject()
                        .put("source", "docs").put("query", "saved evidence");
                toolIntent(selection, "");
            } else if (request.purpose().endsWith(".refine_v2")) {
                refineCalls.incrementAndGet();
                selection.putArray("historyIds");
                selection.putArray("sourceIds").add("core.tool.completed:old-search-evidence");
                toolChoice(selection, request, "none");
            } else {
                assertEquals("context.on_demand.repair_refine_sources_v2", request.purpose());
                repairCalls.incrementAndGet();
                selection.putArray("sourceIds").add("docs:one");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(source);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                    message.getText() != null && message.getText().contains("REPLAYED_CONTEXT_REPAIR_BODY")));
            return textResponse("context recovered", 2, 1);
        }, toolCallBudget(2), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request(
                    List.of(InputBlock.text("Read saved evidence")), Map.of(), PermissionSet.of("context.read")));
            for (int index = 0; index < 2; index++) {
                RunSnapshot snapshot = fixture.engine.get(handle.id());
                assertEquals(RunState.WAITING_APPROVAL, snapshot.state(), snapshot.error());
                if (index == 1) {
                    assertEquals(1, searches.get());
                    assertEquals(1, refineCalls.get());
                    assertEquals(1, repairCalls.get());
                    assertEquals(0, fetches.get());
                }
                var waiting = fixture.runs.eventsAfter(handle.id(), 0).stream()
                        .filter(event -> event.type().equals("core.run.waiting_approval"))
                        .reduce((previous, next) -> next).orElseThrow();
                String fingerprint = ToolApprovalChallenge.fromEventPayload(waiting.payload()).fingerprint();
                fixture.restart();
                handle = fixture.engine.resume(handle.id(), new ResumeCommand("tool.approval",
                        JsonNodeFactory.instance.objectNode().put("approved", true)
                                .put("fingerprint", fingerprint)));
            }
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, selectCalls.get());
            assertEquals(1, refineCalls.get());
            assertEquals(1, repairCalls.get());
            assertEquals(1, searches.get());
            assertEquals(1, fetches.get());
            assertEquals(1, providerCalls.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"permission", "planner_budget"})
    void contextRepairCannotSelectUnauthorizedOrUnadvertisedCandidates(String gate) throws Exception {
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger repairs = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        String rejected = gate.equals("permission") ? "docs:secret" : "docs:trimmed";
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Document evidence"; }
            @Override public PermissionSet requiredPermissions() { return PermissionSet.of("context.read"); }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                List<DeferredContextCandidate> results = new ArrayList<>();
                results.add(new DeferredContextCandidate("one", "v1", "allowed evidence",
                        PermissionSet.NONE));
                if (gate.equals("permission")) {
                    results.add(new DeferredContextCandidate("secret", "v1", "SECRET_CONTEXT_SUMMARY",
                            PermissionSet.of("secret.read")));
                } else {
                    for (int index = 2; index < 8; index++) {
                        results.add(new DeferredContextCandidate("doc" + index, "v1", "x".repeat(120),
                                PermissionSet.NONE));
                    }
                    results.add(new DeferredContextCandidate("trimmed", "v1", "x".repeat(120),
                            PermissionSet.NONE));
                }
                return results;
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                fetches.incrementAndGet();
                throw new AssertionError("rejected context must not be fetched");
            }
        };
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("historyIds");
                selection.putArray("searches").addObject().put("source", "docs").put("query", "evidence");
                toolIntent(selection, "code_target", "code");
            } else if (request.purpose().endsWith(".refine_v2")) {
                assertFalse(request.input().path("candidates").findValuesAsText("id").contains(rejected));
                assertFalse(request.input().toString().contains("SECRET_CONTEXT_SUMMARY"));
                selection.putArray("historyIds");
                selection.putArray("sourceIds").add(rejected);
                toolChoice(selection, request, "direct", "code_target");
            } else {
                assertEquals("context.on_demand.repair_refine_sources_v2", request.purpose());
                repairs.incrementAndGet();
                List<String> allowed = jsonStrings(request.outputSchema().path("properties")
                        .path("sourceIds").path("items").path("enum"));
                assertTrue(allowed.contains("docs:one"));
                assertFalse(allowed.contains(rejected));
                assertFalse(request.input().toString().contains("SECRET_CONTEXT_SUMMARY"));
                selection.putArray("sourceIds").add(rejected);
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true)
                        .put("plannerInputChars", gate.equals("planner_budget") ? 1_000 : 8_000))
                .planner(planner).source(source).autoApproveContext()
                .codeCalls(codeCalls).codeDescriptionPadding(300);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 2, 1);
        }, toolCallBudget(3), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request(
                    List.of(InputBlock.text("Read evidence and use the code tool")), Map.of(),
                    PermissionSet.of("context.read", "tool.execute")));
            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.PAUSED, snapshot.state(), snapshot.error());
            assertEquals(1, searches.get());
            assertEquals(1, repairs.get());
            assertEquals(0, fetches.get());
            assertEquals(0, providerCalls.get());
            assertEquals(0, codeCalls.get());
        }
    }

    @Test
    void 修正未知上下文来源后保留工具意图并完成运行() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger knowledgeSearches = new AtomicInteger();
        AtomicInteger webSearches = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        DeferredContextSource knowledge = new DeferredContextSource() {
            @Override public String id() { return "knowledge"; }
            @Override public String description() { return "Authorized knowledge"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                knowledgeSearches.incrementAndGet();
                return List.of();
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                throw new AssertionError("empty search has no candidate to fetch");
            }
        };
        DeferredContextSource web = new DeferredContextSource() {
            @Override public String id() { return "web"; }
            @Override public String description() { return "Unavailable web context"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("web.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                webSearches.incrementAndGet();
                throw new AssertionError("unauthorized web source must not be searched");
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                throw new AssertionError("unauthorized web source must not be fetched");
            }
        };
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            if (request.purpose().endsWith(".select_v2")) {
                assertEquals(1, request.input().path("sources").size());
                assertEquals("knowledge", request.input().path("sources").get(0)
                        .path("id").asText());
                selection.putArray("searches").addObject()
                        .put("source", "web").put("query", "Shanghai tomorrow");
                selection.withArray("searches").addObject()
                        .put("source", "knowledge").put("query", "Shanghai outing");
                selection.putArray("historyIds");
                toolIntent(selection, "code_target", "code");
            } else if (request.purpose().endsWith(".repair_select_sources_v2")) {
                assertEquals(1, request.outputSchema().path("properties").size(),
                        "repair may change searches only");
                selection.putArray("searches").addObject()
                        .put("source", "knowledge").put("query", "Shanghai outing");
            } else {
                assertEquals("context.on_demand.refine_v2", request.purpose());
                assertEquals(0, request.input().path("candidates").size());
                assertEquals(1, request.input().path("toolCandidates").size(),
                        "the original tool intent must survive source repair");
                selection.putArray("historyIds");
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "code_target");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(knowledge).source(web).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertEquals(List.of("code_target"), toolNames(prompt));
            return textResponse("done", 2, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("plan Shanghai outing")),
                    Map.of(), PermissionSet.of("context.read", "tool.execute"));
            RunHandle handle = fixture.engine.start(request);
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals("done", outcome.output().path("text").asText());
            assertEquals(3, plannerCalls.get());
            assertEquals(1, knowledgeSearches.get());
            assertEquals(0, webSearches.get());
            assertEquals(1, providerCalls.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText()
                                    .equals("repair_select_sources_v2"))
                    .count());
        }
    }

    @Test
    void 三条Web工具组检索经限额修正后重启复用历史与工具意图() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicReference<String> historyId = new AtomicReference<>();
        DeferredContextSource knowledge = new DeferredContextSource() {
            @Override public String id() { return "knowledge"; }
            @Override public String description() { return "Authorized knowledge"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                assertEquals("Shanghai outing", query);
                return List.of(new DeferredContextCandidate(
                        "outing", "v1", "Shanghai outing summary", PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                fetches.incrementAndGet();
                assertEquals("outing", id);
                return "LARGE_DEFERRED_BODY_MARKER Shanghai outing evidence";
            }
        };
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            if (request.purpose().endsWith(".select_v2")) {
                assertEquals(1, request.input().path("sources").size());
                assertEquals("knowledge", request.input().path("sources").get(0)
                        .path("id").asText());
                assertTrue(request.input().path("toolGroups").toString().contains("web"));
                assertEquals(1, request.input().path("history").size());
                historyId.set(request.input().path("history").get(0).path("id").asText());
                var requested = selection.putArray("searches");
                for (String query : List.of("Shanghai weather", "Shanghai sights",
                        "Shanghai restaurants")) {
                    requested.addObject().put("source", "web").put("query", query);
                }
                selection.putArray("historyIds").add(historyId.get());
                toolIntent(selection, "Shanghai outing web search", "web");
            } else if (request.purpose().endsWith(".repair_select_sources_v2")) {
                assertEquals(2, request.outputSchema().path("properties")
                        .path("searches").path("maxItems").asInt());
                assertEquals("knowledge", request.outputSchema().path("properties")
                        .path("searches").path("items").path("properties")
                        .path("source").path("enum").get(0).asText());
                assertEquals(3, request.input().path("requestedQueries").size(),
                        "all rejected queries should reach the bounded repair stage");
                assertEquals(1, request.input().path("allowedSources").size());
                selection.putArray("searches").addObject()
                        .put("source", "knowledge").put("query", "Shanghai outing");
            } else {
                assertEquals("context.on_demand.refine_v2", request.purpose());
                assertEquals(historyId.get(), request.input().path("firstSelection")
                        .path("historyIds").get(0).asText(),
                        "search repair must preserve the initial history selection");
                assertEquals("web", request.input().path("firstSelection")
                        .path("toolIntent").path("groups").get(0).asText(),
                        "search repair must preserve the business tool intent");
                assertEquals("knowledge:outing", request.input().path("candidates")
                        .get(0).path("id").asText());
                selection.putArray("historyIds").add(historyId.get());
                selection.putArray("sourceIds").add("knowledge:outing");
                toolChoice(selection, request, "direct", "web_search");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        List<Prompt> delivered = new ArrayList<>();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(knowledge).autoApproveContext()
                .webToolDescriptions(Map.of("web_search", "Search current web pages"))
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_DEFERRED_FETCH_ONCE);
        try (Fixture fixture = new Fixture(prompt -> {
            delivered.add(prompt);
            providerCalls.incrementAndGet();
            assertEquals(List.of("web_search"), toolNames(prompt));
            assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                    message.getText() != null
                            && message.getText().contains("LARGE_DEFERRED_BODY_MARKER")));
            assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                    "Earlier itinerary preference".equals(message.getText())));
            return textResponse("done", 2, 1);
        }, toolCallBudget(3), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(
                    InputBlock.message("user", "Earlier itinerary preference"),
                    InputBlock.text("plan Shanghai outing")), Map.of(),
                    PermissionSet.of("context.read", "tool.execute"));
            RunHandle handle = fixture.engine.start(request);
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state(),
                    fixture.engine.get(handle.id()).output().toString());
            assertEquals(3, plannerCalls.get(), "select, source repair, and refine");
            assertEquals(1, searches.get());
            assertEquals(1, fetches.get());
            assertEquals(0, providerCalls.get());
            AgentStep first = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("select_v2"))
                    .findFirst().orElseThrow();
            assertEquals(3, first.output().path("selection").path("searches").size());

            fixture.restart();
            RunHandle resumed = fixture.engine.resume(handle.id(),
                    new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));
            RunOutcome outcome = awaitCompletion(fixture, resumed);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals("done", outcome.output().path("text").asText());
            assertEquals(3, plannerCalls.get(), "completed planning stages must replay durably");
            assertEquals(1, searches.get(), "completed search must not repeat on resume");
            assertEquals(1, fetches.get(), "completed fetch must not repeat on resume");
            assertEquals(1, providerCalls.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText()
                                    .equals("repair_select_sources_v2"))
                    .count());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 已授权来源的三条检索仍只修正一次且无效结果安全暂停() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        DeferredContextSource knowledge = new DeferredContextSource() {
            @Override public String id() { return "knowledge"; }
            @Override public String description() { return "Authorized knowledge"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                return List.of();
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                throw new AssertionError("invalid selection must not fetch context");
            }
        };
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            var requested = selection.putArray("searches");
            if (request.purpose().endsWith(".select_v2")) {
                for (String query : List.of("weather", "sights", "restaurants")) {
                    requested.addObject().put("source", "knowledge").put("query", query);
                }
                selection.putArray("historyIds");
                toolIntent(selection, "");
            } else {
                assertEquals("context.on_demand.repair_select_sources_v2", request.purpose(),
                        "cardinality alone must trigger the bounded repair stage");
                assertEquals(2, request.outputSchema().path("properties")
                        .path("searches").path("maxItems").asInt());
                for (String query : List.of("weather", "sights", "restaurants")) {
                    requested.addObject().put("source", "knowledge").put("query", query);
                }
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(knowledge).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("plan Shanghai outing")),
                    Map.of(), PermissionSet.of("context.read"));
            RunHandle handle = fixture.engine.start(request);
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals("context.planning_required", snapshot.output().path("kind").asText());
            assertTrue(snapshot.output().path("reason").asText().contains("too many"),
                    snapshot.output().toString());
            assertEquals(2, plannerCalls.get(), "repair must run only once");
            assertEquals(0, searches.get());
            assertEquals(0, providerCalls.get());
            assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .noneMatch(step -> step.kind() == AgentStep.Kind.MODEL));

            fixture.restart();
            fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));
            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(2, plannerCalls.get(), "completed repair must replay without a new call");
            assertEquals(0, searches.get());
            assertEquals(0, providerCalls.get());
        }
    }

    @Test
    void 已完成的超额选择检查点恢复后补做修正且不重跑初始规划() throws Exception {
        AtomicInteger selects = new AtomicInteger();
        AtomicInteger repairs = new AtomicInteger();
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        DeferredContextSource knowledge = new DeferredContextSource() {
            @Override public String id() { return "knowledge"; }
            @Override public String description() { return "Authorized knowledge"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                assertEquals("Shanghai outing", query);
                return List.of();
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                throw new AssertionError("empty search must not fetch context");
            }
        };
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            var requested = selection.putArray("searches");
            if (request.purpose().endsWith(".select_v2")) {
                selects.incrementAndGet();
                for (String query : List.of("weather", "sights", "restaurants")) {
                    requested.addObject().put("source", "web").put("query", query);
                }
                selection.putArray("historyIds");
                toolIntent(selection, "");
            } else {
                assertEquals("context.on_demand.repair_select_sources_v2", request.purpose());
                if (repairs.incrementAndGet() == 1) {
                    // Seed a paused Run, then remove this new-version repair checkpoint below.
                    for (String query : List.of("weather", "sights", "restaurants")) {
                        requested.addObject().put("source", "knowledge").put("query", query);
                    }
                } else {
                    requested.addObject().put("source", "knowledge")
                            .put("query", "Shanghai outing");
                }
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(knowledge).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("done", 2, 1);
        }, toolCallBudget(1), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("plan Shanghai outing")),
                    Map.of(), PermissionSet.of("context.read"));
            RunHandle handle = fixture.engine.start(request);
            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            var persisted = new RunStepQuery(fixture.runs).steps(handle.id());
            AgentStep select = persisted.stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("select_v2"))
                    .findFirst().orElseThrow();
            AgentStep repair = persisted.stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText()
                                    .equals("repair_select_sources_v2"))
                    .findFirst().orElseThrow();
            assertEquals(3, select.output().path("selection").path("searches").size());
            assertEquals(AgentStep.State.COMPLETED, select.state());
            assertEquals(1, selects.get());
            assertEquals(1, repairs.get());
            assertEquals(0, searches.get());
            assertEquals(0, providerCalls.get());

            // Reopen a paused journal with completed select_v2 and no repair step.
            var repairEvents = fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.payload().path("stepId").asText()
                            .equals(repair.id().value()))
                    .toList();
            assertEquals(2, repairEvents.size());
            for (var event : repairEvents) {
                fixture.jdbc.update("DELETE FROM agent_run_events WHERE run_id=? AND event_sequence=?",
                        handle.id().value(), event.sequence());
            }
            assertTrue(new RunStepQuery(fixture.runs).step(handle.id(), repair.id()).isEmpty());

            fixture.restart();
            RunHandle resumed = fixture.engine.resume(handle.id(),
                    new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));
            RunOutcome outcome = awaitCompletion(fixture, resumed);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals("done", outcome.output().path("text").asText());
            assertEquals(1, selects.get(), "the completed select must be replayed");
            assertEquals(2, repairs.get(), "a missing repair checkpoint must be created once");
            assertEquals(1, searches.get());
            assertEquals(1, providerCalls.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText()
                                    .equals("repair_select_sources_v2"))
                    .count());
        }
    }

    @Test
    void 修正后仍选择未知来源则安全暂停且不调用主模型() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        DeferredContextSource knowledge = new DeferredContextSource() {
            @Override public String id() { return "knowledge"; }
            @Override public String description() { return "Authorized knowledge"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                return List.of();
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                throw new AssertionError("no context should be fetched");
            }
        };
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches").addObject()
                        .put("source", "web").put("query", "Shanghai tomorrow");
                selection.putArray("historyIds");
                toolIntent(selection, "");
            } else {
                assertEquals("context.on_demand.repair_select_sources_v2", request.purpose());
                selection.putArray("searches").addObject()
                        .put("source", "web").put("query", "Shanghai tomorrow");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(knowledge);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("plan Shanghai outing")),
                    Map.of(), PermissionSet.of("context.read", "tool.execute"));
            RunHandle handle = fixture.engine.start(request);
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state(), snapshot.error());
            assertEquals("context.planning_required", snapshot.output().path("kind").asText());
            assertEquals(TurnPausedException.Reason.UNAUTHORIZED_CONTEXT_SOURCE.name(),
                    snapshot.output().path("reasonCode").asText());
            assertEquals("web", snapshot.output().path("contextSourceId").asText());
            assertEquals(2, plannerCalls.get(), "source repair must be attempted once only");
            assertEquals(0, searches.get());
            assertEquals(0, providerCalls.get());
            assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .noneMatch(step -> step.kind() == AgentStep.Kind.MODEL));

            fixture.restart();
            fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));
            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(2, plannerCalls.get(), "persisted repair must be reused after restart");
            assertEquals(0, searches.get());
            assertEquals(0, providerCalls.get());
        }
    }

    @Test
    void 禁用工具时延迟上下文来源不可检索且不产生工具Step() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Documents"; }
            @Override public PermissionSet requiredPermissions() { return PermissionSet.NONE; }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                return List.of();
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                throw new AssertionError("disabled source must not be fetched");
            }
        };
        ModelTaskGateway planner = request -> {
            assertEquals(0, request.input().path("sources").size());
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches").addObject()
                    .put("source", "docs").put("query", "needle");
            selection.putArray("historyIds");
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(source);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunRequest request = fixture.request("read docs", Map.of(
                    "framework.disableTools", JsonNodeFactory.instance.booleanNode(true)));
            RunHandle handle = fixture.engine.start(request);

            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(0, searches.get());
            assertEquals(0, providerCalls.get());
            assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .noneMatch(step -> step.kind() == AgentStep.Kind.TOOL));
        }
    }

    @Test
    void 已完成Light任务可补全中断的规划Step且结果未知时仍暂停() throws Exception {
        for (boolean taskCompleted : List.of(true, false)) {
            AtomicInteger plannerCalls = new AtomicInteger();
            AtomicInteger providerCalls = new AtomicInteger();
            AtomicReference<ModelTaskRequest> planned = new AtomicReference<>();
            ModelTaskGateway planner = request -> {
                planned.set(request);
                plannerCalls.incrementAndGet();
                ObjectNode invalid = JsonNodeFactory.instance.objectNode();
                invalid.putArray("searches").addObject()
                        .put("source", "docs").put("query", "needle");
                invalid.putArray("historyIds");
                assertEquals("context.on_demand.select_v2", request.purpose());
                toolIntent(invalid, "");
                return CompletableFuture.completedFuture(new ModelTaskResult(
                        invalid, "planner", 1, 1, false, Map.of()));
            };
            List<Prompt> delivered = new ArrayList<>();
            FixtureConfig config = new FixtureConfig()
                    .context(contextConfiguration(true, 48_000, 16_000, 1))
                    .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                    .planner(planner);
            try (Fixture fixture = new Fixture(prompt -> {
                delivered.add(prompt);
                providerCalls.incrementAndGet();
                return textResponse("done", 2, 1);
            }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
                RunHandle handle = fixture.engine.start(fixture.request());
                assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
                assertEquals(0, providerCalls.get());
                AgentStep outer = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                        .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                                && step.input().path("phase").asText().equals("select_v2"))
                        .findFirst().orElseThrow();
                assertEquals(AgentStep.State.COMPLETED, outer.state());
                long outerCompletion = fixture.runs.eventsAfter(handle.id(), 0).stream()
                        .filter(event -> event.type().equals("core.step.completed")
                                && event.payload().path("stepId").asText().equals(outer.id().value()))
                        .findFirst().orElseThrow().sequence();
                fixture.jdbc.update("DELETE FROM agent_run_events WHERE run_id=? AND event_sequence=?",
                        handle.id().value(), outerCompletion);
                ModelTaskRequest original = planned.get();
                assertNotNull(original);
                ObjectNode taskInput = JsonNodeFactory.instance.objectNode()
                        .put("purpose", "context.on_demand.select_v2")
                        .put("attempt", 0)
                        .put("modelPolicy", "planner")
                        .put("inputHash", sha256(outer.input().path("plannerInput").toString()));
                taskInput.set("outputSchema", original.outputSchema());
                taskInput.set("messages", StepMessageCodec.messages(List.of(
                        new UserMessage(original.input().toString()))));
                StepId task = StepId.random();
                var events = StepEvents.durableSink(fixture.runs, handle.id());
                StepEvents.started(events, task, AgentStep.Kind.MODEL_TASK, taskInput, null);
                if (taskCompleted) {
                    ChatResponse valid = rawTextResponse("""
                            {"searches":[],"historyIds":[],"toolIntent":{"query":"","groups":[]}}
                            """, 3, 2);
                    StepEvents.completed(events, task, StepMessageCodec.response(valid),
                            StepMessageCodec.usage(valid));
                }

                fixture.restart();
                fixture.engine.resume(handle.id(),
                        new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));

                assertEquals(taskCompleted ? RunState.COMPLETED : RunState.PAUSED,
                        fixture.engine.get(handle.id()).state());
                assertEquals(1, plannerCalls.get(),
                        "persisted LIGHT result must not be recomputed");
                assertEquals(taskCompleted ? 1 : 0, providerCalls.get());
                if (taskCompleted) {
                    AgentStep settled = new RunStepQuery(fixture.runs).step(handle.id(), outer.id())
                            .orElseThrow();
                    assertEquals(AgentStep.State.COMPLETED, settled.state());
                    assertEquals(5, fixture.ledger.snapshot(handle.id()).inputTokens());
                    assertEquals(3, fixture.ledger.snapshot(handle.id()).outputTokens());
                    assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
                }
            }
        }
    }

    @Test
    void 已完成Light任务可补全中断的Refine规划且不重复搜索() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger fetches = new AtomicInteger();
        AtomicReference<ModelTaskRequest> refineRequest = new AtomicReference<>();
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Documents"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                return List.of(new DeferredContextCandidate(
                        "one", "v1", "relevant summary", PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                fetches.incrementAndGet();
                return "REFINED_BODY_MARKER";
            }
        };
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches").addObject()
                        .put("source", "docs").put("query", "needle");
                toolIntent(selection, "");
            } else if (request.purpose().endsWith(".refine_v2")) {
                refineRequest.set(request);
                selection.putArray("sourceIds").add("docs:missing");
                toolChoice(selection, request, "none");
            } else {
                assertEquals("context.on_demand.repair_refine_sources_v2", request.purpose());
                selection.remove("historyIds");
                selection.putArray("sourceIds").add("docs:missing");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        List<Prompt> delivered = new ArrayList<>();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(source).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            delivered.add(prompt);
            providerCalls.incrementAndGet();
            assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                    message.getText() != null
                            && message.getText().contains("REFINED_BODY_MARKER")));
            return textResponse("done", 2, 1);
        }, toolCallBudget(2), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("find needle")),
                    Map.of(), PermissionSet.of("context.read"));
            RunHandle handle = fixture.engine.start(request);
            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(3, plannerCalls.get(), "one select, one refine, one context correction");
            assertEquals(1, searches.get());
            assertEquals(0, fetches.get());
            AgentStep outer = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("refine_v2"))
                    .findFirst().orElseThrow();
            long completion = fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.step.completed")
                            && event.payload().path("stepId").asText().equals(outer.id().value()))
                    .findFirst().orElseThrow().sequence();
            fixture.jdbc.update("DELETE FROM agent_run_events WHERE run_id=? AND event_sequence=?",
                    handle.id().value(), completion);
            ModelTaskRequest original = refineRequest.get();
            assertNotNull(original);
            ObjectNode taskInput = JsonNodeFactory.instance.objectNode()
                    .put("purpose", "context.on_demand.refine_v2")
                    .put("attempt", 0)
                    .put("modelPolicy", "planner")
                    .put("inputHash", sha256(outer.input().path("plannerInput").toString()));
            taskInput.set("outputSchema", original.outputSchema());
            taskInput.set("messages", StepMessageCodec.messages(List.of(
                    new UserMessage(original.input().toString()))));
            StepId task = StepId.random();
            var events = StepEvents.durableSink(fixture.runs, handle.id());
            StepEvents.started(events, task, AgentStep.Kind.MODEL_TASK, taskInput, null);
            ChatResponse valid = rawTextResponse("""
                    {"historyIds":[],"sourceIds":["docs:one"],"toolAction":"none","toolIds":[]}
                    """, 3, 2);
            StepEvents.completed(events, task, StepMessageCodec.response(valid),
                    StepMessageCodec.usage(valid));

            fixture.restart();
            RunHandle resumed = fixture.engine.resume(handle.id(),
                    new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));
            RunOutcome outcome = awaitCompletion(fixture, resumed);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(3, plannerCalls.get(),
                    "a recovered valid refine response does not replay the obsolete correction");
            assertEquals(1, searches.get(), "durable search must be reused");
            assertEquals(1, fetches.get());
            assertEquals(1, providerCalls.get());
            assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.started")).count());
            assertEquals(AgentStep.State.COMPLETED,
                    new RunStepQuery(fixture.runs).step(handle.id(), outer.id())
                            .orElseThrow().state());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void Refine规划暂停后持久候选定义变化阻止重放到主模型() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true));
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, "code_target", "code");
            } else {
                if (request.purpose().endsWith(".refine_v2")) {
                    assertEquals(1, request.input().path("toolCandidates").size());
                    selection.putArray("sourceIds");
                } else {
                    assertEquals("context.on_demand.repair_refine_tools_v2", request.purpose());
                }
                selection.put("toolAction", "direct");
                selection.putArray("toolIds").add("forged-id");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        config.planner(planner);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("use code_target"));
            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(3, plannerCalls.get());
            List<AgentStep> retrieved = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("tool_search_v2"))
                    .toList();
            assertEquals(1, retrieved.size());
            assertEquals("code_target", retrieved.getFirst().output()
                    .path("candidates").get(0).path("name").asText());
            assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .noneMatch(step -> step.kind() == AgentStep.Kind.MODEL));

            config.codeDescriptionPadding(19);
            fixture.restart();
            fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));

            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.PAUSED, snapshot.state());
            assertTrue(snapshot.output().path("reason").asText().contains("changed"),
                    snapshot.output().toString());
            assertEquals(3, plannerCalls.get(), "persisted select and refine must be reused");
            assertEquals(0, providerCalls.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("tool_search_v2"))
                    .count());
        }
    }

    @Test
    void 在途MODEL回放前候选定义变化会在Provider调用前暂停() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, "code_target", "code");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "code_target");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).codeCalls(codeCalls)
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_ACTIVATION_ONCE);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("use code_target"));
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            assertEquals(0, providerCalls.get());
            assertEquals(2, plannerCalls.get());
            Prompt selected = config.pausedPrompt.get();
            assertNotNull(selected);
            ObjectNode modelInput = JsonNodeFactory.instance.objectNode();
            modelInput.set("messages", StepMessageCodec.messages(selected.getInstructions()));
            modelInput.set("toolNames", fixture.json.valueToTree(allToolNames(selected)));
            ObjectNode fingerprints = modelInput.putObject("toolFingerprints");
            ((ToolCallingChatOptions) selected.getOptions()).getToolCallbacks().forEach(callback ->
                    fingerprints.put(callback.getToolDefinition().name(),
                            ToolCatalogSession.fingerprint(callback)));
            modelInput.put("toolSchemaCharacters",
                    ((ToolCallingChatOptions) selected.getOptions()).getToolCallbacks().stream()
                            .mapToInt(SpringAiToolCatalog::schemaCharacters).sum());
            modelInput.put("modelPolicy", "test:model");
            modelInput.put("attempt", 1);
            AgentStep retrieval = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("tool_search_v2"))
                    .findFirst().orElseThrow();
            modelInput.put("toolCandidateStepId", retrieval.id().value());
            StepId inFlight = StepId.random();
            StepEvents.started(StepEvents.durableSink(fixture.runs, handle.id()),
                    inFlight, AgentStep.Kind.MODEL, modelInput, null);
            List<AgentStep> models = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL).toList();
            assertEquals(1, models.size());
            assertEquals(AgentStep.State.RUNNING, models.getFirst().state());

            config.codeDescriptionPadding(29);
            fixture.restart();
            fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));

            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.PAUSED, snapshot.state());
            assertTrue(snapshot.output().path("reason").asText().contains("changed"),
                    snapshot.output().toString());
            assertEquals(0, providerCalls.get());
            assertEquals(0, codeCalls.get());
            assertEquals(2, plannerCalls.get(), "MODEL replay must not rerun tool planning");
        }
    }

    @Test
    void 已完成MODEL恢复待执行工具前先核对候选定义() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, "code_target", "code");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "code_target");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).codeCalls(codeCalls)
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_ACTIVATION_ONCE);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("use code_target"));
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            assertEquals(0, providerCalls.get());
            Prompt selected = config.pausedPrompt.get();
            assertNotNull(selected);
            AgentStep retrieval = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("tool_search_v2"))
                    .findFirst().orElseThrow();
            ObjectNode modelInput = JsonNodeFactory.instance.objectNode();
            modelInput.set("messages", StepMessageCodec.messages(selected.getInstructions()));
            modelInput.set("toolNames", fixture.json.valueToTree(allToolNames(selected)));
            ObjectNode fingerprints = modelInput.putObject("toolFingerprints");
            ((ToolCallingChatOptions) selected.getOptions()).getToolCallbacks().forEach(callback ->
                    fingerprints.put(callback.getToolDefinition().name(),
                            ToolCatalogSession.fingerprint(callback)));
            modelInput.put("toolSchemaCharacters",
                    ((ToolCallingChatOptions) selected.getOptions()).getToolCallbacks().stream()
                            .mapToInt(SpringAiToolCatalog::schemaCharacters).sum());
            modelInput.put("modelPolicy", "test:model");
            modelInput.put("attempt", 1);
            modelInput.put("toolCandidateStepId", retrieval.id().value());
            StepId model = StepId.random();
            var events = StepEvents.durableSink(fixture.runs, handle.id());
            StepEvents.started(events, model, AgentStep.Kind.MODEL, modelInput, null);
            ChatResponse toolCall = namedToolCallResponse("code_target", "{\"value\":42}", 2, 1);
            StepEvents.completed(events, model, StepMessageCodec.response(toolCall),
                    StepMessageCodec.usage(toolCall));
            assertEquals(0, codeCalls.get());

            config.codeDescriptionPadding(29);
            fixture.restart();
            fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));

            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals("tool.recovery_required", snapshot.output().path("kind").asText());
            assertTrue(snapshot.output().path("reason").asText().contains("changed"),
                    snapshot.output().toString());
            assertEquals(0, codeCalls.get(), "definition drift must stop before the tool side effect");
            assertEquals(0, providerCalls.get());
        }
    }

    @Test
    void 已激活工具定义变化时在途MODEL恢复前暂停() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            selection.putArray("searches");
            toolIntent(selection, "__no_matching_tool__");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).codeCalls(codeCalls)
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_ACTIVATION_ONCE);
        try (Fixture fixture = new Fixture(prompt -> {
            assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
            providerCalls.incrementAndGet();
            return namedToolCallResponse("framework_tool_catalog",
                    "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("activate code_target"));
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            assertEquals(1, providerCalls.get());
            Prompt selected = config.pausedPrompt.get();
            assertEquals(List.of("code_target"), toolNames(selected));
            ObjectNode modelInput = JsonNodeFactory.instance.objectNode();
            modelInput.set("messages", StepMessageCodec.messages(selected.getInstructions()));
            modelInput.set("toolNames", fixture.json.valueToTree(allToolNames(selected)));
            ObjectNode fingerprints = modelInput.putObject("toolFingerprints");
            ((ToolCallingChatOptions) selected.getOptions()).getToolCallbacks().forEach(callback ->
                    fingerprints.put(callback.getToolDefinition().name(),
                            ToolCatalogSession.fingerprint(callback)));
            modelInput.put("toolSchemaCharacters",
                    ((ToolCallingChatOptions) selected.getOptions()).getToolCallbacks().stream()
                            .mapToInt(SpringAiToolCatalog::schemaCharacters).sum());
            modelInput.put("modelPolicy", "test:model");
            modelInput.put("attempt", 1);
            StepEvents.started(StepEvents.durableSink(fixture.runs, handle.id()),
                    StepId.random(), AgentStep.Kind.MODEL, modelInput, null);

            config.codeDescriptionPadding(31);
            fixture.restart();
            fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));

            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals("tool.recovery_required", snapshot.output().path("kind").asText());
            assertTrue(snapshot.output().path("reason").asText().contains("changed"),
                    snapshot.output().toString());
            assertEquals(1, providerCalls.get());
            assertEquals(0, codeCalls.get());
        }
    }

    @Test
    void 目录策略撤销后不再恢复展示目录工具() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            selection.putArray("searches");
            toolIntent(selection, "__no_matching_tool__");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .allowedToolNames(Set.of("code_target", "test_mutate", "framework_tool_catalog"))
                .planner(planner).downstreamMutation(DownstreamMutation.PAUSE_ON_CATALOG_ONCE);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("find a tool"));
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            assertEquals(0, providerCalls.get());
            Prompt selected = config.pausedPrompt.get();
            assertEquals(List.of("framework_tool_catalog"), toolNames(selected));
            ObjectNode modelInput = JsonNodeFactory.instance.objectNode();
            modelInput.set("messages", StepMessageCodec.messages(selected.getInstructions()));
            modelInput.set("toolNames", fixture.json.valueToTree(allToolNames(selected)));
            ObjectNode fingerprints = modelInput.putObject("toolFingerprints");
            ((ToolCallingChatOptions) selected.getOptions()).getToolCallbacks().forEach(callback ->
                    fingerprints.put(callback.getToolDefinition().name(),
                            ToolCatalogSession.fingerprint(callback)));
            modelInput.put("toolSchemaCharacters",
                    ((ToolCallingChatOptions) selected.getOptions()).getToolCallbacks().stream()
                            .mapToInt(SpringAiToolCatalog::schemaCharacters).sum());
            modelInput.put("modelPolicy", "test:model");
            modelInput.put("attempt", 1);
            StepEvents.started(StepEvents.durableSink(fixture.runs, handle.id()),
                    StepId.random(), AgentStep.Kind.MODEL, modelInput, null);

            config.allowedToolNames(Set.of("code_target", "test_mutate"));
            fixture.restart();
            fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));

            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals("tool.recovery_required", snapshot.output().path("kind").asText());
            assertTrue(snapshot.output().path("reason").asText().contains("no longer authorized"),
                    snapshot.output().toString());
            assertEquals(0, providerCalls.get());
        }
    }

    @Test
    void Provider两次失败后的重试仍复用同一有效工具候选() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, "code_target", "code");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "code_target");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).clarificationTool(true);
        try (Fixture fixture = new Fixture(prompt -> {
            int attempt = providerCalls.incrementAndGet();
            assertEquals(List.of("code_target"), toolNames(prompt));
            if (attempt <= 2) throw new IllegalStateException("temporary provider failure");
            return textResponse("done", 2, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("use code_target"));
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(3, providerCalls.get());
            assertEquals(2, plannerCalls.get());
            assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.model.retrying")).count());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("tool_search_v2"))
                    .count());
        }
    }

    @Test
    void 按需搜索只暴露许可候选并去重已选正文() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger plannerCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Document evidence"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                assertEquals("needle", query);
                return List.of(
                        new DeferredContextCandidate("one", "v1", "first summary",
                                PermissionSet.NONE),
                        new DeferredContextCandidate("secret", "v1", "SECRET_SUMMARY_MARKER",
                                PermissionSet.of("secret.read")),
                        new DeferredContextCandidate("two", "v1", "second summary",
                                PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String candidateId, String version) {
                fetches.incrementAndGet();
                assertTrue(Set.of("one", "two").contains(candidateId));
                assertEquals("v1", version);
                return "SOURCE_BODY_MARKER";
            }
        };
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            assertFalse(request.input().toString().contains("SOURCE_BODY_MARKER"));
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches").addObject()
                        .put("source", "docs").put("query", "needle");
                selection.putArray("historyIds");
                toolIntent(selection, "");
            } else {
                assertEquals("context.on_demand.refine_v2", request.purpose());
                assertEquals(2, request.input().path("candidates").size());
                assertFalse(request.input().toString().contains("SECRET_SUMMARY_MARKER"));
                selection.putArray("historyIds");
                selection.putArray("sourceIds").add("docs:one").add("docs:two");
                toolChoice(selection, request, "none");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(source).autoApproveContext().singleIoPermit();
        try (Fixture fixture = new Fixture(prompt -> {
            delivered.add(prompt);
            assertEquals(List.of(), toolNames(prompt));
            assertEquals(1, prompt.getInstructions().stream()
                    .filter(message -> message.getText() != null
                            && message.getText().contains("SOURCE_BODY_MARKER"))
                    .count());
            return textResponse("answer", 2, 1);
        }, toolCallBudget(3), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("find needle")),
                    Map.of(), PermissionSet.of("context.read", "tool.execute"));
            RunHandle handle = fixture.engine.start(request);
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, plannerCalls.get());
            assertEquals(1, searches.get());
            assertEquals(2, fetches.get());
            assertEquals(3, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.completed")).count());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 首个来源占满候选额度时仍搜索第二来源并可选择其证据() throws Exception {
        AtomicInteger earlySearches = new AtomicInteger();
        AtomicInteger lateSearches = new AtomicInteger();
        AtomicInteger lateFetches = new AtomicInteger();
        DeferredContextSource early = new DeferredContextSource() {
            @Override public String id() { return "early"; }
            @Override public String description() { return "Early results"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                earlySearches.incrementAndGet();
                return java.util.stream.IntStream.range(0, limit)
                        .mapToObj(index -> new DeferredContextCandidate(
                                "item" + index, "v1", "early summary " + index,
                                PermissionSet.NONE))
                        .toList();
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                throw new AssertionError("early body was not selected");
            }
        };
        DeferredContextSource late = new DeferredContextSource() {
            @Override public String id() { return "late"; }
            @Override public String description() { return "Needed evidence"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                lateSearches.incrementAndGet();
                return List.of(new DeferredContextCandidate(
                        "needed", "v1", "needed late summary", PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                lateFetches.incrementAndGet();
                assertEquals("needed", id);
                return "LATE_NEEDED_BODY";
            }
        };
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches").addObject()
                        .put("source", "early").put("query", "broad");
                selection.withArray("searches").addObject()
                        .put("source", "late").put("query", "needed");
                toolIntent(selection, "");
            } else {
                assertEquals(32, request.input().path("candidates").size());
                assertTrue(request.input().path("candidates").toString()
                        .contains("late:needed"));
                selection.putArray("sourceIds").add("late:needed");
                toolChoice(selection, request, "none");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(early).source(late).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                    message.getText() != null
                            && message.getText().contains("LATE_NEEDED_BODY")));
            return textResponse("done", 2, 1);
        }, toolCallBudget(3), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("find needed evidence")),
                    Map.of(), PermissionSet.of("context.read"));
            RunOutcome outcome = fixture.engine.start(request)
                    .completion().toCompletableFuture()
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, earlySearches.get());
            assertEquals(1, lateSearches.get());
            assertEquals(1, lateFetches.get());
        }
    }

    @Test
    void 同查询跨模型步重新搜索并按候选版本复用正文() throws Exception {
        for (boolean versionChanges : List.of(false, true)) {
            AtomicInteger providerCalls = new AtomicInteger();
            AtomicInteger searches = new AtomicInteger();
            AtomicInteger fetches = new AtomicInteger();
            AtomicInteger businessCalls = new AtomicInteger();
            List<Prompt> delivered = new ArrayList<>();
            DeferredContextSource source = new DeferredContextSource() {
                @Override public String id() { return "docs"; }
                @Override public String description() { return "Documents"; }
                @Override public PermissionSet requiredPermissions() {
                    return PermissionSet.of("context.read");
                }
                @Override public List<DeferredContextCandidate> search(
                        RunRequest request, String query, int limit) {
                    searches.incrementAndGet();
                    assertEquals("same-query", query);
                    String version = versionChanges && providerCalls.get() > 0 ? "v2" : "v1";
                    return List.of(new DeferredContextCandidate(
                            "one", version, "document summary", PermissionSet.NONE));
                }
                @Override public String fetch(RunRequest request, String id, String version) {
                    fetches.incrementAndGet();
                    assertEquals("one", id);
                    return "VERSIONED_BODY_" + version;
                }
            };
            ModelTaskGateway planner = request -> {
                if (providerCalls.get() > 0 && request.purpose().endsWith(".select_v2")) {
                    assertFalse(request.input().path("history").toString().contains("persisted:"));
                    assertFalse(request.input().path("history").toString().contains("VERSIONED_BODY_v1"));
                }
                ObjectNode selection = JsonNodeFactory.instance.objectNode();
                selection.putArray("historyIds");
                if (request.purpose().endsWith(".select_v2")) {
                    selection.putArray("searches").addObject()
                            .put("source", "docs").put("query", "same-query");
                    toolIntent(selection, providerCalls.get() == 0 ? "test_mutate" : "",
                            providerCalls.get() == 0 ? "test" : "");
                } else {
                    selection.putArray("sourceIds").add("docs:one");
                    if (providerCalls.get() == 0) {
                        toolChoice(selection, request, "direct", "test_mutate");
                    } else {
                        toolChoice(selection, request, "none");
                    }
                }
                return CompletableFuture.completedFuture(new ModelTaskResult(
                        selection, "planner", 1, 1, false, Map.of()));
            };
            FixtureConfig config = new FixtureConfig()
                    .context(contextConfiguration(true, 48_000, 16_000, 1))
                    .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                    .planner(planner).source(source).autoApproveContext()
                    .autoApproveTestMutate();
            try (Fixture fixture = new Fixture(prompt -> {
                delivered.add(prompt);
                int call = providerCalls.incrementAndGet();
                String expected = versionChanges && call == 2 ? "v2" : "v1";
                assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                        message.getText() != null && message.getText().contains(
                                "VERSIONED_BODY_" + expected)));
                if (versionChanges && call == 2) {
                    assertFalse(prompt.getInstructions().stream().anyMatch(message ->
                            message.getText() != null
                                    && message.getText().contains("VERSIONED_BODY_v1")));
                }
                return call == 1 ? toolCallResponse(2, 1) : textResponse("done", 2, 1);
            }, toolCallBudget(6), businessCalls, config)) {
                RunRequest request = fixture.request(List.of(InputBlock.text("read document")),
                        Map.of(), PermissionSet.of("context.read", "tool.execute"));
                RunHandle handle = fixture.engine.start(request);
                RunOutcome outcome = awaitCompletion(fixture, handle);

                assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
                assertEquals(2, providerCalls.get());
                assertEquals(2, searches.get(), "the same query needs a fresh search in a later step");
                assertEquals(versionChanges ? 2 : 1, fetches.get(),
                        "only an unchanged candidate ID and version may reuse its body");
                assertEquals(1, businessCalls.get());
                assertEquals(2, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                        .filter(step -> step.kind() == AgentStep.Kind.MODEL).count());
                assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
            }
        }
    }

    @Test
    void 超过业务工具结果淘汰限额的延迟正文在重启后只读取一次() throws Exception {
        String body = "LARGE_DEFERRED_BODY_MARKER" + "x".repeat(2_000);
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger plannerCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Documents"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                return List.of(new DeferredContextCandidate(
                        "large", "v1", "large document", PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                fetches.incrementAndGet();
                assertEquals("large", id);
                assertEquals("v1", version);
                return body;
            }
        };
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches").addObject()
                        .put("source", "docs").put("query", "large");
                toolIntent(selection, "");
            } else {
                selection.putArray("sourceIds").add("docs:large");
                toolChoice(selection, request, "none");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .resultEviction(JsonNodeFactory.instance.objectNode()
                        .put("enabled", true).put("maxCharacters", 1_000))
                .planner(planner).source(source).autoApproveContext()
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_DEFERRED_FETCH_ONCE);
        try (Fixture fixture = new Fixture(prompt -> {
            delivered.add(prompt);
            assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                    message instanceof ToolResponseMessage toolResponse
                            ? toolResponse.getResponses().stream()
                                    .anyMatch(response -> response.responseData().contains(body))
                            : message.getText() != null && message.getText().contains(body)));
            return textResponse("done", 2, 1);
        }, toolCallBudget(2), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("read the large document")),
                    Map.of(), PermissionSet.of("context.read"));
            RunHandle handle = fixture.engine.start(request);
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            assertEquals(1, searches.get());
            assertEquals(1, fetches.get());
            AgentStep read = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.TOOL
                            && step.input().path("tool").asText().startsWith("framework_context_fetch_"))
                    .findFirst().orElseThrow();
            assertEquals(body, read.output().path("rawOutput").path("body").asText());
            assertEquals(body, read.output().path("modelOutput").path("body").asText());

            fixture.restart();
            RunHandle resumed = fixture.engine.resume(handle.id(),
                    new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));
            RunOutcome outcome = awaitCompletion(fixture, resumed);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, searches.get());
            assertEquals(1, fetches.get());
            assertEquals(2, plannerCalls.get());
            assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.started")).count());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL).count(),
                    "resuming the same provider step must reuse its completed context reads");
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 重启后未知结果的上下文读取仍暂停且不重复执行() throws Exception {
        AtomicReference<Fixture> active = new AtomicReference<>();
        AtomicBoolean injected = new AtomicBoolean();
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger searchCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Document evidence"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searchCalls.incrementAndGet();
                return List.of();
            }
            @Override public String fetch(RunRequest request, String candidateId, String version) {
                throw new AssertionError("fetch must not run");
            }
        };
        ModelTaskGateway planner = request -> {
            assertEquals("context.on_demand.select_v2", request.purpose());
            plannerCalls.incrementAndGet();
            if (injected.compareAndSet(false, true)) {
                Fixture fixture = active.get();
                AgentStep planning = new RunStepQuery(fixture.runs)
                        .steps(request.ownerRunId()).stream()
                        .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                                && step.state() == AgentStep.State.RUNNING
                                && step.input().path("phase").asText().equals("select_v2"))
                        .findFirst().orElseThrow();
                String invocation = planning.input().path("key").asText()
                        + "/search/docs/" + sha256("needle\n32");
                ObjectNode readInput = JsonNodeFactory.instance.objectNode()
                        .put("tool", "framework_context_search_" + sha256("docs").substring(0, 12))
                        .put("invocationId", invocation);
                readInput.putObject("arguments").put("query", "needle").put("limit", 32);
                StepEvents.started(StepEvents.durableSink(fixture.runs, request.ownerRunId()),
                        StepId.tool(request.ownerRunId(), invocation), AgentStep.Kind.TOOL,
                        readInput, null);
            }
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches").addObject()
                    .put("source", "docs").put("query", "needle");
            selection.putArray("historyIds");
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(source).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, toolCallBudget(1), new AtomicInteger(), config)) {
            active.set(fixture);
            RunRequest request = fixture.request(List.of(InputBlock.text("find needle")),
                    Map.of(), PermissionSet.of("context.read"));
            RunHandle handle = fixture.engine.start(request);
            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            fixture.restart();
            fixture.engine.resume(handle.id(),
                    new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));

            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(1, plannerCalls.get());
            assertEquals(0, searchCalls.get());
            assertEquals(0, providerCalls.get());
            assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .anyMatch(step -> step.kind() == AgentStep.Kind.TOOL
                            && step.state() == AgentStep.State.RUNNING));
        }
    }

    @Test
    void 已激活工具独立容纳时不强制并列展示目录工具() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> {
                    assertTrue(toolNames(prompt).contains("framework_tool_catalog"));
                    yield namedToolCallResponse("framework_tool_catalog",
                            "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
                }
                case 2 -> {
                    assertEquals(List.of("code_target"), toolNames(prompt));
                    yield namedToolCallResponse("code_target", "{\"value\":7}", 2, 1);
                }
                case 3 -> {
                    assertEquals(List.of(), toolNames(prompt));
                    assertEquals("code_target", lastToolResponse(prompt).name());
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 2)
                        .put("maxToolSchemaCharacters", 4_000))
                .fillerTools(70).codeCalls(codeCalls).codeDescriptionPadding(3_700);
        try (Fixture fixture = new Fixture(model, toolCallBudget(2),
                new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(3, providerCalls.get());
            assertEquals(1, codeCalls.get());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 默认候选上限允许选择第十七个搜索结果() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Documents"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                return java.util.stream.IntStream.rangeClosed(1, 17)
                        .mapToObj(index -> new DeferredContextCandidate(
                                "doc" + index, "v1", "summary " + index, PermissionSet.NONE))
                        .toList();
            }
            @Override public String fetch(RunRequest request, String candidateId, String version) {
                fetches.incrementAndGet();
                assertEquals("doc17", candidateId);
                return "SEVENTEENTH_BODY_MARKER";
            }
        };
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches").addObject()
                        .put("source", "docs").put("query", "all");
                selection.putArray("historyIds");
                toolIntent(selection, "");
            } else {
                assertEquals("context.on_demand.refine_v2", request.purpose());
                assertEquals(17, request.input().path("candidates").size());
                assertTrue(request.input().path("candidates").toString()
                        .contains("docs:doc17"));
                selection.putArray("historyIds");
                selection.putArray("sourceIds").add("docs:doc17");
                toolChoice(selection, request, "none");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(source).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            delivered.add(prompt);
            assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                    message.getText() != null
                            && message.getText().contains("SEVENTEENTH_BODY_MARKER")));
            return textResponse("answer", 2, 1);
        }, toolCallBudget(2), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("read doc 17")),
                    Map.of(), PermissionSet.of("context.read"));
            RunHandle handle = fixture.engine.start(request);
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, fetches.get());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 重复搜索与长查询不会丢失可选来源候选() throws Exception {
        for (boolean duplicate : List.of(true, false)) {
            AtomicInteger searches = new AtomicInteger();
            AtomicInteger fetches = new AtomicInteger();
            String firstQuery = "A".repeat(256);
            String secondQuery = duplicate ? firstQuery : "B".repeat(256);
            DeferredContextSource source = new DeferredContextSource() {
                @Override public String id() { return "docs"; }
                @Override public String description() { return "Document evidence"; }
                @Override public PermissionSet requiredPermissions() {
                    return PermissionSet.of("context.read");
                }
                @Override public List<DeferredContextCandidate> search(
                        RunRequest request, String query, int limit) {
                    searches.incrementAndGet();
                    return List.of(new DeferredContextCandidate(
                            "one", "current", "Matching document", PermissionSet.NONE));
                }
                @Override public String fetch(RunRequest request, String id, String version) {
                    fetches.incrementAndGet();
                    return "MATCHING_DOCUMENT_BODY";
                }
            };
            ModelTaskGateway planner = request -> {
                ObjectNode selection = JsonNodeFactory.instance.objectNode();
                if (request.purpose().endsWith(".select_v2")) {
                    var requested = selection.putArray("searches");
                    requested.addObject().put("source", "docs").put("query", firstQuery);
                    requested.addObject().put("source", "docs").put("query", secondQuery);
                    selection.putArray("historyIds");
                    toolIntent(selection, "");
                } else {
                    assertEquals("context.on_demand.refine_v2", request.purpose());
                    assertTrue(request.input().toString().length() <= 1_000);
                    assertEquals("docs:one", request.input().path("candidates")
                            .get(0).path("id").asText());
                    selection.putArray("historyIds");
                    selection.putArray("sourceIds").add("docs:one");
                    toolChoice(selection, request, "none");
                }
                return CompletableFuture.completedFuture(new ModelTaskResult(
                        selection, "planner", 1, 1, false, Map.of()));
            };
            FixtureConfig config = new FixtureConfig()
                    .context(contextConfiguration(true, 48_000, 16_000, 1))
                    .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true)
                            .put("plannerInputChars", 1_000)
                            .put("searches", duplicate ? 1 : 2))
                    .planner(planner).source(source).autoApproveContext();
            try (Fixture fixture = new Fixture(prompt -> {
                assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                        message.getText() != null
                                && message.getText().contains("MATCHING_DOCUMENT_BODY")));
                return textResponse("done", 2, 1);
            }, toolCallBudget(3), new AtomicInteger(), config)) {
                RunHandle handle = fixture.engine.start(fixture.request(
                        List.of(InputBlock.text("Read the matching document")),
                        Map.of(), PermissionSet.of("context.read")));
                RunOutcome outcome = awaitCompletion(fixture, handle);
                assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
                assertEquals(duplicate ? 1 : 2, searches.get());
                assertEquals(1, fetches.get());
            }
        }
    }

    @Test
    void 按需来源的读取工具被策略拒绝时不进入规划目录() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Document evidence"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                return List.of(new DeferredContextCandidate("one", "v1", "summary",
                        PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String candidateId, String version) {
                throw new AssertionError("denied fetch must not run");
            }
        };
        ModelTaskGateway planner = request -> {
            assertFalse(request.input().path("sources").toString().contains("docs"));
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches");
            selection.putArray("historyIds");
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        String searchName = "framework_context_search_" + sha256("docs").substring(0, 12);
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(source)
                .allowedToolNames(Set.of(searchName));
        try (Fixture fixture = new Fixture(prompt -> textResponse("done", 2, 1),
                RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("search docs")),
                    Map.of(), PermissionSet.of("context.read"));
            RunOutcome outcome = fixture.engine.start(request).completion().toCompletableFuture()
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(0, searches.get());
        }
    }

    @Test
    void 按需遗漏的旧工具交换在重启后仍可按完整调用响应选回() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger mutations = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        AtomicReference<String> firstExchangeId = new AtomicReference<>();
        List<Prompt> delivered = new ArrayList<>();
        ModelTaskGateway planner = request -> {
            int number = plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            var history = selection.putArray("historyIds");
            if (providerCalls.get() == 3 && request.purpose().endsWith(".select_v2")) {
                String expected = firstExchangeId.get();
                assertNotNull(expected);
                assertTrue(request.input().path("history").findValuesAsText("id")
                        .contains(expected), "earlier durable exchange must remain a candidate");
                history.add(expected);
            } else if (providerCalls.get() == 3
                    && request.purpose().endsWith(".refine_v2")) {
                assertTrue(request.input().path("history").findValuesAsText("id")
                        .contains(firstExchangeId.get()),
                        "refinement must advertise the requested durable exchange: "
                                + request.input());
                history.add(firstExchangeId.get());
            }
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                String tool = providerCalls.get() < 2 ? "code_target"
                        : providerCalls.get() == 2 ? "test_mutate" : "";
                toolIntent(selection, tool, tool.startsWith("code") ? "code"
                        : tool.isEmpty() ? "" : "test");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct",
                        providerCalls.get() < 2 ? "code_target" : "test_mutate");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
                case 2 -> namedToolCallResponse("code_target", "{\"value\":2}", 2, 1);
                case 3 -> {
                    assertTrue(prompt.getInstructions().stream()
                            .filter(ToolResponseMessage.class::isInstance)
                            .map(ToolResponseMessage.class::cast)
                            .flatMap(response -> response.getResponses().stream())
                            .noneMatch(response -> response.name().equals("code_target")
                                    && historicalToolData(response).path("value")
                                            .asInt(-1) == 1),
                            "the first code exchange must be omitted until explicitly selected");
                    yield namedToolCallResponse("test_mutate", "{\"value\":3}", 2, 1);
                }
                case 4 -> {
                    List<ToolResponseMessage> responses = prompt.getInstructions().stream()
                            .filter(ToolResponseMessage.class::isInstance)
                            .map(ToolResponseMessage.class::cast).toList();
                    assertEquals(2, responses.size());
                    String responseData = responses.stream()
                            .flatMap(value -> value.getResponses().stream())
                            .map(value -> value.name() + "=" + value.responseData())
                            .collect(java.util.stream.Collectors.joining("; "));
                    var code = responses.stream().flatMap(value -> value.getResponses().stream())
                            .filter(value -> value.name().equals("code_target"))
                            .findFirst().orElseThrow();
                    var mutation = responses.stream().flatMap(value -> value.getResponses().stream())
                            .filter(value -> value.name().equals("test_mutate"))
                            .findFirst().orElseThrow();
                    assertEquals(1, historicalToolData(code).path("value").asInt(-1),
                            "selected exchange=" + firstExchangeId.get() + "; " + responseData);
                    assertEquals("SUCCEEDED", toolEnvelope(mutation).path("status").asText(), responseData);
                    assertEquals(3, toolEnvelope(mutation).path("data").path("observed").asInt(-1),
                            responseData);
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(model, toolCallBudget(4), mutations, config)) {
            RunHandle handle = fixture.engine.start(fixture.request("use older code result later"));
            assertEquals(RunState.WAITING_APPROVAL, fixture.engine.get(handle.id()).state());
            AgentStep firstModel = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL)
                    .findFirst().orElseThrow();
            AssistantMessage firstCall = assertInstanceOf(AssistantMessage.class,
                    StepMessageCodec.message(firstModel.output().path("message")));
            assertEquals("{\"value\":1}", firstCall.getToolCalls().getFirst().arguments(),
                    "selected durable exchange must belong to the first code call");
            firstExchangeId.set("exchange:" + firstModel.id().value());
            fixture.restart();
            var approval = JsonNodeFactory.instance.objectNode().put("approved", true)
                    .put("fingerprint", ToolInvocationFingerprint.create("test_mutate",
                            JsonNodeFactory.instance.objectNode().put("value", 3)));
            handle = fixture.engine.resume(handle.id(), new ResumeCommand("tool.approval", approval));
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(4, providerCalls.get());
            assertEquals(7, plannerCalls.get());
            assertEquals(2, codeCalls.get());
            assertEquals(1, mutations.get());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 历史规划超选和伪造ID在两阶段裁剪且重启后复用持久化选择() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        List<String> catalogIds = new ArrayList<>();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            var selected = selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                catalogIds.addAll(request.input().path("history").findValuesAsText("id"));
                assertEquals(11, catalogIds.size());
                // Deliberately choose all candidates in reverse order. The
                // catalog's chronology, not planner array order, decides recency.
                for (int i = catalogIds.size() - 1; i >= 0; i--) selected.add(catalogIds.get(i));
                selected.add("call_not_a_history_candidate");
                selection.putArray("searches").addObject()
                        .put("source", "docs").put("query", "needle");
                toolIntent(selection, "");
            } else {
                assertEquals("context.on_demand.refine_v2", request.purpose());
                assertEquals(8, request.input().path("firstSelection")
                        .path("historyIds").size());
                for (int i = catalogIds.size() - 1; i >= 0; i--) selected.add(catalogIds.get(i));
                selected.add("call_not_a_history_candidate");
                selection.putArray("sourceIds");
                selection.put("toolAction", "none");
                selection.putArray("toolIds");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Document evidence"; }
            @Override public PermissionSet requiredPermissions() { return PermissionSet.NONE; }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                return List.of(new DeferredContextCandidate("one", "v1", "summary",
                        PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                throw new AssertionError("unselected context must not be fetched");
            }
        };
        List<InputBlock> inputs = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            inputs.add(InputBlock.message("user", "HISTORY_UNIT_" + i));
        }
        inputs.add(InputBlock.text("answer current task"));
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(source);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            List<String> chosen = prompt.getInstructions().stream()
                    .map(message -> message.getText())
                    .filter(text -> text != null && text.startsWith("HISTORY_UNIT_"))
                    .toList();
            assertEquals(8, chosen.size());
            for (int i = 4; i <= 11; i++) {
                assertTrue(chosen.contains("HISTORY_UNIT_" + i));
            }
            return textResponse("done", 2, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request(inputs, Map.of()));
            assertEquals(RunState.WAITING_APPROVAL, fixture.engine.get(handle.id()).state());
            assertEquals(1, plannerCalls.get());
            assertEquals(0, providerCalls.get());
            fixture.restart();
            var waiting = fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.run.waiting_approval"))
                    .reduce((previous, next) -> next).orElseThrow();
            String fingerprint = ToolApprovalChallenge.fromEventPayload(
                    waiting.payload()).fingerprint();
            handle = fixture.engine.resume(handle.id(), new ResumeCommand("tool.approval",
                    JsonNodeFactory.instance.objectNode().put("approved", true)
                            .put("fingerprint", fingerprint)));
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, plannerCalls.get(), "completed select_v2 must replay after restart");
            assertEquals(1, providerCalls.get());
        }
    }

    @Test
    void 历史超选包含伪造ID时只保留最近合法候选() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches");
            var history = selection.putArray("historyIds");
            request.input().path("history").forEach(item -> history.add(item.path("id").asText()));
            history.add("unknown-history");
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        List<InputBlock> inputs = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            inputs.add(InputBlock.message("user", "HISTORY_UNIT_" + i));
        }
        inputs.add(InputBlock.text("answer current task"));
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            List<String> chosen = prompt.getInstructions().stream()
                    .map(message -> message.getText())
                    .filter(text -> text != null && text.startsWith("HISTORY_UNIT_"))
                    .toList();
            assertEquals(8, chosen.size());
            for (int i = 4; i <= 11; i++) {
                assertTrue(chosen.contains("HISTORY_UNIT_" + i));
            }
            return textResponse("done", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request(inputs, Map.of()));
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, providerCalls.get());
        }
    }

    @Test
    void 空历史候选中误选工具调用ID时继续执行() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            assertTrue(request.input().path("history").isEmpty());
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches");
            selection.putArray("historyIds").add("call_2ee5130fdb5b45bdaf47c16f");
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("done", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("answer current task"));
            RunOutcome outcome = awaitCompletion(fixture, handle);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, providerCalls.get());
            AgentStep selection = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("select_v2"))
                    .findFirst().orElseThrow();
            assertEquals("call_2ee5130fdb5b45bdaf47c16f",
                    selection.output().path("selection").path("historyIds").get(0).asText(),
                    "原始规划结果须保留供排查，只有最终选择被过滤");
        }
    }

    @Test
    void 按需选择重复的普通历史只注入一份() throws Exception {
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches");
            var selected = selection.putArray("historyIds");
            request.input().path("history").forEach(value -> selected.add(value.path("id").asText()));
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner);
        try (Fixture fixture = new Fixture(prompt -> {
            assertEquals(1, prompt.getInstructions().stream()
                    .filter(message -> message.getText() != null
                            && message.getText().equals("DUPLICATE_HISTORY_BODY"))
                    .count());
            return textResponse("done", 2, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(
                    InputBlock.message("user", "DUPLICATE_HISTORY_BODY"),
                    InputBlock.message("user", "DUPLICATE_HISTORY_BODY"),
                    InputBlock.text("answer the current task")), Map.of());
            RunOutcome outcome = fixture.engine.start(request).completion().toCompletableFuture()
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
        }
    }

    @Test
    void 按需规划与主模型分别记账并持久化各自的Token用量() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        ChatModel planner = prompt -> {
            plannerCalls.incrementAndGet();
            return rawTextResponse("""
                    {"searches":[],"historyIds":[],"toolIntent":{"query":"","groups":[]}}
                    """, 3, 2);
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .plannerModel(planner);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("done", 5, 4);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = awaitCompletion(fixture, handle);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, plannerCalls.get());
            assertEquals(1, providerCalls.get());
            List<AgentStep> steps = new RunStepQuery(fixture.runs).steps(handle.id());
            AgentStep auxiliary = steps.stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL_TASK)
                    .findFirst().orElseThrow();
            AgentStep main = steps.stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL)
                    .findFirst().orElseThrow();
            assertEquals(3, auxiliary.usage().path("inputTokens").asInt());
            assertEquals(2, auxiliary.usage().path("outputTokens").asInt());
            assertEquals(5, main.usage().path("inputTokens").asInt());
            assertEquals(4, main.usage().path("outputTokens").asInt());
            assertEquals(8, fixture.ledger.snapshot(handle.id()).inputTokens());
            assertEquals(6, fixture.ledger.snapshot(handle.id()).outputTokens());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.model_task.usage")).count());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.model.usage")).count());
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode contextConfiguration(
            boolean enabled, int maxMessages, int maxToolResult, int maxTools) {
        return JsonNodeFactory.instance.objectNode()
                .put("enabled", enabled)
                .put("maxMessageCharacters", maxMessages)
                .put("maxToolSchemaCharacters", 48_000)
                .put("retainedToolExchanges", 4)
                .put("maxToolResultCharacters", maxToolResult)
                .put("maxTools", maxTools);
    }

    private static RunBudget toolCallBudget(int maxToolCalls) {
        return new RunBudget(Duration.ofMinutes(5), 1_000_000, 1_000_000,
                maxToolCalls, new BigDecimal("1000"));
    }

    private static RunOutcome awaitCompletion(Fixture fixture, RunHandle handle)
            throws Exception {
        RunSnapshot snapshot = fixture.engine.get(handle.id());
        if (Set.of(RunState.PAUSED, RunState.WAITING_INPUT, RunState.WAITING_APPROVAL)
                .contains(snapshot.state())) {
            throw new AssertionError("Run cannot complete while " + snapshot.state()
                    + ": " + snapshot.output() + "; " + snapshot.error());
        }
        try {
            return handle.completion().toCompletableFuture()
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            snapshot = fixture.engine.get(handle.id());
            throw new AssertionError("Run completion timed out in " + snapshot.state()
                    + ": " + snapshot.output() + "; " + snapshot.error(), timeout);
        }
    }

    private static void toolIntent(ObjectNode selection, String query, String... groups) {
        ObjectNode intent = selection.putObject("toolIntent").put("query", query);
        var selected = intent.putArray("groups");
        for (String group : groups) {
            if (!group.isBlank()) selected.add(group);
        }
    }

    private static void toolChoice(ObjectNode selection, ModelTaskRequest request,
            String action, String... names) {
        selection.put("toolAction", action);
        var ids = selection.putArray("toolIds");
        for (String name : names) {
            String id = null;
            for (var candidate : request.input().path("toolCandidates")) {
                if (name.equals(candidate.path("name").asText())) {
                    id = candidate.path("id").asText();
                    break;
                }
            }
            assertNotNull(id, "tool must come from the authorized candidate search: " + name);
            ids.add(id);
        }
    }

    private static List<String> toolNames(Prompt prompt) {
        ToolCallingChatOptions options = assertInstanceOf(
                ToolCallingChatOptions.class, prompt.getOptions());
        var callbacks = options.getToolCallbacks();
        return (callbacks == null ? List.<org.springframework.ai.tool.ToolCallback>of() : callbacks).stream()
                .map(callback -> callback.getToolDefinition().name())
                .filter(name -> !name.equals(HarnessDecisionToolCallback.NAME)).toList();
    }

    private static List<String> allToolNames(Prompt prompt) {
        ToolCallingChatOptions options = assertInstanceOf(
                ToolCallingChatOptions.class, prompt.getOptions());
        var callbacks = options.getToolCallbacks();
        return (callbacks == null ? List.<org.springframework.ai.tool.ToolCallback>of() : callbacks).stream()
                .map(callback -> callback.getToolDefinition().name()).toList();
    }

    private static List<String> jsonStrings(com.fasterxml.jackson.databind.JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static ToolResponseMessage.ToolResponse lastToolResponse(Prompt prompt) {
        ToolResponseMessage message = prompt.getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).toList().getLast();
        return message.getResponses().getLast();
    }

    private static com.fasterxml.jackson.databind.JsonNode toolEnvelope(Prompt prompt) {
        return toolEnvelope(lastToolResponse(prompt));
    }

    private static com.fasterxml.jackson.databind.JsonNode toolEnvelope(
            ToolResponseMessage.ToolResponse response) {
        return assertDoesNotThrow(() -> new ObjectMapper()
                .readTree(response.responseData()));
    }

    /** Durable history may contain the original raw result or the current typed callback envelope. */
    private static com.fasterxml.jackson.databind.JsonNode historicalToolData(
            ToolResponseMessage.ToolResponse response) {
        var result = toolEnvelope(response);
        if (result.path("status").isTextual()) {
            assertEquals("SUCCEEDED", result.path("status").asText(), response.responseData());
            assertTrue(result.path("data").isObject(), response.responseData());
            return result.path("data");
        }
        assertTrue(result.isObject(), response.responseData());
        return result;
    }

    private static void assertJournalMatchesDeliveredPrompts(
            Fixture fixture, RunId runId, List<Prompt> delivered) {
        List<AgentStep> modelSteps = new RunStepQuery(fixture.runs).steps(runId).stream()
                .filter(step -> step.kind() == AgentStep.Kind.MODEL).toList();
        assertEquals(delivered.size(), modelSteps.size());
        for (int index = 0; index < delivered.size(); index++) {
            Prompt prompt = delivered.get(index);
            AgentStep step = modelSteps.get(index);
            assertEquals(StepMessageCodec.messages(prompt.getInstructions()),
                    step.input().path("messages"));
            assertEquals(fixture.json.valueToTree(allToolNames(prompt)),
                    step.input().path("toolNames"));
            var callbacks = ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks();
            if (callbacks == null) callbacks = List.of();
            List<org.springframework.ai.chat.messages.Message> manifests = prompt.getInstructions()
                    .stream().filter(ProviderToolManifest::isManifest).toList();
            if (!manifests.isEmpty()) {
                assertEquals(1, manifests.size(), "a provider step has exactly one current tool manifest");
                var expectedManifest = ProviderToolManifest.message(callbacks);
                HostContextBlock.Metadata block = HostContextBlock.metadata(manifests.getFirst());
                if (block != null) {
                    assertEquals(HostContextBlock.Kind.TOOL_MANIFEST, block.kind());
                    assertEquals(runId.value(), block.scope());
                    assertTrue(block.required());
                    assertEquals(HostContextBlock.mark(expectedManifest, block), manifests.getFirst());
                } else assertEquals(expectedManifest, manifests.getFirst());
                assertEquals(manifests.getFirst(), prompt.getInstructions().getLast());
            }
            if (step.input().has("toolFingerprints")) {
                ObjectNode expected = JsonNodeFactory.instance.objectNode();
                callbacks.forEach(callback ->
                        expected.put(callback.getToolDefinition().name(),
                                ToolCatalogSession.fingerprint(callback)));
                assertEquals(expected, step.input().path("toolFingerprints"));
            }
            int characters = callbacks.stream()
                    .mapToInt(callback -> callback.getToolDefinition().name().length()
                            + callback.getToolDefinition().description().length()
                            + callback.getToolDefinition().inputSchema().length())
                    .sum();
            assertEquals(characters, step.input().path("toolSchemaCharacters").asInt(-1));
        }
    }

    private static ChatResponse toolCallResponse(int inputTokens, int outputTokens) {
        return namedToolCallResponse(
                "test_mutate", "{\"value\":1}", inputTokens, outputTokens);
    }

    private static ChatResponse namedToolCallResponse(
            String toolName, String arguments, int inputTokens, int outputTokens) {
        if (toolName.equals("desktop_session_open") || toolName.equals("desktop_session_click")) {
            try {
                ObjectNode supplied = (ObjectNode) new ObjectMapper().readTree(arguments);
                if (toolName.equals("desktop_session_open")) {
                    if (!supplied.has("control")) supplied.put("control", true);
                } else {
                    if (!supplied.has("generation")) supplied.put("generation", 1);
                    if (!supplied.has("x")) supplied.put("x", 57);
                    if (!supplied.has("y")) supplied.put("y", 417);
                    if (!supplied.has("button")) supplied.put("button", 1);
                    if (!supplied.has("clicks")) supplied.put("clicks", 1);
                }
                arguments = supplied.toString();
            } catch (Exception malformed) {
                throw new IllegalArgumentException("invalid desktop fixture arguments", malformed);
            }
        }
        AssistantMessage output = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "provider-call", "function", toolName, arguments)))
                .build();
        return response(output, inputTokens, outputTokens);
    }

    private static ChatResponse textResponse(
            String text, int inputTokens, int outputTokens) {
        ObjectNode decision = JsonNodeFactory.instance.objectNode()
                .put("decision", "CLAIM_DONE").put("userMessage", text);
        decision.putArray("evidenceRefs");
        decision.putArray("unmetCriterionIds");
        String arguments = decision.toString();
        return namedToolCallResponse(HarnessDecisionToolCallback.NAME,
                arguments, inputTokens, outputTokens);
    }

    private static ChatResponse rawTextResponse(
            String text, int inputTokens, int outputTokens) {
        return response(new AssistantMessage(text), inputTokens, outputTokens);
    }

    private static ChatResponse response(
            AssistantMessage output, int inputTokens, int outputTokens) {
        return new ChatResponse(List.of(new Generation(output)),
                ChatResponseMetadata.builder().model("unknown-test-model")
                        .usage(new DefaultUsage(inputTokens, outputTokens)).build());
    }

    private static final class Fixture implements AutoCloseable {
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        private final Clock clock = Clock.systemUTC();
        private final ExtensionManager extensions;
        private final JdbcRunStore runs;
        private final JdbcTemplate jdbc;
        private RunUsageLedger ledger = new RunUsageLedger();
        private AgentEngine engine;
        private final java.util.function.Function<RunUsageLedger, AgentEngine> engineFactory;

        private Fixture(ChatModel model, RunBudget budget, AtomicInteger toolCalls) {
            this(model, budget, toolCalls, new FixtureConfig());
        }

        private Fixture(
                ChatModel model,
                RunBudget budget,
                AtomicInteger toolCalls,
                boolean clarificationTool) {
            this(model, budget, toolCalls,
                    new FixtureConfig().clarificationTool(clarificationTool));
        }

        private Fixture(ChatModel model, RunBudget budget, AtomicInteger toolCalls,
                        FixtureConfig config) {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:h2:mem:spring-reasoning-" + UUID.randomUUID()
                            + ";DB_CLOSE_DELAY=-1", "sa", "");
            new SchemaInitializer(dataSource).initialize();
            jdbc = new JdbcTemplate(dataSource);
            DataSourceTransactionManager transactions =
                    new DataSourceTransactionManager(dataSource);
            JdbcAgentDefinitionStore definitions = new JdbcAgentDefinitionStore(
                    jdbc, transactions, json, clock);
            runs = new JdbcRunStore(jdbc, transactions, json, clock);
            JdbcExecutionPlanStore plans = new JdbcExecutionPlanStore(jdbc, json, clock);

            var capabilities = new java.util.LinkedHashMap<CapabilityId,
                    com.fasterxml.jackson.databind.JsonNode>();
            if (config.context != null) {
                capabilities.put(new CapabilityId("context.compaction"), config.context);
            }
            if (config.onDemand != null) {
                capabilities.put(new CapabilityId("context.on_demand"), config.onDemand);
            }
            if (config.resultEviction != null) {
                capabilities.put(new CapabilityId("tool.result-eviction"), config.resultEviction);
            }
            AgentDefinitionDraft agent = new AgentDefinitionDraft(
                    "test.agent", "Test", "test:model", Map.of("system", "test"),
                    capabilities,
                    JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.objectNode(), RunBudget.UNBOUNDED,
                    JsonNodeFactory.instance.objectNode(), Map.of(
                            "test.tool", "=1.0.0", "context.compaction", "=2.0.0"));
            definitions.saveAgentDraft("workspace", agent, false);
            definitions.publishAgent("workspace", agent.id());
            RunProfileDraft profile = new RunProfileDraft(
                    "test.profile", "Test", PermissionSet.UNRESTRICTED,
                    budget, Map.of(), JsonNodeFactory.instance.objectNode());
            definitions.saveProfileDraft("workspace", profile, false);
            definitions.publishProfile("workspace", profile.id());

            DirectExecutor executor = new DirectExecutor(config.singleIoPermit);
            ModelTaskGateway modelTasks = config.planner == null
                    ? request -> CompletableFuture.failedFuture(
                            new AssertionError("model task not expected"))
                    : config.planner;
            extensions = new ExtensionManager(new ExtensionContext(
                    clock, Runnable::run, modelTasks));
            var artifacts = new ArrayList<ExtensionArtifact>();
            artifacts.add(ExtensionArtifact.builtin(new ToolExtension(toolCalls, config)));
            artifacts.add(ExtensionArtifact.builtin(new BuiltinCapabilityExtension(
                            "context.compaction", "Context", "Bounded context",
                            JsonNodeFactory.instance.objectNode(),
                            JsonNodeFactory.instance.objectNode(), List.of(), registrar -> {
                                if (config.downstreamMutation != DownstreamMutation.NONE) {
                                    registrar.advisor(new DownstreamAdvisorFactory(
                                            config.downstreamMutation, config.pauseAfterActivation,
                                            config.pausedPrompt));
                                }
                            })));
            if (config.onDemand != null) {
                artifacts.add(ExtensionArtifact.builtin(new BuiltinCapabilityExtension(
                        "context.on_demand", "On Demand", "Deferred context",
                        JsonNodeFactory.instance.objectNode(),
                        JsonNodeFactory.instance.objectNode(), List.of(), registrar -> {
                            config.sources.forEach(registrar::deferredContextSource);
                            config.fixedSources.forEach(registrar::fixedContextSource);
                        })));
            }
            if (config.resultEviction != null) {
                MemoryMutationGateway noMutations = new MemoryMutationGateway() {
                    @Override public com.fasterxml.jackson.databind.JsonNode applyCorrection(
                            RunId runId, RunRequest request, String input, String previous) {
                        return JsonNodeFactory.instance.nullNode();
                    }
                    @Override public com.fasterxml.jackson.databind.JsonNode protectOutput(
                            RunId runId, RunRequest request,
                            com.fasterxml.jackson.databind.JsonNode output) {
                        return output;
                    }
                    @Override public void distill(RunId runId, RunRequest request,
                            com.fasterxml.jackson.databind.JsonNode output) { }
                };
                artifacts.add(BuiltinExtensionCatalog.create(
                                (request, query, topK) -> "", noMutations,
                                (query, request) -> List.of(), (request, state) -> "",
                                context -> List.of()).stream()
                        .filter(artifact -> artifact.extension().descriptor().id()
                                .equals("tool.result-eviction"))
                        .findFirst().orElseThrow());
            }
            extensions.publish(artifacts);

            SpringAiModelRegistry models = new SpringAiModelRegistry();
            models.register("test:model", toolCapable(model));
            if (config.plannerModel != null) {
                models.register("test:planner", config.plannerModel);
                models.route("workspace", ModelTier.LIGHT, "test:planner");
            }
            ToolInvocationGateway baseToolGateway = new DefaultToolInvocationGateway(
                    (tool, arguments, owner) -> config.clarificationTool
                            || config.autoApproveTestMutate
                            || config.autoApproveContext
                                    && tool.name().startsWith("framework_context_")
                            || tool.name().equals("framework_tool_catalog")
                            || config.autoApproveDesktop
                                    && tool.name().startsWith("desktop_session_")
                            || config.systemFileRead && tool.name().equals("sys_file_read")
                            || config.autoApproveWeb && tool.group().equals("web")
                            || tool.name().startsWith("code_")
                            || tool.name().startsWith("filler_")
                            ? ToolApprovalDecision.ALLOW
                            : ToolApprovalDecision.REQUIRE_HUMAN_APPROVAL,
                    executor, clock);
            ToolInvocationGateway toolGateway = baseToolGateway;
            engineFactory = currentLedger -> {
                ModelTaskGateway reasoningTasks = config.plannerModel == null ? modelTasks
                        : new SpringAiModelTaskGateway(models, currentLedger,
                                new RunEventModelTaskAuditSink(runs), json, executor, runs);
                SpringAiReasoningGateway reasoning = new SpringAiReasoningGateway(
                        models, new SpringAiAdvisorRegistry(), toolGateway,
                        ExtensionStateStore.disabled(), currentLedger,
                        reasoningTasks,
                        runs, json, executor, config.observations);
                return config.harness
                        ? new AgentEngine(new AgentCompiler(definitions, extensions, json),
                                runs, plans, reasoning, Runnable::run, json, clock, currentLedger,
                                reasoningTasks)
                        : new AgentEngine(new AgentCompiler(definitions, extensions, json),
                                runs, plans, reasoning, Runnable::run, json, clock, currentLedger);
            };
            engine = engineFactory.apply(ledger);
        }

        private void restart() {
            engine.close();
            ledger = new RunUsageLedger();
            engine = engineFactory.apply(ledger);
        }

        private static ChatModel toolCapable(ChatModel delegate) {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    return delegate.call(prompt);
                }

                @Override
                public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
                    return ToolCallingChatOptions.builder().build();
                }
            };
        }

        private RunRequest request() {
            return request("mutate once");
        }

        private RunRequest request(String text) {
            return request(text, Map.of());
        }

        private RunRequest requestWithAllowedGroups(String... groups) {
            var allowed = JsonNodeFactory.instance.arrayNode();
            for (String group : groups) allowed.add(group);
            return request("mutate once", Map.of(ToolGroupAccess.ATTRIBUTE, allowed));
        }

        private RunRequest request(String text,
                                   Map<String, com.fasterxml.jackson.databind.JsonNode> attributes) {
            return request(List.of(InputBlock.text(text)), attributes);
        }

        private RunRequest request(List<InputBlock> inputs,
                                   Map<String, com.fasterxml.jackson.databind.JsonNode> attributes) {
            return request(inputs, attributes, PermissionSet.UNRESTRICTED);
        }

        private RunRequest request(List<InputBlock> inputs,
                                   Map<String, com.fasterxml.jackson.databind.JsonNode> attributes,
                                   PermissionSet ceiling) {
            return RunRequest.builder()
                    .agent(AgentDefinitionRef.latest("test.agent"))
                    .profile(RunProfileRef.latest("test.profile"))
                    .source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", "session"))
                    .inputs(inputs)
                    .permissionCeiling(ceiling)
                    .budget(RunBudget.UNBOUNDED)
                    .attributes(attributes)
                    .build();
        }

        @Override
        public void close() {
            engine.close();
            extensions.close();
        }
    }

    private static final class FixtureConfig {
        private boolean harness;

        private FixtureConfig harness() {
            harness = true;
            return this;
        }
        private boolean clarificationTool;
        private boolean autoApproveTestMutate;
        private boolean denyTestGroup;
        private Set<String> allowedToolNames;
        private com.fasterxml.jackson.databind.node.ObjectNode context;
        private com.fasterxml.jackson.databind.node.ObjectNode onDemand;
        private com.fasterxml.jackson.databind.node.ObjectNode resultEviction;
        private ModelTaskGateway planner;
        private ChatModel plannerModel;
        private final List<DeferredContextSource> sources = new ArrayList<>();
        private final List<FixedContextSource> fixedSources = new ArrayList<>();
        private boolean autoApproveContext;
        private boolean autoApproveDesktop;
        private boolean autoApproveWeb;
        private boolean systemFileRead;
        private boolean singleIoPermit;
        private int fillerTools;
        private Map<String, String> webToolDescriptions = Map.of();
        private Map<String, String> desktopToolDescriptions = Map.of();
        private boolean simulatedDesktopSession;
        private String simulatedApplication = "示例应用";
        private ToolRuntimeContextProvider simulatedRuntimeContext;
        private String simulatedDesktopUnavailableDetail;
        private String simulatedNavigationLabel = "设置标签";
        private boolean simulatedVisualViewEvidence;
        private boolean simulatedClickReobserve;
        private boolean simulatedUnknownDesktopDelivery;
        private boolean simulatedVisualNavigationTarget;
        private SimulatedDesktopState simulatedDesktopState;
        private AtomicInteger simulatedObserveCalls;
        private AtomicInteger simulatedClickCalls;
        private AtomicInteger simulatedOpenCalls;
        private AtomicInteger simulatedLaunchCalls;
        private int codeDescriptionPadding;
        private String codeResult = "code tool executed";
        private AtomicInteger codeCalls = new AtomicInteger();
        private OutputGuard outputGuard;
        private ObservationRegistry observations = ObservationRegistry.NOOP;
        private DownstreamMutation downstreamMutation = DownstreamMutation.NONE;
        private final AtomicInteger pauseAfterActivation = new AtomicInteger();
        private final AtomicReference<Prompt> pausedPrompt = new AtomicReference<>();

        private FixtureConfig clarificationTool(boolean value) {
            clarificationTool = value;
            return this;
        }
        private FixtureConfig autoApproveTestMutate() {
            autoApproveTestMutate = true;
            return this;
        }
        private FixtureConfig denyTestGroup() {
            denyTestGroup = true;
            return this;
        }
        private FixtureConfig allowedToolNames(Set<String> value) {
            allowedToolNames = Set.copyOf(value);
            return this;
        }
        private FixtureConfig context(com.fasterxml.jackson.databind.node.ObjectNode value) {
            context = value;
            return this;
        }
        private FixtureConfig onDemand(com.fasterxml.jackson.databind.node.ObjectNode value) {
            onDemand = value;
            return this;
        }
        private FixtureConfig resultEviction(com.fasterxml.jackson.databind.node.ObjectNode value) {
            resultEviction = value;
            return this;
        }
        private FixtureConfig planner(ModelTaskGateway value) {
            planner = value;
            return this;
        }
        private FixtureConfig plannerModel(ChatModel value) {
            plannerModel = value;
            return this;
        }
        private FixtureConfig source(DeferredContextSource value) {
            sources.add(value);
            return this;
        }
        private FixtureConfig fixedSource(FixedContextSource value) {
            fixedSources.add(value);
            return this;
        }
        private FixtureConfig autoApproveContext() {
            autoApproveContext = true;
            return this;
        }
        private FixtureConfig autoApproveDesktop() {
            autoApproveDesktop = true;
            return this;
        }
        private FixtureConfig systemFileRead() {
            systemFileRead = true;
            return this;
        }
        private FixtureConfig autoApproveWeb() {
            autoApproveWeb = true;
            return this;
        }
        private FixtureConfig simulatedApplication(String application) {
            simulatedApplication = application;
            return this;
        }
        private FixtureConfig simulatedRuntimeContext(ToolRuntimeContextProvider provider) {
            simulatedRuntimeContext = provider;
            return this;
        }
        private FixtureConfig singleIoPermit() {
            singleIoPermit = true;
            return this;
        }
        private FixtureConfig fillerTools(int value) {
            fillerTools = value;
            return this;
        }
        private FixtureConfig webToolDescriptions(Map<String, String> value) {
            webToolDescriptions = Map.copyOf(value);
            return this;
        }
        private FixtureConfig desktopToolDescriptions(Map<String, String> value) {
            desktopToolDescriptions = Map.copyOf(value);
            return this;
        }
        private FixtureConfig simulatedDesktopSession(
                AtomicInteger observeCalls, AtomicInteger clickCalls) {
            simulatedDesktopSession = true;
            simulatedDesktopState = new SimulatedDesktopState(true);
            simulatedObserveCalls = observeCalls;
            simulatedClickCalls = clickCalls;
            simulatedVisualNavigationTarget = true;
            simulatedVisualViewEvidence = true;
            desktopToolDescriptions = Map.of(
                    "desktop_session_observe", "Observe the current session frame",
                    "desktop_session_click", "Click a target in the observed frame");
            autoApproveDesktop = true;
            return this;
        }
        private FixtureConfig simulatedDesktopOpenObserve(
                AtomicInteger openCalls, AtomicInteger observeCalls) {
            simulatedDesktopSession = true;
            simulatedDesktopState = new SimulatedDesktopState(false);
            simulatedOpenCalls = openCalls;
            simulatedObserveCalls = observeCalls;
            simulatedVisualViewEvidence = true;
            desktopToolDescriptions = Map.of(
                    "desktop_session_open", "Open a controllable desktop session",
                    "desktop_session_observe", "Observe the current session frame");
            autoApproveDesktop = true;
            return this;
        }
        private FixtureConfig simulatedDesktopLaunchOpenObserve(
                AtomicInteger launchCalls, AtomicInteger openCalls, AtomicInteger observeCalls,
                AtomicInteger clickCalls) {
            simulatedDesktopOpenObserve(openCalls, observeCalls);
            simulatedLaunchCalls = launchCalls;
            simulatedClickCalls = clickCalls;
            simulatedVisualNavigationTarget = true;
            desktopToolDescriptions = Map.of(
                    "desktop_session_launch_application", "Launch an application and return a window target",
                    "desktop_session_open", "Open a controllable desktop session",
                    "desktop_session_observe", "Observe the current session frame",
                    "desktop_session_click", "Click the settings navigation tab");
            return this;
        }
        private FixtureConfig simulatedDesktopAccessDisabled(AtomicInteger launchCalls) {
            simulatedDesktopLaunchOpenObserve(launchCalls, new AtomicInteger(),
                    new AtomicInteger(), new AtomicInteger());
            simulatedDesktopUnavailableDetail = "请先在设置中开启电脑应用访问";
            return this;
        }
        private FixtureConfig simulatedClickReobserve() {
            simulatedClickReobserve = true;
            return this;
        }
        private FixtureConfig simulatedUnknownDesktopDelivery() {
            simulatedUnknownDesktopDelivery = true;
            return this;
        }
        private FixtureConfig simulatedDesktopDiscovery() {
            Map<String, String> descriptions = new java.util.LinkedHashMap<>(desktopToolDescriptions);
            descriptions.put("desktop_session_probe", "Check desktop permissions and capabilities");
            descriptions.put("desktop_session_targets", "List the application's visible windows");
            desktopToolDescriptions = Map.copyOf(descriptions);
            return this;
        }
        private FixtureConfig simulatedVisualNavigationTarget() {
            simulatedVisualNavigationTarget = true;
            simulatedVisualViewEvidence = false;
            return this;
        }
        private FixtureConfig simulatedVisualNavigationTarget(String label, boolean viewEvidence) {
            simulatedVisualNavigationTarget();
            simulatedNavigationLabel = label;
            simulatedVisualViewEvidence = viewEvidence;
            return this;
        }
        private FixtureConfig codeDescriptionPadding(int value) {
            codeDescriptionPadding = value;
            return this;
        }
        private FixtureConfig codeResult(String value) {
            codeResult = value;
            return this;
        }
        private FixtureConfig codeCalls(AtomicInteger value) {
            codeCalls = value;
            return this;
        }
        private FixtureConfig outputGuard(OutputGuard value) {
            outputGuard = value;
            return this;
        }
        private FixtureConfig observations(ObservationRegistry value) {
            observations = value;
            return this;
        }
        private FixtureConfig downstreamMutation(DownstreamMutation value) {
            downstreamMutation = value;
            return this;
        }
    }

    private enum DownstreamMutation {
        NONE,
        DROP_TOOL_RESPONSE,
        ADD_OVERSIZE_USER,
        PAUSE_AFTER_ACTIVATION_ONCE,
        PAUSE_ON_DESKTOP_OBSERVE_ONCE,
        PAUSE_AFTER_DESKTOP_INPUT_ONCE,
        PAUSE_ON_CATALOG_ONCE,
        PAUSE_AFTER_DEFERRED_FETCH_ONCE,
        PAUSE_AFTER_UNAVAILABLE_FEEDBACK_ONCE,
        PAUSE_AFTER_ARGUMENT_FEEDBACK_ONCE
    }

    private static final class DownstreamAdvisorFactory implements AdvisorSpecFactory {
        private final DownstreamMutation mutation;
        private final AtomicInteger pauseAfterActivation;
        private final AtomicReference<Prompt> pausedPrompt;

        private DownstreamAdvisorFactory(DownstreamMutation mutation,
                                         AtomicInteger pauseAfterActivation,
                                         AtomicReference<Prompt> pausedPrompt) {
            this.mutation = mutation;
            this.pauseAfterActivation = pauseAfterActivation;
            this.pausedPrompt = pausedPrompt;
        }

        @Override public CapabilityId capabilityId() {
            return new CapabilityId("context.compaction");
        }
        @Override public String advisorId() { return "test.downstream-mutation"; }
        @Override public AdvisorSpec create(com.fasterxml.jackson.databind.JsonNode configuration,
                                            CompilationContext context) {
            return new AdvisorSpec(advisorId(), "test.downstream-mutation",
                    org.springframework.ai.chat.client.advisor.ToolCallingAdvisor.DEFAULT_ORDER + 100,
                    configuration);
        }
        @Override public org.springframework.ai.chat.client.advisor.api.Advisor createAdvisor(
                AdvisorSpec specification, AdvisorRuntimeContext context) {
            return new org.springframework.ai.chat.client.advisor.api.CallAdvisor() {
                @Override public String getName() { return specification.id(); }
                @Override public int getOrder() { return specification.order(); }
                @Override
                public org.springframework.ai.chat.client.ChatClientResponse adviseCall(
                        org.springframework.ai.chat.client.ChatClientRequest request,
                        org.springframework.ai.chat.client.advisor.api.CallAdvisorChain chain) {
                    if (mutation == DownstreamMutation.PAUSE_AFTER_ACTIVATION_ONCE
                            && toolNames(request.prompt()).equals(List.of("code_target"))
                            && pauseAfterActivation.getAndIncrement() == 0) {
                        pausedPrompt.set(request.prompt());
                        throw new ToolInputRequiredException(
                                JsonNodeFactory.instance.objectNode().put("kind", "test.pause"),
                                "pause after catalog activation");
                    }
                    if (mutation == DownstreamMutation.PAUSE_ON_DESKTOP_OBSERVE_ONCE
                            && toolNames(request.prompt()).equals(List.of("desktop_session_observe"))
                            && pauseAfterActivation.getAndIncrement() == 0) {
                        pausedPrompt.set(request.prompt());
                        throw new ToolInputRequiredException(
                                JsonNodeFactory.instance.objectNode().put("kind", "test.pause"),
                                "pause before desktop observe provider call");
                    }
                    if (mutation == DownstreamMutation.PAUSE_AFTER_DESKTOP_INPUT_ONCE
                            && toolNames(request.prompt()).equals(List.of("desktop_session_observe"))
                            && request.prompt().getInstructions().stream()
                                    .filter(ToolResponseMessage.class::isInstance)
                                    .map(ToolResponseMessage.class::cast)
                                    .flatMap(message -> message.getResponses().stream())
                                    .anyMatch(response -> response.name().equals("desktop_session_click"))
                            && pauseAfterActivation.getAndIncrement() == 0) {
                        pausedPrompt.set(request.prompt());
                        throw new ToolInputRequiredException(
                                JsonNodeFactory.instance.objectNode().put("kind", "test.pause"),
                                "pause after desktop input, before its follow-up observation");
                    }
                    if (mutation == DownstreamMutation.PAUSE_ON_CATALOG_ONCE
                            && toolNames(request.prompt()).equals(List.of("framework_tool_catalog"))
                            && pauseAfterActivation.getAndIncrement() == 0) {
                        pausedPrompt.set(request.prompt());
                        throw new ToolInputRequiredException(
                                JsonNodeFactory.instance.objectNode().put("kind", "test.pause"),
                                "pause before tool catalog provider call");
                    }
                    if (mutation == DownstreamMutation.PAUSE_AFTER_DEFERRED_FETCH_ONCE
                            && request.prompt().getInstructions().stream().anyMatch(message ->
                                    message.getText() != null
                                            && message.getText().contains("LARGE_DEFERRED_BODY_MARKER"))
                            && pauseAfterActivation.getAndIncrement() == 0) {
                        throw new ToolInputRequiredException(
                                JsonNodeFactory.instance.objectNode().put("kind", "test.pause"),
                                "pause after deferred fetch");
                    }
                    if (mutation == DownstreamMutation.PAUSE_AFTER_UNAVAILABLE_FEEDBACK_ONCE
                            && request.prompt().getInstructions().stream()
                                    .filter(ToolResponseMessage.class::isInstance)
                                    .map(ToolResponseMessage.class::cast)
                                    .flatMap(message -> message.getResponses().stream())
                                    .anyMatch(response -> response.responseData()
                                            .contains("\"error\":\"tool_not_offered\""))
                            && pauseAfterActivation.getAndIncrement() == 0) {
                        pausedPrompt.set(request.prompt());
                        throw new ToolInputRequiredException(
                                JsonNodeFactory.instance.objectNode().put("kind", "test.pause"),
                                "pause after unavailable tool feedback");
                    }
                    if (mutation == DownstreamMutation.PAUSE_AFTER_ARGUMENT_FEEDBACK_ONCE
                            && request.prompt().getInstructions().stream()
                                    .filter(ToolResponseMessage.class::isInstance)
                                    .map(ToolResponseMessage.class::cast)
                                    .flatMap(message -> message.getResponses().stream())
                                    .anyMatch(response -> response.responseData()
                                            .contains("invalid_tool_arguments"))
                            && pauseAfterActivation.getAndIncrement() == 0) {
                        pausedPrompt.set(request.prompt());
                        throw new ToolInputRequiredException(
                                JsonNodeFactory.instance.objectNode().put("kind", "test.pause"),
                                "pause after invalid arguments feedback");
                    }
                    List<org.springframework.ai.chat.messages.Message> messages =
                            new ArrayList<>(request.prompt().getInstructions());
                    if (mutation == DownstreamMutation.DROP_TOOL_RESPONSE
                            && messages.getLast() instanceof ToolResponseMessage) {
                        messages.removeLast();
                    } else if (mutation == DownstreamMutation.ADD_OVERSIZE_USER) {
                        messages.add(new UserMessage("advisor-added-" + "x".repeat(5_000)));
                    }
                    return chain.nextCall(request.mutate()
                            .prompt(new Prompt(messages, request.prompt().getOptions())).build());
                }
            };
        }
    }

    private static final class ToolExtension implements AgentFrameworkExtension {
        private final AtomicInteger calls;
        private final FixtureConfig config;
        private final ExtensionDescriptor descriptor = new ExtensionDescriptor(
                "test.tool", SemanticVersion.parse("1.0.0"), ">=2.0.0 <3.0.0",
                ">=2.0.0 <3.0.0", List.of(), Set.of(), ExtensionScope.PLAN_SCOPED,
                HotUpdateCompatibility.PLAN_ISOLATED, 1, Map.of());

        private ToolExtension(AtomicInteger calls, FixtureConfig config) {
            this.calls = calls;
            this.config = config;
        }

        @Override public ExtensionDescriptor descriptor() { return descriptor; }

        @Override
        public void register(ExtensionRegistrar registrar) {
            if (config.outputGuard != null) registrar.outputGuard(config.outputGuard);
            if (config.allowedToolNames != null) {
                registrar.toolPolicy((tool, configuration, request) ->
                        config.allowedToolNames.contains(tool.name())
                                ? ToolPolicyDecision.ALLOW : ToolPolicyDecision.DENY);
            }
            if (config.denyTestGroup) {
                registrar.toolPolicy((tool, configuration, request) ->
                        tool.group().equals("test")
                                ? com.javaclaw.framework.spi.ToolPolicyDecision.DENY
                                : com.javaclaw.framework.spi.ToolPolicyDecision.ALLOW);
            }
            if (config.clarificationTool) {
                registrar.retryPolicy(new RetryPolicy() {
                    @Override public String id() { return "retry-every-failure"; }
                    @Override public int order() { return 0; }
                    @Override public java.util.Optional<RetryDirective> evaluate(RetryContext context) {
                        return java.util.Optional.of(RetryDirective.retryAfter(Duration.ZERO));
                    }
                });
            }
            registrar.tool(context -> new FrameworkTool() {
                @Override
                public ToolDescriptor descriptor() {
                    ObjectNode schema = JsonNodeFactory.instance.objectNode();
                    schema.put("type", "object");
                    String property = config.clarificationTool ? "question" : "value";
                    schema.putObject("properties").putObject(property).put("type",
                            config.clarificationTool ? "string" : "integer");
                    schema.putArray("required").add(property);
                    schema.put("additionalProperties", false);
                    return new ToolDescriptor(
                            config.clarificationTool ? "test_clarify" : "test_mutate",
                            config.clarificationTool ? "request clarification" : "mutate once",
                            schema, "test",
                            PermissionSet.of(config.clarificationTool
                                    ? "interaction.request" : "tool.execute"),
                            false);
                }

                @Override
                public com.fasterxml.jackson.databind.JsonNode execute(
                        com.fasterxml.jackson.databind.JsonNode arguments,
                        ToolExecutionContext context) {
                    calls.incrementAndGet();
                    if (config.clarificationTool) {
                        ObjectNode waiting = JsonNodeFactory.instance.objectNode();
                        waiting.put("kind", "clarify_request");
                        waiting.putObject("payload").put(
                                "question", arguments.path("question").asText());
                        throw new ToolInputRequiredException(waiting, "clarification required");
                    }
                    return JsonNodeFactory.instance.objectNode()
                            .put("observed", arguments.path("value").asInt());
                }
            });
            for (int index = 0; index < config.fillerTools; index++) {
                final String name = "filler_%03d".formatted(index);
                registrar.tool(context -> auxiliaryTool(name, "filler", new AtomicInteger(), "filler"));
            }
            config.webToolDescriptions.forEach((name, description) ->
                    registrar.tool(context -> auxiliaryTool(name, "web", new AtomicInteger(),
                            "web", description)));
            if (config.systemFileRead) {
                SpringAiAnnotatedToolRegistry hostTools = new SpringAiAnnotatedToolRegistry(
                        new ObjectMapper().findAndRegisterModules());
                hostTools.register("workspace", context -> ToolObjectBundle.of(List.of(
                        new com.javaclaw.system.SystemTools(null, Path.of("target", "screenshots")))));
                registrar.toolProvider(hostTools);
                registrar.toolPolicy((tool, configuration, request) ->
                        !tool.name().startsWith("sys_") || tool.name().equals("sys_file_read")
                                ? ToolPolicyDecision.ALLOW : ToolPolicyDecision.DENY);
            }
            if (config.simulatedDesktopSession) {
                SpringAiAnnotatedToolRegistry hostTools = new SpringAiAnnotatedToolRegistry(
                        new ObjectMapper().findAndRegisterModules());
                hostTools.register("workspace", context -> ToolObjectBundle.of(List.of(
                        simulatedDesktopTools(context, config))));
                registrar.toolProvider(hostTools);
                registrar.toolPolicy((tool, configuration, request) ->
                        tool.name().equals("framework_tool_catalog")
                                || !tool.group().equals("desktop-session")
                                || config.desktopToolDescriptions.containsKey(tool.name())
                        ? ToolPolicyDecision.ALLOW : ToolPolicyDecision.DENY);
            } else {
                config.desktopToolDescriptions.forEach((name, description) ->
                        registrar.tool(context -> auxiliaryTool(name, "desktop-session",
                                new AtomicInteger(), "desktop", description)));
            }
            if (config.context != null || config.fillerTools > 0) {
                registrar.tool(context -> auxiliaryTool(
                        "code_target", "code", config.codeCalls, config.codeResult,
                        config.codeDescriptionPadding));
            }
        }

        private static FrameworkTool auxiliaryTool(String name, String group,
                                                   AtomicInteger calls, String output) {
            return auxiliaryTool(name, group, calls, output, 0);
        }

        private static FrameworkTool auxiliaryTool(String name, String group,
                                                   AtomicInteger calls, String output,
                                                   int descriptionPadding) {
            return auxiliaryTool(name, group, calls, output,
                    name + " for catalog integration" + "d".repeat(descriptionPadding));
        }

        private static FrameworkTool auxiliaryTool(String name, String group,
                                                   AtomicInteger calls, String output,
                                                   String description) {
            return new FrameworkTool() {
                @Override public ToolDescriptor descriptor() {
                    ObjectNode schema = JsonNodeFactory.instance.objectNode();
                    schema.put("type", "object");
                    schema.putObject("properties").putObject("value").put("type", "integer");
                    schema.putArray("required").add("value");
                    schema.put("additionalProperties", false);
                    return new ToolDescriptor(name, description, schema,
                            group, PermissionSet.of("tool.execute"), false);
                }

                @Override public com.fasterxml.jackson.databind.JsonNode execute(
                        com.fasterxml.jackson.databind.JsonNode arguments,
                        ToolExecutionContext context) {
                    calls.incrementAndGet();
                    return JsonNodeFactory.instance.objectNode()
                            .put("payload", output)
                            .put("value", arguments.path("value").asInt());
                }
            };
        }

        private static DesktopSessionTools simulatedDesktopTools(ToolContext context,
                FixtureConfig config) {
            DesktopTarget target = new DesktopTarget("test", "sample-target", 42L,
                    config.simulatedApplication, config.simulatedApplication,
                    0, 0, 120, 500, DesktopTarget.VISIBLE);
            DesktopSessionService service = (DesktopSessionService) Proxy.newProxyInstance(
                    DesktopSessionService.class.getClassLoader(),
                    new Class<?>[]{DesktopSessionService.class}, (proxy, method, args) -> {
                        String operation = method.getName();
                        if (operation.equals("availability")) {
                            if (config.simulatedDesktopUnavailableDetail != null) {
                                return new DesktopAvailability(false, "test", 0,
                                        config.simulatedDesktopUnavailableDetail);
                            }
                            return new DesktopAvailability(true, "test", DesktopAvailability.CAPTURE
                                    | DesktopAvailability.SEMANTIC_INPUT, "ready");
                        }
                        if (operation.equals("discoverTargets")) return CompletableFuture.completedFuture(
                                config.simulatedDesktopState.opened()
                                        || config.simulatedLaunchCalls != null
                                                && config.simulatedLaunchCalls.get() > 0
                                        ? List.of(target) : List.of());
                        if (operation.equals("launchApplication")) {
                            if (config.simulatedDesktopUnavailableDetail != null) {
                                config.simulatedLaunchCalls.incrementAndGet();
                                return CompletableFuture.failedFuture(new IllegalStateException(
                                        config.simulatedDesktopUnavailableDetail));
                            }
                            if (!config.simulatedApplication.equals(args[1])) return CompletableFuture.failedFuture(
                                    new IllegalArgumentException("application unavailable"));
                            config.simulatedLaunchCalls.incrementAndGet();
                            return CompletableFuture.completedFuture(
                                    new DesktopApplicationLaunchResult(42L, List.of(target), "ready"));
                        }
                        if (operation.equals("open")) {
                            if (!"sample-target".equals(args[1])) return CompletableFuture.failedFuture(
                                    new IllegalArgumentException("target unavailable"));
                            config.simulatedOpenCalls.incrementAndGet();
                            config.simulatedDesktopState.open(Boolean.TRUE.equals(args[2]));
                            return CompletableFuture.completedFuture(new DesktopSessionInfo(
                                    "sample-session", target, config.simulatedDesktopState.controlGranted(), false));
                        }
                        if (operation.equals("captureObservation")) {
                            if (!config.simulatedDesktopState.opened()
                                    || !"sample-session".equals(args[1])) {
                                return CompletableFuture.completedFuture(java.util.Optional.empty());
                            }
                            int number = config.simulatedObserveCalls.incrementAndGet();
                            String observationId = "00000000-0000-4000-8000-%012d"
                                    .formatted(number);
                            config.simulatedDesktopState.observed(observationId);
                            DesktopFrame frame = new DesktopFrame("sample-target", 1,
                                    System.currentTimeMillis(), 120, 500, 120 * 4,
                                    new byte[120 * 500 * 4],
                                    config.simulatedDesktopState.targetSelected() ? 2 : 1);
                            List<DesktopElement> elements = new ArrayList<>();
                            if (config.simulatedVisualNavigationTarget) {
                                for (int index = 0; index < 40; index++) {
                                    elements.add(new DesktopElement(observationId + ":a" + index,
                                            "text", config.simulatedDesktopState.targetSelected()
                                                    ? "设置选项" : "概览行",
                                            1, 40 + index * 10, 30, 8, 0));
                                }
                            }
                            return CompletableFuture.completedFuture(java.util.Optional.of(
                                    new DesktopObservation("sample-session", observationId,
                                            frame, elements)));
                        }
                        if (operation.equals("commitObservation")) {
                            @SuppressWarnings("unchecked")
                            List<DesktopVisualRegion> regions = args.length > 3
                                    ? (List<DesktopVisualRegion>) args[3] : List.of();
                            config.simulatedDesktopState.committed(regions);
                            return CompletableFuture.completedFuture(true);
                        }
                        if (operation.equals("releaseForeground")) {
                            return CompletableFuture.completedFuture(null);
                        }
                        if (operation.equals("info")) return new DesktopSessionInfo(
                                "sample-session", target, config.simulatedDesktopState.controlGranted(), false);
                        if (operation.equals("state")) return new DesktopSessionState(
                                "sample-session", DesktopSessionState.Kind.LIVE, "ready",
                                System.currentTimeMillis());
                        if (operation.equals("perform")) {
                            DesktopAction action = (DesktopAction) args[2];
                            if (!config.simulatedDesktopState.controlGranted()) {
                                return CompletableFuture.completedFuture(new DesktopActionResult(
                                        DesktopActionResult.Status.DENIED, "session control required", 1,
                                        DesktopActionResult.Mode.NONE,
                                        DesktopActionResult.Reason.SESSION_CONTROL_REQUIRED, false,
                                        action.observationId(), DesktopActionResult.NextStep.OPEN_SESSION));
                            }
                            ObjectNode check = JsonNodeFactory.instance.objectNode()
                                    .put("sessionId", args[1].toString())
                                    .put("observationId", action.observationId())
                                    .put("elementId", action.elementId());
                            if (action.kind() != DesktopAction.Kind.CLICK
                                    || action.windowGeneration() != 1
                                    || !config.simulatedDesktopState.validClick(check)) {
                                return CompletableFuture.completedFuture(new DesktopActionResult(
                                        DesktopActionResult.Status.STALE_FRAME,
                                        "invalid observed target; no input dispatched", 1,
                                        DesktopActionResult.Mode.NONE,
                                        DesktopActionResult.Reason.STALE_OBSERVATION, false,
                                        action.observationId(), DesktopActionResult.NextStep.OBSERVE));
                            }
                            config.simulatedClickCalls.incrementAndGet();
                            if (config.simulatedUnknownDesktopDelivery) {
                                return CompletableFuture.completedFuture(new DesktopActionResult(
                                        DesktopActionResult.Status.UNKNOWN,
                                        "input may have been delivered; effect is not confirmed", 1,
                                        DesktopActionResult.Mode.BACKGROUND_SEMANTIC,
                                        DesktopActionResult.Reason.DELIVERY_UNCERTAIN, true,
                                        action.observationId(), DesktopActionResult.NextStep.OBSERVE));
                            }
                            if (config.simulatedClickReobserve) {
                                return CompletableFuture.completedFuture(new DesktopActionResult(
                                        DesktopActionResult.Status.UNSUPPORTED,
                                        "background input unavailable", 1,
                                        DesktopActionResult.Mode.NONE,
                                        DesktopActionResult.Reason.NO_SEMANTIC_PATH, false,
                                        action.observationId(), DesktopActionResult.NextStep.OBSERVE));
                            }
                            config.simulatedDesktopState.clicked();
                            return CompletableFuture.completedFuture(new DesktopActionResult(
                                    DesktopActionResult.Status.VERIFIED, "设置入口已点击", 1,
                                    DesktopActionResult.Mode.BACKGROUND_SEMANTIC,
                                    DesktopActionResult.Reason.NONE, true,
                                    action.observationId(), DesktopActionResult.NextStep.OBSERVE));
                        }
                        if (operation.equals("acknowledgeActionResult")
                                || operation.equals("markDeliveryUncertain")
                                || operation.equals("closeSession")
                                || operation.equals("closeScope")
                                || operation.equals("closeWorkspace")
                                || operation.equals("close")) return null;
                        throw new UnsupportedOperationException(operation);
                    });
            var vision = new VisionPreprocessor(request -> {
                assertEquals("vision.desktop.structured", request.purpose());
                String page = config.simulatedDesktopState.page();
                ObjectNode output = JsonNodeFactory.instance.objectNode()
                        .put("summary", page)
                        .put("visibleText", page + "\n" + config.simulatedDesktopState.subject());
                var targets = output.putArray("targets");
                if (config.simulatedVisualNavigationTarget) {
                    for (int index = 0; index < 7; index++) {
                        targets.addObject().put("label", "占位控件" + index)
                                .put("role", "button").put("x", 1).put("y", 40 + index * 20)
                                .put("width", 10).put("height", 10).put("confidence", 0.9);
                    }
                    targets.addObject().put("label", config.simulatedNavigationLabel)
                            .put("role", "tab").put("x", 35).put("y", 399)
                            .put("width", 44).put("height", 36).put("confidence", 0.94);
                }
                if (!config.simulatedVisualNavigationTarget
                        || config.simulatedVisualViewEvidence) {
                    String subject = config.simulatedDesktopState.subject();
                    ObjectNode view = output.putObject("activeView")
                            .put("label", subject).put("confidence", 0.95);
                    view.putObject("heading").put("label", subject).put("role", "heading")
                            .put("x", 1).put("y", 1).put("width", 25).put("height", 10)
                            .put("confidence", 0.95);
                    view.putObject("content").put("label", page).put("role", "content")
                            .put("x", 1).put("y", 20).put("width", 100).put("height", 20)
                            .put("confidence", 0.95);
                }
                return CompletableFuture.completedFuture(new ModelTaskResult(
                        output, "test-vision", 1, 1, false, Map.of()));
            }, context.runId());
            DesktopSessionOwner owner = new DesktopSessionOwner(
                    context.scope().workspaceId(), context.scope().sessionId(),
                    "chat", context.runId().value());
            return new DesktopSessionTools(service, owner,
                    Path.of("target", "simulated-desktop-screenshots"), null, vision,
                    config.simulatedRuntimeContext);
        }
    }

    private static final class SimulatedDesktopState {
        private boolean opened;
        private boolean controlGranted;
        private boolean targetSelected;
        private String lastObservationId = "";
        private Set<String> committedTargets = Set.of();

        private SimulatedDesktopState(boolean opened) {
            this.opened = opened;
            this.controlGranted = opened;
        }

        private synchronized boolean opened() {
            return opened;
        }

        private synchronized void open(boolean requestControl) {
            opened = true;
            if (requestControl && !controlGranted) {
                controlGranted = true;
                lastObservationId = "";
                committedTargets = Set.of();
            }
        }

        private synchronized boolean controlGranted() { return controlGranted; }

        private synchronized void observed(String observationId) {
            lastObservationId = observationId;
            committedTargets = Set.of();
        }

        private synchronized void committed(List<DesktopVisualRegion> regions) {
            committedTargets = regions.stream().map(DesktopVisualRegion::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        private synchronized boolean validClick(com.fasterxml.jackson.databind.JsonNode arguments) {
            String observationId = arguments.path("observationId").asText("");
            String elementId = arguments.path("elementId").asText("");
            if (!opened || targetSelected || lastObservationId.isBlank()
                    || !lastObservationId.equals(observationId)
                    || !elementId.equals(observationId + ":v7")
                    || !committedTargets.contains(elementId)
                    || !arguments.path("sessionId").asText().equals("sample-session")) {
                return false;
            }
            lastObservationId = "";
            committedTargets = Set.of();
            return true;
        }

        private synchronized void clicked() {
            targetSelected = true;
        }

        private synchronized boolean targetSelected() {
            return targetSelected;
        }

        private synchronized String subject() {
            return targetSelected ? "settings" : "overview";
        }

        private synchronized String page() {
            return targetSelected ? "示例应用 设置页面" : "示例应用 概览页面";
        }
    }

    private static final class DirectExecutor implements CancellableTaskExecutor {
        private final boolean singlePermit;
        private final AtomicInteger active = new AtomicInteger();

        private DirectExecutor(boolean singlePermit) { this.singlePermit = singlePermit; }

        @Override public void execute(Runnable command) { command.run(); }

        @Override
        public <T> CancellableTask<T> submit(
                String name, Duration timeout, CancellationToken cancellation, Callable<T> task) {
            CompletableFuture<T> completion = new CompletableFuture<>();
            boolean acquired = false;
            try {
                cancellation.throwIfCancelled();
                if (singlePermit && !active.compareAndSet(0, 1)) {
                    throw new IllegalStateException("nested execution exceeded one I/O permit");
                }
                acquired = singlePermit;
                completion.complete(task.call());
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            } finally {
                if (acquired) active.set(0);
            }
            return new CancellableTask<>() {
                @Override public java.util.concurrent.CompletionStage<T> completion() {
                    return completion;
                }
                @Override public java.util.concurrent.CompletionStage<Void> termination() {
                    return CompletableFuture.completedFuture(null);
                }
                @Override public boolean cancel() { return false; }
            };
        }
    }
}
