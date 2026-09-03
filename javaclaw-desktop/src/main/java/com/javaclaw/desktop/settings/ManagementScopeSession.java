package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.component.PlatformDialogs;

/** 协调作用域选择、离页保护与当前页面绑定，不承担窗口导航职责。 */
final class ManagementScopeSession {
    private static final ButtonType DISCARD = new ButtonType("丢弃并切换", ButtonBar.ButtonData.OK_DONE);
    private final ManagementScopePresenter presenter;
    private final ManagementScopeControl control;
    private final Supplier<ManagedSettingsPage> activePage;
    private final Consumer<Boolean> writeAvailability;
    private Optional<Workspace> applied = Optional.empty();

    ManagementScopeSession(
            CoreSettingsGateway gateway,
            Supplier<Optional<WorkspaceId>> preferredWorkspace,
            Supplier<ManagedSettingsPage> activePage,
            Consumer<Boolean> writeAvailability) {
        this.activePage = Objects.requireNonNull(activePage, "activePage");
        this.writeAvailability = Objects.requireNonNull(writeAvailability, "writeAvailability");
        presenter = new ManagementScopePresenter(gateway, preferredWorkspace);
        control = new ManagementScopeControl(this::requestSelection, this::requestReload);
        presenter.subscribe(this::render);
    }

    Node content() {
        return control;
    }

    void activate() {
        presenter.reload();
    }

    void bind(ManagedSettingsPage page) {
        Objects.requireNonNull(page, "page").workspaceChanged(presenter.state().frozenSelection());
        writeAvailability.accept(presenter.state().availableSelection().isPresent());
    }

    void requestReload() {
        ManagedSettingsPage page = activePage.get();
        if (page != null && page.pending()) {
            showBlocked("操作正在进行", "请等待当前写操作完成后再重新读取工作区。");
            control.render(presenter.state());
            return;
        }
        if (page != null && page.dirty()) {
            page.warnUnsavedChanges();
            control.render(presenter.state());
            return;
        }
        presenter.reload();
    }

    private void requestSelection(Workspace requested) {
        ManagedSettingsPage page = activePage.get();
        if (page != null && page.pending()) {
            showBlocked("操作正在进行", "请等待当前写操作完成后再切换工作区。");
            control.render(presenter.state());
            return;
        }
        if (page != null && page.dirty() && !confirmDiscard()) {
            page.warnUnsavedChanges();
            control.render(presenter.state());
            return;
        }
        if (page != null && page.dirty()) {
            page.discardDraft();
        }
        presenter.select(requested);
    }

    private void render(ManagementScopeState state) {
        control.render(state);
        writeAvailability.accept(state.availableSelection().isPresent());
        Optional<Workspace> frozen = state.frozenSelection();
        if (applied.equals(frozen)) {
            return;
        }
        applied = frozen;
        ManagedSettingsPage page = activePage.get();
        if (page != null) {
            page.workspaceChanged(applied);
        }
    }

    private boolean confirmDiscard() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "切换工作区会丢弃当前页面的未保存修改。", ButtonType.CANCEL, DISCARD);
        initialize(alert);
        alert.setTitle("切换工作区");
        alert.setHeaderText("是否丢弃草稿？");
        return alert.showAndWait().filter(DISCARD::equals).isPresent();
    }

    private void showBlocked(String header, String detail) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, detail, ButtonType.OK);
        initialize(alert);
        alert.setTitle("暂时无法切换工作区");
        alert.setHeaderText(header);
        alert.showAndWait();
    }

    private void initialize(Alert alert) {
        PlatformDialogs.style(alert, control);
    }
}
