package com.javaclaw.agent.tool;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.AttemptId;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ThreadStatus;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStatus;
import com.javaclaw.core.api.TurnSteering;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxResult;

import static com.javaclaw.agent.tool.ToolRuntimeTestSupport.execute;
import static com.javaclaw.agent.tool.ToolRuntimeTestSupport.providers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernedToolRuntimeTest {
    @TempDir
    Path temporary;

    @Test
    void planRejectsUnknownBusinessEffectsEvenWhenSandboxIsReadOnlyAndApprovalWouldPass() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        RegisteredTool external = new RegisteredTool(
                new ToolDescriptor(
                        "send_email", "low-risk external hint", "{\"type\":\"object\",\"additionalProperties\":false}"),
                ToolOrigin.MCP,
                ToolRisk.LOW,
                false,
                workspacePolicy(),
                ignored -> {
                    writes.incrementAndGet();
                    return new ToolHandler.Result(new ThreadItem.AgentMessage("sent"), "sent");
                });
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, external::readOnly);
        var existing = context("send_email", "{}", ApprovalPolicy.ALWAYS);
        TurnConfig config = new TurnConfig(
                "model",
                "provider",
                "medium",
                temporary,
                SandboxPolicy.readOnly(Set.of(temporary), Set.of()),
                ApprovalPolicy.ALWAYS,
                Set.of(),
                Map.of("profileKind", "PLAN"));
        var prior = existing.turn();
        var turn = new AgentTurn(
                prior.id(),
                prior.threadId(),
                prior.attemptId(),
                prior.status(),
                prior.input(),
                config,
                null,
                prior.startedAt(),
                null);
        try (var runtime = runtime(List.of(external), (request, announce) -> true, command -> success(), List.of());
                var session = runtime.open(
                        new TurnExecutionContext(
                                existing.thread(),
                                turn,
                                List.of(),
                                new java.util.concurrent.atomic.AtomicBoolean(),
                                TurnSteering.NONE),
                        ignored -> null)) {
            assertTrue(session.availableTools().isEmpty());
            assertTrue(session.execute(existing.call()).modelContent().contains("not registered"));
            assertEquals(0, writes.get());
        }
    }

    @Test
    void validatesSchemaBeforeApprovalOrExecution() {
        AtomicInteger executions = new AtomicInteger();
        try (GovernedToolRuntime runtime = runtime(
                List.of(commandTool()),
                (request, announce) -> true,
                command -> {
                    executions.incrementAndGet();
                    return success();
                },
                List.of())) {
            var result = execute(
                    runtime,
                    context("command", "{\"argv\":[\"echo\"],\"unexpected\":true}", ApprovalPolicy.ALWAYS),
                    item -> null);

            assertEquals(0, executions.get());
            assertInstanceOf(ThreadItem.DynamicToolCall.class, result.item());
            assertTrue(result.modelContent().contains("not allowed"));
        }
    }

    @Test
    void deniesRiskyCommandWhenApprovalIsRejected() {
        AtomicInteger executions = new AtomicInteger();
        try (GovernedToolRuntime runtime = runtime(
                List.of(commandTool()),
                (request, announce) -> false,
                command -> {
                    executions.incrementAndGet();
                    return success();
                },
                List.of())) {
            var result = execute(
                    runtime,
                    context("command", "{\"argv\":[\"echo\",\"hello\"]}", ApprovalPolicy.ON_RISK),
                    item -> null);

            assertEquals(0, executions.get());
            assertTrue(result.modelContent().contains("denied"));
        }
    }

    @Test
    void networkAuthorityIsSensitiveEvenForALowRiskTool() {
        AtomicInteger executions = new AtomicInteger();
        SandboxPolicy networkPolicy = new SandboxPolicy(
                SandboxMode.READ_ONLY,
                Set.of(temporary),
                Set.of(),
                Set.of(temporary.resolve(".git")),
                new NetworkPolicy(NetworkPolicy.Mode.FULL, Set.of()),
                Set.of(),
                Duration.ofSeconds(10),
                1024);
        RegisteredTool networkTool = new RegisteredTool(
                new ToolDescriptor("network", "", """
                {"type":"object","additionalProperties":false,"properties":{}}
                """),
                ToolOrigin.BUILTIN,
                ToolRisk.LOW,
                false,
                networkPolicy,
                context -> {
                    executions.incrementAndGet();
                    return new ToolHandler.Result(
                            new ThreadItem.DynamicToolCall("network", Map.of("status", "ok")), "ok");
                });
        try (GovernedToolRuntime runtime = new GovernedToolRuntime(
                providers(List.of(networkTool)),
                List.of(),
                List.of(),
                (request, announce) -> true,
                command -> success(),
                networkPolicy,
                Duration.ofSeconds(1),
                new ObjectMapper())) {
            var result = execute(runtime, context("network", "{}", ApprovalPolicy.NEVER, networkPolicy), item -> null);

            assertEquals(0, executions.get());
            assertTrue(result.modelContent().contains("approval is required"));
        }
    }

    @Test
    void preHookCanNarrowButCannotBroadenSandboxAuthority() {
        AtomicReference<SandboxMode> effective = new AtomicReference<>();
        RegisteredTool inspect = new RegisteredTool(
                new ToolDescriptor("inspect", "", """
                {"type":"object","additionalProperties":false,"properties":{}}
                """),
                ToolOrigin.BUILTIN,
                ToolRisk.LOW,
                false,
                workspacePolicy(),
                context -> {
                    effective.set(context.sandboxPolicy().mode());
                    return new ToolHandler.Result(
                            new ThreadItem.DynamicToolCall("inspect", Map.of("status", "ok")), "ok");
                });
        SandboxPolicy host = new SandboxPolicy(
                SandboxMode.HOST_FULL_ACCESS,
                Set.of(),
                Set.of(),
                Set.of(),
                new NetworkPolicy(NetworkPolicy.Mode.FULL, Set.of()),
                Set.of(),
                Duration.ofSeconds(10),
                1024);
        ToolHooks.PreToolHook wideningHook =
                invocation -> new ToolHooks.PreToolDecision(true, "", invocation.arguments(), host);
        try (GovernedToolRuntime runtime =
                runtime(List.of(inspect), (request, announce) -> true, command -> success(), List.of(wideningHook))) {
            execute(runtime, context("inspect", "{}", ApprovalPolicy.ON_RISK), item -> null);
            assertEquals(SandboxMode.WORKSPACE_WRITE, effective.get());
        }
    }

    @Test
    void approvedCommandUsesOnlyTheSandboxExecutor() {
        AtomicInteger executions = new AtomicInteger();
        try (GovernedToolRuntime runtime = runtime(
                List.of(commandTool()),
                (request, announce) -> {
                    announce.run();
                    return true;
                },
                command -> {
                    executions.incrementAndGet();
                    assertEquals(List.of("echo", "hello"), command.argv());
                    return success();
                },
                List.of())) {
            var result = execute(
                    runtime,
                    context("command", "{\"argv\":[\"echo\",\"hello\"]}", ApprovalPolicy.ON_RISK),
                    item -> null);

            assertEquals(1, executions.get());
            assertInstanceOf(ThreadItem.CommandExecution.class, result.item());
        }
    }

    @Test
    void pendingApprovalIsEmittedBeforeProtocolResponseCanResolveIt() throws Exception {
        PendingApprovalGateway gateway = new PendingApprovalGateway(Duration.ofSeconds(2), 4);
        CountDownLatch announced = new CountDownLatch(1);
        AtomicReference<ThreadItem.ApprovalRequest> request = new AtomicReference<>();
        AtomicInteger executions = new AtomicInteger();
        try (gateway;
                GovernedToolRuntime runtime = runtime(
                        List.of(commandTool()),
                        gateway,
                        command -> {
                            executions.incrementAndGet();
                            return success();
                        },
                        List.of());
                ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() ->
                    execute(runtime, context("command", "{\"argv\":[\"echo\"]}", ApprovalPolicy.ON_RISK), item -> {
                        if (item instanceof ThreadItem.ApprovalRequest approval) {
                            request.set(approval);
                            announced.countDown();
                        }
                        return null;
                    }));

            assertTrue(announced.await(1, TimeUnit.SECONDS));
            assertTrue(gateway.respond(request.get().approvalId(), true));
            assertInstanceOf(
                    ThreadItem.CommandExecution.class,
                    future.get(1, TimeUnit.SECONDS).item());
            assertEquals(1, executions.get());
        }
    }

    @Test
    void postHookFailureDoesNotChangeToolResultButEmitsAnErrorItem() throws Exception {
        RegisteredTool inspect = new RegisteredTool(
                new ToolDescriptor("inspect", "", """
                {"type":"object","additionalProperties":false,"properties":{}}
                """),
                ToolOrigin.BUILTIN,
                ToolRisk.LOW,
                false,
                workspacePolicy(),
                context -> new ToolHandler.Result(
                        new ThreadItem.DynamicToolCall("inspect", Map.of("status", "ok")), "ok"));
        CountDownLatch recorded = new CountDownLatch(1);
        AtomicReference<ThreadItem> diagnostic = new AtomicReference<>();
        try (GovernedToolRuntime runtime = new GovernedToolRuntime(
                providers(List.of(inspect)),
                List.of(),
                List.of((invocation, result) -> {
                    throw new IllegalStateException("audit unavailable");
                }),
                (request, announce) -> true,
                command -> success(),
                workspacePolicy(),
                Duration.ofSeconds(1),
                new ObjectMapper())) {
            var result = execute(runtime, context("inspect", "{}", ApprovalPolicy.ON_RISK), item -> {
                if (item instanceof ThreadItem.ErrorItem) {
                    diagnostic.set(item);
                    recorded.countDown();
                }
                return null;
            });

            assertEquals("ok", result.modelContent());
            assertTrue(recorded.await(1, TimeUnit.SECONDS));
            assertEquals("post_hook_failed", ((ThreadItem.ErrorItem) diagnostic.get()).code());
        }
    }

    @Test
    void confirmsEffectsOnceAndRefusesUnknownOutcomesWithoutResending() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        RegisteredTool effect = new RegisteredTool(
                new ToolDescriptor("send", "发送", "{\"type\":\"object\",\"additionalProperties\":false}"),
                ToolOrigin.BUILTIN,
                ToolRisk.LOW,
                false,
                workspacePolicy(),
                ignored -> {
                    int count = executions.incrementAndGet();
                    if (count == 2) {
                        throw new IllegalStateException("connection lost after submission");
                    }
                    return new ToolHandler.Result(
                            new ThreadItem.DynamicToolCall("send", Map.of("status", "submitted")), "submitted");
                });
        var call = context("send", "{}", ApprovalPolicy.ON_RISK);
        var receipts = new ArrayList<ThreadItem.EffectReceipt>();
        try (var runtime = runtime(List.of(effect), (request, announce) -> true, command -> success(), List.of());
                var session = runtime.open(
                        new TurnExecutionContext(
                                call.thread(),
                                call.turn(),
                                List.of(),
                                new java.util.concurrent.atomic.AtomicBoolean(),
                                TurnSteering.NONE),
                        item -> {
                            if (item instanceof ThreadItem.EffectReceipt receipt) {
                                receipts.add(receipt);
                            }
                            return null;
                        })) {
            assertEquals("submitted", session.execute(call.call(), "step-one").modelContent());
            assertEquals("submitted", session.execute(call.call(), "step-one").modelContent());
            assertEquals(1, executions.get());
            session.execute(call.call(), "step-two");
            assertTrue(session.execute(call.call(), "step-two").modelContent().contains("UNKNOWN"));
            assertEquals(2, executions.get());
            assertEquals(
                    List.of("PENDING", "CONFIRMED", "PENDING", "UNKNOWN"),
                    receipts.stream().map(ThreadItem.EffectReceipt::state).toList());
        }
    }

    @Test
    void keepsTheTurnCatalogStableButRevalidatesMutableProviderAuthority() throws Exception {
        AtomicInteger revision = new AtomicInteger(1);
        AtomicReference<Boolean> enabled = new AtomicReference<>(true);
        ToolProvider dynamic = new ToolProvider() {
            @Override
            public String id() {
                return "dynamic";
            }

            @Override
            public List<RegisteredTool> tools(TurnExecutionContext context) {
                int captured = revision.get();
                ToolAvailability availability = () -> {
                    if (!enabled.get() || revision.get() != captured) {
                        throw new IllegalStateException("provider revision was revoked");
                    }
                };
                return List.of(new RegisteredTool(
                        new ToolDescriptor(
                                "dynamic_tool",
                                "revision " + captured,
                                "{\"type\":\"object\",\"additionalProperties\":false}"),
                        ToolOrigin.MCP,
                        ToolRisk.LOW,
                        false,
                        workspacePolicy(),
                        availability,
                        ignored -> new ToolHandler.Result(
                                new ThreadItem.DynamicToolCall(
                                        "dynamic_tool", Map.of("revision", Integer.toString(captured))),
                                "ok")));
            }
        };
        try (GovernedToolRuntime runtime = new GovernedToolRuntime(
                List.of(dynamic),
                List.of(),
                List.of(),
                (request, announce) -> true,
                command -> success(),
                workspacePolicy(),
                Duration.ofSeconds(1),
                new ObjectMapper())) {
            ToolExecutionContext call = context("dynamic_tool", "{}", ApprovalPolicy.ON_RISK);
            try (TurnToolSession session = runtime.open(turn(call), ignored -> null)) {
                assertEquals("revision 1", session.availableTools().getFirst().description());
                revision.incrementAndGet();
                assertEquals("revision 1", session.availableTools().getFirst().description());

                var denied = session.execute(call.call());

                assertTrue(denied.modelContent().contains("revision was revoked"));
            }
            enabled.set(false);
        }
    }

    @Test
    void isolatesProviderDiscoveryFailureFromFirstPartyTools() throws Exception {
        RegisteredTool healthy = new RegisteredTool(
                new ToolDescriptor("healthy", "", """
                {"type":"object","additionalProperties":false}
                """),
                ToolOrigin.BUILTIN,
                ToolRisk.LOW,
                false,
                workspacePolicy(),
                ignored -> new ToolHandler.Result(
                        new ThreadItem.DynamicToolCall("healthy", Map.of("status", "ok")), "ok"));
        ToolProvider failing = new ToolProvider() {
            @Override
            public String id() {
                return "broken_external";
            }

            @Override
            public List<RegisteredTool> tools(TurnExecutionContext context) {
                throw new IllegalStateException("discovery unavailable");
            }
        };
        ArrayList<ThreadItem> emitted = new ArrayList<>();
        try (GovernedToolRuntime runtime = new GovernedToolRuntime(
                List.of(new FirstPartyToolProvider("builtin", List.of(healthy)), failing),
                List.of(),
                List.of(),
                (request, announce) -> true,
                command -> success(),
                workspacePolicy(),
                Duration.ofSeconds(1),
                new ObjectMapper())) {
            ToolExecutionContext call = context("healthy", "{}", ApprovalPolicy.ON_RISK);
            try (TurnToolSession session = runtime.open(turn(call), item -> {
                emitted.add(item);
                return null;
            })) {
                assertEquals(
                        List.of("healthy"),
                        session.availableTools().stream()
                                .map(ToolDescriptor::name)
                                .toList());
                assertEquals("ok", session.execute(call.call()).modelContent());
            }
        }
        assertEquals("tool_provider_unavailable", ((ThreadItem.ErrorItem) emitted.getFirst()).code());
    }

    private GovernedToolRuntime runtime(
            List<RegisteredTool> tools,
            ApprovalGateway approvals,
            com.javaclaw.sandbox.api.SandboxExecutor sandbox,
            List<ToolHooks.PreToolHook> hooks) {
        return new GovernedToolRuntime(
                providers(tools),
                hooks,
                List.of(),
                approvals,
                sandbox,
                workspacePolicy(),
                Duration.ofSeconds(1),
                new ObjectMapper());
    }

    private ToolExecutionContext context(String tool, String arguments, ApprovalPolicy approvals) {
        return context(tool, arguments, approvals, workspacePolicy());
    }

    private ToolExecutionContext context(
            String tool, String arguments, ApprovalPolicy approvals, SandboxPolicy policy) {
        Instant now = Instant.now();
        ThreadId threadId = ThreadId.random();
        TurnId turnId = TurnId.random();
        TurnConfig config =
                new TurnConfig("model", "provider", "medium", temporary, policy, approvals, Set.of(), Map.of());
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
        return new ToolExecutionContext(thread, turn, new ModelToolCall("call", tool, arguments), config);
    }

    private SandboxPolicy workspacePolicy() {
        return SandboxPolicy.workspaceWrite(Set.of(temporary), Set.of(temporary), Set.of(temporary.resolve(".git")));
    }

    private static TurnExecutionContext turn(ToolExecutionContext context) {
        return new TurnExecutionContext(
                context.thread(),
                context.turn(),
                List.of(),
                new java.util.concurrent.atomic.AtomicBoolean(),
                TurnSteering.NONE);
    }

    private RegisteredTool commandTool() {
        ToolDescriptor descriptor = new ToolDescriptor("command", "", """
                {
                  "type":"object",
                  "additionalProperties":false,
                  "required":["argv"],
                  "properties":{"argv":{"type":"array","minItems":1,
                    "items":{"type":"string","minLength":1}}}
                }
                """);
        return new RegisteredTool(descriptor, ToolOrigin.BUILTIN, ToolRisk.HIGH, false, workspacePolicy(), context -> {
            List<String> argv = new ArrayList<>();
            context.arguments().path("argv").forEach(value -> argv.add(value.asText()));
            SandboxResult result = context.sandbox()
                    .execute(new SandboxCommand("test-command", argv, temporary, Map.of(), context.sandboxPolicy()));
            return new ToolHandler.Result(
                    new ThreadItem.CommandExecution(
                            argv,
                            result.exitCode(),
                            result.stdout(),
                            result.stderr(),
                            result.timedOut(),
                            result.truncated()),
                    result.stdout());
        });
    }

    private static SandboxResult success() {
        return new SandboxResult(0, "ok", "", false, false, Duration.ofMillis(1), "fake");
    }
}
