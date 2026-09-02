package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpCatalogEntry;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSettingsPresenterTest {
    @Test
    void 创建启用探测和刷新HttpsEndpoint形成权威闭环() {
        TestMcpSettingsGateway gateway = new TestMcpSettingsGateway();
        McpSettingsPresenter presenter = new McpSettingsPresenter(gateway);
        AtomicReference<McpSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);

        presenter.reload();
        presenter.createDraft();
        presenter.updateDraft(draft(latest.get(), McpAuthType.NONE));

        assertEquals(
                McpTransport.STREAMABLE_HTTPS,
                latest.get().selection().draft().toSpec().transport());
        presenter.save(new char[0]);

        assertEquals(
                McpEndpointState.DISABLED,
                latest.get().selection().endpoint().orElseThrow().state());
        assertFalse(latest.get().dirty());
        presenter.toggleEnabled();
        assertEquals(
                McpEndpointState.ENABLED,
                latest.get().selection().endpoint().orElseThrow().state());
        presenter.probe();
        assertTrue(latest.get().selection().health().isPresent());
        presenter.refreshCatalog();
        assertEquals(1, latest.get().selection().catalog().entries().size());
        assertEquals(1, latest.get().selection().endpoint().orElseThrow().catalogRevision());
    }

    @Test
    void Secret只进入Vault且Endpoint写失败会清理新凭据() {
        TestMcpSettingsGateway gateway = new TestMcpSettingsGateway();
        McpSettingsPresenter presenter = new McpSettingsPresenter(gateway);
        AtomicReference<McpSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);
        presenter.reload();
        presenter.createDraft();
        presenter.updateDraft(draft(latest.get(), McpAuthType.BEARER));
        gateway.failEndpointWrite();
        char[] secret = "top-secret".toCharArray();

        presenter.save(secret);

        assertTrue(allCleared(secret));
        assertEquals(0, gateway.credentialCount());
        assertEquals(SettingsLoadState.ERROR, latest.get().phase());
        assertTrue(latest.get().feedback().message().contains("模拟 Endpoint 写入失败"));
    }

    @Test
    void 签名Bundle提供的StdioEndpoint只能查看不能编辑() {
        TestMcpSettingsGateway gateway = new TestMcpSettingsGateway();
        McpEndpoint endpoint = gateway.addSignedBundleEndpoint();
        McpSettingsPresenter presenter = new McpSettingsPresenter(gateway);
        AtomicReference<McpSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);
        presenter.reload();

        assertEquals(
                McpTransport.SIGNED_BUNDLE_STDIO,
                latest.get().selection().endpoint().orElseThrow().spec().transport());
        assertEquals(
                "trusted.bundle",
                latest.get()
                        .selection()
                        .endpoint()
                        .orElseThrow()
                        .spec()
                        .signedBundleId()
                        .orElseThrow());

        presenter.updateDraft(draft(latest.get(), McpAuthType.NONE));

        assertEquals(SettingsLoadState.ERROR, latest.get().phase());
        assertTrue(latest.get().feedback().message().contains("只读"));
        assertEquals(McpEndpointDraft.from(endpoint), latest.get().selection().draft());

        char[] secret = "must-not-save".toCharArray();
        presenter.save(secret);

        assertTrue(allCleared(secret));
        assertEquals(
                1,
                gateway.mcpEndpointHistory(endpoint.id())
                        .toCompletableFuture()
                        .join()
                        .size());
    }

    @Test
    void 草稿保护与标识校验阻止隐式切换或无效写入() {
        TestMcpSettingsGateway gateway = new TestMcpSettingsGateway();
        McpSettingsPresenter presenter = new McpSettingsPresenter(gateway);
        AtomicReference<McpSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);
        presenter.reload();
        presenter.createDraft();

        presenter.chooseWorkspace(WorkspaceId.random());
        presenter.createDraft();
        assertTrue(latest.get().feedback().message().contains("保存或丢弃"));

        presenter.discardDraft();
        McpEndpointDraft invalid = new McpEndpointDraft(
                "bad id",
                latest.get().workspaceId(),
                "Invalid",
                "https://mcp.example.test/rpc",
                McpAuthType.NONE,
                Optional.empty(),
                "",
                Optional.empty(),
                30);
        presenter.updateDraft(invalid);
        char[] secret = "must-clear".toCharArray();
        presenter.save(secret);

        assertTrue(allCleared(secret));
        assertEquals(SettingsLoadState.ERROR, latest.get().phase());
        assertTrue(latest.get().feedback().message().contains("标识"));

        McpSettingsPresenter empty = new McpSettingsPresenter(new TestMcpSettingsGateway());
        assertThrows(IllegalStateException.class, empty::probe);
        assertThrows(IllegalStateException.class, empty::refreshCatalog);
        assertThrows(IllegalStateException.class, empty::toggleEnabled);
    }

    @Test
    void 更新认证方式会清理脱离Endpoint的旧Vault凭据且启停可逆() {
        TestMcpSettingsGateway gateway = new TestMcpSettingsGateway();
        McpSettingsPresenter presenter = new McpSettingsPresenter(gateway);
        AtomicReference<McpSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);
        presenter.reload();
        presenter.createDraft();
        presenter.updateDraft(draft(latest.get(), McpAuthType.BEARER));
        presenter.save("first-secret".toCharArray());
        assertEquals(1, gateway.credentialCount());

        presenter.updateDraft(draft(latest.get(), McpAuthType.NONE));
        presenter.save(new char[0]);
        assertEquals(0, gateway.credentialCount());
        assertEquals(
                2,
                gateway.mcpEndpointHistory("docs-mcp")
                        .toCompletableFuture()
                        .join()
                        .size());

        presenter.toggleEnabled();
        assertEquals(
                McpEndpointState.ENABLED,
                latest.get().selection().endpoint().orElseThrow().state());
        presenter.toggleEnabled();
        assertEquals(
                McpEndpointState.DISABLED,
                latest.get().selection().endpoint().orElseThrow().state());
    }

    @Test
    void Catalog下一页追加且详情失败保留明确错误() {
        TestMcpSettingsGateway gateway = new TestMcpSettingsGateway();
        McpSettingsPresenter presenter = new McpSettingsPresenter(gateway);
        AtomicReference<McpSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);
        presenter.reload();
        presenter.createDraft();
        presenter.updateDraft(draft(latest.get(), McpAuthType.NONE));
        presenter.save(new char[0]);
        McpEndpoint endpoint = latest.get().selection().endpoint().orElseThrow();
        gateway.enqueueCatalog(CompletableFuture.completedFuture(page("first", Optional.of("next"))));

        presenter.select(endpoint);
        gateway.enqueueCatalog(CompletableFuture.completedFuture(page("second", Optional.empty())));
        presenter.loadNextCatalogPage();
        assertEquals(
                List.of("first", "second"),
                latest.get().selection().catalog().entries().stream()
                        .map(McpCatalogEntry::name)
                        .toList());

        presenter.loadNextCatalogPage();
        assertEquals(2, latest.get().selection().catalog().entries().size());
        gateway.enqueueCatalog(CompletableFuture.failedFuture(new IllegalStateException("目录不可用")));
        presenter.select(endpoint);
        assertEquals(SettingsLoadState.ERROR, latest.get().phase());
        assertTrue(latest.get().feedback().message().contains("目录不可用"));
    }

    @Test
    void 没有Workspace时保持可管理空状态() {
        TestMcpSettingsGateway gateway = new TestMcpSettingsGateway();
        gateway.withoutWorkspaces();
        McpSettingsPresenter presenter = new McpSettingsPresenter(gateway);
        AtomicReference<McpSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);

        presenter.reload();

        assertTrue(latest.get().workspaces().isEmpty());
        assertTrue(latest.get().workspaceId().isEmpty());
        assertEquals("暂无 Workspace", latest.get().feedback().message());
    }

    @Test
    void 未保存Endpoint草稿阻止选择与启停但不阻止丢弃后切换Workspace() {
        TestMcpSettingsGateway gateway = new TestMcpSettingsGateway();
        McpSettingsPresenter presenter = new McpSettingsPresenter(gateway);
        AtomicReference<McpSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);
        presenter.reload();
        presenter.createDraft();
        presenter.updateDraft(draft(latest.get(), McpAuthType.NONE));
        presenter.save(new char[0]);
        McpEndpoint endpoint = latest.get().selection().endpoint().orElseThrow();
        McpEndpointDraft changed = new McpEndpointDraft(
                endpoint.id(),
                latest.get().workspaceId(),
                "Docs MCP Changed",
                "https://mcp.example.test/rpc",
                McpAuthType.NONE,
                Optional.empty(),
                "",
                Optional.empty(),
                30);
        presenter.updateDraft(changed);

        presenter.select(endpoint);
        presenter.toggleEnabled();

        assertTrue(latest.get().dirty());
        assertEquals(
                McpEndpointState.DISABLED,
                latest.get().selection().endpoint().orElseThrow().state());
        assertTrue(latest.get().feedback().message().contains("保存或丢弃"));

        presenter.discardDraft();
        presenter.chooseWorkspace(latest.get().workspaceId().orElseThrow());
        assertFalse(latest.get().dirty());
        assertEquals(
                endpoint.id(), latest.get().selection().endpoint().orElseThrow().id());
    }

    private static McpEndpointDraft draft(McpSettingsState state, McpAuthType authType) {
        return new McpEndpointDraft(
                "docs-mcp",
                state.workspaceId(),
                "Docs MCP",
                "https://mcp.example.test/rpc",
                authType,
                Optional.empty(),
                "",
                Optional.empty(),
                30);
    }

    private static boolean allCleared(char[] value) {
        for (char character : value) {
            if (character != '\0') {
                return false;
            }
        }
        return true;
    }

    private static McpCatalogPage page(String name, Optional<String> nextCursor) {
        McpCatalogEntry entry = McpCatalogEntry.tool(
                name,
                Optional.of(name),
                "测试 Catalog 分页",
                new CanonicalPayload("{\"type\":\"object\"}"),
                new CanonicalPayload("{\"type\":\"object\"}"));
        return new McpCatalogPage(List.of(entry), nextCursor);
    }
}
