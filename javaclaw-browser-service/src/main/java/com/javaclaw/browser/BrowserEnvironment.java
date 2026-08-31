package com.javaclaw.browser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.microsoft.playwright.Playwright;

/** 固定 Java driver 与 Node/Chromium 的临时目录；系统启动器可能重写 TMPDIR，不能依赖隐式继承。 */
final class BrowserEnvironment {
    private BrowserEnvironment() {}

    static Playwright create() throws java.io.IOException {
        Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        if (!Files.isDirectory(temporary) || !Files.isWritable(temporary)) {
            throw new java.io.IOException("browser temporary directory is unavailable");
        }
        return Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of(
                        "TMPDIR",
                        temporary.toString(),
                        "TMP",
                        temporary.toString(),
                        "TEMP",
                        temporary.toString(),
                        // Chromium 在 macOS 使用独立变量覆盖 NSTemporaryDirectory，TMPDIR 本身不会控制 PDF 流的临时文件。
                        "MAC_CHROMIUM_TMPDIR",
                        temporary.toString(),
                        "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD",
                        "1")));
    }
}
