package com.javaclaw.server.extension.thirdparty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2ThirdPartyDocumentStore;
import com.javaclaw.server.persistence.ThirdPartyStorageQuota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThirdPartyWorkerClientTest {
    private static final Instant NOW = Instant.parse("2026-09-01T04:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private InstalledThirdPartyBundle bundle;
    private Workspace workspace;
    private StubSandbox sandbox;
    private ThirdPartyWorkerClient client;
    private H2Database database;

    @BeforeEach
    void initialize() throws Exception {
        json = new CanonicalJson();
        var keys = ThirdPartyBundleTestFixtures.keyPair();
        ThirdPartyBundleDirectories directories = new ThirdPartyBundleDirectories(
                temporaryDirectory.resolve("data-v6"), Clock.fixed(NOW, ZoneOffset.UTC));
        ThirdPartyBundleArchive archive =
                new ThirdPartyBundleArchive(json, ThirdPartyBundleTestFixtures.trustedKeys(keys), directories);
        VerifiedThirdPartyBundle staged = archive.stage(ThirdPartyBundleTestFixtures.attachment(
                ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys)));
        bundle = new ThirdPartyBundleCompiler(json).compile(staged, 1);
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        workspace = new Workspace(
                new WorkspaceId(UUID.randomUUID()),
                "Workspace",
                root,
                com.javaclaw.api.WorkspaceLifecycle.ACTIVE,
                1,
                NOW,
                NOW);
        sandbox = new StubSandbox();
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        client = new ThirdPartyWorkerClient(
                sandbox,
                json,
                new ThirdPartyWorkerActionHandler(
                        database, json, Clock.fixed(NOW, ZoneOffset.UTC), (request, permission, cancellation) -> {
                            throw new AssertionError("this fixture does not request networking");
                        }));
    }

    @Test
    void worker成功响应并保留业务Revision() throws Exception {
        sandbox.output = "{\"actions\":[],\"error\":null,\"ok\":true,\"payload\":{\"value\":1},\"revision\":7}";

        var response = client.invoke(bundle, invocation(new CancellationSource()));

        assertEquals(7, response.revision());
        assertEquals("{\"value\":1}", response.payload().json());
        assertEquals(bundle.root(), sandbox.command.workingDirectory());
        assertEquals("demo.extension", sandbox.command.environment().get("JAVACLAW_EXTENSION_ID"));
    }

    @Test
    void worker区分超时取消退出码和空输出() {
        sandbox.timedOut = true;
        assertThrows(IllegalStateException.class, () -> client.invoke(bundle, invocation(new CancellationSource())));

        sandbox.reset();
        sandbox.cancelled = true;
        assertThrows(IllegalStateException.class, () -> client.invoke(bundle, invocation(new CancellationSource())));

        sandbox.reset();
        sandbox.exitCode = 9;
        assertThrows(IllegalStateException.class, () -> client.invoke(bundle, invocation(new CancellationSource())));

        sandbox.reset();
        sandbox.output = " ";
        assertThrows(IllegalStateException.class, () -> client.invoke(bundle, invocation(new CancellationSource())));

        sandbox.reset();
        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel("stop");
        assertThrows(com.javaclaw.api.TurnCancelledException.class, () -> client.invoke(bundle, invocation(cancelled)));
    }

    @Test
    void worker拒绝错误契约和不一致error字段() {
        sandbox.output =
                "{\"actions\":[],\"error\":{\"code\":\"FAILED\",\"message\":\"bad\"},\"ok\":false,\"payload\":{},\"revision\":0}";
        ThirdPartyWorkerClient.ExtensionWorkerException failure = assertThrows(
                ThirdPartyWorkerClient.ExtensionWorkerException.class,
                () -> client.invoke(bundle, invocation(new CancellationSource())));
        assertEquals("FAILED", failure.code());

        sandbox.output = "{\"actions\":[],\"error\":null,\"ok\":false,\"payload\":{},\"revision\":0}";
        assertThrows(IllegalStateException.class, () -> client.invoke(bundle, invocation(new CancellationSource())));

        sandbox.output =
                "{\"actions\":[],\"error\":{\"code\":\"WARN\",\"message\":\"bad\"},\"ok\":true,\"payload\":{},\"revision\":0}";
        assertThrows(IllegalStateException.class, () -> client.invoke(bundle, invocation(new CancellationSource())));

        sandbox.output = "{\"actions\":[],\"error\":null,\"ok\":true,\"payload\":{},\"revision\":-1}";
        assertThrows(RuntimeException.class, () -> client.invoke(bundle, invocation(new CancellationSource())));

        sandbox.output = "[]";
        assertThrows(RuntimeException.class, () -> client.invoke(bundle, invocation(new CancellationSource())));
    }

    @Test
    void health使用无Workspace请求并检查响应() throws Exception {
        sandbox.output = "{\"actions\":[],\"error\":null,\"ok\":true,\"payload\":{},\"revision\":0}";
        client.health(bundle);
        String input = new String(sandbox.command.standardInput(), StandardCharsets.UTF_8);
        assertTrue(input.contains("\"kind\":\"HEALTH\""));
    }

    @Test
    void worker通过多轮Host动作保存并读取命名空间文档() throws Exception {
        sandbox.outputs.add("""
                {"actions":[{"arguments":{"expectedRevision":0,"key":"state","payload":{"value":1}},
                "id":"save","kind":"DOCUMENT_PUT"}],"error":null,"ok":true,"payload":{},"revision":0}
                """);
        sandbox.outputs.add("""
                {"actions":[{"arguments":{"key":"state"},"id":"read","kind":"DOCUMENT_GET"}],
                "error":null,"ok":true,"payload":{},"revision":0}
                """);
        sandbox.outputs.add("{\"actions\":[],\"error\":null,\"ok\":true,\"payload\":{\"stored\":true},\"revision\":1}");

        var response = client.invoke(bundle, invocation(new CancellationSource()));
        var store = new H2ThirdPartyDocumentStore(
                database,
                bundle.descriptor().id(),
                ThirdPartyStorageQuota.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals("{\"stored\":true}", response.payload().json());
        assertEquals("{\"value\":1}", store.get("state").orElseThrow().payload().json());
        assertEquals(3, sandbox.inputs.size());
        assertTrue(sandbox.inputs.get(1).contains("\"id\":\"save\""));
        assertTrue(sandbox.inputs.get(2).contains("\"present\":true"));
    }

    @Test
    void health拒绝Host动作且普通调用限制动作轮次() {
        String action = """
                {"actions":[{"arguments":{"key":"state"},"id":"read","kind":"DOCUMENT_GET"}],
                "error":null,"ok":true,"payload":{},"revision":0}
                """;
        sandbox.output = action;
        assertThrows(SecurityException.class, () -> client.health(bundle));

        sandbox.reset();
        for (int round = 0; round < 5; round++) {
            sandbox.outputs.add(action);
        }
        assertThrows(IllegalStateException.class, () -> client.invoke(bundle, invocation(new CancellationSource())));
        assertEquals(5, sandbox.inputs.size());
    }

    private ThirdPartyInvocation invocation(CancellationSource cancellation) {
        return new ThirdPartyInvocation(
                ThirdPartyWorkerClient.InvocationKind.QUERY,
                "status",
                workspace,
                Optional.empty(),
                Optional.empty(),
                new CanonicalPayload("{}"),
                Optional.empty(),
                0,
                Optional.empty(),
                cancellation);
    }

    private static final class StubSandbox implements SandboxExecutor {
        private String output = "{}";
        private int exitCode;
        private boolean timedOut;
        private boolean cancelled;
        private SandboxCommand command;
        private final List<String> outputs = new ArrayList<>();
        private final List<String> inputs = new ArrayList<>();

        @Override
        public SandboxResult execute(
                SandboxCommand command, PermissionProfile permission, com.javaclaw.api.CancellationToken cancellation) {
            this.command = command;
            inputs.add(new String(command.standardInput(), StandardCharsets.UTF_8));
            String selected = outputs.isEmpty() ? output : outputs.removeFirst();
            return new SandboxResult(
                    exitCode,
                    selected.getBytes(StandardCharsets.UTF_8),
                    new byte[0],
                    timedOut,
                    cancelled,
                    Duration.ofMillis(1));
        }

        @Override
        public SandboxSession open(
                SandboxCommand command, PermissionProfile permission, com.javaclaw.api.CancellationToken cancellation) {
            throw new UnsupportedOperationException();
        }

        private void reset() {
            output = "{}";
            exitCode = 0;
            timedOut = false;
            cancelled = false;
            outputs.clear();
            inputs.clear();
        }
    }
}
