package com.javaclaw.browser;

import com.javaclaw.platform.data.ApplicationHome;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显式运行 {@code mvn -Dtest=ChromeDownloadFunctionalIT test}，从 Google 下载并启动真实 Chrome。
 * 使用 target/ 下独立应用缓存，不修改系统 Chrome；不进入普通单元测试。
 */
class ChromeDownloadFunctionalIT {

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void 官方Chrome下载后可启动且缓存离线复用() throws Exception {
        Path root = Files.createDirectories(Path.of(System.getProperty("user.dir"),
                "target", "chrome-download-it"));
        ApplicationHome home = ApplicationHome.at(root).prepare();
        var installer = new ChromeForTestingInstaller();
        Path executable = installer.ensureInstalled(home);
        assertEquals(executable, installer.ensureInstalled(home));
        assertTrue(Files.isExecutable(executable));

        String previousTemporary = System.getProperty("playwright.driver.tmpdir");
        System.setProperty("playwright.driver.tmpdir", home.temporaryDirectory().toString());
        try (var playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of(
                "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1",
                "PLAYWRIGHT_BROWSERS_PATH", home.playwrightBrowsersDirectory().toString(),
                "TMPDIR", home.temporaryDirectory().toString(),
                "TMP", home.temporaryDirectory().toString(),
                "TEMP", home.temporaryDirectory().toString())));
             var browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setExecutablePath(executable).setHeadless(true))) {
            var page = browser.newPage();
            page.setContent("<title>下载的 Chrome 验证</title><p id='result'>启动成功</p>");
            assertEquals("下载的 Chrome 验证", page.title());
            assertEquals("启动成功", page.locator("#result").textContent());
            try (var files = Files.list(home.playwrightBrowsersDirectory())) {
                assertEquals(0, files.count(), "不能下载 Playwright 的其他浏览器内核");
            }
        } finally {
            if (previousTemporary == null) System.clearProperty("playwright.driver.tmpdir");
            else System.setProperty("playwright.driver.tmpdir", previousTemporary);
        }
    }
}
