package com.javaclaw.browser.worker;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitUntilState;

import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.builtin.contracts.SiteContracts;

/** 每次任务创建无持久状态 BrowserContext，所有外部请求由宿主 Broker 完成。 */
final class PlaywrightBrowserSession implements BrowserSession {
    private static final String MASK_SECRETS_SCRIPT = """
            () => {
              const selectors = [
                'input[type=password]',
                'input[autocomplete=current-password]',
                'input[autocomplete=new-password]',
                '[data-javaclaw-secret]'
              ];
              for (const element of document.querySelectorAll(selectors.join(','))) {
                if ('value' in element) element.value = '********';
                if (element.matches('[data-javaclaw-secret]')) element.textContent = '********';
                element.setAttribute('data-javaclaw-masked', 'true');
              }
            }
            """;

    private final Clock clock;
    private final BrowserNetworkPort network;
    private final PlaywrightFactory playwrightFactory;
    private Playwright playwright;
    private Browser browser;
    private boolean headless;

    PlaywrightBrowserSession(Clock clock, BrowserNetworkPort network) {
        this(clock, network, Playwright::create);
    }

    PlaywrightBrowserSession(Clock clock, BrowserNetworkPort network, PlaywrightFactory playwrightFactory) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.network = Objects.requireNonNull(network, "network");
        this.playwrightFactory = Objects.requireNonNull(playwrightFactory, "playwrightFactory");
    }

    /**
     * 实现说明：路由和 WebSocket 阻断在导航前安装。HTTP 请求只能由宿主 Broker 返回的字节 fulfill，Worker 永不调用 resume/fallback/fetch；Service
     * Worker、下载和 WSS 同样无法建立旁路。
     */
    @Override
    public synchronized SiteContracts.PageSnapshot snapshot(
            BrowserWorkerProtocol.SnapshotTask task, byte[] storageState) {
        Objects.requireNonNull(task, "task");
        byte[] checkedState =
                Objects.requireNonNull(storageState, "storageState").clone();
        ensureBrowser(true);
        try {
            Browser.NewContextOptions options = contextOptions(checkedState);
            try (BrowserContext context = browser.newContext(options)) {
                configureNetwork(context, task);
                context.setDefaultNavigationTimeout(task.timeout().toMillis());
                context.setDefaultTimeout(task.timeout().toMillis());
                Page page = context.newPage();
                page.onDownload(download -> download.cancel());
                page.navigate(
                        task.uri().toASCIIString(),
                        new Page.NavigateOptions()
                                .setTimeout(task.timeout().toMillis())
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                URI finalUri = URI.create(page.url()).normalize();
                BrowserNetworkPolicy.requireFinalPage(finalUri, task.allowedOrigins());
                maskSecrets(page);
                return new SiteContracts.PageSnapshot(
                        finalUri,
                        page.title(),
                        truncate(page.locator("body").innerText(), task.maxCharacters()),
                        Instant.now(clock));
            }
        } finally {
            Arrays.fill(checkedState, (byte) 0);
        }
    }

    /**
     * 实现说明：登录窗口使用全新 BrowserContext，并继续安装与快照相同的 Broker-only route、Service Worker/WSS 阻断和下载取消。保存前先遮罩密码与显式 Secret
     * 节点，再只通过调用方拥有的二进制结果返回 storage state；不会创建截图、HAR、trace 或持久 profile。
     */
    @Override
    public synchronized byte[] login(
            BrowserWorkerProtocol.LoginTask task, byte[] storageState, BrowserLoginControl control, Runnable ready) {
        Objects.requireNonNull(task, "task");
        BrowserLoginControl checkedControl = Objects.requireNonNull(control, "control");
        Runnable checkedReady = Objects.requireNonNull(ready, "ready");
        byte[] checkedState =
                Objects.requireNonNull(storageState, "storageState").clone();
        ensureBrowser(false);
        try {
            Browser.NewContextOptions options = contextOptions(checkedState);
            try (BrowserContext context = browser.newContext(options)) {
                configureNetwork(context, task.allowedOrigins());
                context.setDefaultNavigationTimeout(Math.min(
                        task.timeout().toMillis(), Duration.ofSeconds(60).toMillis()));
                context.setDefaultTimeout(Duration.ofSeconds(30).toMillis());
                context.onPage(PlaywrightBrowserSession::protectPage);
                Page page = context.newPage();
                protectPage(page);
                page.navigate(
                        task.uri().toASCIIString(),
                        new Page.NavigateOptions()
                                .setTimeout(Math.min(
                                        task.timeout().toMillis(),
                                        Duration.ofSeconds(60).toMillis()))
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                BrowserNetworkPolicy.requireFinalPage(URI.create(page.url()).normalize(), task.allowedOrigins());
                checkedReady.run();
                awaitDecision(page, checkedControl, task.timeout());
                maskAllSecrets(context);
                byte[] result = context.storageState().getBytes(StandardCharsets.UTF_8);
                if (result.length == 0 || result.length > BrowserWorkerProtocol.MAXIMUM_STATE_BYTES) {
                    Arrays.fill(result, (byte) 0);
                    throw new IllegalStateException("Browser storage state is outside the private frame limit");
                }
                return result;
            }
        } finally {
            Arrays.fill(checkedState, (byte) 0);
        }
    }

    /**
     * 实现说明：OAuth 使用独立无持久状态 Context。所有 HTTPS 请求仍由宿主 Broker fulfill；唯一允许的 HTTP 是精确 loopback redirect，它只在 route 内截获并返回私有
     * callback，不发起 socket、DNS 或宿主 HTTP 请求。
     */
    @Override
    public synchronized URI oauth(BrowserWorkerProtocol.OAuthTask task, BrowserLoginControl control, Runnable ready) {
        Objects.requireNonNull(task, "task");
        BrowserLoginControl checkedControl = Objects.requireNonNull(control, "control");
        Runnable checkedReady = Objects.requireNonNull(ready, "ready");
        ensureBrowser(false);
        try (BrowserContext context = browser.newContext(contextOptions(new byte[0]))) {
            AtomicReference<URI> callback = new AtomicReference<>();
            configureOAuthNetwork(context, task, callback);
            context.setDefaultNavigationTimeout(
                    Math.min(task.timeout().toMillis(), Duration.ofSeconds(60).toMillis()));
            context.setDefaultTimeout(Duration.ofSeconds(30).toMillis());
            context.onPage(PlaywrightBrowserSession::protectPage);
            Page page = context.newPage();
            protectPage(page);
            page.navigate(
                    task.authorizationUri().toASCIIString(),
                    new Page.NavigateOptions()
                            .setTimeout(Math.min(
                                    task.timeout().toMillis(),
                                    Duration.ofSeconds(60).toMillis()))
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            checkedReady.run();
            return awaitOAuthCallback(page, checkedControl, callback, task.timeout());
        }
    }

    private static Browser.NewContextOptions contextOptions(byte[] storageState) {
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setAcceptDownloads(false)
                .setIgnoreHTTPSErrors(false)
                .setServiceWorkers(ServiceWorkerPolicy.BLOCK);
        if (storageState.length > 0) {
            options.setStorageState(new String(storageState, StandardCharsets.UTF_8));
        }
        return options;
    }

    private void configureNetwork(BrowserContext context, BrowserWorkerProtocol.SnapshotTask task) {
        configureNetwork(context, task.allowedOrigins());
    }

    private void configureNetwork(BrowserContext context, java.util.Set<URI> allowedOrigins) {
        context.route("**/*", route -> fulfillThroughBroker(route, allowedOrigins));
        context.routeWebSocket("**/*", socket -> socket.close());
    }

    private void configureOAuthNetwork(
            BrowserContext context, BrowserWorkerProtocol.OAuthTask task, AtomicReference<URI> callback) {
        context.route("**/*", route -> {
            URI target = URI.create(route.request().url()).normalize();
            if (sameCallbackTarget(target, task.redirectUri())) {
                if (target.getQuery() == null || target.getQuery().isBlank()) {
                    route.abort("blockedbyclient");
                    return;
                }
                callback.compareAndSet(null, target);
                route.fulfill(
                        new Route.FulfillOptions()
                                .setStatus(200)
                                .setHeaders(Map.of("content-type", "text/html; charset=utf-8"))
                                .setBody(
                                        "<!doctype html><title>JavaClaw</title>Authorization received. You may close this window."));
                return;
            }
            fulfillThroughBroker(route, task.allowedOrigins());
        });
        context.routeWebSocket("**/*", socket -> socket.close());
    }

    private void fulfillThroughBroker(Route route, java.util.Set<URI> allowedOrigins) {
        Request request = route.request();
        byte[] requestBody = request.postDataBuffer();
        byte[] body = requestBody == null ? new byte[0] : requestBody.clone();
        try {
            URI uri = BrowserNetworkPolicy.requireBrokerTarget(request.url(), allowedOrigins);
            BrowserWorkerProtocol.NetworkRequest metadata =
                    new BrowserWorkerProtocol.NetworkRequest(uri, request.method(), listHeaders(request.allHeaders()));
            BrowserNetworkChannel.NetworkResult result = network.exchange(metadata, body);
            fulfill(route, result);
        } catch (RuntimeException denied) {
            route.abort("blockedbyclient");
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private static void fulfill(Route route, BrowserNetworkChannel.NetworkResult result) {
        byte[] body = result.body();
        try {
            if (result.truncated()) {
                route.abort("blockedbyresponse");
                return;
            }
            route.fulfill(new Route.FulfillOptions()
                    .setStatus(result.statusCode())
                    .setHeaders(flattenHeaders(result.headers()))
                    .setBodyBytes(body));
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private static Map<String, List<String>> listHeaders(Map<String, String> headers) {
        return headers.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.of(entry.getValue())));
    }

    private static Map<String, String> flattenHeaders(Map<String, List<String>> headers) {
        return headers.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> String.join("\n", entry.getValue())));
    }

    private static void maskSecrets(Page page) {
        page.evaluate(MASK_SECRETS_SCRIPT);
    }

    private static void maskAllSecrets(BrowserContext context) {
        context.pages().stream().filter(page -> !page.isClosed()).forEach(PlaywrightBrowserSession::maskSecrets);
    }

    private static void protectPage(Page page) {
        page.onDownload(download -> download.cancel());
    }

    private static void awaitDecision(Page page, BrowserLoginControl control, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (page.isClosed()) {
                throw new BrowserLoginInterruptedException("LOGIN_CANCELLED");
            }
            BrowserLoginControl.Decision decision = control.decision();
            if (decision == BrowserLoginControl.Decision.SAVE) {
                return;
            }
            if (decision == BrowserLoginControl.Decision.CANCEL) {
                throw new BrowserLoginInterruptedException("LOGIN_CANCELLED");
            }
            if (System.nanoTime() >= deadline) {
                throw new BrowserLoginInterruptedException("LOGIN_EXPIRED");
            }
            page.waitForTimeout(100);
        }
    }

    private static URI awaitOAuthCallback(
            Page page, BrowserLoginControl control, AtomicReference<URI> callback, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            URI completed = callback.get();
            if (completed != null) {
                return completed;
            }
            if (page.isClosed() || control.decision() == BrowserLoginControl.Decision.CANCEL) {
                throw new BrowserOAuthInterruptedException("OAUTH_CANCELLED");
            }
            if (System.nanoTime() >= deadline) {
                throw new BrowserOAuthInterruptedException("OAUTH_EXPIRED");
            }
            page.waitForTimeout(100);
        }
    }

    private static boolean sameCallbackTarget(URI candidate, URI redirect) {
        return "http".equalsIgnoreCase(candidate.getScheme())
                && Objects.equals(candidate.getHost(), redirect.getHost())
                && candidate.getPort() == redirect.getPort()
                && Objects.equals(candidate.getPath(), redirect.getPath())
                && candidate.getUserInfo() == null
                && candidate.getFragment() == null;
    }

    private void ensureBrowser(boolean requestedHeadless) {
        if (browser != null && browser.isConnected() && headless == requestedHeadless) {
            return;
        }
        close();
        playwright = playwrightFactory.create();
        try {
            browser = playwright
                    .chromium()
                    .launch(new com.microsoft.playwright.BrowserType.LaunchOptions()
                            .setHeadless(requestedHeadless)
                            .setArgs(List.of(
                                    "--disable-background-networking",
                                    "--disable-component-update",
                                    "--disable-default-apps",
                                    "--disable-dev-shm-usage",
                                    "--disable-domain-reliability",
                                    "--disable-features=MediaRouter,OptimizationHints,Translate",
                                    "--disable-sync",
                                    "--metrics-recording-only",
                                    "--no-default-browser-check",
                                    "--no-first-run")));
            headless = requestedHeadless;
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    private static String truncate(String value, int maximumCharacters) {
        String normalized = value == null ? "" : value.strip();
        return normalized.length() <= maximumCharacters ? normalized : normalized.substring(0, maximumCharacters);
    }

    @Override
    public synchronized void close() {
        if (browser != null) {
            browser.close();
            browser = null;
            headless = false;
        }
        if (playwright != null) {
            playwright.close();
            playwright = null;
        }
    }

    @FunctionalInterface
    interface PlaywrightFactory {
        Playwright create();
    }
}
