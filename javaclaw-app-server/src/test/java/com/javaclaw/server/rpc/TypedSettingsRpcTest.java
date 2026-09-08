package com.javaclaw.server.rpc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CapabilityNarrowing;
import com.javaclaw.api.DiagnosticsSnapshot;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ModelPreference;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderReadiness;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderStatus;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.AgentRoleRpcContracts;
import com.javaclaw.protocol.AttachmentRpcContracts;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.DocumentPreviewRpcContracts;
import com.javaclaw.protocol.ExecutionRpcContracts;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.MethodCatalog;
import com.javaclaw.protocol.PermissionProfileRpcContracts;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.ProviderRpcContracts;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.RpcMethodKind;
import com.javaclaw.protocol.TurnStreamRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.server.AppServerBootstrap;
import com.javaclaw.server.ProviderEndpointTestFixtures;
import com.javaclaw.server.testkit.AttachmentRpcTestClient;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedSettingsRpcTest {
    @TempDir
    Path temporaryDirectory;

    private AppServerBootstrap.Components components;
    private AppServerSession session;

    @BeforeEach
    void createSession() {
        components =
                AppServerBootstrap.create(temporaryDirectory.resolve("data-v6"), Clock.systemUTC(), new StubModel());
        session = components.newSession();
        initialize();
    }

    @AfterEach
    void closeComponents() throws Exception {
        components.close();
    }

    @Test
    void typedSettingsAndPlatformRoutesFormCompleteRpcVerticalChain() {
        assertAttachmentRoundTrip();
        ProviderEndpoint provider = createProvider();
        assertProviderQueries(provider);
        AgentRole profile = createProfile(provider);
        assertProfileQueries(profile);
        assertWorkspaceBinding(profile);
        assertDiagnosticsAndCatalog();
    }

    private void assertAttachmentRoundTrip() {
        byte[] bytes = "protocol-v2".getBytes(StandardCharsets.UTF_8);
        AttachmentMetadata attachment = AttachmentRpcTestClient.upload(
                session, components.json(), "attachment-key", AttachmentScope.global(), "text/plain", bytes);
        AttachmentContent content = decodeSuccess(
                session.handle(request(
                        "attachment-read",
                        "attachment/read",
                        new AttachmentRpcContracts.ReadPayload(AttachmentScope.global(), attachment.digest()))),
                AttachmentContent.class);

        assertArrayEquals(bytes, content.content());
    }

    private ProviderEndpoint createProvider() {
        ProviderEndpointSpec spec =
                ProviderEndpointTestFixtures.chat("OpenAI Compatible", ProviderAdapter.OPENAI_COMPATIBLE, "test-model");
        return decodeSuccess(
                session.handle(request(
                        "provider-create",
                        "provider/create",
                        command(
                                "provider-key",
                                new ProviderRpcContracts.ProviderCreatePayload(
                                        "openai", spec, com.javaclaw.api.ProviderLifecycle.ACTIVE)))),
                ProviderEndpoint.class);
    }

    private void assertProviderQueries(ProviderEndpoint provider) {
        ProviderEndpoint providerRead = decodeSuccess(
                session.handle(request(
                        "provider-read",
                        "provider/read",
                        new ProviderRpcContracts.ProviderReadPayload(provider.id(), provider.revision()))),
                ProviderEndpoint.class);
        ProviderRpcContracts.ProviderListResult providers = decodeSuccess(
                session.handle(request("provider-list", "provider/list", new Empty())),
                ProviderRpcContracts.ProviderListResult.class);
        ProviderStatus status = providerStatus("provider-status", "provider/status", provider);
        ProviderStatus probe = providerStatus("provider-probe", "provider/probe", provider);

        assertEquals(provider, providerRead);
        assertEquals(List.of(provider), providers.providers());
        assertEquals(ProviderReadiness.READY, status.readiness());
        assertEquals(status.readiness(), probe.readiness());
        assertEquals("openai", provider.id());
    }

    private ProviderStatus providerStatus(String requestId, String method, ProviderEndpoint provider) {
        ProviderRef providerRef = new ProviderRef(provider.id(), provider.revision(), "test-model");
        return decodeSuccess(
                session.handle(request(requestId, method, new ProviderRpcContracts.ProviderProbePayload(providerRef))),
                ProviderStatus.class);
    }

    private AgentRole createProfile(ProviderEndpoint provider) {
        PermissionProfile permission = decodeSuccess(
                        session.handle(request("permission-list", "permissionProfile/list", new Empty())),
                        PermissionProfileRpcContracts.ListResult.class)
                .profiles()
                .getFirst();
        AgentRoleSpec spec = new AgentRoleSpec(
                "Architect",
                "",
                "保持架构边界清晰。",
                Optional.of(new ModelPreference(new ProviderRef(provider.id(), provider.revision(), "test-model"))),
                Optional.empty(),
                new CapabilityNarrowing(Optional.of(Set.of()), Optional.empty()),
                PermissionConstraint.INHERIT,
                java.util.Map.of());
        return decodeSuccess(
                session.handle(request(
                        "profile-create",
                        "agent/role/create",
                        command("profile-key", new AgentRoleRpcContracts.CreatePayload("architect", spec)))),
                AgentRole.class);
    }

    private void assertProfileQueries(AgentRole profile) {
        AgentRole profileRead = decodeSuccess(
                session.handle(request(
                        "profile-read", "agent/role/read", new AgentRoleRpcContracts.ReadPayload(profile.ref()))),
                AgentRole.class);
        AgentRoleRpcContracts.ListResult profiles = decodeSuccess(
                session.handle(request("profile-list", "agent/role/list", new Empty())),
                AgentRoleRpcContracts.ListResult.class);

        assertEquals(profile, profileRead);
        assertTrue(profiles.roles().contains(profile));
        assertEquals(5, profiles.roles().size());
    }

    private void assertWorkspaceBinding(AgentRole profile) {
        Workspace workspace = decodeSuccess(
                session.handle(request(
                        "settings-workspace",
                        "workspace/create",
                        command(
                                "settings-workspace-key",
                                new CoreRpcContracts.WorkspaceCreatePayload(
                                        "Settings", temporaryDirectory.resolve("settings-workspace"))))),
                Workspace.class);
        ExecutionOverrides execution = new ExecutionOverrides(
                Optional.of(profile.ref()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        ExecutionRpcContracts.DefaultUpdatePayload payload =
                new ExecutionRpcContracts.DefaultUpdatePayload(Optional.of(workspace.id()), execution);
        ExecutionConfiguration configured = decodeSuccess(
                session.handle(
                        request("execution-update", "execution/default/update", command("execution-key", payload))),
                ExecutionConfiguration.class);
        ExecutionRpcContracts.ReadResult read = decodeSuccess(
                session.handle(request(
                        "execution-read",
                        "execution/default/read",
                        new ExecutionRpcContracts.DefaultReadPayload(Optional.of(workspace.id())))),
                ExecutionRpcContracts.ReadResult.class);
        assertEquals(configured, read.configuration().orElseThrow());
    }

    private void assertDiagnosticsAndCatalog() {
        DiagnosticsSnapshot diagnostics = decodeSuccess(
                session.handle(request("diagnostics", "diagnostics/read", new Empty())), DiagnosticsSnapshot.class);
        Set<String> callableCatalog = MethodCatalog.methods().stream()
                .filter(method -> method.kind() != RpcMethodKind.NOTIFICATION)
                .map(com.javaclaw.protocol.RpcMethod::name)
                .filter(method -> !"initialize/session".equals(method))
                .collect(Collectors.toUnmodifiableSet());

        assertTrue(diagnostics.health().databaseHealthy());
        assertEquals(10, diagnostics.health().extensionCount());
        Set<String> connectionMethods = Set.of(
                TurnStreamRpcContracts.SUBSCRIBE,
                TurnStreamRpcContracts.UNSUBSCRIBE,
                TurnStreamRpcContracts.LIST,
                TurnStreamRpcContracts.ITEM_HISTORY,
                DocumentPreviewRpcContracts.RESOLVE,
                DocumentPreviewRpcContracts.RESOURCE,
                DocumentPreviewRpcContracts.READ,
                DocumentPreviewRpcContracts.RENEW,
                DocumentPreviewRpcContracts.CLOSE);
        assertTrue(java.util.Collections.disjoint(
                connectionMethods, components.router().implementedMethods()));
        Set<String> implemented = java.util.stream.Stream.concat(
                        components.router().implementedMethods().stream(), connectionMethods.stream())
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(callableCatalog, implemented);
    }

    private void initialize() {
        InitializeParams params = new InitializeParams(
                ProtocolVersion.CURRENT,
                new ClientInfo("typed-settings-test", "5.0"),
                new CapabilityAdvertisement(Set.of("core.item-envelope"), Set.of()));
        assertTrue(session.handle(request("initialize", "initialize/session", params))
                .result()
                .isPresent());
    }

    private WriteCommand command(String key, Object payload) {
        return new WriteCommand(key, 0, components.json().encode(payload));
    }

    private JsonRpcRequest request(String id, String method, Object params) {
        return new JsonRpcRequest(new RpcId(id), method, components.json().encode(params));
    }

    private <T> T decodeSuccess(JsonRpcResponse response, Class<T> type) {
        return components.json().decode(response.result().orElseThrow(), type);
    }

    private record Empty() {}

    private static final class StubModel implements ModelGateway {
        @Override
        public ModelCapabilities capabilities(String modelId) {
            return new ModelCapabilities(false, false, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation) {
            return new ModelInvocationResult(
                    "完成", List.of(), ModelUsage.zero(), Optional.empty(), Optional.empty(), ModelFinishReason.COMPLETE);
        }
    }
}
