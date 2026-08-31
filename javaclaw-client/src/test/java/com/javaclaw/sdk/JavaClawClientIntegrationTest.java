package com.javaclaw.sdk;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.kernel.AgentKernel;
import com.javaclaw.agent.runtime.DefaultAgentRuntime;
import com.javaclaw.agent.runtime.RuntimeEventBus;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.sdk.model.ThreadSnapshot;
import com.javaclaw.sdk.model.TurnInput;
import com.javaclaw.sdk.model.TurnStartRequest;
import com.javaclaw.server.persistence.H2Persistence;
import com.javaclaw.server.transport.LocalSocketAppServer;
import com.javaclaw.server.transport.StdioAppServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaClawClientIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void roundTripsThroughJsonlWithoutRuntimeDependenciesInTheSdkApi() throws Exception {
        Pipe requests = Pipe.open();
        Pipe responses = Pipe.open();
        var serverInput = Channels.newInputStream(requests.source());
        var clientOutput = Channels.newOutputStream(requests.sink());
        var clientInput = Channels.newInputStream(responses.source());
        var serverOutput = Channels.newOutputStream(responses.sink());
        RuntimeEventBus events = new RuntimeEventBus(32);
        AgentKernel kernel = (context, sink) -> sink.append(new ThreadItem.AgentMessage("sdk answer"));
        try (DefaultAgentRuntime service = new DefaultAgentRuntime(
                        new H2Persistence(temporary.resolve("data-v4")).runtime(), kernel, events);
                ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                try (serverInput;
                        serverOutput) {
                    StdioAppServer.minimal(service, events)
                            .serve(
                                    new InputStreamReader(serverInput, StandardCharsets.UTF_8),
                                    new OutputStreamWriter(serverOutput, StandardCharsets.UTF_8));
                }
                return null;
            });
            List<ClientNotification> notifications = new CopyOnWriteArrayList<>();
            try (JavaClawClient client = new JavaClawClient(new JsonRpcConnection(clientInput, clientOutput));
                    AutoCloseable ignored = client.onNotification(notifications::add)) {
                assertEquals(
                        1, client.initialize("integration-test", "1").join().protocolVersion());
                var workspace = client.workspaces()
                        .create("workspace", temporary, "sdk-workspace")
                        .join();
                var thread = client.threads()
                        .start(workspace.id(), "integration", "sdk-thread")
                        .join();
                client.threads()
                        .startTurn(new TurnStartRequest(
                                thread.id(),
                                "profile_chat",
                                List.of(new TurnInput.Text("question")),
                                TurnStartRequest.ApprovalMode.PROFILE_DEFAULT,
                                TurnStartRequest.ReasoningMode.PROFILE_DEFAULT,
                                "key"))
                        .join();

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                ThreadSnapshot snapshot;
                do {
                    snapshot = client.threads().read(thread.id()).join();
                    if (snapshot.turns().getFirst().status().equals("COMPLETED")) {
                        break;
                    }
                    Thread.sleep(10);
                } while (System.nanoTime() < deadline);

                assertEquals(
                        List.of("userMessage", "agentMessage"),
                        snapshot.items().stream()
                                .map(item -> item.content().kind())
                                .toList());
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (notifications.stream()
                                .noneMatch(value -> value instanceof EventNotification event
                                        && "turn/completed".equals(event.event().type()))
                        && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertTrue(notifications.stream()
                        .anyMatch(value -> value instanceof EventNotification event
                                && "turn/completed".equals(event.event().type())));
            }
        }
    }

    @Test
    void connectsThroughCurrentUserUnixDomainSocket() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        org.junit.jupiter.api.Assumptions.assumeTrue(os.contains("mac") || os.contains("linux"));
        Path socket = temporary.resolve("run/app-server.sock");
        RuntimeEventBus events = new RuntimeEventBus(32);
        AgentKernel kernel = (context, sink) -> sink.append(new ThreadItem.AgentMessage("socket answer"));
        try (ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
                DefaultAgentRuntime service = new DefaultAgentRuntime(
                        new H2Persistence(temporary.resolve("socket-data-v4")).runtime(), kernel, events);
                LocalSocketAppServer server = LocalSocketAppServer.minimal(socket, service, events)) {
            var serving = executor.submit(() -> {
                server.serve();
                return null;
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!Files.exists(socket) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            if (!Files.exists(socket) && serving.isDone()) {
                // A supported-platform transport failure is a release-gate failure. In
                // particular, never turn AF_UNIX or SO_PEERCRED denial into a skipped test.
                serving.get(2, TimeUnit.SECONDS);
            }
            assertTrue(Files.exists(socket));
            assertEquals(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(socket));
            try (LocalSocketAppServer contender = LocalSocketAppServer.minimal(socket, service, events)) {
                IOException busy = assertThrows(IOException.class, contender::serve);
                assertTrue(busy.getMessage().contains("another App Server"));
                assertTrue(Files.exists(socket), "a rejected contender must not delete the active endpoint");
            }

            try (JavaClawClient client = LocalSocketClient.connect(socket)) {
                var initialized = client.initialize("socket-test", "1").join();
                assertEquals(Boolean.TRUE, initialized.capabilities().get("localSocket"));
                var workspace = client.workspaces()
                        .create("workspace", temporary, "socket-workspace")
                        .join();
                var thread = client.threads()
                        .start(workspace.id(), "socket", "socket-thread")
                        .join();
                assertEquals(workspace.id(), thread.workspaceId());
            }
            server.close();
            serving.get(2, TimeUnit.SECONDS);
        }
    }
}
