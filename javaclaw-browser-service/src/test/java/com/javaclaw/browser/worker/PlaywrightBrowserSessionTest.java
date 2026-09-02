package com.javaclaw.browser.worker;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.WebSocketRoute;
import org.junit.jupiter.api.Test;

import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.builtin.contracts.SiteContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaywrightBrowserSessionTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void snapshotUsesEphemeralContextAndOnlyFulfillsThroughHostBroker() {
        FakePlaywright graph = new FakePlaywright();
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger brokerCalls = new AtomicInteger();
        PlaywrightBrowserSession session =
                new PlaywrightBrowserSession(Clock.fixed(NOW, ZoneOffset.UTC), network(brokerCalls), () -> {
                    creations.incrementAndGet();
                    return graph.playwright;
                });

        SiteContracts.PageSnapshot snapshot = session.snapshot(task(4), "{}".getBytes(StandardCharsets.UTF_8));
        graph.exerciseNetworkRoutes();
        graph.exerciseDownload();
        SiteContracts.PageSnapshot second = session.snapshot(task(20), new byte[0]);
        session.close();
        session.close();

        assertEquals(URI.create("https://docs.example.com/final"), snapshot.uri());
        assertEquals("Docs", snapshot.title());
        assertEquals("abcd", snapshot.text());
        assertEquals(NOW, snapshot.capturedAt());
        assertEquals("abcdef", second.text());
        assertEquals(1, creations.get());
        assertEquals(2, graph.contextCloses);
        assertEquals(1, graph.browserCloses);
        assertEquals(1, graph.playwrightCloses);
        assertEquals(1, brokerCalls.get());
        assertEquals(1, graph.httpFulfills);
        assertEquals(1, graph.httpAborts);
        assertEquals(0, graph.httpResumes);
        assertEquals(0, graph.webSocketConnects);
        assertEquals(1, graph.webSocketCloses);
        assertEquals(1, graph.downloadCancels);
        assertEquals(2, graph.secretMasks);
        assertEquals(1, graph.storageStates);
    }

    @Test
    void disconnectedBrowserIsClosedAndRecreated() {
        FakePlaywright graph = new FakePlaywright();
        PlaywrightBrowserSession session = new PlaywrightBrowserSession(
                Clock.fixed(NOW, ZoneOffset.UTC), network(new AtomicInteger()), () -> graph.playwright);
        session.snapshot(task(20), new byte[0]);
        graph.connected = false;

        session.snapshot(task(20), new byte[0]);
        session.close();

        assertEquals(2, graph.launches);
        assertEquals(2, graph.browserCloses);
        assertEquals(2, graph.playwrightCloses);
    }

    @Test
    void deniedFinalPageClosesContextAndBlankBodyIsNormalized() {
        FakePlaywright graph = new FakePlaywright();
        PlaywrightBrowserSession session = new PlaywrightBrowserSession(
                Clock.fixed(NOW, ZoneOffset.UTC), network(new AtomicInteger()), () -> graph.playwright);
        graph.body = null;
        assertEquals("", session.snapshot(task(20), new byte[0]).text());
        graph.finalUri = "https://blocked.example.net/";

        assertThrows(IllegalStateException.class, () -> session.snapshot(task(20), new byte[0]));
        assertEquals(2, graph.contextCloses);
        session.close();
    }

    @Test
    void launchFailureClosesPartiallyCreatedPlaywright() {
        AtomicInteger closes = new AtomicInteger();
        BrowserType failingType = proxy(BrowserType.class, (target, method, arguments) -> {
            if ("launch".equals(method.getName())) {
                throw new IllegalStateException("launch failed");
            }
            return defaultValue(method.getReturnType());
        });
        Playwright playwright = proxy(Playwright.class, (target, method, arguments) -> switch (method.getName()) {
            case "chromium" -> failingType;
            case "close" -> {
                closes.incrementAndGet();
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        });
        PlaywrightBrowserSession session = new PlaywrightBrowserSession(
                Clock.fixed(NOW, ZoneOffset.UTC), network(new AtomicInteger()), () -> playwright);

        assertThrows(IllegalStateException.class, () -> session.snapshot(task(20), new byte[0]));
        assertEquals(1, closes.get());
    }

    @Test
    void loginUsesHeadedEphemeralContextMasksSecretsAndReturnsOnlyPrivateStateBytes() {
        FakePlaywright graph = new FakePlaywright();
        AtomicInteger ready = new AtomicInteger();
        PlaywrightBrowserSession session = new PlaywrightBrowserSession(
                Clock.fixed(NOW, ZoneOffset.UTC), network(new AtomicInteger()), () -> graph.playwright);
        BrowserWorkerProtocol.LoginTask task = new BrowserWorkerProtocol.LoginTask(
                java.util.UUID.randomUUID().toString(),
                URI.create("https://docs.example.com/login"),
                Set.of(URI.create("https://docs.example.com")),
                Duration.ofSeconds(3));

        byte[] state = session.login(task, new byte[0], saveControl(), ready::incrementAndGet);
        try {
            assertEquals("{\"cookies\":[],\"origins\":[]}", new String(state, StandardCharsets.UTF_8));
        } finally {
            java.util.Arrays.fill(state, (byte) 0);
        }
        session.close();

        assertEquals(1, ready.get());
        assertEquals(false, graph.lastHeadless);
        assertEquals(1, graph.secretMasks);
        assertEquals(1, graph.contextCloses);
    }

    private static BrowserLoginControl saveControl() {
        return new BrowserLoginControl() {
            @Override
            public Decision decision() {
                return Decision.SAVE;
            }

            @Override
            public void close() {}
        };
    }

    private static BrowserNetworkPort network(AtomicInteger calls) {
        return (request, body) -> {
            calls.incrementAndGet();
            assertEquals("GET", request.method());
            assertEquals("browser", new String(body, StandardCharsets.UTF_8));
            return new BrowserNetworkChannel.NetworkResult(
                    200,
                    Map.of("content-type", List.of("text/css"), "set-cookie", List.of("session=secret")),
                    "host-body".getBytes(StandardCharsets.UTF_8),
                    false);
        };
    }

    private static BrowserWorkerProtocol.SnapshotTask task(int maximumCharacters) {
        return new BrowserWorkerProtocol.SnapshotTask(
                URI.create("https://docs.example.com/start"),
                Set.of(URI.create("https://docs.example.com")),
                maximumCharacters,
                Duration.ofSeconds(3));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0D;
        }
        return null;
    }

    private static final class FakePlaywright {
        private final Locator locator = proxy(Locator.class, this::locatorCall);
        private final Page page = proxy(Page.class, this::pageCall);
        private final BrowserContext context = proxy(BrowserContext.class, this::contextCall);
        private final Browser browser = proxy(Browser.class, this::browserCall);
        private final BrowserType browserType = proxy(BrowserType.class, this::browserTypeCall);
        private final Playwright playwright = proxy(Playwright.class, this::playwrightCall);
        private Consumer<Route> httpRoute;
        private Consumer<WebSocketRoute> webSocketRoute;
        private Consumer<Download> download;
        private boolean connected = true;
        private String finalUri = "https://docs.example.com/final";
        private String body = "  abcdef  ";
        private int launches;
        private int contextCloses;
        private int browserCloses;
        private int playwrightCloses;
        private int httpFulfills;
        private int httpResumes;
        private int httpAborts;
        private int webSocketConnects;
        private int webSocketCloses;
        private int downloadCancels;
        private int secretMasks;
        private int storageStates;
        private boolean lastHeadless = true;

        private Object playwrightCall(Object target, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "chromium" -> browserType;
                case "close" -> {
                    playwrightCloses++;
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object browserTypeCall(Object target, Method method, Object[] arguments) {
            if ("launch".equals(method.getName())) {
                launches++;
                connected = true;
                BrowserType.LaunchOptions options = (BrowserType.LaunchOptions) arguments[0];
                lastHeadless = options.headless == null || options.headless;
                return browser;
            }
            return defaultValue(method.getReturnType());
        }

        private Object browserCall(Object target, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "isConnected" -> connected;
                case "newContext" -> {
                    Browser.NewContextOptions options = (Browser.NewContextOptions) arguments[0];
                    if (options.storageState != null) {
                        storageStates++;
                    }
                    yield context;
                }
                case "close" -> {
                    browserCloses++;
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            };
        }

        @SuppressWarnings("unchecked")
        private Object contextCall(Object target, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "route" -> {
                    httpRoute = (Consumer<Route>) arguments[1];
                    yield null;
                }
                case "routeWebSocket" -> {
                    webSocketRoute = (Consumer<WebSocketRoute>) arguments[1];
                    yield null;
                }
                case "newPage" -> page;
                case "pages" -> List.of(page);
                case "storageState" -> "{\"cookies\":[],\"origins\":[]}";
                case "close" -> {
                    contextCloses++;
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            };
        }

        @SuppressWarnings("unchecked")
        private Object pageCall(Object target, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "url" -> finalUri;
                case "title" -> "Docs";
                case "locator" -> locator;
                case "onDownload" -> {
                    download = (Consumer<Download>) arguments[0];
                    yield null;
                }
                case "evaluate" -> {
                    secretMasks++;
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object locatorCall(Object target, Method method, Object[] arguments) {
            return "innerText".equals(method.getName()) ? body : defaultValue(method.getReturnType());
        }

        private void exerciseNetworkRoutes() {
            httpRoute.accept(route("https://docs.example.com/style.css"));
            httpRoute.accept(route("http://blocked.example.net/"));
            webSocketRoute.accept(socket());
        }

        private void exerciseDownload() {
            download.accept(proxy(Download.class, (target, method, arguments) -> {
                if ("cancel".equals(method.getName())) {
                    downloadCancels++;
                }
                return defaultValue(method.getReturnType());
            }));
        }

        private Route route(String uri) {
            Request request = proxy(Request.class, (target, method, arguments) -> switch (method.getName()) {
                case "url" -> uri;
                case "method" -> "GET";
                case "allHeaders" -> Map.of("accept", "text/css");
                case "postDataBuffer" -> "browser".getBytes(StandardCharsets.UTF_8);
                default -> defaultValue(method.getReturnType());
            });
            return proxy(Route.class, (target, method, arguments) -> switch (method.getName()) {
                case "request" -> request;
                case "fulfill" -> {
                    httpFulfills++;
                    yield null;
                }
                case "resume" -> {
                    httpResumes++;
                    yield null;
                }
                case "abort" -> {
                    httpAborts++;
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        private WebSocketRoute socket() {
            return proxy(WebSocketRoute.class, (target, method, arguments) -> switch (method.getName()) {
                case "connectToServer" -> {
                    webSocketConnects++;
                    yield target;
                }
                case "close" -> {
                    webSocketCloses++;
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
        }
    }
}
