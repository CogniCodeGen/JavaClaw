package com.javaclaw.desktop.settings;

import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;

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

/** 设置页的模型添加与使用入口；目标跟随设置页独立作用域，发出操作时冻结。 */
final class ProviderSettingsActions {
    private final CoreSettingsGateway gateway;
    private final ProviderSettingsPresenter presenter;
    private final ProviderModelCatalogEditor catalog;
    private final Node owner;
    private final Button use;
    private final HBox content;
    private Optional<Workspace> workspace = Optional.empty();

    ProviderSettingsActions(
            CoreSettingsGateway gateway,
            ProviderSettingsPresenter presenter,
            ProviderModelCatalogEditor catalog,
            Node owner) {
        this.gateway = gateway;
        this.presenter = presenter;
        this.catalog = catalog;
        this.owner = owner;
        PlatformComponentFactory components = new PlatformComponentFactory();
        Button create = components.action("添加模型", ActionStyle.PRIMARY, ActionSize.COMPACT);
        create.setId("providerCreateButton");
        create.setOnAction(event -> {
            if (presenter.state().dirty()) {
                presenter.warnUnsavedChanges();
                return;
            }
            ProviderSetupWizard.show(owner.getScene().getWindow(), gateway, target(), presenter::reload);
        });
        use = components.action("使用此模型", ActionStyle.SOFT, ActionSize.COMPACT);
        use.setId("providerUseModelButton");
        use.setOnAction(event -> useModel());
        Button reload = components.action("刷新", ActionStyle.GHOST, ActionSize.COMPACT);
        reload.setOnAction(event -> presenter.reload());
        content = new HBox(8, create, use, reload);
    }

    Node content() {
        return content;
    }

    void workspaceChanged(Optional<Workspace> value) {
        workspace = value;
        render();
    }

    void render() {
        use.setDisable(presenter.state().pending()
                || presenter.state().dirty()
                || selectedReference().isEmpty()
                || workspace
                        .filter(value -> value.lifecycle() == WorkspaceLifecycle.ARCHIVED)
                        .isPresent());
        use.setText(workspace.map(value -> "在「" + value.name() + "」中使用此模型").orElse("使用此模型"));
    }

    private void useModel() {
        selectedReference()
                .ifPresent(reference -> ProviderSetupWizard.useModel(
                        owner.getScene().getWindow(), gateway, target(), reference, presenter::reload));
    }

    private Optional<ProviderRef> selectedReference() {
        Optional<ProviderEndpoint> endpoint =
                presenter.state().selected().filter(value -> value.lifecycle() == ProviderLifecycle.ACTIVE);
        return endpoint.flatMap(value ->
                selectedModel(value).map(model -> new ProviderRef(value.id(), value.revision(), model.modelId())));
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
