package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.builtin.BuiltinCapabilityExtension;
import com.javaclaw.framework.builtin.InteractionTools;
import com.javaclaw.framework.core.*;
import com.javaclaw.framework.extension.ExtensionArtifact;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OnDemandInteractionReplayVisibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String DELEGATE = InteractionExecutionPolicy.DELEGATE_TOOL;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 旧在途模型步骤恢复时先隐藏无交互条件的委派且保留历史目录(boolean streaming) throws Exception {
        try (Fixture fixture = new Fixture()) {
            var journal = new ModelStepJournal(fixture.request, fixture.store, JSON);
            var catalog = new ToolCatalogSession(fixture.request, fixture.store, JSON,
                    fixture.plan.descriptor().stepContextPolicy(), List.of(fixture.delegate));
            List<ToolCallback> callbacks = new ArrayList<>(SpringAiToolCatalog.createCallbacks(
                    fixture.request, List.of(fixture.delegate, catalog), fixture.tools, JSON, journal));
            callbacks.add(journal.decisionCallback());
            catalog.bindCallbacks(callbacks);
            ToolCallback delegate = callbacks.stream().filter(callback ->
                    callback.getToolDefinition().name().equals(DELEGATE)).findFirst().orElseThrow();

            // 模拟旧版本已冻结的候选与在途请求；恢复时不能重写这份历史。
            StepId candidate = StepId.random();
            var candidateInput = NODES.objectNode().put("phase", "tool_search_v2");
            StepEvents.started(fixture.events, candidate, AgentStep.Kind.ORCHESTRATION, candidateInput, null);
            var candidateOutput = NODES.objectNode();
            candidateOutput.putArray("candidates").addObject().put("id", "t0").put("name", DELEGATE)
                    .put("group", "interaction").put("fingerprint", ToolCatalogSession.fingerprint(delegate));
            StepEvents.completed(fixture.events, candidate, candidateOutput, null);
            List<Message> frozenMessages = List.of(new SystemMessage("遵循宿主冻结合同"),
                    SpringAiPromptFactory.originalTaskMessage(fixture.request));
            var oldOptions = ToolCallingChatOptions.builder()
                    .toolCallbacks(List.of(delegate, journal.decisionCallback())).build();
            StepId oldModel = journal.started(new Prompt(frozenMessages, oldOptions), 1, candidate.value());

            var recovered = journal.recover(List.of(fixture.delegate, catalog), fixture.tools, catalog, false);
            assertNotNull(recovered);
            assertTrue(recovered.replayPrompt());
            assertTrue(recovered.toolNames().contains(DELEGATE));
            var onDemand = new OnDemandContextSession(fixture.request, catalog,
                    ignored -> CompletableFuture.failedFuture(new AssertionError("恢复不能重新规划")),
                    fixture.tools, fixture.store, JSON, List.of(), journal.decisionCallback(),
                    List.of(new SystemMessage("遵循宿主冻结合同")));
            onDemand.replayOnce(recovered);

            var projector = new StepContextProjector(fixture.plan.descriptor().stepContextPolicy(), JSON);
            var boundary = new ProviderContextBoundary();
            var advisor = SpringAiToolCallingAdvisor.builder(projector, catalog, boundary, onDemand)
                    .toolAvailability(callback -> !callback.getToolDefinition().name().equals(DELEGATE)
                            || !InteractionExecutionPolicy.interactionCriteria(TaskResultEvaluator.latestContractV3(
                                    fixture.store.eventsAfter(fixture.runId, 0), JSON).orElse(null)).isEmpty())
                    .toolCallingManager(ToolCallingManager.builder().build()).build();
            var restoredRequest = new ChatClientRequest(new Prompt(recovered.providerMessages(), oldOptions), Map.of());
            ChatClientRequest selected = streaming ? advisor.doBeforeStream(restoredRequest, null)
                    : advisor.doBeforeCall(restoredRequest, null);
            assertEquals(List.of(HarnessDecisionToolCallback.NAME), names(selected.prompt()));
            assertEquals(names(selected.prompt()), boundary.expectedTools().stream()
                    .map(callback -> callback.getToolDefinition().name()).toList());

            AtomicInteger providerCalls = new AtomicInteger();
            ChatModel provider = new ChatModel() {
                @Override public ChatResponse call(Prompt prompt) {
                    providerCalls.incrementAndGet();
                    assertEquals(List.of(HarnessDecisionToolCallback.NAME), names(prompt));
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("恢复后继续回答"))));
                }
                @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
            };
            var usage = new RunUsageLedger();
            usage.open(fixture.runId, RunBudget.UNBOUNDED, fixture.request.runRequest().scope());
            var metered = new SpringAiMeteredChatModel(provider, journal, new AtomicInteger(1),
                    () -> usage.beginModelCall(fixture.runId), fixture.request.control()::throwIfCancelled,
                    ignored -> {}, ignored -> {}, projector, catalog, fixture.request, boundary);
            assertNotNull(streaming ? metered.stream(selected.prompt()).blockLast() : metered.call(selected.prompt()));
            assertEquals(1, providerCalls.get());
            var steps = new RunStepQuery(fixture.store).steps(fixture.runId);
            var original = steps.stream().filter(step -> step.id().equals(oldModel)).findFirst().orElseThrow();
            assertEquals(AgentStep.State.RUNNING, original.state());
            assertTrue(original.input().path("toolNames").toString().contains(DELEGATE));
            var latest = steps.stream().filter(step -> step.kind() == AgentStep.Kind.MODEL).toList().getLast();
            assertEquals(AgentStep.State.COMPLETED, latest.state());
            assertEquals(NODES.arrayNode().add(HarnessDecisionToolCallback.NAME), latest.input().path("toolNames"));
        }
    }

    private static List<String> names(Prompt prompt) {
        return ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name()).toList();
    }

    private static final class Fixture implements AutoCloseable {
        private final RunId runId = new RunId("interaction-replay-test");
        private final List<RunEventEnvelope> history = new ArrayList<>();
        private final ReasoningEventSink events = (type, version, producer, payload) -> history.add(
                new RunEventEnvelope(runId.value(), history.size() + 1L, Instant.now(), type,
                        version, producer, null, null, payload.deepCopy()));
        private final ExtensionManager extensions = new ExtensionManager(new ExtensionContext(
                Clock.systemUTC(), Runnable::run,
                ignored -> CompletableFuture.failedFuture(new AssertionError("恢复不能调用辅助模型"))));
        private final ExecutionPlan plan;
        private final ReasoningRequest request;
        private final RunStore store;
        private final FrameworkTool delegate;
        private final ToolInvocationGateway tools = ignored -> {
            throw new AssertionError("在途模型恢复不能执行业务工具");
        };

        private Fixture() throws Exception {
            extensions.publish(List.of(capability("context.compaction"), capability("context.on_demand")));
            var runRequest = RunRequest.builder().agent(new AgentDefinitionRef("replay.agent", 1L))
                    .profile(new RunProfileRef("replay.profile", 1L)).source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", "session")).input(InputBlock.text("解释概念"))
                    .permissionCeiling(PermissionSet.UNRESTRICTED).budget(RunBudget.UNBOUNDED).build();
            plan = new AgentCompiler(new AgentDefinitionResolver() {
                @Override public AgentDefinition resolveAgent(String workspace, AgentDefinitionRef reference) {
                    return new AgentDefinition("replay.agent", 1L, "恢复测试", "test:model", Map.of(),
                            Map.of(new CapabilityId("context.compaction"), NODES.objectNode().put("enabled", true),
                                    new CapabilityId("context.on_demand"), NODES.objectNode().put("enabled", true)),
                            NODES.objectNode(), NODES.objectNode(), RunBudget.UNBOUNDED,
                            NODES.objectNode(), Map.of(), "agent-checksum");
                }
                @Override public RunProfile resolveProfile(String workspace, RunProfileRef reference) {
                    return new RunProfile("replay.profile", 1L, "恢复测试", PermissionSet.UNRESTRICTED,
                            RunBudget.UNBOUNDED, Map.of(), NODES.objectNode(), "profile-checksum");
                }
            }, extensions, JSON).compile(runRequest);
            var constructor = RunControl.class.getDeclaredConstructor(RunBudget.class, Clock.class);
            constructor.setAccessible(true);
            request = new ReasoningRequest(runId, plan, runRequest, null,
                    constructor.newInstance(RunBudget.UNBOUNDED, Clock.systemUTC()), events);
            var stored = new StoredRun(new RunSnapshot(runId, RunState.RUNNING, plan.descriptor().id(),
                    0, Instant.now(), Instant.now(), null, null, 1), runRequest);
            store = (RunStore) Proxy.newProxyInstance(RunStore.class.getClassLoader(),
                    new Class<?>[]{RunStore.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "find" -> Optional.of(stored);
                        case "readable" -> true;
                        case "eventsAfter" -> history.stream()
                                .filter(event -> event.sequence() > (long) arguments[1]).toList();
                        default -> throw new AssertionError("恢复不能访问其他存储接口：" + method.getName());
                    });
            delegate = new InteractionTools(new InteractionDelegateGateway() {
                @Override public InteractionResult delegate(ToolContext parent, ToolExecutionContext execution,
                        InteractionTask task) { throw new AssertionError("恢复不能创建交互子任务"); }
                @Override public JsonNode selectMode(ToolContext child, ToolExecutionContext execution,
                        InteractionMode mode) { throw new AssertionError("恢复不能访问交互后端"); }
            }, JSON).create(new ToolContext(runId, runRequest.scope(), PermissionSet.UNRESTRICTED,
                    request.control(), request.control().deadline(), runRequest)).getFirst();
            events.emit("core.task.contract", 3, "framework.core", JSON.valueToTree(
                    new TaskContractV3(3, "解释概念", List.of(), false, true, "definition")));
        }

        private static ExtensionArtifact capability(String id) {
            return ExtensionArtifact.builtin(new BuiltinCapabilityExtension(id, id, "恢复测试能力",
                    NODES.objectNode(), NODES.objectNode(), List.of(), null));
        }

        @Override public void close() {
            plan.close();
            extensions.close();
        }
    }
}
