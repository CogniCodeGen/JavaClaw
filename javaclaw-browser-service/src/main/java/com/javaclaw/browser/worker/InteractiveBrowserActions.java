package com.javaclaw.browser.worker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.WaitUntilState;

import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BrowserContracts.Action;
import com.javaclaw.builtin.contracts.BrowserContracts.Operation;

/** 单次受限页面动作；无任意 JavaScript、磁盘路径或自动写操作重试入口。 */
final class InteractiveBrowserActions {
    private static final Set<Operation> NAVIGATION = Set.of(
            Operation.NAVIGATE,
            Operation.BACK,
            Operation.FORWARD,
            Operation.RELOAD,
            Operation.NEW_TAB,
            Operation.CLOSE_TAB,
            Operation.SWITCH_TAB);
    private static final Set<Operation> ELEMENT = Set.of(
            Operation.CLICK,
            Operation.DOUBLE_CLICK,
            Operation.FILL,
            Operation.SELECT,
            Operation.CHECK,
            Operation.PRESS,
            Operation.HOVER,
            Operation.UPLOAD,
            Operation.FILL_SECRET);
    private final BrowserContext context;
    private final InteractiveBrowserPages pages;
    private final InteractiveBrowserNetwork network;
    private final Supplier<BrowserContracts.AccessLease> lease;

    InteractiveBrowserActions(
            BrowserContext context,
            InteractiveBrowserPages pages,
            InteractiveBrowserNetwork network,
            Supplier<BrowserContracts.AccessLease> lease) {
        this.context = context;
        this.pages = pages;
        this.network = network;
        this.lease = lease;
    }

    Optional<ArtifactBytes> execute(Action action, byte[] input) throws IOException {
        if (input.length > BrowserContracts.MAXIMUM_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("Browser private input exceeds limit");
        }
        if (action.operation() != Operation.UPLOAD
                && action.operation() != Operation.FILL_SECRET
                && input.length != 0) {
            throw new IllegalArgumentException("Browser action cannot accept private bytes");
        }
        Page page = pages.page(action.target().pageId());
        if (NAVIGATION.contains(action.operation())) {
            navigate(page, action);
        } else if (ELEMENT.contains(action.operation())) {
            element(page, action, input);
        } else {
            return other(page, action);
        }
        return Optional.empty();
    }

    private void navigate(Page page, Action action) {
        String value = action.input().value();
        switch (action.operation()) {
            case NAVIGATE -> go(page, value);
            case BACK -> page.goBack(new Page.GoBackOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            case FORWARD -> page.goForward(new Page.GoForwardOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            case RELOAD -> page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            case NEW_TAB -> go(pages.newPage(), value);
            case CLOSE_TAB -> pages.closePage(page);
            case SWITCH_TAB -> pages.select(pages.id(page));
            default -> throw new IllegalArgumentException("unsupported navigation");
        }
    }

    private void go(Page page, String uri) {
        network.requireTarget(uri);
        page.navigate(
                uri,
                new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(20_000));
    }

    private void element(Page page, Action action, byte[] input) {
        ElementHandle target = pages.reference(page, action.target().reference());
        String value = action.input().value();
        switch (action.operation()) {
            case CLICK -> target.click();
            case DOUBLE_CLICK -> target.dblclick();
            case FILL -> fill(target, value, false);
            case FILL_SECRET -> secret(target, input);
            case SELECT -> target.selectOption(value);
            case CHECK -> target.setChecked(checked(value));
            case PRESS -> target.press(key(value));
            case HOVER -> target.hover();
            case UPLOAD -> {
                BrowserContracts.FileSpec file = action.input().file().orElseThrow();
                target.setInputFiles(new FilePayload(file.fileName(), file.mediaType(), input));
            }
            default -> throw new IllegalArgumentException("unsupported element action");
        }
    }

    private Optional<ArtifactBytes> other(Page page, Action action) throws IOException {
        switch (action.operation()) {
            case SNAPSHOT -> {
                return Optional.empty();
            }
            case SCREENSHOT -> {
                return Optional.of(new ArtifactBytes(
                        pages.screenshot(page), new BrowserContracts.FileSpec("browser.png", "image/png")));
            }
            case CLICK_AT -> clickAt(page, action);
            case DRAG -> drag(page, action);
            case SCROLL -> scroll(page, action.input().value());
            case WAIT -> page.waitForTimeout(waitMillis(action.input().value()));
            case DOWNLOAD_LINK -> {
                return Optional.of(downloadLink(page, action));
            }
            case DOWNLOAD -> {
                return Optional.of(download(action.input().value()));
            }
            default -> throw new IllegalArgumentException("unsupported Browser action");
        }
        return Optional.empty();
    }

    private void clickAt(Page page, Action action) {
        BrowserContracts.Point point = pages.coordinate(
                page,
                action.target(),
                action.input().point().orElseThrow(),
                lease.get().generation());
        page.mouse().click(point.x(), point.y());
    }

    private void drag(Page page, Action action) {
        BrowserContracts.Drag drag = action.input().drag().orElseThrow();
        long generation = lease.get().generation();
        BrowserContracts.Point from = pages.coordinate(page, action.target(), drag.from(), generation);
        BrowserContracts.Point to = pages.coordinate(page, action.target(), drag.to(), generation);
        page.mouse().move(from.x(), from.y());
        page.mouse().down();
        try {
            page.mouse().move(to.x(), to.y(), new com.microsoft.playwright.Mouse.MoveOptions().setSteps(12));
        } finally {
            page.mouse().up();
        }
    }

    private void fill(ElementHandle target, String value, boolean secret) {
        if ("password".equalsIgnoreCase(target.getAttribute("type")) && !secret) {
            throw new IllegalArgumentException("Browser password requires a private secret fill");
        }
        target.fill(value);
    }

    private void secret(ElementHandle target, byte[] input) {
        if (input.length < 1 || input.length > 65_536) {
            throw new IllegalArgumentException("invalid Browser secret length");
        }
        String value = new String(input, StandardCharsets.UTF_8);
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid Browser secret");
        }
        pages.secret(value);
        fill(target, value, true);
    }

    private ArtifactBytes downloadLink(Page page, Action action) {
        ElementHandle element = pages.reference(page, action.target().reference());
        if (!"a".equals(element.evaluate("e => e.tagName.toLowerCase()"))) {
            throw new IllegalArgumentException("Browser download requires a link reference");
        }
        String raw = (String) element.evaluate("e => e.href");
        java.net.URI uri = network.requireTarget(raw);
        String cookie = context.cookies(raw).stream()
                .map(value -> value.name + "=" + value.value)
                .collect(Collectors.joining("; "));
        Map<String, List<String>> headers = cookie.isEmpty() ? Map.of() : Map.of("cookie", List.of(cookie));
        var response = network.exchange(new BrowserContracts.NetworkRequest(uri, "GET", headers), new byte[0]);
        byte[] body = response.body();
        if (response.truncated()
                || response.statusCode() < 200
                || response.statusCode() >= 300
                || body.length > BrowserContracts.MAXIMUM_ARTIFACT_BYTES) {
            java.util.Arrays.fill(body, (byte) 0);
            throw new IllegalStateException("Browser download failed or exceeded limit");
        }
        String type = response.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("content-type"))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse("application/octet-stream");
        String name = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
        return new ArtifactBytes(
                body, new BrowserContracts.FileSpec(pages.safe(InteractiveBrowserPages.fileName(name), 200), type));
    }

    private ArtifactBytes download(String id) throws IOException {
        com.microsoft.playwright.Download download = pages.download(id);
        try (InputStream stream = download.createReadStream()) {
            byte[] body = stream.readNBytes(BrowserContracts.MAXIMUM_ARTIFACT_BYTES + 1);
            if (body.length > BrowserContracts.MAXIMUM_ARTIFACT_BYTES) {
                java.util.Arrays.fill(body, (byte) 0);
                throw new IllegalStateException("Browser download exceeded limit");
            }
            return new ArtifactBytes(
                    body,
                    new BrowserContracts.FileSpec(
                            pages.safe(InteractiveBrowserPages.fileName(download.suggestedFilename()), 200),
                            "application/octet-stream"));
        }
    }

    private static boolean checked(String value) {
        if (!Set.of("true", "false").contains(value)) {
            throw new IllegalArgumentException("Browser checked must be boolean");
        }
        return Boolean.parseBoolean(value);
    }

    private static String key(String value) {
        if (!Set.of(
                        "Enter",
                        "Tab",
                        "Escape",
                        "ArrowUp",
                        "ArrowDown",
                        "ArrowLeft",
                        "ArrowRight",
                        "Space",
                        "Backspace",
                        "Home",
                        "End",
                        "PageUp",
                        "PageDown")
                .contains(value)) {
            throw new IllegalArgumentException("unsupported Browser key");
        }
        return value;
    }

    private static long waitMillis(String value) {
        long milliseconds = Long.parseLong(value);
        if (milliseconds < 0 || milliseconds > 5000) {
            throw new IllegalArgumentException("Browser wait exceeds limit");
        }
        return milliseconds;
    }

    private static void scroll(Page page, String value) {
        String[] pair = value.split(",", -1);
        if (pair.length != 2) {
            throw new IllegalArgumentException("Browser scroll requires dx,dy CSS pixels");
        }
        double x = Double.parseDouble(pair[0]);
        double y = Double.parseDouble(pair[1]);
        if (!Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x) > 10_000 || Math.abs(y) > 10_000) {
            throw new IllegalArgumentException("Browser scroll exceeds limit");
        }
        page.mouse().wheel(x, y);
    }

    record ArtifactBytes(byte[] bytes, BrowserContracts.FileSpec file) implements AutoCloseable {
        @Override
        public void close() {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }
}
