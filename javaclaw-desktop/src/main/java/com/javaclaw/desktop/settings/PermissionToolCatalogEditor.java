package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javafx.scene.layout.VBox;

import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.WorkspaceId;

/** PermissionProfile 页面使用的精确工具多选子视图。 */
final class PermissionToolCatalogEditor extends VBox {
    private final ToolCatalogPicker picker = new ToolCatalogPicker(ToolCatalogPicker.Mode.MULTIPLE);
    private final ToolCatalogSelectionPresenter presenter;
    private final Runnable changed;
    private Runnable stateChanged = () -> {};
    private ToolCatalogSelectionState state = ToolCatalogSelectionState.initial();
    private Set<String> selectedNames = Set.of();

    /**
     * 创建工具编辑器。
     *
     * @param gateway SDK 设置边界
     * @param changed 选择变化通知
     */
    PermissionToolCatalogEditor(CoreSettingsGateway gateway, Runnable changed) {
        presenter = new ToolCatalogSelectionPresenter(gateway);
        this.changed = Objects.requireNonNull(changed, "changed");
        getChildren().add(picker);
        picker.onSearch(presenter::search);
        picker.onSelection(this::selectionChanged);
        presenter.subscribe(this::render);
    }

    /** @param selected 草稿中的精确工具名 */
    void showSelection(Set<String> selected) {
        selectedNames = Set.copyOf(selected);
        picker.render(state, selectedNames);
    }

    /**
     * 绑定当前固定作用域和精确权限版本。
     *
     * @param workspaceId 固定 Workspace
     * @param permissionProfile 精确权限版本
     */
    void bind(Optional<WorkspaceId> workspaceId, Optional<PermissionProfileRef> permissionProfile) {
        presenter.bind(workspaceId, permissionProfile, Optional.empty());
    }

    /** @param listener 目录异步状态变化通知 */
    void onStateChanged(Runnable listener) {
        stateChanged = Objects.requireNonNull(listener, "listener");
    }

    /** @return 当前选择的精确工具名 */
    Set<String> selectedNames() {
        return picker.selectedNames();
    }

    /** @return 目录可安全用于保存 */
    boolean ready() {
        return state.ready();
    }

    /** @return 是否正在加载目录 */
    boolean pending() {
        return state.phase() == SettingsLoadState.LOADING;
    }

    private void selectionChanged(Set<String> selected) {
        selectedNames = Set.copyOf(selected);
        changed.run();
    }

    private void render(ToolCatalogSelectionState next) {
        state = Objects.requireNonNull(next, "next");
        picker.render(next, selectedNames);
        stateChanged.run();
    }
}
