package com.javaclaw.sdk;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.collaboration.CollaborationGateway;
import com.javaclaw.agent.runtime.DefaultAgentRuntime;
import com.javaclaw.agent.runtime.RuntimeEventBus;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.WorkspaceId;
import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.server.collaboration.WorktreeRecoveryUseCases;
import com.javaclaw.server.configuration.ServerConfiguration;
import com.javaclaw.server.discovery.ServerDiscovery;
import com.javaclaw.server.persistence.H2Persistence;
import com.javaclaw.server.transport.AppServerEndpointConfig;
import com.javaclaw.server.transport.ServerUseCases;
import com.javaclaw.server.transport.StdioAppServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorktreeRecoveryIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void typedSdkKeepsRecoveryIdentifiersRevisionAndConfirmationAcrossJsonRpc() throws Exception {
        var child = ThreadId.random();
        var parent = ThreadId.random();
        var workspace = new WorkspaceId("workspace_recovery");
        var seen = new AtomicReference<WorktreeRecoveryUseCases.CleanupRequest>();
        WorktreeRecoveryUseCases recovery = new WorktreeRecoveryUseCases() {
            @Override
            public List<RecoveryInfo> listRecovery(WorkspaceId id) {
                assertEquals(workspace, id);
                return List.of(new RecoveryInfo(
                        "worktree_test",
                        id.value(),
                        parent,
                        child,
                        "CONFLICT",
                        3,
                        false,
                        "a".repeat(64),
                        "保留冲突现场",
                        Instant.parse("2026-08-29T00:00:00Z")));
            }

            @Override
            public CollaborationGateway.PatchResult exportPatch(ThreadId id, long revision) {
                assertEquals(child, id);
                assertEquals(3, revision);
                return new CollaborationGateway.PatchResult("READY", "b".repeat(64), List.of(), "补丁已生成，尚未应用");
            }

            @Override
            public RecoveryInfo cleanup(CleanupRequest request) {
                seen.set(request);
                return new RecoveryInfo(
                        "worktree_test",
                        workspace.value(),
                        parent,
                        child,
                        "CLEANED",
                        4,
                        false,
                        "b".repeat(64),
                        "已清理，备份保留",
                        Instant.parse("2026-08-29T00:00:01Z"));
            }

            @Override
            public void assertCanDeleteThread(ThreadId id) {
                throw new IllegalStateException("managed worktree must be handled first");
            }
        };
        try (var persistence = new H2Persistence(temporary.resolve("data"));
                var runtime =
                        new DefaultAgentRuntime(persistence.runtime(), (context, sink) -> {}, new RuntimeEventBus());
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var codec = new JsonRpcCodec();
            var endpoint = new AppServerEndpointConfig(
                    runtime,
                    runtime.liveEvents(),
                    codec,
                    StdioAppServer.DEFAULT_MAX_FRAME_CHARS,
                    null,
                    null,
                    ServerDiscovery.EMPTY,
                    false,
                    ServerConfiguration.inMemory(codec.mapper()),
                    persistence.attachments(),
                    runtime.liveItemEvents(),
                    new ServerUseCases(
                            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                            recovery));
            var input = Pipe.open();
            var output = Pipe.open();
            var serving = executor.submit(() -> {
                try (var reader =
                                new InputStreamReader(Channels.newInputStream(input.source()), StandardCharsets.UTF_8);
                        var writer = new OutputStreamWriter(
                                Channels.newOutputStream(output.sink()), StandardCharsets.UTF_8)) {
                    new StdioAppServer(endpoint).serve(reader, writer);
                }
                return null;
            });
            try (var client = new JavaClawClient(new JsonRpcConnection(
                    Channels.newInputStream(output.source()), Channels.newOutputStream(input.sink())))) {
                assertTrue(client.initialize("worktree-sdk", "1")
                        .get(5, TimeUnit.SECONDS)
                        .capabilities()
                        .get("worktreeRecovery"));
                var row = client.threads()
                        .worktrees(workspace.value())
                        .get(5, TimeUnit.SECONDS)
                        .getFirst();
                assertEquals(child.value(), row.childThreadId());
                assertEquals(3, row.revision());
                var patch = client.threads()
                        .exportWorktreePatch(row.childThreadId(), row.revision())
                        .get(5, TimeUnit.SECONDS);
                assertEquals("b".repeat(64), patch.patchAttachmentSha256());
                var cleaned = client.threads()
                        .cleanupWorktree(row.childThreadId(), row.revision(), true, "cleanup-key")
                        .get(5, TimeUnit.SECONDS);
                assertEquals("CLEANED", cleaned.state());
                assertEquals(new WorktreeRecoveryUseCases.CleanupRequest(child, 3, true, "cleanup-key"), seen.get());
            }
            serving.get(5, TimeUnit.SECONDS);
        }
    }
}
