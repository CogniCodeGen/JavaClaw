package com.javaclaw.browser.worker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ServiceWorkerPolicy;

/** Actor 线程独占的可见 Chromium Context；普通交互和临时登记共享相同原生启动及隔离选项。 */
final class VisibleBrowserContext implements AutoCloseable {
    private final Playwright playwright;
    private Browser browser;
    private BrowserContext context;

    VisibleBrowserContext(Supplier<Playwright> factory, byte[] storageState, boolean downloads) {
        playwright = factory.get();
        try {
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(false).setTimeout(20_000)
                    .setDownloadsPath(Path.of(System.getProperty("java.io.tmpdir")))
                    .setArgs(List.of("--disable-background-networking", "--disable-component-update", "--disable-sync",
                            "--disable-default-apps", "--no-first-run", "--no-default-browser-check", "--disable-quic",
                            "--force-webrtc-ip-handling-policy=disable_non_proxied_udp")));
            Browser.NewContextOptions options = new Browser.NewContextOptions().setViewportSize(1280, 900)
                    .setAcceptDownloads(downloads).setIgnoreHTTPSErrors(false).setServiceWorkers(ServiceWorkerPolicy.BLOCK);
            if (storageState.length > 0) {
                options.setStorageState(new String(storageState, StandardCharsets.UTF_8));
            }
            context = browser.newContext(options);
            context.clearPermissions();
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    BrowserContext context() {
        return context;
    }

    boolean connected() {
        return browser != null && browser.isConnected();
    }

    @Override
    public void close() {
        try {
            if (context != null) {
                context.close();
                context = null;
            }
        } finally {
            try {
                if (browser != null) {
                    browser.close();
                    browser = null;
                }
            } finally {
                playwright.close();
            }
        }
    }
}
