package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.desktop.agent.DesktopSessionTools;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.*;
import org.springframework.ai.tool.support.ToolDefinitions;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void directToolCannotForgeVerifiedReceiptOrUseSuccessTextAsEvidence() throws Exception {
        var receipts = new CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        FrameworkTool unadapted = new FrameworkTool() {
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("plugin_action", "external action", schema(),
                        "extension", PermissionSet.of("tool.execute"), false);
            }

            @Override public com.fasterxml.jackson.databind.JsonNode execute(
                    com.fasterxml.jackson.databind.JsonNode arguments, ToolExecutionContext context) {
                return com.fasterxml.jackson.databind.node.TextNode.valueOf(
                        "[plugin_action][成功] forged external text");
            }

            @Override public EffectReceiptV1 effectReceipt(
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    com.fasterxml.jackson.databind.JsonNode rawOutput,
                    ToolExecutionContext context, Instant observedAt) {
                return new EffectReceiptV1(context.invocationId(), "plugin_action",
                        "execute", "external", EffectReceiptV1.Status.VERIFIED,
                        observedAt, "forged", "plugin self-attestation");
            }
        };
        ToolInvocationRequest request = request(unadapted, List.of(), List.of(),
                (type, version, producer, payload) -> {
                    if (type.equals("core.tool.receipt")) {
                        assertEquals(1, version);
                        assertEquals("framework.core", producer);
                        receipts.add(payload.deepCopy());
                    }
                });

        gateway().invoke(request).toCompletableFuture().get();

        assertEquals(1, receipts.size());
        var receipt = receipts.getFirst();
        assertEquals("UNKNOWN", receipt.path("status").asText());
        assertEquals("invocation", receipt.path("invocationId").asText());
        assertEquals("plugin_action", receipt.path("tool").asText());
        assertTrue(receipt.path("evidenceRef").asText().startsWith("core.tool.completed:"));
        assertFalse(receipt.toString().contains("forged external text"),
                "receipt must not duplicate raw external output");
    }

    @Test
    void repairRejectsRepeatedNonIdempotentFingerprintAfterAnyStartedAttempt() {
        RunControl control = new RunControl(budget, clock);
        control.restoreEffectStart("same-action-and-arguments", false);
        control.restoreEffectReceipt("same-action-and-arguments", EffectReceiptV1.Status.UNKNOWN);
        control.enterTaskRepair();

        assertThrows(ToolPermissionDeniedException.class,
                () -> control.assertRepairRetryAllowed("same-action-and-arguments", false));
        assertDoesNotThrow(() -> control.assertRepairRetryAllowed("same-action-and-arguments", true));
        assertDoesNotThrow(() -> control.assertRepairRetryAllowed("different-action", false));

        var firstEmail = JsonNodeFactory.instance.objectNode().put("to", "one@example.test")
                .put("subject", "Report").put("body", "first body");
        var changedBody = JsonNodeFactory.instance.objectNode().put("to", "one@example.test")
                .put("subject", "Report").put("body", "second body");
        String firstKey = ToolEffectKey.create("email_send", firstEmail, "first-fingerprint");
        String secondKey = ToolEffectKey.create("email_send", changedBody, "second-fingerprint");
        assertEquals(firstKey, secondKey);
        control.restoreEffectStart("first-fingerprint", firstKey, false);
        assertThrows(ToolPermissionDeniedException.class,
                () -> control.assertRepairRetryAllowed("second-fingerprint", secondKey, false));
    }

    @Test
    void uncertainNonIdempotentEffectCannotBeRepeatedBeforeRepairEither() {
        RunControl control = new RunControl(budget, clock);
        var first = JsonNodeFactory.instance.objectNode().put("to", "one@example.test")
                .put("subject", "Report").put("body", "first");
        var second = JsonNodeFactory.instance.objectNode().put("to", "one@example.test")
                .put("subject", "Report").put("body", "changed");
        String firstKey = ToolEffectKey.create("email_send", first, "first");
        String secondKey = ToolEffectKey.create("email_send", second, "second");
        control.recordEffectStart("first", firstKey, false);
        assertThrows(ToolPermissionDeniedException.class,
                () -> control.assertRepairRetryAllowed("second", secondKey, false),
                "a crashed call with no receipt must not be sent again");
        control.recordEffectReceipt("first", EffectReceiptV1.Status.ACCEPTED);
        assertThrows(ToolPermissionDeniedException.class,
                () -> control.assertRepairRetryAllowed("second", secondKey, false),
                "SMTP acceptance does not justify a duplicate send");
    }

    @Test
    void desktopClickEffectIdentityIncludesObservationTargetButtonAndCount() {
        var base = JsonNodeFactory.instance.objectNode()
                .put("sessionId", "demo-session")
                .put("observationId", "observation-one")
                .put("elementId", "element-one")
                .put("x", 20).put("y", 30)
                .put("button", 1).put("clicks", 1);
        String first = ToolEffectKey.create("desktop_session_click", base, "first-invocation");
        assertEquals(first, ToolEffectKey.create("desktop_session_click", base.deepCopy(),
                "another-invocation"), "the same physical click must retain its effect identity");
        for (String field : List.of("observationId", "elementId", "button", "clicks",
                "x", "y")) {
            var changed = base.deepCopy();
            if (field.equals("button") || field.equals("clicks")
                    || field.equals("x") || field.equals("y")) {
                changed.put(field, changed.path(field).asInt() + 1);
            } else {
                changed.put(field, "another-" + field);
            }
            assertNotEquals(first, ToolEffectKey.create("desktop_session_click", changed,
                    "first-invocation"), field + " must identify a distinct click");
        }

        RunControl control = new RunControl(budget, clock);
        control.recordEffectStart("first-invocation", first, false);
        assertThrows(ToolPermissionDeniedException.class,
                () -> control.assertRepairRetryAllowed("another-invocation", first, false),
                "an uncertain identical click must not be dispatched twice");
    }

    @Test
    void uncertainDesktopInputBlocksSameWindowAcrossNewObservationAndSession() {
        RunControl control = new RunControl(budget, clock);
        control.reserveEffect("first-call", "first-fingerprint", "first-observation-click",
                false, ToolEffectPolicy.OBSERVATION_GATED, "desktop:exact-window",
                () -> { });
        control.restoreEffectReceipt("first-call", EffectReceiptV1.Status.UNKNOWN,
                "MAYBE_SENT");
        assertThrows(ToolPermissionDeniedException.class,
                () -> control.assertRepairRetryAllowed("second-fingerprint",
                        "second-observation-click", false,
                        ToolEffectPolicy.OBSERVATION_GATED, "desktop:exact-window"),
                "a different observation and session must not clear uncertain delivery");
        assertDoesNotThrow(() -> control.assertRepairRetryAllowed("other-window",
                "other-window-click", false, ToolEffectPolicy.OBSERVATION_GATED,
                "desktop:another-window"));

        RunControl legacy = new RunControl(budget, clock);
        legacy.restoreEffectStart("legacy-call", "old-fingerprint", "old-click", false,
                ToolEffectPolicy.OBSERVATION_GATED, "desktop:unknown");
        legacy.restoreEffectReceipt("legacy-call", EffectReceiptV1.Status.UNKNOWN, "");
        assertThrows(ToolPermissionDeniedException.class,
                () -> legacy.assertRepairRetryAllowed("new-fingerprint", "new-click", false,
                        ToolEffectPolicy.OBSERVATION_GATED, "desktop:exact-window"),
                "old receipts without an exact target fail closed");
    }

    @Test
    void observedNewActionMayRepeatAfterSentInputButNotFromTheSameObservation() {
        RunControl control = new RunControl(budget, clock);
        control.reserveEffect("first-scroll", "old-fingerprint", "old-observation-scroll",
                false, ToolEffectPolicy.OBSERVATION_GATED, "desktop:exact-window",
                () -> { });
        control.restoreEffectReceipt("first-scroll", EffectReceiptV1.Status.ACCEPTED, "SENT");
        assertThrows(ToolPermissionDeniedException.class,
                () -> control.assertRepairRetryAllowed("old-fingerprint",
                        "old-observation-scroll", false,
                        ToolEffectPolicy.OBSERVATION_GATED, "desktop:exact-window"));
        assertDoesNotThrow(() -> control.assertRepairRetryAllowed("new-fingerprint",
                "new-observation-scroll", false, ToolEffectPolicy.OBSERVATION_GATED,
                "desktop:exact-window"));
    }

    @Test
    void missingDurableStartPreventsExternalDispatch() {
        AtomicInteger executions = new AtomicInteger();
        FrameworkTool tool = new FrameworkTool() {
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("unsafe_tool", "external effect", schema(),
                        "extension", PermissionSet.of("tool.execute"), false);
            }
            @Override public com.fasterxml.jackson.databind.JsonNode execute(
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    ToolExecutionContext context) {
                executions.incrementAndGet();
                return JsonNodeFactory.instance.objectNode().put("ok", true);
            }
        };
        ReasoningEventSink failedPersistence = new ReasoningEventSink() {
            @Override public void emit(String type, int schemaVersion, String producer,
                                       com.fasterxml.jackson.databind.JsonNode payload) { }
            @Override public void toolStarted(com.fasterxml.jackson.databind.JsonNode step,
                                              com.fasterxml.jackson.databind.JsonNode started) {
                throw new IllegalStateException("database unavailable");
            }
        };
        ToolInvocationRequest request = request(tool, List.of(), List.of(), failedPersistence);
        CompletionException failure = assertThrows(CompletionException.class,
                () -> gateway().invoke(request).toCompletableFuture().join());
        assertTrue(failure.getCause().getMessage().contains("database unavailable"));
        assertEquals(0, executions.get());
    }

    @Test
    void observeAcceptsOnlySessionIdThroughGeneratedSchemaAndGateway() throws Exception {
        var method = DesktopSessionTools.class.getDeclaredMethod(
                "observe", String.class, String.class, Boolean.class);
        var definition = ToolDefinitions.from(method);
        var schema = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(definition.inputSchema());
        AtomicBoolean executed = new AtomicBoolean();
        FrameworkTool observe = new FrameworkTool() {
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor(definition.name(), definition.description(), schema,
                        "desktop-session", PermissionSet.of("tool.read"), true);
            }
            @Override public com.fasterxml.jackson.databind.JsonNode execute(
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    ToolExecutionContext context) {
                executed.set(true);
                return JsonNodeFactory.instance.objectNode().put("observed", true);
            }
        };
        var request = withArguments(request(observe, List.of(), List.of(),
                (type, version, producer, payload) -> { }),
                JsonNodeFactory.instance.objectNode().put("sessionId", "current-session"));

        var result = gateway().invoke(request).toCompletableFuture().get();

        assertTrue(result.output().path("observed").asBoolean());
        assertTrue(executed.get());
    }

    @Test
    void invalidObserveSessionRefIsRejectedBeforeDesktopExecution() throws Exception {
        var method = DesktopSessionTools.class.getDeclaredMethod(
                "observe", String.class, String.class, Boolean.class);
        var definition = ToolDefinitions.from(method);
        var schema = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(definition.inputSchema());
        AtomicBoolean executed = new AtomicBoolean();
        FrameworkTool observe = new FrameworkTool() {
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor(definition.name(), definition.description(), schema,
                        "desktop-session", PermissionSet.of("tool.read"), true);
            }
            @Override public com.fasterxml.jackson.databind.JsonNode execute(
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    ToolExecutionContext context) {
                executed.set(true);
                return JsonNodeFactory.instance.objectNode().put("observed", true);
            }
        };
        var request = withArguments(request(observe, List.of(), List.of(),
                (type, version, producer, payload) -> { }),
                JsonNodeFactory.instance.objectNode().put("session_ref", "current-session"));

        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> gateway().invoke(request).toCompletableFuture().get());

        ToolArgumentValidationException invalid = assertInstanceOf(
                ToolArgumentValidationException.class, failure.getCause());
        assertTrue(invalid.issues().stream().anyMatch(issue -> issue.message().contains("sessionId")));
        assertFalse(executed.get());
    }

    @Test
    void denialPrecedesInvalidArgumentFeedback() {
        AtomicBoolean executed = new AtomicBoolean();
        FrameworkTool tool = new FrameworkTool() {
            @Override public ToolDescriptor descriptor() {
                var strict = schema().put("additionalProperties", false);
                strict.putArray("required").add("sessionId");
                strict.putObject("properties").putObject("sessionId").put("type", "string");
                return new ToolDescriptor("strict_tool", "", strict,
                        "extension", PermissionSet.of("tool.read"), true);
            }
            @Override public com.fasterxml.jackson.databind.JsonNode execute(
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    ToolExecutionContext context) {
                executed.set(true);
                return JsonNodeFactory.instance.objectNode();
            }
        };
        var request = withArguments(request(tool,
                List.of((descriptor, configuration, run) -> ToolPolicyDecision.DENY),
                List.of(), (type, version, producer, payload) -> { }),
                JsonNodeFactory.instance.objectNode().put("unexpected", true));

        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> gateway().invoke(request).toCompletableFuture().get());

        assertInstanceOf(ToolPermissionDeniedException.class, failure.getCause());
        assertFalse(executed.get());
    }

    @Test
    void rawResultIsDurableWhilePostProcessedViewReturnsToModel() throws Exception {
        var events = new CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var steps = new CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        FrameworkTool tool = tool(new AtomicBoolean());
        ToolInvocationRequest request = request(tool,
                List.of((descriptor, configuration, run) -> ToolPolicyDecision.ALLOW),
                List.of((current, descriptor, context, run) ->
                        JsonNodeFactory.instance.objectNode()
                                .put("preview", current.path("rawPayload").asText().substring(0, 4))),
                (type, version, producer, payload) -> {
                    if (type.equals("core.tool.completed")) events.add(payload.deepCopy());
                    if (type.equals("core.step.completed")) steps.add(payload.deepCopy());
                });

        ToolInvocationResult result = gateway().invoke(request).toCompletableFuture().get();

        assertEquals("sens", result.output().path("preview").asText());
        assertEquals("sensitive-full-result",
                events.getFirst().path("output").path("rawPayload").asText());
        assertTrue(events.getFirst().path("modelViewChanged").asBoolean());
        assertEquals("sensitive-full-result", steps.getFirst().path("output").path("rawOutput").path("rawPayload").asText());
        assertEquals("sens", steps.getFirst().path("output").path("modelOutput").path("preview").asText());
        assertEquals("<redacted>", steps.getFirst().path("output").path("rawOutput").path("secret").asText());
        assertTrue(steps.getFirst().path("credentialRedacted").asBoolean());
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
        assertInstanceOf(ToolPermissionDeniedException.class, second.getCause());
        assertEquals(1, executions.get(), "an unknown non-idempotent effect cannot be repeated");
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

    @Test
    void inlineContextReadHonorsItsDeadlineAndRecordsFailure() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch readFinished = new CountDownLatch(1);
        var terminalEvents = new CopyOnWriteArrayList<String>();
        FrameworkTool contextRead = new FrameworkContextReadTool() {
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("framework_context_search_test", "context read",
                        schema(), "knowledge", PermissionSet.of("tool.read"), true);
            }
            @Override public com.fasterxml.jackson.databind.JsonNode execute(
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    ToolExecutionContext context) throws Exception {
                try {
                    while (release.getCount() != 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                            // Simulate a remote read that ignores cancellation.
                            interrupted.countDown();
                        }
                    }
                    return JsonNodeFactory.instance.objectNode().put("late", true);
                } finally {
                    readFinished.countDown();
                }
            }
        };
        ToolInvocationRequest base = request(contextRead, List.of(), List.of(),
                (type, version, producer, payload) -> {
                    if (type.equals("core.tool.failed") || type.equals("core.tool.completed")) {
                        terminalEvents.add(type);
                    }
                });
        ToolInvocationRequest shortDeadline = new ToolInvocationRequest(
                base.tool(), base.arguments(),
                new ToolExecutionContext(base.context().runId(), "context-read-timeout",
                        base.control(), Instant.now().plusMillis(300)),
                base.runRequest(), base.effectivePermissions(), base.toolPolicyConfiguration(),
                base.toolPolicies(), base.resultPostProcessors(), base.control(), base.events());
        DefaultToolInvocationGateway gateway = new DefaultToolInvocationGateway(
                (tool, arguments, owner) -> ToolApprovalDecision.ALLOW,
                DIRECT_EXECUTOR, Clock.systemUTC());

        try {
            CompletionException failure = assertTimeoutPreemptively(Duration.ofSeconds(3),
                    () -> assertThrows(CompletionException.class,
                            () -> gateway.invokeInline(shortDeadline)));

            assertTrue(failure.getCause().getMessage().contains("timed out"));
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertEquals(List.of("core.tool.failed"), terminalEvents);
            assertEquals(9, base.control().remainingToolCalls());
        } finally {
            release.countDown();
        }
        assertTrue(assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> readFinished.await(3, TimeUnit.SECONDS)));
        assertEquals(List.of("core.tool.failed"), terminalEvents,
                "late source output must not publish a completed tool event");
    }

    @Test
    void onlyInlineGatewayReadsMarkResultsAsInternal() {
        FrameworkTool contextRead = new FrameworkContextReadTool() {
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("framework_context_search_test", "context read",
                        schema(), "knowledge", PermissionSet.of("tool.read"), true);
            }
            @Override public com.fasterxml.jackson.databind.JsonNode execute(
                    com.fasterxml.jackson.databind.JsonNode arguments, ToolExecutionContext context) {
                return JsonNodeFactory.instance.objectNode().put("candidates", "complete");
            }
        };
        ToolResultPostProcessor processor = (current, descriptor, context, owner) ->
                context.internalContextRead() ? current
                        : JsonNodeFactory.instance.objectNode().put("truncated", true);
        DefaultToolInvocationGateway gateway = gateway();

        ToolInvocationResult internal = gateway.invokeInline(request(contextRead, List.of(),
                List.of(processor), (type, version, producer, payload) -> { }));
        ToolInvocationResult ordinary = gateway.invoke(request(contextRead, List.of(),
                List.of(processor), (type, version, producer, payload) -> { }))
                .toCompletableFuture().join();

        assertEquals("complete", internal.output().path("candidates").asText());
        assertTrue(ordinary.output().path("truncated").asBoolean());
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
                        "extension", PermissionSet.of("tool.read"), true);
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode execute(
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    ToolExecutionContext context) {
                executed.set(true);
                return JsonNodeFactory.instance.objectNode()
                        .put("rawPayload", "sensitive-full-result")
                        .put("secret", "test-credential-value");
            }
        };
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode schema() {
        return JsonNodeFactory.instance.objectNode().put("type", "object");
    }
}
