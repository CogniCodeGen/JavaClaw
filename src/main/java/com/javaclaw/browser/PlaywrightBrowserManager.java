package com.javaclaw.browser;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    /** 浏览器状态目录（Cookie 持久化等） */
    private Path browserDir;

    /** 截图保存目录 */
    private Path screenshotDir;

    /** 是否无头模式 */
    private final boolean headless;

    public PlaywrightBrowserManager(boolean headless, Path browserDir, Path screenshotDir) {
        this.headless = headless;
        this.browserDir = browserDir;
        this.screenshotDir = screenshotDir;
    }

    /**
     * 确保浏览器已启动（懒加载）。
     * 首次调用时启动 Playwright 和 Chromium，后续调用直接返回。
     */
    public synchronized void ensureLaunched() {
        if (browser != null && browser.isConnected()) {
            return;
        }
        launch();
    }

    /**
     * 启动 Playwright 和浏览器实例
     */
    private void launch() {

        log.info("正在启动 Playwright 浏览器（headless={}）...", headless);

        try {
            this.playwright = Playwright.create();

            // 检测系统默认浏览器并启动（无需下载 Chromium）
            String channel = detectDefaultBrowserChannel();
            log.info("检测到系统默认浏览器 channel: {}", channel);

            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(headless)
                    .setArgs(List.of(
                            "--disable-blink-features=AutomationControlled",
                            "--no-first-run",
                            "--no-default-browser-check"
                    ));
            if (channel != null) {
                launchOptions.setChannel(channel);
            }
            this.browser = playwright.chromium().launch(launchOptions);

            // 创建浏览器上下文（带持久化存储路径）
            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                    .setViewportSize(DEFAULT_VIEWPORT_WIDTH, DEFAULT_VIEWPORT_HEIGHT)
                    .setLocale("zh-CN")
                    .setTimezoneId("Asia/Shanghai")
                    .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            this.context = browser.newContext(contextOptions);

            // 设置默认超时
            context.setDefaultNavigationTimeout(DEFAULT_NAVIGATION_TIMEOUT);
            context.setDefaultTimeout(DEFAULT_ACTION_TIMEOUT);

            // 加载已保存的 Cookie
            loadCookies();

            // 创建初始 Tab
            Page initialPage = context.newPage();
            pages.add(initialPage);
            activePageIndex = 0;

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

    /**
     * 获取当前活跃页面（自动懒启动浏览器）
     */
    public synchronized Page getActivePage() {
        ensureLaunched();
        if (pages.isEmpty()) {
            return null;
        }
        if (activePageIndex >= pages.size()) {
            activePageIndex = pages.size() - 1;
        }
        return pages.get(activePageIndex);
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
        return context.cookies();
    }

    /**
     * 设置 Cookie
     */
    public synchronized void setCookie(Cookie cookie) {
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
     * 保存 Cookie 到磁盘
     */
    public synchronized void saveCookies() {
        if (context == null || browserDir == null || browser == null || !browser.isConnected()) return;
        try {
            Path cookiePath = browserDir.resolve("pw-cookies.json");
            List<Cookie> cookies = context.cookies();
            // 使用 Playwright 的 storageState 保存完整状态
            String state = context.storageState();
            java.nio.file.Files.writeString(cookiePath, state);
            log.info("已保存 {} 个 Cookie 到 {}", cookies.size(), cookiePath);
        } catch (Exception e) {
            log.error("保存 Cookie 失败", e);
        }
    }

    /**
     * 从磁盘加载 Cookie
     */
    private void loadCookies() {
        if (browserDir == null) return;
        Path cookiePath = browserDir.resolve("pw-cookies.json");
        if (!java.nio.file.Files.exists(cookiePath)) return;

        try {
            String state = java.nio.file.Files.readString(cookiePath);
            // 解析 storageState JSON 并恢复 Cookie
            // storageState 格式: {"cookies":[...], "origins":[...]}
            // 这里使用简单方式：关闭当前 context 并用 storageState 重新创建
            // 但由于 context 已创建，使用 addCookies 方式恢复
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(state);
            com.fasterxml.jackson.databind.JsonNode cookiesNode = root.get("cookies");
            if (cookiesNode != null && cookiesNode.isArray()) {
                List<Cookie> cookies = new ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode node : cookiesNode) {
                    Cookie cookie = new Cookie(
                            node.get("name").asText(),
                            node.get("value").asText()
                    );
                    cookie.setDomain(node.get("domain").asText());
                    cookie.setPath(node.get("path").asText());
                    if (node.has("expires") && node.get("expires").asDouble() > 0) {
                        cookie.setExpires(node.get("expires").asDouble());
                    }
                    cookie.setHttpOnly(node.has("httpOnly") && node.get("httpOnly").asBoolean());
                    cookie.setSecure(node.has("secure") && node.get("secure").asBoolean());
                    if (node.has("sameSite")) {
                        String ss = node.get("sameSite").asText();
                        try {
                            cookie.setSameSite(SameSiteAttribute.valueOf(ss.toUpperCase()));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    cookies.add(cookie);
                }
                if (!cookies.isEmpty()) {
                    context.addCookies(cookies);
                    log.info("已恢复 {} 个 Cookie", cookies.size());
                }
            }
        } catch (Exception e) {
            log.warn("加载 Cookie 失败: {}", e.getMessage());
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
     * 任务结束后重置浏览器状态 — 关闭多余 Tab、导航到空白页、保存 Cookie
     *
     * <p>不关闭浏览器本身（保留进程复用），仅清理任务产生的 Tab 和页面状态。</p>
     */
    public synchronized void resetAfterTask() {
        if (browser == null || !browser.isConnected()) {
            return;
        }

        log.info("正在重置浏览器状态（任务结束清理）...");

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

        // 保存 Cookie
        saveCookies();

        log.info("浏览器状态已重置");
    }

    /**
     * 关闭浏览器和 Playwright，释放所有资源
     */
    public synchronized void shutdown() {
        log.info("正在关闭 Playwright 浏览器...");

        // 保存 Cookie
        saveCookies();

        // 关闭所有页面
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

        log.info("Playwright 浏览器已关闭");
    }
}
