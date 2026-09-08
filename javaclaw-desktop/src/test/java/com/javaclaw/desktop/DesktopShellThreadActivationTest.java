package com.javaclaw.desktop;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ItemHistoryResult;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.desktop.shell.DesktopShellController;
import com.javaclaw.desktop.shell.JavaClawDesktop;
import com.javaclaw.desktop.state.DesktopState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopShellThreadActivationTest {
    @Test
    void 点击真实FXML已选中行可重试恢复且运行期间重复点击保持幂等() throws Exception {
        verifyActivation(false);
    }

    @Test
    void 回车激活真实FXML已选中行可重试恢复且运行期间重复回车保持幂等() throws Exception {
        verifyActivation(true);
    }

    private void verifyActivation(boolean keyboard) throws Exception {
        try (Shell shell = new Shell()) {
            FxTestSupport.await(
                    () -> shell.state.get().interaction().error().isPresent() && shell.server.historyReads.get() == 1);
            assertTrue(shell.state.get().interaction().busy());
            shell.failHistory.set(false);
            FxTestSupport.run(() -> shell.activate(keyboard));
            FxTestSupport.await(() -> shell.server.historyReads.get() == 2
                    && !shell.state.get().interaction().busy());
            assertTrue(shell.state.get().interaction().error().isEmpty());
            FxTestSupport.run(() -> shell.presenter.send("开始生成"));
            FxTestSupport.await(() -> shell.subscriptions.get() == 1
                    && shell.state.get().threads().activeTurn().isPresent()
                    && shell.server.historyReads.get() == 3);
            int reads = shell.server.historyReads.get();
            FxTestSupport.run(() -> {
                shell.activate(keyboard);
                shell.activate(keyboard);
            });
            // 为意外启动的异步 RPC 留出完成窗口，避免仅检查同步 UI 状态掩盖重复订阅。
            Thread.sleep(150);
            assertEquals(1, shell.server.turnStarts.get());
            assertEquals(1, shell.subscriptions.get());
            assertEquals(reads, shell.server.historyReads.get());
            assertEquals(0, shell.server.streamReleases.get());
            assertTrue(shell.state.get().interaction().busy());
        }
    }

    private static final class Shell implements AutoCloseable {
        private final PresenterRpcServer server = new PresenterRpcServer();
        private final AtomicBoolean failHistory = new AtomicBoolean(true);
        private final AtomicInteger subscriptions = new AtomicInteger();
        private final AtomicReference<DesktopState> state = new AtomicReference<>();
        private final DesktopPresenter presenter;
        private DesktopShellController controller;
        private ListView<?> threads;
        private Stage stage;

        private Shell() throws IOException {
            server.streamEnabled = true;
            server.completion = TurnStatus.RUNNING;
            server.history = () -> {
                if (failHistory.get()) {
                    throw new IllegalStateException("可控历史恢复失败");
                }
                return new ItemHistoryResult(List.of(), 0, false);
            };
            server.requestOverride = request -> {
                if (request.method().equals("turn/stream/subscribe")) {
                    subscriptions.incrementAndGet();
                }
                return Optional.empty();
            };
            presenter = new DesktopPresenter(server::client, Shell::dispatch, Clock.systemUTC());
            presenter.subscribe(state::set);
            FxTestSupport.run(this::show);
        }

        private void show() {
            try {
                FXMLLoader loader = new FXMLLoader(JavaClawDesktop.class.getResource("/fxml/main.fxml"));
                Parent root = loader.load();
                controller = loader.getController();
                threads = (ListView<?>) loader.getNamespace().get("threadList");
                stage = new Stage();
                Scene scene = new Scene(root, 1280, 820);
                DesktopStylesheets.apply(scene);
                stage.setScene(scene);
                stage.show();
                controller.attach(presenter);
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        }

        private void activate(boolean keyboard) {
            assertEquals(server.thread(), threads.getSelectionModel().getSelectedItem());
            if (keyboard) {
                threads.requestFocus();
                threads.fireEvent(
                        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
                return;
            }
            threads.applyCss();
            threads.layout();
            ListCell<?> cell = threads.lookupAll(".list-cell").stream()
                    .filter(ListCell.class::isInstance)
                    .map(ListCell.class::cast)
                    .filter(value -> server.thread().equals(value.getItem()))
                    .findFirst()
                    .orElseThrow();
            assertFalse(cell.isEmpty());
            cell.fireEvent(new MouseEvent(
                    MouseEvent.MOUSE_CLICKED,
                    5,
                    5,
                    5,
                    5,
                    MouseButton.PRIMARY,
                    1,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    true,
                    null));
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
            FxTestSupport.run(() -> {
                controller.close();
                stage.close();
            });
            presenter.close();
        }
    }
}
