package com.javaclaw.desktop;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * 通过 SDK 使用 App Server 的 JavaFX 生命周期实现。
 *
 * <p>此类不提供 {@code main}，避免 IDE 将缺少服务端依赖的 Desktop 模块误识别为完整产品入口。 开发调试和发行物统一从
 * {@code com.javaclaw.launcher.JavaClawLauncher} 启动。
 */
public final class JavaClawDesktop extends Application {
    private static final AtomicBoolean LAUNCH_STARTED = new AtomicBoolean();
    private static volatile DesktopLaunchConfiguration launchConfiguration = DesktopLaunchConfiguration.automatic();
    private DesktopComponentGraph components;
    private Thread shutdownHook;

    @Override
    public void init() throws Exception {
        // JavaFX 在独立的初始化线程调用 init，握手等待不能阻塞 JavaFX Application Thread。
        components = DesktopComponentGraph.create(launchConfiguration);
        shutdownHook = new Thread(
                () -> {
                    try {
                        components.close();
                    } catch (Exception failure) {
                        System.err.println("JavaClaw 退出清理失败：" + failure.getMessage());
                    }
                },
                "javaclaw-desktop-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (RuntimeException failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    @Override
    public void start(Stage stage) throws Exception {
        try {
            components.setExternalBrowserOpener(uri -> getHostServices().showDocument(uri.toString()));
            Scene scene = new Scene(components.loadMainView(), 1200, 700);
            stage.setTitle("JavaClaw 4.0");
            stage.setMinWidth(600);
            stage.setMinHeight(500);
            stage.setScene(scene);
            stage.show();
        } catch (Exception | Error failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    @Override
    public void stop() throws Exception {
        try {
            if (components != null) {
                components.close();
            }
        } finally {
            if (shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // JVM 已开始关闭时，幂等的组件 close 允许关闭钩子完成剩余清理。
                }
            }
        }
    }

    /**
     * 一次性安装启动配置并运行 JavaFX，直到所有窗口退出；同一 JVM 不允许再次启动。 配置在启动前发布，避免修改全局环境变量，也不在 JavaFX 线程同步等待服务端握手。
     *
     * @param configuration 非空的基础设施默认配置；由调用方从 IDE 或发行布局解析
     * @param args 透传给 JavaFX 的非空参数数组
     */
    public static void launch(DesktopLaunchConfiguration configuration, String[] args) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(args, "args");
        if (!LAUNCH_STARTED.compareAndSet(false, true)) {
            throw new IllegalStateException("JavaClaw Desktop 在同一 JVM 中只能启动一次");
        }
        launchConfiguration = configuration;
        Application.launch(JavaClawDesktop.class, args);
    }

    private void closeAfterFailure(Throwable failure) {
        try {
            stop();
        } catch (Exception cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
