package com.javaclaw.desktop.shell;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import javafx.application.Application;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopClasspathLaunchTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    Path directory;

    @Test
    void 普通启动入口在真实Classpath子进程中显示生产主窗口并正常退出() throws Exception {
        assertFalse(Application.class.isAssignableFrom(JavaClawDesktopMain.class));
        Path log = directory.resolve("desktop-classpath.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // 直接把生产主类交给 JDK 启动器；测试 Preloader 只观察窗口并关闭，不替代生产启动流程。
        Process process = new ProcessBuilder(
                        java,
                        "--enable-native-access=ALL-UNNAMED",
                        "-Djavafx.preloader=" + DesktopLaunchSmokePreloader.class.getName(),
                        "-Djava.util.prefs.userRoot=" + directory.resolve("preferences"),
                        "-Djavaclaw.log.dir=" + directory.resolve("logs"),
                        "-XX:ErrorFile=" + directory.resolve("hs_err_pid%p.log"),
                        "-cp",
                        System.getProperty("java.class.path"),
                        JavaClawDesktopMain.class.getName(),
                        "classpath-smoke")
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            String output = Files.readString(log);
            assertTrue(finished, "Desktop 未按期关闭，日志：" + log + "\n" + output);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains(DesktopLaunchSmokePreloader.READY), output);
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
