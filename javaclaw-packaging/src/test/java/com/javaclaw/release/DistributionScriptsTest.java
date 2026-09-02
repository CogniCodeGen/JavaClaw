package com.javaclaw.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributionScriptsTest {
    private static final List<String> UNIX_LAUNCHERS =
            List.of("javaclaw", "javaclaw-cli", "javaclaw-health", "javaclaw-service");

    @Test
    void 健康检查隔离临时数据并使用不访问系统凭据的专用模式() throws IOException {
        String unix = Files.readString(sourceBin().resolve("javaclaw-health"), StandardCharsets.UTF_8);
        String windows = Files.readString(sourceBin().resolve("javaclaw-health.cmd"), StandardCharsets.UTF_8);

        assertTrue(unix.contains("-Djavaclaw.data.root=\"$HEALTH_ROOT/data-v5\""));
        assertTrue(unix.contains("-Djavaclaw.log.dir=\"$HEALTH_ROOT/data-v5/logs\""));
        assertTrue(unix.contains("com.javaclaw.launcher.AppServerLauncher --health-check"));
        assertFalse(unix.contains("com.javaclaw.launcher.AppServerLauncher --stdio"));
        assertTrue(windows.contains("-Djavaclaw.data.root=%HEALTH_ROOT%\\data-v5"));
        assertTrue(windows.contains("-Djavaclaw.log.dir=%HEALTH_ROOT%\\data-v5\\logs"));
        assertTrue(windows.contains("com.javaclaw.launcher.AppServerLauncher --health-check"));
        assertFalse(windows.contains("com.javaclaw.launcher.AppServerLauncher --stdio"));
    }

    @Test
    void Unix发行入口保留执行权限() {
        Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"));

        for (String launcher : UNIX_LAUNCHERS) {
            Path path = sourceBin().resolve(launcher);
            assertTrue(Files.isExecutable(path), () -> "Unix 发行入口缺少执行权限：" + path);
        }
    }

    @Test
    void 服务命令统一通过布局校验器注入Worker根() throws IOException {
        for (String launcher : List.of(
                "javaclaw-cli",
                "javaclaw-cli.cmd",
                "javaclaw-health",
                "javaclaw-health.cmd",
                "javaclaw-service",
                "javaclaw-service.cmd")) {
            String script = Files.readString(sourceBin().resolve(launcher), StandardCharsets.UTF_8);
            assertTrue(script.contains("com.javaclaw.launcher.AppServerLauncher"), launcher);
            assertFalse(script.contains("javaclaw.browser.worker.image-root"), launcher);
            assertFalse(script.contains("javaclaw.knowledge.worker.image-root"), launcher);
            assertFalse(script.contains("javaclaw.skill.worker.image-root"), launcher);
        }
    }

    @Test
    void 桌面发行入口显式启用托盘且Mac固定在首线程启动() throws IOException {
        String unix = Files.readString(sourceBin().resolve("javaclaw"), StandardCharsets.UTF_8);
        String windows = Files.readString(sourceBin().resolve("javaclaw.cmd"), StandardCharsets.UTF_8);

        assertTrue(unix.contains("Darwin)"));
        assertTrue(unix.contains("-XstartOnFirstThread"));
        assertTrue(unix.contains("-Djavaclaw.tray.launcher=true"));
        assertTrue(windows.contains("-Djavaclaw.tray.launcher=true"));
        assertFalse(windows.contains("-XstartOnFirstThread"));
    }

    @Test
    void 三平台安装器必须携带原生验证后的Worker目录() throws IOException {
        String linux = source("src/main/packaging/package-linux.sh");
        String macos = source("src/main/packaging/package-macos.sh");
        String windows = source("src/main/packaging/package-windows.ps1");

        for (String script : List.of(linux, macos, windows)) {
            assertTrue(script.contains("worker-image-v1.capability"));
            assertTrue(script.contains("browser-login-v1.capability"));
            assertTrue(script.contains("browser-oauth-v1.capability"));
            assertTrue(script.contains("workers"));
        }
        assertTrue(linux.contains("$APP_IMAGE/lib/app/workers"));
        assertTrue(macos.contains("$APP_IMAGE/Contents/app/workers"));
        assertTrue(windows.contains("app\\workers"));
    }

    @Test
    void 构建将私有依赖分配到独立镜像并锁定Chromium来源() throws IOException {
        String pom = source("pom.xml");
        String workflow = source("../.github/workflows/javaclaw-v5.yml");

        assertTrue(pom.contains("distribution/workers/browser/app"));
        assertTrue(pom.contains("distribution/workers/knowledge/app"));
        assertTrue(pom.contains("java.compiler,java.logging,java.prefs,jdk.compiler,jdk.jshell"));
        assertTrue(pom.contains("javaclaw.playwright.browsers.path"));
        assertTrue(workflow.contains("playwright@1.52.0 install chromium"));
        assertTrue(workflow.contains("-Djavaclaw.playwright.browsers.path="));
        assertTrue(workflow.contains("macos-15-intel"));
        assertTrue(workflow.contains("ubuntu-24.04-arm"));
        assertTrue(workflow.contains("windows-2025"));
    }

    private static Path sourceBin() {
        return packagingRoot().resolve("src/main/distribution/bin");
    }

    private static String source(String relative) throws IOException {
        return Files.readString(packagingRoot().resolve(relative).normalize(), StandardCharsets.UTF_8);
    }

    private static Path packagingRoot() {
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ""))
                .toAbsolutePath()
                .normalize();
        Path repository = Files.isDirectory(root.resolve("javaclaw-packaging")) ? root : root.getParent();
        return repository.resolve("javaclaw-packaging");
    }
}
