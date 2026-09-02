package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpCatalogEntry;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantRef;
import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.ExecutionTimeline;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.ListDetailPane;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformComponentFactory.FeedbackKind;

/** HTTPS MCP Endpoint、健康、Catalog 和 OAuth 的统一管理页面。 */
public final class McpSettingsPage extends VBox implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final McpSettingsPresenter presenter;
    private final McpOAuthSettingsPresenter oauthPresenter;
    private final McpExternalDataPanel externalData;
    private final ComboBox<Workspace> workspace = new ComboBox<>();
    private final Button create;
    private final ListDetailPane<McpEndpoint> masterDetail = new ListDetailPane<>();
    private final TextField id = field("MCP Endpoint 标识");
    private final TextField displayName = field("展示名称");
    private final TextField endpointUri = field("https://example.com/mcp");
    private final ComboBox<McpAuthType> authType = new ComboBox<>();
    private final TextField apiKeyHeader = field("X-Api-Key");
    private final ComboBox<PrivateNetworkGrant> privateGrant = new ComboBox<>();
    private final Spinner<Integer> timeout = new Spinner<>(1, 120, 30);
    private final PasswordField secret = new PasswordField();
    private final Label transport = value();
    private final Label bundleSource = value();
    private final Label lifecycle = value();
    private final Label revision = value();
    private final Label catalogRevision = value();
    private final Label credentialStatus = value();
    private final Label health = value();
    private final Label oauthState = value();
    private final Label oauthHost = value();
    private final Label oauthExpires = value();
    private final Label oauthFeedback = value();
    private final ListView<McpCatalogEntry> catalog = new ListView<>();
    private final ExecutionTimeline history = new ExecutionTimeline();
    private final Button save;
    private final Button discard;
    private final Button toggle;
    private final Button probe;
    private final Button refreshCatalog;
    private final Button nextPage;
    private final Button oauth;
    private final Button oauthRefresh;
    private final Button oauthCancel;
    private final AsyncActionBar actions;
    private McpSettingsState state = McpSettingsState.initial();
    private McpOAuthSettingsState oauthSettings = McpOAuthSettingsState.initial();
    private Node editor;
    private boolean rendering;
    private String oauthSelectionKey = "";

    /**
     * 创建 MCP 管理页。
     *
     * @param gateway 强类型 SDK 设置边界
     */
    public McpSettingsPage(McpSettingsGateway gateway) {
        presenter = new McpSettingsPresenter(gateway);
        oauthPresenter = new McpOAuthSettingsPresenter(gateway, presenter::applyOAuthRefresh);
        externalData = new McpExternalDataPanel(gateway);
        create = components.action("新建 HTTPS Endpoint", ActionStyle.SOFT, ActionSize.NORMAL);
        create.setOnAction(event -> presenter.createDraft());
        save = components.action("保存", ActionStyle.PRIMARY, ActionSize.NORMAL);
        save.setOnAction(event -> save());
        discard = components.action("丢弃草稿", ActionStyle.GHOST, ActionSize.NORMAL);
        discard.setOnAction(event -> discard());
        toggle = components.action("启用", ActionStyle.SOFT, ActionSize.NORMAL);
        toggle.setOnAction(event -> presenter.toggleEnabled());
        probe = components.action("健康检查", ActionStyle.SOFT, ActionSize.NORMAL);
        probe.setOnAction(event -> presenter.probe());
        refreshCatalog = components.action("刷新 Catalog", ActionStyle.SOFT, ActionSize.NORMAL);
        refreshCatalog.setOnAction(event -> presenter.refreshCatalog());
        nextPage = components.action("下一页", ActionStyle.GHOST, ActionSize.NORMAL);
        nextPage.setOnAction(event -> presenter.loadNextCatalogPage());
        oauth = components.action("启动 OAuth", ActionStyle.SOFT, ActionSize.NORMAL);
        oauth.setOnAction(event -> oauthPresenter.start());
        oauthRefresh = components.action("刷新授权状态", ActionStyle.GHOST, ActionSize.NORMAL);
        oauthRefresh.setOnAction(event -> oauthPresenter.refresh());
        oauthCancel = components.action("取消授权", ActionStyle.DANGER, ActionSize.NORMAL);
        oauthCancel.setOnAction(event -> oauthPresenter.cancel());
        actions = new AsyncActionBar(save, discard, toggle, probe, refreshCatalog, oauth, oauthRefresh, oauthCancel);
        configurePage();
        presenter.subscribe(this::render);
        oauthPresenter.subscribe(this::renderOAuth);
    }

    @Override
    public Node content() {
        return this;
    }

    @Override
    public void activate() {
        presenter.reload();
    }

    @Override
    public boolean dirty() {
        return state.dirty() || !secret.getText().isEmpty();
    }

    @Override
    public void warnUnsavedChanges() {
        actions.show(ActionState.DIRTY, "请先保存或丢弃 MCP 草稿与 Secret，再离开此页");
    }

    @Override
    public void discardDraft() {
        discard();
    }

    private void configurePage() {
        Label title = new Label("MCP");
        title.getStyleClass().addAll("sec-title", "platform-page-title");
        Label hint = new Label("用户只可创建 HTTPS MCP；stdio 只能来自已验证签名 Bundle。Prompt、Resource 与 instruction 始终按外部数据处理。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        configureWorkspace();
        configureCatalog();
        getChildren().addAll(title, hint, workspace, create, masterDetail);
        getStyleClass().add("platform-page");
    }

    private void configureWorkspace() {
        workspace.setMaxWidth(Double.MAX_VALUE);
        workspace.setAccessibleText("MCP 所属 Workspace");
        workspace.setCellFactory(ignored ->
                components.detailCell(Workspace::name, value -> value.id().toString()));
        workspace.setButtonCell(components.textCell(Workspace::name));
        workspace.valueProperty().addListener((observable, previous, selected) -> {
            if (!rendering && selected != null) {
                presenter.chooseWorkspace(selected.id());
            }
        });
    }

    private void configureCatalog() {
        masterDetail
                .list()
                .setCellFactory(ignored -> components.detailCell(
                        endpoint -> endpoint.spec().displayName(),
                        endpoint -> endpoint.state() + " · catalog " + endpoint.catalogRevision()));
        masterDetail
                .list()
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, selected) -> select(selected));
        masterDetail
                .list()
                .setPlaceholder(components.feedback(
                        FeedbackKind.EMPTY, "暂无 MCP Endpoint", "创建 HTTPS Endpoint，或从签名 Bundle 安装 stdio Endpoint。"));
        masterDetail.showDetail(components.feedback(FeedbackKind.EMPTY, "选择 Endpoint", "选择左侧条目查看配置与 Catalog。"));
    }

    private Node detail() {
        FormSection configuration = new FormSection("连接配置", "Endpoint 配置按 revision 更新；运行时执行前还会重新检查实时开关与 Catalog。");
        configureEditors();
        configuration.addField("标识", id);
        configuration.addField("名称", displayName);
        configuration.addField("HTTPS URL", endpointUri);
        configuration.addField("认证", authType);
        configuration.addField("API Key Header", apiKeyHeader);
        configuration.addField("私网授权", privateGrant);
        configuration.addField("超时（秒）", timeout);
        configuration.addField("新 Secret", secret);

        FormSection runtime = new FormSection("运行状态", "Secret 只显示是否已配置；健康信息和 Catalog 不包含远端正文。 ");
        runtime.addField("传输", transport);
        runtime.addField("签名 Bundle 来源", bundleSource);
        runtime.addField("状态", lifecycle);
        runtime.addField("Revision", revision);
        runtime.addField("Catalog revision", catalogRevision);
        runtime.addField("Credential", credentialStatus);
        runtime.addField("健康", health);

        FormSection oauthSection = new FormSection(
                "OAuth 2.1 + PKCE", "授权页只在 App Server 管理的隔离 Browser Worker 中打开；Desktop 不接收 URL、code、state 或 token。");
        oauthSection.addField("状态", oauthState);
        oauthSection.addField("授权主机", oauthHost);
        oauthSection.addField("截止时间", oauthExpires);
        oauthSection.addField("说明", oauthFeedback);

        FormSection catalogSection =
                new FormSection("Catalog", "分页读取工具、Prompt 与 Resource 元数据；非 Tool 条目不会自动进入 system context。");
        catalog.setPrefHeight(180);
        catalog.setCellFactory(ignored -> catalogCell());
        catalogSection.addFullWidth(catalog);
        catalogSection.addFullWidth(nextPage);

        FormSection historySection = new FormSection("版本历史", "服务端不可变历史，便于检查 revision 与配置变更。");
        historySection.addFullWidth(history);
        VBox content = new VBox(
                12, configuration, runtime, oauthSection, catalogSection, externalData, historySection, actions);
        content.getStyleClass().add("platform-page");
        return content;
    }

    private void configureEditors() {
        authType.getItems().setAll(McpAuthType.values());
        authType.setAccessibleText("MCP 认证方式");
        authType.setMaxWidth(Double.MAX_VALUE);
        privateGrant.setAccessibleText("MCP 私网授权");
        privateGrant.setMaxWidth(Double.MAX_VALUE);
        privateGrant.setCellFactory(ignored -> grantCell());
        privateGrant.setButtonCell(grantCell());
        timeout.setEditable(true);
        timeout.setAccessibleText("MCP 请求超时秒数");
        secret.setPromptText("留空则保留已配置 Secret");
        secret.setAccessibleText("MCP Secret");
        id.textProperty().addListener((observable, previous, value) -> projectDraft());
        displayName.textProperty().addListener((observable, previous, value) -> projectDraft());
        endpointUri.textProperty().addListener((observable, previous, value) -> projectDraft());
        authType.valueProperty().addListener((observable, previous, value) -> projectDraft());
        apiKeyHeader.textProperty().addListener((observable, previous, value) -> projectDraft());
        privateGrant.valueProperty().addListener((observable, previous, value) -> projectDraft());
        timeout.valueProperty().addListener((observable, previous, value) -> projectDraft());
        secret.textProperty().addListener((observable, previous, value) -> updateActionAvailability());
    }

    private void select(McpEndpoint selected) {
        if (rendering
                || selected == null
                || selected.equals(state.selection().endpoint().orElse(null))) {
            return;
        }
        if (dirty()) {
            warnUnsavedChanges();
            restoreSelection();
            return;
        }
        presenter.select(selected);
    }

    private void projectDraft() {
        if (rendering || authType.getValue() == null) {
            return;
        }
        boolean secretAuthentication =
                authType.getValue() == McpAuthType.BEARER || authType.getValue() == McpAuthType.API_KEY;
        Optional<com.javaclaw.api.CredentialRef> credential =
                secretAuthentication ? state.selection().draft().credential() : Optional.empty();
        Optional<PrivateNetworkGrantRef> grant = Optional.ofNullable(privateGrant.getValue())
                .map(value -> new PrivateNetworkGrantRef(value.id(), value.revision()));
        presenter.updateDraft(new McpEndpointDraft(
                id.getText(),
                state.workspaceId(),
                displayName.getText(),
                endpointUri.getText(),
                authType.getValue(),
                credential,
                apiKeyHeader.getText(),
                grant,
                timeout.getValue()));
    }

    private void save() {
        char[] value = secret.getText().toCharArray();
        secret.clear();
        presenter.save(value);
    }

    private void discard() {
        secret.clear();
        presenter.discardDraft();
    }

    private void render(McpSettingsState snapshot) {
        state = Objects.requireNonNull(snapshot, "snapshot");
        rendering = true;
        try {
            workspace.getItems().setAll(snapshot.workspaces());
            workspace.setValue(findWorkspace(snapshot));
            masterDetail.list().getItems().setAll(snapshot.endpoints());
            masterDetail
                    .list()
                    .getSelectionModel()
                    .select(snapshot.selection().endpoint().orElse(null));
            if (snapshot.selection().endpoint().isPresent()
                    || snapshot.dirty()
                    || !snapshot.selection().draft().id().isEmpty()) {
                renderDetail(snapshot);
            } else {
                masterDetail.showDetail(components.feedback(
                        FeedbackKind.EMPTY, "暂无 MCP Endpoint", "创建新的 HTTPS Endpoint 后可配置认证和 Catalog。"));
            }
        } finally {
            rendering = false;
        }
        updateActionAvailability();
        syncOAuthSelection(snapshot.selection().endpoint());
        externalData.select(snapshot.selection().endpoint());
        showStatus();
    }

    private void syncOAuthSelection(Optional<McpEndpoint> selected) {
        String key = selected.map(value -> value.id() + ':' + value.revision()).orElse("");
        if (!key.equals(oauthSelectionKey)) {
            oauthSelectionKey = key;
            oauthPresenter.select(selected);
        }
    }

    private void renderOAuth(McpOAuthSettingsState snapshot) {
        oauthSettings = Objects.requireNonNull(snapshot, "snapshot");
        oauthState.setText(
                snapshot.authorization().map(value -> value.state().name()).orElse("尚未启动"));
        oauthHost.setText(
                snapshot.authorization().map(value -> value.authorizationHost()).orElse("—"));
        oauthExpires.setText(snapshot.authorization()
                .map(value -> value.expiresAt().toString())
                .orElse("—"));
        oauthFeedback.setText(snapshot.message());
        updateOAuthActions();
    }

    private void renderDetail(McpSettingsState snapshot) {
        if (editor == null) {
            editor = detail();
        }
        masterDetail.showDetail(editor);
        McpEndpointDraft draft = snapshot.selection().draft();
        id.setText(draft.id());
        displayName.setText(draft.displayName());
        endpointUri.setText(draft.endpointUri());
        authType.setValue(draft.authType());
        apiKeyHeader.setText(draft.apiKeyHeader());
        privateGrant.getItems().setAll(snapshot.grants());
        privateGrant.setValue(findGrant(snapshot, draft.privateNetworkGrant()));
        timeout.getValueFactory().setValue(draft.timeoutSeconds());
        renderReadonly(snapshot);
    }

    private void renderReadonly(McpSettingsState snapshot) {
        Optional<McpEndpoint> selected = snapshot.selection().endpoint();
        transport.setText(
                selected.map(value -> value.spec().transport().name()).orElse(McpTransport.STREAMABLE_HTTPS.name()));
        bundleSource.setText(selected.flatMap(value -> value.spec().signedBundleId())
                .map(value -> value + "（已验证签名，只读）")
                .orElse("不适用"));
        lifecycle.setText(selected.map(value -> value.state().name()).orElse("尚未保存"));
        revision.setText(selected.map(value -> Long.toString(value.revision())).orElse("0"));
        catalogRevision.setText(
                selected.map(value -> Long.toString(value.catalogRevision())).orElse("0"));
        credentialStatus.setText(snapshot.selection().draft().credential().isPresent() ? "已配置（不可读取）" : "未配置");
        health.setText(snapshot.selection()
                .health()
                .map(value -> value.state()
                        + value.detail().map(detail -> " · " + detail).orElse(""))
                .orElse("尚未检查"));
        catalog.getItems().setAll(snapshot.selection().catalog().entries());
        nextPage.setDisable(snapshot.selection().catalog().nextCursor().isEmpty());
        history.setEntries(snapshot.selection().history().stream()
                .map(value -> new ExecutionTimeline.Entry(
                        value.updatedAt(),
                        "revision " + value.revision(),
                        value.state() + " · catalog " + value.catalogRevision()))
                .toList());
    }

    private void updateActionAvailability() {
        boolean pending = state.phase() == SettingsLoadState.LOADING;
        Optional<McpEndpoint> selected = state.selection().endpoint();
        boolean editable = selected.map(value -> value.spec().transport() == McpTransport.STREAMABLE_HTTPS)
                .orElse(true);
        boolean hasDraft =
                selected.isPresent() || !state.selection().draft().id().isEmpty();
        boolean secretDirty = !secret.getText().isEmpty();
        updateDraftActions(pending, editable, hasDraft, secretDirty);
        updateEndpointActions(pending, selected);
        updateOAuthActions();
        updateEditors(selected, editable);
    }

    private void updateDraftActions(boolean pending, boolean editable, boolean hasDraft, boolean secretDirty) {
        save.setDisable(pending || !editable || !hasDraft || (!state.dirty() && !secretDirty));
        discard.setDisable(pending || (!state.dirty() && !secretDirty));
    }

    private void updateEndpointActions(boolean pending, Optional<McpEndpoint> selected) {
        boolean unavailable = pending || selected.isEmpty() || state.dirty();
        toggle.setDisable(unavailable);
        probe.setDisable(unavailable);
        refreshCatalog.setDisable(unavailable);
        toggle.setText(
                selected.filter(value -> value.state() == McpEndpointState.ENABLED)
                                .isPresent()
                        ? "停用"
                        : "启用");
    }

    private void updateOAuthActions() {
        Optional<McpEndpoint> selected = state.selection().endpoint();
        boolean configured = selected.filter(value -> value.spec().authType() == McpAuthType.OAUTH_2_1_PKCE)
                .isPresent();
        boolean enabled = selected.filter(value -> value.state() == McpEndpointState.ENABLED)
                .isPresent();
        boolean busy = state.phase() == SettingsLoadState.LOADING
                || oauthSettings.phase() == SettingsLoadState.LOADING
                || state.dirty();
        boolean pending = oauthSettings.pendingAuthorization();
        oauth.setDisable(busy || !configured || !enabled || pending);
        oauthRefresh.setDisable(busy || !configured);
        oauthCancel.setDisable(busy || !pending);
        oauth.setText(oauthSettings.authorization().isPresent() ? "重新授权" : "启动 OAuth");
    }

    private void updateEditors(Optional<McpEndpoint> selected, boolean editable) {
        id.setDisable(selected.isPresent());
        displayName.setDisable(!editable);
        endpointUri.setDisable(!editable);
        authType.setDisable(!editable);
        apiKeyHeader.setDisable(!editable || authType.getValue() != McpAuthType.API_KEY);
        privateGrant.setDisable(!editable);
        timeout.setDisable(!editable);
        secret.setDisable(
                !editable || (authType.getValue() != McpAuthType.BEARER && authType.getValue() != McpAuthType.API_KEY));
    }

    private void showStatus() {
        if (state.phase() == SettingsLoadState.LOADING) {
            actions.show(ActionState.PENDING, state.feedback().message());
        } else if (state.phase() == SettingsLoadState.ERROR) {
            actions.show(ActionState.ERROR, state.feedback().message());
        } else if (dirty()) {
            actions.show(ActionState.DIRTY, "MCP 草稿或 Secret 尚未保存");
        } else {
            actions.show(
                    state.feedback().message().isBlank() ? ActionState.IDLE : ActionState.SUCCESS,
                    state.feedback().message());
        }
    }

    private void restoreSelection() {
        rendering = true;
        try {
            masterDetail
                    .list()
                    .getSelectionModel()
                    .select(state.selection().endpoint().orElse(null));
        } finally {
            rendering = false;
        }
    }

    private static Workspace findWorkspace(McpSettingsState snapshot) {
        return snapshot.workspaceId()
                .flatMap(id -> snapshot.workspaces().stream()
                        .filter(value -> value.id().equals(id))
                        .findFirst())
                .orElse(null);
    }

    private static PrivateNetworkGrant findGrant(
            McpSettingsState snapshot, Optional<PrivateNetworkGrantRef> reference) {
        return reference
                .flatMap(ref -> snapshot.grants().stream()
                        .filter(grant -> grant.id().equals(ref.id()) && grant.revision() == ref.revision())
                        .findFirst())
                .orElse(null);
    }

    private static ListCell<PrivateNetworkGrant> grantCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(PrivateNetworkGrant item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.origin() + " · 到期 " + item.expiresAt());
            }
        };
    }

    private static ListCell<McpCatalogEntry> catalogCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(McpCatalogEntry item, boolean empty) {
                super.updateItem(item, empty);
                setText(
                        empty || item == null
                                ? null
                                : item.kind() + " · " + item.title().orElse(item.name()) + " — " + item.description());
            }
        };
    }

    private static TextField field(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.getStyleClass().add("settings-field");
        return field;
    }

    private static Label value() {
        Label label = new Label("—");
        label.setWrapText(true);
        label.getStyleClass().add("platform-detail-text");
        return label;
    }
}
