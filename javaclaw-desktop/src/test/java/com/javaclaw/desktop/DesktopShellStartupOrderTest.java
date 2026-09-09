package com.javaclaw.desktop;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.settings.ChatConfigurationPanel;
import com.javaclaw.desktop.settings.ManagementCenterWindow;
import com.javaclaw.desktop.settings.MemoryManagementCenterFixture;
import com.javaclaw.desktop.shell.DesktopShellController;
import com.javaclaw.desktop.shell.JavaClawDesktop;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopShellStartupOrderTest {
    @Test
    void 真实启动先绑定无窗口Scene再挂载主窗口并完成连接与配置预览() throws Exception {
        try (StartupHarness harness = new StartupHarness()) {
            harness.attachBeforeMountingWindow();
            FxTestSupport.await(() -> harness.state.get().connection().status() == ConnectionState.Status.CONNECTED
                    && FxTestSupport.call(() -> harness.configuration.ready()));
            FxTestSupport.run(() -> {
                assertTrue(harness.stage.isShowing());
                assertTrue(harness.configuration.ready());
            });
        }
    }

    private static final class StartupHarness implements AutoCloseable {
        private final PresenterRpcServer server = new PresenterRpcServer();
        private final AtomicReference<DesktopState> state = new AtomicReference<>(DesktopState.initial());
        private final DesktopPresenter presenter;
        private DesktopShellController controller;
        private ManagementCenterWindow center;
        private ChatConfigurationPanel configuration;
        private Stage stage;

        private StartupHarness() throws IOException {
            JavaClawClient client = server.client(ignored -> {});
            presenter = new DesktopPresenter(ignored -> client, Platform::runLater, Clock.systemUTC());
            presenter.subscribe(state::set);
        }

        private void attachBeforeMountingWindow() {
            FxTestSupport.run(() -> {
                try {
                    FXMLLoader loader = new FXMLLoader(JavaClawDesktop.class.getResource("/fxml/main.fxml"));
                    Parent root = loader.load();
                    Scene scene = new Scene(root, 940, 640);
                    DesktopStylesheets.apply(scene);
                    center = MemoryManagementCenterFixture.create(presenter, scene);
                    controller = loader.getController();
                    assertNull(scene.getWindow());
                    // 与 JavaClawDesktop.start 保持同样顺序，防止测试提前挂载窗口掩盖启动异常。
                    controller.attach(presenter, center);
                    configuration = (ChatConfigurationPanel)
                            ((VBox) loader.getNamespace().get("executionHost"))
                                    .getChildren()
                                    .getFirst();
                    stage = new Stage();
                    stage.setScene(scene);
                    stage.show();
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
            });
        }

        @Override
        public void close() throws Exception {
            FxTestSupport.run(() -> {
                if (controller != null) {
                    controller.close();
                }
                if (center != null) {
                    center.dispose();
                }
                if (stage != null) {
                    stage.hide();
                }
            });
            presenter.close();
            server.close();
        }
    }
}
