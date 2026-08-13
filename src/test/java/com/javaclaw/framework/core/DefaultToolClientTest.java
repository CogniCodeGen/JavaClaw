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

class DefaultToolClientTest {

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
