package com.javaclaw.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.PlanContracts;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.protocol.BundleRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.InitializeResult;
import com.javaclaw.protocol.JsonRpcError;
import com.javaclaw.protocol.JsonRpcMessage;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.LocalTransport;
import com.javaclaw.protocol.NegotiatedCapabilities;
import com.javaclaw.protocol.ProtocolErrorCode;
import com.javaclaw.protocol.RpcConnection;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.SessionKeyInfo;
import com.javaclaw.protocol.TransportKind;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaClawClientTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final Workspace WORKSPACE = new Workspace(
            WorkspaceId.parse("114fdfd7-d4e7-42f4-9563-dd552b61f97d"),
            "Demo",
            Path.of("/tmp/javaclaw-client-demo"),
            com.javaclaw.api.WorkspaceLifecycle.ACTIVE,
            1,
            Instant.parse("2026-08-31T12:00:00Z"),
            Instant.parse("2026-08-31T12:00:00Z"));

    @Test
    void initializationAndWorkspaceQueryReuseSharedApiContract() throws IOException {
        ScriptedConnection rpc = new ScriptedConnection(request -> switch (request.method()) {
            case "initialize/session" -> initialize(request, Set.of("test.experimental"));
            case "workspace/list" ->
                JsonRpcResponse.success(
                        request.id(), JSON.encode(new CoreRpcContracts.WorkspaceListResult(List.of(WORKSPACE))));
            default -> throw new AssertionError("unexpected method " + request.method());
        });

        try (JavaClawClient client = JavaClawClient.connect(
                new FixedTransport(rpc),
                new ClientInfo("test-client", "5.0"),
                Set.of("test.experimental"),
                notification -> {})) {
            assertEquals("javaclaw-app-server", client.server().serverName());
            assertSame(
                    WORKSPACE.getClass(), client.workspaces().list().getFirst().getClass());
            assertEquals(WORKSPACE, client.workspaces().list().getFirst());
            assertTrue(client.threads() != null);
            assertTrue(client.turns() != null);
            assertTrue(client.items() != null);
            assertTrue(client.inputs() != null);
            assertTrue(client.attachments() != null);
            assertTrue(client.credentials() != null);
            assertTrue(client.roles() != null);
            assertTrue(client.executions() != null);
            assertTrue(client.prompts() != null);
            assertTrue(client.providers() != null);
            assertTrue(client.permissionProfiles() != null);
            assertTrue(client.approvals() != null);
            assertTrue(client.securityGrants() != null);
            assertTrue(client.tools() != null);
            assertTrue(client.rollouts() != null);
            assertTrue(client.worktrees() != null);
            assertTrue(client.diagnostics() != null);
            assertTrue(client.extensions() != null);
            assertTrue(client.extensionBundles() != null);
            assertTrue(client.extensionJobs() != null);
            assertTrue(client.builtins() != null);
        }
        assertTrue(rpc.closed);
    }

    @Test
    void writeCommandCarriesReusableIdempotencyAndExpectedRevision() {
        ScriptedConnection rpc = new ScriptedConnection(request -> {
            WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
            assertEquals("retry-key", command.idempotencyKey());
            assertEquals(7, command.expectedRevision());
            CoreRpcContracts.WorkspaceCreatePayload payload =
                    JSON.decode(command.payload(), CoreRpcContracts.WorkspaceCreatePayload.class);
            assertEquals("Demo", payload.name());
            return JsonRpcResponse.success(request.id(), JSON.encode(WORKSPACE));
        });
        RpcClientConnection connection = new RpcClientConnection(rpc, JSON, notification -> {});

        Workspace result = new com.javaclaw.client.facade.WorkspaceClient(connection)
                .create("Demo", WORKSPACE.root(), new CommandOptions("retry-key", 7));

        assertEquals(WORKSPACE, result);
    }

    @Test
    void Workspace重命名与归档不允许修改根目录() {
        ScriptedConnection rpc = new ScriptedConnection(request -> {
            WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
            assertEquals(WORKSPACE.revision(), command.expectedRevision());
            assertTrue(request.method().equals("workspace/rename")
                    || request.method().equals("workspace/archive"));
            com.javaclaw.api.WorkspaceLifecycle lifecycle = request.method().endsWith("archive")
                    ? com.javaclaw.api.WorkspaceLifecycle.ARCHIVED
                    : com.javaclaw.api.WorkspaceLifecycle.ACTIVE;
            Workspace result = new Workspace(
                    WORKSPACE.id(),
                    "Renamed",
                    WORKSPACE.root(),
                    lifecycle,
                    2,
                    WORKSPACE.createdAt(),
                    WORKSPACE.updatedAt());
            return JsonRpcResponse.success(request.id(), JSON.encode(result));
        });
        var workspaces =
                new com.javaclaw.client.facade.WorkspaceClient(new RpcClientConnection(rpc, JSON, notification -> {}));

        Workspace renamed = workspaces.rename(WORKSPACE, "Renamed", new CommandOptions("rename-workspace", 1));
        Workspace archived = workspaces.archive(WORKSPACE, new CommandOptions("archive-workspace", 1));

        assertEquals(WORKSPACE.root(), renamed.root());
        assertEquals(com.javaclaw.api.WorkspaceLifecycle.ARCHIVED, archived.lifecycle());
    }

    @Test
    void builtInFacadeKeepsNestedCommandContractStronglyTyped() throws IOException {
        PlanContracts.ManagementSaveRequest input = new PlanContracts.ManagementSaveRequest(
                "release-5",
                "发布 5.0",
                "通过全部 v5 验收门禁",
                "仅验证发布门禁",
                List.of(new PlanContracts.ManagementRisk("risk-1", "构建失败")),
                List.of(),
                List.of(new PlanContracts.ManagementStep(
                        "step-1", "verify", "执行完整 verify", "运行 Maven verify", "退出码为 0", List.of())));
        PlanContracts.Definition plan = new PlanContracts.Definition(
                "release-5",
                1,
                "发布 5.0",
                "通过全部 v5 验收门禁",
                "仅验证发布门禁",
                List.of("构建失败"),
                List.of(),
                List.of(new PlanContracts.Step("verify", "执行完整 verify", "运行 Maven verify", "退出码为 0", List.of())),
                Instant.parse("2026-08-31T12:00:00Z"));
        ScriptedConnection rpc = new ScriptedConnection(request -> switch (request.method()) {
            case "initialize/session" -> initialize(request, Set.of());
            case "extension/command" -> extensionCommand(request, input, plan);
            default -> throw new AssertionError("unexpected method " + request.method());
        });

        try (JavaClawClient client = JavaClawClient.connect(
                new FixedTransport(rpc), new ClientInfo("test-client", "5.0"), Set.of(), notification -> {})) {
            PlanContracts.Definition saved =
                    client.builtins().plans().create(WORKSPACE.id(), input, new CommandOptions("plan-create", 0));

            assertEquals(plan, saved);
            assertTrue(client.builtins().loops() != null);
            assertTrue(client.builtins().workflows() != null);
            assertTrue(client.builtins().sdd() != null);
            assertTrue(client.builtins().schedules() != null);
            assertTrue(client.builtins().memories() != null);
            assertTrue(client.builtins().knowledge() != null);
            assertTrue(client.builtins().skills() != null);
            assertTrue(client.builtins().sites() != null);
        }
    }

    @Test
    void bundleFacade保留权限确认摘要和生命周期命令身份() {
        String digest = "a".repeat(64);
        BundleRpcContracts.StageResult staging = staging(digest);
        BundleRpcContracts.Bundle bundle = bundle(digest);
        List<String> methods = new ArrayList<>();
        ScriptedConnection rpc = new ScriptedConnection(request -> {
            methods.add(request.method());
            WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
            return bundleResponse(request, command, staging, bundle);
        });
        var bundles = new com.javaclaw.client.facade.ExtensionBundleClient(
                new RpcClientConnection(rpc, JSON, notification -> {}));

        var staged = bundles.stage(
                new AttachmentMetadata(
                        digest, BundleRpcContracts.BUNDLE_MEDIA_TYPE, 1, Instant.parse("2026-09-01T00:00:00Z")),
                new CommandOptions("stage", 0));
        assertEquals(bundle, bundles.install(staged, new CommandOptions("install", 0)));
        assertEquals(bundle, bundles.enable(bundle.id(), new CommandOptions("enable", 1)));
        assertEquals(bundle, bundles.disable(bundle.id(), new CommandOptions("disable", 1)));
        assertEquals(
                "trash-entry",
                bundles.uninstall(bundle.id(), new CommandOptions("uninstall", 1))
                        .trashId());
        assertEquals(
                List.of(
                        "extension/bundle/stage",
                        "extension/bundle/install",
                        "extension/bundle/enable",
                        "extension/bundle/disable",
                        "extension/bundle/uninstall"),
                methods);
    }

    @Test
    void notificationIsDeliveredBeforeMatchingResponse() {
        List<JsonRpcNotification> notifications = new ArrayList<>();
        ScriptedConnection rpc = new ScriptedConnection(request -> JsonRpcResponse.success(
                request.id(), JSON.encode(new CoreRpcContracts.WorkspaceListResult(List.of()))));
        rpc.beforeResponse = new JsonRpcNotification("extension/event", new CanonicalPayload("{}"));
        RpcClientConnection connection = new RpcClientConnection(rpc, JSON, notifications::add);

        new com.javaclaw.client.facade.WorkspaceClient(connection).list();

        assertEquals(List.of(rpc.beforeResponse), notifications);
    }

    @Test
    void sdk把Extension失效通知解码为强类型事件() throws IOException {
        List<ServerNotification> notifications = new ArrayList<>();
        ScriptedConnection rpc = new ScriptedConnection(request -> switch (request.method()) {
            case "initialize/session" -> initialize(request, Set.of());
            case "workspace/list" ->
                JsonRpcResponse.success(request.id(), JSON.encode(new CoreRpcContracts.WorkspaceListResult(List.of())));
            default -> throw new AssertionError("unexpected method " + request.method());
        });
        try (JavaClawClient client = JavaClawClient.connect(
                new FixedTransport(rpc), new ClientInfo("test-client", "5.0"), Set.of(), notifications::add)) {
            ExtensionRpcContracts.ExtensionEvent event = new ExtensionRpcContracts.ExtensionEvent(
                    WORKSPACE.id(), "plan", "workspace", WORKSPACE.id().toString(), "put", 2);
            rpc.beforeResponse = new JsonRpcNotification("extension/event", JSON.encode(event));

            client.workspaces().list();

            assertEquals(List.of(new ServerNotification.ExtensionChanged(event)), notifications);
        }
    }

    @Test
    void remoteErrorPreservesStableCodeAndData() {
        CanonicalPayload data = new CanonicalPayload("{\"currentRevision\":8}");
        ScriptedConnection rpc = new ScriptedConnection(request -> JsonRpcResponse.failure(
                request.id(),
                new JsonRpcError(ProtocolErrorCode.REVISION_CONFLICT, "revision conflict", Optional.of(data))));
        RpcClientConnection connection = new RpcClientConnection(rpc, JSON, notification -> {});

        RemoteRpcException failure = assertThrows(
                RemoteRpcException.class, () -> new com.javaclaw.client.facade.WorkspaceClient(connection).list());

        assertEquals(ProtocolErrorCode.REVISION_CONFLICT, failure.code());
        assertEquals(Optional.of(data), failure.data());
    }

    @Test
    void failedInitializationClosesConnectionAndPreservesCloseFailure() {
        ScriptedConnection rpc = new ScriptedConnection(request -> JsonRpcResponse.failure(
                request.id(), new JsonRpcError(ProtocolErrorCode.INVALID_REQUEST, "rejected", Optional.empty())));
        IOException closeFailure = new IOException("close failed");
        rpc.closeFailure = closeFailure;

        RemoteRpcException failure = assertThrows(
                RemoteRpcException.class,
                () -> JavaClawClient.connect(
                        new FixedTransport(rpc), new ClientInfo("test", "5.0"), Set.of(), notification -> {}));

        assertTrue(rpc.closed);
        assertSame(closeFailure, failure.getSuppressed()[0]);
    }

    @Test
    void connectionRejectsMismatchedResponsesAndWrapsTransportFailures() {
        ScriptedConnection mismatched = new ScriptedConnection(request -> JsonRpcResponse.success(
                new RpcId("other"), JSON.encode(new CoreRpcContracts.WorkspaceListResult(List.of()))));
        RpcClientConnection connection = new RpcClientConnection(mismatched, JSON, notification -> {});
        assertThrows(
                UncheckedIOException.class, () -> new com.javaclaw.client.facade.WorkspaceClient(connection).list());

        RpcConnection failed = new RpcConnection() {
            private final CountDownLatch closed = new CountDownLatch(1);

            @Override
            public void send(JsonRpcMessage message) throws IOException {
                throw new IOException("send failed");
            }

            @Override
            public JsonRpcMessage receive() throws IOException {
                try {
                    closed.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("connection closed");
            }

            @Override
            public void close() {
                closed.countDown();
            }
        };
        RpcClientConnection failedConnection = new RpcClientConnection(failed, JSON, notification -> {});
        assertThrows(
                UncheckedIOException.class,
                () -> new com.javaclaw.client.facade.WorkspaceClient(failedConnection).list());
    }

    @Test
    void commandOptionsRejectInvalidKeysAndCreateReusableIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new CommandOptions(" ", 0));
        assertThrows(IllegalArgumentException.class, () -> new CommandOptions("x".repeat(201), 0));
        assertThrows(IllegalArgumentException.class, () -> new CommandOptions("key", -1));
        CommandOptions created = CommandOptions.create(3);
        assertEquals(3, created.expectedRevision());
        assertFalse(created.idempotencyKey().isBlank());
    }

    private static JsonRpcResponse initialize(JsonRpcRequest request, Set<String> expectedExperimental) {
        InitializeParams params = JSON.decode(request.params(), InitializeParams.class);
        assertEquals(3, params.appProtocolVersion());
        assertTrue(params.capabilities().stableCapabilities().contains("core.item-envelope"));
        assertEquals(expectedExperimental, params.capabilities().requestedExperimentalCapabilities());
        InitializeResult result = new InitializeResult(
                3,
                "javaclaw-app-server",
                "5.0.0-SNAPSHOT",
                new NegotiatedCapabilities(params.capabilities().stableCapabilities(), Set.of("test.experimental")),
                new SessionKeyInfo(SessionKeyInfo.ALGORITHM, "test-session", "AA"));
        return JsonRpcResponse.success(request.id(), JSON.encode(result));
    }

    private static JsonRpcResponse extensionCommand(
            JsonRpcRequest request, PlanContracts.ManagementSaveRequest input, PlanContracts.Definition plan) {
        WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
        assertEquals("plan-create", command.idempotencyKey());
        assertEquals(0, command.expectedRevision());
        ExtensionRpcContracts.CallPayload call =
                JSON.decode(command.payload(), ExtensionRpcContracts.CallPayload.class);
        assertEquals(BuiltinExtensionIds.PLAN, call.extensionId());
        assertEquals(WORKSPACE.id(), call.workspaceId());
        assertEquals("definition/create", call.operation());
        assertEquals(input, JSON.decode(call.payload(), PlanContracts.ManagementSaveRequest.class));
        return JsonRpcResponse.success(
                request.id(), JSON.encode(new ExtensionRpcContracts.CallResult(JSON.encode(plan), plan.revision())));
    }

    private static BundleRpcContracts.StageResult staging(String digest) {
        var permissions = new BundleRpcContracts.PermissionReview(
                true,
                false,
                false,
                Set.of(),
                Set.of(),
                true,
                "worker",
                Duration.ofSeconds(10),
                64L * 1024 * 1024,
                64L * 1024,
                1,
                16);
        var pointer = new BundleRpcContracts.AttachmentPointer(digest, digest);
        return new BundleRpcContracts.StageResult(
                digest,
                pointer,
                digest,
                "demo.extension",
                "Demo",
                "5.0.0",
                "release-key",
                "b".repeat(64),
                Set.of("QUERY"),
                permissions);
    }

    private static JsonRpcResponse bundleResponse(
            JsonRpcRequest request,
            WriteCommand command,
            BundleRpcContracts.StageResult staging,
            BundleRpcContracts.Bundle bundle) {
        return switch (request.method()) {
            case "extension/bundle/stage" -> {
                assertEquals(0, command.expectedRevision());
                assertEquals(
                        staging.attachment(),
                        JSON.decode(command.payload(), BundleRpcContracts.StagePayload.class)
                                .attachment());
                yield JsonRpcResponse.success(request.id(), JSON.encode(staging));
            }
            case "extension/bundle/install" -> {
                var install = JSON.decode(command.payload(), BundleRpcContracts.CommitPayload.class);
                assertEquals(staging.stagingId(), install.stagingId());
                assertEquals(staging.manifestDigest(), install.approvedManifestDigest());
                yield JsonRpcResponse.success(request.id(), JSON.encode(new BundleRpcContracts.BundleResult(bundle)));
            }
            case "extension/bundle/enable", "extension/bundle/disable" -> {
                assertLifecycle(command, bundle.id());
                yield JsonRpcResponse.success(request.id(), JSON.encode(new BundleRpcContracts.BundleResult(bundle)));
            }
            case "extension/bundle/uninstall" -> {
                assertLifecycle(command, bundle.id());
                yield JsonRpcResponse.success(
                        request.id(), JSON.encode(new BundleRpcContracts.TrashResult(trash(bundle))));
            }
            default -> throw new AssertionError("unexpected method " + request.method());
        };
    }

    private static void assertLifecycle(WriteCommand command, String extensionId) {
        assertEquals(1, command.expectedRevision());
        assertEquals(
                extensionId,
                JSON.decode(command.payload(), BundleRpcContracts.BundlePayload.class)
                        .extensionId());
    }

    private static BundleRpcContracts.Bundle bundle(String digest) {
        return new BundleRpcContracts.Bundle(
                "demo.extension",
                "Demo",
                "5.0.0",
                1,
                "INSTALLED",
                digest,
                "release-key",
                "b".repeat(64),
                Set.of("QUERY"),
                staging(digest).permissions(),
                new BundleRpcContracts.Health(
                        BundleRpcContracts.HealthState.NOT_PROBED,
                        0,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
    }

    private static BundleRpcContracts.TrashEntry trash(BundleRpcContracts.Bundle bundle) {
        return new BundleRpcContracts.TrashEntry(
                "trash-entry",
                bundle.id(),
                bundle.version(),
                bundle.revision(),
                bundle.manifestDigest(),
                bundle.signingKeyId(),
                BundleRpcContracts.TrashState.TRASHED,
                Instant.parse("2026-09-01T00:00:00Z"),
                Optional.empty(),
                Optional.empty());
    }

    private record FixedTransport(RpcConnection connection) implements LocalTransport {
        @Override
        public TransportKind kind() {
            return TransportKind.STDIO;
        }

        @Override
        public RpcConnection connect() {
            return connection;
        }
    }

    private static final class ScriptedConnection implements RpcConnection {
        private static final Object CLOSED = new Object();

        private final Function<JsonRpcRequest, JsonRpcResponse> handler;
        private final BlockingQueue<Object> inbound = new ArrayBlockingQueue<>(16);
        private JsonRpcNotification beforeResponse;
        private IOException closeFailure;
        private boolean closed;

        private ScriptedConnection(Function<JsonRpcRequest, JsonRpcResponse> handler) {
            this.handler = handler;
        }

        @Override
        public void send(JsonRpcMessage message) {
            JsonRpcRequest request = (JsonRpcRequest) message;
            if (beforeResponse != null) {
                inbound.add(beforeResponse);
            }
            inbound.add(handler.apply(request));
        }

        @Override
        public JsonRpcMessage receive() throws IOException {
            try {
                Object message = inbound.take();
                if (message == CLOSED) {
                    throw new IOException("scripted connection closed");
                }
                return (JsonRpcMessage) message;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("scripted connection interrupted", interrupted);
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            inbound.clear();
            inbound.offer(CLOSED);
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
