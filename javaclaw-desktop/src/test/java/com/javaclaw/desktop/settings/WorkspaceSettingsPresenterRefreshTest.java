package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceSettingsPresenterRefreshTest {
    @Test
    void 切换顶部工作区立即同步列表与详情并隔离迟到响应() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        Workspace original = gateway.workspaceSettings.catalog.getFirst();
        Workspace next = new Workspace(
                WorkspaceId.parse("de78974c-336d-4362-af94-01ca2238a402"),
                "另一个工作区",
                Path.of("/tmp/another-workspace"),
                original.lifecycle(),
                1,
                original.createdAt(),
                original.updatedAt());
        AtomicReference<WorkspaceSettingsState> state = new AtomicReference<>();
        WorkspaceSettingsPresenter presenter = presenter(gateway, state, original);
        CompletableFuture<List<Workspace>> pending = new CompletableFuture<>();
        gateway.workspaceSettings.nextResponse = pending;
        presenter.reload();

        presenter.bindWorkspace(Optional.of(next));
        pending.complete(List.of(original));

        assertEquals(List.of(next), state.get().workspaces());
        assertEquals(next, state.get().selected().orElseThrow());
        assertEquals(next.name(), state.get().draftName());
    }

    @Test
    void 自动刷新更新目录但保留名称草稿和原始写入版本() {
        AtomicReference<CommandOptions> write = new AtomicReference<>();
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway() {
            @Override
            public CompletionStage<Workspace> renameWorkspace(
                    Workspace workspace, String name, CommandOptions options) {
                write.set(options);
                return CompletableFuture.failedFuture(new IllegalStateException("版本冲突"));
            }
        };
        Workspace original = gateway.workspaceSettings.catalog.getFirst();
        Workspace remote = renamed(original, "远程名称", original.revision() + 1);
        AtomicReference<WorkspaceSettingsState> state = new AtomicReference<>();
        WorkspaceSettingsPresenter presenter = presenter(gateway, state, original);
        presenter.editName("本地草稿");
        gateway.workspaceSettings.catalog.set(0, remote);

        presenter.refresh();

        assertEquals(List.of(remote), state.get().workspaces());
        assertEquals(original, state.get().selected().orElseThrow());
        assertEquals("本地草稿", state.get().draftName());
        assertTrue(state.get().message().contains("原版本已保留"));
        presenter.saveName();
        assertEquals(original.revision(), write.get().expectedRevision());
        assertEquals("本地草稿", state.get().draftName());
        presenter.discardDraft();
        assertEquals(remote, state.get().selected().orElseThrow());
        assertEquals("远程名称", state.get().draftName());
    }

    @Test
    void 读取期间多次失效合并为一次补读并采用最新登记() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        Workspace original = gateway.workspaceSettings.catalog.getFirst();
        Workspace remote = renamed(original, "最新名称", original.revision() + 1);
        AtomicReference<WorkspaceSettingsState> state = new AtomicReference<>();
        WorkspaceSettingsPresenter presenter = presenter(gateway, state, original);
        CompletableFuture<List<Workspace>> pending = new CompletableFuture<>();
        gateway.workspaceSettings.nextResponse = pending;
        presenter.reload();
        gateway.workspaceSettings.catalog.set(0, remote);

        presenter.refresh();
        presenter.refresh();
        assertEquals(1, gateway.workspaceSettings.reads);
        pending.complete(List.of(original));

        assertEquals(2, gateway.workspaceSettings.reads);
        assertEquals(remote, state.get().selected().orElseThrow());
    }

    @Test
    void 保存期间失效在成功回执更新基线后补读() {
        CompletableFuture<Workspace> saving = new CompletableFuture<>();
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway() {
            @Override
            public CompletionStage<Workspace> renameWorkspace(
                    Workspace workspace, String name, CommandOptions options) {
                return saving;
            }
        };
        Workspace original = gateway.workspaceSettings.catalog.getFirst();
        Workspace saved = renamed(original, "保存的新名称", original.revision() + 1);
        AtomicReference<WorkspaceSettingsState> state = new AtomicReference<>();
        WorkspaceSettingsPresenter presenter = presenter(gateway, state, original);
        presenter.editName(saved.name());
        presenter.saveName();
        presenter.refresh();
        presenter.refresh();
        assertEquals(0, gateway.workspaceSettings.reads);
        gateway.workspaceSettings.catalog.set(0, saved);

        saving.complete(saved);

        assertEquals(1, gateway.workspaceSettings.reads);
        assertEquals(saved, state.get().selected().orElseThrow());
        assertEquals(saved.name(), state.get().draftName());
    }

    private static WorkspaceSettingsPresenter presenter(
            TestCoreSettingsGateway gateway, AtomicReference<WorkspaceSettingsState> state, Workspace workspace) {
        WorkspaceSettingsPresenter presenter = new WorkspaceSettingsPresenter(gateway);
        presenter.subscribe(state::set);
        presenter.bindWorkspace(Optional.of(workspace));
        return presenter;
    }

    private static Workspace renamed(Workspace workspace, String name, long revision) {
        return new Workspace(
                workspace.id(),
                name,
                workspace.root(),
                workspace.lifecycle(),
                revision,
                workspace.createdAt(),
                DesktopTestFixtures.NOW);
    }
}
