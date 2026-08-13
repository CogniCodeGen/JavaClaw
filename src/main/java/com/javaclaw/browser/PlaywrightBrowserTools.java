package com.javaclaw.browser;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolObjectProvider;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.site.SiteCredentialManager;

import java.util.List;
import java.util.Objects;

/**
 * Lifecycle owner and compatibility facade for the Playwright tool components.
 *
 * <p>The framework scans each object returned by {@link #toolObjects()}; direct Java callers may use
 * the forwarding methods below. All components share one interruptible operation gate, so a
 * BrowserContext/Page is never accessed concurrently. Closing this facade shuts down the browser
 * only when ownership was explicitly transferred at construction.
 */
public final class PlaywrightBrowserTools implements AutoCloseable, ToolObjectProvider {

    private final PlaywrightBrowserManager browserManager;
    private final boolean ownsBrowserManager;
    private final BrowserSiteTools site;
    private final BrowserPageTools page;
    private final BrowserReadTools read;
    private final BrowserSessionTools session;
    private final List<Object> toolObjects;

    public PlaywrightBrowserTools(
            PlaywrightBrowserManager browserManager,
            SiteCredentialManager siteCredentials,
            ToolCallOrigin origin,
            JsonCodec json) {
        this(browserManager, siteCredentials, origin, json, false);
    }

    public PlaywrightBrowserTools(
            PlaywrightBrowserManager browserManager,
            SiteCredentialManager siteCredentials,
            ToolCallOrigin origin,
            JsonCodec json,
            boolean ownsBrowserManager) {
        this.browserManager = Objects.requireNonNull(browserManager, "browserManager");
        SiteCredentialManager checkedCredentials =
                Objects.requireNonNull(siteCredentials, "siteCredentials");
        ToolCallOrigin checkedOrigin = origin == null ? ToolCallOrigin.UNKNOWN : origin;
        SnapshotManager snapshots = new SnapshotManager();
        BrowserOperationGate gate = new BrowserOperationGate();
        this.site =
                new BrowserSiteTools(
                        browserManager, checkedCredentials, snapshots, checkedOrigin, gate, json);
        this.page = new BrowserPageTools(browserManager, snapshots, checkedOrigin, gate);
        this.read = new BrowserReadTools(browserManager, snapshots, gate);
        this.session = new BrowserSessionTools(browserManager, snapshots, checkedOrigin, gate);
        this.toolObjects = List.of(site, page, read, session);
        this.ownsBrowserManager = ownsBrowserManager;
        if (checkedOrigin.kind() != ToolCallOrigin.Kind.INTERACTIVE) {
            browserManager.activateScope(checkedOrigin.browserScopeId());
        }
    }

    public PlaywrightBrowserManager getBrowserManager() {
        return browserManager;
    }

    /**
     * Returns an immutable component list. Components share this facade's lifecycle and must not be
     * retained after {@link #close()}.
     */
    @Override
    public List<Object> toolObjects() {
        return toolObjects;
    }

    /** Tool component types used for startup-time authorization contract validation. */
    public static List<Class<?>> toolContractTypes() {
        return List.of(BrowserSiteTools.class, BrowserPageTools.class,
                BrowserReadTools.class, BrowserSessionTools.class);
    }

    @Override
    public void close() {
        if (ownsBrowserManager) {
            browserManager.shutdown();
        }
    }

    public String navigate(String url) {
        return site.navigate(url);
    }

    public String siteSelectAccount(String url, String account) {
        return site.siteSelectAccount(url, account);
    }

    public String siteLoginInteractive() {
        return site.siteLoginInteractive();
    }

    public String siteLoginNow(
            String usernameSelector, String passwordSelector, String submitSelector) {
        return site.siteLoginNow(usernameSelector, passwordSelector, submitSelector);
    }

    public String siteFillPassword(String targetSelector) {
        return site.siteFillPassword(targetSelector);
    }

    public String siteSaveSession() {
        return site.siteSaveSession();
    }

    public String siteClearSession() {
        return site.siteClearSession();
    }

    public String goBack() {
        return page.goBack();
    }

    public String goForward() {
        return page.goForward();
    }

    public String reload() {
        return page.reload();
    }

    public String click(String target) {
        return page.click(target);
    }

    public String doubleClick(String target) {
        return page.doubleClick(target);
    }

    public String fill(String target, String text) {
        return page.fill(target, text);
    }

    public String type(String target, String text) {
        return page.type(target, text);
    }

    public String hover(String target) {
        return page.hover(target);
    }

    public String select(String target, String value) {
        return page.select(target, value);
    }

    public String check(String target, boolean checked) {
        return page.check(target, checked);
    }

    public String focus(String target) {
        return page.focus(target);
    }

    public String upload(String target, String filePath) {
        return page.upload(target, filePath);
    }

    public String drag(String source, String target) {
        return page.drag(source, target);
    }

    public String pressKey(String key) {
        return page.pressKey(key);
    }

    public String scroll(String direction, int amount, String target) {
        return page.scroll(direction, amount, target);
    }

    public String scrollToElement(String target) {
        return page.scrollToElement(target);
    }

    public String waitForElement(String selector, int timeoutSeconds) {
        return page.waitForElement(selector, timeoutSeconds);
    }

    public String waitForText(String text, int timeoutSeconds) {
        return page.waitForText(text, timeoutSeconds);
    }

    public String waitForUrl(String urlPattern, int timeoutSeconds) {
        return page.waitForUrl(urlPattern, timeoutSeconds);
    }

    public String waitForLoad(String state) {
        return page.waitForLoad(state);
    }

    public String mouseMove(int x, int y) {
        return page.mouseMove(x, y);
    }

    public String mouseClickAt(int x, int y) {
        return page.mouseClickAt(x, y);
    }

    public String dialogHandle(boolean accept, String promptText) {
        return page.dialogHandle(accept, promptText);
    }

    public String setViewport(int width, int height) {
        return page.setViewport(width, height);
    }

    public String snapshot(boolean interactiveOnly, boolean showUrls) {
        return read.snapshot(interactiveOnly, showUrls);
    }

    public String screenshot(boolean fullPage) {
        return read.screenshot(fullPage);
    }

    public String screenshotAnnotated() {
        return read.screenshotAnnotated();
    }

    public String getText(String target) {
        return read.getText(target);
    }

    public String getHtml(String target) {
        return read.getHtml(target);
    }

    public String getAttribute(String target, String attribute) {
        return read.getAttribute(target, attribute);
    }

    public String getUrl() {
        return read.getUrl();
    }

    public String getTitle() {
        return read.getTitle();
    }

    public String getValue(String target) {
        return read.getValue(target);
    }

    public String getCount(String selector) {
        return read.getCount(selector);
    }

    public String isVisible(String target) {
        return read.isVisible(target);
    }

    public String isEnabled(String target) {
        return read.isEnabled(target);
    }

    public String isChecked(String target) {
        return read.isChecked(target);
    }

    public String tabNew(String url) {
        return session.tabNew(url);
    }

    public String tabList() {
        return session.tabList();
    }

    public String tabClose(int index) {
        return session.tabClose(index);
    }

    public String tabSwitch(int index) {
        return session.tabSwitch(index);
    }

    public String evalJs(String script) {
        return session.evalJs(script);
    }

    public String cookieGet(String url) {
        return session.cookieGet(url);
    }

    public String cookieSet(String name, String value, String domain, String path) {
        return session.cookieSet(name, value, domain, path);
    }

    public String cookieClear() {
        return session.cookieClear();
    }

    public String savePdf() {
        return session.savePdf();
    }
}
