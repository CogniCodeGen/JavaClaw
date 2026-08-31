package com.javaclaw.server.extension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.prompt.PromptHashes;
import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.agent.tool.FirstPartyToolProvider;
import com.javaclaw.agent.tool.GovernedToolRuntime;
import com.javaclaw.agent.tool.RegisteredTool;
import com.javaclaw.agent.tool.ToolAvailability;
import com.javaclaw.agent.tool.ToolHandler;
import com.javaclaw.agent.tool.ToolOrigin;
import com.javaclaw.agent.tool.ToolRisk;
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
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.server.persistence.H2Persistence;
import com.javaclaw.server.persistence.H2ToolAuthorizationRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolAuthorizationServiceTest {
    private static final String NAME = "mcp__mail__tool__send";
    private static final String SCHEMA = """
            {"type":"object","properties":{"to":{"type":"string"},"body":{"type":"string"},"bcc":{"type":"string"}},
            "required":["to","body","bcc"],"additionalProperties":false}
            """.strip();
    private static final String ARGS = "{\"to\":\"approved@example.test\",\"body\":\"report\",\"bcc\":\"\"}";
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void permissionPinsReceiverSourceSchemaAndScopeAndRejectsExtraRecipients() throws Exception {
        try (var persistence = new H2Persistence(temporary.resolve("data"))) {
            var workspace =
                    persistence.workspaces().create("scope", Files.createDirectory(temporary.resolve("work")), "ws");
            var service = service(persistence);
            var request = request(workspace.id().value(), 2);
            assertThrows(IllegalArgumentException.class, () -> service.put(request, false, "unconfirmed"));
            var grant = service.put(request, true, "approved");
            assertEquals(grant, service.put(request, true, "approved"));
            var tool = tool(workspace.root(), new AtomicInteger(), new AtomicBoolean(true));
            var context = context(workspace.id().value(), workspace.root(), "SCHEDULE", ARGS);
            assertTrue(service.consume(
                            context, tool, json.readTree(ARGS), context.config().sandboxPolicy(), "one")
                    .isPresent());
            assertTrue(service.consume(
                            context, tool, json.readTree(ARGS), context.config().sandboxPolicy(), "one")
                    .isPresent());
            assertEquals(1, service.list(workspace.id().value()).getFirst().consumedUses());
            for (String changed : List.of(
                    "{\"to\":\"attacker@example.test\",\"body\":\"report\",\"bcc\":\"\"}",
                    "{\"to\":\"approved@example.test\",\"body\":\"report\",\"bcc\":\"attacker@example.test\"}",
                    "{\"to\":\"approved@example.test\",\"body\":\"report\",\"bcc\":\"\",\"cc\":\"hidden\"}")) {
                assertTrue(service.consume(
                                context,
                                tool,
                                json.readTree(changed),
                                context.config().sandboxPolicy(),
                                changed)
                        .isEmpty());
            }
            var chat = context(workspace.id().value(), workspace.root(), "CHAT", ARGS);
            assertTrue(service.consume(
                            chat, tool, json.readTree(ARGS), chat.config().sandboxPolicy(), "chat")
                    .isEmpty());
            var host = new SandboxPolicy(
                    SandboxMode.HOST_FULL_ACCESS,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    new NetworkPolicy(NetworkPolicy.Mode.FULL, Set.of()),
                    Set.of(),
                    Duration.ofSeconds(30),
                    4096);
            assertTrue(service.consume(context, tool, json.readTree(ARGS), host, "host")
                    .isEmpty());
            service.disable(grant.id(), grant.revision(), "revoke");
            assertTrue(service.consume(
                            context, tool, json.readTree(ARGS), context.config().sandboxPolicy(), "after-revoke")
                    .isEmpty());
        }
    }

    @Test
    void concurrentConsumptionNeverExceedsTheDurableQuotaAndDoesNotResetOnUpdate() throws Exception {
        try (var persistence = new H2Persistence(temporary.resolve("data"));
                var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var workspace =
                    persistence.workspaces().create("quota", Files.createDirectory(temporary.resolve("work")), "ws");
            var service = service(persistence);
            var grant = service.put(request(workspace.id().value(), 3), true, "approved");
            var repository = new H2ToolAuthorizationRepository(persistence.database());
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 0; index < 32; index++) {
                String invocation = "concurrent-" + index;
                results.add(workers.submit(() -> repository
                        .consume(grant.id(), grant.revision(), invocation, PromptHashes.sha256(ARGS), Instant.now())
                        .isPresent()));
            }
            int accepted = 0;
            for (var result : results) {
                accepted += result.get(10, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0;
            }
            assertEquals(3, accepted);
            assertEquals(3, service.list(workspace.id().value()).getFirst().consumedUses());
            var changed = new ToolAuthorization(
                    grant.id(),
                    grant.workspaceId(),
                    grant.sourceId(),
                    grant.toolName(),
                    grant.sourceRevision(),
                    grant.schemaSha256(),
                    grant.argumentTemplate(),
                    grant.recipientField(),
                    grant.variableFields(),
                    5,
                    0,
                    grant.expiresAt(),
                    true,
                    grant.revision(),
                    Instant.now());
            assertEquals(3, service.put(changed, true, "increase").consumedUses());
            assertThrows(IllegalStateException.class, () -> service.disable(grant.id(), grant.revision(), "stale"));
        }
    }

    @Test
    void governedRecoveryDoesNotSendOrChargeTwiceAndSourceRevocationStillWins() throws Exception {
        try (var persistence = new H2Persistence(temporary.resolve("data"))) {
            var workspace =
                    persistence.workspaces().create("send", Files.createDirectory(temporary.resolve("work")), "ws");
            var service = service(persistence);
            service.put(request(workspace.id().value(), 1), true, "approved");
            var sent = new AtomicInteger();
            var enabled = new AtomicBoolean(true);
            var tool = tool(workspace.root(), sent, enabled);
            var call = context(workspace.id().value(), workspace.root(), "SCHEDULE", ARGS);
            var events = new ArrayList<ThreadItem>();
            try (var runtime = new GovernedToolRuntime(
                            List.of(new FirstPartyToolProvider("fixture", List.of(tool))),
                            List.of(),
                            List.of(),
                            null,
                            command -> {
                                throw new AssertionError("MCP fixture must not spawn a process");
                            },
                            call.config().sandboxPolicy(),
                            Duration.ofSeconds(1),
                            json,
                            service);
                    var session = runtime.open(
                            new TurnExecutionContext(
                                    call.thread(), call.turn(), List.of(), new AtomicBoolean(), TurnSteering.NONE),
                            item -> {
                                events.add(item);
                                return null;
                            })) {
                assertEquals(
                        "submitted", session.execute(call.call(), "send-once").modelContent());
                assertEquals(
                        "submitted", session.execute(call.call(), "send-once").modelContent());
                assertEquals(1, sent.get());
                assertEquals(1, service.list(workspace.id().value()).getFirst().consumedUses());
                assertTrue(session.execute(call.call(), "second-send")
                        .modelContent()
                        .contains("approval"));
                enabled.set(false);
                assertTrue(
                        session.execute(call.call(), "send-once").modelContent().contains("disabled"));
                assertEquals(1, sent.get());
            }
            assertEquals(
                    1,
                    events.stream()
                            .filter(ThreadItem.DynamicToolCall.class::isInstance)
                            .count());
            assertTrue(events.stream()
                    .filter(ThreadItem.EffectReceipt.class::isInstance)
                    .map(ThreadItem.EffectReceipt.class::cast)
                    .anyMatch(value -> value.state().equals("CONFIRMED")));
        }
    }

    private ToolAuthorizationService service(H2Persistence persistence) {
        var option = new ToolAuthorityOption(
                "mail", NAME, "test-only communication", 1, PromptHashes.sha256(SCHEMA), SCHEMA);
        return new ToolAuthorizationService(
                new H2ToolAuthorizationRepository(persistence.database()),
                persistence.workspaces(),
                ignored -> List.of(option),
                json);
    }

    private ToolAuthorization request(String workspace, int maximum) {
        return new ToolAuthorization(
                null,
                workspace,
                "mail",
                NAME,
                1,
                PromptHashes.sha256(SCHEMA),
                ARGS,
                "to",
                Set.of("body"),
                maximum,
                0,
                Instant.now().plusSeconds(600),
                true,
                0,
                null);
    }

    private RegisteredTool tool(Path root, AtomicInteger sent, AtomicBoolean enabled) {
        return new RegisteredTool(
                new ToolDescriptor(NAME, "test-only", SCHEMA),
                ToolOrigin.MCP,
                ToolRisk.HIGH,
                true,
                SandboxPolicy.readOnly(Set.of(root), Set.of()),
                new ToolAvailability() {
                    @Override
                    public void verify() {
                        if (!enabled.get()) {
                            throw new IllegalStateException("source disabled");
                        }
                    }

                    @Override
                    public Optional<Identity> identity() {
                        return Optional.of(new Identity("mail", 1));
                    }
                },
                ignored -> {
                    sent.incrementAndGet();
                    return new ToolHandler.Result(
                            new ThreadItem.McpToolCall("mail", "send", Map.of("status", "submitted")), "submitted");
                });
    }

    private ToolExecutionContext context(String workspace, Path root, String kind, String arguments) {
        Instant now = Instant.now();
        ThreadId threadId = ThreadId.random();
        var config = new TurnConfig(
                "fake",
                "fake",
                "medium",
                root,
                SandboxPolicy.readOnly(Set.of(root), Set.of()),
                ApprovalPolicy.NEVER,
                Set.of(),
                Map.of("profileKind", kind));
        var thread = new AgentThread(threadId, workspace, null, null, "", root, ThreadStatus.ACTIVE, 0, 0, 1, now, now);
        var turn = new AgentTurn(
                TurnId.random(),
                threadId,
                AttemptId.random(),
                TurnStatus.IN_PROGRESS,
                List.of(new TurnInput.Text("test")),
                config,
                null,
                now,
                null);
        return new ToolExecutionContext(thread, turn, new ModelToolCall("call", NAME, arguments), config);
    }
}
