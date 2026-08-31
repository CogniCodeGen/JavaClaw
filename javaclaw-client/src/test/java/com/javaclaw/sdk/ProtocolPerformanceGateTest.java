package com.javaclaw.sdk;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.conversation.ProfileRepository;
import com.javaclaw.agent.conversation.ProfileService;
import com.javaclaw.agent.kernel.AgentKernel;
import com.javaclaw.agent.kernel.AgentLoopKernel;
import com.javaclaw.agent.model.ModelGateway;
import com.javaclaw.agent.prompt.PromptHashes;
import com.javaclaw.agent.runtime.DefaultAgentRuntime;
import com.javaclaw.agent.runtime.RuntimeEventBus;
import com.javaclaw.agent.tool.FirstPartyToolProvider;
import com.javaclaw.agent.tool.GovernedToolRuntime;
import com.javaclaw.agent.tool.RegisteredTool;
import com.javaclaw.agent.tool.ToolHandler;
import com.javaclaw.agent.tool.ToolOrigin;
import com.javaclaw.agent.tool.ToolRisk;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ModelRequest;
import com.javaclaw.core.api.ModelResponse;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ProfileKind;
import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.core.api.TurnStatus;
import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.server.configuration.ServerConfiguration;
import com.javaclaw.server.discovery.ServerDiscovery;
import com.javaclaw.server.persistence.H2Persistence;
import com.javaclaw.server.persistence.H2ProfileRepository;
import com.javaclaw.server.transport.AppServerEndpointConfig;
import com.javaclaw.server.transport.ServerUseCases;
import com.javaclaw.server.transport.StdioAppServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixed-fake-model release gate for transport overhead, deliberately excluded from quick tests. */
@EnabledIfSystemProperty(named = "javaclaw.performance.gate", matches = "true")
class ProtocolPerformanceGateTest {
    private static final int WARMUPS = 4;
    private static final int SAMPLES = 40;
    private static final Duration COMPLETION_TIMEOUT = Duration.ofSeconds(5);
    private static final long FAKE_MODEL_MILLIS = 120;

    @TempDir
    Path temporary;

    @Test
    void sdkAppServerP50AndP95AddNoMoreThanTenPercent() throws Exception {
        Map<String, Set<String>> promptFingerprints = new ConcurrentHashMap<>();
        ModelGateway model = request -> {
            boolean toolScenario = request.messages().stream()
                    .anyMatch(message -> message.role() == ModelMessage.Role.USER
                            && message.content().equals("tool: run"));
            boolean toolCompleted = request.messages().getLast().role() == ModelMessage.Role.TOOL;
            String phase = toolScenario ? (toolCompleted ? "tool-result" : "tool-call") : "ordinary";
            promptFingerprints
                    .computeIfAbsent(phase, ignored -> ConcurrentHashMap.newKeySet())
                    .add(fingerprint(request));
            // 普通 Turn 一次 120ms；工具 Turn 两次各 60ms。只固定 Provider 延时，其余均走生产实现。
            Thread.sleep(toolScenario ? FAKE_MODEL_MILLIS / 2 : FAKE_MODEL_MILLIS);
            return new ModelResponse(
                    toolScenario && !toolCompleted ? "" : "ok",
                    "",
                    toolScenario && !toolCompleted
                            ? List.of(new ModelToolCall("fake_call", "fake_read", "{}"))
                            : List.of(),
                    new ModelUsage(100, 10, 0));
        };

        List<Result> results = new ArrayList<>();
        try (GovernedToolRuntime tools = governedTools();
                DirectHarness direct =
                        new DirectHarness(temporary.resolve("direct-data-v4"), new AgentLoopKernel(model, tools));
                ProtocolHarness protocol =
                        new ProtocolHarness(temporary.resolve("protocol-data-v4"), new AgentLoopKernel(model, tools))) {
            for (String scenario : List.of("ordinary", "tool")) {
                String prompt = scenario.equals("tool") ? "tool: run" : "answer";
                for (int index = 0; index < WARMUPS; index++) {
                    direct.measure(prompt);
                    protocol.measure(prompt);
                }
                List<Long> directNanos = new ArrayList<>();
                List<Long> protocolNanos = new ArrayList<>();
                for (int index = 0; index < SAMPLES; index++) {
                    // Alternate order so neither path systematically receives the GC, JIT or
                    // thermal state that follows the other. Forty samples also makes nearest-rank
                    // p95 a real percentile instead of the single maximum used by ten samples.
                    if ((index & 1) == 0) {
                        directNanos.add(direct.measure(prompt));
                        protocolNanos.add(protocol.measure(prompt));
                    } else {
                        protocolNanos.add(protocol.measure(prompt));
                        directNanos.add(direct.measure(prompt));
                    }
                }
                Result result = new Result(scenario, statistics(directNanos), statistics(protocolNanos));
                results.add(result);
                assertWithinTenPercent(result, "p50", result.direct.p50, result.protocol.p50);
                assertWithinTenPercent(result, "p95", result.direct.p95, result.protocol.p95);
            }
        }

        assertEquals(Set.of("ordinary", "tool-call", "tool-result"), promptFingerprints.keySet());
        promptFingerprints.forEach((phase, hashes) -> assertEquals(
                1, hashes.size(), "direct and SDK paths must compile identical model inputs for " + phase));
        writeReport(results);
    }

    private GovernedToolRuntime governedTools() {
        var ceiling = SandboxPolicy.readOnly(Set.of(temporary), Set.of());
        var tool = new RegisteredTool(
                        new ToolDescriptor(
                                "fake_read",
                                "Read a deterministic test value.",
                                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
                        ToolOrigin.BUILTIN,
                        ToolRisk.LOW,
                        false,
                        ceiling,
                        invocation -> new ToolHandler.Result(
                                new ThreadItem.DynamicToolCall("fake_read", Map.of("result", "ok")), "ok"))
                .readOnly();
        return new GovernedToolRuntime(
                List.of(new FirstPartyToolProvider("performance", List.of(tool))),
                List.of(),
                List.of(),
                null,
                command -> {
                    throw new AssertionError("performance fake must not launch a real process");
                },
                ceiling,
                Duration.ofSeconds(2),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static String fingerprint(ModelRequest request) {
        List<String> values = new ArrayList<>();
        request.messages().forEach(message -> {
            values.add(message.role().name());
            values.add(message.content());
            values.add(message.toolCallId() == null ? "" : message.toolCallId());
            message.toolCalls().forEach(call -> values.addAll(List.of(call.id(), call.name(), call.argumentsJson())));
        });
        request.tools()
                .forEach(tool -> values.addAll(List.of(tool.name(), tool.description(), tool.inputSchemaJson())));
        return PromptHashes.sequence(values);
    }

    private static ProfileService profiles(H2Persistence persistence) {
        var profiles = new ProfileService(new H2ProfileRepository(persistence.database()), Set.of());
        profiles.put(
                new ProfileRepository.ProfileDraft(
                        "profile_chat",
                        "Benchmark",
                        ProfileKind.CHAT,
                        "fake",
                        "fake",
                        "",
                        Set.of("fake_read"),
                        SandboxMode.READ_ONLY,
                        4,
                        4,
                        Map.of()),
                0,
                "benchmark-profile");
        return profiles;
    }

    private static void assertWithinTenPercent(Result result, String percentile, double direct, double protocol) {
        double limit = direct * 1.10d;
        assertTrue(
                protocol <= limit,
                () -> result.scenario + " " + percentile
                        + " protocol overhead exceeded 10%: direct=" + direct
                        + "ms protocol=" + protocol + "ms limit=" + limit + "ms");
    }

    private void writeReport(List<Result> results) throws Exception {
        StringBuilder json = new StringBuilder("{\n  \"format\": \"javaclaw-performance-v1\",\n")
                .append("  \"kernel\": \"AgentLoopKernel\",\n  \"identicalCompiledInputs\": true,\n")
                .append("  \"fakeModelMillis\": ")
                .append(FAKE_MODEL_MILLIS)
                .append(",\n")
                .append("  \"warmups\": ")
                .append(WARMUPS)
                .append(",\n")
                .append("  \"samples\": ")
                .append(SAMPLES)
                .append(",\n")
                .append("  \"maximumProtocolOverheadPercent\": 10.0,\n")
                .append("  \"results\": [\n");
        for (int index = 0; index < results.size(); index++) {
            Result result = results.get(index);
            if (index > 0) {
                json.append(",\n");
            }
            json.append("    {\"scenario\": \"")
                    .append(result.scenario)
                    .append("\", \"directP50Millis\": ")
                    .append(format(result.direct.p50))
                    .append(", \"directP95Millis\": ")
                    .append(format(result.direct.p95))
                    .append(", \"protocolP50Millis\": ")
                    .append(format(result.protocol.p50))
                    .append(", \"protocolP95Millis\": ")
                    .append(format(result.protocol.p95))
                    .append("}");
        }
        json.append("\n  ]\n}\n");
        Path target = Path.of("target", "performance-gate.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, json, StandardCharsets.UTF_8);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static Statistics statistics(List<Long> samples) {
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        return new Statistics(milliseconds(percentile(sorted, 0.50d)), milliseconds(percentile(sorted, 0.95d)));
    }

    private static long percentile(List<Long> sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index);
    }

    private static double milliseconds(long nanos) {
        return nanos / 1_000_000d;
    }

    private record Statistics(double p50, double p95) {}

    private record Result(String scenario, Statistics direct, Statistics protocol) {}

    private final class DirectHarness implements AutoCloseable {
        private final H2Persistence persistence;
        private final ProfileService profiles;
        private final RuntimeEventBus events = new RuntimeEventBus(2_048);
        private final DefaultAgentRuntime service;
        private final CompletionProbe completions = new CompletionProbe();
        private final String workspaceId;

        private DirectHarness(Path dataRoot, AgentKernel kernel) {
            persistence = new H2Persistence(dataRoot);
            profiles = profiles(persistence);
            service = new DefaultAgentRuntime(persistence.runtime(), kernel, events);
            events.subscribe(completions);
            workspaceId = service.createWorkspace("direct", temporary, "performance-direct-workspace")
                    .id()
                    .value();
        }

        private long measure(String prompt) throws Exception {
            AgentThread thread = service.startThread(new com.javaclaw.core.api.WorkspaceId(workspaceId), "sample");
            CountDownLatch completed = completions.expect(thread.id().value());
            TurnConfig config = profiles.resolve(
                            "profile_chat",
                            service.readWorkspace(new com.javaclaw.core.api.WorkspaceId(workspaceId))
                                    .orElseThrow(),
                            ApprovalPolicy.NEVER,
                            "medium")
                    .turnConfig();
            long started = System.nanoTime();
            var turn = service.startTurn(
                    new TurnStartCommand(thread.id(), List.of(new TurnInput.Text(prompt)), config, null));
            assertTrue(
                    completed.await(COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "direct Turn did not complete");
            long elapsed = System.nanoTime() - started;
            assertEquals(
                    TurnStatus.COMPLETED,
                    service.readTurn(turn.id()).orElseThrow().status());
            assertEquals(
                    prompt.startsWith("tool:")
                            ? List.of("userMessage", "dynamicToolCall", "agentMessage")
                            : List.of("userMessage", "agentMessage"),
                    service.readThread(thread.id()).orElseThrow().items().stream()
                            .map(item -> item.kind())
                            .toList());
            return elapsed;
        }

        @Override
        public void close() {
            service.close();
            persistence.close();
        }
    }

    private final class ProtocolHarness implements AutoCloseable {
        private final H2Persistence persistence;
        private final RuntimeEventBus events = new RuntimeEventBus(2_048);
        private final DefaultAgentRuntime service;
        private final ExecutorService serverTask = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        private final JavaClawClient client;
        private final ProtocolCompletionProbe completions = new ProtocolCompletionProbe();
        private final String workspaceId;

        private ProtocolHarness(Path dataRoot, AgentKernel kernel) throws Exception {
            persistence = new H2Persistence(dataRoot);
            var profiles = profiles(persistence);
            service = new DefaultAgentRuntime(persistence.runtime(), kernel, events);
            var codec = new JsonRpcCodec();
            var endpoint = new AppServerEndpointConfig(
                    service,
                    events,
                    codec,
                    StdioAppServer.DEFAULT_MAX_FRAME_CHARS,
                    null,
                    null,
                    ServerDiscovery.EMPTY,
                    false,
                    ServerConfiguration.inMemory(codec.mapper()),
                    persistence.attachments(),
                    service.liveItemEvents(),
                    new ServerUseCases(
                            profiles, null, null, null, null, null, null, null, null, null, null, null, null, null,
                            null));
            Pipe requests = Pipe.open();
            Pipe responses = Pipe.open();
            var serverInput = Channels.newInputStream(requests.source());
            var clientOutput = Channels.newOutputStream(requests.sink());
            var clientInput = Channels.newInputStream(responses.source());
            var serverOutput = Channels.newOutputStream(responses.sink());
            serverTask.submit(() -> {
                try (serverInput;
                        serverOutput) {
                    new StdioAppServer(endpoint)
                            .serve(
                                    new InputStreamReader(serverInput, StandardCharsets.UTF_8),
                                    new OutputStreamWriter(serverOutput, StandardCharsets.UTF_8));
                }
                return null;
            });
            client = new JavaClawClient(new JsonRpcConnection(clientInput, clientOutput));
            client.onNotification(completions::accept);
            client.initialize("performance-gate", "4.0.0").join();
            workspaceId = client.workspaces()
                    .create("protocol", temporary, "performance-protocol-workspace")
                    .join()
                    .id();
        }

        private long measure(String prompt) throws Exception {
            String threadId =
                    client.threads().start(workspaceId, "sample", null).join().id();
            CountDownLatch completed = completions.expect(threadId);
            long started = System.nanoTime();
            client.threads()
                    .startTurn(new com.javaclaw.sdk.model.TurnStartRequest(
                            threadId,
                            "profile_chat",
                            List.of(new com.javaclaw.sdk.model.TurnInput.Text(prompt)),
                            com.javaclaw.sdk.model.TurnStartRequest.ApprovalMode.DENY_ALL,
                            com.javaclaw.sdk.model.TurnStartRequest.ReasoningMode.PROFILE_DEFAULT,
                            null))
                    .join();
            assertTrue(
                    completed.await(COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "SDK/App Server Turn did not complete");
            long elapsed = System.nanoTime() - started;
            var snapshot = client.threads().read(threadId).join();
            assertEquals("COMPLETED", snapshot.turns().getLast().status());
            assertEquals(
                    prompt.startsWith("tool:")
                            ? List.of("userMessage", "dynamicToolCall", "agentMessage")
                            : List.of("userMessage", "agentMessage"),
                    snapshot.items().stream().map(item -> item.content().kind()).toList());
            return elapsed;
        }

        @Override
        public void close() {
            client.close();
            serverTask.shutdownNow();
            service.close();
            persistence.close();
        }
    }

    private static final class CompletionProbe implements Flow.Subscriber<ThreadEvent> {
        private final ConcurrentHashMap<String, CountDownLatch> expected = new ConcurrentHashMap<>();

        private CountDownLatch expect(String threadId) {
            CountDownLatch latch = new CountDownLatch(1);
            expected.put(threadId, latch);
            return latch;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ThreadEvent event) {
            if (!"turn/completed".equals(event.type())) {
                return;
            }
            CountDownLatch latch = expected.remove(event.threadId().value());
            if (latch != null) {
                latch.countDown();
            }
        }

        @Override
        public void onError(Throwable throwable) {}

        @Override
        public void onComplete() {}
    }

    private static final class ProtocolCompletionProbe {
        private final ConcurrentHashMap<String, CountDownLatch> expected = new ConcurrentHashMap<>();

        private CountDownLatch expect(String threadId) {
            CountDownLatch latch = new CountDownLatch(1);
            expected.put(threadId, latch);
            return latch;
        }

        private void accept(ClientNotification notification) {
            if (!(notification instanceof EventNotification event)
                    || !"turn/completed".equals(event.event().type())) {
                return;
            }
            String threadId = event.event().threadId();
            CountDownLatch latch = expected.remove(threadId);
            if (latch != null) {
                latch.countDown();
            }
        }
    }
}
