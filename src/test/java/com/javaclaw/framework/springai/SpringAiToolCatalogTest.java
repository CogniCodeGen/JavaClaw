package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentDefinition;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunProfile;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.core.AgentCompiler;
import com.javaclaw.framework.core.ExecutionPlan;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.StepContextPolicy;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.AgentDefinitionResolver;
import com.javaclaw.framework.spi.ExtensionContext;
import com.javaclaw.framework.spi.RunStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpringAiToolCatalogTest {

    private static ExtensionManager extensions;
    private static ExecutionPlan plan;
    private static HarnessDecisionToolCallback decisionCallback;

    @BeforeAll
    static void createTrustedControlCallback() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        extensions = new ExtensionManager(new ExtensionContext(Clock.systemUTC(), Runnable::run,
                ignored -> CompletableFuture.failedFuture(new AssertionError("no model task expected"))));
        RunRequest runRequest = runRequest(Map.of());
        plan = new AgentCompiler(new AgentDefinitionResolver() {
            @Override public AgentDefinition resolveAgent(String workspaceId, AgentDefinitionRef reference) {
                return new AgentDefinition("agent", 1L, "Catalog test", "test:model",
                        Map.of("system", "test"), Map.of(), JsonNodeFactory.instance.objectNode(),
                        JsonNodeFactory.instance.objectNode(), RunBudget.UNBOUNDED,
                        JsonNodeFactory.instance.objectNode(), Map.of(), "agent-checksum");
            }
            @Override public RunProfile resolveProfile(String workspaceId, RunProfileRef reference) {
                return new RunProfile("profile", 1L, "Catalog test", PermissionSet.UNRESTRICTED,
                        RunBudget.UNBOUNDED, Map.of(), JsonNodeFactory.instance.objectNode(),
                        "profile-checksum");
            }
        }, extensions, json).compile(runRequest);
        RunStore unusedStore = (RunStore) Proxy.newProxyInstance(
                RunStore.class.getClassLoader(), new Class<?>[]{RunStore.class},
                (ignored, method, arguments) -> {
                    throw new AssertionError("catalog test must not read " + method.getName());
                });
        ReasoningRequest reasoning = new ReasoningRequest(new RunId("catalog-test-run"),
                plan, runRequest, null, null, null);
        decisionCallback = new ModelStepJournal(reasoning, unusedStore, json).decisionCallback();
    }

    @AfterAll
    static void closeTrustedControlCallback() {
        if (plan != null) plan.close();
        if (extensions != null) extensions.close();
    }

    @Test
    void 必需恢复工具优先于目录顺序() {
        List<ToolCallback> callbacks = withControl(
                callback("optional", 2_000),
                callback("required", 1_000),
                callback("later", 1_000));
        StepContextPolicy policy = new StepContextPolicy(48_000, 4_000, 4, 16_000, 2);

        ToolCatalogProjection projection = SpringAiToolCatalog.project(
                callbacks, Set.of("required"), policy);

        assertEquals(List.of("required", "optional", HarnessDecisionToolCallback.NAME), names(projection));
        assertEquals(3, projection.callbacks().size());
        assertEquals(4, projection.availableToolCount());
    }

    @Test
    void 工具数量和Schema字符预算同时生效() {
        List<ToolCallback> callbacks = withControl(
                callback("first", 1_500),
                callback("second", 1_500),
                callback("third", 1_500));

        ToolCatalogProjection countLimited = SpringAiToolCatalog.project(
                callbacks, Set.of(), new StepContextPolicy(48_000, 10_000, 4, 16_000, 2));
        ToolCatalogProjection schemaLimited = SpringAiToolCatalog.project(
                callbacks, Set.of(), new StepContextPolicy(48_000, 4_000, 4, 16_000, 10));

        assertEquals(List.of("first", "second", HarnessDecisionToolCallback.NAME), names(countLimited));
        assertEquals(List.of("first", "second", HarnessDecisionToolCallback.NAME), names(schemaLimited));
    }

    @Test
    void 必需恢复工具超出预算时拒绝静默裁剪() {
        List<ToolCallback> callbacks = withControl(
                callback("required-one", 2_500),
                callback("required-two", 2_500));
        StepContextPolicy policy = new StepContextPolicy(48_000, 4_000, 4, 16_000, 10);

        assertThrows(IllegalStateException.class, () -> SpringAiToolCatalog.project(
                callbacks, Set.of("required-one", "required-two"), policy));
    }

    @Test
    void 显式工具组优先于发现工具和普通工具() {
        List<ToolCallback> callbacks = withControl(
                callback("ordinary", "host", 1_000),
                callback("memory_recall", "memory", 1_000),
                callback("selected", "workspace", 1_000));
        StepContextPolicy policy = new StepContextPolicy(48_000, 4_000, 4, 16_000, 2);

        ToolCatalogProjection projection = SpringAiToolCatalog.project(
                callbacks, Set.of(), Set.of("workspace"), policy);

        assertEquals(List.of("selected", "memory_recall", HarnessDecisionToolCallback.NAME), names(projection));
    }

    @Test
    void 已使用工具组加入后续目录优先级() {
        List<ToolCallback> callbacks = withControl(
                callback("ordinary", "host", 1_000),
                callback("memory_recall", "memory", 1_000),
                callback("workspace_read", "workspace", 1_000));
        AssistantMessage used = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "used", "function", "workspace_read", "{}")))
                .build();

        Set<String> groups = SpringAiToolCatalog.preferredGroups(
                request(Map.of()), List.of(used), callbacks);
        ToolCatalogProjection projection = SpringAiToolCatalog.project(
                callbacks, Set.of(), groups,
                new StepContextPolicy(48_000, 4_000, 4, 16_000, 2));

        assertEquals(Set.of("workspace"), groups);
        assertEquals(List.of("workspace_read", "memory_recall", HarnessDecisionToolCallback.NAME), names(projection));
    }

    @Test
    void 通配工具授权不会伪造显式目录偏好() {
        var groups = JsonNodeFactory.instance.arrayNode().add("*");

        assertEquals(Set.of(), SpringAiToolCatalog.explicitGroups(
                request(Map.of("framework.allowedToolGroups", groups))));
    }

    @Test
    void 缺少必需恢复工具时拒绝继续() {
        List<ToolCallback> callbacks = withControl(callback("available", 1_000));

        assertThrows(IllegalStateException.class, () -> SpringAiToolCatalog.project(
                callbacks, Set.of("missing"), StepContextPolicy.DEFAULT));
    }

    @Test
    void 普通同名工具不能冒充可信控制回调() {
        assertThrows(IllegalStateException.class, () -> SpringAiToolCatalog.project(
                List.of(callback(HarnessDecisionToolCallback.NAME, 100)),
                Set.of(), StepContextPolicy.DEFAULT));
    }

    @Test
    void 重复工具名在目录构造阶段被拒绝() {
        List<ToolCallback> callbacks = List.of(
                callback("duplicate", 1_000),
                callback("duplicate", 1_000));

        assertThrows(IllegalStateException.class,
                () -> SpringAiToolCatalog.ensureUniqueNames(callbacks));
    }

    @Test
    void 从投影消息提取全部已持久化工具名() {
        AssistantMessage calls = AssistantMessage.builder().content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("one", "function", "first", "{}"),
                        new AssistantMessage.ToolCall("two", "function", "second", "{}")))
                .build();
        ToolResponseMessage responses = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("one", "first", "ok"),
                new ToolResponseMessage.ToolResponse("two", "second", "ok")))
                .build();

        assertEquals(Set.of("first", "second"),
                SpringAiToolCatalog.requiredNames(List.<Message>of(calls, responses)));
    }

    private static ToolCallback callback(String name, int descriptionCharacters) {
        return callback(name, "", descriptionCharacters);
    }

    private static List<ToolCallback> withControl(ToolCallback... business) {
        List<ToolCallback> callbacks = new ArrayList<>(List.of(business));
        callbacks.add(decisionCallback);
        return List.copyOf(callbacks);
    }

    private static ToolCallback callback(
            String name, String group, int descriptionCharacters) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("d".repeat(descriptionCharacters))
                .inputSchema("{}")
                .build();
        return new GroupedTestCallback(definition, group);
    }

    private static ReasoningRequest request(
            Map<String, com.fasterxml.jackson.databind.JsonNode> attributes) {
        RunRequest request = runRequest(attributes);
        return new ReasoningRequest(new RunId("run-test"), null, request,
                null, null, null);
    }

    private static RunRequest runRequest(
            Map<String, com.fasterxml.jackson.databind.JsonNode> attributes) {
        return RunRequest.builder()
                .agent(new AgentDefinitionRef("agent", 1L))
                .profile(new RunProfileRef("profile", 1L))
                .source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "session"))
                .input(InputBlock.text("task"))
                .permissionCeiling(PermissionSet.NONE)
                .budget(RunBudget.UNBOUNDED)
                .attributes(attributes)
                .build();
    }

    private record GroupedTestCallback(
            ToolDefinition definition,
            String group)
            implements ToolCallback, SpringAiToolCatalog.GroupedCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String input) {
            return "{}";
        }
    }

    private static List<String> names(ToolCatalogProjection projection) {
        return projection.callbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }
}
