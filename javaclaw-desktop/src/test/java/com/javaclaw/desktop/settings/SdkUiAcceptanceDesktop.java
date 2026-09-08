package com.javaclaw.desktop.settings;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.javaclaw.desktop.DesktopLaunchOptions;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.appearance.AppearancePreferenceStore;
import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;
import com.javaclaw.desktop.shell.DesktopShellController;
import com.javaclaw.desktop.state.DesktopState;

/** 运行生产 FXML、Presenter 和 SDK 的人工验收入口；窗口与外观偏好只保存在内存。 */
public final class SdkUiAcceptanceDesktop {
    private final Path output;
    private final SdkUiAcceptanceDiagnostics diagnostics = new SdkUiAcceptanceDiagnostics();
    DesktopPresenter presenter;
    ManagementCenterWindow center;
    DesktopAppearanceManager appearance;
    Stage stage;
    volatile DesktopState state = DesktopState.initial();
    private DesktopShellController controller;
    private int capture;

    private SdkUiAcceptanceDesktop(Path output) {
        this.output = output;
    }

    /**
     * 连接隔离测试服务并显示真实主窗口；F12 保存当前所有窗口的 Scene 截图及可见文本。
     *
     * @param arguments 截图输出目录；第二个可选参数 suite 执行自动验收；UDS 来自 JVM 属性
     * @throws Exception 无法创建证据目录
     */
    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]).toAbsolutePath();
        Files.createDirectories(output);
        SdkUiAcceptanceDesktop desktop = new SdkUiAcceptanceDesktop(output);
        FxTestSupport.run(desktop::start);
        if (arguments.length > 1 && "suite".equals(arguments[1])) {
            try {
                SdkUiAcceptanceReplay.run(desktop, output);
            } catch (Exception | AssertionError failure) {
                try {
                    desktop.diagnostics.report(output, desktop.state, failure);
                    FxTestSupport.run(desktop::captureWindows);
                } catch (Exception | AssertionError captureFailure) {
                    failure.addSuppressed(captureFailure);
                }
                throw failure;
            } finally {
                FxTestSupport.run(desktop::close);
            }
        }
    }

    private void start() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
            Parent root = loader.load();
            presenter = new DesktopPresenter(
                    diagnostics.observe(
                            DesktopLaunchOptions.fromSystemProperties().connector()),
                    SdkUiAcceptanceDesktop::dispatch,
                    Clock.systemUTC());
            presenter.subscribe(value -> state = value);
            appearance = new DesktopAppearanceManager(new MemoryAppearanceStore());
            center = new ManagementCenterWindow(
                    appearance, SdkManagementSettingsGateways.create(presenter), new MemoryWindowStore());
            Scene scene = new Scene(root, 1280, 820);
            DesktopStylesheets.apply(scene);
            appearance.register(scene);
            controller = loader.getController();
            controller.attach(presenter, center);
            stage = new Stage();
            stage.setTitle("JavaClaw V3 功能验收（测试数据）");
            stage.setMinWidth(940);
            stage.setMinHeight(640);
            stage.setScene(scene);
            Window.getWindows().addListener((javafx.collections.ListChangeListener<Window>) change -> {
                while (change.next()) {
                    if (change.wasAdded()) {
                        change.getAddedSubList().forEach(this::captureKey);
                    }
                }
            });
            stage.setOnCloseRequest(event -> close());
            stage.show();
        } catch (Exception failure) {
            throw new AssertionError("真实 Desktop 验收启动失败", failure);
        }
    }

    private void captureKey(Window window) {
        if (window.getScene() != null) {
            window.getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.F12) {
                    captureWindows();
                    event.consume();
                }
            });
        }
    }

    private static void dispatch(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private void captureWindows() {
        int number = ++capture;
        int index = 0;
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window instanceof Stage stage && stage.isShowing()) {
                saveWindow(stage, number + "-" + (++index));
            }
        }
    }

    private void saveWindow(Stage stage, String id) {
        try {
            Scene scene = stage.getScene();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            var snapshot = scene.snapshot(null);
            BufferedImage image = new BufferedImage(
                    (int) snapshot.getWidth(), (int) snapshot.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
                }
            }
            ImageIO.write(image, "png", output.resolve("capture-" + id + ".png").toFile());
            List<String> text = new ArrayList<>();
            text.add(stage.getTitle());
            text.add("Scene " + scene.getWidth() + " x " + scene.getHeight());
            collectText(scene.getRoot(), text);
            Files.write(output.resolve("capture-" + id + ".txt"), text);
            System.out.println("UI_CAPTURE " + id);
        } catch (Exception failure) {
            throw new AssertionError("无法保存验收截图", failure);
        }
    }

    private static void collectText(Node node, List<String> text) {
        if (!node.isVisible()) {
            return;
        }
        if (node instanceof Labeled label
                && label.getText() != null
                && !label.getText().isBlank()) {
            text.add(label.getText());
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collectText(child, text));
        }
    }

    private void close() {
        try {
            diagnostics.close();
            controller.close();
            center.dispose();
            presenter.close();
        } catch (Exception failure) {
            failure.printStackTrace();
        } finally {
            Platform.exit();
        }
    }

    private static final class MemoryAppearanceStore implements AppearancePreferenceStore {
        private AppearancePreferences preferences = AppearancePreferences.defaults();

        @Override
        public AppearancePreferences load() {
            return preferences;
        }

        @Override
        public void save(AppearancePreferences value) {
            preferences = value;
        }
    }

    private static final class MemoryWindowStore implements ManagementWindowPreferenceStore {
        private ManagementWindowPreferences preferences = ManagementWindowPreferences.defaults();

        @Override
        public ManagementWindowPreferences load() {
            return preferences;
        }

        @Override
        public void save(ManagementWindowPreferences value) {
            preferences = value;
        }
    }
}
