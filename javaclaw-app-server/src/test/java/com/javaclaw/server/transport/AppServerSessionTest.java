package com.javaclaw.server.transport;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.automation.AutomationRuntime;
import com.javaclaw.agent.conversation.ProfileRepository;
import com.javaclaw.agent.conversation.ProfileService;
import com.javaclaw.agent.kernel.AgentKernel;
import com.javaclaw.agent.runtime.DefaultAgentRuntime;
import com.javaclaw.agent.runtime.RuntimeEventBus;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.RpcMethods;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPaths;
import com.javaclaw.server.bootstrap.ServerComponentGraph;
import com.javaclaw.server.configuration.ServerConfiguration;
import com.javaclaw.server.diagnostics.DiagnosticsService;
import com.javaclaw.server.discovery.ServerDiscovery;
import com.javaclaw.server.persistence.H2AutomationRepository;
import com.javaclaw.server.persistence.H2DiagnosticsRepository;
import com.javaclaw.server.persistence.H2Persistence;
import com.javaclaw.server.persistence.H2ProfileRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppServerSessionTest {
    @TempDir
    Path temporary;

    @Test
    void requiresHandshakeAndExposesOneThreadTurnItemTranscript() throws Exception {
        RuntimeEventBus events = new RuntimeEventBus(32);
        AgentKernel kernel = (context, sink) -> {
            sink.usage(new ModelUsage(12, 4, 1));
            sink.append(new ThreadItem.AgentMessage("answer"));
        };
        try (DefaultAgentRuntime service =
                new DefaultAgentRuntime(new H2Persistence(temporary.resolve("data-v4")).runtime(), kernel, events)) {
            JsonRpcCodec codec = new JsonRpcCodec();
            List<JsonRpcNotification> notifications = new CopyOnWriteArrayList<>();
            try (AppServerSession session = session(service, events, codec.mapper(), notifications::add)) {
                JsonRpcResponse rejected =
                        send(session, 1, RpcMethods.THREAD_LIST, JsonNodeFactory.instance.objectNode());
                assertEquals(-32001, rejected.error().code());

                InitializeParams initialize = new InitializeParams(
                        ProtocolVersion.CURRENT, new InitializeParams.ClientInfo("test", "test", "1"), Map.of());
                JsonRpcResponse initialized =
                        send(session, 2, RpcMethods.INITIALIZE, codec.mapper().valueToTree(initialize));
                assertEquals(
                        ProtocolVersion.CURRENT,
                        initialized.result().path("protocolVersion").asInt());

                ObjectNode createWorkspace = JsonNodeFactory.instance.objectNode();
                createWorkspace.put("name", "workspace");
                createWorkspace.put("root", temporary.toString());
                createWorkspace.put("idempotencyKey", "workspace-test");
                String workspaceId = send(session, 3, RpcMethods.WORKSPACE_CREATE, createWorkspace)
                        .result()
                        .path("id")
                        .asText();

                ObjectNode start = JsonNodeFactory.instance.objectNode();
                start.put("workspaceId", workspaceId);
                start.put("title", "hello");
                JsonRpcResponse thread = send(session, 4, RpcMethods.THREAD_START, start);
                String threadId = thread.result().path("id").asText();
                assertFalse(threadId.isBlank());

                ObjectNode turn = JsonNodeFactory.instance.objectNode();
                turn.put("threadId", threadId);
                ObjectNode input = turn.putArray("input").addObject();
                input.put("type", "text");
                input.put("text", "question");
                ObjectNode config = turn.putObject("config");
                config.put("provider", "test");
                config.put("model", "fake");
                JsonRpcResponse accepted = send(session, 5, RpcMethods.TURN_START, turn);
                assertFalse(accepted.result().path("id").asText().isBlank());

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                JsonRpcResponse snapshot;
                do {
                    snapshot = send(session, 6, RpcMethods.THREAD_READ, threadParams(threadId));
                    if (snapshot.result()
                            .path("turns")
                            .get(0)
                            .path("status")
                            .asText()
                            .equals("COMPLETED")) {
                        break;
                    }
                    Thread.sleep(10);
                } while (System.nanoTime() < deadline);

                assertEquals(
                        List.of("userMessage", "agentMessage"),
                        snapshot.result().path("items").findValues("kind").stream()
                                .map(JsonNode::asText)
                                .toList());
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (notifications.stream().noneMatch(value -> RpcMethods.TURN_COMPLETED.equals(value.method()))
                        && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertTrue(notifications.stream().anyMatch(value -> RpcMethods.ITEM_COMPLETED.equals(value.method())));
                assertTrue(notifications.stream().anyMatch(value -> RpcMethods.TURN_COMPLETED.equals(value.method())));
                assertTrue(notifications.stream().anyMatch(value -> RpcMethods.USAGE_UPDATED.equals(value.method())));

                JsonNode execution = send(session, 7, RpcMethods.THREAD_EXECUTION_SUMMARY, threadParams(threadId))
                        .result();
                assertEquals(threadId, execution.path("threadId").asText());
                assertEquals(
                        "test", execution.path("turns").get(0).path("provider").asText());
                assertEquals(
                        "fake", execution.path("turns").get(0).path("model").asText());
                assertEquals(
                        12, execution.path("turns").get(0).path("inputTokens").asLong());
                assertFalse(execution.toString().contains("secret"));
                assertFalse(execution.toString().contains("reasoningContent"));

                JsonRpcResponse missing = send(session, 8, "unknown/method", JsonNodeFactory.instance.objectNode());
                assertEquals(-32601, missing.error().code());
            }
        }
    }

    @Test
    void routesApprovalResponsesOnlyWhenCapabilityIsInstalled() {
        RuntimeEventBus events = new RuntimeEventBus(8);
        AtomicReference<String> resolved = new AtomicReference<>();
        try (DefaultAgentRuntime service = new DefaultAgentRuntime(
                        new H2Persistence(temporary.resolve("approval-data")).runtime(),
                        (context, sink) -> {},
                        events);
                AppServerSession session =
                        session(service, events, new JsonRpcCodec().mapper(), ignored -> {}, (id, approved) -> {
                            resolved.set(id + ":" + approved);
                            return true;
                        })) {
            JsonRpcCodec codec = new JsonRpcCodec();
            InitializeParams initialize = new InitializeParams(
                    ProtocolVersion.CURRENT, new InitializeParams.ClientInfo("test", "test", "1"), Map.of());
            assertTrue(send(session, 1, RpcMethods.INITIALIZE, codec.mapper().valueToTree(initialize))
                    .result()
                    .path("capabilities")
                    .path("approvals")
                    .asBoolean());
            ObjectNode params = JsonNodeFactory.instance.objectNode();
            params.put("approvalId", "approval_1");
            params.put("approved", true);

            assertTrue(send(session, 2, RpcMethods.APPROVAL_RESPOND, params)
                    .result()
                    .path("accepted")
                    .asBoolean());
            assertEquals("approval_1:true", resolved.get());
        }
    }

    @Test
    void retriesATerminalTurnInANewBranchWithoutMutatingTheSource() throws Exception {
        RuntimeEventBus events = new RuntimeEventBus(32);
        JsonRpcCodec codec = new JsonRpcCodec();
        try (H2Persistence store = new H2Persistence(temporary.resolve("retry-branch-data"));
                DefaultAgentRuntime service = new DefaultAgentRuntime(
                        store.runtime(),
                        (context, sink) -> sink.append(new ThreadItem.AgentMessage("answer")),
                        events)) {
            ProfileService profiles = new ProfileService(new H2ProfileRepository(store.database()), java.util.Set.of());
            putTestProfile(profiles, "profile_chat", com.javaclaw.core.api.ProfileKind.CHAT);
            var workspace = service.createWorkspace("Retry", temporary, "retry-workspace");
            try (AppServerSession session = session(
                    service,
                    events,
                    codec.mapper(),
                    ignored -> {},
                    null,
                    null,
                    ServerDiscovery.EMPTY,
                    false,
                    ServerConfiguration.inMemory(codec.mapper()),
                    store.attachments(),
                    service.liveItemEvents(),
                    ServerUseCases.profiles(profiles))) {
                initialize(session, codec);
                ObjectNode start = JsonNodeFactory.instance.objectNode();
                start.put("workspaceId", workspace.id().value());
                start.put("title", "source");
                start.put("idempotencyKey", "retry-source");
                String sourceId = send(session, 2, RpcMethods.THREAD_START, start)
                        .result()
                        .path("id")
                        .asText();

                ObjectNode turn = profileTurn(sourceId, "original");
                turn.put("idempotencyKey", "retry-original-turn");
                String targetTurnId = send(session, 3, RpcMethods.TURN_START, turn)
                        .result()
                        .path("id")
                        .asText();
                awaitTerminal(service, targetTurnId);
                JsonNode sourceBefore = send(session, 4, RpcMethods.THREAD_READ, threadParams(sourceId))
                        .result();

                ObjectNode retry = JsonNodeFactory.instance.objectNode();
                retry.put("threadId", sourceId);
                retry.put("targetTurnId", targetTurnId);
                retry.put("profileId", "profile_chat");
                retry.put("replaceInput", true);
                retry.put("replacementText", "revised");
                retry.put("idempotencyKey", "retry-new-branch");
                retry.putObject("config").put("approvalPolicy", "NEVER");
                JsonNode started = send(session, 5, RpcMethods.THREAD_RETRY_IN_NEW_BRANCH, retry)
                        .result();
                String branchId = started.path("thread").path("id").asText();
                String retriedTurnId = started.path("turn").path("id").asText();

                assertFalse(branchId.isBlank());
                assertFalse(sourceId.equals(branchId));
                assertFalse(targetTurnId.equals(retriedTurnId));
                awaitTerminal(service, retriedTurnId);
                assertEquals(
                        sourceBefore,
                        send(session, 6, RpcMethods.THREAD_READ, threadParams(sourceId))
                                .result());
                JsonNode branch = send(session, 7, RpcMethods.THREAD_READ, threadParams(branchId))
                        .result();
                assertEquals(1, branch.path("turns").size());
                assertEquals(
                        "revised",
                        branch.path("items").findValues("text").stream()
                                .map(JsonNode::asText)
                                .filter("revised"::equals)
                                .findFirst()
                                .orElseThrow());
                assertEquals(
                        branchId,
                        send(session, 8, RpcMethods.THREAD_RETRY_IN_NEW_BRANCH, retry)
                                .result()
                                .path("thread")
                                .path("id")
                                .asText());
            }
        }
    }

    @Test
    void routesUserInputResponsesOnlyWhenCapabilityIsInstalled() {
        RuntimeEventBus events = new RuntimeEventBus(8);
        AtomicReference<String> resolved = new AtomicReference<>();
        try (DefaultAgentRuntime service = new DefaultAgentRuntime(
                        new H2Persistence(temporary.resolve("user-input-data")).runtime(),
                        (context, sink) -> {},
                        events);
                AppServerSession session = session(
                        service,
                        events,
                        new JsonRpcCodec().mapper(),
                        ignored -> {},
                        null,
                        (id, value, cancelled) -> {
                            resolved.set(id + ":" + value + ":" + cancelled);
                            return true;
                        },
                        ServerDiscovery.EMPTY,
                        false)) {
            JsonRpcCodec codec = new JsonRpcCodec();
            InitializeParams initialize = new InitializeParams(
                    ProtocolVersion.CURRENT, new InitializeParams.ClientInfo("test", "test", "1"), Map.of());
            assertTrue(send(session, 1, RpcMethods.INITIALIZE, codec.mapper().valueToTree(initialize))
                    .result()
                    .path("capabilities")
                    .path("userInput")
                    .asBoolean());
            ObjectNode params = JsonNodeFactory.instance.objectNode();
            params.put("requestId", "input_1");
            params.put("value", "yes");
            params.put("cancelled", false);

            assertTrue(send(session, 2, RpcMethods.USER_INPUT_RESPOND, params)
                    .result()
                    .path("accepted")
                    .asBoolean());
            assertEquals("input_1:yes:false", resolved.get());
        }
    }

    @Test
    void resumeReturnsDurableReplayWithoutDuplicatingItAsNotifications() {
        RuntimeEventBus events = new RuntimeEventBus(8);
        try (DefaultAgentRuntime service = new DefaultAgentRuntime(
                new H2Persistence(temporary.resolve("resume-data")).runtime(), (context, sink) -> {}, events)) {
            var workspace = service.createWorkspace("workspace", temporary, "resume-workspace");
            var thread = service.startThread(workspace.id(), "resume");
            List<JsonRpcNotification> notifications = new CopyOnWriteArrayList<>();
            JsonRpcCodec codec = new JsonRpcCodec();
            try (AppServerSession session = session(service, events, codec.mapper(), notifications::add)) {
                initialize(session, codec);
                ObjectNode params = threadParams(thread.id().value());
                params.put("afterSequence", 0);

                JsonRpcResponse response = send(session, 2, RpcMethods.THREAD_RESUME, params);

                assertNotNull(response.result().path("snapshot").path("thread").path("id"));
                assertEquals(1, response.result().path("events").size());
                assertTrue(notifications.isEmpty(), "replayed events belong only in the resume response");
            }
        }
    }

    @Test
    void configurationIsSharedAcrossConnectionsAndBounded() {
        RuntimeEventBus events = new RuntimeEventBus(8);
        JsonRpcCodec codec = new JsonRpcCodec();
        ServerConfiguration configuration = ServerConfiguration.inMemory(codec.mapper());
        try (DefaultAgentRuntime service = new DefaultAgentRuntime(
                        new H2Persistence(temporary.resolve("configuration-data")).runtime(),
                        (context, sink) -> {},
                        events);
                AppServerSession writer = session(
                        service,
                        events,
                        codec.mapper(),
                        ignored -> {},
                        null,
                        null,
                        ServerDiscovery.EMPTY,
                        false,
                        configuration);
                AppServerSession reader = session(
                        service,
                        events,
                        codec.mapper(),
                        ignored -> {},
                        null,
                        null,
                        ServerDiscovery.EMPTY,
                        false,
                        configuration)) {
            initialize(writer, codec);
            initialize(reader, codec);
            ObjectNode patch = JsonNodeFactory.instance.objectNode();
            patch.put("theme", "dark");
            patch.putObject("limits").put("turns", 8);

            assertEquals(
                    "dark",
                    send(writer, 2, RpcMethods.CONFIG_UPDATE, patch)
                            .result()
                            .path("theme")
                            .asText());
            assertEquals(
                    8,
                    send(reader, 2, RpcMethods.CONFIG_READ, JsonNodeFactory.instance.objectNode())
                            .result()
                            .path("limits")
                            .path("turns")
                            .asInt());

            ObjectNode removal = JsonNodeFactory.instance.objectNode();
            removal.putNull("theme");
            assertFalse(
                    send(reader, 3, RpcMethods.CONFIG_UPDATE, removal).result().has("theme"));
        }
    }

    @Test
    void serverSandboxCeilingAlwaysProtectsRuntimeAndCredentialRoots() {
        Path dataRoot = temporary.resolve("data-v4").toAbsolutePath().normalize();
        var ceiling = ServerComponentGraph.hostCeiling(dataRoot);

        assertTrue(ceiling.protectedRoots().contains(SandboxPaths.canonicalize(dataRoot)));
        assertTrue(ceiling.protectedRoots()
                .contains(SandboxPaths.canonicalize(
                        Path.of(System.getProperty("user.home")).resolve(".ssh"))));
        assertTrue(ceiling.protectedRoots()
                .contains(SandboxPaths.canonicalize(
                        Path.of(System.getProperty("user.home")).resolve(".javaclaw"))));
    }

    @Test
    void notificationsNeverProduceProtocolErrorResponses() {
        RuntimeEventBus events = new RuntimeEventBus(8);
        try (DefaultAgentRuntime service = new DefaultAgentRuntime(
                        new H2Persistence(temporary.resolve("notification-data")).runtime(),
                        (context, sink) -> {},
                        events);
                AppServerSession session = session(service, events, new JsonRpcCodec().mapper(), ignored -> {})) {
            assertTrue(session.handle(new JsonRpcNotification(
                            "2.0", "unknown/notification", JsonNodeFactory.instance.objectNode()))
                    .isEmpty());
        }
    }

    @Test
    void eventPaginationRejectsUnsafeBoundsAsInvalidParameters() {
        RuntimeEventBus events = new RuntimeEventBus(8);
        JsonRpcCodec codec = new JsonRpcCodec();
        try (DefaultAgentRuntime service = new DefaultAgentRuntime(
                        new H2Persistence(temporary.resolve("event-bounds-data")).runtime(),
                        (context, sink) -> {},
                        events);
                AppServerSession session = session(service, events, codec.mapper(), ignored -> {})) {
            initialize(session, codec);
            ObjectNode createWorkspace = JsonNodeFactory.instance.objectNode();
            createWorkspace.put("name", "workspace");
            createWorkspace.put("root", temporary.toString());
            createWorkspace.put("idempotencyKey", "event-bounds-workspace");
            String workspaceId = send(session, 2, RpcMethods.WORKSPACE_CREATE, createWorkspace)
                    .result()
                    .path("id")
                    .asText();
            ObjectNode start = JsonNodeFactory.instance.objectNode();
            start.put("workspaceId", workspaceId);
            String threadId = send(session, 3, RpcMethods.THREAD_START, start)
                    .result()
                    .path("id")
                    .asText();

            ObjectNode fractional = threadParams(threadId);
            fractional.put("limit", 1.5d);
            assertEquals(
                    com.javaclaw.protocol.JsonRpcError.INVALID_PARAMS,
                    send(session, 4, RpcMethods.EVENT_LIST, fractional).error().code());

            ObjectNode excessive = threadParams(threadId);
            excessive.put("limit", 10_001);
            assertEquals(
                    com.javaclaw.protocol.JsonRpcError.INVALID_PARAMS,
                    send(session, 5, RpcMethods.EVENT_LIST, excessive).error().code());
        }
    }

    @Test
    void managesLoopAndScheduleThroughTheUnifiedProtocol() {
        RuntimeEventBus events = new RuntimeEventBus(32);
        JsonRpcCodec codec = new JsonRpcCodec();
        try (H2Persistence store = new H2Persistence(temporary.resolve("automation-data"));
                DefaultAgentRuntime service = new DefaultAgentRuntime(
                        store.runtime(), (context, sink) -> sink.append(new ThreadItem.AgentMessage("done")), events)) {
            var workspace = service.createWorkspace("Automation", temporary, "automation-workspace");
            ProfileService profiles = new ProfileService(new H2ProfileRepository(store.database()), java.util.Set.of());
            putTestProfile(profiles, "profile_loop", com.javaclaw.core.api.ProfileKind.LOOP);
            putTestProfile(profiles, "profile_schedule", com.javaclaw.core.api.ProfileKind.SCHEDULE);
            try (AutomationRuntime automation = new AutomationRuntime(
                    new H2AutomationRepository(store.database()), service, service, service, (id, value, kind) -> {
                        var resolved =
                                profiles.resolve(id, value, com.javaclaw.core.api.ApprovalPolicy.NEVER, "medium");
                        if (resolved.profile().kind() != kind) {
                            throw new IllegalArgumentException("profile kind mismatch");
                        }
                        return resolved;
                    })) {
                automation.start();
                try (AppServerSession session = session(
                        service,
                        events,
                        codec.mapper(),
                        ignored -> {},
                        null,
                        null,
                        ServerDiscovery.EMPTY,
                        false,
                        ServerConfiguration.inMemory(codec.mapper()),
                        store.attachments(),
                        service.liveItemEvents(),
                        new ServerUseCases(
                                profiles,
                                null,
                                automation,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null))) {
                    initialize(session, codec);
                    ObjectNode put = JsonNodeFactory.instance.objectNode();
                    put.put("expectedRevision", 0);
                    put.put("idempotencyKey", "automation-put");
                    ObjectNode definition = put.putObject("automation");
                    definition.put("id", "automation_loop");
                    definition.put("kind", "LOOP");
                    definition.put("name", "Loop");
                    definition.put("workspaceId", workspace.id().value());
                    definition.put("profileId", "profile_loop");
                    definition.put("prompt", "finish the task");
                    definition.putObject("definition").put("maxIterations", 3);
                    JsonRpcResponse saved = send(session, 2, RpcMethods.AUTOMATION_PUT, put);
                    assertEquals("automation_loop", saved.result().path("id").asText());

                    ObjectNode start = JsonNodeFactory.instance.objectNode();
                    start.put("automationId", "automation_loop");
                    start.put("idempotencyKey", "automation-start");
                    assertFalse(send(session, 3, RpcMethods.AUTOMATION_START, start)
                            .result()
                            .path("id")
                            .asText()
                            .isBlank());

                    ObjectNode schedule = JsonNodeFactory.instance.objectNode();
                    schedule.put("expectedRevision", 0);
                    schedule.put("idempotencyKey", "schedule-put");
                    ObjectNode value = schedule.putObject("schedule");
                    value.put("id", "schedule_manual");
                    value.put("name", "Manual");
                    value.put("workspaceId", workspace.id().value());
                    value.put("profileId", "profile_schedule");
                    value.put("prompt", "scheduled task");
                    value.put("cronExpression", "0 0 0 1 1 ? 2099");
                    value.put("zoneId", "UTC");
                    value.put("enabled", false);
                    assertEquals(
                            "schedule_manual",
                            send(session, 4, RpcMethods.SCHEDULE_PUT, schedule)
                                    .result()
                                    .path("id")
                                    .asText());

                    ObjectNode trigger = JsonNodeFactory.instance.objectNode();
                    trigger.put("scheduleId", "schedule_manual");
                    trigger.put("idempotencyKey", "manual-trigger");
                    JsonRpcResponse triggered = send(session, 5, RpcMethods.SCHEDULE_TRIGGER, trigger);
                    assertFalse(triggered.result().path("skipped").asBoolean());
                    assertEquals("STARTED", triggered.result().path("reason").asText());

                    ObjectNode preview = JsonNodeFactory.instance.objectNode();
                    preview.put("cronExpression", "0 0 9 * * ?");
                    preview.put("zoneId", "Asia/Shanghai");
                    preview.put("count", 5);
                    JsonNode future = send(session, 6, RpcMethods.SCHEDULE_PREVIEW, preview)
                            .result();
                    assertEquals("Asia/Shanghai", future.path("zoneId").asText());
                    assertEquals(5, future.path("fireTimes").size());

                    preview.put("count", 21);
                    assertEquals(
                            com.javaclaw.protocol.JsonRpcError.INVALID_PARAMS,
                            send(session, 7, RpcMethods.SCHEDULE_PREVIEW, preview)
                                    .error()
                                    .code());
                }
            }
        }
    }

    @Test
    void diagnosticsProtocolExportsOnlyTheSanitizedBoundedBundle() throws Exception {
        RuntimeEventBus events = new RuntimeEventBus(8);
        JsonRpcCodec codec = new JsonRpcCodec();
        try (H2Persistence store = new H2Persistence(temporary.resolve("diagnostics-data"));
                DefaultAgentRuntime service = new DefaultAgentRuntime(store.runtime(), (context, sink) -> {}, events)) {
            H2DiagnosticsRepository records = new H2DiagnosticsRepository(store.database());
            records.append("WARN", "plugin", "timeout", "sanitized failure", "{\"path\":\"[redacted]\"}");
            DiagnosticsService diagnostics = new DiagnosticsService(records, store.attachments(), codec.mapper());
            ServerUseCases features = new ServerUseCases(
                    null, null, null, null, null, diagnostics, null, null, null, null, null, null, null, null, null);
            try (AppServerSession session = session(
                    service,
                    events,
                    codec.mapper(),
                    ignored -> {},
                    null,
                    null,
                    ServerDiscovery.EMPTY,
                    false,
                    ServerConfiguration.inMemory(codec.mapper()),
                    store.attachments(),
                    service.liveItemEvents(),
                    features)) {
                initialize(session, codec);
                ObjectNode read = JsonNodeFactory.instance.objectNode();
                read.put("limit", 10);
                JsonNode view =
                        send(session, 2, RpcMethods.DIAGNOSTICS_READ, read).result();
                assertEquals("javaclaw-diagnostics-v1", view.path("format").asText());
                assertFalse(view.path("credentialsIncluded").asBoolean(true));
                assertFalse(view.path("environmentIncluded").asBoolean(true));
                assertEquals(
                        "sanitized failure",
                        view.path("records").get(0).path("message").asText());

                JsonNode exported = send(
                                session, 3, RpcMethods.DIAGNOSTICS_EXPORT, JsonNodeFactory.instance.objectNode())
                        .result();
                String sha256 = exported.path("sha256").asText();
                assertTrue(sha256.matches("[0-9a-f]{64}"));
                ByteArrayOutputStream extracted = new ByteArrayOutputStream();
                try (ZipInputStream zip =
                        new ZipInputStream(store.attachments().openAttachment(sha256), StandardCharsets.UTF_8)) {
                    for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                        if ("diagnostics.json".equals(entry.getName())) {
                            zip.transferTo(extracted);
                        }
                    }
                }
                String content = extracted.toString(StandardCharsets.UTF_8);
                assertTrue(content.contains("sanitized failure"));
                assertFalse(content.contains("JAVACLAW_"));
                assertFalse(content.toLowerCase().contains("api_key"));
            }
        }
    }

    private static void putTestProfile(ProfileService profiles, String id, com.javaclaw.core.api.ProfileKind kind) {
        profiles.put(
                new ProfileRepository.ProfileDraft(
                        id, id, kind, "openai", "gpt-5", "", java.util.Set.of(), SandboxMode.READ_ONLY, 8, 8, Map.of()),
                0,
                "put-" + id);
    }

    private static AppServerSession session(
            DefaultAgentRuntime service,
            RuntimeEventBus events,
            com.fasterxml.jackson.databind.ObjectMapper json,
            java.util.function.Consumer<JsonRpcNotification> notifications) {
        return session(
                service,
                events,
                json,
                notifications,
                null,
                null,
                ServerDiscovery.EMPTY,
                false,
                ServerConfiguration.inMemory(json),
                null,
                com.javaclaw.agent.runtime.LiveItemSource.EMPTY,
                ServerUseCases.EMPTY);
    }

    private static AppServerSession session(
            DefaultAgentRuntime service,
            RuntimeEventBus events,
            com.fasterxml.jackson.databind.ObjectMapper json,
            java.util.function.Consumer<JsonRpcNotification> notifications,
            ApprovalResponseHandler approvals) {
        return session(
                service,
                events,
                json,
                notifications,
                approvals,
                null,
                ServerDiscovery.EMPTY,
                false,
                ServerConfiguration.inMemory(json),
                null,
                com.javaclaw.agent.runtime.LiveItemSource.EMPTY,
                ServerUseCases.EMPTY);
    }

    private static AppServerSession session(
            DefaultAgentRuntime service,
            RuntimeEventBus events,
            com.fasterxml.jackson.databind.ObjectMapper json,
            java.util.function.Consumer<JsonRpcNotification> notifications,
            ApprovalResponseHandler approvals,
            UserInputResponseHandler userInputs,
            ServerDiscovery discovery,
            boolean localSocket) {
        return session(
                service,
                events,
                json,
                notifications,
                approvals,
                userInputs,
                discovery,
                localSocket,
                ServerConfiguration.inMemory(json),
                null,
                com.javaclaw.agent.runtime.LiveItemSource.EMPTY,
                ServerUseCases.EMPTY);
    }

    private static AppServerSession session(
            DefaultAgentRuntime service,
            RuntimeEventBus events,
            com.fasterxml.jackson.databind.ObjectMapper json,
            java.util.function.Consumer<JsonRpcNotification> notifications,
            ApprovalResponseHandler approvals,
            UserInputResponseHandler userInputs,
            ServerDiscovery discovery,
            boolean localSocket,
            ServerConfiguration configuration) {
        return session(
                service,
                events,
                json,
                notifications,
                approvals,
                userInputs,
                discovery,
                localSocket,
                configuration,
                null,
                com.javaclaw.agent.runtime.LiveItemSource.EMPTY,
                ServerUseCases.EMPTY);
    }

    private static AppServerSession session(
            DefaultAgentRuntime service,
            RuntimeEventBus events,
            com.fasterxml.jackson.databind.ObjectMapper json,
            java.util.function.Consumer<JsonRpcNotification> notifications,
            ApprovalResponseHandler approvals,
            UserInputResponseHandler userInputs,
            ServerDiscovery discovery,
            boolean localSocket,
            ServerConfiguration configuration,
            com.javaclaw.agent.runtime.persistence.AttachmentRepository attachments,
            com.javaclaw.agent.runtime.LiveItemSource liveItems,
            ServerUseCases useCases) {
        return new AppServerSession(
                service,
                events,
                json,
                notifications,
                liveItems,
                new DomainRpcApi(
                        service,
                        discovery,
                        configuration,
                        attachments,
                        approvals,
                        userInputs,
                        useCases,
                        json,
                        localSocket,
                        liveItems != com.javaclaw.agent.runtime.LiveItemSource.EMPTY));
    }

    private static void initialize(AppServerSession session, JsonRpcCodec codec) {
        InitializeParams initialize = new InitializeParams(
                ProtocolVersion.CURRENT, new InitializeParams.ClientInfo("test", "test", "1"), Map.of());
        assertEquals(
                ProtocolVersion.CURRENT,
                send(session, 1, RpcMethods.INITIALIZE, codec.mapper().valueToTree(initialize))
                        .result()
                        .path("protocolVersion")
                        .asInt());
    }

    private static ObjectNode threadParams(String id) {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("threadId", id);
        return params;
    }

    private static ObjectNode profileTurn(String threadId, String text) {
        ObjectNode params = threadParams(threadId);
        params.put("profileId", "profile_chat");
        params.putObject("config").put("approvalPolicy", "NEVER");
        ObjectNode input = params.putArray("input").addObject();
        input.put("type", "text");
        input.put("text", text);
        return params;
    }

    private static void awaitTerminal(DefaultAgentRuntime service, String turnId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        var id = new com.javaclaw.core.api.TurnId(turnId);
        while (!service.readTurn(id).orElseThrow().status().terminal() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(service.readTurn(id).orElseThrow().status().terminal());
    }

    private static JsonRpcResponse send(AppServerSession session, long id, String method, JsonNode params) {
        return session.handle(new JsonRpcRequest("2.0", JsonNodeFactory.instance.numberNode(id), method, params))
                .orElseThrow();
    }
}
