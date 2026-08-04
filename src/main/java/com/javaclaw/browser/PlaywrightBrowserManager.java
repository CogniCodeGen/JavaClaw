package com.javaclaw.browser;

import com.javaclaw.config.AppDatabase;
import com.javaclaw.util.ProjectAccessPolicy;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Playwright 浏览器生命周期管理器
 *
 * <p>管理 Playwright 实例、Browser、BrowserContext 和 Page 的完整生命周期。
 * 支持多 Tab（Page）管理、Cookie 持久化、视口配置等。
 * 替代原有 JavaFX WebView 方案，提供原生 Chromium 浏览器交互能力。</p>
 *
 * <p>线程安全说明：所有 Playwright 操作必须在创建它的线程上执行，
 * 内部通过同步方法保证安全。</p>
 *
 * @author JavaClaw
 */
public class PlaywrightBrowserManager {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightBrowserManager.class);
    private static final String STORAGE_STATE_KEY = "playwright-storage-state";
    private static final String DEFAULT_SCOPE_ID = "interactive:default";

    /** 默认视口宽度 */
    private static final int DEFAULT_VIEWPORT_WIDTH = 1280;

    /** 默认视口高度 */
    private static final int DEFAULT_VIEWPORT_HEIGHT = 720;

    /** 默认导航超时（毫秒） */
    private static final double DEFAULT_NAVIGATION_TIMEOUT = 30_000;

    /** 默认操作超时（毫秒） */
    private static final double DEFAULT_ACTION_TIMEOUT = 15_000;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private final List<Page> pages = new ArrayList<>();
    private int activePageIndex = 0;

    /**
     * 交互浏览器一次只激活一个会话 Context；切换会话时把 storageState 暂存在内存，
     * 关闭旧 Context，再在同一 Browser 进程中创建新 Context。这样既隔离账号，又不为每个
     * 聊天会话启动一个 Chromium 进程。
     */
    private final Map<String, ScopeSnapshot> scopeSnapshots = new LinkedHashMap<>();
    private String activeScopeId = DEFAULT_SCOPE_ID;
    private String requestedScopeId = DEFAULT_SCOPE_ID;

    /** 浏览器状态目录（Cookie 持久化等） */
    private Path browserDir;

    /** 截图保存目录 */
    private Path screenshotDir;

    /** 是否无头模式 */
    private final boolean headless;

    /** 当前实际启动模式；交互式登录期间会临时切换为可见浏览器。 */
    private boolean currentHeadless;

    /** 是否正处于交互式登录的可见浏览器阶段。 */
    private boolean userInteractionActive;

    /** 本次交互式登录开始前的状态，用于用户拒绝保存时限定为当前会话内存状态。 */
    private String userInteractionBaselineState;

    /**
     * 非空表示当前 Context 含有“仅限本次会话”的登录态。该状态只保存在当前会话的内存快照，
     * 任务重置时可用此基线回滚，绝不写入工作区级全局认证状态。
     */
    private String transientPersistenceBaseline;

    /**
     * 兼容旧构造签名。完整账号隔离后，浏览器认证态不再写入工作区级 browser_state；
     * 持久化只允许经 site_sessions 按具体账号配置写入。
     *
     * <p>置 {@code false} 时 {@link #saveCookies()} 变为空操作，用于与主浏览器并行运行的
     * 隔离浏览器（循环、定时、SDD）。这些浏览器只会按明确选中的 site_sessions 账号恢复，
     * 不继承任何聊天会话。</p>
     */
    private final boolean persistCookies;

    public PlaywrightBrowserManager(boolean headless, Path browserDir, Path screenshotDir) {
        this(headless, browserDir, screenshotDir, true);
    }

    public PlaywrightBrowserManager(boolean headless, Path browserDir, Path screenshotDir,
                                    boolean persistCookies) {
        this.headless = headless;
        this.browserDir = browserDir;
        this.screenshotDir = screenshotDir;
        this.persistCookies = persistCookies;
    }

    /**
     * 确保浏览器已启动（懒加载）。
     * 首次调用时启动 Playwright 和 Chromium，后续调用直接返回。
     */
    public synchronized void ensureLaunched() {
        activateRequestedScopeIfNeeded();
        if (browser != null && browser.isConnected() && context != null) {
            return;
        }
        if (browser != null && browser.isConnected()) {
            openContext(snapshotFor(activeScopeId));
        } else {
            launch();
        }
    }

    /**
     * 启动 Playwright 和浏览器实例
     */
    private void launch() {
        launch(headless, snapshotFor(activeScopeId));
    }

    /**
     * 以指定显示模式和会话状态启动浏览器。
     *
     * @param launchHeadless       本次是否无头；不改变构造时配置的默认模式
     * @param snapshotOverride 可选的当前会话内存快照；为空时创建全新空白 Context
     */
    private void launch(boolean launchHeadless, ScopeSnapshot snapshotOverride) {

        log.info("正在启动 Playwright 浏览器（headless={}）...", launchHeadless);

        try {
            this.playwright = Playwright.create();

            // 检测系统默认浏览器并启动（无需下载 Chromium）
            String channel = detectDefaultBrowserChannel();
            log.info("检测到系统默认浏览器 channel: {}", channel);

            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(launchHeadless)
                    .setArgs(List.of(
                            "--disable-blink-features=AutomationControlled",
                            "--no-first-run",
                            "--no-default-browser-check"
                    ));
            if (channel != null) {
                launchOptions.setChannel(channel);
            }
            this.browser = playwright.chromium().launch(launchOptions);

            this.currentHeadless = launchHeadless;
            openContext(snapshotOverride);

            log.info("Playwright 浏览器已启动，初始 Tab 已创建");
        } catch (Exception e) {
            // 启动失败时清理已分配的资源，防止浏览器进程泄漏
            log.error("Playwright 浏览器启动失败，正在清理资源", e);
            if (context != null) { try { context.close(); } catch (Exception ignored) {} context = null; }
            if (browser != null) { try { browser.close(); } catch (Exception ignored) {} browser = null; }
            if (playwright != null) { try { playwright.close(); } catch (Exception ignored) {} playwright = null; }
            throw e;
        }
    }

    private BrowserContext createContext(String storageState) {
        Browser.NewContextOptions options = newContextOptions();
        if (storageState != null && !storageState.isBlank()) {
            options.setStorageState(storageState);
        }
        try {
            return browser.newContext(options);
        } catch (PlaywrightException e) {
            if (storageState == null || storageState.isBlank()) throw e;
            log.warn("恢复浏览器 storageState 失败，将使用空白会话启动: {}", e.getMessage());
            return browser.newContext(newContextOptions());
        }
    }

    private void openContext(ScopeSnapshot snapshot) {
        String storageState = snapshot == null ? null : snapshot.storageState();
        this.context = createContext(storageState);
        context.setDefaultNavigationTimeout(DEFAULT_NAVIGATION_TIMEOUT);
        context.setDefaultTimeout(DEFAULT_ACTION_TIMEOUT);
        pages.clear();
        pages.add(context.newPage());
        activePageIndex = 0;
        transientPersistenceBaseline = snapshot == null ? null : snapshot.transientBaseline();
        userInteractionActive = false;
        userInteractionBaselineState = null;
        log.info("浏览器 Context 已激活: scope={}", activeScopeId);
    }

    private Browser.NewContextOptions newContextOptions() {
        return new Browser.NewContextOptions()
                .setViewportSize(DEFAULT_VIEWPORT_WIDTH, DEFAULT_VIEWPORT_HEIGHT)
                .setLocale("zh-CN")
                .setTimezoneId("Asia/Shanghai")
                .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
    }

    /**
     * 获取当前活跃页面（自动懒启动浏览器）
     */
    public synchronized Page getActivePage() {
        ensureLaunched();
        syncContextPages();
        if (pages.isEmpty()) {
            return null;
        }
        if (activePageIndex >= pages.size()) {
            activePageIndex = pages.size() - 1;
        }
        return pages.get(activePageIndex);
    }

    /**
     * 临时把无头浏览器切换为可见窗口，并在完整继承当前会话的前提下打开登录页。
     * 用户可直接在该 Playwright 窗口中完成 SSO、验证码或双因素认证。
     */
    public synchronized Page showPageForUser(String url) {
        ensureLaunched();
        String normalizedUrl = normalizeUrl(url);
        if (!userInteractionActive) {
            userInteractionBaselineState = context.storageState();
        }

        if (currentHeadless) {
            String state = context.storageState();
            String interactionBaseline = userInteractionBaselineState;
            closeBrowserResources(false);
            try {
                launch(false, new ScopeSnapshot(state, transientPersistenceBaseline));
                userInteractionBaselineState = interactionBaseline;
            } catch (RuntimeException visibleLaunchFailure) {
                try {
                    launch(headless, new ScopeSnapshot(state, transientPersistenceBaseline));
                    userInteractionBaselineState = interactionBaseline;
                } catch (RuntimeException restoreFailure) {
                    visibleLaunchFailure.addSuppressed(restoreFailure);
                }
                throw visibleLaunchFailure;
            }
        }

        userInteractionActive = true;
        Page page = getActivePage();
        if (page == null) {
            throw new PlaywrightException("无法创建交互式登录页面");
        }
        page.navigate(normalizedUrl, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.bringToFront();
        return page;
    }

    /**
     * 结束用户操作阶段。若管理器默认无头，则把用户刚取得的完整会话迁回新的无头 Context，
     * 并继续停留在登录完成后的页面；这样“不保存站点”只影响下次使用，不影响当前任务。
     *
     * @param persistSession 用户是否明确同意把本次登录态用于后续任务
     */
    public synchronized Page resumeAfterUserInteraction(boolean persistSession) {
        if (!userInteractionActive) {
            return getActivePage();
        }

        syncContextPages();
        Page visiblePage = activeOpenPage();
        String returnUrl = visiblePage == null ? "about:blank" : visiblePage.url();
        String state = context == null ? null : context.storageState();
        userInteractionActive = false;
        if (!persistSession && transientPersistenceBaseline == null) {
            transientPersistenceBaseline = userInteractionBaselineState;
        }
        userInteractionBaselineState = null;

        if (!headless || currentHeadless) {
            return visiblePage;
        }

        closeBrowserResources(false);
        launch(true, new ScopeSnapshot(state, transientPersistenceBaseline));
        Page page = getActivePage();
        if (page != null && returnUrl != null && !returnUrl.isBlank()
                && !"about:blank".equals(returnUrl)) {
            page.navigate(returnUrl, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        }
        return page;
    }

    /**
     * 标记当前 Context 新增的登录态只在本次任务内有效。用于无需切换可见浏览器的传统表单登录。
     */
    public synchronized void keepSessionTransientUntilTaskReset(String baselineState) {
        if (transientPersistenceBaseline == null
                && baselineState != null && !baselineState.isBlank()) {
            transientPersistenceBaseline = baselineState;
        }
    }

    /** 同步用户在可见浏览器里自行打开/关闭的 Tab（包括 SSO 弹窗）。 */
    private void syncContextPages() {
        pages.removeIf(page -> page == null || page.isClosed());
        if (context == null) return;
        for (Page page : context.pages()) {
            if (!page.isClosed() && !pages.contains(page)) {
                pages.add(page);
                activePageIndex = pages.size() - 1;
            }
        }
        if (!pages.isEmpty() && activePageIndex >= pages.size()) {
            activePageIndex = pages.size() - 1;
        }
    }

    private Page activeOpenPage() {
        if (pages.isEmpty()) return null;
        if (activePageIndex < 0 || activePageIndex >= pages.size()) {
            activePageIndex = pages.size() - 1;
        }
        Page active = pages.get(activePageIndex);
        if (!active.isClosed()) return active;
        for (int i = pages.size() - 1; i >= 0; i--) {
            if (!pages.get(i).isClosed()) {
                activePageIndex = i;
                return pages.get(i);
            }
        }
        return null;
    }

    // ==================== Tab 管理 ====================

    /**
     * 新建 Tab 并切换到该 Tab
     *
     * @param url 可选的初始 URL，为 null 则打开空白页
     * @return 新 Tab 的索引
     */
    public synchronized int newTab(String url) {
        ensureLaunched();
        Page newPage = context.newPage();
        pages.add(newPage);
        activePageIndex = pages.size() - 1;

        if (url != null && !url.isBlank()) {
            newPage.navigate(normalizeUrl(url));
        }

        log.info("新建 Tab[{}]，共 {} 个 Tab", activePageIndex, pages.size());
        return activePageIndex;
    }

    /**
     * 关闭指定 Tab
     *
     * @param index Tab 索引，-1 表示关闭当前 Tab
     * @return 是否成功关闭
     */
    public synchronized boolean closeTab(int index) {
        int targetIndex = (index == -1) ? activePageIndex : index;

        if (targetIndex < 0 || targetIndex >= pages.size()) {
            return false;
        }

        // 至少保留一个 Tab
        if (pages.size() <= 1) {
            log.warn("无法关闭最后一个 Tab");
            return false;
        }

        Page page = pages.remove(targetIndex);
        page.close();

        // 调整活跃 Tab 索引
        if (activePageIndex >= pages.size()) {
            activePageIndex = pages.size() - 1;
        }

        log.info("已关闭 Tab[{}]，剩余 {} 个 Tab，当前活跃 Tab[{}]",
                targetIndex, pages.size(), activePageIndex);
        return true;
    }

    /**
     * 切换到指定 Tab
     *
     * @param index Tab 索引
     * @return 是否成功切换
     */
    public synchronized boolean switchTab(int index) {
        if (index < 0 || index >= pages.size()) {
            return false;
        }
        activePageIndex = index;
        pages.get(activePageIndex).bringToFront();
        log.info("已切换到 Tab[{}]", index);
        return true;
    }

    /**
     * 列出所有 Tab 信息
     *
     * @return Tab 信息列表（索引、标题、URL）
     */
    public synchronized List<String> listTabs() {
        syncContextPages();
        List<String> tabInfos = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            String marker = (i == activePageIndex) ? " [当前]" : "";
            tabInfos.add(String.format("[%d]%s 标题: %s | URL: %s",
                    i, marker, page.title(), page.url()));
        }
        return tabInfos;
    }

    // ==================== Cookie 管理 ====================

    /**
     * 获取所有 Cookie
     */
    public synchronized List<Cookie> getCookies() {
        ensureLaunched();
        return context.cookies();
    }

    /**
     * 设置 Cookie
     */
    public synchronized void setCookie(Cookie cookie) {
        ensureLaunched();
        context.addCookies(List.of(cookie));
    }

    /**
     * 清除所有 Cookie
     */
    public synchronized void clearCookies() {
        if (context == null) return;
        context.clearCookies();
        log.info("已清除所有 Cookie");
    }

    /**
     * 清除旧版工作区级浏览器认证态。
     *
     * <p>保留方法名是为了兼容应用生命周期调用点。登录持久化现在只能通过
     * {@code SiteCredentialManager.tryWriteSession(...)} 写入具体账号配置；这里不再保存
     * 当前 Context，避免任一会话成为整个工作区的隐式默认账号。</p>
     */
    public synchronized void saveCookies() {
        if (!persistCookies) return;
        try {
            try (Connection c = AppDatabase.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM browser_state WHERE workspace_id = ? AND state_key = ?")) {
                ps.setString(1, AppDatabase.currentWorkspaceId());
                ps.setString(2, STORAGE_STATE_KEY);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            log.warn("清除旧版工作区浏览器认证态失败: {}", e.getMessage());
        }
    }

    // ==================== 视口与配置 ====================

    /**
     * 设置视口大小
     */
    public synchronized void setViewport(int width, int height) {
        Page page = getActivePage();
        if (page != null) {
            page.setViewportSize(width, height);
            log.info("已设置视口大小: {}x{}", width, height);
        }
    }

    // ==================== 浏览器检测 ====================

    /**
     * 检测系统默认浏览器，返回 Playwright channel 名称。
     * 支持 macOS（通过 LaunchServices）、Linux（通过 xdg-settings）、Windows（通过注册表）。
     * 仅支持 Chromium 内核浏览器（Chrome、Edge、Chromium），不支持的浏览器返回 "chrome" 作为降级。
     *
     * @return Playwright channel 名称，如 "chrome"、"msedge"、"chromium"
     */
    private static String detectDefaultBrowserChannel() {
        if (ProjectAccessPolicy.strictIsolationEnabled()) {
            return "chrome";
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            String bundleId = null;

            if (os.contains("mac")) {
                // macOS: 从 LaunchServices 读取 https 处理程序
                Process process = new ProcessBuilder("defaults", "read",
                        System.getProperty("user.home") + "/Library/Preferences/com.apple.LaunchServices/com.apple.launchservices.secure",
                        "LSHandlers")
                        .redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes());
                process.waitFor();
                // 解析 plist 文本格式，查找 https 对应的 LSHandlerRoleAll
                String[] blocks = output.split("\\{");
                for (String block : blocks) {
                    if (block.contains("LSHandlerURLScheme") && block.contains("https")) {
                        for (String line : block.split("\n")) {
                            if (line.contains("LSHandlerRoleAll")) {
                                bundleId = line.replaceAll(".*=\\s*\"?([^\";}]+)\"?.*", "$1").trim();
                                break;
                            }
                        }
                        if (bundleId != null) break;
                    }
                }
            } else if (os.contains("linux")) {
                // Linux: xdg-settings get default-web-browser
                Process process = new ProcessBuilder("xdg-settings", "get", "default-web-browser")
                        .redirectErrorStream(true).start();
                bundleId = new String(process.getInputStream().readAllBytes()).trim().toLowerCase();
                process.waitFor();
            } else if (os.contains("win")) {
                // Windows: 从注册表读取默认浏览器
                Process process = new ProcessBuilder("reg", "query",
                        "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\https\\UserChoice",
                        "/v", "ProgId")
                        .redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes()).trim();
                process.waitFor();
                bundleId = output.toLowerCase();
            }

            if (bundleId != null) {
                bundleId = bundleId.toLowerCase();
                if (bundleId.contains("edge") || bundleId.contains("msedge")) {
                    return "msedge";
                } else if (bundleId.contains("chromium")) {
                    return "chromium";
                } else if (bundleId.contains("chrome") || bundleId.contains("google")) {
                    return "chrome";
                }
            }
        } catch (Exception e) {
            log.warn("检测系统默认浏览器失败，降级使用 chrome: {}", e.getMessage());
        }

        // 默认降级到 chrome
        return "chrome";
    }

    // ==================== URL 工具方法 ====================

    /**
     * 规范化 URL，自动补全协议前缀
     */
    public static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) return "about:blank";
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")
                && !url.startsWith("about:") && !url.startsWith("file://")) {
            url = "https://" + url;
        }
        return url;
    }

    // ==================== 会话作用域 ====================

    /**
     * 请求把后续浏览器操作切换到指定会话作用域。
     *
     * <p>此方法本身不触碰 Playwright，实际切换延迟到下一次浏览器操作所在的工作线程，
     * 避免 UI 切换聊天时直接操作由后台线程创建的浏览器对象。</p>
     */
    public synchronized void activateScope(String scopeId) {
        requestedScopeId = normalizeScopeId(scopeId);
    }

    public static String conversationScopeId(String sessionId) {
        return "conversation:" + (sessionId == null || sessionId.isBlank()
                ? "default" : sessionId.trim());
    }

    /** 当前请求使用的作用域 ID。 */
    public synchronized String getActiveScopeId() {
        return requestedScopeId;
    }

    /**
     * 释放一个会话的内存认证态。聊天会话删除时调用；持久化的站点账号会话不受影响。
     */
    public synchronized void releaseScope(String scopeId) {
        String normalized = normalizeScopeId(scopeId);
        scopeSnapshots.remove(normalized);
        boolean activeMatch = normalized.equals(activeScopeId);
        boolean requestedMatch = normalized.equals(requestedScopeId);
        if (!activeMatch && !requestedMatch) return;

        if (activeMatch) {
            closeCurrentContext();
            activeScopeId = DEFAULT_SCOPE_ID;
            // 若另一个会话已请求接管浏览器，保留该请求；下一次操作会直接打开它，
            // 不能因删除当前会话而把新会话的请求一并重置。
            if (requestedMatch) requestedScopeId = DEFAULT_SCOPE_ID;
            transientPersistenceBaseline = null;
            userInteractionBaselineState = null;
            userInteractionActive = false;
        } else {
            // 仅取消一个尚未真正激活的切换请求，当前 Context 不应被误关。
            requestedScopeId = activeScopeId;
        }
        log.info("已释放浏览器会话作用域: {}", normalized);
    }

    /**
     * 用空白 Context 替换当前会话 Context。切换同一站点账号前调用，确保旧账号的
     * Cookie、localStorage、IndexedDB、缓存和 Service Worker 不会与新账号混合。
     */
    public synchronized void replaceActiveContextWithBlank() {
        ensureLaunched();
        scopeSnapshots.remove(activeScopeId);
        closeCurrentContext();
        transientPersistenceBaseline = null;
        openContext(null);
    }

    /**
     * 创建无人值守任务专属浏览器。它不继承交互会话，也不会把临时认证态写回全局状态。
     */
    public synchronized PlaywrightBrowserManager createIsolated(String scopeId) {
        String normalized = normalizeScopeId(scopeId);
        Path isolatedDir = browserDir == null ? null
                : browserDir.resolve("isolated").resolve(Integer.toHexString(normalized.hashCode()));
        PlaywrightBrowserManager isolated = new PlaywrightBrowserManager(
                true, isolatedDir, screenshotDir, false);
        isolated.activateScope(normalized);
        return isolated;
    }

    private void activateRequestedScopeIfNeeded() {
        if (requestedScopeId.equals(activeScopeId)) return;
        if (userInteractionActive) {
            throw new IllegalStateException("用户正在浏览器中登录，暂不能切换聊天会话");
        }

        snapshotActiveScope();
        closeCurrentContext();
        activeScopeId = requestedScopeId;
        ScopeSnapshot snapshot = snapshotFor(activeScopeId);
        transientPersistenceBaseline = snapshot == null ? null : snapshot.transientBaseline();
        if (browser != null && browser.isConnected()) {
            openContext(snapshot);
        }
        log.info("浏览器已切换会话作用域: {}", activeScopeId);
    }

    private void snapshotActiveScope() {
        if (context == null || activeScopeId == null) return;
        try {
            scopeSnapshots.put(activeScopeId,
                    new ScopeSnapshot(context.storageState(), transientPersistenceBaseline));
        } catch (Exception e) {
            log.warn("保存浏览器会话内存快照失败 scope={}: {}", activeScopeId, e.getMessage());
        }
    }

    private ScopeSnapshot snapshotFor(String scopeId) {
        return scopeSnapshots.get(normalizeScopeId(scopeId));
    }

    private static String normalizeScopeId(String scopeId) {
        return scopeId == null || scopeId.isBlank() ? DEFAULT_SCOPE_ID : scopeId.trim();
    }

    private record ScopeSnapshot(String storageState, String transientBaseline) {
    }

    // ==================== 生命周期 ====================

    /**
     * 获取截图保存目录
     */
    public Path getScreenshotDir() {
        return screenshotDir;
    }

    /**
     * 获取浏览器是否已启动
     */
    public synchronized boolean isRunning() {
        return browser != null && browser.isConnected();
    }

    /**
     * 切换浏览器所属工作区。
     *
     * <p>切换时关闭浏览器并清空全部会话内存快照。下一次使用从空白 Context 开始，
     * 只有明确绑定的站点账号可从 site_sessions 恢复。</p>
     */
    public synchronized void rebindWorkspace(Path newBrowserDir, Path newScreenshotDir) {
        closeBrowserResources(false);
        scopeSnapshots.clear();
        activeScopeId = DEFAULT_SCOPE_ID;
        requestedScopeId = DEFAULT_SCOPE_ID;
        userInteractionActive = false;
        userInteractionBaselineState = null;
        transientPersistenceBaseline = null;
        this.browserDir = newBrowserDir;
        this.screenshotDir = newScreenshotDir;
        log.info("浏览器已绑定新工作区路径: browser={}, screenshots={}",
                newBrowserDir, newScreenshotDir);
    }

    /**
     * 任务结束后重置浏览器状态 — 关闭多余 Tab、导航到空白页、保存 Cookie
     *
     * <p>不关闭浏览器本身（保留进程复用），仅清理任务产生的 Tab 和页面状态。</p>
     */
    public synchronized void resetAfterTask() {
        if (browser == null || !browser.isConnected()) {
            return;
        }

        log.info("正在重置浏览器状态（任务结束清理）...");

        // 用户拒绝保存的登录态只服务当前任务：任务结束先回到登录前基线，再执行常规清理/持久化。
        if (transientPersistenceBaseline != null) {
            String baseline = transientPersistenceBaseline;
            transientPersistenceBaseline = null;
            closeBrowserResources(false);
            launch(headless, new ScopeSnapshot(baseline, null));
        }

        // 关闭多余 Tab，只保留第一个
        while (pages.size() > 1) {
            Page extra = pages.remove(pages.size() - 1);
            try {
                extra.close();
            } catch (Exception ignored) {
            }
        }
        activePageIndex = 0;

        // 将剩余 Tab 导航到空白页
        if (!pages.isEmpty()) {
            try {
                pages.get(0).navigate("about:blank");
            } catch (Exception ignored) {
            }
        }

        // 只清除旧版全局认证态；站点账号状态由 site_sessions 独立持久化
        saveCookies();

        log.info("浏览器状态已重置");
    }

    /**
     * 关闭浏览器和 Playwright，释放所有资源
     */
    public synchronized void shutdown() {
        log.info("正在关闭 Playwright 浏览器...");

        closeBrowserResources(true);
        scopeSnapshots.clear();
        activeScopeId = DEFAULT_SCOPE_ID;
        requestedScopeId = DEFAULT_SCOPE_ID;

        log.info("Playwright 浏览器已关闭");
    }

    /** 释放当前 Playwright 对象；工作区切换时可禁止把旧上下文保存到新的 workspace id。 */
    private void closeBrowserResources(boolean saveState) {

        // 保存 Cookie
        if (saveState) saveCookies();

        closeCurrentContext();

        // 关闭浏览器
        if (browser != null) {
            try {
                browser.close();
            } catch (Exception ignored) {
            }
            browser = null;
        }

        // 关闭 Playwright
        if (playwright != null) {
            try {
                playwright.close();
            } catch (Exception ignored) {
            }
            playwright = null;
        }

    }

    /** 只关闭当前 Context 和页面，保留 Chromium/Playwright 进程供下个会话复用。 */
    private void closeCurrentContext() {
        for (Page page : pages) {
            try {
                page.close();
            } catch (Exception ignored) {
            }
        }
        pages.clear();

        // 关闭上下文
        if (context != null) {
            try {
                context.close();
            } catch (Exception ignored) {
            }
            context = null;
        }
        activePageIndex = 0;
        userInteractionActive = false;
        userInteractionBaselineState = null;
    }
}
