package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantPreview;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.SecurityGrantState;
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
import com.javaclaw.desktop.component.PlatformDialogs;

/** 私网授权预览确认、审计与不可逆撤销页面。 */
public final class PrivateNetworkGrantSettingsPage extends VBox implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final PrivateNetworkGrantSettingsPresenter presenter;
    private final ComboBox<PrivateNetworkPurpose> purpose = new ComboBox<>();
    private final TextField origin = new TextField();
    private final TextArea dnsAddresses = new TextArea();
    private final TextField validityHours = new TextField("1");
    private final ListDetailPane<PrivateNetworkGrant> grants = new ListDetailPane<>();
    private final Label previewOrigin = value();
    private final Label previewAddresses = value();
    private final Label previewExpiry = value();
    private final Label previewDigest = value();
    private final Label grantId = value();
    private final Label grantState = value();
    private final Label grantScope = value();
    private final Label grantAddresses = value();
    private final Label grantExpiry = value();
    private final ExecutionTimeline decisions = new ExecutionTimeline();
    private final Button preview;
    private final Button confirm;
    private final Button revoke;
    private final AsyncActionBar actions;
    private PrivateNetworkGrantSettingsState state = PrivateNetworkGrantSettingsState.initial();
    private Node detail;
    private Optional<Workspace> scopedWorkspace = Optional.empty();
    private boolean rendering;

    /** @param gateway 强类型 SDK 设置边界 */
    public PrivateNetworkGrantSettingsPage(CoreSettingsGateway gateway) {
        presenter = new PrivateNetworkGrantSettingsPresenter(gateway);
        preview = components.action("生成确认预览", ActionStyle.SOFT, ActionSize.NORMAL);
        preview.setOnAction(event -> presenter.preview());
        confirm = components.action("确认并创建", ActionStyle.PRIMARY, ActionSize.NORMAL);
        confirm.setOnAction(event -> confirmCreate());
        revoke = components.action("撤销授权", ActionStyle.DANGER, ActionSize.NORMAL);
        revoke.setOnAction(event -> revoke());
        actions = new AsyncActionBar(preview, confirm);
        configurePage();
        presenter.subscribe(this::render);
    }

    @Override
    public Node content() {
        return this;
    }

    @Override
    public Optional<Node> actionContent() {
        return Optional.of(actions);
    }

    @Override
    public void activate() {
        scopedWorkspace.ifPresent(presenter::selectWorkspace);
    }

    @Override
    public boolean dirty() {
        return state.dirty();
    }

    @Override
    public boolean pending() {
        return state.phase() == SettingsLoadState.LOADING || state.phase() == SettingsLoadState.SAVING;
    }

    @Override
    public void workspaceChanged(Optional<Workspace> workspace) {
        Optional<Workspace> checked = Objects.requireNonNull(workspace, "workspace");
        if (scopedWorkspace.equals(checked)) {
            return;
        }
        scopedWorkspace = checked;
        checked.ifPresentOrElse(presenter::selectWorkspace, presenter::invalidateWorkspace);
        renderActions(state);
    }

    @Override
    public void warnUnsavedChanges() {
        actions.show(ActionState.DIRTY, "请先确认创建或丢弃私网授权草稿");
    }

    @Override
    public void discardDraft() {
        presenter.discardDraft();
    }

    private void configurePage() {
        Label title = new Label("私网授权");
        title.getStyleClass().addAll("sec-title", "platform-page-title");
        Label hint = new Label("授权只绑定一个工作区、用途、精确 HTTPS 来源地址、当前 DNS 地址集合和期限；撤销会立即阻止新请求。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        FormSection draft = draftSection();
        FormSection previewSection = previewSection();
        configureGrantList();
        getChildren().addAll(title, hint, draft, previewSection, grants);
        getStyleClass().add("platform-page");
    }

    private FormSection draftSection() {
        FormSection section = new FormSection("新授权", "先生成服务端权威预览，再核对摘要并确认创建；默认 1 小时，硬上限 24 小时。");
        purpose.getItems().setAll(PrivateNetworkPurpose.values());
        purpose.setValue(PrivateNetworkPurpose.MCP);
        purpose.setConverter(SettingsLabels.converter(SettingsLabels::privateNetworkPurpose));
        purpose.setMaxWidth(Double.MAX_VALUE);
        purpose.setAccessibleText("私网授权用途");
        origin.setPromptText("https://service.example.test[:port]");
        origin.setAccessibleText("精确 HTTPS 来源地址");
        dnsAddresses.setPromptText("每行一个当前 DNS 数字地址，例如 10.0.0.8");
        dnsAddresses.setAccessibleText("DNS 地址集合");
        dnsAddresses.setPrefRowCount(3);
        validityHours.setAccessibleText("私网授权有效小时数");
        purpose.valueProperty().addListener((observable, previous, value) -> edit());
        origin.textProperty().addListener((observable, previous, value) -> edit());
        dnsAddresses.textProperty().addListener((observable, previous, value) -> edit());
        validityHours.textProperty().addListener((observable, previous, value) -> edit());
        section.addField("用途", purpose);
        section.addField("来源地址", origin);
        section.addField("DNS 地址", dnsAddresses);
        section.addField("有效小时", validityHours);
        Label forbidden = new Label("本机回环、本地链路、云元数据和多播地址永远不可授权；来源或地址变化后，旧授权不会自动扩大。");
        forbidden.setWrapText(true);
        forbidden.getStyleClass().add("sec-hint");
        section.addFullWidth(forbidden);
        return section;
    }

    private FormSection previewSection() {
        FormSection section = new FormSection("待确认预览", "任何草稿变更都会立即作废旧预览，避免确认内容与提交内容不一致。");
        section.addField("规范来源地址", previewOrigin);
        section.addField("地址集合", previewAddresses);
        section.addField("到期时间", previewExpiry);
        section.addField("确认摘要", previewDigest);
        return section;
    }

    private void configureGrantList() {
        grants.list()
                .setCellFactory(ignored -> components.detailCell(
                        value -> SettingsLabels.privateNetworkPurpose(value.purpose()) + " · " + value.origin(),
                        value -> SettingsLabels.securityGrantState(value.state()) + " · 版本 " + value.revision() + " · "
                                + value.expiresAt()));
        grants.list()
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, selected) -> selectGrant(selected));
        grants.list().setPlaceholder(components.feedback(FeedbackKind.EMPTY, "暂无私网授权", "创建后会在这里显示最新版本和决策记录。"));
        grants.showDetail(components.feedback(FeedbackKind.EMPTY, "选择授权", "选择左侧授权查看冻结范围、审计与撤销动作。"));
    }

    private Node grantDetail() {
        FormSection identity = new FormSection("授权快照", "授权范围不可编辑；如需变更，必须撤销后重新预览并确认。");
        identity.addField("授权标识", grantId);
        identity.addField("状态", grantState);
        identity.addField("范围", grantScope);
        identity.addField("DNS 地址", grantAddresses);
        identity.addField("到期", grantExpiry);
        FormSection audit = new FormSection("权限决策", "记录逐层校验结果，不包含密钥、正文或用户绝对路径。");
        audit.addFullWidth(decisions);
        VBox box = new VBox(12, identity, audit, revoke);
        box.getStyleClass().add("platform-page");
        return box;
    }

    private void selectGrant(PrivateNetworkGrant selected) {
        if (!rendering) {
            presenter.selectGrant(selected);
        }
    }

    private void edit() {
        if (!rendering && purpose.getValue() != null) {
            presenter.edit(purpose.getValue(), origin.getText(), dnsAddresses.getText(), validityHours.getText());
        }
    }

    private void render(PrivateNetworkGrantSettingsState snapshot) {
        state = Objects.requireNonNull(snapshot, "snapshot");
        rendering = true;
        try {
            purpose.setValue(snapshot.purpose());
            origin.setText(snapshot.origin());
            dnsAddresses.setText(snapshot.dnsAddresses());
            validityHours.setText(snapshot.validityHours());
            grants.list().getItems().setAll(snapshot.grants());
            grants.list().getSelectionModel().select(snapshot.selected().orElse(null));
            renderPreview(snapshot.preview().orElse(null));
            renderSelected(snapshot.selected().orElse(null));
            renderActions(snapshot);
        } finally {
            rendering = false;
        }
    }

    private void renderPreview(PrivateNetworkGrantPreview value) {
        previewOrigin.setText(value == null ? "—" : value.origin().toString());
        previewAddresses.setText(value == null ? "—" : String.join(", ", value.dnsAddresses()));
        previewExpiry.setText(value == null ? "—" : value.expiresAt().toString());
        previewDigest.setText(value == null ? "—" : value.confirmationDigest());
    }

    private void renderSelected(PrivateNetworkGrant selected) {
        if (selected == null) {
            grants.showDetail(components.feedback(FeedbackKind.EMPTY, "选择授权", "选择左侧授权查看冻结范围、审计与撤销动作。"));
            return;
        }
        if (detail == null) {
            detail = grantDetail();
        }
        grants.showDetail(detail);
        grantId.setText(selected.id() + " · 版本 " + selected.revision());
        grantState.setText(SettingsLabels.securityGrantState(selected.state()));
        grantScope.setText(SettingsLabels.privateNetworkPurpose(selected.purpose()) + " · " + selected.origin());
        grantAddresses.setText(String.join(", ", selected.dnsAddresses()));
        grantExpiry.setText(selected.expiresAt().toString());
        decisions.setEntries(state.decisions().stream()
                .filter(trace -> trace.grantId().equals(selected.id()))
                .map(trace -> new ExecutionTimeline.Entry(
                        trace.decidedAt(),
                        trace.allowed() ? "允许" : "拒绝",
                        trace.operation() + " · " + trace.resource()
                                + trace.denialReason()
                                        .map(reason -> " · " + reason)
                                        .orElse("")))
                .toList());
        revoke.setDisable(state.phase() == SettingsLoadState.LOADING || selected.state() != SecurityGrantState.ACTIVE);
    }

    private void renderActions(PrivateNetworkGrantSettingsState snapshot) {
        boolean pending = snapshot.phase() == SettingsLoadState.LOADING || snapshot.phase() == SettingsLoadState.SAVING;
        preview.setDisable(pending
                || scopedWorkspace.isEmpty()
                || snapshot.workspace().isEmpty()
                || snapshot.origin().isBlank()
                || snapshot.dnsAddresses().isBlank());
        confirm.setDisable(pending || snapshot.preview().isEmpty());
        purpose.setDisable(pending);
        origin.setDisable(pending);
        dnsAddresses.setDisable(pending);
        validityHours.setDisable(pending);
        if (pending) {
            actions.show(ActionState.PENDING, snapshot.message());
        } else if (snapshot.phase() == SettingsLoadState.ERROR) {
            actions.show(ActionState.ERROR, snapshot.message());
        } else if (snapshot.dirty()) {
            actions.show(ActionState.DIRTY, snapshot.message());
        } else {
            actions.show(snapshot.message().isBlank() ? ActionState.IDLE : ActionState.SUCCESS, snapshot.message());
        }
    }

    private void confirmCreate() {
        PrivateNetworkGrantPreview current = state.preview().orElseThrow();
        Alert alert = new Alert(
                Alert.AlertType.CONFIRMATION,
                current.origin() + "\n" + String.join(", ", current.dnsAddresses()) + "\n到期：" + current.expiresAt(),
                ButtonType.CANCEL,
                ButtonType.OK);
        alert.setTitle("确认私网授权");
        alert.setHeaderText("确认授予 " + SettingsLabels.privateNetworkPurpose(current.purpose()) + " 精确私网访问？");
        PlatformDialogs.style(alert, this);
        if (alert.showAndWait().filter(ButtonType.OK::equals).isPresent()) {
            presenter.confirmCreate();
        }
    }

    private void revoke() {
        PrivateNetworkGrant current = state.selected().orElseThrow();
        Alert alert = new Alert(
                Alert.AlertType.CONFIRMATION, "撤销不可恢复；依赖该版本的 MCP 或网站新请求会立即失败。", ButtonType.CANCEL, ButtonType.OK);
        alert.setTitle("撤销私网授权");
        alert.setHeaderText(current.origin().toString());
        PlatformDialogs.style(alert, this);
        if (alert.showAndWait().filter(ButtonType.OK::equals).isPresent()) {
            presenter.revoke();
        }
    }

    private static Label value() {
        Label label = new Label("—");
        label.setWrapText(true);
        label.getStyleClass().add("platform-detail-text");
        return label;
    }
}
