package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.CapabilityMetadata;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskCriterionV3;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static com.javaclaw.framework.api.TaskContractV3.DesktopObservationPolicy.LEGACY_WINDOW;
import static com.javaclaw.framework.api.TaskContractV3.DesktopObservationPolicy.REQUIRED_SUBJECT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskObservationContractValidationTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final TrustedCapabilityRegistry CAPABILITIES = TrustedCapabilityRegistry.builtins();

    @Test
    void newModelObservationsCannotOmitOrBlankTheSubjectForAnyApplication() {
        for (String target : List.of("QQ", "Calendar", "org.example.application")) {
            for (String subject : List.of("<missing>", "", "  ")) {
                AtomicInteger calls = new AtomicInteger();
                ModelTaskGateway planner = task -> {
                    calls.incrementAndGet();
                    assertTrue(task.outputSchema().path("properties").path("criteria").path("items")
                            .path("required").toString().contains("requiredSubject"));
                    assertFalse(task.outputSchema().path("properties").has("desktopObservationPolicy"));
                    return answer(plan(target, subject));
                };
                TaskContractV3 compiled = new TaskContractCompiler(planner, JSON).compileV3(
                        RunId.random(), request("Inspect the requested content in " + target), () -> false);
                assertFalse(compiled.reliable(), target + "/" + subject);
                assertTrue(compiled.reasonCodes().contains("MISSING_OBSERVABLE_SUBJECT"));
                assertEquals(REQUIRED_SUBJECT, compiled.desktopObservationPolicy());
                assertEquals(2, calls.get());
            }
        }
    }

    @Test
    void aRepairCanSupplyTheMissingLogicalSubjectWithoutGuessingAVisibleTitle() {
        AtomicInteger calls = new AtomicInteger();
        ModelTaskGateway planner = task -> answer(plan("Calendar",
                calls.incrementAndGet() == 1 ? "<missing>" : "tomorrow's events"));
        TaskContractV3 compiled = new TaskContractCompiler(planner, JSON).compileV3(
                RunId.random(), request("Inspect tomorrow's events in Calendar"), () -> false);

        assertTrue(compiled.reliable());
        assertEquals("model-repair", compiled.source());
        assertEquals(REQUIRED_SUBJECT, compiled.desktopObservationPolicy());
        assertEquals("tomorrow's events", compiled.criteria().getFirst().requiredSubject());
        assertEquals(2, calls.get());
    }

    @Test
    void aWindowObservationUsesAnExplicitLogicalSubjectInTheRuntimeConditions() {
        ModelTaskGateway planner = task -> answer(plan("Calendar", "current Calendar window contents"));
        TaskContractV3 compiled = new TaskContractCompiler(planner, JSON).compileV3(
                RunId.random(), request("Inspect the current Calendar window"), () -> false);
        var context = new TaskAcceptanceContext(request("inspect").withAttribute(
                TaskAcceptanceContext.ATTRIBUTE, JSON.valueToTree(compiled)), List::of).currentContext();

        assertTrue(compiled.reliable());
        assertEquals(1, context.size());
        assertEquals("current Calendar window contents",
                context.getFirst().path("conditions").get(0).path("subject").asText());
    }

    @Test
    void modelOutputCannotChooseTheLegacyWindowPolicyToAvoidTheSubjectRequirement() {
        ModelTaskGateway planner = task -> answer(plan("Calendar", "<missing>")
                .put("desktopObservationPolicy", "LEGACY_WINDOW"));
        TaskContractV3 compiled = new TaskContractCompiler(planner, JSON).compileV3(
                RunId.random(), request("Inspect tomorrow's events in Calendar"), () -> false);

        assertFalse(compiled.reliable());
        assertEquals(REQUIRED_SUBJECT, compiled.desktopObservationPolicy());
        assertTrue(compiled.reasonCodes().contains("MISSING_OBSERVABLE_SUBJECT"));
    }

    @Test
    void repairFailureKeepsTheHostObservationPolicyAndMissingSubjectDiagnostic() {
        AtomicInteger calls = new AtomicInteger();
        ModelTaskGateway planner = task -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("fixture repair failure");
            return answer(plan("Calendar", "<missing>"));
        };
        TaskContractV3 compiled = new TaskContractCompiler(planner, JSON).compileV3(
                RunId.random(), request("Inspect tomorrow's events in Calendar"), () -> false);

        assertFalse(compiled.reliable());
        assertEquals(REQUIRED_SUBJECT, compiled.desktopObservationPolicy());
        assertTrue(compiled.reasonCodes().contains("MISSING_OBSERVABLE_SUBJECT"));
        assertTrue(compiled.reasonCodes().contains("PLANNING_REPAIR_FAILED"));
    }

    @Test
    void invalidStrictContractCannotUseAWindowReceiptOrGeneratePartialAcceptanceContext() {
        TaskContractV3 strict = new TaskContractV3(3, "Inspect tomorrow's events", List.of(
                observation("missing", ""), observation("known", "tomorrow's events")), true, true,
                "model", List.of(), List.of(), REQUIRED_SUBJECT);
        var evidence = linkedWindowReceipts();

        assertEquals(TaskOutcome.UNVERIFIED,
                TaskResultEvaluator.evaluateV3(strict, evidence, "", CAPABILITIES).outcome());
        assertFalse(TaskResultEvaluator.desktopContract(strict, CAPABILITIES).reliable());
        var delegated = JSON.createObjectNode().put("kind", "desktop.access");
        var context = new TaskAcceptanceContext(request("inspect").withAttribute(
                TaskAcceptanceContext.ATTRIBUTE, JSON.valueToTree(strict)), () -> List.of(delegated))
                .currentContext();
        assertEquals(List.of(delegated), context);
    }

    @Test
    void legacyPersistedWindowObservationWithoutTheNewFieldRemainsCompatible() throws Exception {
        TaskContractV3 legacy = new TaskContractV3(3, "Inspect the current Calendar window", List.of(
                new TaskCriterionV3("open", "Calendar window", "desktop.open",
                        CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "Calendar",
                        EffectReceiptV1.Status.ACCEPTED, ""), observation("view", "")),
                true, true, "model");
        ObjectNode persisted = JSON.valueToTree(legacy);
        persisted.remove("desktopObservationPolicy");
        TaskContractV3 decoded = JSON.treeToValue(persisted, TaskContractV3.class);

        assertEquals(LEGACY_WINDOW, decoded.desktopObservationPolicy());
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, TaskResultEvaluator.evaluateV3(decoded,
                linkedWindowReceipts(), "", CAPABILITIES).outcome());
        TaskContractV3 definition = new TaskContractCompiler(null, JSON).compileV3(RunId.random(),
                request("Inspect Calendar").withAttribute(TaskContractCompiler.ATTRIBUTE, persisted), () -> false);
        assertTrue(definition.reliable());
        assertEquals(LEGACY_WINDOW, definition.desktopObservationPolicy());
    }

    @Test
    void explicitStrictDefinitionsAndJsonRoundTripsKeepTheSubjectPolicy() throws Exception {
        TaskContractV3 strict = new TaskContractV3(3, "Inspect Calendar", List.of(observation("view", "")),
                true, true, "definition", List.of(), List.of(), REQUIRED_SUBJECT);
        TaskContractV3 persisted = JSON.treeToValue(JSON.valueToTree(strict), TaskContractV3.class);
        assertEquals(REQUIRED_SUBJECT, persisted.desktopObservationPolicy());
        TaskContractV3 validated = new TaskContractCompiler(null, JSON).compileV3(RunId.random(),
                request("Inspect Calendar").withAttribute(TaskContractCompiler.ATTRIBUTE,
                        JSON.valueToTree(persisted)), () -> false);

        assertFalse(validated.reliable());
        assertEquals(REQUIRED_SUBJECT, validated.desktopObservationPolicy());
        assertTrue(validated.reasonCodes().contains("MISSING_OBSERVABLE_SUBJECT"));
    }

    @Test
    void theSubjectPolicyDoesNotRequireViewSubjectsForOrdinaryActions() {
        ModelTaskGateway planner = task -> {
            var plan = plan("Calendar", "");
            ((ObjectNode) plan.path("criteria").get(0)).put("capabilityId", "desktop.launch")
                    .put("requiredEvidence", "ACCEPTED");
            return answer(plan);
        };
        TaskContractV3 compiled = new TaskContractCompiler(planner, JSON).compileV3(
                RunId.random(), request("Launch Calendar"), () -> false);

        assertTrue(compiled.reliable());
        assertEquals(REQUIRED_SUBJECT, compiled.desktopObservationPolicy());
    }

    @Test
    void structuredResolvedIntentLetsTheHostDeriveReliabilityAfterSubjectValidation() {
        ModelTaskGateway planner = task -> {
            var schema = task.outputSchema();
            assertTrue(schema.path("required").toString().contains("intentStatus"));
            assertFalse(schema.path("properties").has("reliable"));
            return answer(withIntent(plan("Calendar", "tomorrow's events"), "RESOLVED"));
        };
        TaskContractV3 compiled = new TaskContractCompiler(planner, JSON).compileV3(
                RunId.random(), request("Inspect tomorrow's events in Calendar"), () -> false);

        assertTrue(compiled.reliable());
        assertEquals(REQUIRED_SUBJECT, compiled.desktopObservationPolicy());
        assertTrue(compiled.reasonCodes().isEmpty());
    }

    @Test
    void genuineMissingHumanChoiceAndUnsupportedIntentRemainUnreliable() {
        for (String status : List.of("NEEDS_HUMAN", "UNSUPPORTED")) {
            ObjectNode output = withIntent(plan("Calendar", "tomorrow's events"), status);
            output.putArray("reasonCodes").add(status.equals("NEEDS_HUMAN")
                    ? "ACCOUNT_CHOICE_REQUIRED" : "REQUIRED_CAPABILITY_UNAVAILABLE");
            if (status.equals("NEEDS_HUMAN")) output.putArray("unresolvedInputs").add("Which requested account?");
            ModelTaskGateway planner = task -> answer(output);
            TaskContractV3 compiled = new TaskContractCompiler(planner, JSON).compileV3(
                    RunId.random(), request("Inspect events in the particular account I choose"), () -> false);

            assertFalse(compiled.reliable());
            assertFalse(compiled.reasonCodes().contains("INVALID_PLAN"));
            assertEquals(status.equals("NEEDS_HUMAN") ? List.of("Which requested account?") : List.of(),
                    compiled.unresolvedInputs());
        }
    }

    @Test
    void contradictoryOrMalformedIntentCannotBecomeAResolvedContract() {
        var contradictoryLegacy = withIntent(plan("Calendar", "tomorrow's events"), "RESOLVED")
                .put("reliable", false);
        var missingHumanInputs = withIntent(plan("Calendar", "tomorrow's events"), "NEEDS_HUMAN");
        missingHumanInputs.putArray("reasonCodes").add("ACCOUNT_CHOICE_REQUIRED");
        var emptyHumanInputs = missingHumanInputs.deepCopy();
        emptyHumanInputs.putArray("unresolvedInputs").add("  ");
        var contradictoryResolved = withIntent(plan("Calendar", "tomorrow's events"), "RESOLVED");
        contradictoryResolved.putArray("unresolvedInputs").add("Which account?");
        var unknownStatus = withIntent(plan("Calendar", "tomorrow's events"), "UNCERTAIN");
        var unsupportedWithoutReason = withIntent(plan("Calendar", "tomorrow's events"), "UNSUPPORTED");
        for (ObjectNode output : List.of(contradictoryLegacy, missingHumanInputs, emptyHumanInputs,
                contradictoryResolved, unknownStatus, unsupportedWithoutReason)) {
            ModelTaskGateway planner = task -> answer(output);
            TaskContractV3 compiled = new TaskContractCompiler(planner, JSON).compileV3(
                    RunId.random(), request("Inspect tomorrow's events in Calendar"), () -> false);

            assertFalse(compiled.reliable(), output.toString());
            assertTrue(compiled.reasonCodes().contains("INVALID_PLAN"), output.toString());
        }
    }

    @Test
    void oldModelFalseReliabilityIsPreservedInsteadOfUpgradedFromRuntimeWords() {
        var oldOutput = plan("Calendar", "tomorrow's events").put("reliable", false);
        oldOutput.putArray("unresolvedInputs").add("application login status and account context");
        ModelTaskGateway planner = task -> answer(oldOutput);
        TaskContractV3 compiled = new TaskContractCompiler(planner, JSON).compileV3(
                RunId.random(), request("Inspect tomorrow's events in Calendar"), () -> false);

        assertFalse(compiled.reliable());
        assertTrue(compiled.reasonCodes().contains("MODEL_UNRELIABLE"));
        assertEquals(List.of("application login status and account context"), compiled.unresolvedInputs());
    }

    @Test
    void structuredResolvedIntentUsesTheHumanHistoryGoalWithoutRequiringLegacyReliability() {
        String goal = "Inspect tomorrow's events in Calendar";
        ModelTaskGateway planner = task -> answer(withIntent(plan("Calendar", "tomorrow's events"), "RESOLVED")
                .put("originalRequest", goal));
        var request = requestWithHistory("Continue", goal);
        TaskContractV3 compiled = new TaskContractCompiler(planner, JSON).compileV3(
                RunId.random(), request, () -> false);

        assertTrue(compiled.reliable());
        assertEquals(goal, compiled.originalRequest());
    }

    @Test
    void contradictoryStructuredIntentCannotReplaceTheCurrentHumanRequestFromHistory() {
        ModelTaskGateway planner = task -> answer(withIntent(plan("Calendar", "tomorrow's events"), "RESOLVED")
                .put("reliable", false).put("originalRequest", "an unauthorized old goal"));
        TaskContractV3 compiled = new TaskContractCompiler(planner, JSON).compileV3(RunId.random(),
                requestWithHistory("Stop and explain records", "Inspect Calendar"), () -> false);

        assertFalse(compiled.reliable());
        assertEquals("Stop and explain records", compiled.originalRequest());
        assertTrue(compiled.reasonCodes().contains("INVALID_PLAN"));
    }

    private static ObjectNode withIntent(ObjectNode output, String status) {
        output.remove("reliable");
        return output.put("intentStatus", status);
    }

    private static TaskCriterionV3 observation(String id, String subject) {
        return new TaskCriterionV3(id, "Observe requested Calendar content", "desktop.observe",
                CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "Calendar",
                EffectReceiptV1.Status.OBSERVED, subject);
    }

    private static ObjectNode plan(String target, String subject) {
        var value = JSON.createObjectNode().put("applicable", true).put("reliable", true);
        var criterion = value.putArray("criteria").addObject().put("id", "view")
                .put("description", "Observe the requested application content")
                .put("capabilityId", "desktop.observe").put("targetType", "DESKTOP_APPLICATION")
                .put("target", target).put("requiredEvidence", "OBSERVED");
        if (!subject.equals("<missing>")) criterion.put("requiredSubject", subject);
        return value;
    }

    private static CompletableFuture<ModelTaskResult> answer(JsonNode value) {
        return CompletableFuture.completedFuture(new ModelTaskResult(
                value, "fixture", 0, 0, false, Map.of()));
    }

    private static List<RunEventEnvelope> linkedWindowReceipts() {
        var open = JSON.createObjectNode().put("invocationId", "open")
                .put("tool", "desktop_session_open").put("operation", "open")
                .put("target", "Calendar").put("status", "ACCEPTED")
                .put("evidenceRef", "desktop:open").put("observedAt", Instant.EPOCH.toString());
        open.putObject("metadata").put("sessionId", "session").put("targetId", "window")
                .put("applicationId", "org.example.calendar");
        var observed = JSON.createObjectNode().put("invocationId", "view")
                .put("tool", "desktop_session_observe").put("operation", "observe")
                .put("target", "Calendar").put("status", "OBSERVED")
                .put("evidenceRef", "desktop:view").put("observedAt", Instant.EPOCH.toString());
        observed.putObject("metadata").put("sessionId", "session").put("targetId", "window")
                .put("applicationId", "org.example.calendar").put("observationId", UUID.randomUUID().toString())
                .put("windowGeneration", "0").put("contentRevision", "0").put("capturedAtMillis", "1000");
        return List.of(new RunEventEnvelope("run", 1, Instant.EPOCH, "core.tool.receipt", 1,
                        "framework.core", "correlation", null, open),
                new RunEventEnvelope("run", 2, Instant.EPOCH, "core.tool.receipt", 1,
                        "framework.core", "correlation", null, observed));
    }

    private static RunRequest request(String text) {
        return RunRequest.builder().agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "thread"))
                .input(InputBlock.text(text)).build();
    }

    private static RunRequest requestWithHistory(String text, String history) {
        return RunRequest.builder().agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "thread"))
                .inputs(List.of(InputBlock.message("user", history), InputBlock.text(text))).build();
    }
}
