package com.javaclaw.browser;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChromeBrowserLauncherTest {

    @Test
    void 系统已有Chrome时不触发下载() {
        AtomicInteger installs = new AtomicInteger();
        ChromeBrowserLauncher.launch(options -> {
            assertEquals("chrome", options.channel);
            return null;
        }, new BrowserType.LaunchOptions(), () -> {
            installs.incrementAndGet();
            return Path.of("chrome");
        });
        assertEquals(0, installs.get());
    }

    @Test
    void 系统缺少Chrome时只准备Chrome并保留启动参数() {
        var channels = new ArrayList<Object>();
        AtomicInteger installs = new AtomicInteger();
        Path executable = Path.of("cached-google-chrome");
        ChromeBrowserLauncher.launch(options -> {
            channels.add(options.channel);
            if (channels.size() == 1) throw missingChrome();
            assertEquals(executable, options.executablePath);
            assertEquals(Boolean.TRUE, options.headless);
            return null;
        }, new BrowserType.LaunchOptions().setHeadless(true), () -> {
            installs.incrementAndGet();
            return executable;
        });
        assertEquals(2, channels.size());
        assertEquals("chrome", channels.getFirst());
        assertNull(channels.getLast());
        assertEquals(1, installs.get());
    }

    @Test
    void 已安装Chrome的其他启动错误不触发下载() {
        PlaywrightException failure = new PlaywrightException("Chrome sandbox initialization failed");
        PlaywrightException observed = assertThrows(PlaywrightException.class,
                () -> ChromeBrowserLauncher.launch(options -> { throw failure; },
                        new BrowserType.LaunchOptions(), () -> {
                            throw new AssertionError("已有浏览器启动失败不应下载另一份");
                        }));
        assertSame(failure, observed);
    }

    @Test
    void 下载失败保留具体原因且不重复安装() {
        IOException failure = new IOException("Chrome 下载服务返回 HTTP 503");
        AtomicInteger installs = new AtomicInteger();
        IllegalStateException observed = assertThrows(IllegalStateException.class,
                () -> ChromeBrowserLauncher.launch(options -> { throw missingChrome(); },
                        new BrowserType.LaunchOptions(), () -> {
                            installs.incrementAndGet();
                            throw failure;
                        }));
        assertSame(failure, observed.getCause());
        assertEquals(1, installs.get());
    }

    private static PlaywrightException missingChrome() {
        return new PlaywrightException("Chromium distribution 'chrome' is not found at /missing/chrome");
    }
}
