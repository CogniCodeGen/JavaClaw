package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.extension.ExtensionArtifact;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DefaultToolClientTest {

    @Test
    void persistedNodeInvocationReplaysCompletedResultAndBlocksUnknownSideEffects() throws Exception {
        Clock clock = Clock.systemUTC();
        var json = new ObjectMapper().findAndRegisterModules();
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:tool-replay-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new com.javaclaw.platform.data.SchemaInitializer(source).initialize();
        var runs = new com.javaclaw.framework.store.JdbcRunStore(new org.springframework.jdbc.core.JdbcTemplate(source),
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source), json, clock);
        RunId owner = RunId.random();
        RunScope scope = new RunScope("workspace", "workflow", "thread");
        RunRequest request = RunRequest.builder().agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest("chat")).source(InvocationSource.workflow("graph"))
                .scope(scope).input(InputBlock.text("execute node")).permissionCeiling(PermissionSet.UNRESTRICTED).build();
        var empty = JsonNodeFactory.instance.objectNode();
        runs.create(owner, request, "plan", new RunEventDraft("core.run.created", 1, "test", null, null, empty));
        runs.append(owner, Set.of(RunState.CREATED), RunState.RUNNING,
                new RunEventDraft("core.run.started", 1, "test", null, null, empty), null, null);
        var events = StepEvents.durableSink(runs, owner);
        String invocation = "graph:node:save:visit:1";
        var arguments = JsonNodeFactory.instance.objectNode().put("value", 1);
        var input = JsonNodeFactory.instance.objectNode().put("tool", "save").put("invocationId", invocation);
        input.set("arguments", arguments);
        StepId first = StepId.tool(owner, invocation);
        StepEvents.started(events, first, AgentStep.Kind.TOOL, input, null);
        var output = JsonNodeFactory.instance.objectNode().put("durationMillis", 3);
        output.set("rawOutput", JsonNodeFactory.instance.objectNode().put("secret", "raw"));
        output.set("modelOutput", JsonNodeFactory.instance.objectNode().put("summary", "saved"));
        StepEvents.completed(events, first, output, null);
        var extensions = new ExtensionManager(new ExtensionContext(clock, Runnable::run,
                ignored -> CompletableFuture.failedFuture(new AssertionError("no model task"))));
        try {
            AgentDefinitionResolver definitions = new AgentDefinitionResolver() {
                @Override public AgentDefinition resolveAgent(String workspace, AgentDefinitionRef ref) {
                    throw new AssertionError("replay must not compile or execute a new plan");
                }
                @Override public RunProfile resolveProfile(String workspace, RunProfileRef ref) {
                    throw new AssertionError("replay must not compile a profile");
                }
            };
            DefaultToolClient client = new DefaultToolClient(ignored -> {
                throw new AssertionError("completed or ambiguous side effects must not execute again");
            }, new AgentCompiler(definitions, extensions, json), clock,
                    (challenge, run) -> CompletableFuture.failedFuture(new AssertionError("no approval")), runs, null);
            ToolCallRequest call = new ToolCallRequest(scope, InvocationSource.workflow("graph"), "save", arguments,
                    PermissionSet.UNRESTRICTED, RunBudget.UNBOUNDED, "graph", () -> false, Set.of("test"), owner, invocation);
            var result = client.invoke(call).toCompletableFuture().get();
            assertEquals("saved", result.output().path("summary").asText());
            String unknown = "graph:node:save:visit:2";
            StepEvents.started(events, StepId.tool(owner, unknown), AgentStep.Kind.TOOL, input, null);
            var next = new ToolCallRequest(scope, call.source(), "save", arguments, call.permissionCeiling(),
                    call.budget(), "graph", () -> false, Set.of("test"), owner, unknown);
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> client.invoke(next).toCompletableFuture().get());
            assertInstanceOf(ToolRecoveryRequiredException.class, failure.getCause());
        } finally {
            extensions.close();
        }
    }

    @Test
    void cancellingPublishedStageCannotSkipRunResourceCleanup() {
        AtomicBoolean toolClosed = new AtomicBoolean();
        CompletableFuture<ToolInvocationResult> invocation = new CompletableFuture<>();
        ToolInvocationGateway gateway = ignored -> invocation;
        Clock clock = Clock.systemUTC();
        ExtensionManager extensions = new ExtensionManager(new ExtensionContext(
                clock, Runnable::run, request -> CompletableFuture.failedFuture(
                new AssertionError("model task not expected"))));
        try {
            extensions.publish(List.of(ExtensionArtifact.builtin(
                    new ClosingToolExtension(toolClosed))));
            AgentDefinitionResolver definitions = new AgentDefinitionResolver() {
                @Override
                public AgentDefinition resolveAgent(
                        String workspaceId, AgentDefinitionRef reference) {
                    return new AgentDefinition(
                            "system.default", 1, "System", "test:model", Map.of(),
                            Map.of(), JsonNodeFactory.instance.objectNode(),
                            JsonNodeFactory.instance.objectNode(), RunBudget.UNBOUNDED,
                            JsonNodeFactory.instance.objectNode(),
                            Map.of("test.closing-tool", "=1.0.0"), "agent-checksum");
                }

                @Override
                public RunProfile resolveProfile(
                        String workspaceId, RunProfileRef reference) {
                    return new RunProfile(
                            "chat", 1, "Chat", PermissionSet.UNRESTRICTED,
                            RunBudget.UNBOUNDED, Map.of(),
                            JsonNodeFactory.instance.objectNode(), "profile-checksum");
                }
            };
            DefaultToolClient client = new DefaultToolClient(
                    gateway, new AgentCompiler(definitions, extensions,
                    new ObjectMapper().findAndRegisterModules()), clock);
            ToolCallRequest request = new ToolCallRequest(
                    new RunScope("workspace", "user", "session"),
                    InvocationSource.workflow("workflow"), "closing_tool",
                    JsonNodeFactory.instance.objectNode(), PermissionSet.UNRESTRICTED,
                    RunBudget.UNBOUNDED, "correlation", () -> false, Set.of("test"));

            CompletableFuture<?> published = client.invoke(request).toCompletableFuture();
            assertTrue(published.cancel(true));
            assertFalse(toolClosed.get());

            invocation.complete(new ToolInvocationResult(
                    JsonNodeFactory.instance.objectNode().put("ok", true), Duration.ZERO));

            assertTrue(toolClosed.get());
        } finally {
            extensions.close();
        }
    }

    private static final class ClosingToolExtension implements AgentFrameworkExtension {
        private final AtomicBoolean closed;

        private ClosingToolExtension(AtomicBoolean closed) {
            this.closed = closed;
        }

        @Override
        public ExtensionDescriptor descriptor() {
            return new ExtensionDescriptor(
                    "test.closing-tool", SemanticVersion.parse("1.0.0"),
                    ">=2.0.0 <3.0.0", ">=2.0.0 <3.0.0", List.of(), Set.of(),
                    ExtensionScope.PLAN_SCOPED, HotUpdateCompatibility.PLAN_ISOLATED,
                    1, Map.of());
        }

        @Override
        public void register(ExtensionRegistrar registrar) {
            registrar.tool(context -> new FrameworkTool() {
                @Override
                public ToolDescriptor descriptor() {
                    return new ToolDescriptor(
                            "closing_tool", "test cleanup",
                            JsonNodeFactory.instance.objectNode().put("type", "object"),
                            "test", PermissionSet.of("tool.read"), true);
                }

                @Override
                public com.fasterxml.jackson.databind.JsonNode execute(
                        com.fasterxml.jackson.databind.JsonNode arguments,
                        ToolExecutionContext context) {
                    throw new AssertionError("gateway controls this test invocation");
                }

                @Override
                public void close() {
                    closed.set(true);
                }
            });
        }
    }
}
