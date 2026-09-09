package com.javaclaw.desktop.shell;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellWorkspaceCreationTest {
    @Test
    void 选择已登记的规范化目录直接打开并停止创建流程() {
        FxTestSupport.run(() -> {
            Workspace workspace = DesktopTestFixtures.workspace();
            List<Workspace> opened = new ArrayList<>();
            AtomicReference<Throwable> failure = answer("打开已有工作区", true);

            Optional<Path> directory = ShellWorkspaceCreation.chooseDirectory(
                    owner(),
                    () -> Optional.of(workspace.root().resolve("child/..")),
                    () -> List.of(workspace),
                    opened::add);

            rethrow(failure);
            assertTrue(directory.isEmpty(), "已有登记不能继续进入执行配置和创建提交");
            assertEquals(List.of(workspace), opened);
        });
    }

    @Test
    void 选择其他目录可继续创建且不修改已有工作区() {
        FxTestSupport.run(() -> {
            Workspace workspace = DesktopTestFixtures.workspace();
            Path fresh = Path.of("/tmp/a-different-workspace");
            ArrayDeque<Path> selections = new ArrayDeque<>(List.of(workspace.root(), fresh));
            List<Workspace> opened = new ArrayList<>();
            AtomicReference<Throwable> failure = answer("选择其他目录", true);

            Optional<Path> directory = ShellWorkspaceCreation.chooseDirectory(
                    owner(), () -> Optional.of(selections.removeFirst()), () -> List.of(workspace), opened::add);

            rethrow(failure);
            assertEquals(Optional.of(fresh), directory);
            assertTrue(opened.isEmpty());
            assertTrue(selections.isEmpty());
        });
    }

    @Test
    void 取消重复目录提示不打开也不创建() {
        FxTestSupport.run(() -> {
            Workspace workspace = DesktopTestFixtures.workspace();
            List<Workspace> opened = new ArrayList<>();
            AtomicReference<Throwable> failure = answer(ButtonType.CANCEL.getText(), true);

            Optional<Path> directory = ShellWorkspaceCreation.chooseDirectory(
                    owner(), () -> Optional.of(workspace.root()), () -> List.of(workspace), opened::add);

            rethrow(failure);
            assertTrue(directory.isEmpty());
            assertTrue(opened.isEmpty());
        });
    }

    @Test
    void 归档登记不提供打开或自动恢复操作() {
        FxTestSupport.run(() -> {
            Workspace source = DesktopTestFixtures.workspace();
            Workspace archived = new Workspace(
                    source.id(),
                    source.name(),
                    source.root(),
                    WorkspaceLifecycle.ARCHIVED,
                    source.revision(),
                    source.createdAt(),
                    source.updatedAt());
            List<Workspace> opened = new ArrayList<>();
            AtomicReference<Throwable> failure = answer(ButtonType.CANCEL.getText(), false);

            Optional<Path> directory = ShellWorkspaceCreation.chooseDirectory(
                    owner(), () -> Optional.of(archived.root()), () -> List.of(archived), opened::add);

            rethrow(failure);
            assertTrue(directory.isEmpty());
            assertTrue(opened.isEmpty());
        });
    }

    private static VBox owner() {
        VBox owner = new VBox();
        new Scene(owner);
        return owner;
    }

    private static AtomicReference<Throwable> answer(String text, boolean canOpen) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            Window window = Window.getWindows().stream()
                    .filter(value -> value.isShowing() && value.getScene().getRoot() instanceof DialogPane)
                    .findFirst()
                    .orElseThrow();
            DialogPane dialog = (DialogPane) window.getScene().getRoot();
            try {
                assertEquals(
                        canOpen,
                        dialog.getButtonTypes().stream()
                                .anyMatch(type -> type.getText().equals("打开已有工作区")));
                assertTrue(dialog.getContentText().contains(canOpen ? "修改名称" : "已归档"));
                ButtonType type = dialog.getButtonTypes().stream()
                        .filter(value -> value.getText().equals(text))
                        .findFirst()
                        .orElseThrow();
                ((Button) dialog.lookupButton(type)).fire();
                assertFalse(window.isShowing());
            } catch (Throwable thrown) {
                failure.set(thrown);
                window.hide();
            }
        });
        return failure;
    }

    private static void rethrow(AtomicReference<Throwable> failure) {
        if (failure.get() != null) {
            throw new AssertionError("重复目录提示交互失败", failure.get());
        }
    }
}
