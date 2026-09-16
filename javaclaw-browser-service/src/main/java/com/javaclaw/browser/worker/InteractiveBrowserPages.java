package com.javaclaw.browser.worker;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.JSHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import com.javaclaw.builtin.contracts.BrowserContracts;

/** Actor 独占的页面、元素引用与观察帧；导航、控制权变更和页面结构变化使旧引用失效。 */
final class InteractiveBrowserPages implements AutoCloseable {
    private static final String INTERACTIVE = "a[href],button,input:not([type=hidden]),textarea,select,"
            + "[role=button],[role=link],[role=checkbox],[role=combobox],[role=textbox],[contenteditable=true]";
    private final BrowserContext context;
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, ElementHandle> references = new LinkedHashMap<>();
    private final Map<String, Download> downloads = new LinkedHashMap<>();
    private final Set<String> secrets = new LinkedHashSet<>();
    private String selected = "";
    private String referencePage = "";
    private String fingerprint = "";
    private long epoch;
    private BrowserContracts.Frame frame;
    private boolean privateSession;

    InteractiveBrowserPages(BrowserContext context) {
        this.context = context;
        context.onPage(this::register);
    }

    void register(Page page) {
        if (pages.containsValue(page)) {
            return;
        }
        if (pages.size() >= BrowserContracts.MAXIMUM_TABS) {
            page.close();
            return;
        }
        String id = UUID.randomUUID().toString();
        pages.put(id, page);
        if (selected.isEmpty()) {
            selected = id;
        }
        page.setDefaultTimeout(5_000);
        page.onDialog(dialog -> dialog.dismiss());
        page.onDownload(this::download);
        page.onClose(ignored -> {
            pages.remove(id);
            if (id.equals(selected)) {
                selected = pages.isEmpty() ? "" : pages.keySet().iterator().next();
            }
            invalidate();
        });
    }

    Page page(String id) {
        Page result = pages.get(id.isEmpty() ? selected : id);
        if (result == null || result.isClosed()) {
            throw new IllegalStateException("Browser page is closed");
        }
        return result;
    }

    String id(Page page) {
        return pages.entrySet().stream()
                .filter(entry -> entry.getValue() == page)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }

    Page newPage() {
        if (pages.size() >= BrowserContracts.MAXIMUM_TABS) {
            throw new IllegalStateException("Browser tab quota exceeded");
        }
        Page page = context.newPage();
        register(page);
        select(id(page));
        return page;
    }

    void closePage(Page page) {
        if (pages.size() < 2) {
            throw new IllegalStateException("Close the Browser session to close its final tab");
        }
        page.close();
    }

    void select(String id) {
        Page page = page(id);
        selected = this.id(page);
        page.bringToFront();
        invalidate();
    }

    List<BrowserContracts.Tab> tabs() {
        return pages.entrySet().stream()
                .map(entry -> new BrowserContracts.Tab(
                        entry.getKey(),
                        safeUri(entry.getValue().url()),
                        safe(entry.getValue().title(), 1000),
                        entry.getKey().equals(selected)))
                .toList();
    }

    BrowserContracts.PageSnapshot snapshot(Page page) {
        collectSecrets(page);
        List<BrowserContracts.Element> elements = refreshReferences(page);
        List<BrowserContracts.DownloadInfo> available = downloads.entrySet().stream()
                .map(entry -> new BrowserContracts.DownloadInfo(
                        entry.getKey(), safe(fileName(entry.getValue().suggestedFilename()), 200)))
                .toList();
        return new BrowserContracts.PageSnapshot(
                id(page),
                safeUri(page.url()),
                safe(page.title(), 1000),
                safe(page.locator("body").innerText(), 24_000),
                elements,
                available);
    }

    private List<BrowserContracts.Element> refreshReferences(Page page) {
        invalidate();
        referencePage = id(page);
        fingerprint = hash(page);
        List<BrowserContracts.Element> elements = new ArrayList<>();
        for (ElementHandle element : page.querySelectorAll(INTERACTIVE)) {
            if (elements.size() >= 200 || !element.isVisible()) {
                element.dispose();
                continue;
            }
            String reference = "r" + epoch + "_" + elements.size();
            references.put(reference, element);
            elements.add(describe(element, reference));
        }
        return elements;
    }

    List<BrowserContracts.LoginForm> loginForms(Page page, URI expectedOrigin) {
        String origin = InteractiveBrowserCredentials.origin(expectedOrigin);
        refreshReferences(page);
        List<BrowserContracts.LoginForm> forms = new ArrayList<>();
        for (var entry : new ArrayList<>(references.entrySet())) {
            ElementHandle password = entry.getValue();
            if (!"password".equalsIgnoreCase(password.getAttribute("type")) || forms.size() >= 20) {
                continue;
            }
            JSHandle handle = password.evaluateHandle("""
                    (password, origin) => {
                      if (location.origin !== origin || password.ownerDocument !== document) {
                        throw new Error('BROWSER_CREDENTIAL_TARGET_CHANGED');
                      }
                      const scope = password.form || document;
                      const pick = selector => Array.from(scope.querySelectorAll(selector))
                        .find(input => input.form === password.form) || null;
                      return pick('input[autocomplete=username],input[type=email]')
                        || pick('input[type=text],input:not([type])');
                    }
                    """, origin);
            ElementHandle username = handle.asElement();
            if (username == null || !username.isVisible()) {
                handle.dispose();
                continue;
            }
            String userRef = "r" + epoch + "_u" + forms.size();
            references.put(userRef, username);
            String label = (String) password.evaluate("e => e.form?.getAttribute('aria-label') || e.form?.name || ''");
            forms.add(new BrowserContracts.LoginForm(
                    safe(label.isBlank() ? "登录表单 " + (forms.size() + 1) : label, 300),
                    describe(username, userRef).name(),
                    describe(password, entry.getKey()).name(),
                    new BrowserContracts.CredentialsTarget(id(page), userRef, entry.getKey())));
        }
        return List.copyOf(forms);
    }

    ElementHandle reference(Page page, String value) {
        if (!id(page).equals(referencePage) || !hash(page).equals(fingerprint)) {
            throw new IllegalStateException("BROWSER_STALE_OBSERVATION");
        }
        ElementHandle element = references.get(value);
        if (element == null || !element.isVisible()) {
            throw new IllegalStateException("BROWSER_STALE_OBSERVATION");
        }
        return element;
    }

    BrowserContracts.Frame frame(Page page, byte[] png, long generation) {
        int width = dimension(png, 16);
        int height = dimension(png, 20);
        frame = new BrowserContracts.Frame(
                UUID.randomUUID().toString(), id(page), epoch, generation, viewport(page), width, height);
        return frame;
    }

    BrowserContracts.Point coordinate(
            Page page, BrowserContracts.Target target, BrowserContracts.Point point, long generation) {
        if (frame == null
                || !frame.frameId().equals(target.frameId())
                || !frame.pageId().equals(id(page))
                || frame.controlGeneration() != generation
                || !hash(page).equals(fingerprint)
                || !frame.viewport().equals(viewport(page))) {
            throw new IllegalStateException("BROWSER_STALE_OBSERVATION");
        }
        if (point.x() >= frame.imageWidth() || point.y() >= frame.imageHeight()) {
            throw new IllegalArgumentException("Browser coordinate is outside the observation");
        }
        return new BrowserContracts.Point(
                point.x() * frame.viewport().width() / frame.imageWidth(),
                point.y() * frame.viewport().height() / frame.imageHeight());
    }

    byte[] screenshot(Page page) {
        collectSecrets(page);
        if (privateSession) {
            throw new IllegalStateException("BROWSER_SCREENSHOT_PRIVATE_SESSION");
        }
        List<Locator> masks = new ArrayList<>();
        masks.add(page.locator("input,textarea,[contenteditable=true]"));
        secrets.forEach(secret -> masks.add(page.getByText(secret, new Page.GetByTextOptions().setExact(false))));
        return page.screenshot(new Page.ScreenshotOptions().setFullPage(false).setMask(masks));
    }

    /** 人工输入、凭据或持久登录态可能被页面绘制到 Canvas；隐私标记在 Context 生存期内不可撤销。 */
    void restrictScreenshots() {
        privateSession = true;
        frame = null;
    }

    void secret(String value) {
        if (value.isEmpty()) {
            return;
        }
        if (secrets.size() >= 64 && !secrets.contains(value)) {
            throw new IllegalStateException("Browser secret quota exceeded");
        }
        restrictScreenshots();
        secrets.add(value);
    }

    Download download(String id) {
        Download download = downloads.get(id);
        if (download == null) {
            throw new IllegalArgumentException("Browser download is unknown");
        }
        return download;
    }

    String safe(String value, int maximum) {
        String text = value == null ? "" : value;
        for (String secret : secrets) {
            text = text.replace(secret, "[已遮罩]");
        }
        return text.length() <= maximum ? text : text.substring(0, maximum);
    }

    private URI safeUri(String value) {
        URI uri = URI.create(value);
        if (uri.getHost() == null) {
            return URI.create("about:blank");
        }
        String prefix = uri.getScheme() + "://" + uri.getRawAuthority();
        String result = value.substring(prefix.length())
                .replaceAll(
                        "(?i)([?&](?:code|password|passwd|token|access_token|refresh_token|id_token|secret|credential|authorization|session)=)[^&#]*",
                        "$1REDACTED");
        int fragment = result.indexOf('#');
        if (fragment >= 0) {
            result = result.substring(0, fragment);
        }
        for (String secret : secrets) {
            String encoded = java.net.URLEncoder.encode(secret, StandardCharsets.UTF_8);
            result = result.replace(secret, "REDACTED")
                    .replace(encoded, "REDACTED")
                    .replace(encoded.replace("+", "%20"), "REDACTED");
        }
        return URI.create(prefix + result);
    }

    void invalidate() {
        epoch++;
        references.values().forEach(ElementHandle::dispose);
        references.clear();
        frame = null;
    }

    void pump() {
        if (!pages.isEmpty()) {
            page("").waitForTimeout(25);
        }
    }

    boolean empty() {
        return pages.isEmpty();
    }

    private void download(Download download) {
        if (downloads.size() >= 8) {
            download.cancel();
            return;
        }
        downloads.put(UUID.randomUUID().toString(), download);
    }

    private BrowserContracts.Element describe(ElementHandle element, String reference) {
        String tag = element.evaluate("e => e.tagName.toLowerCase()").toString();
        String role = element.getAttribute("role");
        String name = element.getAttribute("aria-label");
        if (name == null || name.isBlank()) {
            name = element.getAttribute("placeholder");
        }
        if (name == null || name.isBlank()) {
            name = element.innerText();
        }
        Optional<Boolean> checked = Optional.empty();
        String type = element.getAttribute("type");
        if ("checkbox".equals(type) || "radio".equals(type)) {
            checked = Optional.of(element.isChecked());
        }
        return new BrowserContracts.Element(
                reference, safe(role == null ? tag : role, 100), safe(name, 300), tag, element.isEnabled(), checked);
    }

    private void collectSecrets(Page page) {
        for (ElementHandle field : page.querySelectorAll("input[type=password]")) {
            try {
                secret(field.inputValue());
            } finally {
                field.dispose();
            }
        }
    }

    private static BrowserContracts.Viewport viewport(Page page) {
        Object raw = page.evaluate("() => [innerWidth,innerHeight,scrollX,scrollY,devicePixelRatio]");
        List<?> values = (List<?>) raw;
        return new BrowserContracts.Viewport(
                ((Number) values.get(0)).intValue(),
                ((Number) values.get(1)).intValue(),
                ((Number) values.get(2)).doubleValue(),
                ((Number) values.get(3)).doubleValue(),
                ((Number) values.get(4)).doubleValue());
    }

    private static String hash(Page page) {
        String content = page.content();
        if (content.length() > 2_000_000) {
            throw new IllegalStateException("Browser page exceeds observation limit");
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest((page.url() + "\n" + content).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static int dimension(byte[] png, int offset) {
        if (png.length < 24) {
            throw new IllegalArgumentException("Browser screenshot is not PNG");
        }
        return java.nio.ByteBuffer.wrap(png, offset, 4).getInt();
    }

    static String fileName(String input) {
        String name = input == null ? "download.bin" : input.replaceAll("[\\\\/\\p{Cntrl}]", "_");
        if (name.isBlank()) {
            return "download.bin";
        }
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    @Override
    public void close() {
        invalidate();
        downloads.clear();
        pages.clear();
        secrets.clear();
    }
}
