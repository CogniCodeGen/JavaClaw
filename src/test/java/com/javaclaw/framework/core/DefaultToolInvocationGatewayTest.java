package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.builtin.context.BoundedToolResultProcessor;
import com.javaclaw.framework.spi.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DefaultToolInvocationGatewayTest {
    private static final CancellableTaskExecutor DIRECT_EXECUTOR = new CancellableTaskExecutor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public <T> CancellableTask<T> submit(
                String name, Duration timeout, CancellationToken cancellation, Callable<T> task) {
            CompletableFuture<T> completion = new CompletableFuture<>();
            try {
                cancellation.throwIfCancelled();
                completion.complete(task.call());
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
            CompletableFuture<Void> termination = CompletableFuture.completedFuture(null);
            return new CancellableTask<>() {
                @Override public java.util.concurrent.CompletionStage<T> completion() {
                    return completion;
                }
                @Override public java.util.concurrent.CompletionStage<Void> termination() {
                    return termination;
                }
                @Override public boolean cancel() { return false; }
            };
        }
    };

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final RunBudget budget = new RunBudget(
            Duration.ofMinutes(1), 1000, 1000, 10, BigDecimal.TEN);

    @Test
    void rawResultIsDurableWhilePostProcessedViewReturnsToModel() throws Exception {
        var events = new CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        FrameworkTool tool = tool(new AtomicBoolean());
        ToolInvocationRequest request = request(tool,
                List.of((descriptor, configuration, run) -> ToolPolicyDecision.ALLOW),
                List.of((current, descriptor, context, run) ->
                        JsonNodeFactory.instance.objectNode()
                                .put("preview", current.path("secret").asText().substring(0, 4))),
                (type, version, producer, payload) -> {
                    if (type.equals("core.tool.completed")) events.add(payload.deepCopy());
                });

        ToolInvocationResult result = gateway().invoke(request).toCompletableFuture().get();

        assertEquals("sens", result.output().path("preview").asText());
        assertEquals("sensitive-full-result",
                events.getFirst().path("output").path("secret").asText());
        assertTrue(events.getFirst().path("modelViewChanged").asBoolean());
    }

    @Test
    void resultBudgetEventIncludesConfiguredAndActualLimits() throws Exception {
        var events = new CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        FrameworkTool tool = tool(new AtomicBoolean());
        ToolInvocationRequest base = request(tool, List.of(),
                List.of(new BoundedToolResultProcessor()),
                (type, version, producer, payload) -> {
                    if (type.equals("core.tool.result.budget")) events.add(payload.deepCopy());
                });
        var configuration = JsonNodeFactory.instance.objectNode()
                .put("maxCharacters", 2_000)
                .put("largeMaxCharacters", 6_000);
        var capabilities = JsonNodeFactory.instance.objectNode()
                .set("tool.result-eviction", configuration);
        RunRequest configured = base.runRequest().withAttribute(
                "framework.compiledCapabilities", capabilities);
        ToolInvocationRequest request = new ToolInvocationRequest(
                base.tool(), base.arguments(), base.context(), configured,
                base.effectivePermissions(), base.toolPolicyConfiguration(),
                base.toolPolicies(), base.resultPostProcessors(), base.control(), base.events());

        gateway().invoke(request).toCompletableFuture().get();

        assertEquals(1, events.size());
        assertEquals("default", events.getFirst().path("resultClass").asText());
        assertEquals(2_000, events.getFirst().path("defaultLimitCharacters").asInt());
        assertEquals(6_000, events.getFirst().path("largeLimitCharacters").asInt());
        assertEquals(2_000, events.getFirst().path("actualLimitCharacters").asInt());
        assertEquals(events.getFirst().path("modelCharacters").asInt(),
                events.getFirst().path("runConsumedCharacters").asInt());
    }

    @Test
    void completedOutcomeRetriesTwiceWithoutRepeatingTheTool() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger completionAttempts = new AtomicInteger();
        FrameworkTool tool = new FrameworkTool() {
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("send_once", "", schema(), "extension",
                        PermissionSet.NONE, false);
            }

            @Override public com.fasterxml.jackson.databind.JsonNode execute(
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    ToolExecutionContext context) {
                executions.incrementAndGet();
                return JsonNodeFactory.instance.objectNode().put("sent", true);
            }
        };
        ToolInvocationRequest request = request(tool, List.of(), List.of(),
                (type, version, producer, payload) -> {
                    if (type.equals("core.tool.completed")
                            && completionAttempts.incrementAndGet() < 3) {
                        throw new IllegalStateException("transient event store failure");
                    }
                });

        ToolInvocationResult result = gateway().invoke(request).toCompletableFuture().get();

        assertTrue(result.output().path("sent").asBoolean());
        assertEquals(1, executions.get());
        assertEquals(3, completionAttempts.get());
    }

    @Test
    void exhaustedOutcomeCommitFailsWithoutRepeatingTheTool() {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger completionAttempts = new AtomicInteger();
        FrameworkTool tool = new FrameworkTool() {
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("send_once", "", schema(), "extension",
                        PermissionSet.NONE, false);
            }

            @Override public com.fasterxml.jackson.databind.JsonNode execute(
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    ToolExecutionContext context) {
                executions.incrementAndGet();
                return JsonNodeFactory.instance.objectNode().put("sent", true);
            }
        };
        ToolInvocationRequest request = request(tool, List.of(), List.of(),
                (type, version, producer, payload) -> {
                    if (type.equals("core.tool.completed")) {
                        completionAttempts.incrementAndGet();
                        throw new IllegalStateException("event store unavailable");
                    }
                });

        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> gateway().invoke(request).toCompletableFuture().get());

        ToolOutcomeCommitException outcome = assertInstanceOf(
                ToolOutcomeCommitException.class, failure.getCause());
        assertEquals("send_once", outcome.tool());
        assertEquals("invocation", outcome.invocationId());
        assertEquals(1, executions.get());
        assertEquals(3, completionAttempts.get());
    }

    @Test
    void cancellationAfterExecutionStillCommitsTheActualOutcomeFirst() {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<RunControl> control =
                new java.util.concurrent.atomic.AtomicReference<>();
        FrameworkTool tool = new FrameworkTool() {
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("send_once", "", schema(), "extension",
                        PermissionSet.NONE, false);
            }

            @Override public com.fasterxml.jackson.databind.JsonNode execute(
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    ToolExecutionContext context) {
                executions.incrementAndGet();
                control.get().cancel();
                return JsonNodeFactory.instance.objectNode().put("sent", true);
            }
        };
        ToolInvocationRequest request = request(tool, List.of(), List.of(),
                (type, version, producer, payload) -> {
                    if (type.equals("core.tool.completed")) {
                        completions.incrementAndGet();
                        assertTrue(payload.path("output").path("sent").asBoolean());
                    }
                });
        control.set(request.control());

        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> gateway().invoke(request).toCompletableFuture().get());

        assertInstanceOf(RunCancelledException.class, failure.getCause());
        assertEquals(1, executions.get());
        assertEquals(1, completions.get());
    }

    @Test
    void waitingInputOutcomeUsesTheSameMandatoryCommitRetries() {
        AtomicInteger completionAttempts = new AtomicInteger();
        FrameworkTool tool = new FrameworkTool() {
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("clarify", "", schema(), "agents",
                        PermissionSet.NONE, false);
            }

            @Override public com.fasterxml.jackson.databind.JsonNode execute(
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    ToolExecutionContext context) {
                throw new ToolInputRequiredException(
                        JsonNodeFactory.instance.objectNode().put("kind", "clarify_request"),
                        "need answer");
            }
        };
        ToolInvocationRequest request = request(tool, List.of(), List.of(),
                (type, version, producer, payload) -> {
                    if (type.equals("core.tool.completed")
                            && completionAttempts.incrementAndGet() < 3) {
                        throw new IllegalStateException("transient event store failure");
                    }
                });

        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> gateway().invoke(request).toCompletableFuture().get());

        assertInstanceOf(ToolInputRequiredException.class, failure.getCause());
        assertEquals(3, completionAttempts.get());
    }

    @Test
    void failedResultPostProcessingReturnsASafeViewWithoutRepeatingTheTool() throws Exception {
        AtomicBoolean executed = new AtomicBoolean();
        ToolInvocationRequest request = request(tool(executed), List.of(),
                List.of((current, descriptor, context, run) -> {
                    throw new IllegalStateException("processor unavailable");
                }), (type, version, producer, payload) -> { });

        ToolInvocationResult result = gateway().invoke(request).toCompletableFuture().get();

        assertTrue(executed.get());
        assertEquals("[tool result unavailable: post-processing failed]",
                result.output().asText());
    }

    @Test
    void toolPolicyDenialPrecedesExecutionAndCannotBeBypassedByPermission() {
        AtomicBoolean executed = new AtomicBoolean();
        FrameworkTool tool = tool(executed);
        ToolInvocationRequest request = request(tool,
                List.of((descriptor, configuration, run) -> ToolPolicyDecision.DENY),
                List.of(), (type, version, producer, payload) -> {});

        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> gateway().invoke(request).toCompletableFuture().get());

        assertInstanceOf(ToolPermissionDeniedException.class, failure.getCause());
        assertFalse(executed.get());
    }

    @Test
    void disallowedToolGroupIsRejectedBeforeExecution() {
        AtomicBoolean executed = new AtomicBoolean();
        FrameworkTool tool = tool(executed);
        ToolInvocationRequest base = request(tool, List.of(), List.of(),
                (type, version, producer, payload) -> { });
        var groups = JsonNodeFactory.instance.arrayNode().add("web");
        RunRequest restricted = base.runRequest().withAttribute(
                ToolGroupAccess.ATTRIBUTE, groups);
        ToolInvocationRequest request = new ToolInvocationRequest(
                base.tool(), base.arguments(), base.context(), restricted,
                base.effectivePermissions(), base.toolPolicyConfiguration(),
                base.toolPolicies(), base.resultPostProcessors(), base.control(), base.events());

        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> gateway().invoke(request).toCompletableFuture().get());

        assertInstanceOf(ToolPermissionDeniedException.class, failure.getCause());
        assertFalse(executed.get());
    }

    @Test
    void exactToolNameAndGroupMustBothBeAllowed() {
        AtomicBoolean executed = new AtomicBoolean();
        FrameworkTool tool = tool(executed);
        ToolInvocationRequest base = request(tool, List.of(), List.of(),
                (type, version, producer, payload) -> { });
        RunRequest wrongName = base.runRequest()
                .withAttribute(ToolGroupAccess.ATTRIBUTE,
                        JsonNodeFactory.instance.arrayNode().add("extension"))
                .withAttribute(ToolNameAccess.ATTRIBUTE,
                        JsonNodeFactory.instance.arrayNode().add("another_tool"));
        ToolInvocationRequest denied = new ToolInvocationRequest(
                base.tool(), base.arguments(), base.context(), wrongName,
                base.effectivePermissions(), base.toolPolicyConfiguration(),
                base.toolPolicies(), base.resultPostProcessors(), base.control(), base.events());

        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> gateway().invoke(denied).toCompletableFuture().get());
        assertInstanceOf(ToolPermissionDeniedException.class, failure.getCause());
        assertFalse(executed.get());

        RunRequest allowed = wrongName.withAttribute(ToolNameAccess.ATTRIBUTE,
                JsonNodeFactory.instance.arrayNode().add("contract_tool"));
        ToolInvocationRequest accepted = new ToolInvocationRequest(
                base.tool(), base.arguments(), base.context(), allowed,
                base.effectivePermissions(), base.toolPolicyConfiguration(),
                base.toolPolicies(), base.resultPostProcessors(), base.control(), base.events());
        assertDoesNotThrow(() -> gateway().invoke(accepted).toCompletableFuture().get());
        assertTrue(executed.get());
    }

    @Test
    void approvalGrantIsOneShotAndScopesOnlyTheRetriedInvocation() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        FrameworkTool tool = new FrameworkTool() {
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("delete", "delete", schema(), "system",
                        PermissionSet.of("tool.execute"), false);
            }
            @Override public com.fasterxml.jackson.databind.JsonNode execute(
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    ToolExecutionContext context) {
                executions.incrementAndGet();
                assertTrue(ToolApprovalScope.current().isPresent());
                return JsonNodeFactory.instance.objectNode().put("ok", true);
            }
        };
        ToolInvocationRequest request = request(tool, List.of(), List.of(),
                (type, version, producer, payload) -> { });
        DefaultToolInvocationGateway gateway = new DefaultToolInvocationGateway(
                (descriptor, arguments, owner) -> ToolApprovalDecision.REQUIRE_HUMAN_APPROVAL,
                DIRECT_EXECUTOR, clock);

        var challenge = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> gateway.invoke(request).toCompletableFuture().get());
        ToolApprovalRequiredException required = assertInstanceOf(
                ToolApprovalRequiredException.class, challenge.getCause());
        request.control().approveToolCall(ToolApprovalGrant.approve(
                required.challenge(), true));
        gateway.invoke(request).toCompletableFuture().get();

        assertEquals(1, executions.get());
        assertTrue(ToolApprovalScope.current().isEmpty());
        var second = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> gateway.invoke(request).toCompletableFuture().get());
        assertInstanceOf(ToolApprovalRequiredException.class, second.getCause());
    }

    @Test
    void approvalRequiresTheExactToolAndCanonicalArguments() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        FrameworkTool tool = new FrameworkTool() {
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("delete", "delete", schema(), "system",
                        PermissionSet.of("tool.execute"), false);
            }
            @Override public com.fasterxml.jackson.databind.JsonNode execute(
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    ToolExecutionContext context) {
                executions.incrementAndGet();
                return JsonNodeFactory.instance.objectNode().put("ok", true);
            }
        };
        var firstArguments = JsonNodeFactory.instance.objectNode().put("a", 1).put("b", 2);
        ToolInvocationRequest base = request(tool, List.of(), List.of(),
                (type, version, producer, payload) -> { });
        ToolInvocationRequest first = withArguments(base, firstArguments);
        DefaultToolInvocationGateway gateway = new DefaultToolInvocationGateway(
                (descriptor, arguments, owner) -> ToolApprovalDecision.REQUIRE_HUMAN_APPROVAL,
                DIRECT_EXECUTOR, clock);
        var challengeFailure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> gateway.invoke(first).toCompletableFuture().get());
        ToolApprovalRequiredException required = assertInstanceOf(
                ToolApprovalRequiredException.class, challengeFailure.getCause());

        base.control().approveToolCall(new ToolApprovalGrant(
                "another_tool", required.fingerprint(), true, true));
        var wrongTool = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> gateway.invoke(first).toCompletableFuture().get());
        assertInstanceOf(ToolApprovalRequiredException.class, wrongTool.getCause());

        base.control().approveToolCall(ToolApprovalGrant.approve(required.challenge(), true));
        var reordered = JsonNodeFactory.instance.objectNode().put("b", 2).put("a", 1);
        gateway.invoke(withArguments(base, reordered)).toCompletableFuture().get();
        assertEquals(1, executions.get());
    }

    @Test
    void waitingInputIsACompletedToolTerminalEventAndPropagatesTheControlSignal() {
        var terminalEvents = new CopyOnWriteArrayList<String>();
        var payloads = new CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        FrameworkTool tool = new FrameworkTool() {
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("clarify", "clarify", schema(), "agents",
                        PermissionSet.of("interaction.request"), false);
            }
            @Override public com.fasterxml.jackson.databind.JsonNode execute(
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    ToolExecutionContext context) {
                throw new ToolInputRequiredException(
                        JsonNodeFactory.instance.objectNode().put("kind", "clarify_request"),
                        "need answer");
            }
        };
        ToolInvocationRequest request = request(tool, List.of(), List.of(),
                (type, version, producer, payload) -> {
                    if (type.equals("core.tool.completed") || type.equals("core.tool.failed")) {
                        terminalEvents.add(type);
                        payloads.add(payload.deepCopy());
                    }
                });

        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> gateway().invoke(request).toCompletableFuture().get());

        assertInstanceOf(ToolInputRequiredException.class, failure.getCause());
        assertEquals(List.of("core.tool.completed"), terminalEvents);
        assertTrue(payloads.getFirst().path("waitingInput").asBoolean());
        assertEquals("clarify_request",
                payloads.getFirst().path("output").path("kind").asText());
    }

    private DefaultToolInvocationGateway gateway() {
        return new DefaultToolInvocationGateway(
                (tool, arguments, request) -> ToolApprovalDecision.ALLOW,
                DIRECT_EXECUTOR, clock);
    }

    private ToolInvocationRequest request(
            FrameworkTool tool,
            List<ToolPolicy> policies,
            List<ToolResultPostProcessor> processors,
            ReasoningEventSink events) {
        RunId runId = new RunId("tool-test");
        RunRequest owner = RunRequest.builder()
                .agent(AgentDefinitionRef.latest("test.agent"))
                .profile(RunProfileRef.latest("test.profile"))
                .source(InvocationSource.workflow("workflow-test"))
                .scope(new RunScope("workspace", "user", "session"))
                .input(InputBlock.text("tool"))
                .permissionCeiling(PermissionSet.UNRESTRICTED)
                .budget(budget)
                .build();
        RunControl control = new RunControl(budget, clock);
        return new ToolInvocationRequest(tool, JsonNodeFactory.instance.objectNode(),
                new ToolExecutionContext(runId, "invocation", control, control.deadline()),
                owner, PermissionSet.UNRESTRICTED, JsonNodeFactory.instance.objectNode(),
                policies, processors, control, events);
    }

    private static ToolInvocationRequest withArguments(
            ToolInvocationRequest request,
            com.fasterxml.jackson.databind.JsonNode arguments) {
        return new ToolInvocationRequest(request.tool(), arguments, request.context(),
                request.runRequest(), request.effectivePermissions(),
                request.toolPolicyConfiguration(), request.toolPolicies(),
                request.resultPostProcessors(), request.control(), request.events());
    }

    private static FrameworkTool tool(AtomicBoolean executed) {
        return new FrameworkTool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("contract_tool", "",
                        JsonNodeFactory.instance.objectNode().put("type", "object"),
                        PermissionSet.of("tool.read"), true);
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode execute(
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    ToolExecutionContext context) {
                executed.set(true);
                return JsonNodeFactory.instance.objectNode()
                        .put("secret", "sensitive-full-result");
            }
        };
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode schema() {
        return JsonNodeFactory.instance.objectNode().put("type", "object");
    }
}
