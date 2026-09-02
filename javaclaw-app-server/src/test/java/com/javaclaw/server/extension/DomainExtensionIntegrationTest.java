package com.javaclaw.server.extension;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SiteManagementContracts;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.server.AppServerBootstrap;
import com.javaclaw.server.rpc.AppServerSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainExtensionIntegrationTest {
    private static final Instant LATER = Instant.parse("2026-08-31T11:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void eachDomainAppliesItsOwnDiscoveryRulesAndWorkspaceIsolation() throws Exception {
        try (AppServerBootstrap.Components components = AppServerBootstrap.create(
                temporaryDirectory.resolve("data-v5"), Clock.systemUTC(), new UnusedModel(), enabled -> {})) {
            AppServerSession session = components.newSession();
            initialize(session, components);
            Workspace first = createWorkspace(session, components, "First");
            Workspace second = createWorkspace(session, components, "Second");

            verifyMemory(session, components, first, second);
            verifySkill(session, components, first);
            verifySite(session, components, first);
        }
    }

    @Test
    void siteRequiresAnExactHttpsBaseHostInItsAllowlist() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteContracts.Site(
                        "docs",
                        1,
                        1,
                        "Docs",
                        URI.create("https://docs.example.com/start"),
                        Set.of(URI.create("https://*.example.com")),
                        SiteContracts.SiteCredential.none(),
                        Optional.empty(),
                        true,
                        LATER));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteContracts.Site(
                        "docs",
                        1,
                        1,
                        "Docs",
                        URI.create("https://docs.example.com/start"),
                        Set.of(URI.create("https://other.example.com")),
                        SiteContracts.SiteCredential.none(),
                        Optional.empty(),
                        true,
                        LATER));
    }

    private void verifyMemory(
            AppServerSession session, AppServerBootstrap.Components components, Workspace first, Workspace second) {
        command(
                session,
                components,
                first,
                new CommandSpec(BuiltinExtensionIds.MEMORY, "create", memory("kept"), "memory-1", 0));
        command(
                session,
                components,
                first,
                new CommandSpec(BuiltinExtensionIds.MEMORY, "create", memory("draft"), "memory-2", 0));
        command(
                session,
                components,
                second,
                new CommandSpec(BuiltinExtensionIds.MEMORY, "create", memory("other"), "memory-3", 0));

        MemoryContracts.SearchResult result = query(
                session,
                components,
                first.id(),
                BuiltinExtensionIds.MEMORY,
                new MemoryContracts.SearchRequest("architecture", Set.of("workspace"), Set.of("v5"), 10),
                MemoryContracts.SearchResult.class);

        assertEquals(
                Set.of("kept", "draft"),
                result.matches().stream().map(MemoryContracts.Memory::id).collect(java.util.stream.Collectors.toSet()));
    }

    private void verifySkill(AppServerSession session, AppServerBootstrap.Components components, Workspace workspace) {
        savePublishSkill(session, components, workspace, "review", true, "skill-review");
        savePublishSkill(session, components, workspace, "hidden", false, "skill-hidden");

        SkillContracts.SearchResult result = query(
                session,
                components,
                workspace.id(),
                BuiltinExtensionIds.SKILL,
                new SkillContracts.SearchRequest("review", 10),
                SkillContracts.SearchResult.class);

        assertEquals(
                List.of("review"),
                result.matches().stream().map(SkillContracts.Summary::id).toList());
    }

    private void verifySite(AppServerSession session, AppServerBootstrap.Components components, Workspace workspace) {
        command(
                session,
                components,
                workspace,
                new CommandSpec(BuiltinExtensionIds.SITE, "site/create", site("docs", true), "site-1", 0));
        command(
                session,
                components,
                workspace,
                new CommandSpec(BuiltinExtensionIds.SITE, "site/create", site("disabled", false), "site-2", 0));

        SiteContracts.SearchResult result = query(
                session,
                components,
                workspace.id(),
                BuiltinExtensionIds.SITE,
                new SiteContracts.SearchRequest("docs.example.com", 10),
                SiteContracts.SearchResult.class);

        assertEquals(
                List.of("docs"),
                result.matches().stream().map(SiteContracts.Projection::id).toList());
    }

    private MemoryContracts.CreateRequest memory(String id) {
        return new MemoryContracts.CreateRequest(
                id,
                MemoryContracts.MemoryKind.FACT,
                "workspace",
                "JavaClaw architecture decision",
                Set.of("v5"),
                false,
                Optional.empty());
    }

    private void savePublishSkill(
            AppServerSession session,
            AppServerBootstrap.Components components,
            Workspace workspace,
            String id,
            boolean enabled,
            String key) {
        command(
                session,
                components,
                workspace,
                new CommandSpec(
                        BuiltinExtensionIds.SKILL,
                        "draft/save-content",
                        new SkillContracts.SaveContentRequest(
                                id, id + " code review", "Review Java code", "Treat this as explicit data"),
                        key + "-draft",
                        0));
        command(
                session,
                components,
                workspace,
                new CommandSpec(
                        BuiltinExtensionIds.SKILL,
                        "publish",
                        new SkillContracts.PublishRequest(id, 1),
                        key + "-publish",
                        0));
        if (enabled) {
            command(
                    session,
                    components,
                    workspace,
                    new CommandSpec(
                            BuiltinExtensionIds.SKILL,
                            "enable",
                            new SkillContracts.EnableRequest(id, true),
                            key + "-enable",
                            1));
        }
    }

    private SiteManagementContracts.SaveRequest site(String id, boolean enabled) {
        URI origin = URI.create("https://docs.example.com");
        return new SiteManagementContracts.SaveRequest(
                id,
                id + " documentation",
                origin,
                List.of(new SiteManagementContracts.AllowedOrigin("origin-1", origin)),
                enabled);
    }

    private void command(
            AppServerSession session,
            AppServerBootstrap.Components components,
            Workspace workspace,
            CommandSpec command) {
        ExtensionRpcContracts.CallPayload call = new ExtensionRpcContracts.CallPayload(
                command.extensionId(),
                workspace.id(),
                Optional.empty(),
                Optional.empty(),
                command.operation(),
                components.json().encode(command.payload()));
        decode(
                session.handle(request(
                        components,
                        command.key(),
                        "extension/command",
                        new WriteCommand(
                                command.key(),
                                command.expectedRevision(),
                                components.json().encode(call)))),
                components,
                ExtensionRpcContracts.CallResult.class);
    }

    private <T> T query(
            AppServerSession session,
            AppServerBootstrap.Components components,
            WorkspaceId workspaceId,
            String extensionId,
            Object search,
            Class<T> type) {
        ExtensionRpcContracts.CallPayload call = new ExtensionRpcContracts.CallPayload(
                extensionId,
                workspaceId,
                Optional.empty(),
                Optional.empty(),
                "search",
                components.json().encode(search));
        ExtensionRpcContracts.CallResult result = decode(
                session.handle(request(components, extensionId, "extension/query", call)),
                components,
                ExtensionRpcContracts.CallResult.class);
        return components.json().decode(result.payload(), type);
    }

    private Workspace createWorkspace(AppServerSession session, AppServerBootstrap.Components components, String name) {
        CoreRpcContracts.WorkspaceCreatePayload payload =
                new CoreRpcContracts.WorkspaceCreatePayload(name, temporaryDirectory.resolve(name));
        return decode(
                session.handle(request(
                        components,
                        "workspace-" + name,
                        "workspace/create",
                        new WriteCommand(
                                "workspace-key-" + name, 0, components.json().encode(payload)))),
                components,
                Workspace.class);
    }

    private void initialize(AppServerSession session, AppServerBootstrap.Components components) {
        InitializeParams params = new InitializeParams(
                ProtocolVersion.CURRENT,
                new ClientInfo("domain-extension-test", "5.0"),
                new CapabilityAdvertisement(Set.of("core.item-envelope"), Set.of()));
        assertTrue(session.handle(request(components, "init", "initialize/session", params))
                .result()
                .isPresent());
    }

    private JsonRpcRequest request(AppServerBootstrap.Components components, String id, String method, Object params) {
        return new JsonRpcRequest(new RpcId(id), method, components.json().encode(params));
    }

    private <T> T decode(JsonRpcResponse response, AppServerBootstrap.Components components, Class<T> type) {
        CanonicalPayload payload = response.result()
                .orElseThrow(() -> new AssertionError(response.error().orElseThrow()));
        return components.json().decode(payload, type);
    }

    private static final class UnusedModel implements ModelGateway {
        @Override
        public ModelCapabilities capabilities(String modelId) {
            throw new AssertionError("model must not be used");
        }

        @Override
        public ModelInvocationResult invoke(
                com.javaclaw.api.TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                com.javaclaw.api.CancellationToken cancellation) {
            throw new AssertionError("model must not be used");
        }
    }

    private record CommandSpec(
            String extensionId, String operation, Object payload, String key, long expectedRevision) {}
}
