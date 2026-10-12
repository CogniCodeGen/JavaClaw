package com.javaclaw.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.PlaywrightException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Function;

/** 优先启动系统 Google Chrome，仅在程序缺失时准备应用内 Chrome。 */
final class ChromeBrowserLauncher {

    private ChromeBrowserLauncher() {}

    static Browser launch(Function<BrowserType.LaunchOptions, Browser> launch,
            BrowserType.LaunchOptions options, Installer installer) {
        options.setChannel("chrome");
        try {
            return launch.apply(options);
        } catch (PlaywrightException failure) {
            if (failure.getMessage() == null
                    || !failure.getMessage().contains("Chromium distribution 'chrome' is not found")) {
                throw failure;
            }
            try {
                Path executable = installer.install();
                return launch.apply(options.setChannel((String) null).setExecutablePath(executable));
            } catch (IOException preparationFailure) {
                throw new IllegalStateException("未找到 Google Chrome，自动准备失败: "
                        + preparationFailure.getMessage(), preparationFailure);
            }
        }
    }

    @FunctionalInterface
    interface Installer {
        Path install() throws IOException;
    }
}
