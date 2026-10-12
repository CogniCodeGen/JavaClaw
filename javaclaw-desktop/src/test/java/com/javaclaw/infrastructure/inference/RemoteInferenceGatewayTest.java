package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.application.plugin.v2.PluginManagementService.*;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.inference.api.*;
import com.javaclaw.platform.data.DataRoot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class RemoteInferenceGatewayTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<Call> calls = new ArrayList<>();
    private final List<Lease> leases = new ArrayList<>();
    private final UUID profileId = UUID.randomUUID(), assetId = UUID.randomUUID();
    private RemoteInferenceGateway gateway;
    private boolean pendingChat;
    private volatile boolean pendingLoad;
    private volatile FakeInvocation lastLoad;
    private Throwable chatFailure;
    private FakeInvocation lastChat;
    private Consumer<PluginEvent> streamEvents;
    private int verified;
    private long currentRevision = 7;
    private InferenceModelProfile currentProfile;
    private InferenceModelAsset currentAsset;
    private final List<Long> exactRevisions = new ArrayList<>();

    @BeforeEach void initialize() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:remote-" + UUID.randomUUID(), "sa", "");
        WorkspaceManager workspaces = new WorkspaceManager(new DataRoot(temporary), new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource)) {
            @Override public String getCurrentWorkspaceId() { return "workspace-a"; }
        };
        currentProfile = new InferenceModelProfile(profileId, "fixture model", InferenceModelProfile.Kind.GENERATION,
                assetId, "example-model-plugin", Map.of(), Map.of(), 4096, 0,
                InferenceModelProfile.State.READY, "", Instant.now(), Instant.now());
        currentAsset = new InferenceModelAsset(assetId, InferenceModelAsset.Source.LOCAL_DIRECTORY, "fixture", "llama",
                "a".repeat(64), temporary.toString(), "", "", List.of(), 0, InferenceModelAsset.State.READY,
                "", Instant.now(), InferenceModelAsset.ArtifactMetadata.unknown(0));
        InferenceCatalogPort catalog = (InferenceCatalogPort) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{InferenceCatalogPort.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "profile" -> Optional.ofNullable(currentProfile);
                    case "asset" -> Optional.ofNullable(currentAsset);
                    case "publishedModels" -> List.of();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        PluginManagementService plugins = (PluginManagementService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginManagementService.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "acquireRun" -> {
                        long revision = args.length == 4 ? (long) args[3] : currentRevision;
                        if (args.length == 4) exactRevisions.add(revision);
                        var lease = new Lease(revision); leases.add(lease); yield lease;
                    }
                    case "invoke" -> invoke(args);
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        gateway = new RemoteInferenceGateway(plugins, workspaces, catalog, (value, cancelled) -> verified++, json);
    }

    @Test void routesAnyPluginAndLoadsWithinTheSameImmutableLease() throws Exception {
        var response = gateway.chat(request("request-a"));

        assertEquals("answer", response.content());
        assertEquals(new InferenceUsage(10, 5), response.usage());
        assertEquals(List.of("load", "chat"), calls.stream().map(Call::operation).toList());
        assertSame(calls.get(0).lease(), calls.get(1).lease());
        assertEquals("example-model-plugin", calls.get(0).lease().pluginId());
        assertEquals("custom-contribution", calls.get(1).contribution());
        assertEquals("MODEL", calls.get(1).capability());
        assertEquals("example-model-plugin", json.readTree(calls.getFirst().payload()).path("startup").path("runtimeId").asText());
        assertEquals(1, verified);
        assertTrue(leases.getFirst().closed);
    }

    @Test void streamForwardsDeltasAndCancellationClosesLeaseWithOneTerminal() throws Exception {
        pendingChat = true;
        List<InferenceStreamEvent> events = new ArrayList<>();
        var session = gateway.streamChat(request("stream-a"), events::add);
        assertFalse(leases.getFirst().closed);
        streamEvents.accept(new PluginEvent(lastChat.requestId(), 1, "DATA",
                "{\"requestId\":\"stream-a\",\"type\":\"CONTENT_DELTA\",\"text\":\"hello\"}"));
        assertTrue(session.cancel());
        assertThrows(java.util.concurrent.ExecutionException.class, () -> session.completion().get());
        assertEquals(List.of(InferenceStreamEvent.Type.STARTED, InferenceStreamEvent.Type.CONTENT_DELTA,
                InferenceStreamEvent.Type.CANCELLED), events.stream().map(InferenceStreamEvent::type).toList());
        assertTrue(leases.getFirst().closed);
        assertFalse(gateway.cancel("stream-a"));
        assertEquals(2, calls.size());
    }

    @Test void transportControlAndFinalResponseAreNotParsedAsBusinessDeltas() throws Exception {
        pendingChat = true;
        List<InferenceStreamEvent> events = new ArrayList<>();
        var session = gateway.streamChat(request("stream-final"), events::add);
        streamEvents.accept(new PluginEvent(lastChat.requestId(), 1, "DATA",
                "{\"type\":\"CONTENT_DELTA\",\"text\":\"hello\"}"));
        streamEvents.accept(new PluginEvent(lastChat.requestId(), 2, "DATA", "{\"content\":\"hello\",\"finishReason\":\"STOP\"}"));
        streamEvents.accept(new PluginEvent(lastChat.requestId(), 3, "COMPLETE", "{}"));
        lastChat.completion.complete(new InvocationResult(lastChat.requestId(),
                "{\"content\":\"hello\",\"finishReason\":\"STOP\"}"));

        assertEquals("hello", session.completion().get().content());
        assertEquals(List.of(InferenceStreamEvent.Type.STARTED, InferenceStreamEvent.Type.CONTENT_DELTA,
                InferenceStreamEvent.Type.COMPLETE), events.stream().map(InferenceStreamEvent::type).toList());
        assertTrue(leases.getFirst().closed);
    }

    @Test void unknownOutcomeIsNeverRetriedAndRemainsVisibleToCaller() {
        chatFailure = new PluginException("UNKNOWN_OUTCOME", "连接丢失，执行结果未知");

        var failure = assertThrows(LocalInferenceGateway.InferenceException.class, () -> gateway.chat(request("uncertain-a")));

        assertEquals("UNKNOWN_OUTCOME", failure.code());
        assertFalse(failure.retryable());
        assertEquals(1, calls.stream().filter(value -> value.operation().equals("chat")).count());
        assertTrue(leases.getFirst().closed);
    }

    @Test void cancellationDuringColdLoadNeverStartsTheChat() throws Exception {
        pendingLoad = true;
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var response = executor.submit(() -> gateway.chat(request("cold-a")));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (lastLoad == null && System.nanoTime() < deadline) Thread.sleep(10);
            assertNotNull(lastLoad);
            assertTrue(gateway.cancel("cold-a"));
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> response.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("cancelled", ((LocalInferenceGateway.InferenceException) failure.getCause()).code());
            assertEquals(List.of("load"), calls.stream().map(Call::operation).toList());
            assertTrue(lastLoad.completion.isCompletedExceptionally());
            assertTrue(leases.getFirst().closed);
        }
    }

    @Test void configurationUpgradeKeepsOldRunOnItsPinnedInstanceAndMissingLockFailsClosed() throws Exception {
        LocalInferenceGateway oldRun = gateway.forSnapshots(List.of(new InferenceProviderSnapshot(
                "workspace-a", "example-model-plugin", "a".repeat(64), 7, frozenConfiguration())));
        oldRun.chat(request("old-before"));
        currentRevision = 8;
        oldRun.chat(request("old-after"));
        gateway.chat(request("new-run"));

        assertEquals(List.of(7L, 7L), exactRevisions);
        assertEquals(List.of(7L, 7L, 8L), leases.stream().map(Lease::configurationRevision).toList());
        assertEquals(List.of("instance-7", "instance-7", "instance-8"), leases.stream().map(Lease::instanceId).toList());
        assertEquals(2, calls.stream().filter(value -> value.operation().equals("load")).count());
        int acquired = leases.size();
        var failure = assertThrows(LocalInferenceGateway.InferenceException.class,
                () -> gateway.forSnapshots(List.of()).chat(request("unbound")));
        assertEquals("model_snapshot_missing", failure.code());
        assertEquals(acquired, leases.size());
    }

    @Test void profileEditsCannotChangeAnOldRunAndDifferentFrozenConfigurationsDoNotShareLoadedModels() throws Exception {
        LocalInferenceGateway oldRun = gateway.forSnapshots(List.of(new InferenceProviderSnapshot(
                "workspace-a", "example-model-plugin", "a".repeat(64), 7, frozenConfiguration())));
        oldRun.chat(request("original-profile"));
        var old = currentProfile;
        currentProfile = new InferenceModelProfile(old.id(), old.name(), old.kind(), old.assetId(), old.runtimeId(),
                Map.of("gpuLayers", 3), Map.of("temperature", 0.2), 8192, old.embeddingDimensions(), old.state(), "", old.createdAt(), Instant.now());
        LocalInferenceGateway newRun = gateway.forSnapshots(List.of(new InferenceProviderSnapshot(
                "workspace-a", "example-model-plugin", "a".repeat(64), 7, frozenConfiguration())));
        newRun.chat(request("changed-profile"));
        currentProfile = null; currentAsset = null;
        oldRun.chat(request("old-after-delete"));
        List<Call> loads = calls.stream().filter(call -> call.operation().equals("load")).toList();
        assertEquals(2, loads.size());
        var original = json.readTree(loads.getFirst().payload()); var changed = json.readTree(loads.getLast().payload());
        assertEquals(4096, original.path("startup").path("contextLength").asInt());
        assertEquals(8192, changed.path("startup").path("contextLength").asInt());
        assertEquals(3, changed.path("startup").path("loadParameters").path("gpuLayers").asInt());
        assertNotEquals(original.path("profileId").asText(), changed.path("profileId").asText());
        assertNotEquals(profileId.toString(), original.path("profileId").asText());
        assertEquals(original.path("profileId"), json.readTree(calls.getLast().payload()).path("profileId"));
    }
    private String frozenConfiguration() throws Exception {
        return json.writeValueAsString(Map.of("profiles", List.of(currentProfile), "assets", List.of(currentAsset)));
    }

    @SuppressWarnings("unchecked")
    private FakeInvocation invoke(Object[] args) {
        Lease lease = (Lease) args[0]; String capability = (String) args[1], contribution = (String) args[2];
        String operation = (String) args[3], payload = (String) args[4];
        calls.add(new Call(lease, capability, contribution, operation, payload));
        var result = new FakeInvocation();
        if (operation.equals("chat")) {
            lastChat = result; streamEvents = (Consumer<PluginEvent>) args[6];
            if (chatFailure != null) result.completion.completeExceptionally(chatFailure);
            else if (!pendingChat) result.completion.complete(new InvocationResult(result.requestId(),
                    "{\"requestId\":\"request-a\",\"model\":\"fixture\",\"content\":\"answer\",\"finishReason\":\"STOP\","
                            + "\"usage\":{\"promptTokens\":10,\"completionTokens\":5},\"queueTimeMs\":1,\"inferenceTimeMs\":2}"));
        } else if (operation.equals("load") && pendingLoad) lastLoad = result;
        else result.completion.complete(new InvocationResult(result.requestId(),
                "{\"model\":{\"actualContextLength\":4096,\"actualEmbeddingDimensions\":0,\"selectedBackend\":\"cpu\",\"capabilities\":[\"chat\"]}}"));
        return result;
    }
    private InferenceChatRequest request(String id) {
        return new InferenceChatRequest(id, profileId,
                List.of(new InferenceMessage(InferenceMessage.Role.USER, "hi", "", "", List.of())),
                List.of(), Map.of(), Duration.ofSeconds(5));
    }
    private record Call(Lease lease, String capability, String contribution, String operation, String payload) { }
    private static final class FakeInvocation implements PluginInvocation {
        private final String id = UUID.randomUUID().toString();
        private final CompletableFuture<InvocationResult> completion = new CompletableFuture<>();
        @Override public String requestId() { return id; }
        @Override public CompletableFuture<InvocationResult> completion() { return completion; }
        @Override public boolean cancel() { return completion.completeExceptionally(new java.util.concurrent.CancellationException("已取消")); }
    }
    private static final class Lease implements RunLease {
        private boolean closed;
        private final long revision;
        Lease(long revision) { this.revision = revision; }
        @Override public String runId() { return "run-a"; }
        @Override public String workspaceId() { return "workspace-a"; }
        @Override public String pluginId() { return "example-model-plugin"; }
        @Override public String artifactHash() { return "a".repeat(64); }
        @Override public long configurationRevision() { return revision; }
        @Override public String instanceId() { return "instance-" + revision; }
        @Override public Set<String> grantedPermissions() { return Set.of(); }
        @Override public List<ContributionInfo> contributions() {
            return List.of(new ContributionInfo("custom-contribution", "MODEL", "2.0", "Model", "", "{}"));
        }
        @Override public String manifestJson() { return "{}"; }
        @Override public void close() { closed = true; }
    }
}
