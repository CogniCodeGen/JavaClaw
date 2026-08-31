package com.javaclaw.sdk;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.conversation.CompactionService;
import com.javaclaw.agent.conversation.ProfileRepository;
import com.javaclaw.agent.conversation.ProfileService;
import com.javaclaw.agent.kernel.AgentLoopKernel;
import com.javaclaw.agent.runtime.DefaultAgentRuntime;
import com.javaclaw.agent.runtime.RuntimeEventBus;
import com.javaclaw.agent.tool.TurnToolSession;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ModelRequest;
import com.javaclaw.core.api.ModelResponse;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ProfileKind;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.core.api.ToolExecutionResult;
import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sdk.model.StructuredItemContent;
import com.javaclaw.sdk.model.ThreadSnapshot;
import com.javaclaw.sdk.model.TurnInput;
import com.javaclaw.sdk.model.TurnStartRequest;
import com.javaclaw.server.configuration.ServerConfiguration;
import com.javaclaw.server.discovery.ServerDiscovery;
import com.javaclaw.server.persistence.H2Persistence;
import com.javaclaw.server.persistence.H2ProfileRepository;
import com.javaclaw.server.transport.AppServerEndpointConfig;
import com.javaclaw.server.transport.ServerUseCases;
import com.javaclaw.server.transport.StdioAppServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void manualCompactionReturnsImmediatelyAndInstallsIdOnlySummaryWindow() throws Exception {
        exercise(true);
    }

    @Test
    void emptySummaryFailsAtomicallyWithoutSchemaRepairOrToolDiscovery() throws Exception {
        exercise(false);
    }

    private void exercise(boolean validSummary) throws Exception {
        var requestsSeen = new CopyOnWriteArrayList<ModelRequest>();
        var toolSessions = new AtomicInteger();
        var compactionEntered = new CountDownLatch(1);
        var releaseCompaction = new CountDownLatch(1);
        var kernel = new AgentLoopKernel(
                request -> {
                    requestsSeen.add(request);
                    boolean compact =
                            "COMPACTION".equals(request.config().attributes().get("invocationPurpose"));
                    if (compact) {
                        assertTrue(request.tools().isEmpty());
                        assertTrue(request.messages().stream()
                                .anyMatch(message -> message.role() == ModelMessage.Role.USER
                                        && message.content().contains("当前进展")));
                        compactionEntered.countDown();
                        assertTrue(releaseCompaction.await(5, TimeUnit.SECONDS));
                        return new ModelResponse(
                                validSummary ? "目标：保留原 UI。\n决策：沿用原配色。\n待办：完成真实测试。" : "   ",
                                "",
                                List.of(),
                                new ModelUsage(10, 10, 0));
                    }
                    return new ModelResponse("普通对话结果", "", List.of(), new ModelUsage(10, 10, 0));
                },
                (context, sink) -> {
                    assertFalse(
                            "COMPACTION"
                                    .equals(context.turn().config().attributes().get("invocationPurpose")),
                            "压缩 Turn 不得发现或启动工具 Provider");
                    toolSessions.incrementAndGet();
                    return new TurnToolSession() {
                        @Override
                        public List<ToolDescriptor> availableTools() {
                            return List.of();
                        }

                        @Override
                        public ToolExecutionResult execute(ModelToolCall call) {
                            throw new AssertionError("无工具");
                        }
                    };
                });
        var events = new RuntimeEventBus();
        try (var persistence = new H2Persistence(temporary.resolve(validSummary ? "success" : "failure"));
                var runtime = new DefaultAgentRuntime(persistence.runtime(), kernel, events);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var profiles = new ProfileService(new H2ProfileRepository(persistence.database()), Set.of());
            profiles.put(
                    new ProfileRepository.ProfileDraft(
                            "chat",
                            "Chat",
                            ProfileKind.CHAT,
                            "anthropic",
                            "fake",
                            "",
                            Set.of(),
                            SandboxMode.READ_ONLY,
                            8,
                            8,
                            Map.of()),
                    0,
                    "profile");
            var codec = new JsonRpcCodec();
            var endpoint = new AppServerEndpointConfig(
                    runtime,
                    events,
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
                            profiles,
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
                            null,
                            new CompactionService(runtime, runtime),
                            null));
            Pipe requests = Pipe.open();
            Pipe responses = Pipe.open();
            var serving = executor.submit(() -> {
                try (var input = Channels.newInputStream(requests.source());
                        var output = Channels.newOutputStream(responses.sink())) {
                    new StdioAppServer(endpoint)
                            .serve(
                                    new InputStreamReader(input, StandardCharsets.UTF_8),
                                    new OutputStreamWriter(output, StandardCharsets.UTF_8));
                }
                return null;
            });
            try (var client = new JavaClawClient(new JsonRpcConnection(
                    Channels.newInputStream(responses.source()), Channels.newOutputStream(requests.sink())))) {
                client.initialize("compaction-test", "1").join();
                var workspace = client.workspaces()
                        .create("workspace", temporary, "workspace-" + validSummary)
                        .join();
                var thread = client.threads()
                        .start(workspace.id(), "压缩测试", "thread-" + validSummary)
                        .join();
                var first = client.threads()
                        .startTurn(new TurnStartRequest(
                                thread.id(), "chat", List.of(new TurnInput.Text("原用户目标：保留原 UI")), null, null, "first"))
                        .join();
                ThreadSnapshot original = client.threads()
                        .awaitTurn(thread.id(), first.id(), Duration.ofSeconds(5))
                        .join();

                client.threads().startCompaction(thread.id()).get(1, TimeUnit.SECONDS);
                assertTrue(compactionEntered.await(2, TimeUnit.SECONDS));
                ThreadSnapshot scheduled =
                        awaitTurnCount(client, thread.id(), original.turns().size() + 1);
                String compactionTurnId = scheduled.turns().getLast().id();
                assertEquals(1, toolSessions.get(), "压缩开始前只能打开普通 Turn 的工具会话");
                releaseCompaction.countDown();

                ThreadSnapshot compacted = client.threads()
                        .awaitTurn(thread.id(), compactionTurnId, Duration.ofSeconds(5))
                        .join();
                assertEquals(
                        validSummary ? "COMPLETED" : "FAILED",
                        compacted.turns().getLast().status());
                assertTrue(
                        compacted.items().stream()
                                .map(item -> item.id())
                                .toList()
                                .containsAll(original.items().stream()
                                        .map(item -> item.id())
                                        .toList()),
                        "压缩不得删除原始 transcript");
                assertEquals(
                        1,
                        requestsSeen.stream()
                                .filter(request -> "COMPACTION"
                                        .equals(request.config().attributes().get("invocationPurpose")))
                                .count());
                assertEquals(1, toolSessions.get());

                var compactionItem = compacted.items().stream()
                        .filter(item -> item.turnId().equals(compactionTurnId))
                        .filter(item -> item.content().kind().equals("contextCompaction"))
                        .findFirst()
                        .orElseThrow();
                assertEquals(validSummary ? "COMPLETED" : "FAILED", compactionItem.state());
                if (validSummary) {
                    var content = (StructuredItemContent) compactionItem.content();
                    assertEquals("{}", content.document().canonicalJson());
                    assertFalse(content.document().canonicalJson().contains("summary"));
                }

                var next = client.threads()
                        .startTurn(new TurnStartRequest(
                                thread.id(), "chat", List.of(new TurnInput.Text("继续核验")), null, null, "next"))
                        .join();
                client.threads()
                        .awaitTurn(thread.id(), next.id(), Duration.ofSeconds(5))
                        .join();
                ModelRequest nextRequest = requestsSeen.getLast();
                assertEquals(
                        validSummary,
                        nextRequest.messages().stream()
                                .anyMatch(message -> message.role() == ModelMessage.Role.USER
                                        && message.content().contains("待办：完成真实测试")));
                assertEquals(
                        !validSummary,
                        nextRequest.messages().stream()
                                        .anyMatch(message -> message.content().contains("原用户目标：保留原 UI"))
                                && nextRequest.messages().stream()
                                        .noneMatch(message -> message.content().contains("以下是该模型生成的摘要")));
                assertEquals(2, toolSessions.get());
            }
            serving.get(5, TimeUnit.SECONDS);
        }
    }

    private static ThreadSnapshot awaitTurnCount(JavaClawClient client, String threadId, int expected)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            ThreadSnapshot snapshot = client.threads().read(threadId).get(1, TimeUnit.SECONDS);
            if (snapshot.turns().size() >= expected) {
                return snapshot;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("压缩 Turn 未进入持久快照");
    }
}
