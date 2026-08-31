package com.javaclaw.browser;

import java.net.HttpCookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitUntilState;

import com.javaclaw.protocol.LocalRpcPeer;

/** 单线程 Playwright Actor；不暴露任意 JavaScript、Cookie 导出或本机路径，网络只能经反向 Broker 请求。 */
final class InteractiveBrowserRenderer implements AutoCloseable {
    private static final int BINARY_LIMIT = 4 * 1024 * 1024;
    private final LocalRpcPeer peer;
    private final ObjectMapper json;
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, ElementHandle> references = new LinkedHashMap<>();
    private final Map<String, Download> downloads = new LinkedHashMap<>();
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private String referencePage;
    private String fingerprint;
    private long generation;
    private long networkBytes;
    private int networkRequests;
    private final Set<String> secretValues = new java.util.LinkedHashSet<>();

    InteractiveBrowserRenderer(LocalRpcPeer peer, ObjectMapper json) {
        this.peer = peer;
        this.json = json;
    }

    JsonNode handle(String method, JsonNode params) throws Exception {
        return switch (method) {
            case "browser/open" -> open(params);
            case "browser/state" -> {
                requireContext();
                String state = context.storageState();
                if (state.length() > 512_000) {
                    throw new IllegalStateException("session state exceeds limit");
                }
                yield json.createObjectNode().put("state", state);
            }
            case "browser/close" -> {
                close();
                yield json.createObjectNode().put("closed", true);
            }
            case "browser/action" -> action(params);
            default -> throw new IllegalArgumentException("unsupported browser method");
        };
    }

    private ObjectNode open(JsonNode params) throws Exception {
        if (context != null) {
            throw new IllegalStateException("browser session is already open");
        }
        stage("driver");
        playwright = BrowserEnvironment.create();
        stage("chromium");
        var arguments = new ArrayList<>(List.of(
                "--disable-background-networking",
                "--disable-sync",
                "--disable-default-apps",
                "--no-first-run",
                "--disable-quic",
                "--force-webrtc-ip-handling-policy=disable_non_proxied_udp"));
        if (System.getenv("WAYLAND_DISPLAY") != null
                && System.getProperty("os.name")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("linux")) {
            arguments.add("--ozone-platform=wayland");
        }
        browser = playwright
                .chromium()
                .launch(new BrowserType.LaunchOptions()
                        .setTimeout(20_000)
                        // 下载和 PDF 与会话的私有临时目录同生命周期；不能落到宿主机默认 Downloads 或全局临时目录。
                        .setDownloadsPath(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")))
                        .setHeadless(!params.path("headed").asBoolean())
                        .setArgs(arguments));
        var options = new Browser.NewContextOptions()
                .setViewportSize(1280, 900)
                .setServiceWorkers(ServiceWorkerPolicy.BLOCK)
                .setAcceptDownloads(true);
        if (params.hasNonNull("state")) {
            options.setStorageState(params.path("state").asText());
        }
        stage("context");
        context = browser.newContext(options);
        context.clearPermissions();
        context.route("**/*", this::network);
        context.routeWebSocket("**/*", socket -> socket.close());
        context.onPage(this::register);
        Page page = context.newPage();
        register(page);
        stage("navigate");
        page.navigate(
                params.path("url").asText(),
                new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(20_000));
        stage("snapshot");
        return snapshot(id(page));
    }

    private static void stage(String value) {
        // 固定阶段代码供受限启动诊断使用；不输出 URL、页面内容、凭据或 Playwright 异常正文。
        System.err.println("Browser Service stage: " + value);
    }

    private void register(Page page) {
        if (pages.containsValue(page)) {
            return;
        }
        if (pages.size() >= 8) {
            page.close();
            return;
        }
        pages.put("tab_" + UUID.randomUUID().toString().substring(0, 8), page);
        page.setDefaultTimeout(5_000);
        page.onDialog(dialog -> dialog.dismiss());
        page.onDownload(download -> {
            if (downloads.size() >= 8) {
                download.cancel();
                return;
            }
            downloads.put("download_" + UUID.randomUUID().toString().substring(0, 8), download);
        });
        page.onClose(ignored -> pages.values().removeIf(value -> value == page));
    }

    private ObjectNode action(JsonNode params) throws Exception {
        requireContext();
        String operation = params.path("operation").asText();
        String tab = params.path("tabId")
                .asText(pages.isEmpty() ? "" : pages.keySet().iterator().next());
        Page page = pages.get(tab);
        if (page == null) {
            throw new IllegalArgumentException("unknown tab");
        }
        switch (operation) {
            case "snapshot", "tabs" -> {
                return snapshot(tab);
            }
            case "navigate" ->
                page.navigate(
                        params.path("value").asText(),
                        new Page.NavigateOptions()
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                                .setTimeout(20_000));
            case "newTab" -> {
                if (pages.size() >= 8) {
                    throw new IllegalStateException("tab quota exceeded");
                }
                page = context.newPage();
                register(page);
                tab = id(page);
                page.navigate(
                        params.path("value").asText(),
                        new Page.NavigateOptions()
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                                .setTimeout(20_000));
            }
            case "closeTab" -> {
                if (pages.size() == 1) {
                    throw new IllegalStateException("close the session instead of its last tab");
                }
                page.close();
                tab = pages.keySet().iterator().next();
            }
            case "click" -> element(page, tab, params).click();
            case "downloadLink" -> {
                // Chromium 的原生 HTTP 下载不经过 Playwright route。显式下载链接只通过 Broker 发起一次 GET，
                // 不重复点击、不重新提交表单，也不为下载开放沙箱原始网络。
                var link = element(page, tab, params);
                if (!"a".equals(String.valueOf(link.evaluate("e => e.tagName.toLowerCase()")))) {
                    throw new IllegalArgumentException("downloadLink requires a current link reference");
                }
                URI target = URI.create(String.valueOf(link.evaluate("e => e.href")));
                if (!Set.of("https", "http").contains(target.getScheme()) || target.getUserInfo() != null) {
                    throw new IllegalArgumentException("download link must be an HTTP resource without user info");
                }
                if (++networkRequests > 1000) {
                    throw new IllegalStateException("browser network request quota exceeded");
                }
                var request = json.createObjectNode()
                        .put("url", target.toString())
                        .put("method", "GET")
                        .put("bodyBase64", "");
                var headers = request.putObject("headers");
                headers.put("Accept", "*/*");
                String cookies = String.join(
                        "; ",
                        context.cookies(target.toString()).stream()
                                .map(cookie -> cookie.name + "=" + cookie.value)
                                .toList());
                if (!cookies.isEmpty()) {
                    headers.put("Cookie", cookies);
                }
                var response = peer.call("broker/request", request, Duration.ofSeconds(20));
                int status = response.path("status").asInt();
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("download endpoint did not return a successful response");
                }
                byte[] bytes = binary(response.path("bodyBase64").asText());
                networkBytes += bytes.length;
                if (networkBytes > 64L * 1024 * 1024) {
                    throw new IllegalStateException("browser network byte quota exceeded");
                }
                applyCookies(target, response.path("headers").path("set-cookie"));
                String name = link.getAttribute("download");
                return artifact(bytes, "application/octet-stream", name == null || name.isBlank() ? "download" : name);
            }
            case "fill" -> {
                var element = element(page, tab, params);
                if ("password".equalsIgnoreCase(element.getAttribute("type"))
                        && !params.path("secret").asBoolean()) {
                    throw new IllegalArgumentException("password fields require a server SecretRef");
                }
                String text = params.path("value").asText();
                if (text.length() > 16_384) {
                    throw new IllegalArgumentException("fill exceeds limit");
                }
                if (params.path("secret").asBoolean()) {
                    if (secretValues.size() >= 16 && !secretValues.contains(text)) {
                        throw new IllegalStateException("secret quota exceeded");
                    }
                    secretValues.add(text);
                }
                element.fill(text);
            }
            case "select" ->
                element(page, tab, params).selectOption(params.path("value").asText());
            case "press" -> {
                String key = params.path("value").asText();
                if (!Set.of("Enter", "Tab", "Escape", "ArrowUp", "ArrowDown", "Space")
                        .contains(key)) {
                    throw new IllegalArgumentException("unsupported browser key");
                }
                element(page, tab, params).press(key);
            }
            case "upload" -> {
                byte[] bytes = binary(params.path("bodyBase64").asText());
                String name = params.path("name").asText();
                if (name.isBlank() || name.length() > 200 || name.contains("/") || name.contains("\\")) {
                    throw new IllegalArgumentException("invalid display name");
                }
                element(page, tab, params)
                        .setInputFiles(
                                new FilePayload(name, params.path("mediaType").asText(), bytes));
            }
            case "wait" -> {
                int millis = params.path("value").asInt();
                if (millis < 0 || millis > 5_000) {
                    throw new IllegalArgumentException("wait exceeds limit");
                }
                page.waitForTimeout(millis);
            }
            case "screenshot", "pdf" -> {
                if (operation.equals("pdf") && !secretValues.isEmpty()) {
                    throw new IllegalStateException(
                            "PDF export is unavailable after a secret fill; use a masked screenshot");
                }
                var masks = new ArrayList<com.microsoft.playwright.Locator>();
                masks.add(page.locator("input,textarea,[contenteditable=true]"));
                Page screenshotPage = page;
                secretValues.forEach(secret ->
                        masks.add(screenshotPage.getByText(secret, new Page.GetByTextOptions().setExact(false))));
                byte[] bytes = operation.equals("pdf")
                        ? page.pdf()
                        : page.screenshot(
                                new Page.ScreenshotOptions().setFullPage(false).setMask(masks));
                return artifact(bytes, operation.equals("pdf") ? "application/pdf" : "image/png", operation);
            }
            case "download" -> {
                Download download = downloads.get(params.path("value").asText());
                if (download == null) {
                    throw new IllegalArgumentException("unknown download");
                }
                String failure = download.failure();
                if (failure != null) {
                    // 仅记录已知固定错误码，浏览器异常可能含 URL、路径或认证信息，不能直接写日志。
                    String category = Set.of(
                                            "canceled",
                                            "net::ERR_ABORTED",
                                            "net::ERR_FAILED",
                                            "FILE_ACCESS_DENIED",
                                            "FILE_FAILED")
                                    .contains(failure)
                            ? failure
                            : "unclassified";
                    System.err.println("Browser download rejected: " + category);
                    throw new IllegalStateException("browser download did not complete");
                }
                try (var input = download.createReadStream()) {
                    byte[] bytes = input.readNBytes(BINARY_LIMIT + 1);
                    return artifact(bytes, "application/octet-stream", download.suggestedFilename());
                }
            }
            default -> throw new IllegalArgumentException("unsupported browser operation");
        }
        return snapshot(tab);
    }

    private ElementHandle element(Page page, String tab, JsonNode params) throws Exception {
        if (!tab.equals(referencePage) || !hash(page).equals(fingerprint)) {
            throw new IllegalStateException("page changed; refresh snapshot");
        }
        ElementHandle element = references.get(params.path("reference").asText());
        if (element == null || !element.isVisible()) {
            throw new IllegalArgumentException("stale or invisible element");
        }
        return element;
    }

    private ObjectNode snapshot(String tab) throws Exception {
        Page page = pages.get(tab);
        references.values().forEach(ElementHandle::dispose);
        references.clear();
        referencePage = tab;
        fingerprint = hash(page);
        generation++;
        var value = json.createObjectNode()
                .put("tabId", tab)
                .put("url", page.url())
                .put("title", bounded(page.title(), 2000))
                .put("text", bounded(page.locator("body").innerText(), 24_000));
        var nodes = value.putArray("elements");
        List<ElementHandle> elements = page.querySelectorAll(
                "a,button,input:not([type=hidden]),textarea,select,[role=button],[contenteditable=true]");
        int index = 0;
        for (var element : elements) {
            if (index >= 200 || !element.isVisible()) {
                element.dispose();
                continue;
            }
            String ref = "r" + generation + "_" + index++;
            references.put(ref, element);
            String tag = String.valueOf(element.evaluate("e => e.tagName.toLowerCase()"));
            String type = element.getAttribute("type");
            String label = element.getAttribute("aria-label");
            if (label == null || label.isBlank()) {
                label = element.getAttribute("placeholder");
            }
            if (label == null || label.isBlank()) {
                label = element.innerText();
            }
            nodes.addObject()
                    .put("reference", ref)
                    .put("tag", tag)
                    .put("type", type == null ? "" : type)
                    .put("label", bounded(label == null ? "" : label, 300));
        }
        var tabs = value.putArray("tabs");
        for (var entry : List.copyOf(pages.entrySet())) {
            tabs.addObject()
                    .put("id", entry.getKey())
                    .put("url", entry.getValue().url())
                    .put("title", bounded(entry.getValue().title(), 1000));
        }
        var files = value.putArray("downloads");
        downloads.forEach((id, download) ->
                files.addObject().put("id", id).put("name", bounded(download.suggestedFilename(), 200)));
        return value;
    }

    private void network(Route route) {
        try {
            if (++networkRequests > 1000) {
                throw new IllegalStateException("browser network request quota exceeded");
            }
            var request = route.request();
            byte[] body = request.postDataBuffer();
            if (body != null && body.length > BINARY_LIMIT) {
                throw new IllegalArgumentException("browser request exceeds limit");
            }
            var params = json.createObjectNode()
                    .put("url", request.url())
                    .put("method", request.method())
                    .put("bodyBase64", Base64.getEncoder().encodeToString(body == null ? new byte[0] : body));
            var headers = params.putObject("headers");
            request.allHeaders().forEach(headers::put);
            var response = peer.call("broker/request", params, Duration.ofSeconds(20));
            byte[] content = binary(response.path("bodyBase64").asText());
            networkBytes += content.length;
            if (networkBytes > 64L * 1024 * 1024) {
                throw new IllegalStateException("browser network byte quota exceeded");
            }
            var responseHeaders = new LinkedHashMap<String, String>();
            response.path("headers").properties().forEach(entry -> {
                if (!entry.getKey().equalsIgnoreCase("set-cookie")) {
                    var parts = new ArrayList<String>();
                    entry.getValue().forEach(part -> parts.add(part.asText()));
                    responseHeaders.put(entry.getKey(), String.join(", ", parts));
                }
            });
            applyCookies(URI.create(request.url()), response.path("headers").path("set-cookie"));
            route.fulfill(new Route.FulfillOptions()
                    .setStatus(response.path("status").asInt())
                    .setHeaders(responseHeaders)
                    .setBodyBytes(content));
        } catch (Exception rejected) {
            route.abort();
        }
    }

    private void applyCookies(URI uri, JsonNode headers) {
        for (var header : headers) {
            for (HttpCookie value : HttpCookie.parse(header.asText())) {
                String domain = value.getDomain() == null ? uri.getHost() : value.getDomain();
                String checked = domain.startsWith(".") ? domain.substring(1) : domain;
                if (!uri.getHost().equalsIgnoreCase(checked)) {
                    continue;
                }
                // 只接受当前准确主机的 Cookie，不把 Domain 扩大到公共后缀或其他站点。
                domain = uri.getHost();
                var cookie = new Cookie(value.getName(), value.getValue())
                        .setDomain(domain)
                        .setPath(value.getPath() == null ? "/" : value.getPath())
                        .setHttpOnly(value.isHttpOnly())
                        .setSecure(value.getSecure());
                String attributes = header.asText().toLowerCase(java.util.Locale.ROOT);
                if (attributes.matches("(?s).*;\\s*samesite=strict(?:;.*)?")) {
                    cookie.setSameSite(com.microsoft.playwright.options.SameSiteAttribute.STRICT);
                } else if (attributes.matches("(?s).*;\\s*samesite=none(?:;.*)?") && value.getSecure()) {
                    cookie.setSameSite(com.microsoft.playwright.options.SameSiteAttribute.NONE);
                } else {
                    cookie.setSameSite(com.microsoft.playwright.options.SameSiteAttribute.LAX);
                }
                if (value.getMaxAge() >= 0) {
                    cookie.setExpires(System.currentTimeMillis() / 1000.0 + value.getMaxAge());
                }
                context.addCookies(List.of(cookie));
            }
        }
    }

    private ObjectNode artifact(byte[] bytes, String mediaType, String name) {
        if (bytes.length > BINARY_LIMIT) {
            throw new IllegalArgumentException("browser artifact exceeds 4 MiB");
        }
        return json.createObjectNode()
                .put("bodyBase64", Base64.getEncoder().encodeToString(bytes))
                .put("mediaType", mediaType)
                .put("name", bounded(name, 200));
    }

    private static byte[] binary(String value) {
        if (value.length() > (BINARY_LIMIT + 2L) / 3 * 4) {
            throw new IllegalArgumentException("browser body exceeds limit");
        }
        byte[] bytes = Base64.getDecoder().decode(value);
        if (bytes.length > BINARY_LIMIT) {
            throw new IllegalArgumentException("browser body exceeds limit");
        }
        return bytes;
    }

    private static String hash(Page page) throws Exception {
        String content = page.content();
        if (content.length() > 2_000_000) {
            throw new IllegalArgumentException("page DOM exceeds limit");
        }
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256")
                        .digest((page.url() + "\n" + content).getBytes(StandardCharsets.UTF_8)));
    }

    private String id(Page page) {
        return pages.entrySet().stream()
                .filter(entry -> entry.getValue() == page)
                .findFirst()
                .orElseThrow()
                .getKey();
    }

    private static String bounded(String value, int limit) {
        return value.length() > limit ? value.substring(0, limit) : value;
    }

    private void requireContext() {
        if (context == null) {
            throw new IllegalStateException("browser is not open");
        }
    }

    void pump() {
        if (context != null && !pages.isEmpty()) {
            try {
                pages.values().iterator().next().waitForTimeout(10);
            } catch (com.microsoft.playwright.PlaywrightException closed) {
            }
        }
    }

    @Override
    public void close() {
        references.values().forEach(ElementHandle::dispose);
        references.clear();
        downloads.clear();
        if (context != null) {
            context.close();
            context = null;
        }
        pages.clear();
        if (browser != null) {
            browser.close();
            browser = null;
        }
        if (playwright != null) {
            playwright.close();
            playwright = null;
        }
    }
}
