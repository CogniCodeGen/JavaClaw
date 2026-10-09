package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.agent.vision.VisionPreprocessor;
import com.javaclaw.desktop.agent.DesktopSessionTools;
import com.javaclaw.desktop.api.*;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.builtin.BuiltinCapabilityExtension;
import com.javaclaw.framework.builtin.ClarifyTools;
import com.javaclaw.framework.builtin.InteractionTools;
import com.javaclaw.framework.core.*;
import com.javaclaw.framework.extension.ExtensionArtifact;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.*;
import com.javaclaw.framework.store.JdbcAgentDefinitionStore;
import com.javaclaw.framework.store.JdbcExecutionPlanStore;
import com.javaclaw.framework.store.JdbcRunStore;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises continuation through the real reasoning, tool, journal and task-outcome boundaries. */
@Timeout(30)
class SpringAiInteractionContinuationIntegrationTest {
    private static final String REPAIR_REASON = "INTERACTION_BLOCKED_WITH_AVAILABLE_STEP";
    private static final String TARGET = "continuation-target";
    private static final String SESSION = "continuation-session";
    private static final String APPLICATION = "ContinuationTest";
    private static final Set<String> DESKTOP_TOOLS = Set.of(
            "desktop_session_applications", "desktop_session_launch_application", "desktop_session_targets",
            "desktop_session_open", "desktop_session_observe", "desktop_session_click",
            "desktop_session_probe");

    @Test
    void largeWindowInventoryCanBeFilteredThenOpenedAndObservedWithinTheInteractionBudget() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        DesktopStub desktop = new DesktopStub();
        desktop.toolResultCharacters = 12_000;
        desktop.contextCharacters = 24_000;
        desktop.retainedToolExchanges = 2;
        var windows = new java.util.ArrayList<DesktopTarget>();
        for (int index = 0; index < 164; index++) {
            windows.add(new DesktopTarget("test", "unrelated-window-" + index, 1000L + index,
                    "Unrelated application " + index, "窗口 \\\"" + "长标题\\\n".repeat(50),
                    0, 0, 120, 120, DesktopTarget.VISIBLE, "org.example.unrelated." + index));
        }
        windows.add(desktop.target);
        desktop.windows = List.copyOf(windows);
        ChatModel model = prompt -> {
            assertTrue(prompt.getInstructions().stream().mapToInt(StepContextProjector::characters).sum() <= 24_000);
            for (var message : prompt.getInstructions()) {
                if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage response) {
                    for (var value : response.getResponses()) {
                        assertTrue(value.responseData().length() <= 12_512,
                                () -> "response envelope exceeded the frozen interaction budget: " + value.name());
                    }
                }
            }
            return switch (modelCalls.incrementAndGet()) {
                case 1 -> call("desktop_session_launch_application", "{\"application\":\"" + APPLICATION + "\"}");
                case 2 -> call("desktop_session_targets", "{}");
                case 3 -> {
                    JsonNode page = lastTargets(prompt);
                    assertTrue(page.path("hasMore").asBoolean());
                    assertTrue(page.path("count").asInt() < 165);
                    assertFalse(page.path("targets").toString().contains(TARGET),
                            "the desired window must be beyond the initial page in this regression");
                    assertTrue(toolNames(prompt).contains("desktop_session_targets"), () -> offeredState(prompt));
                    yield call("desktop_session_targets", "{\"query\":\"" + APPLICATION + "\",\"offset\":0}");
                }
                case 4 -> {
                    JsonNode page = lastTargets(prompt);
                    assertEquals(1, page.path("count").asInt());
                    assertEquals(TARGET, page.path("targets").get(0).path("targetId").asText());
                    assertFalse(page.path("hasMore").asBoolean());
                    assertTrue(toolNames(prompt).contains("desktop_session_open"), () -> offeredState(prompt));
                    yield call("desktop_session_open", "{\"targetId\":\"" + TARGET + "\",\"control\":false}");
                }
                case 5 -> call("desktop_session_observe", "{\"sessionId\":\"" + SESSION + "\"}");
                case 6 -> done();
                default -> throw new AssertionError("unexpected model call after completion");
            };
        };
        try (Fixture fixture = new Fixture(model, desktop, DESKTOP_TOOLS, true)) {
            RunHandle run = fixture.engine.start(fixture.request());
            RunOutcome outcome = fixture.await(run);
            assertEquals(RunState.COMPLETED, outcome.state(), () -> outcome + "; tools=" + fixture.startedTools(run.id()));
            assertEquals(TaskOutcome.VERIFIED_COMPLETE, fixture.engine.taskResult(run.id()).orElseThrow().outcome());
            assertEquals(6, modelCalls.get());
            assertEquals(1, desktop.launches.get());
            assertEquals(1, desktop.opens.get());
            assertEquals(1, desktop.observes.get());
            assertEquals(0, desktop.clicks.get());
            assertEquals(0, fixture.repairs(run.id()));
            assertEquals(List.of("desktop_session_applications", "desktop_session_launch_application",
                    "desktop_session_targets", "desktop_session_targets", "desktop_session_open",
                    "desktop_session_observe"), fixture.startedTools(run.id()));
            assertFalse(fixture.events(run.id()).stream().anyMatch(event -> event.type().equals("core.run.failed")));
        }
    }

    private static JsonNode lastTargets(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance)
                .map(org.springframework.ai.chat.messages.ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .filter(response -> response.name().equals("desktop_session_targets"))
                .reduce((first, last) -> last).map(response -> {
                    try { return new ObjectMapper().readTree(response.responseData()).path("data"); }
                    catch (java.io.IOException malformed) { throw new AssertionError(malformed); }
                }).orElseThrow();
    }

    @Test
    void unsupportedBlockedDecisionContinuesWithOpenAndFreshObservationInTheSameRun() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        DesktopStub desktop = new DesktopStub();
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1 -> call("desktop_session_launch_application", "{\"application\":\"" + APPLICATION + "\"}");
            case 2 -> call("desktop_session_targets", "{}");
            case 3 -> {
                assertTrue(toolNames(prompt).contains("desktop_session_open"), () -> offeredState(prompt));
                yield blocked("未打开控制会话，权限尚未授予");
            }
            case 4 -> {
                assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                        message.getText().contains("宿主复核")
                                && message.getText().contains("desktop_session_open")),
                        "the host must explain why the previous BLOCKED decision was unsupported");
                assertTrue(toolNames(prompt).contains("desktop_session_open"));
                yield call("desktop_session_open", "{\"targetId\":\"" + TARGET + "\",\"control\":false}");
            }
            case 5 -> call("desktop_session_observe", "{\"sessionId\":\"" + SESSION + "\"}");
            case 6 -> done();
            default -> throw new AssertionError("unexpected model call after completion");
        };
        try (Fixture fixture = new Fixture(model, desktop, DESKTOP_TOOLS, true)) {
            RunHandle run = fixture.engine.start(fixture.request());
            RunOutcome outcome = fixture.await(run);

            assertEquals(RunState.COMPLETED, outcome.state(), () -> outcome + "; repairs="
                    + fixture.repairs(run.id()) + "; tools=" + fixture.startedTools(run.id()));
            assertEquals(TaskOutcome.VERIFIED_COMPLETE, fixture.engine.taskResult(run.id()).orElseThrow().outcome());
            assertEquals(6, modelCalls.get());
            assertEquals(1, desktop.launches.get());
            assertEquals(1, desktop.opens.get());
            assertEquals(1, desktop.observes.get());
            assertEquals(0, desktop.clicks.get());
            assertEquals(1, fixture.repairs(run.id()));
            assertEquals(List.of("desktop_session_applications", "desktop_session_launch_application",
                    "desktop_session_targets", "desktop_session_open",
                    "desktop_session_observe"), fixture.startedTools(run.id()));
            assertFalse(fixture.events(run.id()).stream().anyMatch(event -> event.type().equals("core.task.stop")
                    && event.payload().path("reasonCode").asText().equals("MODEL_BLOCKED")),
                    "the repaired BLOCKED decision must not become the final task stop");
        }
    }

    @Test
    void aSecondUnsupportedBlockedDecisionStopsWithoutAnUnboundedRepairLoop() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        DesktopStub desktop = new DesktopStub();
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1 -> call("desktop_session_launch_application", "{\"application\":\"" + APPLICATION + "\"}");
            case 2, 3 -> blocked("没有当前控制会话，因此停止");
            default -> throw new AssertionError("BLOCKED correction must be bounded");
        };
        try (Fixture fixture = new Fixture(model, desktop, DESKTOP_TOOLS)) {
            RunHandle run = fixture.engine.start(fixture.request());
            fixture.await(run);

            assertEquals(3, modelCalls.get());
            assertEquals(1, fixture.repairs(run.id()));
            assertEquals(TaskOutcome.BLOCKED, fixture.engine.taskResult(run.id()).orElseThrow().outcome());
            assertEquals(0, desktop.opens.get(), "the host must not execute the suggested tool itself");
            assertEquals(0, desktop.observes.get());
            assertEquals(1, desktop.launches.get());
        }
    }

    @Test
    void aRealPermissionDenialRemainsBlockedWithoutAnotherOpenAttempt() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        DesktopStub desktop = new DesktopStub();
        desktop.denyOpen = true;
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1 -> call("desktop_session_launch_application", "{\"application\":\"" + APPLICATION + "\"}");
            case 2 -> call("desktop_session_open", "{\"targetId\":\"" + TARGET + "\",\"control\":false}");
            case 3 -> blocked("宿主打开会话返回 ACCESS_DENIED，需要用户检查系统权限");
            default -> throw new AssertionError("a real denial must not trigger speculative retries");
        };
        try (Fixture fixture = new Fixture(model, desktop, DESKTOP_TOOLS)) {
            RunHandle run = fixture.engine.start(fixture.request());
            fixture.await(run);

            assertEquals(3, modelCalls.get());
            assertEquals(1, desktop.opens.get());
            assertEquals(0, desktop.observes.get());
            assertEquals(0, fixture.repairs(run.id()));
            assertEquals(TaskOutcome.BLOCKED, fixture.engine.taskResult(run.id()).orElseThrow().outcome());
            assertTrue(fixture.events(run.id()).stream().anyMatch(event ->
                    event.type().equals("core.tool.completed")
                            && event.payload().path("tool").asText().equals("desktop_session_open")
                            && event.payload().path("output").toString().contains("ACCESS_DENIED")));
        }
    }

    @Test
    void aNegativeAvailabilityProbeIsEvidenceOfARealBlockEvenWhenTheToolCallSucceeds() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        DesktopStub desktop = new DesktopStub();
        desktop.available = false;
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1 -> call("desktop_session_launch_application", "{\"application\":\"" + APPLICATION + "\"}");
            case 2 -> call("desktop_session_probe", "{}");
            case 3 -> {
                assertTrue(toolNames(prompt).contains("desktop_session_open"));
                yield blocked("宿主能力探测返回 available=false");
            }
            default -> throw new AssertionError("negative availability must not be ignored");
        };
        try (Fixture fixture = new Fixture(model, desktop, DESKTOP_TOOLS)) {
            RunHandle run = fixture.engine.start(fixture.request());
            fixture.await(run);

            assertEquals(3, modelCalls.get());
            assertEquals(0, fixture.repairs(run.id()));
            assertEquals(1, desktop.launches.get());
            assertEquals(TaskOutcome.BLOCKED, fixture.engine.taskResult(run.id()).orElseThrow().outcome());
            assertTrue(fixture.events(run.id()).stream().anyMatch(event ->
                    event.type().equals("core.tool.completed")
                            && event.payload().path("tool").asText().equals("desktop_session_probe")
                            && !event.payload().path("output").path("available").asBoolean(true)));
        }
    }

    @Test
    void aDisabledDesktopSettingDoesNotCauseAContinuationRepair() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        DesktopStub desktop = new DesktopStub();
        desktop.settingEnabled = false;
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1 -> call("desktop_session_launch_application", "{\"application\":\"" + APPLICATION + "\"}");
            case 2 -> blocked("宿主当前设置明确禁止桌面访问");
            default -> throw new AssertionError("the host must honor the current disabled setting");
        };
        try (Fixture fixture = new Fixture(model, desktop, DESKTOP_TOOLS)) {
            RunHandle run = fixture.engine.start(fixture.request());
            fixture.await(run);
            assertEquals(2, modelCalls.get());
            assertEquals(0, fixture.repairs(run.id()));
            assertEquals(0, desktop.opens.get());
            assertEquals(TaskOutcome.BLOCKED, fixture.engine.taskResult(run.id()).orElseThrow().outcome());
        }
    }

    @Test
    void insufficientInputBudgetStopsBeforeRequestingAnotherModelCall() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        DesktopStub desktop = new DesktopStub();
        desktop.maxInputTokens = 20_000;
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1 -> call("desktop_session_launch_application", "{\"application\":\"" + APPLICATION + "\"}");
            case 2 -> blocked("未建立桌面会话", 18_000);
            default -> throw new AssertionError("a continuation cannot spend the settlement reserve");
        };
        try (Fixture fixture = new Fixture(model, desktop, DESKTOP_TOOLS)) {
            RunHandle run = fixture.engine.start(fixture.request());
            fixture.await(run);
            assertEquals(2, modelCalls.get());
            assertEquals(0, fixture.repairs(run.id()));
            assertEquals(0, desktop.opens.get());
            assertEquals(TaskOutcome.BLOCKED, fixture.engine.taskResult(run.id()).orElseThrow().outcome());
        }
    }

    @Test
    void anUnavailableNextToolDoesNotCauseAContinuationRepair() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        DesktopStub desktop = new DesktopStub();
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1 -> call("desktop_session_launch_application", "{\"application\":\"" + APPLICATION + "\"}");
            case 2 -> {
                assertFalse(toolNames(prompt).contains("desktop_session_open"));
                yield blocked("没有可调用的打开会话接口");
            }
            default -> throw new AssertionError("a missing tool cannot be repaired by prompting");
        };
        try (Fixture fixture = new Fixture(model, desktop,
                Set.of("desktop_session_applications", "desktop_session_launch_application"))) {
            RunHandle run = fixture.engine.start(fixture.request());
            fixture.await(run);

            assertEquals(2, modelCalls.get());
            assertEquals(0, fixture.repairs(run.id()));
            assertEquals(TaskOutcome.BLOCKED, fixture.engine.taskResult(run.id()).orElseThrow().outcome());
        }
    }

    @Test
    void unknownInputDeliveryNeverTriggersAContinuationRepairOrReplaysTheInput() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        DesktopStub desktop = new DesktopStub();
        desktop.unknownClick = true;
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1 -> call("desktop_session_launch_application", "{\"application\":\"" + APPLICATION + "\"}");
            case 2 -> call("desktop_session_open", "{\"targetId\":\"" + TARGET + "\",\"control\":true}");
            case 3 -> call("desktop_session_observe", "{\"sessionId\":\"" + SESSION + "\"}");
            case 4 -> call("desktop_session_click", "{\"sessionId\":\"" + SESSION
                    + "\",\"observationId\":\"00000000-0000-4000-8000-000000000001\","
                    + "\"generation\":1,\"x\":20,\"y\":20,\"button\":1,\"clicks\":1,"
                    + "\"elementId\":\"00000000-0000-4000-8000-000000000001:v0\"}");
            case 5 -> blocked("先前输入的投递结果未知，不能重放");
            default -> throw new AssertionError("an uncertain effect must not be retried by continuation repair");
        };
        try (Fixture fixture = new Fixture(model, desktop, DESKTOP_TOOLS)) {
            RunHandle run = fixture.engine.start(fixture.request());
            fixture.await(run);

            assertEquals(1, desktop.clicks.get());
            assertEquals(1, desktop.opens.get());
            assertEquals(0, fixture.repairs(run.id()));
            assertEquals(TaskOutcome.BLOCKED, fixture.engine.taskResult(run.id()).orElseThrow().outcome());
            assertTrue(fixture.events(run.id()).stream().anyMatch(event ->
                    event.type().equals("core.tool.receipt")
                            && event.payload().path("tool").asText().equals("desktop_session_click")
                            && event.payload().path("status").asText().equals("UNKNOWN")));
            assertFalse(fixture.events(run.id()).stream().anyMatch(event ->
                    event.type().equals("core.effect.reconciled")),
                    "a fresh observation must not silently reconcile uncertain input");
        }
    }

    private static List<String> toolNames(Prompt prompt) {
        ToolCallingChatOptions options = assertInstanceOf(ToolCallingChatOptions.class, prompt.getOptions());
        return options.getToolCallbacks().stream().map(callback -> callback.getToolDefinition().name()).toList();
    }

    private static String offeredState(Prompt prompt) {
        String cursor = prompt.getInstructions().stream().map(message -> message.getText())
                .filter(text -> text.startsWith("Host computer-use state:\n"))
                .map(text -> text.lines().limit(2).collect(java.util.stream.Collectors.joining(" ")))
                .findFirst().orElse("no host cursor");
        return "offered=" + toolNames(prompt) + "; " + cursor;
    }

    private static ChatResponse blocked(String message) {
        return blocked(message, 2);
    }

    private static ChatResponse blocked(String message, int inputTokens) {
        ObjectNode decision = JsonNodeFactory.instance.objectNode().put("decision", "BLOCKED")
                .put("userMessage", message);
        decision.putArray("evidenceRefs");
        decision.putArray("unmetCriterionIds").add("contacts");
        return call(HarnessDecisionToolCallback.NAME, decision.toString(), inputTokens);
    }

    private static ChatResponse done() {
        return call(HarnessDecisionToolCallback.NAME,
                "{\"decision\":\"CLAIM_DONE\",\"userMessage\":\"已观察联系人页面\","
                        + "\"evidenceRefs\":[],\"unmetCriterionIds\":[]}");
    }

    private static ChatResponse call(String tool, String arguments) {
        return call(tool, arguments, 2);
    }

    private static ChatResponse call(String tool, String arguments, int inputTokens) {
        AssistantMessage message = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("call-" + UUID.randomUUID(), "function", tool, arguments))).build();
        return new ChatResponse(List.of(new Generation(message)), ChatResponseMetadata.builder()
                .model("continuation-test-model").usage(new DefaultUsage(inputTokens, 1)).build());
    }

    private static final class Fixture implements AutoCloseable {
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final JdbcRunStore runs;
        final ExtensionManager extensions;
        final AgentEngine engine;

        Fixture(ChatModel delegate, DesktopStub desktop, Set<String> allowedTools) {
            this(delegate, desktop, allowedTools, false);
        }

        Fixture(ChatModel delegate, DesktopStub desktop, Set<String> allowedTools, boolean onDemand) {
            Clock clock = Clock.systemUTC();
            var source = new DriverManagerDataSource("jdbc:h2:mem:interaction-continuation-"
                    + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
            new SchemaInitializer(source).initialize();
            JdbcTemplate jdbc = new JdbcTemplate(source);
            var transactions = new DataSourceTransactionManager(source);
            var definitions = new JdbcAgentDefinitionStore(jdbc, transactions, json, clock);
            runs = new JdbcRunStore(jdbc, transactions, json, clock);
            var plans = new JdbcExecutionPlanStore(jdbc, json, clock);
            var capabilities = new java.util.LinkedHashMap<CapabilityId, JsonNode>();
            capabilities.put(new CapabilityId("context.compaction"), JsonNodeFactory.instance.objectNode()
                    .put("enabled", true).put("maxMessageCharacters", desktop.contextCharacters)
                    .put("maxToolSchemaCharacters", desktop.contextCharacters)
                    .put("retainedToolExchanges", desktop.retainedToolExchanges)
                    .put("maxToolResultCharacters", desktop.toolResultCharacters).put("maxTools", 12));
            if (onDemand) capabilities.put(new CapabilityId("context.on_demand"),
                    JsonNodeFactory.instance.objectNode().put("enabled", true));
            AgentDefinitionDraft agent = new AgentDefinitionDraft(InteractionExecutionPolicy.AGENT_ID,
                    "Interaction continuation test", "test:model", Map.of("system", "test"), capabilities,
                    JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(),
                    RunBudget.UNBOUNDED, JsonNodeFactory.instance.objectNode(),
                    Map.of("test.desktop", "=1.0.0", "context.compaction", "=2.0.0"));
            definitions.saveAgentDraft("workspace", agent, false);
            definitions.publishAgent("workspace", agent.id());
            RunBudget budget = new RunBudget(Duration.ofMinutes(5), desktop.maxInputTokens, 1_000_000,
                    20, new BigDecimal("1000"));
            var profile = new RunProfileDraft(InteractionExecutionPolicy.PROFILE_ID, "Interaction test",
                    PermissionSet.UNRESTRICTED, budget, Map.of(), JsonNodeFactory.instance.objectNode());
            definitions.saveProfileDraft("workspace", profile, false);
            definitions.publishProfile("workspace", profile.id());
            var executor = new DirectExecutor();
            ModelTaskGateway tasks = request -> {
                if (!onDemand) return CompletableFuture.failedFuture(
                        new AssertionError("unexpected auxiliary model task: " + request.purpose()));
                ObjectNode selection = JsonNodeFactory.instance.objectNode();
                selection.putArray("historyIds");
                if (request.purpose().endsWith(".select_v2")) {
                    selection.putArray("searches");
                    selection.putObject("toolIntent").put("query", "desktop_session_applications")
                            .putArray("groups").add("desktop-session");
                } else {
                    selection.putArray("sourceIds");
                    selection.put("toolAction", "direct");
                    JsonNode candidate = java.util.stream.StreamSupport.stream(
                            request.input().path("toolCandidates").spliterator(), false)
                            .filter(value -> value.path("name").asText().equals("desktop_session_applications"))
                            .findFirst().orElseThrow();
                    selection.putArray("toolIds").add(candidate.path("id").asText());
                }
                return CompletableFuture.completedFuture(new ModelTaskResult(selection, "test-planner", 1, 1, false, Map.of()));
            };
            extensions = new ExtensionManager(new ExtensionContext(clock, Runnable::run, tasks));
            var artifacts = new java.util.ArrayList<ExtensionArtifact>();
            artifacts.add(ExtensionArtifact.builtin(new AgentFrameworkExtension() {
                @Override public ExtensionDescriptor descriptor() {
                    return new ExtensionDescriptor("test.desktop", SemanticVersion.parse("1.0.0"),
                            ">=2.0.0 <3.0.0", ">=2.0.0 <3.0.0", List.of(), Set.of(),
                            ExtensionScope.PLAN_SCOPED, HotUpdateCompatibility.PLAN_ISOLATED, 1, Map.of());
                }
                @Override public void register(ExtensionRegistrar registrar) {
                    SpringAiAnnotatedToolRegistry registry = new SpringAiAnnotatedToolRegistry(json);
                    registry.register("workspace", context -> ToolObjectBundle.of(List.of(
                            desktop.tools(context), new ClarifyTools())));
                    registrar.toolProvider(registry);
                    registrar.toolProvider(new InteractionTools(new InteractionDelegateGateway() {
                        @Override public InteractionResult delegate(ToolContext parent,
                                ToolExecutionContext execution, InteractionTask task) {
                            throw new AssertionError("a child may not delegate recursively");
                        }
                        @Override public JsonNode selectMode(ToolContext child,
                                ToolExecutionContext execution, InteractionMode mode) {
                            throw new AssertionError("the test starts in the authorized DESKTOP mode");
                        }
                    }, json));
                    registrar.toolPolicy((tool, configuration, request) -> allowedTools.contains(tool.name())
                            || tool.group().equals("interaction") || tool.name().equals("ask_user_clarification")
                            ? ToolPolicyDecision.ALLOW : ToolPolicyDecision.DENY);
                }
            }));
            artifacts.add(ExtensionArtifact.builtin(new BuiltinCapabilityExtension("context.compaction",
                    "Context", "Bounded context", JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.objectNode(), List.of(), registrar -> { })));
            if (onDemand) artifacts.add(ExtensionArtifact.builtin(new BuiltinCapabilityExtension("context.on_demand",
                    "On demand", "Deferred context", JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.objectNode(), List.of(), registrar -> { })));
            extensions.publish(artifacts);
            SpringAiModelRegistry models = new SpringAiModelRegistry();
            models.register("test:model", new ChatModel() {
                @Override public ChatResponse call(Prompt prompt) {
                    // All scenarios begin with one real native-catalog exchange before testing the stop decision.
                    if (desktop.catalogs.get() == 0) {
                        assertTrue(toolNames(prompt).contains("desktop_session_applications"));
                        return SpringAiInteractionContinuationIntegrationTest.call("desktop_session_applications",
                                "{\"query\":\"" + APPLICATION + "\",\"offset\":0}");
                    }
                    return delegate.call(prompt);
                }
                @Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
                    return ToolCallingChatOptions.builder().build();
                }
            });
            RunUsageLedger ledger = new RunUsageLedger();
            var tools = new DefaultToolInvocationGateway((tool, arguments, owner) -> ToolApprovalDecision.ALLOW,
                    executor, clock);
            var reasoning = new SpringAiReasoningGateway(models, new SpringAiAdvisorRegistry(), tools,
                    ExtensionStateStore.disabled(), ledger, tasks, runs, json, executor,
                    io.micrometer.observation.ObservationRegistry.NOOP);
            engine = new AgentEngine(new AgentCompiler(definitions, extensions, json), runs, plans,
                    reasoning, Runnable::run, json, clock, ledger, tasks);
        }

        RunRequest request() {
            TaskContractV3 contract = new TaskContractV3(3, "打开示例应用查看联系人",
                    List.of(new TaskCriterionV3("launch", "启动示例应用", "desktop.launch",
                                    CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, APPLICATION,
                                    EffectReceiptV1.Status.ACCEPTED, ""),
                            new TaskCriterionV3("contacts", "观察联系人页面", "desktop.observe",
                                    CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, APPLICATION,
                                    EffectReceiptV1.Status.OBSERVED, "联系人")), true, true, "definition");
            RunScope parentScope = new RunScope("workspace", "user", "parent-session");
            InvocationSource parentSource = InvocationSource.chat();
            RunRequest parent = RunRequest.builder().agent(AgentDefinitionRef.latest("parent-fixture"))
                    .profile(RunProfileRef.latest(InteractionExecutionPolicy.PROFILE_ID))
                    .source(parentSource).scope(parentScope).input(InputBlock.text(contract.originalRequest()))
                    .permissionCeiling(PermissionSet.UNRESTRICTED).budget(RunBudget.UNBOUNDED).build();
            RunId parentId = RunId.random();
            runs.create(parentId, parent, "parent-fixture-plan", new RunEventDraft("core.run.created", 1,
                    "framework.core", "parent", null, JsonNodeFactory.instance.objectNode()));
            assertTrue(runs.append(parentId, Set.of(RunState.CREATED), RunState.WAITING_CHILD,
                    new RunEventDraft("core.task.contract", 3, "framework.core", "parent", null,
                            json.valueToTree(contract)), null, null).isPresent());
            InteractionTask task = new InteractionTask(1, "fixture-delegation", 1, InteractionMode.DESKTOP,
                    contract.originalRequest(), JsonNodeFactory.instance.objectNode(), List.of(),
                    List.of("launch", "contacts"));
            return RunRequest.builder().agent(AgentDefinitionRef.latest(InteractionExecutionPolicy.AGENT_ID))
                    .profile(RunProfileRef.latest(InteractionExecutionPolicy.PROFILE_ID))
                    .source(new InvocationSource("interaction", "interaction-executor"))
                    .scope(InteractionDelegateCoordinator.childScope(parentScope))
                    .inputs(List.of(InputBlock.text("打开示例应用查看联系人")))
                    .linkage(new RunLinkage(parentId, null, "interaction-executor"))
                    .permissionCeiling(PermissionSet.UNRESTRICTED).budget(RunBudget.UNBOUNDED)
                    .attributes(Map.of(TaskContractCompiler.ATTRIBUTE, json.valueToTree(contract),
                            InteractionExecutionPolicy.TASK_ATTRIBUTE, json.valueToTree(task),
                            InteractionExecutionPolicy.PARENT_SCOPE_ATTRIBUTE, json.valueToTree(parentScope),
                            InteractionExecutionPolicy.PARENT_SOURCE_ATTRIBUTE, json.valueToTree(parentSource),
                            InteractionExecutionPolicy.ALLOWED_MODES_ATTRIBUTE,
                            JsonNodeFactory.instance.arrayNode().add("DESKTOP"),
                            InteractionExecutionPolicy.INITIAL_MODE_ATTRIBUTE,
                            JsonNodeFactory.instance.textNode("DESKTOP"))).build();
        }

        RunOutcome await(RunHandle run) throws Exception {
            RunSnapshot snapshot = engine.get(run.id());
            assertFalse(Set.of(RunState.PAUSED, RunState.WAITING_INPUT, RunState.WAITING_APPROVAL)
                    .contains(snapshot.state()), snapshot.state() + ": " + snapshot.output() + "; " + snapshot.error());
            RunOutcome outcome = run.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(events(run.id()).stream().anyMatch(event -> event.type().equals("core.model.started")),
                    () -> "The fixture did not reach reasoning: " + outcome + "; tools=" + startedTools(run.id()));
            return outcome;
        }
        List<RunEventEnvelope> events(RunId id) { return runs.eventsAfter(id, 0); }
        long repairs(RunId id) {
            return events(id).stream().filter(event -> event.type().equals("core.task.repair_requested")
                    && event.payload().path("reasonCode").asText().equals(REPAIR_REASON)).count();
        }
        List<String> startedTools(RunId id) {
            return events(id).stream().filter(event -> event.type().equals("core.tool.started"))
                    .map(event -> event.payload().path("tool").asText()).toList();
        }
        @Override public void close() { engine.close(); extensions.close(); }
    }

    private static final class DesktopStub {
        final AtomicInteger catalogs = new AtomicInteger();
        final AtomicInteger launches = new AtomicInteger();
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger observes = new AtomicInteger();
        final AtomicInteger clicks = new AtomicInteger();
        boolean opened;
        boolean control;
        boolean denyOpen;
        boolean available = true;
        boolean settingEnabled = true;
        boolean unknownClick;
        long maxInputTokens = 1_000_000;
        int toolResultCharacters = 16_000;
        int contextCharacters = 48_000;
        int retainedToolExchanges = 4;
        final DesktopTarget target = new DesktopTarget("test", TARGET, 42L, APPLICATION,
                APPLICATION, 0, 0, 120, 120, DesktopTarget.VISIBLE, APPLICATION);
        List<DesktopTarget> windows = List.of(target);

        DesktopSessionTools tools(ToolContext context) {
            DesktopSessionService service = (DesktopSessionService) Proxy.newProxyInstance(
                    DesktopSessionService.class.getClassLoader(), new Class<?>[]{DesktopSessionService.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "availability": return new DesktopAvailability(available, "test",
                                    available ? DesktopAvailability.CAPTURE | DesktopAvailability.SEMANTIC_INPUT : 0,
                                    available ? "ready" : "screen recording permission denied");
                            case "liveSessionIds": return Optional.of(opened ? List.of(SESSION) : List.of());
                            case "discoverApplications":
                                catalogs.incrementAndGet();
                                return CompletableFuture.completedFuture(new DesktopApplicationCatalog(List.of(
                                        new DesktopApplicationInfo(APPLICATION, APPLICATION, APPLICATION,
                                                APPLICATION, List.of(APPLICATION))), false));
                            case "discoverTargets": return CompletableFuture.completedFuture(windows);
                            case "launchApplication":
                                launches.incrementAndGet();
                                return CompletableFuture.completedFuture(new DesktopApplicationLaunchResult(42L,
                                        APPLICATION, List.of(target), "ready"));
                            case "open":
                                opens.incrementAndGet();
                                assertEquals(TARGET, args[1]);
                                if (denyOpen) return CompletableFuture.failedFuture(new SecurityException("screen recording denied"));
                                opened = true;
                                control = Boolean.TRUE.equals(args[2]);
                                return CompletableFuture.completedFuture(info());
                            case "info": return info();
                            case "state": return new DesktopSessionState(SESSION, DesktopSessionState.Kind.LIVE,
                                    "ready", System.currentTimeMillis());
                            case "captureObservation":
                                if (!opened) return CompletableFuture.completedFuture(Optional.empty());
                                int index = observes.incrementAndGet();
                                long captured = System.currentTimeMillis();
                                if (args.length == 3) captured = Math.max(captured, (long) args[2] + 1);
                                String observation = "00000000-0000-4000-8000-%012d".formatted(index);
                                return CompletableFuture.completedFuture(Optional.of(new DesktopObservation(SESSION,
                                        observation, new DesktopFrame(TARGET, 1, captured, 120, 120, 120 * 4,
                                                new byte[120 * 120 * 4], 1), List.of())));
                            case "commitObservation": return CompletableFuture.completedFuture(true);
                            case "perform":
                                assertTrue(control, "the test service must never receive unauthorized input");
                                clicks.incrementAndGet();
                                DesktopAction action = (DesktopAction) args[2];
                                return CompletableFuture.completedFuture(new DesktopActionResult(
                                        unknownClick ? DesktopActionResult.Status.UNKNOWN : DesktopActionResult.Status.VERIFIED,
                                        unknownClick ? "delivery uncertain" : "clicked", 1,
                                        DesktopActionResult.Mode.BACKGROUND_SEMANTIC,
                                        unknownClick ? DesktopActionResult.Reason.DELIVERY_UNCERTAIN : DesktopActionResult.Reason.NONE,
                                        true, action.observationId(), DesktopActionResult.NextStep.OBSERVE));
                            case "closeSession", "closeScope", "closeWorkspace", "close": return null;
                            default:
                                if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                                throw new UnsupportedOperationException(method.getName());
                        }
                    });
            VisionPreprocessor vision = new VisionPreprocessor(request -> {
                String subject = unknownClick ? "概览" : "联系人";
                ObjectNode output = JsonNodeFactory.instance.objectNode()
                        .put("summary", subject + "页面").put("visibleText", subject + "\n测试分组");
                output.putArray("targets").addObject().put("label", "联系人标签").put("role", "tab")
                        .put("x", 10).put("y", 10).put("width", 30).put("height", 30).put("confidence", 0.95);
                ObjectNode view = output.putObject("activeView").put("label", subject).put("confidence", 0.95);
                view.putObject("heading").put("label", subject).put("role", "heading")
                        .put("x", 1).put("y", 1).put("width", 50).put("height", 10).put("confidence", 0.95);
                view.putObject("content").put("label", "测试分组").put("role", "content")
                        .put("x", 1).put("y", 50).put("width", 100).put("height", 20).put("confidence", 0.95);
                return CompletableFuture.completedFuture(new ModelTaskResult(output, "test-vision", 1, 1, false, Map.of()));
            }, context.runId());
            return new DesktopSessionTools(service,
                    new DesktopSessionOwner(context.scope().workspaceId(), context.scope().sessionId(),
                            "interaction", context.runId().value()),
                    Path.of("target", "continuation-test-screenshots"), null, vision,
                    () -> List.of(JsonNodeFactory.instance.objectNode().put("kind", "desktop.access.current")
                            .put("settingEnabled", settingEnabled).put("systemStatus", "NOT_CHECKED")));
        }
        DesktopSessionInfo info() { return new DesktopSessionInfo(SESSION, target, control, false); }
    }

    private static final class DirectExecutor implements CancellableTaskExecutor {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public <T> CancellableTask<T> submit(String name, Duration timeout,
                CancellationToken cancellation, Callable<T> task) {
            CompletableFuture<T> result = new CompletableFuture<>();
            try { cancellation.throwIfCancelled(); result.complete(task.call()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
            return new CancellableTask<>() {
                @Override public java.util.concurrent.CompletionStage<T> completion() { return result; }
                @Override public java.util.concurrent.CompletionStage<Void> termination() {
                    return CompletableFuture.completedFuture(null);
                }
                @Override public boolean cancel() { return false; }
            };
        }
    }
}
