package com.javaclaw.desktop;

import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.settings.ManagementCenterWindow;
import com.javaclaw.desktop.settings.MemoryManagementCenterFixture;
import com.javaclaw.desktop.shell.DesktopShellController;
import com.javaclaw.desktop.shell.JavaClawDesktop;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopShellNewThreadTest {
    @Test
    void 点击新建对话直接提交默认标题且迟到创建结果不抢焦点() throws Exception {
        try (Shell shell = new Shell()) {
            shell.awaitReady();
            AtomicInteger dialogs = new AtomicInteger();
            FxTestSupport.run(() -> {
                Platform.runLater(() -> cancelDialogs(dialogs));
                shell.create.fire();
                assertSame(shell.composer, shell.stage.getScene().getFocusOwner());
            });
            FxTestSupport.run(() -> assertEquals(0, dialogs.get(), "新建对话不得再弹出标题输入框"));
            FxTestSupport.await(() -> shell.title.get() != null);
            assertEquals("新对话", shell.title.get());
            FxTestSupport.run(() -> shell.workspaces.requestFocus());
            shell.release.countDown();
            FxTestSupport.await(() -> shell.server.threadCreates.get() == 1
                    && !shell.state.get().interaction().busy());
            FxTestSupport.run(
                    () -> assertSame(shell.workspaces, shell.stage.getScene().getFocusOwner()));
        }
    }

    @Test
    void 新建工作区仍先确认名称且取消不提交创建() throws Exception {
        try (Shell shell = new Shell()) {
            shell.awaitReady();
            AtomicInteger dialogs = new AtomicInteger();
            AtomicReference<String> header = new AtomicReference<>();
            FxTestSupport.run(() -> {
                Platform.runLater(() -> {
                    DialogPane pane = dialog().orElseThrow();
                    header.set(pane.getHeaderText());
                    cancelDialogs(dialogs);
                });
                shell.workspaceCreate.fire();
            });
            assertEquals("设置工作区名称", header.get());
            assertEquals(1, dialogs.get());
            assertEquals(0, shell.server.workspaceCreates.get());
            assertEquals(0, shell.server.threadCreates.get());
        }
    }

    private static Optional<DialogPane> dialog() {
        return Window.getWindows().stream()
                .filter(Window::isShowing)
                .map(window -> window.getScene().getRoot())
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .findFirst();
    }

    private static void cancelDialogs(AtomicInteger count) {
        dialog().ifPresent(pane -> {
            count.incrementAndGet();
            ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
        });
    }

    private static final class Shell implements AutoCloseable {
        private final PresenterRpcServer server = new PresenterRpcServer();
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<String> title = new AtomicReference<>();
        private final AtomicReference<DesktopState> state = new AtomicReference<>();
        private final DesktopPresenter presenter;
        private DesktopShellController controller;
        private ManagementCenterWindow center;
        private Stage stage;
        private Button create;
        private Button workspaceCreate;
        private TextArea composer;
        private ComboBox<?> workspaces;

        private Shell() throws IOException {
            CanonicalJson json = new CanonicalJson();
            server.requestOverride = request -> {
                if (request.method().equals("thread/create")) {
                    WriteCommand command = json.decode(request.params(), WriteCommand.class);
                    title.set(json.decode(command.payload(), CoreRpcContracts.ThreadCreatePayload.class)
                            .title());
                    awaitRelease();
                }
                return Optional.empty();
            };
            presenter = new DesktopPresenter(server::client, Shell::dispatch, Clock.systemUTC());
            presenter.subscribe(state::set);
            FxTestSupport.run(this::show);
        }

        private void awaitReady() {
            FxTestSupport.await(() -> state.get().connection().status() == ConnectionState.Status.CONNECTED
                    && state.get().transcript().nextSequence() > 0
                    && !state.get().interaction().busy());
        }

        private void show() {
            try {
                FXMLLoader loader = new FXMLLoader(JavaClawDesktop.class.getResource("/fxml/main.fxml"));
                Parent root = loader.load();
                controller = loader.getController();
                create = (Button) root.lookup(".sb-new-btn");
                workspaceCreate = (Button) loader.getNamespace().get("newWorkspaceButton");
                composer = (TextArea) loader.getNamespace().get("composer");
                workspaces = (ComboBox<?>) loader.getNamespace().get("workspaceBox");
                stage = new Stage();
                Scene scene = new Scene(root, 1280, 820);
                DesktopStylesheets.apply(scene);
                stage.setScene(scene);
                center = MemoryManagementCenterFixture.create(presenter, scene);
                stage.show();
                controller.attach(presenter, center);
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        }

        private void awaitRelease() {
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        }

        private static void dispatch(Runnable action) {
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            FxTestSupport.run(() -> {
                controller.close();
                center.dispose();
                stage.close();
            });
            presenter.close();
        }
    }
}
