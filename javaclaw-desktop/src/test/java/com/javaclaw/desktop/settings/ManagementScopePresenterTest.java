package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.desktop.DesktopTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementScopePresenterTest {
    @Test
    void 自动目录失效在当前读取完成后合并补读() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        Workspace original = gateway.workspaceSettings.catalog.getFirst();
        Workspace next = workspace("新增工作区", "92e52883-b048-4459-90c8-ed7ee568d550");
        ManagementScopePresenter presenter = new ManagementScopePresenter(gateway, () -> Optional.of(original.id()));
        CompletableFuture<List<Workspace>> pending = new CompletableFuture<>();
        gateway.workspaceSettings.nextResponse = pending;
        presenter.refresh();
        gateway.workspaceSettings.catalog.add(next);

        presenter.refresh();
        presenter.refresh();
        assertEquals(1, gateway.workspaceSettings.reads);
        pending.complete(List.of(original));

        assertEquals(2, gateway.workspaceSettings.reads);
        assertEquals(2, presenter.state().workspaces().size());
        assertEquals(original.id(), presenter.state().selected().orElseThrow().id());
    }

    @Test
    void 主窗口切换后重新读取仍保留设置中心已冻结作用域() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        Workspace original = gateway.workspaceSettings.catalog.getFirst();
        Workspace other = workspace("另一个工作区", "92e52883-b048-4459-90c8-ed7ee568d550");
        gateway.workspaceSettings.catalog.add(other);
        AtomicReference<WorkspaceId> mainSelection = new AtomicReference<>(original.id());
        ManagementScopePresenter presenter =
                new ManagementScopePresenter(gateway, () -> Optional.of(mainSelection.get()));

        presenter.reload();
        mainSelection.set(other.id());
        presenter.reload();

        assertEquals(original.id(), presenter.state().selected().orElseThrow().id());
        assertEquals(
                original.id(),
                presenter.state().availableSelection().orElseThrow().id());
        assertTrue(presenter.state().message().contains("主窗口已切换"));
    }

    @Test
    void 已冻结工作区失效后保留名称但不再提供可执行作用域() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        Workspace original = gateway.workspaceSettings.catalog.getFirst();
        ManagementScopePresenter presenter = new ManagementScopePresenter(gateway, () -> Optional.of(original.id()));

        presenter.reload();
        gateway.workspaceSettings.catalog.clear();
        gateway.workspaceSettings.catalog.add(workspace("其他工作区", "75b2252b-b73a-4586-86ec-d8b59d01c00f"));
        presenter.reload();

        assertEquals(original.id(), presenter.state().selected().orElseThrow().id());
        assertTrue(presenter.state().availableSelection().isEmpty());
        assertTrue(presenter.state().message().contains("写操作已暂停"));
        assertFalse(presenter.state().workspaces().isEmpty());
    }

    @Test
    void 目录重载中和失败后保留冻结Workspace但关闭可写作用域() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        Workspace original = gateway.workspaceSettings.catalog.getFirst();
        ManagementScopePresenter presenter = new ManagementScopePresenter(gateway, () -> Optional.of(original.id()));
        presenter.reload();
        CompletableFuture<List<Workspace>> pending = new CompletableFuture<>();
        gateway.workspaceSettings.nextResponse = pending;

        presenter.reload();

        assertEquals(SettingsLoadState.LOADING, presenter.state().phase());
        assertEquals(original, presenter.state().frozenSelection().orElseThrow());
        assertTrue(presenter.state().availableSelection().isEmpty());

        pending.completeExceptionally(new IllegalStateException("连接中断"));

        assertEquals(SettingsLoadState.ERROR, presenter.state().phase());
        assertEquals(original, presenter.state().frozenSelection().orElseThrow());
        assertTrue(presenter.state().availableSelection().isEmpty());
    }

    @Test
    void 迟到的目录响应不能覆盖新epoch() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        Workspace original = gateway.workspaceSettings.catalog.getFirst();
        ManagementScopePresenter presenter = new ManagementScopePresenter(gateway, () -> Optional.of(original.id()));
        CompletableFuture<List<Workspace>> stale = new CompletableFuture<>();
        gateway.workspaceSettings.nextResponse = stale;

        presenter.reload();
        presenter.reload();
        ManagementScopeState current = presenter.state();
        stale.complete(List.of(workspace("迟到工作区", "41ac61ee-303f-4d58-816a-5557e3a2dcd3")));

        assertEquals(current, presenter.state());
        assertEquals(original.id(), presenter.state().selected().orElseThrow().id());
    }

    @Test
    void 没有活动Workspace时显式关闭写入并拒绝选择目录外对象() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        Workspace archived = new Workspace(
                WorkspaceId.parse("0b538f20-720a-48e7-8505-110a80b3180b"),
                "已归档",
                Path.of("/tmp/archived-workspace"),
                WorkspaceLifecycle.ARCHIVED,
                2,
                DesktopTestFixtures.NOW,
                DesktopTestFixtures.NOW);
        gateway.workspaceSettings.catalog.clear();
        gateway.workspaceSettings.catalog.add(archived);
        ManagementScopePresenter presenter = new ManagementScopePresenter(gateway, Optional::empty);

        presenter.reload();

        assertTrue(presenter.state().selected().isEmpty());
        assertTrue(presenter.state().availableSelection().isEmpty());
        assertEquals("尚无可用工作区", presenter.state().message());
        assertThrows(IllegalArgumentException.class, () -> presenter.select(archived));
    }

    private static Workspace workspace(String name, String id) {
        return new Workspace(
                WorkspaceId.parse(id),
                name,
                Path.of("/tmp", id),
                WorkspaceLifecycle.ACTIVE,
                1,
                DesktopTestFixtures.NOW,
                DesktopTestFixtures.NOW);
    }
}
