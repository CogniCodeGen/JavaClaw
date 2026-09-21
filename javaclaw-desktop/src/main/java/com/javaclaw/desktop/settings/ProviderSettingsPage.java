package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.desktop.component.AlertDangerConfirmationPolicy;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.DangerZone;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** 服务选择和单页配置共用稳定视口；读取保持可取消，完整配置只通过原子 SDK 命令保存。 */
public final class ProviderSettingsPage implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final ProviderSettingsPresenter presenter;
    private final ProviderSettingsActions setupActions;
    private final ProviderVerificationPresenter chatVerification;
    private final ProviderVerificationPresenter embeddingVerification;
    private final ProviderEmbeddingBindingPresenter embeddingBinding;
    private final VBox content = components.page("模型服务");
    private final StackPane detail = new StackPane();
    private final VBox summary = new VBox(8);
    private final ProviderServiceBrowser browser = new ProviderServiceBrowser(detail);
    private final StackPane footer = new StackPane();
    private final CoreSettingsGateway gateway;
    private ProviderConfigurationEditor editor;
    private final Label selectedSummary = new Label("请选择模型服务");
    private final Label setupProgress = new Label();
    private final ProviderVerificationSection verificationSection;
    private final ProviderModelCatalogEditor modelCatalog;
    private final ProviderContextEditor contextEditor;
    private final SettingsPageRefresh configurationRefresh;
    private final Button probe;
    private final AsyncActionBar actions;
    private final DangerZone dangerZone;
    private ProviderEmbeddingBindingState embeddingState = ProviderEmbeddingBindingState.initial();
    private ProviderVerificationSettingsState chatVerificationState =
            ProviderVerificationSettingsState.initial(ProviderModelPurpose.CHAT);
    private ProviderVerificationSettingsState embeddingVerificationState =
            ProviderVerificationSettingsState.initial(ProviderModelPurpose.EMBEDDING);
    private boolean rendering;
    private boolean queuedCreate;
    private String savedMessage = "";

    /**
     * 创建模型服务列表页。
     *
     * @param gateway SDK 异步边界
     */
    public ProviderSettingsPage(CoreSettingsGateway gateway) {
        this(gateway, () -> {});
    }

    /**
     * 创建列表页并保留独立的模型使用入口。
     *
     * @param gateway SDK 异步边界
     * @param used 独立使用模型成功后的导航回调，配置保存不调用
     */
    public ProviderSettingsPage(CoreSettingsGateway gateway, Runnable used) {
        this.gateway = gateway;
        presenter = new ProviderSettingsPresenter(gateway);
        chatVerification = new ProviderVerificationPresenter(gateway, ProviderModelPurpose.CHAT);
        embeddingVerification = new ProviderVerificationPresenter(gateway, ProviderModelPurpose.EMBEDDING);
        embeddingBinding = new ProviderEmbeddingBindingPresenter(gateway);
        modelCatalog = new ProviderModelCatalogEditor(() -> {}, ignored -> {}, this::bindEmbeddingModel);
        modelCatalog.readOnly();
        contextEditor = new ProviderContextEditor(gateway, presenter::reload);
        setupActions = new ProviderSettingsActions(
                gateway, presenter, modelCatalog, content, used, () -> dirty() || pending() || editor != null);
        setupActions.onConfigure(this::configure);
        modelCatalog.onModelSelected(
                ignored -> bindModelContext(), () -> !pending() && contextEditor.allowModelChange());
        probe = components.action("本地检查", ActionStyle.SOFT, ActionSize.NORMAL);
        probe.setOnAction(event -> presenter.probe());
        actions = new AsyncActionBar();
        dangerZone = new DangerZone(
                "归档模型服务",
                "归档后保留历史任务，不能用于新Agent或新任务。",
                "归档当前模型服务",
                new AlertDangerConfirmationPolicy(content),
                presenter::archive);
        verificationSection = new ProviderVerificationSection(
                components, chatVerification::verify, embeddingVerification::verify, this::bindVerifications);
        buildLayout();
        bindSelection();
        configurationRefresh = SettingsPageRefresh.provider(gateway, this, presenter, embeddingBinding);
        presenter.subscribe(this::render);
        chatVerification.subscribe(this::renderVerification);
        embeddingVerification.subscribe(this::renderVerification);
        embeddingBinding.subscribe(this::renderEmbeddingBinding);
        contextEditor.onStateChanged(this::bindVerifications);
    }

    @Override
    public Node content() {
        return content;
    }

    @Override
    public Optional<Node> actionContent() {
        return Optional.of(footer);
    }

    /** @return 页面自己分配服务列表、编辑器及表单视口，不再由管理中心包装滚动层 */
    @Override
    public boolean ownsViewport() {
        return true;
    }

    /** 从聊天添加入口定位到当前设置页；已有草稿或未决写入会阻止替换。 */
    public void beginCreate() {
        if (presenter.state().phase() == SettingsLoadState.LOADING && editor == null && !dirty()) {
            queuedCreate = true;
            return;
        }
        configure(Optional.empty());
    }

    @Override
    public void activate() {
        configurationRefresh.activate();
    }

    @Override
    public void invalidateCache() {
        configurationRefresh.invalidate();
    }

    @Override
    public void deactivate() {
        queuedCreate = false;
        if (editor != null) {
            editor.cancelPreview();
        }
        configurationRefresh.deactivate();
    }

    @Override
    public void dispose() {
        closeEditor();
        configurationRefresh.close();
    }

    @Override
    public void workspaceChanged(Optional<com.javaclaw.api.Workspace> workspace) {
        setupActions.workspaceChanged(workspace);
    }

    @Override
    public boolean dirty() {
        return presenter.state().dirty() || contextEditor.dirty() || (editor != null && editor.dirty());
    }

    @Override
    public boolean pending() {
        return presenter.state().phase() == SettingsLoadState.SAVING
                || (editor != null && editor.pending())
                || advancedPending();
    }

    private boolean advancedPending() {
        return verificationSection != null && verificationSection.confirming()
                || chatVerificationState.pending()
                || embeddingVerificationState.pending()
                || embeddingState.saving()
                || contextEditor.saving();
    }

    @Override
    public void warnUnsavedChanges() {
        if (editor != null) {
            editor.warnUnsavedChanges();
        } else {
            contextEditor.warnUnsavedChanges();
            presenter.warnUnsavedChanges();
        }
    }

    @Override
    public void discardDraft() {
        closeEditor();
        contextEditor.discardDraft();
        presenter.discardDraft();
    }

    private void buildLayout() {
        browser.creationAction(setupActions.creationAction());
        content.setMinSize(0, 0);
        detail.setMinSize(0, 0);
        selectedSummary.setWrapText(true);
        selectedSummary.setMinWidth(0);
        selectedSummary.setId("providerSelectedSummary");
        setupProgress.setWrapText(true);
        setupProgress.getStyleClass().add("sec-hint");
        VBox tools = new VBox(12, modelCatalog.embeddingAction(), contextEditor, probe, verificationSection.content());
        ScrollPane toolScroll = new ScrollPane(tools);
        toolScroll.setFitToWidth(true);
        toolScroll.setPrefViewportHeight(200);
        toolScroll.setMinHeight(0);
        TitledPane management = new TitledPane("所选模型的验证与容量", toolScroll);
        management.setExpanded(false);
        management.setAnimated(false);
        TitledPane archive = new TitledPane("归档服务", dangerZone);
        archive.setExpanded(false);
        archive.setAnimated(false);
        summary.setMinSize(0, 0);
        summary.getChildren().addAll(selectedSummary, modelCatalog, management, archive);
        VBox.setVgrow(modelCatalog, Priority.ALWAYS);
        detail.getChildren().setAll(summary);
        footer.getChildren().setAll(actions);
        content.getChildren().addAll(setupActions.content(), setupProgress, browser);
        VBox.setVgrow(browser, Priority.ALWAYS);
    }

    private void bindSelection() {
        browser.onSelected(selected -> {
            if (rendering) {
                return;
            }
            if (!mayReplaceDraft()) {
                browser.render(presenter.state().providers(), presenter.state().selected());
                return;
            }
            closeEditor();
            savedMessage = "";
            presenter.select(selected);
        });
    }

    private boolean mayReplaceDraft() {
        if (pending()) {
            return false;
        }
        if (dirty()) {
            warnUnsavedChanges();
            return false;
        }
        return true;
    }

    private void configure(Optional<ProviderEndpoint> source) {
        if (!mayReplaceDraft()) {
            return;
        }
        long credentialRevision = source.isPresent()
                ? presenter
                        .state()
                        .credential()
                        .map(CredentialMetadata::revision)
                        .orElse(0L)
                : 0;
        if (source.flatMap(value -> value.spec().credential()).isPresent() && credentialRevision < 1) {
            actions.show(ActionState.ERROR, "密钥元数据尚未读取，请刷新后编辑。");
            return;
        }
        closeEditor();
        savedMessage = "";
        editor = new ProviderConfigurationEditor(
                gateway, source, credentialRevision, this::saved, this::discardDraft, this::reloadConfiguration);
        browser.creating(source.isEmpty());
        editor.onChanged(this::editorChanged);
        detail.getChildren().setAll(editor);
        footer.getChildren().setAll(editor.actionContent());
        editorChanged();
    }

    private void editorChanged() {
        setEditingLayout(editor != null);
        browser.lock(pending());
        setupActions.render();
    }

    private void setEditingLayout(boolean editing) {
        Node title = content.getChildren().getFirst();
        title.setVisible(!editing);
        title.setManaged(!editing);
        setupActions.content().setVisible(!editing);
        setupActions.content().setManaged(!editing);
        setupProgress.setVisible(!editing);
        setupProgress.setManaged(!editing);
    }

    private void saved(ProviderConfigurationResult result) {
        closeEditor();
        savedMessage = "已保存，包含 " + result.provider().spec().models().size() + " 个模型。";
        presenter.select(result.provider());
        presenter.reload();
        actions.show(
                ActionState.SUCCESS,
                "已保存，包含 " + result.provider().spec().models().size() + " 个模型。");
    }

    private void reloadConfiguration() {
        // 重新读取是显式放弃冲突草稿，不能在后台刷新时自动推进 expectedRevision。
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "重新读取会放弃当前未保存修改和临时密钥，是否继续？",
                javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);
        com.javaclaw.desktop.component.PlatformDialogs.style(
                confirm, content.getScene().getWindow());
        if (confirm.showAndWait()
                .filter(javafx.scene.control.ButtonType.OK::equals)
                .isPresent()) {
            discardDraft();
            presenter.reload();
        }
    }

    private void closeEditor() {
        if (editor != null) {
            editor.close();
            editor = null;
        }
        detail.getChildren().setAll(summary);
        footer.getChildren().setAll(actions);
        setEditingLayout(false);
        browser.creating(false);
    }

    private void render(ProviderSettingsState state) {
        rendering = true;
        try {
            browser.render(state.providers(), state.selected());
            selectedSummary.setText(state.selected()
                    .map(endpoint -> endpoint.spec().displayName() + " · "
                            + endpoint.spec().models().size() + " 个模型 · 版本 " + endpoint.revision())
                    .orElse("请选择模型服务"));
            setupProgress.setText(state.setupMessage());
            renderModelCatalog();
            bindModelContext();
            bindVerifications();
        } finally {
            rendering = false;
        }
        tryQueuedCreation();
    }

    private void tryQueuedCreation() {
        if (queuedCreate && presenter.state().phase() == SettingsLoadState.READY && !pending() && !dirty()) {
            queuedCreate = false;
            configure(Optional.empty());
        }
    }

    private void renderStatus(ProviderSettingsState state) {
        setupActions.render();
        boolean unavailable = state.selected()
                .filter(endpoint -> endpoint.lifecycle() != ProviderLifecycle.ARCHIVED)
                .isEmpty();
        browser.lock(pending());
        dangerZone.setActionDisabled(pending() || unavailable || dirty() || (editor != null));
        probe.setDisable(
                pending() || unavailable || dirty() || state.draft().models().isEmpty());
        verificationSection.renderProbe(state.providerStatus());
        if (state.pending()) {
            actions.show(ActionState.PENDING, state.message());
        } else if (state.phase() == SettingsLoadState.ERROR) {
            actions.show(ActionState.ERROR, "模型服务操作未完成，请检查连接后重试。草稿已保留。");
        } else {
            actions.show(
                    state.message().isBlank() && savedMessage.isEmpty() ? ActionState.IDLE : ActionState.SUCCESS,
                    state.message().isBlank() ? savedMessage : state.message());
        }
    }

    private void bindVerifications() {
        ProviderSettingsState state = presenter.state();
        boolean busy = state.pending() || embeddingState.pending() || contextEditor.pending() || (editor != null);
        boolean credential =
                state.credential().isPresent() || state.draft().authentication() == ProviderAuthentication.NONE;
        chatVerification.bind(
                state.selected(), verificationSection.modelId(ProviderModelPurpose.CHAT), credential, dirty(), busy);
        embeddingVerification.bind(
                state.selected(),
                verificationSection.modelId(ProviderModelPurpose.EMBEDDING),
                credential,
                dirty(),
                busy);
        renderStatus(state);
        tryQueuedCreation();
    }

    private void renderVerification(ProviderVerificationSettingsState state) {
        if (state.purpose() == ProviderModelPurpose.CHAT) {
            chatVerificationState = Objects.requireNonNull(state, "state");
        } else {
            embeddingVerificationState = Objects.requireNonNull(state, "state");
        }
        verificationSection.renderVerification(state);
        renderModelCatalog();
        renderStatus(presenter.state());
    }

    private void renderEmbeddingBinding(ProviderEmbeddingBindingState state) {
        embeddingState = state;
        renderModelCatalog();
        bindVerifications();
    }

    private void renderModelCatalog() {
        ProviderSettingsState state = presenter.state();
        modelCatalog.render(
                new ProviderModelCatalogState(
                        state.draft().adapter(),
                        state.draft().models(),
                        state.selected(),
                        List.of(),
                        embeddingState.binding(),
                        state.providerStatus(),
                        java.util.stream.Stream.of(chatVerificationState.result(), embeddingVerificationState.result())
                                .flatMap(Optional::stream)
                                .toList(),
                        embeddingState.pending(),
                        embeddingState.message()),
                false,
                false,
                state.selected()
                                .filter(endpoint -> endpoint.lifecycle() != ProviderLifecycle.ARCHIVED)
                                .isPresent()
                        && !dirty()
                        && !pending());
    }

    private void bindModelContext() {
        setupActions.render();
        ProviderSettingsState state = presenter.state();
        verificationSection.renderModels(
                Optional.ofNullable(modelCatalog.selectedModel()).map(List::of).orElse(List.of()));
        var selected = Optional.ofNullable(modelCatalog.selectedModel())
                .filter(model -> model.supports(ProviderModelPurpose.CHAT));
        contextEditor.bind(state.selected()
                .filter(endpoint ->
                        !state.dirty() && !state.pending() && endpoint.lifecycle() != ProviderLifecycle.ARCHIVED)
                .flatMap(endpoint -> selected.map(model ->
                        new com.javaclaw.api.ProviderRef(endpoint.id(), endpoint.revision(), model.modelId()))));
        bindVerifications();
    }

    private void bindEmbeddingModel(ProviderModelSpec model) {
        ProviderEndpoint endpoint = presenter.state().selected().orElseThrow();
        embeddingBinding.bind(endpoint, model);
    }
}
