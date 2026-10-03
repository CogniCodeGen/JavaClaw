package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.core.*;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.extension.ExtensionRegistrySnapshot;
import com.javaclaw.framework.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises host routing with real candidate journals and complete durable tool exchanges. */
class ComputerUseApplicationContextTest {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String APPLICATIONS = OnDemandApplicationRecovery.APPLICATIONS;
    private static final String LAUNCH = OnDemandApplicationRecovery.LAUNCH;

    @Test
    void plannedAndActivatedLaunchesWaitForInstalledIdentityBeforeStartingANewRun() throws Exception {
        for (boolean activated : List.of(false, true)) {
            try (Fixture fixture = new Fixture(2)) {
                if (activated) fixture.activateLaunch();
                // A prior Run's opaque FAILED result provides no NOT_SENT proof.
                // The new Run resolves identity before attempting that literal again.
                var oldCall = new AssistantMessage.ToolCall("old-call", "function", LAUNCH,
                        "{\"application\":\"阅读器\"}");
                List<Message> oldFailure = List.of(AssistantMessage.builder().toolCalls(List.of(oldCall)).build(),
                        ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(
                                oldCall.id(), LAUNCH, "{\"status\":\"FAILED\",\"data\":\"not found\"}"))).build());
                var discovery = fixture.selection.beforeLaunch(fixture.incoming(), List.of(LAUNCH), oldFailure);
                assertNotNull(discovery);
                assertEquals(List.of(APPLICATIONS), businessNames(discovery));
                assertEquals("DISCOVER_APPLICATIONS", control(discovery).path("phase").asText());
                if (activated) assertEquals(List.of(LAUNCH), fixture.catalog.activeNames(),
                        "preflight defers the launch callback without rewriting its activation journal");
                fixture.catalogPage(0, false);
                var choose = fixture.currentSelection();
                assertEquals(List.of(LAUNCH), businessNames(choose));
                assertEquals("SELECT_APPLICATION", control(choose).path("phase").asText());
                assertTrue(responses(choose).contains("org.example.reader"));
                assertTrue(responses(choose).contains("\"launchName\":\"Reader Shortcut\""),
                        "the primary sees the portable launch argument, not an assumed applicationId");
                assertTrue(choose.messages().stream().anyMatch(message -> message.getText()
                        .contains("exact launchName")));
                assertEquals(0, fixture.dispatched, "schema selection never executes desktop operations");
            }
        }
    }

    @Test
    void confirmedLaunchFailureAndInstalledIdentityRemainVisibleAfterAnUnrelatedProbe() throws Exception {
        try (Fixture fixture = new Fixture(2)) {
            fixture.rejectLaunch();
            fixture.probe();
            var discover = fixture.currentSelection();
            assertEquals(List.of(APPLICATIONS), businessNames(discover));
            assertTrue(responses(discover).contains("APPLICATION_NOT_FOUND"));
            assertTrue(responses(discover).contains("\"requestedApplication\":\"阅读器\""));
            fixture.catalogPage(0, false);
            fixture.probe();
            var choose = fixture.currentSelection();
            assertEquals(List.of(LAUNCH), businessNames(choose));
            assertTrue(responses(choose).contains("APPLICATION_NOT_FOUND"));
            assertTrue(responses(choose).contains("Reader Shortcut"));
            assertTrue(choose.messages().stream().filter(org.springframework.ai.chat.messages.UserMessage.class::isInstance)
                    .anyMatch(message -> message.getText().contains("UNTRUSTED_APPLICATION_METADATA")),
                    "application labels remain user-level data, separate from host instructions");
            assertEquals(0, fixture.dispatched);
        }
    }

    @Test
    void catalogPaginationRemainsAvailableAndEarlierPagesSurviveTheExchangeRetentionLimit() throws Exception {
        for (int toolLimit : List.of(1, 2)) {
            try (Fixture fixture = new Fixture(toolLimit)) {
                fixture.rejectLaunch();
                fixture.catalogPage(0, true);
                var next = fixture.currentSelection();
                assertTrue(businessNames(next).contains(APPLICATIONS), "an incomplete page cannot force launch");
                if (toolLimit == 1) assertEquals(List.of(APPLICATIONS), businessNames(next));
                else assertEquals(List.of(LAUNCH, APPLICATIONS), businessNames(next));
                for (int page = 1; page < 5; page++) fixture.catalogPage(page, page < 4);
                fixture.probe();
                var choose = fixture.currentSelection();
                assertEquals(List.of(LAUNCH), businessNames(choose));
                var recovery = recovery(choose);
                assertEquals(5, recovery.path("applications").size());
                assertEquals("Reader Shortcut", recovery.path("applications").get(0).path("launchName").asText());
                assertEquals("Reader Shortcut 4", recovery.path("applications").get(4).path("launchName").asText());
                assertFalse(recovery.path("catalogTruncated").asBoolean());
                assertTrue(responses(choose).contains("APPLICATION_NOT_FOUND"));
                long exchanges = choose.messages().stream().filter(ToolResponseMessage.class::isInstance).count();
                assertEquals(3, exchanges, "only failure/latest catalog are pinned beside the current probe exchange");
            }
        }
    }

    private static List<String> businessNames(OnDemandContextSession.Selection selection) {
        return selection.callbacks().stream().map(callback -> callback.getToolDefinition().name())
                .filter(name -> !name.equals(HarnessDecisionToolCallback.NAME)).toList();
    }

    private static String responses(OnDemandContextSession.Selection selection) {
        return selection.messages().stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::responseData).reduce("", String::concat);
    }

    private static JsonNode control(OnDemandContextSession.Selection selection) throws Exception {
        return hostPayload(selection, "Host computer-use control state:\n");
    }

    private static JsonNode recovery(OnDemandContextSession.Selection selection) throws Exception {
        String marker = "this grants no permissions):\n";
        return hostPayload(selection, marker);
    }

    private static JsonNode hostPayload(OnDemandContextSession.Selection selection, String marker) throws Exception {
        String text = selection.messages().stream().map(Message::getText).filter(java.util.Objects::nonNull)
                .filter(value -> value.contains(marker)).findFirst().orElseThrow();
        return new ObjectMapper().readTree(text.substring(text.indexOf(marker) + marker.length()).split("\n", 2)[0]);
    }

    private static final class Fixture implements AutoCloseable {
        private final ObjectMapper json = new ObjectMapper();
        private final RunId run = new RunId("host-application-context");
        private final List<RunEventEnvelope> history = new ArrayList<>();
        private final RunRequest runRequest = RunRequest.builder()
                .agent(new AgentDefinitionRef("agent", 1L)).profile(new RunProfileRef("profile", 1L))
                .source(InvocationSource.chat()).scope(new RunScope("workspace", "user", "session"))
                .input(InputBlock.text("打开阅读器查看目录")).permissionCeiling(PermissionSet.UNRESTRICTED).build();
        private final RunStore runs = (RunStore) Proxy.newProxyInstance(RunStore.class.getClassLoader(),
                new Class<?>[]{RunStore.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "find" -> Optional.of(new StoredRun(null, runRequest));
                    case "readable" -> true;
                    case "eventsAfter" -> history.stream().filter(event -> event.sequence() > (long) arguments[1]).toList();
                    default -> throw new AssertionError("Unexpected store call: " + method.getName());
                });
        private final ReasoningEventSink events = (type, version, producer, payload) -> history.add(
                new RunEventEnvelope(run.value(), history.size() + 1, Instant.EPOCH.plusMillis(history.size()),
                        type, version, producer, null, null, payload));
        private final ModelTaskGateway modelTasks = request -> CompletableFuture.failedFuture(
                new AssertionError("host desktop stages must not call the LIGHT planner"));
        private final ExtensionManager extensions = new ExtensionManager(new ExtensionContext(
                Clock.systemUTC(), Runnable::run, modelTasks));
        private final ExecutionPlan plan;
        private final ReasoningRequest request;
        private final ToolCatalogSession catalog;
        private final OnDemandHistoryCatalog historyCatalog;
        private final ComputerUseContextSelection selection;
        private int dispatched;

        private Fixture(int toolLimit) throws Exception {
            var budget = new RunBudget(Duration.ofHours(1), 100_000, 100_000, 20, BigDecimal.TEN);
            var policy = new StepContextPolicy(48_000, 48_000, 4, 16_000, toolLimit);
            var demand = new OnDemandContextPolicy(2, 3, 32, 8_000, 12_000, toolLimit);
            var descriptor = new ExecutionPlanDescriptor("plan", runRequest.agent(), runRequest.profile(),
                    "agent-checksum", "profile-checksum", extensions.currentSnapshot().generation(), List.of(),
                    "test-model", Map.of(), "prompt", Map.of(), NODES.objectNode(), PermissionSet.UNRESTRICTED,
                    budget, policy, demand, List.of(), List.of(), NODES.objectNode(), "checksum");
            Constructor<ExecutionPlan> planConstructor = ExecutionPlan.class.getDeclaredConstructor(
                    ExecutionPlanDescriptor.class, ExtensionRegistrySnapshot.SnapshotLease.class);
            planConstructor.setAccessible(true);
            plan = planConstructor.newInstance(descriptor, extensions.acquireCurrent());
            Constructor<RunControl> controlConstructor = RunControl.class.getDeclaredConstructor(RunBudget.class, Clock.class);
            controlConstructor.setAccessible(true);
            request = new ReasoningRequest(run, plan, runRequest, null,
                    controlConstructor.newInstance(budget, Clock.systemUTC()), events);
            List<FrameworkTool> tools = List.of(tool(APPLICATIONS), tool(LAUNCH), tool("desktop_session_probe"));
            catalog = new ToolCatalogSession(request, runs, json, policy, tools);
            var callbacks = new ArrayList<ToolCallback>();
            tools.forEach(tool -> callbacks.add(callback(tool.descriptor())));
            callbacks.add(callback(catalog.descriptor()));
            callbacks.add(new ModelStepJournal(request, runs, json).decisionCallback());
            catalog.bindCallbacks(callbacks);
            var query = new RunStepQuery(runs);
            historyCatalog = new OnDemandHistoryCatalog(request, runs, List.of(), 8);
            var planner = new OnDemandContextPlanner(request, demand, catalog, modelTasks, runs, query, json, List.of());
            ToolInvocationGateway gateway = ignored -> CompletableFuture.failedFuture(new AssertionError("unexpected execution"));
            selection = new ComputerUseContextSelection(request, catalog, planner, runs, query, historyCatalog,
                    new FixedContextSession(request, gateway, runs, json), new StepContextProjector(policy, json));
        }

        private List<Message> incoming() {
            var incoming = new ArrayList<Message>();
            incoming.add(new SystemMessage("test system"));
            var exchanges = historyCatalog.candidates(List.of());
            if (!exchanges.isEmpty()) incoming.addAll(exchanges.getLast().messages());
            return List.copyOf(incoming);
        }

        private OnDemandContextSession.Selection currentSelection() {
            return selection.select(incoming(), selection.cursor(incoming()), false);
        }

        private FrameworkTool tool(String name) {
            ToolDescriptor descriptor = new ToolDescriptor(name, "Desktop interface " + name,
                    NODES.objectNode().put("type", "object"), "desktop-session", PermissionSet.NONE, true);
            return new FrameworkTool() {
                @Override public ToolDescriptor descriptor() { return descriptor; }
                @Override public JsonNode execute(JsonNode arguments, ToolExecutionContext context) {
                    dispatched++;
                    throw new AssertionError("host selection may only choose schemas");
                }
            };
        }

        private ToolCallback callback(ToolDescriptor descriptor) {
            return new TestCallback(ToolDefinition.builder().name(descriptor.name()).description(descriptor.description())
                    .inputSchema(descriptor.inputSchema().toString()).build());
        }

        private void activateLaunch() {
            ObjectNode data = NODES.objectNode().put("action", "activate").put("success", true);
            data.putArray("activated").add(LAUNCH);
            var id = StepId.tool(run, "catalog-activation");
            ObjectNode input = NODES.objectNode().put("tool", ToolCatalogSession.NAME);
            input.putObject("arguments").put("action", "activate");
            StepEvents.started(events, id, AgentStep.Kind.TOOL, input, null);
            StepEvents.completed(events, id, NODES.objectNode().set("rawOutput", data), null);
        }

        private void rejectLaunch() {
            var data = NODES.objectNode().put("schemaVersion", 1).put("kind", "desktop.launch")
                    .put("admission", "REJECTED").put("status", "FAILED").put("delivery", "NOT_SENT")
                    .put("reasonCode", "APPLICATION_NOT_FOUND").put("requestedApplication", "阅读器")
                    .put("dispatchAttempted", false);
            data.putArray("targets");
            exchange(LAUNCH, NODES.objectNode().put("application", "阅读器"), data, "FAILED", "launch_application",
                    "FAILED", NODES.objectNode().put("delivery", "NOT_SENT").put("reasonCode", "APPLICATION_NOT_FOUND")
                            .put("requestedApplication", "阅读器"));
        }

        private void catalogPage(int offset, boolean hasMore) {
            var data = NODES.objectNode().put("schemaVersion", 1).put("kind", "desktop.applications")
                    .put("offset", offset).put("count", 1).put("truncated", false).put("hasMore", hasMore);
            if (hasMore) data.put("nextOffset", offset + 1);
            data.putArray("applications").addObject().put("name", "Reader").put("displayName", "阅读器")
                    .put("applicationId", offset == 0 ? "org.example.reader" : "org.example.reader" + offset)
                    .put("launchName", offset == 0 ? "Reader Shortcut" : "Reader Shortcut " + offset)
                    .putArray("aliases").add("阅读器");
            exchange(APPLICATIONS, NODES.objectNode().put("offset", offset).put("limit", 1), data, "SUCCEEDED", "applications",
                    "OBSERVED", NODES.objectNode());
        }

        private void probe() {
            exchange("desktop_session_probe", NODES.objectNode(), NODES.objectNode().put("kind", "desktop.probe"),
                    "SUCCEEDED", "probe", "OBSERVED", NODES.objectNode());
        }

        private void exchange(String name, ObjectNode arguments, ObjectNode data, String status,
                String operation, String receiptStatus, ObjectNode metadata) {
            var model = StepId.random();
            var call = new AssistantMessage.ToolCall("call", "function", name, arguments.toString());
            var assistant = AssistantMessage.builder().toolCalls(List.of(call)).build();
            StepEvents.started(events, model, AgentStep.Kind.MODEL, NODES.objectNode(), null);
            StepEvents.completed(events, model, NODES.objectNode().set("message", StepMessageCodec.message(assistant)), null);
            String invocation = "model/" + model.value() + "/" + call.id();
            ObjectNode input = NODES.objectNode().put("tool", name).put("invocationId", invocation);
            input.set("arguments", arguments);
            StepId tool = StepId.tool(run, invocation);
            StepEvents.started(events, tool, AgentStep.Kind.TOOL, input, model.value());
            ObjectNode output = NODES.objectNode().put("status", status).put("durationMillis", 1);
            output.set("rawOutput", data);
            output.set("modelOutput", data);
            StepEvents.completed(events, tool, output, null);
            ObjectNode receipt = NODES.objectNode().put("tool", name).put("invocationId", invocation)
                    .put("operation", operation).put("status", receiptStatus).put("evidenceRef", "host:" + invocation);
            receipt.set("metadata", metadata);
            events.emit("core.tool.receipt", 1, "framework.core", receipt);
        }

        @Override public void close() { plan.close(); extensions.close(); }
    }

    private record TestCallback(ToolDefinition definition) implements ToolCallback, SpringAiToolCatalog.GroupedCallback {
        @Override public String group() { return "desktop-session"; }
        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public String call(String input) { throw new AssertionError("host selection may only choose schemas"); }
    }
}
