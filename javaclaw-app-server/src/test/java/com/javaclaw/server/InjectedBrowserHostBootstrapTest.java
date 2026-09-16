package com.javaclaw.server;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteManagementContracts;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.server.rpc.AppServerSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InjectedBrowserHostBootstrapTest {
    @TempDir
    Path temporary;

    @Test
    void 注入模型且无Worker时仍经扩展RPC创建和读取同一账号() throws Exception {
        Files.createDirectories(temporary.resolve("workspace"));
        ModelGateway models = (ModelGateway) Proxy.newProxyInstance(
                ModelGateway.class.getClassLoader(), new Class<?>[] {ModelGateway.class}, (proxy, method, args) -> {
                    throw new AssertionError("账号管理不得调用模型");
                });
        try (var components = AppServerBootstrap.create(
                        temporary.resolve("data-v6"), Clock.systemUTC(), models, ignored -> {});
                var session = components.newSession()) {
            var json = components.json();
            initialize(components, session);
            Workspace workspace = json.decode(
                    rpc(
                            session,
                            "workspace/create",
                            json.encode(new WriteCommand(
                                    "workspace",
                                    0,
                                    json.encode(new CoreRpcContracts.WorkspaceCreatePayload(
                                            "Browser", temporary.resolve("workspace")))))),
                    Workspace.class);
            URI origin = URI.create("https://example.com");
            command(
                    components,
                    session,
                    workspace.id(),
                    "site/create",
                    new SiteManagementContracts.SaveRequest(
                            "example",
                            "测试网站",
                            origin,
                            List.of(new SiteManagementContracts.AllowedOrigin("primary", origin)),
                            true));
            command(
                    components,
                    session,
                    workspace.id(),
                    "account/create",
                    new SiteAccountContracts.CreateRequest("example", "工作"));
            var result = json.decode(
                    rpc(
                            session,
                            "extension/query",
                            json.encode(call(
                                    workspace.id(),
                                    "account/list",
                                    json.encode(new SiteAccountContracts.ListRequest("example"))))),
                    ExtensionRpcContracts.CallResult.class);
            var accounts = json.decode(result.payload(), SiteAccountContracts.AccountList.class)
                    .accounts();
            assertEquals(1, accounts.size());
            assertEquals("工作", accounts.getFirst().name());
            assertTrue(accounts.getFirst().defaultAccount());
        }
    }

    private static void initialize(AppServerBootstrap.Components components, AppServerSession session) {
        rpc(
                session,
                "initialize/session",
                components
                        .json()
                        .encode(new InitializeParams(
                                3,
                                new ClientInfo("injected-browser", "6"),
                                new CapabilityAdvertisement(Set.of(), Set.of()))));
    }

    private static void command(
            AppServerBootstrap.Components components,
            AppServerSession session,
            WorkspaceId workspace,
            String operation,
            Object payload) {
        var json = components.json();
        rpc(
                session,
                "extension/command",
                json.encode(
                        new WriteCommand(operation, 0, json.encode(call(workspace, operation, json.encode(payload))))));
    }

    private static ExtensionRpcContracts.CallPayload call(
            WorkspaceId workspace, String operation, CanonicalPayload payload) {
        return new ExtensionRpcContracts.CallPayload(
                BuiltinExtensionIds.SITE, workspace, Optional.empty(), Optional.empty(), operation, payload);
    }

    private static CanonicalPayload rpc(AppServerSession session, String method, CanonicalPayload payload) {
        var response =
                session.handle(new JsonRpcRequest(new RpcId(UUID.randomUUID().toString()), method, payload));
        assertTrue(response.error().isEmpty(), () -> response.error().toString());
        return response.result().orElseThrow();
    }
}
