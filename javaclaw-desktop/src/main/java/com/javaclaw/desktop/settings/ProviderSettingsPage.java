package com.javaclaw.desktop.settings;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.DangerZone;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.ListDetailPane;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.RevisionConflictPane;
import com.javaclaw.desktop.component.SecretStatusField;
import com.javaclaw.protocol.ProviderVerificationRpcContracts;

/** Provider 列表、强类型配置、Vault Secret 和本地探测页面。 */
public final class ProviderSettingsPage implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final ProviderSettingsPresenter presenter;
    private final ProviderVerificationPresenter verification;
    private final VBox content = components.page("Provider");
    private final ListDetailPane<ProviderEndpoint> masterDetail = new ListDetailPane<>();
    private final TextField id = new TextField();
    private final TextField displayName = new TextField();
    private final ComboBox<ProviderAdapter> adapter = new ComboBox<>();
    private final TextField baseUri = new TextField();
    private final CheckBox chat = new CheckBox("Chat / Tool Call");
    private final CheckBox embedding = new CheckBox("Embedding");
    private final TextArea models = new TextArea();
    private final TextField timeout = new TextField();
    private final TextField retries = new TextField();
    private final TextArea options = new TextArea();
    private final ComboBox<ProviderLifecycle> lifecycle = new ComboBox<>();
    private final PasswordField secret = new PasswordField();
    private final HBox secretEditor = new HBox(8);
    private final SecretStatusField secretStatus;
    private final Label probeStatus = new Label();
    private final ComboBox<String> verificationModel = new ComboBox<>();
    private final Label verificationStatus = new Label();
    private final Button save;
    private final Button discard;
    private final Button probe;
    private final Button verifyRoundTrip;
    private final AsyncActionBar actions;
    private final RevisionConflictPane conflict;
    private final DangerZone dangerZone;
    private final VBox form = new VBox(12);
    private boolean rendering;

    /**
     * 创建 Provider 设置页。
     *
     * @param gateway SDK 异步边界
     */
    public ProviderSettingsPage(CoreSettingsGateway gateway) {
        presenter = new ProviderSettingsPresenter(gateway);
        verification = new ProviderVerificationPresenter(gateway);
        save = components.action("保存 Provider", ActionStyle.PRIMARY, ActionSize.NORMAL);
        discard = components.action("放弃更改", ActionStyle.GHOST, ActionSize.NORMAL);
        probe = components.action("本地检查", ActionStyle.SOFT, ActionSize.NORMAL);
        verifyRoundTrip = components.action("执行计费验证", ActionStyle.DANGER, ActionSize.NORMAL);
        actions = new AsyncActionBar(discard, probe, save);
        conflict = new RevisionConflictPane(presenter::reload, this::showConflictComparison);
        dangerZone = new DangerZone(
                "归档 Provider", "归档后不会删除历史 Turn，但不能用于新 Profile 或新 Turn。", "归档当前 Provider", presenter::archive);
        secretStatus = new SecretStatusField(this::openSecretEditor, presenter::clearSecret);
        configureControls();
        buildLayout();
        bindEvents();
        presenter.subscribe(this::render);
        verification.subscribe(this::renderVerification);
    }

    @Override
    public Node content() {
        return content;
    }

    @Override
    public void activate() {
        presenter.reload();
    }

    @Override
    public boolean dirty() {
        return presenter.state().dirty();
    }

    @Override
    public void warnUnsavedChanges() {
        presenter.warnUnsavedChanges();
    }

    @Override
    public void discardDraft() {
        presenter.discardDraft();
    }

    private void configureControls() {
        id.setPromptText("例如 openai-primary");
        displayName.setPromptText("用户可见名称");
        adapter.setItems(FXCollections.observableArrayList(ProviderAdapter.values()));
        baseUri.setPromptText("官方默认地址可留空；自定义地址必须是 HTTP(S)");
        models.setPromptText("每行一个 Provider 原生 model ID");
        models.setPrefRowCount(4);
        timeout.setPromptText("60");
        retries.setPromptText("0");
        options.setPromptText("非敏感厂商选项，每行 key=value");
        options.setPrefRowCount(3);
        lifecycle.setItems(FXCollections.observableArrayList(ProviderLifecycle.ACTIVE, ProviderLifecycle.DISABLED));
        secret.setPromptText("只用于本次写入，不会回读");
        secret.setId("providerSecretInput");
        probeStatus.setWrapText(true);
        probeStatus.getStyleClass().add("sec-hint");
        verificationStatus.setWrapText(true);
        verificationStatus.getStyleClass().add("sec-hint");
        hideSecretEditor();
    }

    private void buildLayout() {
        Label hint = new Label("配置模型端点、模型目录和 Vault 引用。保存与探测都通过 Java SDK 执行，探测不会调用模型。 ");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        Button create = components.action("新建", ActionStyle.PRIMARY, ActionSize.COMPACT);
        create.setId("providerCreateButton");
        create.setOnAction(event -> presenter.createDraft());
        Button reload = components.action("刷新", ActionStyle.GHOST, ActionSize.COMPACT);
        reload.setOnAction(event -> presenter.reload());
        HBox toolbar = new HBox(8, create, reload);
        masterDetail
                .list()
                .setCellFactory(ignored -> components.detailCell(
                        endpoint -> endpoint.spec().displayName(),
                        endpoint -> endpoint.id() + " · v" + endpoint.revision() + " · " + endpoint.lifecycle()));
        form.getChildren()
                .addAll(
                        identitySection(),
                        modelSection(),
                        secretSection(),
                        statusSection(),
                        conflict,
                        actions,
                        dangerZone);
        masterDetail.showDetail(form);
        VBox.setVgrow(masterDetail, Priority.ALWAYS);
        content.getChildren().addAll(hint, toolbar, masterDetail);
    }

    private FormSection identitySection() {
        FormSection section = new FormSection("端点", "ID 创建后不可修改；停用是实时 kill switch，归档后不能再供新 Profile 使用。");
        section.addField("Provider ID", id);
        section.addField("显示名称", displayName);
        section.addField("Adapter", adapter);
        section.addField("Base URI", baseUri);
        section.addField("状态", lifecycle);
        return section;
    }

    private FormSection modelSection() {
        FormSection section = new FormSection("模型与调用", "模型 ID 必须来自此目录；选项禁止包含 token、password、cookie 等敏感值。");
        section.addField("角色", new HBox(12, chat, embedding));
        section.addField("模型目录", models);
        section.addField("超时（秒）", timeout);
        section.addField("最大重试", retries);
        section.addField("Adapter 选项", options);
        return section;
    }

    private FormSection secretSection() {
        Button apply = components.action("写入 Secret", ActionStyle.PRIMARY, ActionSize.COMPACT);
        apply.setOnAction(event -> submitSecret());
        Button cancel = components.action("取消", ActionStyle.GHOST, ActionSize.COMPACT);
        cancel.setOnAction(event -> hideSecretEditor());
        HBox.setHgrow(secret, Priority.ALWAYS);
        secretEditor.getChildren().addAll(secret, apply, cancel);
        FormSection section = new FormSection("Credential", "只显示配置状态。Secret 在进入 JSON-RPC 前使用会话公钥封装，明文不会回读、复制或导出。");
        section.addField("Vault 状态", secretStatus);
        section.addFullWidth(secretEditor);
        return section;
    }

    private FormSection statusSection() {
        FormSection section =
                new FormSection("运行状态", "本地检查不调用模型；显式计费验证会发送一个受固定小预算约束的无工具请求，可能产生真实费用，且不会保存 Prompt 或响应正文。");
        section.addField("最近检查", probeStatus);
        section.addField("验证模型", verificationModel);
        section.addField("计费验证", verificationStatus);
        section.addFullWidth(verifyRoundTrip);
        return section;
    }

    private void bindEvents() {
        masterDetail.list().getSelectionModel().selectedItemProperty().addListener((ignored, previous, selected) -> {
            if (!rendering && selected != null) {
                presenter.select(selected);
            }
        });
        id.textProperty().addListener((ignored, previous, value) -> draftChanged());
        displayName.textProperty().addListener((ignored, previous, value) -> draftChanged());
        adapter.valueProperty().addListener((ignored, previous, value) -> draftChanged());
        baseUri.textProperty().addListener((ignored, previous, value) -> draftChanged());
        chat.selectedProperty().addListener((ignored, previous, value) -> draftChanged());
        embedding.selectedProperty().addListener((ignored, previous, value) -> draftChanged());
        models.textProperty().addListener((ignored, previous, value) -> draftChanged());
        timeout.textProperty().addListener((ignored, previous, value) -> draftChanged());
        retries.textProperty().addListener((ignored, previous, value) -> draftChanged());
        options.textProperty().addListener((ignored, previous, value) -> draftChanged());
        lifecycle.valueProperty().addListener((ignored, previous, value) -> draftChanged());
        save.setOnAction(event -> presenter.save());
        discard.setOnAction(event -> presenter.discardDraft());
        probe.setOnAction(event -> presenter.probe());
        verifyRoundTrip.setOnAction(event -> confirmRoundTrip());
        verificationModel.valueProperty().addListener((ignored, previous, value) -> {
            if (!rendering) {
                bindVerification(presenter.state());
            }
        });
    }

    private void draftChanged() {
        if (rendering || adapter.getValue() == null || lifecycle.getValue() == null) {
            return;
        }
        ProviderDraft previous = presenter.state().draft();
        presenter.updateDraft(new ProviderDraft(
                id.getText(),
                displayName.getText(),
                adapter.getValue(),
                baseUri.getText(),
                chat.isSelected(),
                embedding.isSelected(),
                models.getText(),
                previous.credential(),
                integer(timeout.getText()),
                integer(retries.getText()),
                options.getText(),
                lifecycle.getValue()));
    }

    private void render(ProviderSettingsState state) {
        rendering = true;
        try {
            masterDetail.list().getItems().setAll(state.providers());
            masterDetail.list().getSelectionModel().select(state.selected().orElse(null));
            renderDraft(state.draft(), state.selected().isPresent());
            renderStatus(state);
            updateVerificationModels(state);
            bindVerification(state);
        } finally {
            rendering = false;
        }
    }

    private void renderDraft(ProviderDraft draft, boolean existing) {
        id.setText(draft.id());
        id.setDisable(existing);
        displayName.setText(draft.displayName());
        adapter.setValue(draft.adapter());
        baseUri.setText(draft.baseUri());
        chat.setSelected(draft.chat());
        embedding.setSelected(draft.embedding());
        models.setText(draft.models());
        timeout.setText(Integer.toString(draft.timeoutSeconds()));
        retries.setText(Integer.toString(draft.maximumRetries()));
        options.setText(draft.options());
        lifecycle.setValue(
                draft.lifecycle() == ProviderLifecycle.ARCHIVED ? ProviderLifecycle.DISABLED : draft.lifecycle());
    }

    private void renderStatus(ProviderSettingsState state) {
        boolean pending = state.pending();
        boolean archived = state.selected()
                .map(endpoint -> endpoint.lifecycle() == ProviderLifecycle.ARCHIVED)
                .orElse(false);
        form.setDisable(state.phase() == SettingsLoadState.LOADING);
        renderButtons(state, pending, archived);
        renderSecretStatus(state, pending, archived);
        renderProbeStatus(state);
        renderConflict(state);
        renderActionState(state);
    }

    private void renderButtons(ProviderSettingsState state, boolean pending, boolean archived) {
        save.setDisable(pending || archived || !state.dirty());
        discard.setDisable(pending || !state.dirty());
        dangerZone.setActionDisabled(pending || state.selected().isEmpty() || archived || state.dirty());
        probe.setDisable(pending || state.selected().isEmpty() || archived || state.dirty());
    }

    private void renderSecretStatus(ProviderSettingsState state, boolean pending, boolean archived) {
        secretStatus.setConfigured(state.credential().isPresent());
        if (state.selected().flatMap(endpoint -> endpoint.spec().credential()).isPresent()
                && state.credential().isEmpty()) {
            secretStatus.setStatusText("引用缺失或 Vault 不可用");
        }
        secretStatus.setActionsDisabled(pending || state.selected().isEmpty() || archived || state.dirty());
    }

    private void renderProbeStatus(ProviderSettingsState state) {
        probeStatus.setText(state.providerStatus()
                .map(status -> status.readiness() + " · " + status.checkedAt())
                .orElse("尚未检查"));
    }

    private void renderConflict(ProviderSettingsState state) {
        if (state.revisionConflict()) {
            conflict.showUnknownActual(
                    state.selected().map(ProviderEndpoint::revision).orElse(0L));
        } else {
            conflict.hide();
        }
    }

    private void renderActionState(ProviderSettingsState state) {
        if (state.phase() == SettingsLoadState.LOADING || state.phase() == SettingsLoadState.SAVING) {
            actions.show(ActionState.PENDING, state.message());
        } else if (state.phase() == SettingsLoadState.ERROR) {
            actions.show(ActionState.ERROR, state.message());
        } else if (state.dirty()) {
            actions.show(ActionState.DIRTY, state.message().isBlank() ? "Provider 草稿尚未保存" : state.message());
        } else if (!state.message().isBlank()) {
            actions.show(ActionState.SUCCESS, state.message());
        } else {
            actions.show(ActionState.IDLE, "");
        }
    }

    private void updateVerificationModels(ProviderSettingsState state) {
        String previous = verificationModel.getValue();
        List<String> available =
                state.selected().map(endpoint -> endpoint.spec().models()).orElse(List.of());
        verificationModel.getItems().setAll(available);
        String selected = Optional.ofNullable(previous)
                .filter(available::contains)
                .orElseGet(() -> available.stream().findFirst().orElse(null));
        verificationModel.setValue(selected);
    }

    private void bindVerification(ProviderSettingsState state) {
        verification.bind(
                state.selected(),
                Optional.ofNullable(verificationModel.getValue()),
                state.credential().isPresent(),
                state.pending() || state.dirty());
    }

    private void renderVerification(ProviderVerificationSettingsState state) {
        verifyRoundTrip.setDisable(state.pending() || !state.available());
        verificationModel.setDisable(state.pending());
        verificationStatus.setText(state.result()
                .map(result -> result.state()
                        + " · "
                        + result.latencyMillis()
                        + " ms · usage "
                        + result.usage()
                                .map(value -> value.inputTokens() + "/" + value.outputTokens())
                                .orElse("未知"))
                .orElse(state.message()));
    }

    private void confirmRoundTrip() {
        TextInputDialog dialog = new TextInputDialog();
        if (content.getScene() != null && content.getScene().getWindow() != null) {
            dialog.initOwner(content.getScene().getWindow());
        }
        dialog.setTitle("确认可能计费的模型验证");
        dialog.setHeaderText("此操作会向所选 Provider 发送真实模型请求，可能产生费用");
        dialog.setContentText("精确输入：" + ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
        dialog.showAndWait().ifPresent(value -> verification.verify(true, value));
    }

    private void openSecretEditor() {
        secret.clear();
        secretEditor.setVisible(true);
        secretEditor.setManaged(true);
        secret.requestFocus();
    }

    private void hideSecretEditor() {
        secret.clear();
        secretEditor.setVisible(false);
        secretEditor.setManaged(false);
    }

    private void submitSecret() {
        char[] value = secret.getText().toCharArray();
        hideSecretEditor();
        try {
            presenter.replaceSecret(value);
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    private void showConflictComparison() {
        ProviderSettingsState state = presenter.state();
        String detail = state.selected()
                .map(endpoint -> "本地草稿与已读取 v" + endpoint.revision() + " 不同；重新读取会丢弃草稿。")
                .orElse("新建草稿的 ID 已被占用；请更换 ID 或重新读取。");
        actions.show(ActionState.DIRTY, detail);
    }

    private static int integer(String value) {
        try {
            return Integer.parseInt(Objects.requireNonNullElse(value, "").strip());
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }
}
