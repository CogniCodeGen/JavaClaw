package com.javaclaw.browser;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;

/** 无原始网络的进程外浏览器 Actor；stdout 仅传协议，所有网页网络请求通过反向 Broker 完成。 */
public final class BrowserServiceMain {
    private static final int PROTOCOL_VERSION = 1;
    private static final int MAX_FRAME_CHARS = 3 * 1024 * 1024;
    private static final int MAX_HTML_BYTES = 512 * 1024;
    private static final int MAX_SCREENSHOT_BYTES = 1536 * 1024;

    private BrowserServiceMain() {}

    /** 启动进程外 Browser Service，从 UTF-8 标准输入读取请求并向标准输出写回复；浏览器由本服务持有，服务结束时关闭。参数不参与协议处理。 */
    public static void main(String[] args) throws Exception {
        var protocolOutput = System.out;
        System.setOut(System.err);
        serve(
                new InputStreamReader(System.in, StandardCharsets.UTF_8),
                new OutputStreamWriter(protocolOutput, StandardCharsets.UTF_8));
    }

    static void serve(Reader input, Writer output) throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (var peer = new com.javaclaw.protocol.LocalRpcPeer(input, output);
                Renderer renderer = new Renderer();
                InteractiveBrowserRenderer interactive = new InteractiveBrowserRenderer(peer, json)) {
            boolean initialized = false;
            while (peer.isOpen()) {
                var request = peer.next(java.time.Duration.ofMillis(25));
                if (request == null) {
                    interactive.pump();
                    continue;
                }
                if (!initialized && !"initialize".equals(request.method())) {
                    peer.reject(request, -32002, "initialize is required");
                    continue;
                }
                try {
                    JsonNode result;
                    switch (request.method()) {
                        case "initialize" -> {
                            if (request.params().path("protocolVersion").asInt(PROTOCOL_VERSION) != PROTOCOL_VERSION) {
                                throw new IllegalArgumentException("unsupported version");
                            }
                            initialized = true;
                            result = json.createObjectNode()
                                    .put("protocolVersion", PROTOCOL_VERSION)
                                    .put("network", "disabled");
                        }
                        case "health" -> result = json.createObjectNode().put("status", "ok");
                        case "browser/render" -> result = renderer.render(request.params(), json);
                        case "browser/open", "browser/action", "browser/state", "browser/close" ->
                            result = interactive.handle(request.method(), request.params());
                        case "shutdown" -> {
                            peer.respond(request, json.createObjectNode().put("stopped", true));
                            return;
                        }
                        default -> {
                            peer.reject(request, -32601, "method not found");
                            continue;
                        }
                    }
                    peer.respond(request, result);
                } catch (Exception failure) {
                    // Playwright 异常会附带 DOM、URL 和输入值；不把原始错误写入任何协议响应。
                    System.err.println("Browser Service operation rejected: "
                            + failure.getClass().getSimpleName());
                    peer.reject(
                            request,
                            safeFailureCode(failure),
                            "browser operation rejected; refresh the page snapshot and verify capability");
                }
            }
        }
    }

    private static int safeFailureCode(Exception failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof java.nio.file.AccessDeniedException) {
                return -32013;
            }
            String message = current.getMessage();
            if (message != null && message.contains("Executable doesn't exist")) {
                return -32014;
            }
            if (message != null && message.contains("Failed to create driver")) {
                return -32015;
            }
            if (message != null && message.contains("Target page, context or browser has been closed")) {
                return -32016;
            }
        }
        return -32000;
    }

    private static String bounded(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static final class Renderer implements AutoCloseable {
        private Playwright playwright;
        private Browser browser;

        private ObjectNode render(JsonNode params, ObjectMapper json) throws java.io.IOException {
            byte[] htmlBytes;
            try {
                htmlBytes = Base64.getDecoder().decode(params.path("htmlBase64").asText());
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("htmlBase64 is invalid", failure);
            }
            if (htmlBytes.length < 1 || htmlBytes.length > MAX_HTML_BYTES) {
                throw new IllegalArgumentException("decoded HTML must contain 1-524288 bytes");
            }
            String html = new String(htmlBytes, StandardCharsets.UTF_8);
            int maximumText = params.path("maximumTextCharacters").asInt(20_000);
            if (maximumText < 100 || maximumText > 100_000) {
                throw new IllegalArgumentException("maximumTextCharacters is invalid");
            }
            boolean screenshot = params.path("screenshot").asBoolean(false);
            ensureBrowser();
            try (BrowserContext context =
                    browser.newContext(new Browser.NewContextOptions().setViewportSize(1280, 900))) {
                context.route("**/*", route -> route.abort());
                Page page = context.newPage();
                page.setDefaultTimeout(5_000);
                page.setContent(
                        html,
                        new Page.SetContentOptions()
                                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                                .setTimeout(5_000));
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                String title = bounded(page.title(), 4_000);
                String text = page.locator("body").innerText();
                text = bounded(text, maximumText);
                ObjectNode result = json.createObjectNode();
                result.put("title", title);
                result.put("text", text);
                if (screenshot) {
                    byte[] image = page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
                    if (image.length > MAX_SCREENSHOT_BYTES) {
                        throw new IllegalStateException("screenshot exceeds 2 MiB");
                    }
                    result.put("screenshotBase64", Base64.getEncoder().encodeToString(image));
                }
                return result;
            }
        }

        private void ensureBrowser() throws java.io.IOException {
            if (browser != null && browser.isConnected()) {
                return;
            }
            playwright = BrowserEnvironment.create();
            browser = playwright
                    .chromium()
                    .launch(new BrowserType.LaunchOptions()
                            .setHeadless(true)
                            .setArgs(List.of(
                                    "--disable-background-networking",
                                    "--disable-sync",
                                    "--disable-default-apps",
                                    "--no-first-run")));
        }

        @Override
        public void close() {
            if (browser != null) {
                browser.close();
            }
            if (playwright != null) {
                playwright.close();
            }
        }
    }
}
