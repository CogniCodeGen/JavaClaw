package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentDefinition;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.CapabilityMetadata;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunProfile;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskCriterionV3;
import com.javaclaw.framework.core.AgentCompiler;
import com.javaclaw.framework.core.ExecutionPlan;
import com.javaclaw.framework.core.InteractionExecutionPolicy;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunControl;
import com.javaclaw.framework.core.StepContextPolicy;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.AgentDefinitionResolver;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.framework.spi.ExtensionContext;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.spi.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCatalogInteractionVisibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String DELEGATE = InteractionExecutionPolicy.DELEGATE_TOOL;

    @Test
    void 纯回答及非交互合同在所有目录入口隐藏委派() throws Exception {
        for (TaskContractV3 contract : List.of(answer(), contract("file.read"))) {
            try (Fixture fixture = new Fixture(8)) {
                fixture.contract(contract);
                fixture.assertHidden();
                assertTrue(fixture.session.summaries().stream().anyMatch(tool -> tool.name().equals("workspace_read")));
            }
        }
    }

    @Test
    void 缺少可信宿主合同不能通过请求属性或伪造事件显示委派() throws Exception {
        try (Fixture fixture = new Fixture(8)) {
            fixture.assertHidden();
            fixture.event(fixture.runId.value(), "core.task.contract", 3, "model", JSON.valueToTree(contract("browser.observe")));
            fixture.assertHidden();
            fixture.event(fixture.runId.value(), "core.task.contract", 2, "framework.core", JSON.valueToTree(contract("browser.observe")));
            fixture.assertHidden();
            fixture.event("another-run", "core.task.contract", 3, "framework.core", JSON.valueToTree(contract("desktop.observe")));
            fixture.assertHidden();
            fixture.event(fixture.runId.value(), "core.task.contract", 3, "framework.core", NODES.objectNode().put("version", 3));
            fixture.assertHidden();
        }
    }

    @Test
    void 浏览器及桌面冻结条件均保留正常委派入口() throws Exception {
        for (String capability : List.of("browser.observe", "desktop.observe")) {
            try (Fixture fixture = new Fixture(8)) {
                fixture.contract(contract(capability));
                fixture.assertVisible();
                assertTrue(fixture.activate().path("success").asBoolean());
            }
        }
    }

    @Test
    void 合同修订即时改变可见性且旧激活不能绕过() throws Exception {
        try (Fixture fixture = new Fixture(8)) {
            fixture.contract(answer());
            fixture.assertHidden();
            fixture.revise(contract("desktop.observe"));
            fixture.assertVisible();
            fixture.persistActivation(fixture.activate());
            assertEquals(List.of(DELEGATE, "workspace_read"), fixture.session.activeNames());

            fixture.revise(contract("file.read"));
            fixture.assertHidden();
            assertEquals(List.of("workspace_read"), fixture.session.activeNames());
            fixture.revise(contract("browser.observe"));
            fixture.assertVisible();
        }
    }

    @Test
    void 目录容量不足的普通投影同样过滤无交互合同委派() throws Exception {
        try (Fixture fixture = new Fixture(2)) {
            fixture.contract(answer());
            assertFalse(names(fixture.session.project(List.of())).contains(DELEGATE));
            assertTrue(names(fixture.session.project(List.of())).contains("workspace_read"));
        }
    }

    private static TaskContractV3 answer() {
        return new TaskContractV3(3, "解释概念", List.of(), false, true, "model");
    }

    private static TaskContractV3 contract(String capability) {
        var targetKind = capability.startsWith("browser.") ? CapabilityMetadata.TargetKind.URL
                : capability.startsWith("desktop.") ? CapabilityMetadata.TargetKind.DESKTOP_APPLICATION
                : CapabilityMetadata.TargetKind.FILE;
        var criterion = new TaskCriterionV3("required", "需要的真实结果", capability,
                targetKind, "target", EffectReceiptV1.Status.OBSERVED, "内容");
        return new TaskContractV3(3, "测试任务", List.of(criterion), true, true, "definition");
    }

    private static List<String> names(ToolCatalogProjection projection) {
        return projection.callbacks().stream().map(callback -> callback.getToolDefinition().name()).toList();
    }

    private static FrameworkTool tool(String name, String group) {
        return new FrameworkTool() {
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor(name, "测试目录工具", NODES.objectNode().put("type", "object"),
                        group, PermissionSet.NONE, true);
            }
            @Override public JsonNode execute(JsonNode arguments, ToolExecutionContext execution) {
                throw new AssertionError("目录测试不能执行业务工具");
            }
        };
    }

    private static ToolCallback callback(FrameworkTool tool) {
        var descriptor = tool.descriptor();
        return new GroupedCallback(ToolDefinition.builder().name(descriptor.name())
                .description(descriptor.description()).inputSchema(descriptor.inputSchema().toString()).build(), descriptor.group());
    }

    private record GroupedCallback(ToolDefinition definition, String group)
            implements ToolCallback, SpringAiToolCatalog.GroupedCallback {
        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public String call(String input) { throw new AssertionError("目录测试不能执行回调"); }
    }

    private static final class Fixture implements AutoCloseable {
        private final RunId runId = new RunId("catalog-interaction-run");
        private final List<RunEventEnvelope> events = new ArrayList<>();
        private final ExtensionManager extensions;
        private final ExecutionPlan plan;
        private final ToolCatalogSession session;

        Fixture(int maxTools) throws Exception {
            extensions = new ExtensionManager(new ExtensionContext(Clock.systemUTC(), Runnable::run,
                    ignored -> CompletableFuture.failedFuture(new AssertionError("目录测试不能调用模型"))));
            var compiled = NODES.objectNode();
            compiled.putObject("interaction.run").put("enabled", true);
            RunRequest request = RunRequest.builder().agent(new AgentDefinitionRef("catalog.agent", 1L))
                    .profile(new RunProfileRef("catalog.profile", 1L)).source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", "session")).input(InputBlock.text("测试任务"))
                    .permissionCeiling(PermissionSet.UNRESTRICTED).budget(RunBudget.UNBOUNDED)
                    .attributes(Map.of("framework.compiledCapabilities", compiled,
                            "framework.taskContract", JSON.valueToTree(ToolCatalogInteractionVisibilityTest.contract("browser.observe")))).build();
            plan = new AgentCompiler(new AgentDefinitionResolver() {
                @Override public AgentDefinition resolveAgent(String workspace, AgentDefinitionRef reference) {
                    return new AgentDefinition("catalog.agent", 1L, "目录测试", "test:model", Map.of(), Map.of(),
                            NODES.objectNode(), NODES.objectNode(), RunBudget.UNBOUNDED, NODES.objectNode(), Map.of(), "agent-checksum");
                }
                @Override public RunProfile resolveProfile(String workspace, RunProfileRef reference) {
                    return new RunProfile("catalog.profile", 1L, "目录测试", PermissionSet.UNRESTRICTED,
                            RunBudget.UNBOUNDED, Map.of(), NODES.objectNode(), "profile-checksum");
                }
            }, extensions, JSON).compile(request);
            RunStore store = (RunStore) Proxy.newProxyInstance(RunStore.class.getClassLoader(),
                    new Class<?>[]{RunStore.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("eventsAfter")) {
                            assertEquals(runId, arguments[0]);
                            long after = (Long) arguments[1];
                            return events.stream().filter(event -> event.sequence() > after).toList();
                        }
                        throw new AssertionError("目录测试不得访问存储接口：" + method.getName());
                    });
            var constructor = RunControl.class.getDeclaredConstructor(RunBudget.class, Clock.class);
            constructor.setAccessible(true);
            var reasoning = new ReasoningRequest(runId, plan, request, null,
                    constructor.newInstance(RunBudget.UNBOUNDED, Clock.systemUTC()), null);
            var tools = List.of(tool(DELEGATE, "interaction"), tool("workspace_read", "files"), tool("workspace_list", "files"));
            session = new ToolCatalogSession(reasoning, store, JSON,
                    new StepContextPolicy(48_000, 48_000, 4, 16_000, maxTools), tools);
            List<ToolCallback> callbacks = new ArrayList<>(tools.stream().map(ToolCatalogInteractionVisibilityTest::callback).toList());
            callbacks.add(callback(session));
            callbacks.add(new ModelStepJournal(reasoning, store, JSON).decisionCallback());
            session.bindCallbacks(callbacks);
        }

        void contract(TaskContractV3 contract) {
            event(runId.value(), "core.task.contract", 3, "framework.core", JSON.valueToTree(contract));
        }

        void revise(TaskContractV3 contract) {
            event(runId.value(), "core.task.contract_revised", 3, "framework.core", JSON.valueToTree(contract));
        }

        void event(String owner, String type, int version, String producer, JsonNode payload) {
            events.add(new RunEventEnvelope(owner, events.size() + 1L, Instant.now(), type,
                    version, producer, null, null, payload));
        }

        JsonNode activate() {
            ObjectNode input = NODES.objectNode().put("action", "activate");
            input.putArray("names").add(DELEGATE).add("workspace_read");
            return session.execute(input, null);
        }

        void persistActivation(JsonNode output) {
            assertTrue(output.path("success").asBoolean());
            ObjectNode started = NODES.objectNode().put("stepId", "activate-step").put("kind", "TOOL");
            started.putObject("input").put("tool", ToolCatalogSession.NAME)
                    .putObject("arguments").put("action", "activate");
            event(runId.value(), "core.step.started", 1, "framework.core", started);
            ObjectNode completed = NODES.objectNode().put("stepId", "activate-step");
            completed.putObject("output").set("rawOutput", output);
            event(runId.value(), "core.step.completed", 1, "framework.core", completed);
        }

        void assertHidden() {
            assertFalse(session.summaries().stream().anyMatch(tool -> tool.name().equals(DELEGATE)));
            assertFalse(session.execute(NODES.objectNode(), null).toString().contains(DELEGATE));
            assertFalse(names(session.project(List.of())).contains(DELEGATE));
            assertFalse(names(session.projectPlanned(List.of(DELEGATE), 8, ToolCatalogSession.CatalogMode.NONE)).contains(DELEGATE));
            assertThrows(IllegalStateException.class,
                    () -> session.projectExactRole(List.of(DELEGATE), 8, ToolCatalogSession.CatalogMode.NONE));
            JsonNode activation = activate();
            assertFalse(activation.path("success").asBoolean());
            assertFalse(activation.has("activated"), "混合激活必须整批拒绝");
        }

        void assertVisible() {
            assertTrue(session.summaries().stream().anyMatch(tool -> tool.name().equals(DELEGATE)));
            assertTrue(session.execute(NODES.objectNode(), null).toString().contains(DELEGATE));
            assertTrue(names(session.project(List.of())).contains(DELEGATE));
            assertTrue(names(session.projectPlanned(List.of(DELEGATE), 8, ToolCatalogSession.CatalogMode.NONE)).contains(DELEGATE));
            assertTrue(names(session.projectExactRole(List.of(DELEGATE), 8, ToolCatalogSession.CatalogMode.NONE)).contains(DELEGATE));
        }

        @Override public void close() {
            plan.close();
            extensions.close();
        }
    }
}
