package com.javaclaw.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.state.ThreadState;

/** 合并主窗口目录刷新；只替换登记快照，不导航、不丢弃当前对话或正在运行的任务。 */
final class DesktopCatalogRefresh {
    private final DesktopPresenter desktop;
    private final DesktopStore store;
    private boolean pending;
    private boolean requested;

    DesktopCatalogRefresh(DesktopPresenter desktop, DesktopStore store) {
        this.desktop = desktop;
        this.store = store;
    }

    void request() {
        requested = true;
        if (pending || store.state().connection().status() != ConnectionState.Status.CONNECTED) {
            return;
        }
        requested = false;
        pending = true;
        var connection = store.state().connection().connectedAt();
        desktop.submitSettingsRequest(client -> client.workspaces().list()).whenComplete((catalog, failure) -> {
            pending = false;
            if (failure == null && connection.equals(store.state().connection().connectedAt())) {
                store.update(state -> apply(state, catalog));
            }
            if (requested) {
                request();
            }
        });
    }

    private static DesktopState apply(DesktopState state, List<Workspace> catalog) {
        var before = state.threads();
        Optional<Workspace> selected = before.selectedWorkspace().map(previous -> catalog.stream()
                .filter(value -> value.id().equals(previous.id())).findFirst().orElse(previous));
        List<Workspace> visible = new ArrayList<>(catalog);
        selected.filter(value -> visible.stream().noneMatch(item -> item.id().equals(value.id())))
                .ifPresent(visible::add);
        ThreadState threads = new ThreadState(visible, selected, before.threads(), before.selectedThread(), before.activeTurn());
        return new DesktopState(state.connection(), state.navigation(), threads, state.transcript(), state.interaction());
    }
}
