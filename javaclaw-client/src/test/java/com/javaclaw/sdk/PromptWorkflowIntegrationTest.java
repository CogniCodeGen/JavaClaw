package com.javaclaw.sdk;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.conversation.ProfilePromptService;
import com.javaclaw.agent.conversation.ProfileRepository;
import com.javaclaw.agent.conversation.ProfileService;
import com.javaclaw.agent.kernel.AgentLoopKernel;
import com.javaclaw.agent.prompt.AgentsInstructionResolver;
import com.javaclaw.agent.prompt.AgentsInstructionSettings;
import com.javaclaw.agent.runtime.DefaultAgentRuntime;
import com.javaclaw.agent.runtime.RuntimeEventBus;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ModelResponse;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ProfileKind;
import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sdk.model.ProfileInfo;
import com.javaclaw.sdk.model.PromptDraftItemContent;
import com.javaclaw.server.configuration.ServerConfiguration;
import com.javaclaw.server.discovery.ServerDiscovery;
import com.javaclaw.server.persistence.H2Persistence;
import com.javaclaw.server.persistence.H2ProfileRepository;
import com.javaclaw.server.transport.AppServerEndpointConfig;
import com.javaclaw.server.transport.ServerUseCases;
import com.javaclaw.server.transport.StdioAppServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptWorkflowIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void resolvesAgentsMetadataAndOptimizesPromptsWithoutLeakingWireTypes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var kernel = new AgentLoopKernel(
                request -> {
                    calls.incrementAndGet();
                    assertTrue(request.tools().isEmpty());
                    assertFalse(request.messages().stream()
                            .anyMatch(message -> message.role() == ModelMessage.Role.SYSTEM
                                    && message.content().contains("PRIVATE_RULE")));
                    return new ModelResponse(
                            "{\"draft\":\"保留原 UI，先核验后执行\",\"changes\":[\"明确证据要求\"],\"warnings\":[]}",
                            "",
                            List.of(),
                            new ModelUsage(20, 10, 0));
                },
                (turn, sink) -> {
                    throw new AssertionError("prompt task opened a tool provider");
                });
        var events = new RuntimeEventBus();
        Path configuration = Files.createDirectories(temporary.resolve("config"));
        var resolver = new AgentsInstructionResolver(configuration, AgentsInstructionSettings::defaults);
        try (var persistence = new H2Persistence(temporary.resolve("data-v4"));
                var runtime = new DefaultAgentRuntime(persistence.runtime(), kernel, events);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var profiles = new ProfileService(new H2ProfileRepository(persistence.database()), Set.of());
            profiles.put(
                    new ProfileRepository.ProfileDraft(
                            "author",
                            "写作助手",
                            ProfileKind.CHAT,
                            "openai",
                            "fake",
                            "原草稿\n",
                            Set.of(),
                            SandboxMode.READ_ONLY,
                            8,
                            8,
                            Map.of()),
                    0,
                    "seed");
            var prompts = new ProfilePromptService(profiles, runtime, runtime, runtime, List::of);
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
                            resolver,
                            prompts,
                            new com.javaclaw.agent.conversation.PlanAdoptionService(
                                    profiles, runtime, runtime, runtime),
                            null,
                            null,
                            null,
                            null,
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
                client.initialize("prompt-test", "1").join();
                Path agents = temporary.resolve("AGENTS.md");
                Files.writeString(agents, "PRIVATE_RULE：沿用原 UI");
                var workspace = client.workspaces()
                        .create("workspace", temporary, "workspace")
                        .join();
                var effective =
                        client.workspaces().resolveInstructions(workspace.id()).join();
                assertEquals(temporary.toRealPath(), effective.workingDirectory());
                assertEquals(agents.toRealPath(), effective.sources().getFirst().path());
                assertEquals(Files.size(agents), effective.totalProjectBytes());
                assertEquals(64, effective.sources().getFirst().sha256().length());

                var preview =
                        client.models().previewPrompt("author", workspace.id()).join();
                assertEquals("原草稿\n", preview.editablePrompt());
                assertTrue(
                        preview.layers().stream().anyMatch(layer -> layer.id().equals("base")));
                assertEquals(0, calls.get());
                var thread = client.threads()
                        .start(workspace.id(), "提示词草稿", "draft-thread")
                        .join();
                var turn = client.models()
                        .optimizePrompt(thread.id(), "author", "原草稿\n", 1, "optimize")
                        .join();
                var completed = client.threads()
                        .awaitTurn(thread.id(), turn.id(), Duration.ofSeconds(5))
                        .join();
                assertEquals("COMPLETED", completed.turns().getFirst().status());
                var draft = completed.items().stream()
                        .map(item -> item.content())
                        .filter(PromptDraftItemContent.class::isInstance)
                        .map(PromptDraftItemContent.class::cast)
                        .findFirst()
                        .orElseThrow();
                assertEquals("author", draft.profileId());
                ProfileInfo original = client.models().readProfile("author").join();
                assertEquals("原草稿\n", original.systemPrompt());
                assertEquals(1, calls.get());
                var saved = client.models()
                        .putProfile(withPrompt(original, draft.draft()), draft.expectedRevision(), "save")
                        .join();
                assertEquals(2, saved.revision());
                assertEquals(
                        turn.id(),
                        client.models()
                                .optimizePrompt(thread.id(), "author", "原草稿\n", 1, "optimize")
                                .join()
                                .id());
                assertEquals(1, calls.get());
                assertThrows(
                        RuntimeException.class,
                        () -> client.models()
                                .putProfile(withPrompt(original, "stale"), 1, "stale")
                                .join());
            }
            serving.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static ProfileInfo withPrompt(ProfileInfo value, String prompt) {
        return new ProfileInfo(
                value.id(),
                value.name(),
                value.kind(),
                value.provider(),
                value.model(),
                prompt,
                value.enabledTools(),
                value.requestedSandboxMode(),
                value.maxIterations(),
                value.maxModelCalls(),
                value.attributes(),
                value.revision(),
                value.updatedAt());
    }
}
