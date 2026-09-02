package com.javaclaw.desktop.settings;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpOAuthState;
import com.javaclaw.api.McpTransport;
import com.javaclaw.client.CommandOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpOAuthSettingsPresenterTest {
    @Test
    void 启动读取取消只呈现脱敏状态() {
        TestMcpSettingsGateway gateway = new TestMcpSettingsGateway();
        McpEndpoint endpoint = enabledEndpoint(gateway);
        AtomicReference<McpOAuthSettingsState> latest = new AtomicReference<>();
        McpOAuthSettingsPresenter presenter = new McpOAuthSettingsPresenter(gateway, (updated, health) -> {});
        presenter.subscribe(latest::set);

        presenter.select(Optional.of(endpoint));
        presenter.start();

        assertEquals(
                McpOAuthState.PENDING,
                latest.get().authorization().orElseThrow().state());
        assertEquals(
                "login.example.test", latest.get().authorization().orElseThrow().authorizationHost());
        assertFalse(latest.get().message().contains("https://"));
        assertTrue(latest.get().pendingAuthorization());

        presenter.cancel();

        assertEquals(
                McpOAuthState.CANCELLED,
                latest.get().authorization().orElseThrow().state());
        assertFalse(latest.get().pendingAuthorization());
    }

    @Test
    void 授权成功后回读Endpoint凭据与健康快照() {
        TestMcpSettingsGateway gateway = new TestMcpSettingsGateway();
        McpEndpoint endpoint = enabledEndpoint(gateway);
        AtomicReference<McpEndpoint> refreshed = new AtomicReference<>();
        McpOAuthSettingsPresenter presenter =
                new McpOAuthSettingsPresenter(gateway, (updated, health) -> refreshed.set(updated));
        presenter.select(Optional.of(endpoint));
        presenter.start();
        gateway.authorizeOAuth();

        presenter.refresh();

        assertEquals(
                McpOAuthState.AUTHORIZED,
                presenter.state().authorization().orElseThrow().state());
        assertTrue(refreshed.get().spec().credential().isPresent());
        assertTrue(presenter.state().message().contains("已刷新"));
    }

    private static McpEndpoint enabledEndpoint(TestMcpSettingsGateway gateway) {
        var workspace = gateway.workspaces().toCompletableFuture().join().getFirst();
        McpEndpointSpec spec = new McpEndpointSpec(
                workspace.id(),
                "OAuth MCP",
                McpTransport.STREAMABLE_HTTPS,
                Optional.of(URI.create("https://mcp.example.test/rpc")),
                Optional.empty(),
                McpAuthType.OAUTH_2_1_PKCE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(30));
        McpEndpoint created = gateway.createMcpEndpoint("oauth-endpoint", spec, CommandOptions.create(0))
                .toCompletableFuture()
                .join();
        McpEndpoint enabled = gateway.setMcpEndpointEnabled(created, true, CommandOptions.create(created.revision()))
                .toCompletableFuture()
                .join();
        assertEquals(McpEndpointState.ENABLED, enabled.state());
        return enabled;
    }
}
