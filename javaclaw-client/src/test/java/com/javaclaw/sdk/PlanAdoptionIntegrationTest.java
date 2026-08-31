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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.conversation.PlanAdoptionService;
import com.javaclaw.agent.conversation.ProfileRepository;
import com.javaclaw.agent.conversation.ProfileService;
import com.javaclaw.agent.kernel.AgentLoopKernel;
import com.javaclaw.agent.runtime.DefaultAgentRuntime;
import com.javaclaw.agent.runtime.RuntimeEventBus;
import com.javaclaw.agent.tool.TurnToolSession;
import com.javaclaw.core.api.ModelResponse;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ProfileKind;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.core.api.ToolExecutionResult;
import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sdk.model.ArtifactItemContent;
import com.javaclaw.sdk.model.PlanItemContent;
import com.javaclaw.sdk.model.ProfileInfo;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanAdoptionIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void requiresDecisionsAndVersionThenCreatesOneNormalExecutionTurnEvenAfterReconnectReplay() throws Exception {
        var calls = new AtomicInteger();
        var kernel = new AgentLoopKernel(
                request -> {
                    calls.incrementAndGet();
                    return new ModelResponse("""
                    {"goal":"保留 UI 完成升级","scope":"当前工作区","steps":["核对测试"],
                    "dependencies":[],"acceptanceCriteria":["测试通过"],"risks":[],
                    "openQuestions":["是否保留原配色？"]}
                    """, "", List.of(), new ModelUsage(10, 10, 0));
                },
                (context, sink) -> new TurnToolSession() {
                    @Override
                    public List<ToolDescriptor> availableTools() {
                        return List.of();
                    }

                    @Override
                    public ToolExecutionResult execute(ModelToolCall call) {
                        throw new AssertionError("本场景没有工具");
                    }
                });
        var events = new RuntimeEventBus();
        try (var persistence = new H2Persistence(temporary.resolve("data"));
                var runtime = new DefaultAgentRuntime(persistence.runtime(), kernel, events);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var profiles = new ProfileService(new H2ProfileRepository(persistence.database()), Set.of());
            for (var kind : List.of(ProfileKind.PLAN, ProfileKind.CHAT)) {
                profiles.put(
                        new ProfileRepository.ProfileDraft(
                                kind.name(),
                                kind.name(),
                                kind,
                                "openai",
                                "fake",
                                "",
                                Set.of(),
                                SandboxMode.READ_ONLY,
                                8,
                                8,
                                Map.of()),
                        0,
                        "seed-" + kind);
            }
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
                            new PlanAdoptionService(profiles, runtime, runtime, runtime),
                            null,
                            null,
                            null,
                            null,
                            null));
            var requests = Pipe.open();
            var responses = Pipe.open();
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
                client.initialize("plan-adoption", "1").join();
                var workspace = client.workspaces()
                        .create("test", temporary, "workspace")
                        .join();
                var thread =
                        client.threads().start(workspace.id(), "计划", "thread").join();
                var planTurn = client.threads()
                        .startTurn(new TurnStartRequest(
                                thread.id(), "PLAN", List.of(new TurnInput.Text("继续")), null, null, "plan"))
                        .join();
                var planned = client.threads()
                        .awaitTurn(thread.id(), planTurn.id(), Duration.ofSeconds(5))
                        .join();
                assertEquals("COMPLETED", planned.turns().getFirst().status());
                var plan = planned.items().stream()
                        .filter(item -> item.content() instanceof PlanItemContent)
                        .findFirst()
                        .orElseThrow();
                assertEquals(1, calls.get(), "继续仍然只生成计划");
                assertThrows(
                        RuntimeException.class,
                        () -> client.threads()
                                .adoptPlan(thread.id(), plan.id(), "CHAT", 1, "", "missing-decision")
                                .join());
                assertThrows(
                        RuntimeException.class,
                        () -> client.threads()
                                .adoptPlan(thread.id(), plan.id(), "CHAT", 2, "沿用原配色", "stale-profile")
                                .join());
                assertEquals(1, calls.get());
                var adopted = client.threads()
                        .adoptPlan(thread.id(), plan.id(), "CHAT", 1, "沿用原配色", "adopt")
                        .join();
                var completed = client.threads()
                        .awaitTurn(thread.id(), adopted.id(), Duration.ofSeconds(5))
                        .join();
                assertTrue(completed.items().stream()
                        .anyMatch(item -> item.content() instanceof ArtifactItemContent artifact
                                && artifact.category().equals("plan-adoption")));
                assertEquals(2, calls.get());
                ProfileInfo chat = client.models().readProfile("CHAT").join();
                client.models()
                        .putProfile(
                                new ProfileInfo(
                                        chat.id(),
                                        chat.name(),
                                        chat.kind(),
                                        chat.provider(),
                                        chat.model(),
                                        "新的人设",
                                        chat.enabledTools(),
                                        chat.requestedSandboxMode(),
                                        chat.maxIterations(),
                                        chat.maxModelCalls(),
                                        chat.attributes(),
                                        chat.revision(),
                                        chat.updatedAt()),
                                1,
                                "update-chat")
                        .join();
                assertEquals(
                        adopted.id(),
                        client.threads()
                                .adoptPlan(thread.id(), plan.id(), "CHAT", 1, "沿用原配色", "adopt")
                                .join()
                                .id());
                assertEquals(2, calls.get(), "同一幂等请求不能再计费");
                assertThrows(
                        RuntimeException.class,
                        () -> client.threads()
                                .adoptPlan(thread.id(), plan.id(), "CHAT", 1, "不同决定", "adopt")
                                .join());
            }
            serving.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
