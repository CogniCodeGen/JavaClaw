package com.javaclaw.desktop.settings;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;

import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** 列表上的统一配置入口；使用模型保持独立动作，配置完成只刷新并选中保存结果。 */
final class ProviderSettingsActions {
    private final CoreSettingsGateway gateway;
    private final ProviderSettingsPresenter presenter;
    private final ProviderModelCatalogEditor catalog;
    private final Node owner;
    private final Runnable used;
    private final BooleanSupplier blocked;
    private final Button create;
    private final Button edit;
    private final Button use;
    private final Button reload;
    private final FlowPane content;
    private final Label configurationReason = new Label();
    private Optional<Workspace> workspace = Optional.empty();
    private Consumer<Optional<ProviderEndpoint>> configure = ignored -> {};

    ProviderSettingsActions(
            CoreSettingsGateway gateway,
            ProviderSettingsPresenter presenter,
            ProviderModelCatalogEditor catalog,
            Node owner,
            Runnable used,
            BooleanSupplier blocked) {
        this.gateway = gateway;
        this.presenter = presenter;
        this.catalog = catalog;
        this.owner = owner;
        this.used = used;
        this.blocked = blocked;
        PlatformComponentFactory components = new PlatformComponentFactory();
        create = components.action("添加服务", ActionStyle.PRIMARY, ActionSize.COMPACT);
        create.setId("providerCreateButton");
        create.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        create.setOnAction(event -> configure.accept(Optional.empty()));
        edit = components.action("编辑", ActionStyle.SOFT, ActionSize.COMPACT);
        edit.setId("providerConfigureButton");
        edit.setOnAction(event -> configure.accept(presenter.state().selected()));
        use = components.action("使用此模型", ActionStyle.SOFT, ActionSize.COMPACT);
        use.setId("providerUseModelButton");
        use.setOnAction(event -> useModel());
        reload = components.action("刷新", ActionStyle.GHOST, ActionSize.COMPACT);
        reload.setOnAction(event -> presenter.reload());
        configurationReason.setId("providerConfigurationReason");
        configurationReason.setWrapText(true);
        configurationReason.setMinHeight(Region.USE_PREF_SIZE);
        configurationReason.getStyleClass().add("sec-hint");
        content = new FlowPane(8, 8, edit, use, reload, configurationReason);
    }

    Node content() {
        return content;
    }

    Node creationAction() {
        return create;
    }

    void onConfigure(Consumer<Optional<ProviderEndpoint>> listener) {
        configure = listener;
    }

    void workspaceChanged(Optional<Workspace> value) {
        workspace = value;
        render();
    }

    void render() {
        boolean busy = presenter.state().pending() || presenter.state().dirty() || blocked.getAsBoolean();
        create.setDisable(busy);
        reload.setDisable(busy);
        String reason = credentialUnavailable();
        configurationReason.setText(reason);
        configurationReason.setVisible(!reason.isEmpty());
        configurationReason.setManaged(!reason.isEmpty());
        edit.setDisable(busy
                || !reason.isEmpty()
                || presenter
                        .state()
                        .selected()
                        .filter(value -> value.lifecycle() != ProviderLifecycle.ARCHIVED)
                        .isEmpty());
        use.setDisable(busy
                || selectedReference().isEmpty()
                || workspace
                        .filter(value -> value.lifecycle() == WorkspaceLifecycle.ARCHIVED)
                        .isPresent());
        use.setText("使用此模型");
    }

    private String credentialUnavailable() {
        ProviderSettingsState state = presenter.state();
        boolean bound =
                state.selected().flatMap(value -> value.spec().credential()).isPresent();
        if (!bound || state.credential().isPresent()) {
            return "";
        }
        return state.message().isBlank() ? "正在读取已有密钥版本，完成后可编辑所选服务。" : "已有密钥状态暂不可用，请检查连接后刷新。";
    }

    private void useModel() {
        selectedReference()
                .ifPresent(reference -> ProviderSetupWizard.useModel(
                        owner.getScene().getWindow(), gateway, target(), reference, () -> {}, used));
    }

    private Optional<ProviderRef> selectedReference() {
        return presenter
                .state()
                .selected()
                .filter(value -> value.lifecycle() == ProviderLifecycle.ACTIVE)
                .flatMap(value -> selectedModel(value)
                        .map(model -> new ProviderRef(value.id(), value.revision(), model.modelId())));
    }

    private Optional<ProviderModelSpec> selectedModel(ProviderEndpoint endpoint) {
        Optional<ProviderModelSpec> selected = Optional.ofNullable(catalog.selectedModel())
                .filter(model -> model.supports(ProviderModelPurpose.CHAT))
                .filter(model -> endpoint.spec().models().contains(model));
        if (selected.isPresent()) {
            return selected;
        }
        var chatModels = endpoint.spec().models().stream()
                .filter(model -> model.supports(ProviderModelPurpose.CHAT))
                .toList();
        return chatModels.size() == 1 ? Optional.of(chatModels.getFirst()) : Optional.empty();
    }

    private ProviderSetupTarget target() {
        return new ProviderSetupTarget(
                workspace.map(Workspace::id),
                Optional.empty(),
                workspace.map(Workspace::name).orElse(""));
    }
}
