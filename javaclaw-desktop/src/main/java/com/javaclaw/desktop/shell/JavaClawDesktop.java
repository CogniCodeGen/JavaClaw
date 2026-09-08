package com.javaclaw.desktop.shell;

import java.io.IOException;
import java.net.URL;
import java.time.Clock;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import com.javaclaw.desktop.DesktopClientConnector;
import com.javaclaw.desktop.DesktopLaunchOptions;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;
import com.javaclaw.desktop.appearance.JavaPreferencesAppearanceStore;
import com.javaclaw.desktop.settings.ManagementCenterWindow;
import com.javaclaw.desktop.settings.SdkManagementSettingsGateways;
import com.javaclaw.desktop.web.WebSurfaceRuntime;

/** JavaClaw 6 JavaFX 入口；Desktop 只创建 SDK Presenter 和平台视图。 */
public final class JavaClawDesktop extends Application {
    private DesktopPresenter presenter;
    private ManagementCenterWindow managementCenter;
    private DesktopShellController controller;
    private static java.util.function.Consumer<java.net.URI> external = ignored -> {};

    /**
     * 加载 509f197 视觉语言的 v6 壳并建立 Protocol v3 连接。
     *
     * @param stage 主窗口
     * @throws IOException FXML 资源损坏
     */
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(requireResource("/fxml/main.fxml"));
        Parent root = loader.load();
        DesktopClientConnector connector = connector();
        presenter = new DesktopPresenter(connector, JavaClawDesktop::dispatchToFx, Clock.systemUTC());

        Scene scene = new Scene(root, 1280, 820);
        DesktopStylesheets.apply(scene);
        DesktopAppearanceManager appearance = new DesktopAppearanceManager(new JavaPreferencesAppearanceStore());
        appearance.register(scene);
        managementCenter = new ManagementCenterWindow(appearance, SdkManagementSettingsGateways.create(presenter));
        controller = loader.getController();
        external = uri -> getHostServices().showDocument(uri.toString());
        controller.attach(presenter, managementCenter);
        stage.setTitle("JavaClaw 6");
        stage.setMinWidth(940);
        stage.setMinHeight(640);
        stage.setScene(scene);
        stage.show();
    }

    /** 关闭页面、SDK、App Server stdio 子进程和后台线程，随后清理本进程 WebKit 临时 profile。 */
    @Override
    public void stop() throws Exception {
        try {
            if (presenter != null) {
                if (controller != null) {
                    controller.close();
                }
                if (managementCenter != null) {
                    managementCenter.dispose();
                }
                presenter.close();
            }
        } finally {
            WebSurfaceRuntime.close();
        }
    }

    /**
     * 启动 JavaFX。
     *
     * @param arguments JavaFX 参数；transport 仅从显式 JVM 属性读取
     */
    public static void main(String[] arguments) {
        launch(arguments);
    }

    static void openExternal(java.net.URI uri) {
        external.accept(uri);
    }

    private static DesktopClientConnector connector() {
        try {
            return DesktopLaunchOptions.fromSystemProperties().connector();
        } catch (RuntimeException configurationFailure) {
            String message = configurationFailure.getMessage();
            return notifications -> throwConfiguration(message);
        }
    }

    private static com.javaclaw.client.sdk.JavaClawClient throwConfiguration(String message) throws IOException {
        throw new IOException(message == null ? "Desktop transport 配置无效" : message);
    }

    private static URL requireResource(String path) {
        URL resource = JavaClawDesktop.class.getResource(path);
        if (resource == null) {
            throw new IllegalStateException("Desktop 资源不存在: " + path);
        }
        return resource;
    }

    private static void dispatchToFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
