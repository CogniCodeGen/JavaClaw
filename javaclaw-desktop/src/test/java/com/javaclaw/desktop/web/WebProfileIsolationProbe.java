package com.javaclaw.desktop.web;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;

import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;

/** 独立 JVM 的真实 WebKit profile 探针；只接收测试拥有的就绪及释放信号文件。 */
public final class WebProfileIsolationProbe {
    private static final long STARTED = System.nanoTime();

    private WebProfileIsolationProbe() {}

    /**
     * 显示页面后通知父测试，并在精确释放信号或十秒期限到达时关闭。
     *
     * @param arguments 就绪文件、释放文件
     * @throws Exception 创建页面或写入信号失败
     */
    public static void main(String[] arguments) throws Exception {
        log("启动，java.home=" + System.getProperty("java.home"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> log("JVM shutdown hook"), "probe-diagnostic"));
        WebSurfaceHost host =
                FxTestSupport.call(() -> new WebSurfaceHost("chat", new Label("简版"), (action, value) -> {}));
        Stage stage = FxTestSupport.call(() -> {
            Stage result = new Stage();
            Scene scene = new Scene(host, 320, 220);
            DesktopStylesheets.apply(scene);
            result.setScene(scene);
            result.show();
            host.show("probe", "{\"items\":[]}");
            return result;
        });
        try {
            log("等待页面确认");
            FxTestSupport.await(() -> FxTestSupport.call(host::acknowledged));
            Files.writeString(
                    Path.of(arguments[0]), WebSurfaceRuntime.directory().toString());
            log("已写入 ready，profile=" + WebSurfaceRuntime.directory());
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!Files.exists(Path.of(arguments[1])) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            log(Files.exists(Path.of(arguments[1])) ? "收到 release" : "达到十秒退出期限");
        } finally {
            log("开始关闭页面");
            FxTestSupport.run(() -> {
                host.close();
                stage.close();
            });
            WebSurfaceRuntime.close();
            Platform.exit();
            log("关闭完成");
        }
    }

    private static void log(String phase) {
        System.err.println("pid=" + ProcessHandle.current().pid() + "，elapsedMs="
                + (System.nanoTime() - STARTED) / 1_000_000 + "，" + phase);
    }
}
