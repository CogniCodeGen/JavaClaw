package com.javaclaw.server.extension.mcp;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.agent.tool.UserInputGateway;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.AttemptId;
import com.javaclaw.core.api.ModelResponse;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadStatus;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStatus;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultMcpInputResolverTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void returnsBareMultiFieldElicitationAndWorkspaceRootsResponses() throws Exception {
        AtomicInteger answers = new AtomicInteger();
        UserInputGateway userInput = (request, announce) -> {
            announce.run();
            return new UserInputGateway.Response(answers.getAndIncrement() == 0 ? "octocat" : "true", false);
        };
        DefaultMcpInputResolver resolver = new DefaultMcpInputResolver(
                userInput, request -> new ModelResponse("sample", "", List.of(), ModelUsage.ZERO), json);
        var request = json.readTree("""
                {"method":"elicitation/create","params":{"mode":"form",
                 "message":"Credentials","requestedSchema":{"type":"object",
                 "properties":{"name":{"type":"string"},
                 "remember":{"type":"boolean"}}}}}
                """);

        var response = resolver.resolve("request", request, invocation());
        var roots = resolver.resolve("roots", json.readTree("{\"method\":\"roots/list\",\"params\":{}}"), invocation());

        assertEquals("accept", response.path("action").asText());
        assertEquals("octocat", response.path("content").path("name").asText());
        assertEquals(true, response.path("content").path("remember").asBoolean());
        assertFalse(response.has("resultType"));
        assertFalse(roots.has("resultType"));
        assertEquals(
                temporary.toRealPath().toUri().toString(),
                roots.path("roots").get(0).path("uri").asText());
    }

    @Test
    void nestedSamplingCannotOpenARecursiveToolLoop() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        DefaultMcpInputResolver resolver = new DefaultMcpInputResolver(
                (request, announce) -> new UserInputGateway.Response("", true),
                request -> {
                    modelCalls.incrementAndGet();
                    return new ModelResponse("", "", List.of(), ModelUsage.ZERO);
                },
                json);
        var request = json.readTree("""
                {"method":"sampling/createMessage","params":{"messages":[],
                 "tools":[{"name":"recursive"}]}}
                """);

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("sampling", request, invocation()));
        assertEquals(0, modelCalls.get());
    }

    @Test
    void rejectsNestedSchemasAndUnknownRequiredFieldsBeforePrompting() throws Exception {
        AtomicInteger prompts = new AtomicInteger();
        DefaultMcpInputResolver resolver = new DefaultMcpInputResolver(
                (request, announce) -> {
                    prompts.incrementAndGet();
                    return new UserInputGateway.Response("", false);
                },
                request -> new ModelResponse("", "", List.of(), ModelUsage.ZERO),
                json);

        var nested = json.readTree("""
                {"method":"elicitation/create","params":{"mode":"form","message":"x",
                 "requestedSchema":{"type":"object","properties":{"config":{
                 "type":"object","properties":{"secret":{"type":"string"}}}}}}}
                """);
        var badRequired = json.readTree("""
                {"method":"elicitation/create","params":{"mode":"form","message":"x",
                 "requestedSchema":{"type":"object","properties":{"name":{
                 "type":"string"}},"required":["missing"]}}}
                """);

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("nested", nested, invocation()));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("required", badRequired, invocation()));
        assertEquals(0, prompts.get());
    }

    @Test
    void validatesMultiSelectValuesAndConsumesTheSharedSamplingBudget() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        DefaultMcpInputResolver resolver = new DefaultMcpInputResolver(
                (request, announce) -> new UserInputGateway.Response("[\"read\",\"write\"]", false),
                request -> {
                    modelCalls.incrementAndGet();
                    return new ModelResponse("sample", "", List.of(), ModelUsage.ZERO);
                },
                json);
        var form = json.readTree("""
                {"method":"elicitation/create","params":{"mode":"form","message":"x",
                 "requestedSchema":{"type":"object","properties":{"permissions":{
                 "type":"array","items":{"type":"string","enum":["read","write"]},
                 "minItems":1,"maxItems":2}},"required":["permissions"]}}}
                """);

        var result = resolver.resolve("form", form, invocation());
        assertEquals(
                List.of("read", "write"),
                json.convertValue(result.path("content").path("permissions"), List.class));

        var sampling = json.readTree("""
                {"method":"sampling/createMessage","params":{"messages":[]}}
                """);
        McpInvocation shared = invocation();
        var call = shared.context();
        McpInvocation bounded = new McpInvocation(
                new ToolExecutionContext(
                        call.thread(),
                        call.turn(),
                        call.call(),
                        call.config(),
                        new com.javaclaw.agent.runtime.TurnScope(
                                new java.util.concurrent.atomic.AtomicBoolean(),
                                new com.javaclaw.agent.runtime.BudgetAccount(1, 100_000),
                                Duration.ofSeconds(5))),
                shared.events());
        resolver.resolve("sample-1", sampling, bounded);
        assertThrows(IllegalStateException.class, () -> resolver.resolve("sample-2", sampling, bounded));
        assertEquals(1, modelCalls.get());
    }

    private McpInvocation invocation() {
        Instant now = Instant.now();
        ThreadId threadId = ThreadId.random();
        TurnId turnId = TurnId.random();
        SandboxPolicy policy = new SandboxPolicy(
                SandboxMode.READ_ONLY,
                Set.of(temporary),
                Set.of(),
                Set.of(),
                NetworkPolicy.disabled(),
                Set.of(),
                Duration.ofSeconds(2),
                SandboxPolicy.DEFAULT_OUTPUT_LIMIT);
        TurnConfig config = new TurnConfig(
                "model", "provider", "medium", temporary, policy, ApprovalPolicy.ON_RISK, Set.of(), Map.of());
        AgentThread thread = new AgentThread(
                threadId, "workspace", null, null, "", temporary, ThreadStatus.ACTIVE, 0, 0, 1, now, now);
        AgentTurn turn = new AgentTurn(
                turnId,
                threadId,
                AttemptId.random(),
                TurnStatus.IN_PROGRESS,
                List.of(new TurnInput.Text("test")),
                config,
                null,
                now,
                null);
        return new McpInvocation(
                new ToolExecutionContext(thread, turn, new ModelToolCall("call", "mcp", "{}"), config),
                ignored -> null);
    }
}
