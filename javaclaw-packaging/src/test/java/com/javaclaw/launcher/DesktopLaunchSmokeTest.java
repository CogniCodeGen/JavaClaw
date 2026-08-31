package com.javaclaw.launcher;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopLaunchSmokeTest {
    @TempDir
    Path temporary;

    @Test
    @EnabledIf("nativeDesktopGate")
    void opensTheRealWindowLoadsInitialStateAndClosesOwnedProcesses() throws Exception {
        String java = System.getProperty(
                "javaclaw.smoke.java",
                Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                System.getProperty("os.name", "")
                                                .toLowerCase(Locale.ROOT)
                                                .contains("windows")
                                        ? "java.exe"
                                        : "java")
                        .toString());
        String productClasspath = System.getProperty("javaclaw.smoke.classpath");
        String classpath = productClasspath == null
                ? LaunchTestSupport.ideClasspath()
                : Path.of(DesktopLaunchProbe.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI())
                        + File.pathSeparator
                        + productClasspath;
        List<String> command = new ArrayList<>(
                List.of(java, "-Djavaclaw.ui.reduceMotion=true", "-cp", classpath, DesktopLaunchProbe.class.getName()));
        Path log = temporary.resolve("desktop.log");
        ProcessBuilder builder =
                new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        // 真实窗口测试必须使用独立数据与缓存，不能连接或修改开发者已有的服务和凭据。
        builder.environment()
                .keySet()
                .removeIf(name -> name.startsWith("JAVACLAW_")
                        || name.startsWith("OPENAI_")
                        || name.startsWith("ANTHROPIC_")
                        || name.startsWith("GOOGLE_")
                        || name.startsWith("GEMINI_")
                        || name.startsWith("SPRING_AI_"));
        // 视觉验收只连接测试内的 loopback SSE 服务；知识导入也不得意外使用开发者的付费 Embedding。
        builder.environment().put("JAVACLAW_EMBEDDING_PROVIDER", "desktop-test-disabled");
        builder.environment()
                .put("JAVACLAW_DATA_DIR", temporary.resolve("数据 data").toString());
        builder.environment()
                .put("JAVACLAW_CONFIG_DIR", temporary.resolve("配置 config").toString());
        builder.environment()
                .put("JAVACLAW_CACHE_DIR", temporary.resolve("缓存 cache").toString());
        String screenshot = System.getProperty("javaclaw.smoke.screenshot");
        if (screenshot != null) {
            builder.environment().put("JAVACLAW_TEST_SCREENSHOT", screenshot);
        }
        Process process = builder.start();
        try {
            boolean exited = process.waitFor(90, TimeUnit.SECONDS);
            String output = Files.readString(log);
            assertTrue(exited, () -> "Desktop timed out; log: " + log + "\n" + output);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("JAVACLAW_DESKTOP_SMOKE_OK"), output);
            assertTrue(output.contains("JAVACLAW_DESKTOP_TRANSCRIPT_OK"), output);
            assertTrue(output.contains("JAVACLAW_DESKTOP_SETTINGS_MENU_OK"), output);
            assertTrue(output.contains("JAVACLAW_DESKTOP_PROVIDER_UI_OK"), output);
            assertTrue(output.contains("JAVACLAW_DESKTOP_PROVIDER_PERSISTENCE_OK"), output);
            assertFalse(output.contains("DESKTOP_TEST_RECONNECT"), output);
            assertFalse(output.contains("No SLF4J providers were found"), output);
            assertFalse(output.contains("Failed to load class \"org.slf4j.impl.StaticLoggerBinder\""), output);
            assertFalse(output.contains("Log4j2 could not find a logging implementation"), output);
            assertFalse(output.contains("Log4j API could not find a logging provider"), output);
            assertEquals(
                    9,
                    output.lines()
                            .filter(line -> line.startsWith("JAVACLAW_DESKTOP_THEME_OK"))
                            .count(),
                    output);
            assertEquals(
                    12,
                    output.lines()
                            .filter(line -> line.startsWith("JAVACLAW_DESKTOP_PAGE_OK"))
                            .count(),
                    output);
            verifyVisualRegression(screenshot);
            assertProductLog("desktop");
            assertProductLog("app-server");
        } finally {
            process.destroyForcibly();
        }
    }

    private static void verifyVisualRegression(String screenshot) throws Exception {
        boolean gate = Boolean.getBoolean("javaclaw.visual.gate");
        boolean update = Boolean.getBoolean("javaclaw.visual.update");
        if (!gate && !update) {
            return;
        }
        assertTrue(screenshot != null && !screenshot.isBlank(), "视觉门禁需要 -Djavaclaw.smoke.screenshot");
        if (gate) {
            assertTrue(
                    System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"),
                    "像素门禁只在固定 macOS/JDK 25 环境执行");
            assertTrue(Runtime.version().feature() == 25, "像素门禁要求 JDK 25");
        }
        VisualRegressionGate.verify(Path.of(screenshot), update);
    }

    private void assertProductLog(String process) throws Exception {
        Path log = temporary.resolve("配置 config/logs").resolve(process + ".log");
        assertTrue(Files.isRegularFile(log), () -> "缺少产品滚动日志：" + log);
        assertTrue(Files.size(log) <= 6L * 1024 * 1024, () -> "活动日志超过单文件滚动上限：" + log);
        String content = Files.readString(log);
        assertFalse(content.contains("desktop-fixture-not-a-real-key"), () -> "日志泄漏合成凭据：" + log);
        assertTrue(content.contains(process.equals("desktop") ? "Desktop" : "App Server"), content);
    }

    private static boolean nativeDesktopGate() {
        return Boolean.getBoolean("javaclaw.desktop.smoke") || Boolean.getBoolean("javaclaw.require.native.sandbox");
    }
}
