package com.javaclaw.browser.worker;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.BindingCallback;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.ServiceWorkerPolicy;

/** 不启动 Chromium 的 actor 夹具；保留 DOM 变化、页面和私有输入的真实因果。 */
final class InteractivePlaywrightFixture {
    final List<FakePage> pages = new ArrayList<>();
    final BrowserContext context = proxy(BrowserContext.class, this::contextCall);
    final Browser browser = proxy(Browser.class, this::browserCall);
    final BrowserType type = proxy(BrowserType.class, this::typeCall);
    final Playwright playwright = proxy(Playwright.class, this::playwrightCall);
    Consumer<Page> onPage;
    Consumer<Route> route;
    Consumer<FakePage> createdPage = ignored -> {};
    boolean headless = true;
    boolean indexedDb;
    ServiceWorkerPolicy serviceWorkers;
    int closes;
    int contextCloses;
    boolean connected = true;
    String storage = "{\"cookies\":[],\"origins\":[{\"indexedDB\":[]}]}";
    Runnable beforeStorage = () -> {};
    final Map<String, BindingCallback> bindings = new LinkedHashMap<>();
    final List<String> scripts = new ArrayList<>();
    final List<Cookie> cookies = new ArrayList<>();

    private Object playwrightCall(Object target, Method method, Object[] args) {
        return switch (method.getName()) {
            case "chromium" -> type;
            case "close" -> {
                closes++;
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        };
    }

    private Object typeCall(Object target, Method method, Object[] args) {
        if (method.getName().equals("launch")) {
            headless = ((BrowserType.LaunchOptions) args[0]).headless;
            return browser;
        }
        return defaultValue(method.getReturnType());
    }

    private Object browserCall(Object target, Method method, Object[] args) {
        return switch (method.getName()) {
            case "isConnected" -> connected;
            case "newContext" -> {
                serviceWorkers = ((Browser.NewContextOptions) args[0]).serviceWorkers;
                yield context;
            }
            default -> defaultValue(method.getReturnType());
        };
    }

    @SuppressWarnings("unchecked")
    private Object contextCall(Object target, Method method, Object[] args) {
        return switch (method.getName()) {
            case "close" -> {
                contextCloses++;
                // Playwright 的 Context.close 同时关闭其页面，并触发已有的页面关闭监听器。
                pages.stream().filter(page -> !page.closed).forEach(page -> page.page.close());
                yield null;
            }
            case "route" -> {
                route = (Consumer<Route>) args[1];
                yield null;
            }
            case "onPage" -> {
                onPage = (Consumer<Page>) args[0];
                yield null;
            }
            case "exposeBinding" -> {
                bindings.put((String) args[0], (BindingCallback) args[1]);
                yield null;
            }
            case "addInitScript" -> {
                scripts.add((String) args[0]);
                yield null;
            }
            case "newPage" -> {
                FakePage page = new FakePage();
                pages.add(page);
                createdPage.accept(page);
                onPage.accept(page.page);
                yield page.page;
            }
            case "cookies" -> cookies;
            case "storageState" -> {
                indexedDb = ((BrowserContext.StorageStateOptions) args[0]).indexedDB;
                beforeStorage.run();
                yield storage;
            }
            default -> defaultValue(method.getReturnType());
        };
    }

    static final class FakePage {
        final Frame frame =
                proxy(Frame.class, (target, method, args) -> method.getName().equals("url") ? this.uri : null);
        Consumer<Frame> onNavigated = ignored -> {};
        Runnable navigation = () -> {};
        final Locator locator = proxy(Locator.class, this::locatorCall);
        final Mouse mouse = proxy(Mouse.class, this::mouseCall);
        final Page page = proxy(Page.class, this::pageCall);
        Consumer<Page> onClose;
        Consumer<Download> onDownload;
        final List<String> commands = new ArrayList<>();
        FilePayload uploaded;
        boolean link;
        String linkUri = "https://docs.example.com/report.txt";
        boolean mouseHeld;
        double wheelX;
        double wheelY;
        String uri = "about:blank";
        String user = "";
        String password = "";
        String body = "Login";
        long dom;
        double scroll;
        double clickedX;
        double clickedY;
        int clicks;
        int masks;
        int valueReads;
        boolean sameForm = true;
        boolean closed;

        @SuppressWarnings("unchecked")
        private Object pageCall(Object target, Method method, Object[] args) {
            return switch (method.getName()) {
                case "url" -> uri;
                case "content" -> "<html>" + dom + "</html>";
                case "locator", "getByText" -> locator;
                case "mouse" -> mouse;
                case "isClosed" -> closed;
                case "onClose" -> {
                    onClose = (Consumer<Page>) args[0];
                    yield null;
                }
                case "close" -> {
                    closed = true;
                    onClose.accept(page);
                    yield null;
                }
                case "navigate" -> {
                    uri = (String) args[0];
                    dom++;
                    onNavigated.accept(frame);
                    navigation.run();
                    yield null;
                }
                case "querySelectorAll" -> elements((String) args[0]);
                case "evaluate" -> pageEvaluate(args);
                case "screenshot" -> screenshot((Page.ScreenshotOptions) args[0]);
                default -> pageOther(method, args);
            };
        }

        @SuppressWarnings("unchecked")
        private Object pageOther(Method method, Object[] args) {
            if (method.getName().equals("mainFrame")) {
                return frame;
            }
            if (method.getName().equals("onFrameNavigated")) {
                onNavigated = (Consumer<Frame>) args[0];
                return null;
            }
            if (method.getName().equals("waitForTimeout")) {
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                return null;
            }
            commands.add(method.getName());
            if (method.getName().equals("onDownload")) {
                onDownload = (Consumer<Download>) args[0];
            }
            return method.getName().equals("title") ? body : defaultValue(method.getReturnType());
        }

        private Object pageEvaluate(Object[] args) {
            return "() => [location.href, document.title]".equals(args[0])
                    ? List.of(uri, body)
                    : List.of(1280, 900, 0, scroll, 2);
        }

        private List<ElementHandle> elements(String selector) {
            if (selector.equals("input[type=password]")) {
                return List.of(element(true));
            }
            return List.of(element(false), element(true));
        }

        private byte[] screenshot(Page.ScreenshotOptions options) {
            masks = options.mask.size();
            byte[] png = new byte[24];
            ByteBuffer.wrap(png).putInt(16, 2560).putInt(20, 1800);
            return png;
        }

        private ElementHandle element(boolean secret) {
            return proxy(ElementHandle.class, (target, method, args) -> switch (method.getName()) {
                case "asElement" -> target;
                case "isVisible", "isEnabled" -> true;
                case "evaluate" -> elementEvaluate(args);
                case "evaluateHandle" -> {
                    checkOrigin((String) args[1]);
                    yield element(false);
                }
                case "getAttribute" -> attribute(secret, (String) args[0]);
                case "innerText" -> "";
                case "inputValue" -> {
                    valueReads++;
                    yield secret ? password : user;
                }
                case "fill" -> {
                    if (secret) {
                        password = (String) args[0];
                    } else {
                        user = (String) args[0];
                    }
                    dom++;
                    body = user + " " + password;
                    yield null;
                }
                case "click" -> {
                    clicks++;
                    dom++;
                    yield null;
                }
                default -> elementOther(method, args);
            });
        }

        private Object elementOther(Method method, Object[] args) {
            commands.add(method.getName());
            if (method.getName().equals("setInputFiles")) {
                uploaded = (FilePayload) args[0];
            }
            if (method.getName().equals("selectOption")) {
                return List.of((String) args[0]);
            }
            return defaultValue(method.getReturnType());
        }

        private Object elementEvaluate(Object[] args) {
            if (args.length < 2) {
                String expression = (String) args[0];
                if (expression.contains("e.href")) {
                    return linkUri;
                }
                return expression.contains("e.form") ? "Sign in" : link ? "a" : "input";
            }
            Map<?, ?> values = (Map<?, ?>) args[1];
            checkOrigin((String) values.get("origin"));
            if (!sameForm) {
                throw new IllegalStateException("BROWSER_CREDENTIAL_TARGET_CHANGED");
            }
            if (((String) args[0]).contains("return user.value")) {
                return user + '\0' + password;
            }
            user = (String) values.get("username");
            password = (String) values.get("secret");
            body = user + " " + password;
            dom++;
            return null;
        }

        private void checkOrigin(String expected) {
            URI actual = URI.create(uri);
            if (!(actual.getScheme() + "://" + actual.getAuthority()).equals(expected)) {
                throw new IllegalStateException("BROWSER_CREDENTIAL_TARGET_CHANGED");
            }
        }

        private String attribute(boolean secret, String key) {
            return switch (key) {
                case "type" -> secret ? "password" : "text";
                case "aria-label" -> secret ? "Password" : "Username";
                default -> null;
            };
        }

        private Object locatorCall(Object target, Method method, Object[] args) {
            return method.getName().equals("innerText") ? body : defaultValue(method.getReturnType());
        }

        private Object mouseCall(Object target, Method method, Object[] args) {
            commands.add("mouse." + method.getName());
            if (method.getName().equals("down")) {
                mouseHeld = true;
            } else if (method.getName().equals("up")) {
                mouseHeld = false;
            } else if (method.getName().equals("wheel")) {
                wheelX = (double) args[0];
                wheelY = (double) args[1];
            }
            if (method.getName().equals("click")) {
                clicks++;
                clickedX = (double) args[0];
                clickedY = (double) args[1];
            }
            return defaultValue(method.getReturnType());
        }
    }

    BindingCallback.Source source(FakePage page) {
        return new BindingCallback.Source() {
            @Override
            public BrowserContext context() {
                return context;
            }

            @Override
            public Page page() {
                return page.page;
            }

            @Override
            public Frame frame() {
                return page.frame;
            }
        };
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (target, method, args) -> {
            if (method.getName().equals("equals")) {
                return target == args[0];
            }
            if (method.getName().equals("hashCode")) {
                return System.identityHashCode(target);
            }
            return handler.invoke(target, method, args);
        });
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
}
