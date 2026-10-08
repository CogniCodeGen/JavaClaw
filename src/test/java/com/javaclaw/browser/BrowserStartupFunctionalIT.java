package com.javaclaw.browser;

import com.javaclaw.platform.data.ApplicationHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在空浏览器缓存中验证真实 Chrome 启动，不进入普通单元测试。
 *
 * <p>显式运行 {@code mvn -Dtest=BrowserStartupFunctionalIT test}，需要安装 Google Chrome。
 * 可把 {@code PLAYWRIGHT_DOWNLOAD_HOST} 指向不可达地址，验证启动不依赖浏览器下载。</p>
 */
class BrowserStartupFunctionalIT {

    @Test
    @Timeout(30)
    void 空缓存首次启动和关闭后重启均可读取页面且不下载浏览器() throws Exception {
        Path testHome = Files.createTempDirectory(
                Path.of(System.getProperty("user.dir"), "target"), "browser-startup-");
        String previousHome = System.getProperty(ApplicationHome.DEVELOPMENT_HOME_PROPERTY);
        String previousTemporary = System.getProperty("playwright.driver.tmpdir");
        System.setProperty(ApplicationHome.DEVELOPMENT_HOME_PROPERTY, testHome.toString());
        try {
            Path browsers = ApplicationHome.resolve().prepare().playwrightBrowsersDirectory();
            assertDirectoryEmpty(browsers);
            assertStartupWithoutDownloads(testHome);
            assertStartupWithoutDownloads(testHome);
            assertDirectoryEmpty(browsers);
            try (var files = Files.list(testHome.resolve("data/tmp"))) {
                assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".zip")),
                        "首次启动不应产生浏览器下载包");
            }
        } finally {
            restoreProperty(ApplicationHome.DEVELOPMENT_HOME_PROPERTY, previousHome);
            restoreProperty("playwright.driver.tmpdir", previousTemporary);
        }
    }

    private static void assertStartupWithoutDownloads(Path testHome) {
        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(
                true, testHome.resolve("data/browser"), testHome.resolve("data/screenshots"));
        try {
            var page = manager.getActivePage();
            page.setContent("<title>浏览器启动验证</title><p id='result'>启动成功</p>");
            assertTrue(manager.isRunning());
            assertEquals("浏览器启动验证", page.title());
            assertEquals("启动成功", page.locator("#result").textContent());
        } finally {
            manager.shutdown();
        }
        assertFalse(manager.isRunning(), "关闭后应释放浏览器资源");
    }

    private static void assertDirectoryEmpty(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count(), "浏览器缓存应保持为空，不能安装额外浏览器");
        }
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }
}
