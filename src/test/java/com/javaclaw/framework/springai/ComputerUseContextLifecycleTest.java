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
import com.javaclaw.desktop.agent.DesktopSessionTools;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSessionService;
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
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** Reuses the actual preceding provider prompt, rather than rebuilding small test inputs. */
class ComputerUseContextLifecycleTest {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String APPLICATIONS = OnDemandApplicationRecovery.APPLICATIONS;
    private static final String LAUNCH = OnDemandApplicationRecovery.LAUNCH;
    private static final String APP_ID = "org.example.reader";
    private static final String LAUNCH_NAME = "Reader Shortcut";
    private static final String SESSION = "reader-session";
    private static final String TARGET = "reader-window";
    private static final String CONTROL = "Host computer-use control state:\n";
    private static final String RECOVERY = "Host application identity recovery state";

    @Test
    void full115ApplicationCatalogStaysBoundedAcrossMandatoryAndOptionalSteps() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var discovery = fixture.computerUse.beforeLaunch(fixture.prompt, List.of(LAUNCH), List.of());
            assertNotNull(discovery);
            fixture.accept(discovery);
            assertEquals(List.of(APPLICATIONS), businessNames(discovery));

            fixture.catalogPage(0, 64, true);
            fixture.accept(fixture.mandatory());
            assertTrue(fixture.prompt.stream().anyMatch(message -> text(message).contains(LAUNCH_NAME)),
                    "the exact portable launch argument must survive catalog projection");

            fixture.catalogPage(64, 51, false);
            var choose = fixture.mandatory();
            fixture.accept(choose);
            assertTrue(businessNames(choose).contains(LAUNCH));
            assertTrue(businessNames(choose).stream().allMatch(name -> name.equals(LAUNCH) || name.equals(APPLICATIONS)),
                    "bounded catalog projection may retain the read-only query tool beside launch");
            assertEquals(115, fixture.catalogEntries,
                    "exercise the incident's real catalog scale rather than a one-entry fixture");
            assertEquals(1, countContaining(fixture.prompt, RECOVERY),
                    "the second catalog page replaces the preceding recovery projection");

            fixture.launch("SUCCEEDED", "ACCEPTED", "SENT");
            var open = fixture.mandatory();
            fixture.accept(open);
            assertEquals(List.of("desktop_session_open"), businessNames(open));
            assertEquals("OPEN_SESSION", control(fixture.prompt).path("phase").asText());
            assertEquals(TARGET, control(fixture.prompt).path("targetId").asText());
            assertCatalogRetired(fixture.prompt);

            fixture.open();
            var observe = fixture.mandatory();
            fixture.accept(observe);
            assertEquals(List.of("desktop_session_observe"), businessNames(observe));
            assertEquals("OBSERVE", control(fixture.prompt).path("phase").asText());

            // READY goes through the optional planner and its separate assembly path.
            // Every iteration starts with the complete previous projection plus a new exchange.
            for (int index = 0; index < 8; index++) {
                String frame = fixture.observe();
                var ready = fixture.optional.select(fixture.prompt);
                fixture.accept(ready);
                assertEquals("READY", control(fixture.prompt).path("phase").asText());
                assertEquals(frame, control(fixture.prompt).path("observationId").asText());
                assertFalse(businessNames(ready).contains(LAUNCH),
                        "a successful launch must not be offered again merely because the catalog is trimmed");
                assertCatalogRetired(fixture.prompt);
            }
            assertEquals(1, fixture.launchCalls);
            assertEquals(16, fixture.plannerCalls,
                    "all post-observation projections exercised optional selection and refinement");
            assertEquals(0, fixture.dispatched,
                    "host routing and projection select schemas without dispatching effects");
        }
    }

    @Test
    void unknownLaunchStillRequiresTargetDiscoveryAfterItsCatalogProjectionExpires() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.accept(fixture.computerUse.beforeLaunch(fixture.prompt, List.of(LAUNCH), List.of()));
            fixture.catalogPage(0, 64, true);
            fixture.accept(fixture.mandatory());
            fixture.catalogPage(64, 51, false);
            fixture.accept(fixture.mandatory());
            fixture.launch("UNCERTAIN", "UNKNOWN", "MAYBE_SENT");

            for (int attempt = 0; attempt < 5; attempt++) {
                var discovery = fixture.mandatory();
                fixture.accept(discovery);
                assertEquals(List.of("desktop_session_targets"), businessNames(discovery));
                assertEquals("DISCOVER_TARGETS", control(fixture.prompt).path("phase").asText());
                assertFalse(businessNames(discovery).contains(LAUNCH));
            }
            assertEquals(1, fixture.launchCalls);
            assertEquals(0, fixture.dispatched);
        }
    }

    @Test
    void emptyOwnerInventoryRoutesAPlannedObserveThroughRealTargetsOpenAndAFreshObservation() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.accept(fixture.optional.select(fixture.prompt));
            assertEquals(List.of("desktop_session_targets"), businessNames(fixture.lastSelection),
                    "there is no sessionId to observe, even when the optional planner selected observe");
            assertEquals("", control(fixture.prompt).path("sessionId").asText());
            assertEquals(0, fixture.launchCalls);

            fixture.discoverTargets();
            fixture.accept(fixture.mandatory());
            assertEquals(List.of("desktop_session_open"), businessNames(fixture.lastSelection));
            assertEquals("RECOVER_SESSION", control(fixture.prompt).path("phase").asText());
            assertEquals("", control(fixture.prompt).path("targetId").asText(),
                    "two applications require choosing an actual owning window from the returned catalog");
            var targetResponse = fixture.prompt.stream().filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream())
                    .filter(response -> response.name().equals("desktop_session_targets")).findFirst().orElseThrow();
            var returnedTargets = new ObjectMapper().readTree(targetResponse.responseData()).path("data").path("targets");
            assertEquals(2, returnedTargets.size());
            assertEquals("other-window", returnedTargets.get(1).path("targetId").asText());
            assertEquals("org.example.other", returnedTargets.get(1).path("applicationId").asText());
            assertEquals("", control(fixture.prompt).path("sessionId").asText());

            fixture.open();
            fixture.accept(fixture.mandatory());
            assertEquals(List.of("desktop_session_observe"), businessNames(fixture.lastSelection));
            assertEquals(SESSION, control(fixture.prompt).path("sessionId").asText());
            assertFalse(control(fixture.prompt).path("inputAllowed").asBoolean());
            String frame = fixture.observe();
            fixture.accept(fixture.optional.select(fixture.prompt));
            assertEquals("READY", control(fixture.prompt).path("phase").asText());
            assertEquals(frame, control(fixture.prompt).path("observationId").asText());
            assertTrue(control(fixture.prompt).path("inputAllowed").asBoolean());
            assertEquals(0, fixture.dispatched, "schema routing does not execute effects or invent session IDs");
        }
    }

    @Test
    void recoveryDefersPreviouslyActivatedSessionCallsAndLaunchWithoutChangingTheirJournal() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.activateSessionTools();
            var selected = fixture.computerUse.beforeLaunch(fixture.prompt,
                    List.of("desktop_session_observe", "desktop_session_click", LAUNCH), List.of());
            assertNotNull(selected);
            assertEquals(List.of("desktop_session_targets"), businessNames(selected));
            assertEquals(List.of("desktop_session_observe", "desktop_session_click", LAUNCH),
                    fixture.catalog.activeNames(), "the original activation remains journaled");
            assertEquals(0, fixture.dispatched);
        }
    }

    @Test
    void readOnlyReadyPreservesObservationAndUnrelatedOptionalPlanning() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.readOnlyReady();
            fixture.accept(fixture.optional.select(fixture.prompt));
            assertEquals(List.of("desktop_session_observe", "desktop_session_open"),
                    businessNames(fixture.lastSelection));
            assertEquals("READY", control(fixture.prompt).path("phase").asText());
            assertEquals("READ_ONLY", control(fixture.prompt).path("controlAccess").asText());
            assertFalse(control(fixture.prompt).path("inputAllowed").asBoolean());
            assertFalse(businessNames(fixture.lastSelection).contains("desktop_session_click"));
            assertEquals(4, fixture.plannerCalls, "READY still uses optional selection and refinement");

            fixture.observe();
            fixture.plannedTool = "read_file";
            fixture.accept(fixture.optional.select(fixture.prompt));
            assertTrue(businessNames(fixture.lastSelection).contains("read_file"),
                    "an open read-only desktop session cannot restrict unrelated authorized tools");
            assertEquals("READY", control(fixture.prompt).path("phase").asText());
            assertFalse(businessNames(fixture.lastSelection).contains("desktop_session_click"));
            assertEquals(0, fixture.dispatched);
        }
    }

    @Test
    void readOnlyProjectionMasksActivatedInputButRetainsItsActivationJournal() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.readOnlyReady();
            fixture.activateSessionTools();
            fixture.accept(fixture.mandatory());
            assertFalse(businessNames(fixture.lastSelection).contains("desktop_session_click"));
            assertTrue(businessNames(fixture.lastSelection).contains("desktop_session_observe"));
            assertTrue(fixture.catalog.activeNames().contains("desktop_session_click"));
            var upgrade = fixture.computerUse.beforeLaunch(fixture.prompt,
                    List.of("desktop_session_click"), List.of());
            fixture.accept(upgrade);
            assertEquals("OPEN_CONTROL", control(fixture.prompt).path("phase").asText());
            assertTrue(businessNames(upgrade).contains("desktop_session_open"));
            assertFalse(businessNames(upgrade).contains("desktop_session_click"));
            assertEquals(0, fixture.dispatched);
        }
    }

    private static void assertCatalogRetired(List<Message> messages) {
        assertEquals(1, countContaining(messages, RECOVERY),
                "only the compact selected launch receipt remains after application selection");
        Message identity = messages.stream().filter(message -> text(message).contains(RECOVERY)).findFirst().orElseThrow();
        assertFalse(text(identity).contains("\"applications\""));
        assertFalse(text(identity).contains("\"catalogStatus\""));
        assertTrue(text(identity).contains("\"selectedLaunch\""));
        assertFalse(messages.stream().anyMatch(message -> text(message).contains("\"kind\":\"desktop.applications\"")),
                "the complete 115-entry catalog remains in durable events, not post-launch provider context");
    }

    private static long countContaining(List<Message> messages, String marker) {
        return messages.stream().filter(message -> text(message).contains(marker)).count();
    }

    private static String text(Message message) {
        return message.getText() == null ? "" : message.getText();
    }

    private static JsonNode control(List<Message> messages) throws Exception {
        String text = messages.stream().map(ComputerUseContextLifecycleTest::text).filter(value -> value.startsWith(CONTROL))
                .findFirst().orElseThrow();
        return new ObjectMapper().readTree(text.substring(CONTROL.length()).split("\n", 2)[0]);
    }

    private static List<String> businessNames(OnDemandContextSession.Selection selection) {
        return selection.callbacks().stream().map(callback -> callback.getToolDefinition().name())
                .filter(name -> !name.equals(HarnessDecisionToolCallback.NAME)).toList();
    }

    private static void assertCompleteExchanges(List<Message> messages) {
        Map<String, String> calls = new HashMap<>();
        Map<String, String> results = new HashMap<>();
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant) {
                for (var call : assistant.getToolCalls())
                    assertNull(calls.put(call.id(), call.name()), "a tool call appears only once in its projection");
            } else if (message instanceof ToolResponseMessage response) {
                for (var result : response.getResponses())
                    assertNull(results.put(result.id(), result.name()), "a tool result appears only once in its projection");
            }
        }
        assertEquals(calls, results, "provider history contains complete matching call/result pairs");
    }

    private static final class Fixture implements AutoCloseable {
        private final ObjectMapper json = new ObjectMapper();
        private final RunId run = new RunId("computer-context-lifecycle");
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
        private final ModelTaskGateway modelTasks = request -> {
            this.plannerCalls++;
            ObjectNode selection = NODES.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().equals("context.on_demand.select_v2")) {
                selection.putArray("searches");
                selection.putObject("toolIntent").put("query", this.plannedTool)
                        .putArray("groups").add("desktop-session");
            } else {
                assertEquals("context.on_demand.refine_v2", request.purpose());
                selection.putArray("sourceIds");
                selection.put("toolAction", "direct");
                String observeId = null;
                for (JsonNode candidate : request.input().path("toolCandidates")) {
                    if (candidate.path("name").asText().equals(this.plannedTool))
                        observeId = candidate.path("id").asText();
                }
                assertNotNull(observeId, "optional observe choice comes from the actual frozen candidates");
                selection.putArray("toolIds").add(observeId);
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(selection, "planner", 1, 1, false, Map.of()));
        };
        private final ExtensionManager extensions = new ExtensionManager(new ExtensionContext(
                Clock.systemUTC(), Runnable::run, modelTasks));
        private final ExecutionPlan plan;
        private final ComputerUseContextSelection computerUse;
        private final OnDemandContextSession optional;
        private final ToolCatalogSession catalog;
        private final List<String> liveSessions = new ArrayList<>();
        private List<Message> prompt = List.of(new SystemMessage("Stable test instructions."));
        private OnDemandContextSession.Selection lastSelection;
        private int plannerCalls;
        private int dispatched;
        private int launchCalls;
        private int catalogEntries;
        private String plannedTool = "desktop_session_observe";

        private Fixture() throws Exception {
            this(false);
        }

        private Fixture(boolean inventoryKnown) throws Exception {
            var budget = new RunBudget(Duration.ofHours(1), 100_000, 100_000, 40, BigDecimal.TEN);
            var policy = new StepContextPolicy(48_000, 48_000, 4, 16_000, 4);
            var demand = new OnDemandContextPolicy(2, 3, 32, 8_000, 12_000, 4);
            var descriptor = new ExecutionPlanDescriptor("plan", runRequest.agent(), runRequest.profile(),
                    "agent-checksum", "profile-checksum", extensions.currentSnapshot().generation(), List.of(),
                    "test-model", Map.of(), "prompt", Map.of(), NODES.objectNode(), PermissionSet.UNRESTRICTED,
                    budget, policy, demand, List.of(), List.of(), NODES.objectNode(), "checksum");
            Constructor<ExecutionPlan> constructor = ExecutionPlan.class.getDeclaredConstructor(
                    ExecutionPlanDescriptor.class, ExtensionRegistrySnapshot.SnapshotLease.class);
            constructor.setAccessible(true);
            plan = constructor.newInstance(descriptor, extensions.acquireCurrent());
            Constructor<RunControl> controlConstructor = RunControl.class.getDeclaredConstructor(RunBudget.class, Clock.class);
            controlConstructor.setAccessible(true);
            var request = new ReasoningRequest(run, plan, runRequest, null,
                    controlConstructor.newInstance(budget, Clock.systemUTC()), events);
            List<FrameworkTool> tools;
            if (inventoryKnown) {
                DesktopSessionOwner owner = new DesktopSessionOwner("workspace", "session", "chat", "user");
                DesktopSessionService service = (DesktopSessionService) Proxy.newProxyInstance(
                        DesktopSessionService.class.getClassLoader(), new Class<?>[]{DesktopSessionService.class},
                        (proxy, method, arguments) -> {
                            assertEquals("liveSessionIds", method.getName(), "runtime projection must only read the host map");
                            assertEquals(owner, arguments[0]);
                            return Optional.of(List.copyOf(liveSessions));
                        });
                var source = new DesktopSessionTools(service, owner,
                        Path.of(System.getProperty("java.io.tmpdir"), "javaclaw-session-recovery-test"));
                var registry = new SpringAiAnnotatedToolRegistry(json);
                registry.register("workspace", ignored -> ToolObjectBundle.of(List.of(source)));
                tools = new ArrayList<>(registry.create(new ToolContext(run, runRequest.scope(), PermissionSet.UNRESTRICTED,
                        request.control(), request.control().deadline(), runRequest)).stream()
                        .filter(tool -> List.of(APPLICATIONS, LAUNCH, "desktop_session_targets", "desktop_session_open",
                                "desktop_session_observe", "desktop_session_click").contains(tool.descriptor().name())).toList());
                tools.add(tool("read_file"));
            } else {
                tools = List.of(tool(APPLICATIONS), tool(LAUNCH), tool("desktop_session_targets"),
                        tool("desktop_session_open"), tool("desktop_session_observe"), tool("read_file"));
            }
            catalog = new ToolCatalogSession(request, runs, json, policy, tools);
            var callbacks = new ArrayList<ToolCallback>();
            tools.forEach(tool -> callbacks.add(callback(tool.descriptor())));
            callbacks.add(callback(catalog.descriptor()));
            callbacks.add(new ModelStepJournal(request, runs, json).decisionCallback());
            catalog.bindCallbacks(callbacks);
            var query = new RunStepQuery(runs);
            var historyCatalog = new OnDemandHistoryCatalog(request, runs, List.of(), 32);
            var planner = new OnDemandContextPlanner(request, demand, catalog, modelTasks, runs, query, json, List.of());
            ToolInvocationGateway gateway = ignored -> CompletableFuture.failedFuture(new AssertionError("unexpected execution"));
            computerUse = new ComputerUseContextSelection(request, catalog, planner, runs, query, historyCatalog,
                    new FixedContextSession(request, gateway, runs, json), new StepContextProjector(policy, json));
            optional = new OnDemandContextSession(request, catalog, modelTasks, gateway, runs, json, List.of());
        }

        private OnDemandContextSession.Selection mandatory() {
            return computerUse.select(prompt, computerUse.cursor(prompt), false);
        }

        private void readOnlyReady() {
            accept(optional.select(prompt));
            discoverTargets();
            accept(mandatory());
            open(false);
            accept(mandatory());
            observe();
        }

        private void accept(OnDemandContextSession.Selection selection) {
            assertNotNull(selection);
            lastSelection = selection;
            prompt = selection.messages();
            assertTrue(prompt.stream().mapToInt(StepContextProjector::characters).sum() <= 48_000,
                    "every provider prompt respects the frozen 48K character budget");
            assertEquals(1, countContaining(prompt, "Stable test instructions."));
            assertEquals(1, countContaining(prompt, CONTROL), "only the latest cursor is projected");
            assertTrue(countContaining(prompt, RECOVERY) <= 1, "recovery state never accumulates");
            assertCompleteExchanges(prompt);
        }

        private FrameworkTool tool(String name) {
            ToolDescriptor descriptor = new ToolDescriptor(name, "Desktop interface " + name,
                    NODES.objectNode().put("type", "object"), "desktop-session", PermissionSet.NONE, true);
            return new FrameworkTool() {
                @Override public ToolDescriptor descriptor() { return descriptor; }
                @Override public JsonNode execute(JsonNode arguments, ToolExecutionContext context) {
                    dispatched++;
                    throw new AssertionError("projection may only select schemas");
                }
            };
        }

        private ToolCallback callback(ToolDescriptor descriptor) {
            return new TestCallback(ToolDefinition.builder().name(descriptor.name()).description(descriptor.description())
                    .inputSchema(descriptor.inputSchema().toString()).build());
        }

        private void catalogPage(int offset, int count, boolean hasMore) {
            ObjectNode data = NODES.objectNode().put("schemaVersion", 1).put("kind", "desktop.applications")
                    .put("offset", offset).put("count", count).put("truncated", false).put("hasMore", hasMore);
            if (hasMore) data.put("nextOffset", offset + count);
            var applications = data.putArray("applications");
            for (int index = offset; index < offset + count; index++) {
                boolean reader = index == 10;
                applications.addObject().put("name", reader ? "Reader" : "Installed Application " + index)
                        .put("displayName", reader ? "阅读器" : "Installed Application Display Name " + index)
                        .put("applicationId", reader ? APP_ID : "org.example.installed.application." + index)
                        .put("launchName", reader ? LAUNCH_NAME : "Installed Application Shortcut " + index)
                        .putArray("aliases").add(reader ? "阅读器" : "Application Localized Alias " + index);
            }
            catalogEntries += count;
            assertTrue(data.toString().length() < 16_000, "each page independently fits the tool result budget");
            exchange(APPLICATIONS, NODES.objectNode().put("offset", offset).put("limit", count), data,
                    "SUCCEEDED", "applications", "OBSERVED", NODES.objectNode());
        }

        private void launch(String status, String receiptStatus, String delivery) {
            launchCalls++;
            ObjectNode data = NODES.objectNode().put("schemaVersion", 1).put("kind", "desktop.launch")
                    .put("requestedApplication", LAUNCH_NAME).put("applicationId", APP_ID).put("processId", 3243);
            data.putArray("targets").addObject().put("targetId", TARGET).put("processId", 3243)
                    .put("applicationId", APP_ID).put("visible", true).put("systemSurface", false);
            exchange(LAUNCH, NODES.objectNode().put("application", LAUNCH_NAME), data, status,
                    "launch_application", receiptStatus, NODES.objectNode().put("delivery", delivery)
                            .put("requestedApplication", LAUNCH_NAME));
        }

        private void open() {
            open(true);
        }

        private void open(boolean control) {
            ObjectNode data = NODES.objectNode().put("schemaVersion", 1).put("kind", "desktop.session")
                    .put("sessionId", SESSION).put("controlGranted", control);
            data.putObject("target").put("targetId", TARGET).put("applicationId", APP_ID);
            exchange("desktop_session_open", NODES.objectNode().put("targetId", TARGET).put("control", control), data, "SUCCEEDED",
                    "open", "ACCEPTED", NODES.objectNode().put("sessionId", SESSION)
                            .put("targetId", TARGET).put("applicationId", APP_ID).put("delivery", "SENT")
                            .put("controlGranted", Boolean.toString(control)));
            liveSessions.add(SESSION);
        }

        private void activateSessionTools() {
            ObjectNode data = NODES.objectNode().put("action", "activate").put("success", true);
            data.putArray("activated").add("desktop_session_observe").add("desktop_session_click").add(LAUNCH);
            var id = StepId.tool(run, "session-tool-activation");
            ObjectNode input = NODES.objectNode().put("tool", ToolCatalogSession.NAME);
            input.putObject("arguments").put("action", "activate");
            StepEvents.started(events, id, AgentStep.Kind.TOOL, input, null);
            StepEvents.completed(events, id, NODES.objectNode().set("rawOutput", data), null);
        }

        private void discoverTargets() {
            ObjectNode data = NODES.objectNode().put("schemaVersion", 1).put("kind", "desktop.targets");
            var targets = data.putArray("targets");
            targets.addObject().put("targetId", TARGET).put("application", "Reader").put("applicationId", APP_ID)
                    .put("processId", 42).put("visible", true).put("systemSurface", false);
            targets.addObject().put("targetId", "other-window").put("application", "Other application")
                    .put("applicationId", "org.example.other").put("processId", 43)
                    .put("visible", true).put("systemSurface", false);
            exchange("desktop_session_targets", NODES.objectNode(), data, "SUCCEEDED", "targets", "OBSERVED",
                    NODES.objectNode());
        }

        private String observe() {
            String frame = UUID.randomUUID().toString();
            ObjectNode data = NODES.objectNode().put("schemaVersion", 1).put("kind", "desktop.observation")
                    .put("sessionId", SESSION).put("targetId", TARGET).put("observationId", frame);
            exchange("desktop_session_observe", NODES.objectNode().put("sessionId", SESSION), data, "SUCCEEDED",
                    "observe", "OBSERVED", NODES.objectNode().put("sessionId", SESSION)
                            .put("targetId", TARGET).put("observationId", frame));
            return frame;
        }

        private void exchange(String name, ObjectNode arguments, ObjectNode data, String status,
                String operation, String receiptStatus, ObjectNode metadata) {
            var model = StepId.random();
            var call = new AssistantMessage.ToolCall("call-" + history.size(), "function", name, arguments.toString());
            var assistant = AssistantMessage.builder().toolCalls(List.of(call)).build();
            assertNotNull(lastSelection);
            assertTrue(lastSelection.callbacks().stream().anyMatch(callback -> callback.getToolDefinition().name().equals(name)),
                    "the synthetic provider may call only a tool offered by the preceding frozen selection");
            ObjectNode providerInput = NODES.objectNode();
            providerInput.set("messages", StepMessageCodec.messages(lastSelection.messages()));
            var offeredNames = providerInput.putArray("toolNames");
            var fingerprints = providerInput.putObject("toolFingerprints");
            int schemaCharacters = 0;
            for (var callback : lastSelection.callbacks()) {
                String offered = callback.getToolDefinition().name();
                offeredNames.add(offered);
                fingerprints.put(offered, ToolCatalogSession.fingerprint(callback));
                schemaCharacters += SpringAiToolCatalog.schemaCharacters(callback);
            }
            providerInput.put("toolSchemaCharacters", schemaCharacters).put("modelPolicy", "test-model").put("attempt", 1);
            if (lastSelection.toolCandidateStepId() != null)
                providerInput.put("toolCandidateStepId", lastSelection.toolCandidateStepId());
            StepEvents.started(events, model, AgentStep.Kind.MODEL, providerInput, null);
            StepEvents.completed(events, model, NODES.objectNode().set("message", StepMessageCodec.message(assistant)), null);
            String invocation = "model/" + model.value() + "/" + call.id();
            ObjectNode input = NODES.objectNode().put("tool", name).put("invocationId", invocation);
            input.set("arguments", arguments);
            StepId tool = StepId.tool(run, invocation);
            StepEvents.started(events, tool, AgentStep.Kind.TOOL, input, model.value());
            ObjectNode started = input.deepCopy();
            events.emit("core.tool.started", 1, "framework.core", started);
            ObjectNode output = NODES.objectNode().put("status", status).put("durationMillis", 1)
                    .put("displayMessage", "Desktop operation completed");
            output.set("rawOutput", data);
            output.set("modelOutput", data);
            StepEvents.completed(events, tool, output, null);
            ObjectNode completed = NODES.objectNode().put("tool", name).put("invocationId", invocation).put("status", status);
            completed.set("output", data);
            events.emit("core.tool.completed", 2, "framework.core", completed);
            ObjectNode receipt = NODES.objectNode().put("tool", name).put("invocationId", invocation)
                    .put("operation", operation).put("status", receiptStatus)
                    .put("evidenceRef", "core.tool.completed:" + run.value() + ":" + invocation);
            receipt.set("metadata", metadata);
            events.emit("core.tool.receipt", 1, "framework.core", receipt);

            var next = new ArrayList<>(prompt);
            next.add(assistant);
            ObjectNode visible = SpringAiToolCallback.modelVisibleResult(new ToolInvocationResult(
                    data, Duration.ofMillis(1), ToolExecutionStatus.valueOf(status), "", "Desktop operation completed"),
                    runs, run, invocation);
            next.add(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(
                    call.id(), name, visible.toString()))).build());
            prompt = List.copyOf(next);
        }

        @Override public void close() { plan.close(); extensions.close(); }
    }

    private record TestCallback(ToolDefinition definition) implements ToolCallback, SpringAiToolCatalog.GroupedCallback {
        @Override public String group() { return "desktop-session"; }
        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public String call(String input) { throw new AssertionError("projection may only select schemas"); }
    }
}
