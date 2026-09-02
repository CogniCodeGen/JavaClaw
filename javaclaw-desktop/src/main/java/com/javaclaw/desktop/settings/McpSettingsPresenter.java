package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpHealth;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;

/** MCP 管理页异步状态机；不持有 JavaFX 控件或 Secret 明文。 */
public final class McpSettingsPresenter {
    private static final int CATALOG_PAGE_SIZE = 50;

    private final McpSettingsGateway gateway;
    private final McpCredentialSaveCoordinator credentials;
    private Consumer<McpSettingsState> listener = ignored -> {};
    private McpSettingsState state = McpSettingsState.initial();

    /**
     * 创建 Presenter。
     *
     * @param gateway 强类型 SDK 设置边界
     */
    public McpSettingsPresenter(McpSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        credentials = new McpCredentialSaveCoordinator(gateway);
    }

    /** @param value 完整状态订阅者；注册后立即收到当前快照 */
    public void subscribe(Consumer<McpSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 重新读取 Workspace，并为首个活动 Workspace 读取 MCP 目录。 */
    public void reload() {
        long epoch = nextEpoch();
        publish(status(SettingsLoadState.LOADING, "正在读取 MCP 配置…", false, epoch));
        gateway.workspaces().whenComplete((workspaces, failure) -> completeWorkspaceReload(epoch, workspaces, failure));
    }

    /**
     * 切换 Workspace；有未保存草稿时拒绝切换。
     *
     * @param workspaceId 目标 Workspace
     */
    public void chooseWorkspace(WorkspaceId workspaceId) {
        WorkspaceId checked = Objects.requireNonNull(workspaceId, "workspaceId");
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        loadWorkspace(checked, nextEpoch());
    }

    /**
     * 选择 Endpoint 并读取历史及首屏 Catalog。
     *
     * @param endpoint 端点快照
     */
    public void select(McpEndpoint endpoint) {
        McpEndpoint checked = Objects.requireNonNull(endpoint, "endpoint");
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        loadEndpointDetails(checked);
    }

    private void loadEndpointDetails(McpEndpoint endpoint) {
        long epoch = nextEpoch();
        McpEndpointDraft draft = McpEndpointDraft.from(endpoint);
        publish(new McpSettingsState(
                SettingsLoadState.LOADING,
                state.workspaces(),
                state.workspaceId(),
                state.grants(),
                state.endpoints(),
                new McpSettingsState.Selection(
                        Optional.of(endpoint),
                        draft,
                        draft,
                        Optional.empty(),
                        List.of(),
                        new McpSettingsState.Catalog(List.of(), Optional.empty())),
                new McpSettingsState.Feedback("正在读取 Endpoint 详情…", false, epoch)));
        CompletionStage<List<McpEndpoint>> history = gateway.mcpEndpointHistory(endpoint.id());
        CompletionStage<McpCatalogPage> catalog =
                gateway.mcpCatalog(endpoint.id(), Optional.empty(), Optional.empty(), CATALOG_PAGE_SIZE);
        history.thenCombine(catalog, EndpointDetails::new)
                .whenComplete((details, failure) -> completeDetails(epoch, details, failure));
    }

    /** 开始填写当前 Workspace 下的新 HTTPS Endpoint。 */
    public void createDraft() {
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        McpEndpointDraft draft = McpEndpointDraft.empty(state.workspaceId());
        publish(new McpSettingsState(
                SettingsLoadState.READY,
                state.workspaces(),
                state.workspaceId(),
                state.grants(),
                state.endpoints(),
                new McpSettingsState.Selection(
                        Optional.empty(),
                        draft,
                        draft,
                        Optional.empty(),
                        List.of(),
                        new McpSettingsState.Catalog(List.of(), Optional.empty())),
                new McpSettingsState.Feedback("填写新的 HTTPS MCP Endpoint", true, nextEpoch())));
    }

    /** @param draft 控件投影出的完整草稿 */
    public void updateDraft(McpEndpointDraft draft) {
        McpEndpointDraft checked = Objects.requireNonNull(draft, "draft");
        try {
            requireEditableEndpoint();
        } catch (IllegalStateException failure) {
            failLocal(failure);
            return;
        }
        McpSettingsState.Selection current = state.selection();
        boolean dirty = !checked.equals(current.baseline());
        publish(new McpSettingsState(
                SettingsLoadState.READY,
                state.workspaces(),
                state.workspaceId(),
                state.grants(),
                state.endpoints(),
                new McpSettingsState.Selection(
                        current.endpoint(),
                        current.baseline(),
                        checked,
                        current.health(),
                        current.history(),
                        current.catalog()),
                new McpSettingsState.Feedback(
                        dirty ? "MCP 草稿尚未保存" : "", dirty, state.feedback().epoch())));
    }

    /**
     * 保存草稿；PasswordField 内容只在本次调用栈和 SDK sealed-secret 边界存在。
     *
     * @param secret 可选新 Secret；方法返回前清零调用方数组
     */
    public void save(char[] secret) {
        try {
            requireEditableEndpoint();
            requireIdentifier(state.selection().draft().id());
            long epoch = begin("正在密封凭据并保存 MCP Endpoint…");
            credentials.prepare(state.selection().draft(), secret).whenComplete((prepared, failure) -> {
                if (failure != null) {
                    fail(epoch, failure);
                } else {
                    writePrepared(epoch, prepared.spec(), prepared.createdCredential());
                }
            });
        } catch (RuntimeException failure) {
            if (secret != null) {
                java.util.Arrays.fill(secret, '\0');
            }
            failLocal(failure);
        }
    }

    /** 探测所选 Endpoint；只写入脱敏健康投影。 */
    public void probe() {
        McpEndpoint selected = requireSelected();
        long epoch = begin("正在检查 MCP 协议与健康状态…");
        gateway.probeMcpEndpoint(selected.id())
                .whenComplete((health, failure) -> completeHealth(epoch, health, failure));
    }

    /** 原子刷新目录，成功后重新读取第一页。 */
    public void refreshCatalog() {
        McpEndpoint selected = requireSelected();
        long epoch = begin("正在刷新 MCP Catalog…");
        gateway.refreshMcpCatalog(selected, CommandOptions.create(selected.revision()))
                .whenComplete((updated, failure) -> completeCatalogRefresh(epoch, updated, failure));
    }

    /** 读取下一页目录；旧响应按 epoch 丢弃。 */
    public void loadNextCatalogPage() {
        McpEndpoint selected = requireSelected();
        Optional<String> cursor = state.selection().catalog().nextCursor();
        if (cursor.isEmpty()) {
            return;
        }
        long epoch = begin("正在读取下一页 Catalog…");
        gateway.mcpCatalog(selected.id(), Optional.empty(), cursor, CATALOG_PAGE_SIZE)
                .whenComplete((page, failure) -> completeCatalogPage(epoch, page, failure));
    }

    /** 实时启用或停用当前 Endpoint。 */
    public void toggleEnabled() {
        McpEndpoint selected = requireSelected();
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        boolean enable = selected.state() != McpEndpointState.ENABLED;
        long epoch = begin(enable ? "正在启用 MCP Endpoint…" : "正在停用 MCP Endpoint…");
        gateway.setMcpEndpointEnabled(selected, enable, CommandOptions.create(selected.revision()))
                .whenComplete((updated, failure) -> completeWrite(epoch, updated, failure, Optional.empty()));
    }

    /**
     * 接收 OAuth 子状态机读取的权威 Endpoint 与健康快照。
     *
     * @param endpoint 已绑定 Vault CredentialRef 的最新版本
     * @param health 服务端完成 token 交换后的脱敏健康状态
     */
    public void applyOAuthRefresh(McpEndpoint endpoint, McpHealth health) {
        loadEndpointDetails(Objects.requireNonNull(endpoint, "endpoint"));
        completeHealth(state.feedback().epoch(), Objects.requireNonNull(health, "health"), null);
    }

    /** 丢弃当前表单草稿。 */
    public void discardDraft() {
        McpSettingsState.Selection current = state.selection();
        McpEndpointDraft baseline = current.baseline();
        publish(new McpSettingsState(
                SettingsLoadState.READY,
                state.workspaces(),
                state.workspaceId(),
                state.grants(),
                state.endpoints(),
                new McpSettingsState.Selection(
                        current.endpoint(), baseline, baseline, current.health(), current.history(), current.catalog()),
                new McpSettingsState.Feedback("本地草稿已丢弃", false, state.feedback().epoch())));
    }

    /** 显示统一离页保护消息。 */
    public void warnUnsavedChanges() {
        publish(status(state.phase(), "请先保存或丢弃 MCP 草稿", true, state.feedback().epoch()));
    }

    /** @return 当前不可变状态 */
    public McpSettingsState state() {
        return state;
    }

    private void completeWorkspaceReload(long epoch, List<Workspace> workspaces, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        List<Workspace> catalog = List.copyOf(workspaces);
        Optional<WorkspaceId> workspace = catalog.stream().findFirst().map(Workspace::id);
        publish(new McpSettingsState(
                SettingsLoadState.READY,
                catalog,
                workspace,
                List.of(),
                List.of(),
                McpSettingsState.Selection.empty(McpEndpointDraft.empty(workspace)),
                new McpSettingsState.Feedback(workspace.isEmpty() ? "暂无 Workspace" : "", false, epoch)));
        workspace.ifPresent(value -> loadWorkspace(value, nextEpoch()));
    }

    private void loadWorkspace(WorkspaceId workspaceId, long epoch) {
        publish(new McpSettingsState(
                SettingsLoadState.LOADING,
                state.workspaces(),
                Optional.of(workspaceId),
                List.of(),
                List.of(),
                McpSettingsState.Selection.empty(McpEndpointDraft.empty(Optional.of(workspaceId))),
                new McpSettingsState.Feedback("正在读取 Workspace MCP 目录…", false, epoch)));
        gateway.mcpEndpoints(workspaceId)
                .thenCombine(gateway.privateNetworkGrants(workspaceId), WorkspaceCatalog::new)
                .whenComplete((catalog, failure) -> completeWorkspace(epoch, catalog, failure));
    }

    private void completeWorkspace(long epoch, WorkspaceCatalog catalog, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        List<PrivateNetworkGrant> grants = catalog.grants().stream()
                .filter(grant -> grant.purpose() == PrivateNetworkPurpose.MCP)
                .filter(grant -> grant.state() == SecurityGrantState.ACTIVE)
                .toList();
        Optional<McpEndpoint> first = catalog.endpoints().stream().findFirst();
        McpEndpointDraft draft =
                first.map(McpEndpointDraft::from).orElseGet(() -> McpEndpointDraft.empty(state.workspaceId()));
        publish(new McpSettingsState(
                SettingsLoadState.READY,
                state.workspaces(),
                state.workspaceId(),
                grants,
                catalog.endpoints(),
                new McpSettingsState.Selection(
                        first,
                        draft,
                        draft,
                        Optional.empty(),
                        List.of(),
                        new McpSettingsState.Catalog(List.of(), Optional.empty())),
                new McpSettingsState.Feedback(catalog.endpoints().isEmpty() ? "暂无 MCP Endpoint" : "", false, epoch)));
        first.ifPresent(this::loadEndpointDetails);
    }

    private void completeDetails(long epoch, EndpointDetails details, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        McpSettingsState.Selection current = state.selection();
        publish(new McpSettingsState(
                SettingsLoadState.READY,
                state.workspaces(),
                state.workspaceId(),
                state.grants(),
                state.endpoints(),
                new McpSettingsState.Selection(
                        current.endpoint(),
                        current.baseline(),
                        current.draft(),
                        current.health(),
                        details.history(),
                        new McpSettingsState.Catalog(
                                details.catalog().entries(), details.catalog().nextCursor())),
                new McpSettingsState.Feedback("", false, epoch)));
    }

    private void writePrepared(long epoch, McpEndpointSpec spec, Optional<CredentialMetadata> createdCredential) {
        McpSettingsState.Selection selection = state.selection();
        CompletionStage<McpEndpoint> request = selection.endpoint().isEmpty()
                ? gateway.createMcpEndpoint(selection.draft().id(), spec, CommandOptions.create(0))
                : gateway.updateMcpEndpoint(
                        selection.draft().id(),
                        spec,
                        CommandOptions.create(selection.endpoint().orElseThrow().revision()));
        request.whenComplete((endpoint, failure) -> completeWrite(epoch, endpoint, failure, createdCredential));
    }

    private void completeWrite(
            long epoch, McpEndpoint endpoint, Throwable failure, Optional<CredentialMetadata> createdCredential) {
        if (failure != null) {
            createdCredential.ifPresent(this::clearOrphanCredential);
            if (!stale(epoch)) {
                fail(epoch, failure);
            }
            return;
        }
        if (stale(epoch)) {
            return;
        }
        clearDetachedCredential(endpoint);
        List<McpEndpoint> endpoints = replace(state.endpoints(), endpoint);
        McpEndpointDraft draft = McpEndpointDraft.from(endpoint);
        publish(new McpSettingsState(
                SettingsLoadState.READY,
                state.workspaces(),
                state.workspaceId(),
                state.grants(),
                endpoints,
                new McpSettingsState.Selection(
                        Optional.of(endpoint),
                        draft,
                        draft,
                        state.selection().health(),
                        state.selection().history(),
                        state.selection().catalog()),
                new McpSettingsState.Feedback("MCP Endpoint 已保存", false, epoch)));
    }

    private void clearDetachedCredential(McpEndpoint endpoint) {
        Optional<CredentialRef> before = state.selection().baseline().credential();
        Optional<CredentialRef> after = endpoint.spec().credential();
        if (before.isPresent() && !before.equals(after)) {
            gateway.credential(before.orElseThrow()).whenComplete((metadata, failure) -> {
                if (failure == null) {
                    metadata.ifPresent(this::clearOrphanCredential);
                }
            });
        }
    }

    private void clearOrphanCredential(CredentialMetadata metadata) {
        gateway.clearCredential(metadata.reference(), CommandOptions.create(metadata.revision()));
    }

    private void completeHealth(long epoch, McpHealth health, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        McpSettingsState.Selection current = state.selection();
        publish(selection(
                SettingsLoadState.READY,
                new McpSettingsState.Selection(
                        current.endpoint(),
                        current.baseline(),
                        current.draft(),
                        Optional.of(health),
                        current.history(),
                        current.catalog()),
                "健康检查已完成",
                false,
                epoch));
    }

    private void completeCatalogRefresh(long epoch, McpEndpoint updated, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        List<McpEndpoint> endpoints = replace(state.endpoints(), updated);
        McpEndpointDraft draft = McpEndpointDraft.from(updated);
        publish(new McpSettingsState(
                SettingsLoadState.LOADING,
                state.workspaces(),
                state.workspaceId(),
                state.grants(),
                endpoints,
                new McpSettingsState.Selection(
                        Optional.of(updated),
                        draft,
                        draft,
                        state.selection().health(),
                        state.selection().history(),
                        new McpSettingsState.Catalog(List.of(), Optional.empty())),
                new McpSettingsState.Feedback("Catalog 已提交，正在读取第一页…", false, epoch)));
        gateway.mcpCatalog(updated.id(), Optional.empty(), Optional.empty(), CATALOG_PAGE_SIZE)
                .whenComplete((page, pageFailure) -> completeCatalogPage(epoch, page, pageFailure));
    }

    private void completeCatalogPage(long epoch, McpCatalogPage page, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        McpSettingsState.Selection current = state.selection();
        List<com.javaclaw.api.McpCatalogEntry> entries =
                new java.util.ArrayList<>(current.catalog().entries());
        entries.addAll(page.entries());
        publish(selection(
                SettingsLoadState.READY,
                new McpSettingsState.Selection(
                        current.endpoint(),
                        current.baseline(),
                        current.draft(),
                        current.health(),
                        current.history(),
                        new McpSettingsState.Catalog(entries, page.nextCursor())),
                "Catalog 已刷新",
                false,
                epoch));
    }

    private long begin(String message) {
        long epoch = nextEpoch();
        publish(status(SettingsLoadState.LOADING, message, state.dirty(), epoch));
        return epoch;
    }

    private void failLocal(Throwable failure) {
        publish(status(
                SettingsLoadState.ERROR,
                SettingsFailures.message(failure),
                state.dirty(),
                state.feedback().epoch()));
    }

    private void fail(long epoch, Throwable failure) {
        publish(status(SettingsLoadState.ERROR, SettingsFailures.message(failure), state.dirty(), epoch));
    }

    private McpSettingsState status(SettingsLoadState phase, String message, boolean dirty, long epoch) {
        return new McpSettingsState(
                phase,
                state.workspaces(),
                state.workspaceId(),
                state.grants(),
                state.endpoints(),
                state.selection(),
                new McpSettingsState.Feedback(message, dirty, epoch));
    }

    private McpSettingsState selection(
            SettingsLoadState phase, McpSettingsState.Selection selection, String message, boolean dirty, long epoch) {
        return new McpSettingsState(
                phase,
                state.workspaces(),
                state.workspaceId(),
                state.grants(),
                state.endpoints(),
                selection,
                new McpSettingsState.Feedback(message, dirty, epoch));
    }

    private McpEndpoint requireSelected() {
        return state.selection().endpoint().orElseThrow(() -> new IllegalStateException("请先选择 MCP Endpoint"));
    }

    private void requireEditableEndpoint() {
        state.selection()
                .endpoint()
                .filter(endpoint -> endpoint.spec().transport() == McpTransport.SIGNED_BUNDLE_STDIO)
                .ifPresent(endpoint -> {
                    throw new IllegalStateException("签名 Bundle 提供的 stdio MCP Endpoint 只读，不能由用户编辑");
                });
    }

    private long nextEpoch() {
        return state.feedback().epoch() + 1;
    }

    private boolean stale(long epoch) {
        return epoch != state.feedback().epoch();
    }

    private void publish(McpSettingsState next) {
        state = Objects.requireNonNull(next, "next");
        listener.accept(state);
    }

    private static void requireIdentifier(String value) {
        if (!Objects.requireNonNull(value, "id").strip().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("MCP Endpoint 标识只能包含字母、数字、点、下划线和短横线");
        }
    }

    private static List<McpEndpoint> replace(List<McpEndpoint> endpoints, McpEndpoint updated) {
        java.util.ArrayList<McpEndpoint> next = new java.util.ArrayList<>(endpoints);
        next.removeIf(endpoint -> endpoint.id().equals(updated.id()));
        next.add(updated);
        next.sort(java.util.Comparator.comparing(endpoint -> endpoint.spec().displayName()));
        return List.copyOf(next);
    }

    private record WorkspaceCatalog(List<McpEndpoint> endpoints, List<PrivateNetworkGrant> grants) {}

    private record EndpointDetails(List<McpEndpoint> history, McpCatalogPage catalog) {}
}
