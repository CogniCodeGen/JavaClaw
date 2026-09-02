package com.javaclaw.desktop.settings;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeState;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorktreeId;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedWorktreeSettingsPageTest {
    @Test
    void 恢复中心生成Patch和Backup后保持Thread导航与安全动作状态() {
        FxTestSupport.run(() -> {
            WorktreeGateway gateway = new WorktreeGateway();
            ManagedWorktreeSettingsPage page = page(gateway);

            button(page, "生成 Patch Attachment").fire();
            assertTrue(texts(page).contains("PATCH"));
            button(page, "生成验证 Backup").fire();
            assertTrue(texts(page).contains("BACKUP"));
            assertFalse(button(page, "危险 Cleanup").isDisabled());

            button(page, "打开父 Thread").fire();
            assertTrue(texts(page).stream().anyMatch(value -> value.contains("主窗口打开")));
            button(page, "打开子 Thread").fire();
            checkBox(page, "显示已清理历史").setSelected(true);
            assertTrue(checkBox(page, "显示已清理历史").isSelected());
        });
    }

    @Test
    void 不同Worktree终态只开放合法中断捕获和清理动作() {
        FxTestSupport.run(() -> {
            WorktreeGateway gateway = new WorktreeGateway();
            gateway.replaceWithStateMatrix();
            ManagedWorktreeSettingsPage page = page(gateway);
            ListView<ManagedWorktree> list = worktreeList(page);

            for (ManagedWorktree worktree : List.copyOf(list.getItems())) {
                list.getSelectionModel().select(worktree);
                boolean capturable =
                        switch (worktree.state()) {
                            case READY, INTERRUPTED, COMPLETED, FAILED, APPLIED -> true;
                            case RUNNING, CONFLICTED, APPLYING, UNKNOWN_OUTCOME, CLEANING, CLEANED -> false;
                        };
                boolean interruptible = worktree.state() == ManagedWorktreeState.READY
                        || worktree.state() == ManagedWorktreeState.RUNNING;
                boolean cleanupAllowed = capturable && worktree.backup().isPresent();
                assertTrue(button(page, "生成 Patch Attachment").isDisabled() != capturable);
                assertTrue(button(page, "中断子任务").isDisabled() != interruptible);
                assertTrue(button(page, "危险 Cleanup").isDisabled() != cleanupAllowed);
            }
        });
    }

    private static ManagedWorktreeSettingsPage page(WorktreeGateway gateway) {
        ManagedWorktreeSettingsPage page = new ManagedWorktreeSettingsPage(gateway.proxy());
        new Scene(page, 1_040, 720);
        page.activate();
        page.applyCss();
        return page;
    }

    private static ListView<ManagedWorktree> worktreeList(Parent root) {
        ListView<?> found = nodes(root, ListView.class).stream()
                .filter(value -> !value.getItems().isEmpty())
                .filter(value -> value.getItems().getFirst() instanceof ManagedWorktree)
                .findFirst()
                .orElseThrow();
        return castList(found);
    }

    @SuppressWarnings("unchecked")
    private static ListView<ManagedWorktree> castList(ListView<?> value) {
        return (ListView<ManagedWorktree>) value;
    }

    private static Button button(Parent root, String text) {
        return nodes(root, Button.class).stream()
                .filter(value -> text.equals(value.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少按钮: " + text));
    }

    private static CheckBox checkBox(Parent root, String text) {
        return nodes(root, CheckBox.class).stream()
                .filter(value -> text.equals(value.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> texts(Parent root) {
        return nodes(root, Label.class).stream().map(Label::getText).toList();
    }

    private static <T extends Node> List<T> nodes(Parent root, Class<T> type) {
        ArrayList<T> result = new ArrayList<>();
        Queue<Node> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Node current = pending.remove();
            if (type.isInstance(current)) {
                result.add(type.cast(current));
            }
            if (current instanceof Parent parent) {
                pending.addAll(parent.getChildrenUnmodifiable());
            }
        }
        return List.copyOf(result);
    }

    private static final class WorktreeGateway implements InvocationHandler {
        private final Workspace workspace = DesktopTestFixtures.workspace();
        private final TestManagedWorktreeSettings store = new TestManagedWorktreeSettings();
        private List<ManagedWorktree> worktrees = store.list(workspace.id(), true);

        private CoreSettingsGateway proxy() {
            return (CoreSettingsGateway) Proxy.newProxyInstance(
                    CoreSettingsGateway.class.getClassLoader(), new Class<?>[] {CoreSettingsGateway.class}, this);
        }

        private void replaceWithStateMatrix() {
            ManagedWorktree template = worktrees.getFirst();
            store.backup(template);
            template = store.list(workspace.id(), true).getFirst();
            ManagedWorktree backedUp = template;
            worktrees = java.util.Arrays.stream(ManagedWorktreeState.values())
                    .map(state -> copy(
                            backedUp,
                            state,
                            state == ManagedWorktreeState.APPLIED || state == ManagedWorktreeState.CLEANED))
                    .toList();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "workspaces" -> completed(List.of(workspace));
                case "managedWorktrees" -> completed(worktrees);
                case "exportManagedWorktreePatch" -> completed(store.exportPatch(selected(arguments)));
                case "backupManagedWorktree" -> backup(selected(arguments));
                case "navigateToThread" -> completed(store.navigate((com.javaclaw.api.ThreadId) arguments[0]));
                case "toString" -> "WorktreeGateway";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }

        private CompletableFuture<com.javaclaw.api.ManagedWorktreeArtifact> backup(ManagedWorktree current) {
            com.javaclaw.api.ManagedWorktreeArtifact artifact = store.backup(current);
            worktrees = store.list(workspace.id(), true);
            return completed(artifact);
        }

        private static ManagedWorktree selected(Object[] arguments) {
            return (ManagedWorktree) arguments[0];
        }

        private static ManagedWorktree copy(ManagedWorktree source, ManagedWorktreeState state, boolean backedUp) {
            return new ManagedWorktree(
                    new WorktreeId(UUID.randomUUID()),
                    source.workspaceId(),
                    source.parentThreadId(),
                    source.childThreadId(),
                    source.executionRoot(),
                    source.baseCommit(),
                    state,
                    source.revision(),
                    backedUp ? source.backup() : Optional.empty(),
                    source.createdAt(),
                    source.updatedAt());
        }

        private static <T> CompletableFuture<T> completed(T value) {
            return CompletableFuture.completedFuture(value);
        }
    }
}
