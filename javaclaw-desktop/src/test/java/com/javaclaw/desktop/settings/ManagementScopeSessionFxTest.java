package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ManagementScopeSessionFxTest {
    @Test
    void 目录Loading和失败时页面保留冻结作用域与草稿但关闭写入门() {
        FxTestSupport.run(() -> {
            TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
            Workspace original = gateway.workspaceSettings.catalog.getFirst();
            CapturingPage page = new CapturingPage();
            List<Boolean> writeAvailability = new ArrayList<>();
            ManagementScopeSession session = new ManagementScopeSession(
                    gateway, () -> Optional.of(original.id()), () -> page, writeAvailability::add);
            session.bind(page);
            session.activate();
            page.draft = "未保存草稿";
            CompletableFuture<List<Workspace>> pending = new CompletableFuture<>();
            gateway.workspaceSettings.nextResponse = pending;

            session.activate();

            assertEquals(original.id(), page.received.get(1).orElseThrow().id());
            assertEquals(2, page.received.size());
            assertEquals("未保存草稿", page.draft);
            assertFalse(writeAvailability.getLast());

            pending.completeExceptionally(new IllegalStateException("连接中断"));

            assertEquals(2, page.received.size());
            assertEquals(original.id(), page.received.getLast().orElseThrow().id());
            assertEquals("未保存草稿", page.draft);
            assertFalse(writeAvailability.getLast());
        });
    }

    @Test
    void 重新读取按钮在Dirty或Pending时不会发起目录请求() {
        FxTestSupport.run(() -> {
            TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
            Workspace original = gateway.workspaceSettings.catalog.getFirst();
            CapturingPage page = new CapturingPage();
            ManagementScopeSession session =
                    new ManagementScopeSession(gateway, () -> Optional.of(original.id()), () -> page, ignored -> {});
            session.bind(page);
            session.activate();
            Button reload = reloadButton(session.content());
            assertEquals(1, gateway.workspaceSettings.reads);

            page.dirty = true;
            reload.fire();
            assertEquals(1, gateway.workspaceSettings.reads);
            assertEquals(1, page.warnings);

            page.dirty = false;
            page.pending = true;
            closeNextDialog();
            reload.fire();
            assertEquals(1, gateway.workspaceSettings.reads);

            page.pending = false;
            reload.fire();
            assertEquals(2, gateway.workspaceSettings.reads);
        });
    }

    @Test
    void 切换Workspace时尊重写操作和草稿保护() {
        FxTestSupport.run(() -> {
            TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
            Workspace original = gateway.workspaceSettings.catalog.getFirst();
            Workspace other = workspace("代码审查", "eb82a5b0-1269-4129-b8e2-d378bd55e390");
            gateway.workspaceSettings.catalog.add(other);
            CapturingPage page = new CapturingPage();
            ManagementScopeSession session =
                    new ManagementScopeSession(gateway, () -> Optional.of(original.id()), () -> page, ignored -> {});
            session.bind(page);
            session.activate();
            ComboBox<?> selector = workspaceSelector(session.content());

            page.pending = true;
            closeNextDialog(ButtonType.OK);
            selector.getSelectionModel().select(selector.getItems().indexOf(other));
            assertEquals(original.id(), page.received.getLast().orElseThrow().id());

            page.pending = false;
            page.dirty = true;
            closeNextDialog(ButtonType.CANCEL);
            selector.getSelectionModel().select(selector.getItems().indexOf(other));
            assertEquals(1, page.warnings);
            assertEquals(0, page.discards);
            assertEquals(original.id(), page.received.getLast().orElseThrow().id());

            closeNextDialog("丢弃并切换");
            selector.getSelectionModel().select(selector.getItems().indexOf(other));
            assertEquals(1, page.discards);
            assertEquals(other.id(), page.received.getLast().orElseThrow().id());

            page.dirty = false;
            selector.getSelectionModel().select(selector.getItems().indexOf(original));
            assertEquals(original.id(), page.received.getLast().orElseThrow().id());
        });
    }

    @Test
    void 没有活动页面时仍可以重读并切换Workspace() {
        FxTestSupport.run(() -> {
            TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
            Workspace original = gateway.workspaceSettings.catalog.getFirst();
            Workspace other = workspace("文档", "9a0f810a-b4bd-4bf8-b48d-6e03b3c505d7");
            gateway.workspaceSettings.catalog.add(other);
            List<Boolean> writeAvailability = new ArrayList<>();
            ManagementScopeSession session = new ManagementScopeSession(
                    gateway, () -> Optional.of(original.id()), () -> null, writeAvailability::add);
            session.activate();

            reloadButton(session.content()).fire();
            ComboBox<?> selector = workspaceSelector(session.content());
            selector.getSelectionModel().select(selector.getItems().indexOf(other));

            assertEquals(2, gateway.workspaceSettings.reads);
            assertEquals(other, selector.getValue());
            assertEquals(Boolean.TRUE, writeAvailability.getLast());
        });
    }

    private static Button reloadButton(Node root) {
        return ((javafx.scene.Parent) root)
                .getChildrenUnmodifiable().stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .filter(button -> "重新读取".equals(button.getText()))
                        .findFirst()
                        .orElseThrow();
    }

    private static ComboBox<?> workspaceSelector(Node root) {
        return ((javafx.scene.Parent) root)
                .getChildrenUnmodifiable().stream()
                        .filter(ComboBox.class::isInstance)
                        .map(ComboBox.class::cast)
                        .findFirst()
                        .orElseThrow();
    }

    private static void closeNextDialog() {
        closeNextDialog(ButtonType.OK);
    }

    private static void closeNextDialog(ButtonType buttonType) {
        Platform.runLater(() -> Window.getWindows().stream()
                .map(Window::getScene)
                .filter(java.util.Objects::nonNull)
                .map(Scene::getRoot)
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .findFirst()
                .map(dialog -> dialog.lookupButton(buttonType))
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .ifPresent(Button::fire));
    }

    private static void closeNextDialog(String buttonText) {
        Platform.runLater(() -> Window.getWindows().stream()
                .map(Window::getScene)
                .filter(java.util.Objects::nonNull)
                .map(Scene::getRoot)
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .flatMap(dialog -> dialog.getButtonTypes().stream()
                        .map(dialog::lookupButton)
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .filter(button -> buttonText.equals(button.getText()))
                        .findFirst()
                        .stream())
                .findFirst()
                .ifPresent(Button::fire));
    }

    private static Workspace workspace(String name, String id) {
        return new Workspace(
                WorkspaceId.parse(id),
                name,
                java.nio.file.Path.of("/tmp", id),
                WorkspaceLifecycle.ACTIVE,
                1,
                DesktopTestFixtures.NOW,
                DesktopTestFixtures.NOW);
    }

    private static final class CapturingPage implements ManagedSettingsPage {
        private final List<Optional<Workspace>> received = new ArrayList<>();
        private final VBox content = new VBox();
        private String draft = "未保存草稿";
        private boolean dirty;
        private boolean pending;
        private int warnings;
        private int discards;

        @Override
        public Node content() {
            return content;
        }

        @Override
        public void activate() {}

        @Override
        public boolean dirty() {
            return dirty;
        }

        @Override
        public boolean pending() {
            return pending;
        }

        @Override
        public void workspaceChanged(Optional<Workspace> workspace) {
            received.add(workspace);
            if (workspace.isEmpty()) {
                draft = "";
            }
        }

        @Override
        public void warnUnsavedChanges() {
            warnings++;
        }

        @Override
        public void discardDraft() {
            discards++;
            dirty = false;
        }
    }
}
