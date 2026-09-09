package com.javaclaw.desktop.shell;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.application.Preloader;
import javafx.scene.Parent;
import javafx.stage.Stage;
import javafx.stage.Window;

/** 通过公开 JavaFX Preloader 生命周期观察生产窗口；不连接服务、不调用模型，也不依赖操作系统焦点。 */
public final class DesktopLaunchSmokePreloader extends Preloader {
    static final String READY = "DESKTOP_CLASSPATH_READY";

    /** 创建由 JavaFX 启动器管理的测试观察者。 */
    public DesktopLaunchSmokePreloader() {}

    @Override
    public void start(Stage stage) {
        // 测试 Preloader 不显示自己的窗口；就绪信号必须来自生产主窗口。
    }

    @Override
    public void handleStateChangeNotification(StateChangeNotification notification) {
        if (notification.getType() != StateChangeNotification.Type.BEFORE_START) {
            return;
        }
        boolean argumentsPreserved =
                notification.getApplication().getParameters().getRaw().contains("classpath-smoke");
        new AnimationTimer() {
            private final long deadline = System.nanoTime() + 15_000_000_000L;

            @Override
            public void handle(long now) {
                Stage window = mainWindow();
                if (window != null && argumentsPreserved) {
                    stop();
                    System.out.println(READY);
                    window.close();
                    Platform.exit();
                } else if (System.nanoTime() >= deadline) {
                    stop();
                    System.err.println("生产主窗口未出现或启动参数未透传");
                    Platform.exit();
                }
            }
        }.start();
    }

    private static Stage mainWindow() {
        for (Window window : Window.getWindows()) {
            if (window instanceof Stage stage && stage.isShowing() && "JavaClaw 6".equals(stage.getTitle())) {
                Parent root = stage.getScene().getRoot();
                if (root.lookup("#composer") != null && root.lookup("#settingsButton") != null) {
                    return stage;
                }
            }
        }
        return null;
    }
}
