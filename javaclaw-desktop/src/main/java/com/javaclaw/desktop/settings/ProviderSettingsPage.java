package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderReasoningSummary;
import com.javaclaw.desktop.component.AlertDangerConfirmationPolicy;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.DangerZone;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.ListDetailPane;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.RevisionConflictPane;

/** Provider 列表、逐模型目录、强类型选项、Vault Secret 和运行检查页面。 */
public final class ProviderSettingsPage implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final ProviderSettingsPresenter presenter;
    private final ProviderVerificationPresenter chatVerification;
    private final ProviderVerificationPresenter embeddingVerification;
    private final ProviderModelDiscoveryPresenter discovery;
    private final ProviderEmbeddingBindingPresenter embeddingBinding;
    private final ProviderProfileReferencePanel profileReferences;
    private final VBox content = components.page("模型服务");
    private final ListDetailPane<ProviderEndpoint> masterDetail = new ListDetailPane<>();
    private final Label id = new Label("—");
    private final TextField displayName = new TextField();
    private final ComboBox<ProviderAdapter> adapter = new ComboBox<>();
    private final TextField baseUri = new TextField();
    private final ComboBox<ProviderAuthentication> authentication = new ComboBox<>();
    private final ComboBox<ProviderLifecycle> lifecycle = new ComboBox<>();
    private final Label endpointPreview = new Label();
    private final Label setupProgress = new Label();
    private final TextField timeout = new TextField();
    private final TextField retries = new TextField();
    private final TextField organization = new TextField();
    private final TextField project = new TextField();
    private final TextField apiVersion = new TextField();
    private final ComboBox<ProviderReasoningSummary> reasoningSummary = new ComboBox<>();
    private final ProviderSecretSection secretSection;
    private final ProviderVerificationSection verificationSection;
    private final ProviderModelCatalogEditor modelCatalog;
    private final Button save;
    private final Button discard;
    private final Button probe;
    private final AsyncActionBar actions;
    private final RevisionConflictPane conflict;
    private final DangerZone dangerZone;
    private final VBox form = new VBox(12);
    private ProviderModelDiscoveryState discoveryState = ProviderModelDiscoveryState.initial();
    private ProviderEmbeddingBindingState embeddingState = ProviderEmbeddingBindingState.initial();
    private ProviderVerificationSettingsState chatVerificationState =
            ProviderVerificationSettingsState.initial(ProviderModelPurpose.CHAT);
    private ProviderVerificationSettingsState embeddingVerificationState =
            ProviderVerificationSettingsState.initial(ProviderModelPurpose.EMBEDDING);
    private boolean rendering;

    /**
     * 创建 Provider 设置页。
     *
     * @param gateway SDK 异步边界
     */
    public ProviderSettingsPage(CoreSettingsGateway gateway) {
        presenter = new ProviderSettingsPresenter(gateway);
        chatVerification = new ProviderVerificationPresenter(gateway, ProviderModelPurpose.CHAT);
        embeddingVerification = new ProviderVerificationPresenter(gateway, ProviderModelPurpose.EMBEDDING);
        discovery = new ProviderModelDiscoveryPresenter(gateway);
        embeddingBinding = new ProviderEmbeddingBindingPresenter(gateway);
        profileReferences = new ProviderProfileReferencePanel(gateway);
        modelCatalog =
                new ProviderModelCatalogEditor(this::discoverModels, this::replaceModels, this::bindEmbeddingModel);
        save = components.action("保存模型服务", ActionStyle.PRIMARY, ActionSize.NORMAL);
        discard = components.action("放弃更改", ActionStyle.GHOST, ActionSize.NORMAL);
        probe = components.action("本地检查", ActionStyle.SOFT, ActionSize.NORMAL);
        actions = new AsyncActionBar(discard, probe, save);
        conflict = new RevisionConflictPane(presenter::reload, this::showConflictComparison);
        dangerZone = new DangerZone(
                "归档模型服务",
                "归档后不会删除历史任务，但不能用于新智能体方案或新任务。",
                "归档当前模型服务",
                new AlertDangerConfirmationPolicy(content),
                presenter::archive);
        secretSection = new ProviderSecretSection(components, presenter::replaceSecret, presenter::clearSecret);
        verificationSection = new ProviderVerificationSection(
                components, chatVerification::verify, embeddingVerification::verify, this::bindVerifications);
        configureControls();
        buildLayout();
        bindEvents();
        presenter.subscribe(this::render);
        chatVerification.subscribe(this::renderVerification);
        embeddingVerification.subscribe(this::renderVerification);
        discovery.subscribe(this::renderDiscovery);
        embeddingBinding.subscribe(this::renderEmbeddingBinding);
    }

    @Override
    public Node content() {
        return content;
    }

    @Override
    public Optional<Node> actionContent() {
        return Optional.of(actions);
    }

    @Override
    public void activate() {
        presenter.reload();
        embeddingBinding.reload();
    }

    @Override
    public void deactivate() {
        discovery.reset();
    }

    @Override
    public void dispose() {
        discovery.reset();
    }

    @Override
    public void workspaceChanged(Optional<com.javaclaw.api.Workspace> workspace) {
        discovery.reset();
    }

    @Override
    public boolean dirty() {
        return presenter.state().dirty();
    }

    @Override
    public boolean pending() {
        return presenter.state().pending()
                || chatVerificationState.pending()
                || embeddingVerificationState.pending()
                || discoveryState.pending()
                || embeddingState.pending();
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
        id.setAccessibleText("模型服务技术标识");
        id.setWrapText(true);
        id.getStyleClass().add("platform-detail-text");
        displayName.setPromptText("用户可见名称");
        adapter.setItems(FXCollections.observableArrayList(ProviderAdapter.values()));
        adapter.setConverter(SettingsLabels.converter(SettingsLabels::providerAdapter));
        authentication.setItems(FXCollections.observableArrayList(ProviderAuthentication.values()));
        authentication.setConverter(SettingsLabels.converter(ProviderSettingsPage::authenticationLabel));
        baseUri.setPromptText("官方默认地址可留空；自定义地址必须是 HTTP(S)");
        lifecycle.setItems(FXCollections.observableArrayList(ProviderLifecycle.ACTIVE, ProviderLifecycle.DISABLED));
        lifecycle.setConverter(SettingsLabels.converter(SettingsLabels::providerLifecycle));
        reasoningSummary.setItems(FXCollections.observableArrayList(ProviderReasoningSummary.values()));
        reasoningSummary.setConverter(SettingsLabels.converter(ProviderSettingsPage::reasoningLabel));
        timeout.setPromptText("60");
        retries.setPromptText("0");
        organization.setPromptText("可选 OpenAI organization");
        project.setPromptText("可选 OpenAI project");
        apiVersion.setPromptText("留空使用 Google SDK 默认版本");
        endpointPreview.setWrapText(true);
        endpointPreview.getStyleClass().add("sec-hint");
        setupProgress.setWrapText(true);
        setupProgress.getStyleClass().add("sec-hint");
    }

    private void buildLayout() {
        Label hint = new Label("配置模型连接、逐模型用途和访问密钥。读取模型目录不会执行推理，也不会产生模型费用。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        Button create = components.action("新建", ActionStyle.PRIMARY, ActionSize.COMPACT);
        create.setId("providerCreateButton");
        create.setOnAction(event -> {
            discovery.reset();
            presenter.createDraft();
        });
        Button reload = components.action("刷新", ActionStyle.GHOST, ActionSize.COMPACT);
        reload.setOnAction(event -> presenter.reload());
        configureMasterList();
        form.getChildren()
                .addAll(
                        identitySection(),
                        connectionSection(),
                        modelSection(),
                        secretSection.content(),
                        requestSection(),
                        verificationSection.content(),
                        profileReferences.content(),
                        technicalDetails(),
                        conflict,
                        dangerZone);
        masterDetail.showDetail(form);
        VBox.setVgrow(masterDetail, Priority.ALWAYS);
        content.getChildren().addAll(hint, setupProgress, new HBox(8, create, reload), masterDetail);
    }

    private void configureMasterList() {
        masterDetail
                .list()
                .setCellFactory(ignored -> components.detailCell(
                        endpoint -> endpoint.spec().displayName(),
                        endpoint -> endpoint.id()
                                + " · 版本 "
                                + endpoint.revision()
                                + " · "
                                + SettingsLabels.providerLifecycle(endpoint.lifecycle())));
    }

    private FormSection identitySection() {
        FormSection section = new FormSection("基本信息", "显示名称用于界面识别；技术标识创建后不可修改。");
        section.addField("显示名称", displayName);
        section.addField("接口类型", adapter);
        section.addField("状态", lifecycle);
        return section;
    }

    private FormSection technicalDetails() {
        FormSection section = new FormSection("技术详情", "模型服务标识由 JavaClaw 自动生成；创建后保持不变。");
        section.addField("模型服务标识", id);
        return section;
    }

    private FormSection connectionSection() {
        FormSection section = new FormSection("连接", "无鉴权只能用于明确的自定义 OpenAI 兼容地址。");
        section.addField("服务地址", baseUri);
        section.addField("鉴权方式", authentication);
        section.addField("最终地址", endpointPreview);
        return section;
    }

    private FormSection modelSection() {
        FormSection section = new FormSection("模型", "每个模型单独确认对话或向量用途；远程候选不会自动覆盖已保存目录。");
        section.addFullWidth(modelCatalog);
        return section;
    }

    private FormSection requestSection() {
        FormSection section = new FormSection("请求设置", "只显示当前 Adapter 支持的强类型高级选项，不接受任意 key=value。");
        section.addField("超时（秒）", timeout);
        section.addField("最大重试次数", retries);
        section.addField("OpenAI Organization", organization);
        section.addField("OpenAI Project", project);
        section.addField("Google API Version", apiVersion);
        section.addField("Reasoning Summary", reasoningSummary);
        return section;
    }

    private void bindEvents() {
        masterDetail.list().getSelectionModel().selectedItemProperty().addListener((ignored, previous, selected) -> {
            if (!rendering && selected != null) {
                discovery.reset();
                presenter.select(selected);
            }
        });
        displayName.textProperty().addListener((ignored, previous, value) -> draftChanged());
        adapter.valueProperty().addListener((ignored, previous, value) -> draftChanged());
        baseUri.textProperty().addListener((ignored, previous, value) -> draftChanged());
        authentication.valueProperty().addListener((ignored, previous, value) -> draftChanged());
        lifecycle.valueProperty().addListener((ignored, previous, value) -> draftChanged());
        timeout.textProperty().addListener((ignored, previous, value) -> draftChanged());
        retries.textProperty().addListener((ignored, previous, value) -> draftChanged());
        organization.textProperty().addListener((ignored, previous, value) -> draftChanged());
        project.textProperty().addListener((ignored, previous, value) -> draftChanged());
        apiVersion.textProperty().addListener((ignored, previous, value) -> draftChanged());
        reasoningSummary.valueProperty().addListener((ignored, previous, value) -> draftChanged());
        save.setOnAction(event -> presenter.save());
        discard.setOnAction(event -> presenter.discardDraft());
        probe.setOnAction(event -> presenter.probe());
    }

    private void draftChanged() {
        if (rendering
                || adapter.getValue() == null
                || authentication.getValue() == null
                || lifecycle.getValue() == null
                || reasoningSummary.getValue() == null) {
            return;
        }
        ProviderAdapter selectedAdapter = adapter.getValue();
        ProviderDraft previous = presenter.state().draft();
        presenter.updateDraft(new ProviderDraft(
                id.getText(),
                displayName.getText(),
                selectedAdapter,
                baseUri.getText(),
                authenticationFor(selectedAdapter),
                previous.models(),
                previous.credential(),
                integer(timeout.getText()),
                integer(retries.getText()),
                supportsOpenAiOptions(selectedAdapter) ? organization.getText() : "",
                supportsOpenAiOptions(selectedAdapter) ? project.getText() : "",
                selectedAdapter == ProviderAdapter.GOOGLE_GENAI ? apiVersion.getText() : "",
                selectedAdapter == ProviderAdapter.OPENAI_RESPONSES
                        ? reasoningSummary.getValue()
                        : ProviderReasoningSummary.AUTO,
                lifecycle.getValue()));
    }

    private ProviderAuthentication authenticationFor(ProviderAdapter selectedAdapter) {
        return selectedAdapter == ProviderAdapter.OPENAI_COMPATIBLE
                ? authentication.getValue()
                : ProviderAuthentication.API_KEY;
    }

    private void render(ProviderSettingsState state) {
        rendering = true;
        try {
            masterDetail.list().getItems().setAll(state.providers());
            masterDetail.list().getSelectionModel().select(state.selected().orElse(null));
            renderDraft(state.draft());
            setupProgress.setText(state.setupMessage());
            renderStatus(state);
            verificationSection.renderModels(
                    state.selected().map(endpoint -> endpoint.spec().models()).orElse(List.of()));
            renderModelCatalog();
            bindVerifications();
            profileReferences.bind(state.selected());
        } finally {
            rendering = false;
        }
    }

    private void renderDraft(ProviderDraft draft) {
        id.setText(draft.id());
        displayName.setText(draft.displayName());
        adapter.setValue(draft.adapter());
        baseUri.setText(draft.baseUri());
        authentication.setValue(draft.authentication());
        lifecycle.setValue(
                draft.lifecycle() == ProviderLifecycle.ARCHIVED ? ProviderLifecycle.DISABLED : draft.lifecycle());
        timeout.setText(Integer.toString(draft.timeoutSeconds()));
        retries.setText(Integer.toString(draft.maximumRetries()));
        organization.setText(draft.organization());
        project.setText(draft.project());
        apiVersion.setText(draft.apiVersion());
        reasoningSummary.setValue(draft.reasoningSummary());
        renderAdapterFields(draft.adapter());
        endpointPreview.setText(ProviderEndpointPreview.describe(draft));
    }

    private void renderAdapterFields(ProviderAdapter selectedAdapter) {
        authentication.setDisable(selectedAdapter != ProviderAdapter.OPENAI_COMPATIBLE);
        boolean openAiOptions = supportsOpenAiOptions(selectedAdapter);
        organization.setDisable(!openAiOptions);
        project.setDisable(!openAiOptions);
        apiVersion.setDisable(selectedAdapter != ProviderAdapter.GOOGLE_GENAI);
        reasoningSummary.setDisable(selectedAdapter != ProviderAdapter.OPENAI_RESPONSES);
    }

    private void renderStatus(ProviderSettingsState state) {
        boolean archived = state.selected()
                .map(endpoint -> endpoint.lifecycle() == ProviderLifecycle.ARCHIVED)
                .orElse(false);
        form.setDisable(state.phase() == SettingsLoadState.LOADING);
        save.setDisable(pending() || archived || !state.dirty());
        discard.setDisable(pending() || !state.dirty());
        dangerZone.setActionDisabled(pending() || state.selected().isEmpty() || archived || state.dirty());
        probe.setDisable(pending()
                || state.selected().isEmpty()
                || archived
                || state.dirty()
                || state.draft().models().isEmpty());
        renderSecretStatus(state, archived);
        verificationSection.renderProbe(state.providerStatus());
        if (state.revisionConflict()) {
            conflict.showUnknownActual(
                    state.selected().map(ProviderEndpoint::revision).orElse(0L));
        } else {
            conflict.hide();
        }
        renderActionState(state);
    }

    private void renderSecretStatus(ProviderSettingsState state, boolean archived) {
        boolean none = state.draft().authentication() == ProviderAuthentication.NONE;
        secretSection.render(
                state.credential().isPresent(),
                state.selected()
                                .flatMap(endpoint -> endpoint.spec().credential())
                                .isPresent()
                        && state.credential().isEmpty(),
                pending() || state.selected().isEmpty() || archived || state.dirty() || none);
    }

    private void renderActionState(ProviderSettingsState state) {
        if (state.pending()) {
            actions.show(ActionState.PENDING, state.message());
        } else if (state.phase() == SettingsLoadState.ERROR) {
            actions.show(ActionState.ERROR, state.message());
        } else if (state.dirty()) {
            actions.show(ActionState.DIRTY, state.message().isBlank() ? "模型服务草稿尚未保存" : state.message());
        } else if (!state.message().isBlank()) {
            actions.show(ActionState.SUCCESS, state.message());
        } else {
            actions.show(ActionState.IDLE, "");
        }
    }

    private void bindVerifications() {
        ProviderSettingsState state = presenter.state();
        chatVerification.bind(
                state.selected(),
                verificationSection.modelId(ProviderModelPurpose.CHAT),
                state.credential().isPresent() || state.draft().authentication() == ProviderAuthentication.NONE,
                pending() || state.dirty());
        embeddingVerification.bind(
                state.selected(),
                verificationSection.modelId(ProviderModelPurpose.EMBEDDING),
                state.credential().isPresent() || state.draft().authentication() == ProviderAuthentication.NONE,
                pending() || state.dirty());
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

    private void renderDiscovery(ProviderModelDiscoveryState state) {
        discoveryState = state;
        renderModelCatalog();
        renderStatus(presenter.state());
    }

    private void renderEmbeddingBinding(ProviderEmbeddingBindingState state) {
        embeddingState = state;
        renderModelCatalog();
        renderStatus(presenter.state());
    }

    private void renderModelCatalog() {
        if (modelCatalog == null || presenter == null) {
            return;
        }
        ProviderSettingsState provider = presenter.state();
        List<ProviderModelDiscoveryCandidate> candidates = discoveryState
                .result()
                .filter(result -> provider.selected()
                        .map(endpoint -> endpoint.id().equals(result.endpointId())
                                && endpoint.revision() == result.endpointRevision())
                        .orElse(false))
                .map(result -> result.candidates())
                .orElse(List.of());
        String message = !discoveryState.message().isBlank() ? discoveryState.message() : embeddingState.message();
        boolean exactSaved = provider.selected().isPresent() && !provider.dirty();
        modelCatalog.render(
                new ProviderModelCatalogState(
                        provider.draft().adapter(),
                        provider.draft().models(),
                        provider.selected(),
                        candidates,
                        embeddingState.binding(),
                        provider.providerStatus(),
                        java.util.stream.Stream.of(chatVerificationState.result(), embeddingVerificationState.result())
                                .flatMap(Optional::stream)
                                .toList(),
                        discoveryState.pending() || embeddingState.pending(),
                        message),
                canDiscover(provider),
                canEditCatalog(provider),
                exactSaved);
    }

    private boolean canEditCatalog(ProviderSettingsState state) {
        return state.selected()
                        .filter(endpoint -> endpoint.lifecycle() != ProviderLifecycle.ARCHIVED)
                        .isPresent()
                && (state.draft().authentication() == ProviderAuthentication.NONE
                        || state.credential().isPresent());
    }

    private boolean canDiscover(ProviderSettingsState state) {
        if (state.selected().isEmpty() || state.dirty()) {
            return false;
        }
        return state.draft().authentication() == ProviderAuthentication.NONE
                || state.credential().isPresent();
    }

    private void discoverModels() {
        presenter
                .state()
                .selected()
                .ifPresent(endpoint ->
                        discovery.discover(endpoint, presenter.state().dirty()));
    }

    private void replaceModels(List<ProviderModelSpec> models) {
        ProviderSettingsState state = presenter.state();
        ProviderDraft updated = state.draft().withModels(models);
        if (state.setupPhase() == ProviderSetupPhase.MODELS && !models.isEmpty()) {
            updated = updated.withLifecycle(ProviderLifecycle.ACTIVE);
        }
        presenter.updateDraft(updated);
    }

    private void bindEmbeddingModel(ProviderModelSpec model) {
        ProviderEndpoint endpoint =
                presenter.state().selected().orElseThrow(() -> new IllegalStateException("请先保存模型服务"));
        embeddingBinding.bind(endpoint, model);
    }

    private void showConflictComparison() {
        ProviderSettingsState state = presenter.state();
        String detail = state.selected()
                .map(endpoint -> "本地草稿与已读取的版本 " + endpoint.revision() + " 不同；重新读取会丢弃草稿。")
                .orElse("新建草稿的标识已被占用；请更换标识或重新读取。");
        actions.show(ActionState.DIRTY, detail);
    }

    private static boolean supportsOpenAiOptions(ProviderAdapter value) {
        return value == ProviderAdapter.OPENAI_COMPATIBLE || value == ProviderAdapter.OPENAI_RESPONSES;
    }

    private static String authenticationLabel(ProviderAuthentication value) {
        return value == ProviderAuthentication.API_KEY ? "API Key" : "无鉴权（仅自定义兼容地址）";
    }

    private static String reasoningLabel(ProviderReasoningSummary value) {
        return switch (value) {
            case AUTO -> "自动";
            case CONCISE -> "简短";
            case DETAILED -> "详细";
        };
    }

    private static int integer(String value) {
        try {
            return Integer.parseInt(Objects.requireNonNullElse(value, "").strip());
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }
}
