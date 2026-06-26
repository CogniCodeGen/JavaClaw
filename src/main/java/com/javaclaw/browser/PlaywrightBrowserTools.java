package com.javaclaw.browser;

import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.site.SiteCredential;
import com.javaclaw.site.SiteCredentialManager;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Playwright 浏览器工具类 — 基于原生 Chromium 的完整浏览器交互工具集
 *
 * <p>参考 agent-browser 项目功能，使用 Playwright Java 实现，为 Web 智能体提供
 * 全面的浏览器操作能力。核心采用 snapshot-ref 工作流：先获取页面快照获得元素引用，
 * 再通过引用精确操作元素。</p>
 *
 * <p>工具分类：
 * <ul>
 *   <li>导航：navigate, back, forward, reload</li>
 *   <li>快照与截图：snapshot, screenshot</li>
 *   <li>元素交互：click, type, fill, select, check, hover</li>
 *   <li>键盘操作：press_key, keyboard_type</li>
 *   <li>滚动：scroll, scroll_to_element</li>
 *   <li>信息获取：get_text, get_url, get_title, get_attribute</li>
 *   <li>状态检查：is_visible, is_enabled</li>
 *   <li>等待：wait_for_element, wait_for_text, wait_for_url</li>
 *   <li>Tab 管理：tab_new, tab_list, tab_close, tab_switch</li>
 *   <li>JavaScript 执行：eval_js</li>
 *   <li>Cookie 管理：cookie_get, cookie_set, cookie_clear</li>
 *   <li>PDF 生成：save_pdf</li>
 * </ul>
 * </p>
 *
 * @author JavaClaw
 */
public class PlaywrightBrowserTools {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightBrowserTools.class);

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final PlaywrightBrowserManager browserManager;
    private final SnapshotManager snapshotManager;

    public PlaywrightBrowserTools(PlaywrightBrowserManager browserManager) {
        this.browserManager = browserManager;
        this.snapshotManager = new SnapshotManager();
    }

    /**
     * 获取浏览器管理器实例（用于任务结束后资源清理）
     */
    public PlaywrightBrowserManager getBrowserManager() {
        return browserManager;
    }

    // ==================== 导航工具 ====================

    @Tool(name = "web_navigate", description = "导航到指定 URL 地址。自动补全 https:// 前缀。导航后建议使用 web_snapshot 获取页面元素。" +
            "若该站点已在「站点管理」中登记，会自动恢复已保存的登录会话；尚未登录时会在响应中提示可调用 site_login_now。")
    public String navigate(
            @ToolParam(name = "url", description = "目标 URL 地址，例如 www.baidu.com 或 https://github.com") String url) {
        log.debug("工具调用: web_navigate({})", url);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_navigate", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_navigate", "导航到: " + url)) {
                return ToolResponse.error("web_navigate", "用户取消了操作");
            }

            String normalizedUrl = PlaywrightBrowserManager.normalizeUrl(url);

            // 站点匹配：导航前若有已存储会话 → 注入到 BrowserContext，避免再次登录
            SiteCredential site = SiteCredentialManager.getInstance().findByUrl(normalizedUrl);
            String sessionRestoreNote = "";
            if (site != null) {
                String storage = SiteCredentialManager.getInstance().readSession(site.getId());
                if (storage != null && !storage.isBlank()) {
                    if (restoreSessionToContext(page.context(), storage)) {
                        SiteCredentialManager.getInstance().touchUsage(site.getId());
                        sessionRestoreNote = "\n[站点] 已恢复 " + site.getName() + " 的已保存会话";
                    }
                }
            }

            Response response = page.navigate(normalizedUrl, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            int status = (response != null) ? response.status() : 0;
            String title = page.title();

            // 导航后自动清除旧引用
            snapshotManager.clearRefs();

            // 若站点已登记但未恢复出会话 → 提示 LLM 可主动登录
            String credentialHint = "";
            if (site != null && sessionRestoreNote.isEmpty()) {
                credentialHint = String.format(
                        "\n[站点] 此站点已登记凭据 (用户名: %s)，无可用会话。如页面要求登录，可调用 site_login_now 自动登录；登录成功后可调用 site_save_session 持久化会话。",
                        safeUsername(site.getUsername()));
            }

            return ToolResponse.success("web_navigate",
                    String.format("已导航到: %s\n标题: %s\nHTTP状态: %d%s%s\n提示: 使用 web_snapshot 获取页面可交互元素",
                            normalizedUrl, title, status, sessionRestoreNote, credentialHint));
        } catch (PlaywrightException e) {
            log.error("web_navigate 执行异常", e);
            return ToolResponse.error("web_navigate", "导航失败: " + e.getMessage());
        }
    }

    // ==================== 站点管理工具 ====================

    @Tool(name = "site_login_now",
          description = "在当前页面用「站点管理」中已登记的凭据自动填充并提交登录表单。" +
                        "无需指定用户名/密码：工具内部根据当前页面 URL 匹配到站点条目后直接填入。" +
                        "支持可选选择器覆盖默认启发式（用户名/密码/提交按钮）。登录成功会自动保存会话。")
    public String siteLoginNow(
            @ToolParam(name = "username_selector",
                    description = "用户名输入框的 CSS 选择器；留空则按常见命名启发式查找") String usernameSelector,
            @ToolParam(name = "password_selector",
                    description = "密码输入框的 CSS 选择器；留空则按 input[type=password] 自动定位") String passwordSelector,
            @ToolParam(name = "submit_selector",
                    description = "提交按钮的 CSS 选择器；留空则尝试 button[type=submit] / 含登录文案的按钮") String submitSelector) {
        log.debug("工具调用: site_login_now");
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("site_login_now", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("site_login_now",
                    "在当前页面 [" + page.url() + "] 用已登记凭据自动登录")) {
                return ToolResponse.error("site_login_now", "用户取消了操作");
            }

            String currentUrl = page.url();
            SiteCredential site = SiteCredentialManager.getInstance().findByUrl(currentUrl);
            if (site == null) {
                return ToolResponse.error("site_login_now",
                        "当前 URL " + currentUrl + " 未匹配任何站点凭据。请在「设置 → 站点管理」中登记。");
            }

            // 1) 用户名
            String userSel = (usernameSelector != null && !usernameSelector.isBlank())
                    ? usernameSelector : findUsernameSelector(page);
            if (userSel == null) {
                return ToolResponse.error("site_login_now",
                        "未找到用户名输入框。请通过 web_snapshot 查看后用 username_selector 参数指定。");
            }
            page.fill(userSel, site.getUsername());

            // 2) 密码（直接由本工具读取，不进入 LLM 上下文）
            String passSel = (passwordSelector != null && !passwordSelector.isBlank())
                    ? passwordSelector : "input[type='password']";
            page.fill(passSel, site.getPassword());

            // 3) 提交
            String submitSel = (submitSelector != null && !submitSelector.isBlank())
                    ? submitSelector : findSubmitSelector(page);
            String beforeUrl = page.url();
            if (submitSel != null) {
                page.click(submitSel);
            } else {
                page.locator(passSel).press("Enter");
            }

            // 4) 等待导航或表单消失（最多 10s）
            try {
                page.waitForURL(u -> !u.equals(beforeUrl),
                        new Page.WaitForURLOptions().setTimeout(10_000));
            } catch (PlaywrightException ignored) {
                // 有些 SPA 不变更 URL，仍可能登录成功；继续走会话校验
            }

            // 5) 自动保存会话
            String storageState = page.context().storageState();
            SiteCredentialManager.getInstance().writeSession(site.getId(), storageState);

            snapshotManager.clearRefs();
            return ToolResponse.success("site_login_now",
                    String.format("已使用 %s 的凭据登录，会话已保存。当前 URL: %s",
                            site.getName(), page.url()));
        } catch (PlaywrightException e) {
            log.error("site_login_now 执行异常", e);
            return ToolResponse.error("site_login_now", "自动登录失败: " + e.getMessage());
        }
    }

    @Tool(name = "site_fill_password",
          description = "把已登记的密码填入指定输入框。用于 site_login_now 启发式无法覆盖的非常规登录表单。" +
                        "本工具不向 LLM 暴露密码，密码由站点管理器内部读取。")
    public String siteFillPassword(
            @ToolParam(name = "target_selector",
                    description = "目标密码输入框的 CSS 选择器或元素引用 @e1") String targetSelector) {
        log.debug("工具调用: site_fill_password({})", targetSelector);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("site_fill_password", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("site_fill_password",
                    "填入已登记密码到: " + targetSelector)) {
                return ToolResponse.error("site_fill_password", "用户取消了操作");
            }
            if (targetSelector == null || targetSelector.isBlank()) {
                return ToolResponse.error("site_fill_password", "target_selector 不能为空");
            }

            SiteCredential site = SiteCredentialManager.getInstance().findByUrl(page.url());
            if (site == null) {
                return ToolResponse.error("site_fill_password",
                        "当前 URL 未匹配任何站点凭据");
            }

            Locator locator = resolveTarget(page, targetSelector);
            if (locator == null) {
                return ToolResponse.error("site_fill_password",
                        "无法解析元素: " + targetSelector);
            }
            locator.fill(site.getPassword());
            return ToolResponse.success("site_fill_password",
                    "已将 " + site.getName() + " 的密码填入 " + targetSelector);
        } catch (PlaywrightException e) {
            log.error("site_fill_password 执行异常", e);
            return ToolResponse.error("site_fill_password", "填充失败: " + e.getMessage());
        }
    }

    @Tool(name = "site_save_session",
          description = "把当前浏览器会话（cookies + localStorage）保存到匹配的站点条目，" +
                        "下次访问该站点时会自动恢复，免去再次登录。")
    public String siteSaveSession() {
        log.debug("工具调用: site_save_session");
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("site_save_session", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("site_save_session",
                    "保存当前会话: " + page.url())) {
                return ToolResponse.error("site_save_session", "用户取消了操作");
            }

            SiteCredential site = SiteCredentialManager.getInstance().findByUrl(page.url());
            if (site == null) {
                return ToolResponse.error("site_save_session",
                        "当前 URL " + page.url() + " 未匹配任何站点凭据");
            }
            String storageState = page.context().storageState();
            SiteCredentialManager.getInstance().writeSession(site.getId(), storageState);
            return ToolResponse.success("site_save_session",
                    "已保存 " + site.getName() + " 的会话，下次访问该站点会自动恢复");
        } catch (Exception e) {
            log.error("site_save_session 执行异常", e);
            return ToolResponse.error("site_save_session", "保存失败: " + e.getMessage());
        }
    }

    @Tool(name = "site_clear_session",
          description = "清除匹配的站点条目的已保存会话（cookies/storage），下次访问需重新登录。" +
                        "凭据条目本身保留，仅删除其会话快照。")
    public String siteClearSession() {
        log.debug("工具调用: site_clear_session");
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("site_clear_session", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("site_clear_session",
                    "清除站点会话: " + page.url())) {
                return ToolResponse.error("site_clear_session", "用户取消了操作");
            }

            SiteCredential site = SiteCredentialManager.getInstance().findByUrl(page.url());
            if (site == null) {
                return ToolResponse.error("site_clear_session",
                        "当前 URL 未匹配任何站点凭据");
            }
            SiteCredentialManager.getInstance().clearSession(site.getId());
            return ToolResponse.success("site_clear_session",
                    "已清除 " + site.getName() + " 的会话");
        } catch (Exception e) {
            log.error("site_clear_session 执行异常", e);
            return ToolResponse.error("site_clear_session", "清除失败: " + e.getMessage());
        }
    }

    // ==================== 站点工具内部辅助 ====================

    /**
     * 把 storageState JSON 注入到当前 BrowserContext（只复用 cookies；
     * Playwright Java 不允许对已有 context 直接 setStorageState，origins 部分由站点登录自然产生）
     */
    private boolean restoreSessionToContext(BrowserContext context, String storageStateJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(storageStateJson);
            com.fasterxml.jackson.databind.JsonNode cookiesNode = root.get("cookies");
            if (cookiesNode == null || !cookiesNode.isArray() || cookiesNode.size() == 0) {
                return false;
            }
            List<Cookie> cookies = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode node : cookiesNode) {
                Cookie ck = new Cookie(
                        node.get("name").asText(),
                        node.get("value").asText());
                if (node.has("domain")) ck.setDomain(node.get("domain").asText());
                if (node.has("path")) ck.setPath(node.get("path").asText());
                if (node.has("expires") && node.get("expires").asDouble() > 0) {
                    ck.setExpires(node.get("expires").asDouble());
                }
                if (node.has("httpOnly")) ck.setHttpOnly(node.get("httpOnly").asBoolean());
                if (node.has("secure")) ck.setSecure(node.get("secure").asBoolean());
                if (node.has("sameSite")) {
                    try {
                        ck.setSameSite(SameSiteAttribute.valueOf(
                                node.get("sameSite").asText().toUpperCase()));
                    } catch (IllegalArgumentException ignored) {}
                }
                cookies.add(ck);
            }
            if (!cookies.isEmpty()) context.addCookies(cookies);
            return true;
        } catch (Exception e) {
            log.warn("注入站点会话失败", e);
            return false;
        }
    }

    /**
     * 启发式查找用户名输入框选择器；按命中优先级返回首个存在的元素的选择器
     */
    private String findUsernameSelector(Page page) {
        String[] candidates = {
                "input[autocomplete='username']",
                "input[name='username']",
                "input[name='email']",
                "input[type='email']",
                "input[id*='user' i]",
                "input[id*='email' i]",
                "input[name*='login' i]",
                "input[name*='account' i]"
        };
        for (String sel : candidates) {
            try {
                if (page.locator(sel).count() > 0) return sel;
            } catch (PlaywrightException ignored) {}
        }
        return null;
    }

    /**
     * 启发式查找登录提交按钮
     */
    private String findSubmitSelector(Page page) {
        String[] candidates = {
                "button[type='submit']",
                "input[type='submit']",
                "button:has-text('登 录')",
                "button:has-text('登录')",
                "button:has-text('Sign in')",
                "button:has-text('Log in')",
                "button:has-text('Login')"
        };
        for (String sel : candidates) {
            try {
                if (page.locator(sel).count() > 0) return sel;
            } catch (PlaywrightException ignored) {}
        }
        return null;
    }

    /** 用户名脱敏展示用（避免响应里完整泄漏） */
    private static String safeUsername(String u) {
        if (u == null || u.isBlank()) return "(未设置)";
        if (u.length() <= 3) return u.charAt(0) + "***";
        return u.substring(0, 2) + "***" + u.substring(Math.max(2, u.length() - 2));
    }

    @Tool(name = "web_go_back", description = "浏览器后退到上一页")
    public String goBack() {
        log.debug("工具调用: web_go_back()");
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_go_back", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_go_back", "浏览器后退到上一页")) {
                return ToolResponse.error("web_go_back", "用户取消了操作");
            }

            page.goBack(new Page.GoBackOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            snapshotManager.clearRefs();
            return ToolResponse.success("web_go_back", "已后退到: " + page.url());
        } catch (Exception e) {
            return ToolResponse.fromException("web_go_back", (Exception) e);
        }
    }

    @Tool(name = "web_go_forward", description = "浏览器前进到下一页")
    public String goForward() {
        log.debug("工具调用: web_go_forward()");
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_go_forward", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_go_forward", "浏览器前进到下一页")) {
                return ToolResponse.error("web_go_forward", "用户取消了操作");
            }

            page.goForward(new Page.GoForwardOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            snapshotManager.clearRefs();
            return ToolResponse.success("web_go_forward", "已前进到: " + page.url());
        } catch (Exception e) {
            return ToolResponse.fromException("web_go_forward", (Exception) e);
        }
    }

    @Tool(name = "web_reload", description = "刷新当前页面")
    public String reload() {
        log.debug("工具调用: web_reload()");
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_reload", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_reload", "刷新页面: " + page.url())) {
                return ToolResponse.error("web_reload", "用户取消了操作");
            }

            page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            snapshotManager.clearRefs();
            return ToolResponse.success("web_reload", "已刷新页面: " + page.url());
        } catch (Exception e) {
            return ToolResponse.fromException("web_reload", (Exception) e);
        }
    }

    // ==================== 快照与截图工具 ====================

    @Tool(name = "web_snapshot", description = "获取当前页面的无障碍树快照。为每个可交互元素分配引用标记（如 @e1、@e2），" +
            "后续可通过引用直接操作元素（如 web_click @e1）。这是与页面交互前的必要步骤。" +
            "参数 interactive_only 控制是否只显示可交互元素（默认 true），show_urls 控制是否显示链接 URL（默认 false）。")
    public String snapshot(
            @ToolParam(name = "interactive_only", description = "是否只显示可交互元素，默认 true") boolean interactiveOnly,
            @ToolParam(name = "show_urls", description = "是否显示链接的 URL 地址，默认 false") boolean showUrls) {
        log.debug("工具调用: web_snapshot(interactiveOnly={}, showUrls={})", interactiveOnly, showUrls);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_snapshot", "浏览器未启动");

            String result = snapshotManager.snapshot(page, interactiveOnly, showUrls, -1);
            return ToolResponse.success("web_snapshot", result);
        } catch (Exception e) {
            return ToolResponse.fromException("web_snapshot", (Exception) e);
        }
    }

    @Tool(name = "web_screenshot", description = "对当前页面进行截图并保存为 PNG 文件。" +
            "可选参数 full_page 控制是否截取整个页面（默认 false 只截取可见区域）。")
    public String screenshot(
            @ToolParam(name = "full_page", description = "是否截取整个页面（含滚动区域），默认 false") boolean fullPage) {
        log.debug("工具调用: web_screenshot(fullPage={})", fullPage);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_screenshot", "浏览器未启动");

            Path screenshotDir = browserManager.getScreenshotDir();
            if (screenshotDir == null) return ToolResponse.error("web_screenshot", "截图目录未配置");

            // 确保目录存在
            screenshotDir.toFile().mkdirs();

            String filename = "screenshot_" + TIMESTAMP_FMT.format(LocalDateTime.now()) + ".png";
            Path filePath = screenshotDir.resolve(filename);

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(filePath)
                    .setFullPage(fullPage));

            log.info("截图已保存: {}", filePath);
            return ToolResponse.success("web_screenshot", "截图已保存: " + filePath.toAbsolutePath());
        } catch (Exception e) {
            return ToolResponse.fromException("web_screenshot", (Exception) e);
        }
    }

    @Tool(name = "web_screenshot_annotated", description = "对当前页面进行标注截图：在每个可交互元素上叠加红色边框和编号标签。" +
            "标注与快照引用对应（@e1 → 标签1），方便视觉定位元素。截图保存为 PNG 文件。")
    public String screenshotAnnotated() {
        log.debug("工具调用: web_screenshot_annotated()");
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_screenshot_annotated", "浏览器未启动");

            Path screenshotDir = browserManager.getScreenshotDir();
            if (screenshotDir == null) return ToolResponse.error("web_screenshot_annotated", "截图目录未配置");
            screenshotDir.toFile().mkdirs();

            // 注入标注覆盖层
            int annotationCount = injectAnnotationOverlay(page);

            // 截图
            String filename = "annotated_" + TIMESTAMP_FMT.format(LocalDateTime.now()) + ".png";
            Path filePath = screenshotDir.resolve(filename);
            page.screenshot(new Page.ScreenshotOptions().setPath(filePath));

            // 移除标注覆盖层
            removeAnnotationOverlay(page);

            log.info("标注截图已保存: {}，标注 {} 个元素", filePath, annotationCount);
            return ToolResponse.success("web_screenshot_annotated",
                    String.format("标注截图已保存: %s\n标注了 %d 个可交互元素", filePath.toAbsolutePath(), annotationCount));
        } catch (Exception e) {
            return ToolResponse.fromException("web_screenshot_annotated", (Exception) e);
        }
    }

    // ==================== 元素交互工具 ====================

    @Tool(name = "web_click", description = "点击页面元素。通过引用（如 @e1）、CSS选择器或文本内容定位元素。" +
            "引用来自 web_snapshot 返回的元素列表。")
    public String click(
            @ToolParam(name = "target", description = "目标元素：引用（@e1）、CSS选择器（#id、.class）或文本内容") String target) {
        log.debug("工具调用: web_click({})", target);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_click", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_click", "点击元素: " + target)) {
                return ToolResponse.error("web_click", "用户取消了操作");
            }

            Locator locator = resolveTarget(page, target);
            if (locator == null) return ToolResponse.error("web_click", "未找到目标元素: " + target);

            locator.click();

            // 点击后可能页面变化，清除旧引用
            snapshotManager.clearRefs();
            return ToolResponse.success("web_click", "已点击元素: " + target);
        } catch (Exception e) {
            return ToolResponse.fromException("web_click", (Exception) e);
        }
    }

    @Tool(name = "web_dblclick", description = "双击页面元素。")
    public String doubleClick(
            @ToolParam(name = "target", description = "目标元素：引用（@e1）、CSS选择器或文本内容") String target) {
        log.debug("工具调用: web_dblclick({})", target);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_dblclick", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_dblclick", "双击元素: " + target)) {
                return ToolResponse.error("web_dblclick", "用户取消了操作");
            }

            Locator locator = resolveTarget(page, target);
            if (locator == null) return ToolResponse.error("web_dblclick", "未找到目标元素: " + target);

            locator.dblclick();
            snapshotManager.clearRefs();
            return ToolResponse.success("web_dblclick", "已双击元素: " + target);
        } catch (Exception e) {
            return ToolResponse.fromException("web_dblclick", (Exception) e);
        }
    }

    @Tool(name = "web_fill", description = "在输入框中填充文本（会先清空原有内容）。触发 input 和 change 事件。" +
            "适用于文本框、搜索框、密码框等。")
    public String fill(
            @ToolParam(name = "target", description = "目标输入框：引用（@e1）、CSS选择器或标签文本") String target,
            @ToolParam(name = "text", description = "要填充的文本内容") String text) {
        log.debug("工具调用: web_fill({}, '{}')", target, text);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_fill", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_fill",
                    "在 " + target + " 中填充文本: " + text)) {
                return ToolResponse.error("web_fill", "用户取消了操作");
            }

            Locator locator = resolveTarget(page, target);
            if (locator == null) return ToolResponse.error("web_fill", "未找到目标元素: " + target);

            locator.fill(text);
            return ToolResponse.success("web_fill", "已在 " + target + " 中填充文本: " + text);
        } catch (Exception e) {
            return ToolResponse.fromException("web_fill", (Exception) e);
        }
    }

    @Tool(name = "web_type", description = "在当前焦点元素或指定元素中逐字输入文本（模拟键盘输入，不清空原有内容）。" +
            "适用于需要逐字触发事件的场景（如搜索自动补全）。")
    public String type(
            @ToolParam(name = "target", description = "目标元素：引用（@e1）、CSS选择器或文本。传空字符串则在当前焦点元素输入") String target,
            @ToolParam(name = "text", description = "要输入的文本") String text) {
        log.debug("工具调用: web_type({}, '{}')", target, text);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_type", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_type",
                    (target != null && !target.isBlank() ? "在 " + target + " 中" : "在焦点元素") + "键入: " + text)) {
                return ToolResponse.error("web_type", "用户取消了操作");
            }

            if (target != null && !target.isBlank()) {
                Locator locator = resolveTarget(page, target);
                if (locator == null) return ToolResponse.error("web_type", "未找到目标元素: " + target);
                locator.pressSequentially(text, new Locator.PressSequentiallyOptions().setDelay(50));
            } else {
                page.keyboard().type(text, new Keyboard.TypeOptions().setDelay(50));
            }
            return ToolResponse.success("web_type", "已输入文本: " + text);
        } catch (Exception e) {
            return ToolResponse.fromException("web_type", (Exception) e);
        }
    }

    @Tool(name = "web_hover", description = "将鼠标悬停在指定元素上。可用于触发悬停菜单、提示框等。")
    public String hover(
            @ToolParam(name = "target", description = "目标元素：引用（@e1）、CSS选择器或文本") String target) {
        log.debug("工具调用: web_hover({})", target);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_hover", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_hover", "悬停元素: " + target)) {
                return ToolResponse.error("web_hover", "用户取消了操作");
            }

            Locator locator = resolveTarget(page, target);
            if (locator == null) return ToolResponse.error("web_hover", "未找到目标元素: " + target);

            locator.hover();
            return ToolResponse.success("web_hover", "已悬停在元素: " + target);
        } catch (Exception e) {
            return ToolResponse.fromException("web_hover", (Exception) e);
        }
    }

    @Tool(name = "web_select", description = "在下拉选择框中选择指定选项。可通过值、标签文本或索引选择。")
    public String select(
            @ToolParam(name = "target", description = "目标下拉框：引用（@e1）、CSS选择器") String target,
            @ToolParam(name = "value", description = "要选择的选项值或标签文本") String value) {
        log.debug("工具调用: web_select({}, '{}')", target, value);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_select", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_select",
                    "下拉选择 " + target + " = " + value)) {
                return ToolResponse.error("web_select", "用户取消了操作");
            }

            Locator locator = resolveTarget(page, target);
            if (locator == null) return ToolResponse.error("web_select", "未找到目标元素: " + target);

            // 先尝试按 value 选择，再尝试按 label 选择
            try {
                locator.selectOption(new SelectOption().setValue(value));
            } catch (PlaywrightException e1) {
                try {
                    locator.selectOption(new SelectOption().setLabel(value));
                } catch (PlaywrightException e2) {
                    locator.selectOption(value);
                }
            }
            return ToolResponse.success("web_select", "已选择选项: " + value);
        } catch (Exception e) {
            return ToolResponse.fromException("web_select", (Exception) e);
        }
    }

    @Tool(name = "web_check", description = "勾选或取消勾选复选框/开关。")
    public String check(
            @ToolParam(name = "target", description = "目标复选框：引用（@e1）、CSS选择器") String target,
            @ToolParam(name = "checked", description = "是否勾选，true 为勾选，false 为取消") boolean checked) {
        log.debug("工具调用: web_check({}, {})", target, checked);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_check", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_check",
                    (checked ? "勾选" : "取消勾选") + "复选框: " + target)) {
                return ToolResponse.error("web_check", "用户取消了操作");
            }

            Locator locator = resolveTarget(page, target);
            if (locator == null) return ToolResponse.error("web_check", "未找到目标元素: " + target);

            locator.setChecked(checked);
            return ToolResponse.success("web_check",
                    (checked ? "已勾选" : "已取消勾选") + "元素: " + target);
        } catch (Exception e) {
            return ToolResponse.fromException("web_check", (Exception) e);
        }
    }

    @Tool(name = "web_focus", description = "将焦点移到指定元素上。")
    public String focus(
            @ToolParam(name = "target", description = "目标元素：引用（@e1）、CSS选择器") String target) {
        log.debug("工具调用: web_focus({})", target);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_focus", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_focus", "聚焦元素: " + target)) {
                return ToolResponse.error("web_focus", "用户取消了操作");
            }

            Locator locator = resolveTarget(page, target);
            if (locator == null) return ToolResponse.error("web_focus", "未找到目标元素: " + target);

            locator.focus();
            return ToolResponse.success("web_focus", "已聚焦到元素: " + target);
        } catch (Exception e) {
            return ToolResponse.fromException("web_focus", (Exception) e);
        }
    }

    @Tool(name = "web_upload", description = "上传文件到文件输入框。")
    public String upload(
            @ToolParam(name = "target", description = "文件输入框：引用（@e1）、CSS选择器") String target,
            @ToolParam(name = "file_path", description = "要上传的文件路径") String filePath) {
        log.debug("工具调用: web_upload({}, {})", target, filePath);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_upload", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_upload",
                    "上传文件 " + filePath + " 到 " + target)) {
                return ToolResponse.error("web_upload", "用户取消了操作");
            }

            Locator locator = resolveTarget(page, target);
            if (locator == null) return ToolResponse.error("web_upload", "未找到目标元素: " + target);

            locator.setInputFiles(java.nio.file.Paths.get(filePath));
            return ToolResponse.success("web_upload", "已上传文件: " + filePath);
        } catch (Exception e) {
            return ToolResponse.fromException("web_upload", (Exception) e);
        }
    }

    @Tool(name = "web_drag", description = "将元素拖拽到目标位置。")
    public String drag(
            @ToolParam(name = "source", description = "源元素：引用（@e1）、CSS选择器") String source,
            @ToolParam(name = "target_element", description = "目标元素：引用（@e2）、CSS选择器") String targetElement) {
        log.debug("工具调用: web_drag({}, {})", source, targetElement);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_drag", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_drag",
                    "拖拽 " + source + " 到 " + targetElement)) {
                return ToolResponse.error("web_drag", "用户取消了操作");
            }

            Locator srcLocator = resolveTarget(page, source);
            Locator tgtLocator = resolveTarget(page, targetElement);
            if (srcLocator == null) return ToolResponse.error("web_drag", "未找到源元素: " + source);
            if (tgtLocator == null) return ToolResponse.error("web_drag", "未找到目标元素: " + targetElement);

            srcLocator.dragTo(tgtLocator);
            snapshotManager.clearRefs();
            return ToolResponse.success("web_drag", "已将 " + source + " 拖拽到 " + targetElement);
        } catch (Exception e) {
            return ToolResponse.fromException("web_drag", (Exception) e);
        }
    }

    // ==================== 键盘操作工具 ====================

    @Tool(name = "web_press_key", description = "按下键盘按键。支持特殊键（Enter、Tab、Escape、ArrowDown 等）" +
            "和组合键（Control+C、Meta+A、Shift+Tab 等）。")
    public String pressKey(
            @ToolParam(name = "key", description = "按键名称，如 Enter、Tab、Escape、ArrowDown、Control+A、Meta+C") String key) {
        log.debug("工具调用: web_press_key({})", key);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_press_key", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_press_key", "按下按键: " + key)) {
                return ToolResponse.error("web_press_key", "用户取消了操作");
            }

            page.keyboard().press(key);
            return ToolResponse.success("web_press_key", "已按下按键: " + key);
        } catch (Exception e) {
            return ToolResponse.fromException("web_press_key", (Exception) e);
        }
    }

    // ==================== 滚动工具 ====================

    @Tool(name = "web_scroll", description = "滚动页面。direction 为 up/down/left/right，amount 为像素数（默认500）。" +
            "也可指定目标元素，在该元素内滚动。")
    public String scroll(
            @ToolParam(name = "direction", description = "滚动方向：up、down、left、right") String direction,
            @ToolParam(name = "amount", description = "滚动像素数，默认 500") int amount,
            @ToolParam(name = "target", description = "可选的目标元素（在该元素内滚动），传空字符串则滚动整个页面") String target) {
        log.debug("工具调用: web_scroll({}, {}, {})", direction, amount, target);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_scroll", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_scroll",
                    "向" + direction + "滚动 " + (amount <= 0 ? 500 : amount) + " 像素")) {
                return ToolResponse.error("web_scroll", "用户取消了操作");
            }

            int pixels = amount <= 0 ? 500 : amount;
            int deltaX = 0, deltaY = 0;
            switch (direction.toLowerCase()) {
                case "down" -> deltaY = pixels;
                case "up" -> deltaY = -pixels;
                case "right" -> deltaX = pixels;
                case "left" -> deltaX = -pixels;
                default -> {
                    return ToolResponse.error("web_scroll", "无效方向: " + direction + "，支持: up/down/left/right");
                }
            }

            if (target != null && !target.isBlank()) {
                Locator locator = resolveTarget(page, target);
                if (locator != null) {
                    locator.evaluate("(el, [dx, dy]) => el.scrollBy(dx, dy)",
                            List.of(deltaX, deltaY));
                } else {
                    return ToolResponse.error("web_scroll", "未找到滚动目标元素: " + target);
                }
            } else {
                page.mouse().wheel(deltaX, deltaY);
            }

            return ToolResponse.success("web_scroll",
                    String.format("已向%s滚动 %d 像素", direction, pixels));
        } catch (Exception e) {
            return ToolResponse.fromException("web_scroll", (Exception) e);
        }
    }

    @Tool(name = "web_scroll_to_element", description = "滚动页面直到指定元素出现在可见区域内。")
    public String scrollToElement(
            @ToolParam(name = "target", description = "目标元素：引用（@e1）、CSS选择器") String target) {
        log.debug("工具调用: web_scroll_to_element({})", target);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_scroll_to_element", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_scroll_to_element",
                    "滚动到元素: " + target)) {
                return ToolResponse.error("web_scroll_to_element", "用户取消了操作");
            }

            Locator locator = resolveTarget(page, target);
            if (locator == null) return ToolResponse.error("web_scroll_to_element", "未找到目标元素: " + target);

            locator.scrollIntoViewIfNeeded();
            return ToolResponse.success("web_scroll_to_element", "已滚动到元素: " + target);
        } catch (Exception e) {
            return ToolResponse.fromException("web_scroll_to_element", (Exception) e);
        }
    }

    // ==================== 信息获取工具 ====================

    @Tool(name = "web_get_text", description = "获取指定元素的文本内容。")
    public String getText(
            @ToolParam(name = "target", description = "目标元素：引用（@e1）、CSS选择器") String target) {
        log.debug("工具调用: web_get_text({})", target);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_get_text", "浏览器未启动");

            Locator locator = resolveTarget(page, target);
            if (locator == null) return ToolResponse.error("web_get_text", "未找到目标元素: " + target);

            String text = locator.innerText();
            return ToolResponse.success("web_get_text", text);
        } catch (Exception e) {
            return ToolResponse.fromException("web_get_text", (Exception) e);
        }
    }

    @Tool(name = "web_get_html", description = "获取指定元素的 HTML 内容。")
    public String getHtml(
            @ToolParam(name = "target", description = "目标元素：引用（@e1）、CSS选择器") String target) {
        log.debug("工具调用: web_get_html({})", target);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_get_html", "浏览器未启动");

            Locator locator = resolveTarget(page, target);
            if (locator == null) return ToolResponse.error("web_get_html", "未找到目标元素: " + target);

            String html = locator.innerHTML();
            // 截断过长的 HTML
            if (html.length() > 5000) {
                html = html.substring(0, 5000) + "\n... (HTML 内容已截断，共 " + html.length() + " 字符)";
            }
            return ToolResponse.success("web_get_html", html);
        } catch (Exception e) {
            return ToolResponse.fromException("web_get_html", (Exception) e);
        }
    }

    @Tool(name = "web_get_attribute", description = "获取指定元素的属性值。")
    public String getAttribute(
            @ToolParam(name = "target", description = "目标元素：引用（@e1）、CSS选择器") String target,
            @ToolParam(name = "attribute", description = "属性名称，如 href、src、class、value 等") String attribute) {
        log.debug("工具调用: web_get_attribute({}, {})", target, attribute);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_get_attribute", "浏览器未启动");

            Locator locator = resolveTarget(page, target);
            if (locator == null) return ToolResponse.error("web_get_attribute", "未找到目标元素: " + target);

            String value = locator.getAttribute(attribute);
            return ToolResponse.success("web_get_attribute",
                    attribute + "=\"" + (value != null ? value : "(null)") + "\"");
        } catch (Exception e) {
            return ToolResponse.fromException("web_get_attribute", (Exception) e);
        }
    }

    @Tool(name = "web_get_url", description = "获取当前页面的 URL 地址")
    public String getUrl() {
        log.debug("工具调用: web_get_url()");
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_get_url", "浏览器未启动");
            return ToolResponse.success("web_get_url", page.url());
        } catch (Exception e) {
            return ToolResponse.fromException("web_get_url", (Exception) e);
        }
    }

    @Tool(name = "web_get_title", description = "获取当前页面的标题")
    public String getTitle() {
        log.debug("工具调用: web_get_title()");
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_get_title", "浏览器未启动");
            return ToolResponse.success("web_get_title", page.title());
        } catch (Exception e) {
            return ToolResponse.fromException("web_get_title", (Exception) e);
        }
    }

    @Tool(name = "web_get_value", description = "获取输入框当前的值。")
    public String getValue(
            @ToolParam(name = "target", description = "目标输入框：引用（@e1）、CSS选择器") String target) {
        log.debug("工具调用: web_get_value({})", target);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_get_value", "浏览器未启动");

            Locator locator = resolveTarget(page, target);
            if (locator == null) return ToolResponse.error("web_get_value", "未找到目标元素: " + target);

            String value = locator.inputValue();
            return ToolResponse.success("web_get_value", "值=\"" + value + "\"");
        } catch (Exception e) {
            return ToolResponse.fromException("web_get_value", (Exception) e);
        }
    }

    @Tool(name = "web_get_count", description = "获取匹配指定 CSS 选择器的元素数量。")
    public String getCount(
            @ToolParam(name = "selector", description = "CSS 选择器") String selector) {
        log.debug("工具调用: web_get_count({})", selector);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_get_count", "浏览器未启动");

            int count = page.locator(selector).count();
            return ToolResponse.success("web_get_count", "匹配元素数量: " + count);
        } catch (Exception e) {
            return ToolResponse.fromException("web_get_count", (Exception) e);
        }
    }

    // ==================== 状态检查工具 ====================

    @Tool(name = "web_is_visible", description = "检查指定元素是否可见。")
    public String isVisible(
            @ToolParam(name = "target", description = "目标元素：引用（@e1）、CSS选择器") String target) {
        log.debug("工具调用: web_is_visible({})", target);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_is_visible", "浏览器未启动");

            Locator locator = resolveTarget(page, target);
            if (locator == null) return ToolResponse.success("web_is_visible", "元素不存在，不可见");

            boolean visible = locator.isVisible();
            return ToolResponse.success("web_is_visible", target + " " + (visible ? "可见" : "不可见"));
        } catch (Exception e) {
            return ToolResponse.fromException("web_is_visible", (Exception) e);
        }
    }

    @Tool(name = "web_is_enabled", description = "检查指定元素是否启用（非 disabled 状态）。")
    public String isEnabled(
            @ToolParam(name = "target", description = "目标元素：引用（@e1）、CSS选择器") String target) {
        log.debug("工具调用: web_is_enabled({})", target);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_is_enabled", "浏览器未启动");

            Locator locator = resolveTarget(page, target);
            if (locator == null) return ToolResponse.error("web_is_enabled", "未找到目标元素: " + target);

            boolean enabled = locator.isEnabled();
            return ToolResponse.success("web_is_enabled", target + " " + (enabled ? "已启用" : "已禁用"));
        } catch (Exception e) {
            return ToolResponse.fromException("web_is_enabled", (Exception) e);
        }
    }

    @Tool(name = "web_is_checked", description = "检查复选框/单选按钮是否被选中。")
    public String isChecked(
            @ToolParam(name = "target", description = "目标元素：引用（@e1）、CSS选择器") String target) {
        log.debug("工具调用: web_is_checked({})", target);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_is_checked", "浏览器未启动");

            Locator locator = resolveTarget(page, target);
            if (locator == null) return ToolResponse.error("web_is_checked", "未找到目标元素: " + target);

            boolean checked = locator.isChecked();
            return ToolResponse.success("web_is_checked", target + " " + (checked ? "已选中" : "未选中"));
        } catch (Exception e) {
            return ToolResponse.fromException("web_is_checked", (Exception) e);
        }
    }

    // ==================== 等待工具 ====================

    @Tool(name = "web_wait_for_element", description = "等待指定元素出现在页面上。超时时间默认 10 秒。")
    public String waitForElement(
            @ToolParam(name = "selector", description = "CSS 选择器") String selector,
            @ToolParam(name = "timeout_seconds", description = "超时秒数，默认 10") int timeoutSeconds) {
        log.debug("工具调用: web_wait_for_element({}, {}s)", selector, timeoutSeconds);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_wait_for_element", "浏览器未启动");

            int timeout = timeoutSeconds <= 0 ? 10 : timeoutSeconds;
            page.locator(selector).waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(timeout * 1000.0));
            return ToolResponse.success("web_wait_for_element", "元素已出现: " + selector);
        } catch (TimeoutError e) {
            return ToolResponse.timeout("web_wait_for_element", timeoutSeconds,
                    "元素 " + selector + " 未在超时时间内出现");
        } catch (Exception e) {
            return ToolResponse.fromException("web_wait_for_element", (Exception) e);
        }
    }

    @Tool(name = "web_wait_for_text", description = "等待页面上出现指定文本。超时时间默认 10 秒。")
    public String waitForText(
            @ToolParam(name = "text", description = "要等待的文本内容") String text,
            @ToolParam(name = "timeout_seconds", description = "超时秒数，默认 10") int timeoutSeconds) {
        log.debug("工具调用: web_wait_for_text('{}', {}s)", text, timeoutSeconds);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_wait_for_text", "浏览器未启动");

            int timeout = timeoutSeconds <= 0 ? 10 : timeoutSeconds;
            page.getByText(text).first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(timeout * 1000.0));
            return ToolResponse.success("web_wait_for_text", "文本已出现: " + text);
        } catch (TimeoutError e) {
            return ToolResponse.timeout("web_wait_for_text", timeoutSeconds,
                    "文本 \"" + text + "\" 未在超时时间内出现");
        } catch (Exception e) {
            return ToolResponse.fromException("web_wait_for_text", (Exception) e);
        }
    }

    @Tool(name = "web_wait_for_url", description = "等待页面 URL 匹配指定模式。超时时间默认 10 秒。")
    public String waitForUrl(
            @ToolParam(name = "url_pattern", description = "URL 匹配模式（支持通配符 *）") String urlPattern,
            @ToolParam(name = "timeout_seconds", description = "超时秒数，默认 10") int timeoutSeconds) {
        log.debug("工具调用: web_wait_for_url('{}', {}s)", urlPattern, timeoutSeconds);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_wait_for_url", "浏览器未启动");

            int timeout = timeoutSeconds <= 0 ? 10 : timeoutSeconds;
            // 将通配符模式转换为正则
            String regex = urlPattern.replace("*", ".*");
            page.waitForURL(java.util.regex.Pattern.compile(regex),
                    new Page.WaitForURLOptions().setTimeout(timeout * 1000.0));
            return ToolResponse.success("web_wait_for_url", "URL 已匹配: " + page.url());
        } catch (TimeoutError e) {
            return ToolResponse.timeout("web_wait_for_url", timeoutSeconds,
                    "URL 未匹配模式: " + urlPattern);
        } catch (Exception e) {
            return ToolResponse.fromException("web_wait_for_url", (Exception) e);
        }
    }

    @Tool(name = "web_wait_for_load", description = "等待页面加载到指定状态。" +
            "状态可选：load（完全加载）、domcontentloaded（DOM解析完成）、networkidle（网络空闲）。")
    public String waitForLoad(
            @ToolParam(name = "state", description = "加载状态：load、domcontentloaded、networkidle") String state) {
        log.debug("工具调用: web_wait_for_load({})", state);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_wait_for_load", "浏览器未启动");

            LoadState loadState = switch (state.toLowerCase()) {
                case "load" -> LoadState.LOAD;
                case "domcontentloaded" -> LoadState.DOMCONTENTLOADED;
                case "networkidle" -> LoadState.NETWORKIDLE;
                default -> {
                    yield LoadState.LOAD;
                }
            };
            page.waitForLoadState(loadState);
            return ToolResponse.success("web_wait_for_load", "页面已达到加载状态: " + state);
        } catch (Exception e) {
            return ToolResponse.fromException("web_wait_for_load", (Exception) e);
        }
    }

    // ==================== Tab 管理工具 ====================

    @Tool(name = "web_tab_new", description = "新建浏览器 Tab 页。可选指定初始 URL。")
    public String tabNew(
            @ToolParam(name = "url", description = "初始 URL，传空字符串打开空白页") String url) {
        log.debug("工具调用: web_tab_new({})", url);
        try {
            if (!ToolConfirmationManager.requestConfirmation("web_tab_new",
                    "新建浏览器 Tab" + (url != null && !url.isBlank() ? ": " + url : ""))) {
                return ToolResponse.error("web_tab_new", "用户取消了操作");
            }
            int index = browserManager.newTab(url);
            snapshotManager.clearRefs();
            return ToolResponse.success("web_tab_new",
                    "已创建新 Tab[" + index + "]" + (url != null && !url.isBlank() ? "，已导航到: " + url : ""));
        } catch (Exception e) {
            return ToolResponse.fromException("web_tab_new", (Exception) e);
        }
    }

    @Tool(name = "web_tab_list", description = "列出所有打开的 Tab 页信息（索引、标题、URL）。")
    public String tabList() {
        log.debug("工具调用: web_tab_list()");
        try {
            List<String> tabs = browserManager.listTabs();
            String result = String.join("\n", tabs);
            return ToolResponse.success("web_tab_list", "共 " + tabs.size() + " 个 Tab:\n" + result);
        } catch (Exception e) {
            return ToolResponse.fromException("web_tab_list", (Exception) e);
        }
    }

    @Tool(name = "web_tab_close", description = "关闭指定 Tab 页。传 -1 关闭当前 Tab。至少保留一个 Tab。")
    public String tabClose(
            @ToolParam(name = "index", description = "Tab 索引，-1 表示关闭当前 Tab") int index) {
        log.debug("工具调用: web_tab_close({})", index);
        try {
            if (!ToolConfirmationManager.requestConfirmation("web_tab_close",
                    "关闭浏览器 Tab " + (index == -1 ? "(当前)" : "索引 " + index))) {
                return ToolResponse.error("web_tab_close", "用户取消了操作");
            }
            boolean success = browserManager.closeTab(index);
            if (success) {
                snapshotManager.clearRefs();
                return ToolResponse.success("web_tab_close", "已关闭 Tab[" + index + "]");
            } else {
                return ToolResponse.error("web_tab_close", "关闭 Tab 失败（索引无效或只剩一个 Tab）");
            }
        } catch (Exception e) {
            return ToolResponse.fromException("web_tab_close", (Exception) e);
        }
    }

    @Tool(name = "web_tab_switch", description = "切换到指定索引的 Tab 页。")
    public String tabSwitch(
            @ToolParam(name = "index", description = "目标 Tab 索引") int index) {
        log.debug("工具调用: web_tab_switch({})", index);
        try {
            if (!ToolConfirmationManager.requestConfirmation("web_tab_switch",
                    "切换到浏览器 Tab 索引 " + index)) {
                return ToolResponse.error("web_tab_switch", "用户取消了操作");
            }
            boolean success = browserManager.switchTab(index);
            if (success) {
                snapshotManager.clearRefs();
                return ToolResponse.success("web_tab_switch", "已切换到 Tab[" + index + "]");
            } else {
                return ToolResponse.error("web_tab_switch", "切换失败，无效的 Tab 索引: " + index);
            }
        } catch (Exception e) {
            return ToolResponse.fromException("web_tab_switch", (Exception) e);
        }
    }

    // ==================== JavaScript 执行工具 ====================

    @Tool(name = "web_eval_js", description = "在当前页面中执行 JavaScript 代码并返回结果。" +
            "可用于获取复杂数据、操作 DOM、调用页面 API 等。")
    public String evalJs(
            @ToolParam(name = "script", description = "要执行的 JavaScript 代码") String script) {
        log.debug("工具调用: web_eval_js({})", script.length() > 100 ? script.substring(0, 100) + "..." : script);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_eval_js", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_eval_js",
                    "执行 JS: " + (script != null && script.length() > 120 ? script.substring(0, 120) + "..." : script))) {
                return ToolResponse.error("web_eval_js", "用户取消了操作");
            }

            Object result = page.evaluate(script);
            String resultStr = (result != null) ? result.toString() : "(undefined)";
            // 截断过长的结果
            if (resultStr.length() > 5000) {
                resultStr = resultStr.substring(0, 5000) + "\n... (结果已截断)";
            }
            return ToolResponse.success("web_eval_js", resultStr);
        } catch (Exception e) {
            return ToolResponse.fromException("web_eval_js", (Exception) e);
        }
    }

    // ==================== Cookie 管理工具 ====================

    @Tool(name = "web_cookie_get", description = "获取当前浏览器的所有 Cookie 或指定 URL 的 Cookie。")
    public String cookieGet(
            @ToolParam(name = "url", description = "可选的 URL 过滤，传空字符串获取所有 Cookie") String url) {
        log.debug("工具调用: web_cookie_get({})", url);
        try {
            List<Cookie> cookies;
            if (url != null && !url.isBlank()) {
                cookies = browserManager.getCookies().stream()
                        .filter(c -> url.contains(c.domain))
                        .collect(Collectors.toList());
            } else {
                cookies = browserManager.getCookies();
            }

            if (cookies.isEmpty()) {
                return ToolResponse.success("web_cookie_get", "无 Cookie");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("共 ").append(cookies.size()).append(" 个 Cookie:\n");
            for (Cookie cookie : cookies) {
                sb.append(String.format("  %s=%s (domain=%s, path=%s, secure=%s, httpOnly=%s)\n",
                        cookie.name, truncate(cookie.value, 50),
                        cookie.domain, cookie.path, cookie.secure, cookie.httpOnly));
            }
            return ToolResponse.success("web_cookie_get", sb.toString());
        } catch (Exception e) {
            return ToolResponse.fromException("web_cookie_get", (Exception) e);
        }
    }

    @Tool(name = "web_cookie_set", description = "设置一个 Cookie。")
    public String cookieSet(
            @ToolParam(name = "name", description = "Cookie 名称") String name,
            @ToolParam(name = "value", description = "Cookie 值") String value,
            @ToolParam(name = "domain", description = "Cookie 域名") String domain,
            @ToolParam(name = "path", description = "Cookie 路径，默认 /") String path) {
        log.debug("工具调用: web_cookie_set({}, {}, {})", name, domain, path);
        try {
            if (!ToolConfirmationManager.requestConfirmation("web_cookie_set",
                    String.format("设置 Cookie %s=%s (domain=%s)", name, value, domain))) {
                return ToolResponse.error("web_cookie_set", "用户取消了操作");
            }
            Cookie cookie = new Cookie(name, value);
            cookie.setDomain(domain);
            cookie.setPath(path != null && !path.isBlank() ? path : "/");
            browserManager.setCookie(cookie);
            return ToolResponse.success("web_cookie_set",
                    String.format("已设置 Cookie: %s=%s (domain=%s)", name, value, domain));
        } catch (Exception e) {
            return ToolResponse.fromException("web_cookie_set", (Exception) e);
        }
    }

    @Tool(name = "web_cookie_clear", description = "清除所有浏览器 Cookie。")
    public String cookieClear() {
        log.debug("工具调用: web_cookie_clear()");
        try {
            if (!ToolConfirmationManager.requestConfirmation("web_cookie_clear",
                    "清除所有浏览器 Cookie")) {
                return ToolResponse.error("web_cookie_clear", "用户取消了操作");
            }
            browserManager.clearCookies();
            return ToolResponse.success("web_cookie_clear", "已清除所有 Cookie");
        } catch (Exception e) {
            return ToolResponse.fromException("web_cookie_clear", (Exception) e);
        }
    }

    // ==================== PDF 生成工具 ====================

    @Tool(name = "web_save_pdf", description = "将当前页面保存为 PDF 文件。仅在无头模式下可用。")
    public String savePdf() {
        log.debug("工具调用: web_save_pdf()");
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_save_pdf", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_save_pdf",
                    "保存当前页面为 PDF: " + page.url())) {
                return ToolResponse.error("web_save_pdf", "用户取消了操作");
            }

            Path screenshotDir = browserManager.getScreenshotDir();
            if (screenshotDir == null) return ToolResponse.error("web_save_pdf", "保存目录未配置");
            screenshotDir.toFile().mkdirs();

            String filename = "page_" + TIMESTAMP_FMT.format(LocalDateTime.now()) + ".pdf";
            Path filePath = screenshotDir.resolve(filename);

            page.pdf(new Page.PdfOptions()
                    .setPath(filePath)
                    .setFormat("A4")
                    .setPrintBackground(true));

            return ToolResponse.success("web_save_pdf", "PDF 已保存: " + filePath.toAbsolutePath());
        } catch (Exception e) {
            return ToolResponse.fromException("web_save_pdf", (Exception) e);
        }
    }

    // ==================== 鼠标操作工具 ====================

    @Tool(name = "web_mouse_move", description = "将鼠标移动到页面上的指定坐标位置。")
    public String mouseMove(
            @ToolParam(name = "x", description = "X 坐标") int x,
            @ToolParam(name = "y", description = "Y 坐标") int y) {
        log.debug("工具调用: web_mouse_move({}, {})", x, y);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_mouse_move", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_mouse_move",
                    String.format("鼠标移动到 (%d, %d)", x, y))) {
                return ToolResponse.error("web_mouse_move", "用户取消了操作");
            }

            page.mouse().move(x, y);
            return ToolResponse.success("web_mouse_move", String.format("鼠标已移动到 (%d, %d)", x, y));
        } catch (Exception e) {
            return ToolResponse.fromException("web_mouse_move", (Exception) e);
        }
    }

    @Tool(name = "web_mouse_click_at", description = "在页面上指定坐标位置点击鼠标。")
    public String mouseClickAt(
            @ToolParam(name = "x", description = "X 坐标") int x,
            @ToolParam(name = "y", description = "Y 坐标") int y) {
        log.debug("工具调用: web_mouse_click_at({}, {})", x, y);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_mouse_click_at", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_mouse_click_at",
                    String.format("鼠标在 (%d, %d) 处点击", x, y))) {
                return ToolResponse.error("web_mouse_click_at", "用户取消了操作");
            }

            page.mouse().click(x, y);
            snapshotManager.clearRefs();
            return ToolResponse.success("web_mouse_click_at", String.format("已在 (%d, %d) 处点击", x, y));
        } catch (Exception e) {
            return ToolResponse.fromException("web_mouse_click_at", (Exception) e);
        }
    }

    // ==================== 对话框处理工具 ====================

    @Tool(name = "web_dialog_handle", description = "处理浏览器弹出的对话框（alert、confirm、prompt）。" +
            "accept 为 true 表示接受（确定），false 表示拒绝（取消）。" +
            "对于 prompt 对话框可以提供输入文本。使用前需要先调用此方法注册处理器，然后触发对话框。")
    public String dialogHandle(
            @ToolParam(name = "accept", description = "true 接受/确定，false 拒绝/取消") boolean accept,
            @ToolParam(name = "prompt_text", description = "prompt 对话框的输入文本，非 prompt 对话框传空字符串") String promptText) {
        log.debug("工具调用: web_dialog_handle(accept={}, text='{}')", accept, promptText);
        try {
            Page page = browserManager.getActivePage();
            if (page == null) return ToolResponse.error("web_dialog_handle", "浏览器未启动");
            if (!ToolConfirmationManager.requestConfirmation("web_dialog_handle",
                    "处理浏览器原生对话框: " + (accept ? "接受" : "拒绝")
                            + (promptText != null && !promptText.isEmpty() ? "（输入：" + promptText + "）" : ""))) {
                return ToolResponse.error("web_dialog_handle", "用户取消了操作");
            }

            // 注册一次性对话框处理器
            page.onceDialog(dialog -> {
                log.info("处理对话框: type={}, message={}", dialog.type(), dialog.message());
                if (accept) {
                    if (promptText != null && !promptText.isEmpty()) {
                        dialog.accept(promptText);
                    } else {
                        dialog.accept();
                    }
                } else {
                    dialog.dismiss();
                }
            });

            return ToolResponse.success("web_dialog_handle",
                    "对话框处理器已注册：" + (accept ? "接受" : "拒绝") +
                            (promptText != null && !promptText.isEmpty() ? "，输入: " + promptText : ""));
        } catch (Exception e) {
            return ToolResponse.fromException("web_dialog_handle", (Exception) e);
        }
    }

    // ==================== 视口设置工具 ====================

    @Tool(name = "web_set_viewport", description = "设置浏览器视口大小。可用于测试响应式布局或模拟移动设备。")
    public String setViewport(
            @ToolParam(name = "width", description = "视口宽度（像素）") int width,
            @ToolParam(name = "height", description = "视口高度（像素）") int height) {
        log.debug("工具调用: web_set_viewport({}, {})", width, height);
        try {
            if (!ToolConfirmationManager.requestConfirmation("web_set_viewport",
                    String.format("设置浏览器视口为 %dx%d", width, height))) {
                return ToolResponse.error("web_set_viewport", "用户取消了操作");
            }
            browserManager.setViewport(width, height);
            return ToolResponse.success("web_set_viewport",
                    String.format("视口已设置为 %dx%d", width, height));
        } catch (Exception e) {
            return ToolResponse.fromException("web_set_viewport", (Exception) e);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 解析目标字符串为 Locator
     * 支持三种格式：
     * 1. 引用：@e1、e1、1 → 通过 SnapshotManager 解析
     * 2. CSS 选择器：以 #、.、[、> 等开头 → 直接使用
     * 3. 文本内容：其他情况 → 先尝试 getByText，再尝试 getByRole
     */
    private Locator resolveTarget(Page page, String target) {
        if (target == null || target.isBlank()) return null;
        target = target.trim();

        // 1. 引用格式
        if (target.startsWith("@e") || (target.startsWith("e") && target.length() > 1 && Character.isDigit(target.charAt(1)))) {
            return snapshotManager.resolveRef(page, target);
        }
        // 纯数字也视为引用
        if (target.matches("\\d+")) {
            return snapshotManager.resolveRef(page, target);
        }

        // 2. CSS 选择器
        if (target.startsWith("#") || target.startsWith(".") || target.startsWith("[")
                || target.startsWith(">") || target.contains("::") || target.startsWith("//")
                || target.matches("^[a-z]+[\\[.#>~+ ].*")) {
            // XPath
            if (target.startsWith("//")) {
                return page.locator("xpath=" + target);
            }
            return snapshotManager.resolveSelector(page, target);
        }

        // 3. 文本内容匹配
        // 先尝试精确匹配
        Locator textLocator = page.getByText(target, new Page.GetByTextOptions().setExact(true));
        if (textLocator.count() > 0) {
            return textLocator.first();
        }
        // 再尝试模糊匹配
        textLocator = page.getByText(target);
        if (textLocator.count() > 0) {
            return textLocator.first();
        }

        // 最后尝试 getByLabel
        Locator labelLocator = page.getByLabel(target);
        if (labelLocator.count() > 0) {
            return labelLocator.first();
        }

        // 尝试 getByPlaceholder
        Locator placeholderLocator = page.getByPlaceholder(target);
        if (placeholderLocator.count() > 0) {
            return placeholderLocator.first();
        }

        log.warn("无法定位目标元素: {}", target);
        return null;
    }

    /**
     * 注入标注覆盖层到页面
     *
     * @return 标注的元素数量
     */
    private int injectAnnotationOverlay(Page page) {
        // 先确保有引用数据
        Map<String, SnapshotManager.RefEntry> refs = snapshotManager.getRefMap();
        if (refs.isEmpty()) {
            // 自动执行一次快照
            snapshotManager.snapshot(page, true, false, -1);
            refs = snapshotManager.getRefMap();
        }

        // 注入标注 JS
        String annotateJs = """
                (() => {
                    // 移除旧标注
                    document.querySelectorAll('[data-jc-annotation]').forEach(el => el.remove());

                    const INTERACTIVE_TAGS = ['A', 'BUTTON', 'INPUT', 'SELECT', 'TEXTAREA', 'SUMMARY'];
                    const INTERACTIVE_ROLES = new Set([
                        'button', 'link', 'textbox', 'textarea', 'checkbox', 'radio',
                        'combobox', 'listbox', 'menuitem', 'searchbox', 'slider',
                        'spinbutton', 'switch', 'tab', 'treeitem', 'option'
                    ]);

                    let count = 0;
                    const elements = document.querySelectorAll('*');
                    for (const el of elements) {
                        const role = el.getAttribute('role') || '';
                        const tag = el.tagName;
                        const isInteractive = INTERACTIVE_TAGS.includes(tag)
                            || INTERACTIVE_ROLES.has(role.toLowerCase())
                            || el.getAttribute('contenteditable') === 'true'
                            || (el.getAttribute('tabindex') !== null && el.getAttribute('tabindex') !== '-1');

                        if (!isInteractive) continue;

                        const rect = el.getBoundingClientRect();
                        if (rect.width === 0 || rect.height === 0) continue;

                        const style = window.getComputedStyle(el);
                        if (style.display === 'none' || style.visibility === 'hidden') continue;

                        count++;

                        // 创建标注边框
                        const overlay = document.createElement('div');
                        overlay.setAttribute('data-jc-annotation', 'true');
                        overlay.style.cssText = `
                            position: fixed;
                            left: ${rect.left - 2}px;
                            top: ${rect.top - 2}px;
                            width: ${rect.width + 4}px;
                            height: ${rect.height + 4}px;
                            border: 2px solid red;
                            pointer-events: none;
                            z-index: 999999;
                            box-sizing: border-box;
                        `;

                        // 创建标签
                        const label = document.createElement('div');
                        label.setAttribute('data-jc-annotation', 'true');
                        label.textContent = count;
                        label.style.cssText = `
                            position: fixed;
                            left: ${rect.left - 2}px;
                            top: ${Math.max(0, rect.top - 18)}px;
                            background: red;
                            color: white;
                            font-size: 11px;
                            font-weight: bold;
                            padding: 1px 4px;
                            border-radius: 2px;
                            pointer-events: none;
                            z-index: 999999;
                            font-family: monospace;
                        `;

                        document.body.appendChild(overlay);
                        document.body.appendChild(label);
                    }
                    return count;
                })()
                """;

        Object result = page.evaluate(annotateJs);
        return (result instanceof Number) ? ((Number) result).intValue() : 0;
    }

    /**
     * 移除标注覆盖层
     */
    private void removeAnnotationOverlay(Page page) {
        page.evaluate("() => document.querySelectorAll('[data-jc-annotation]').forEach(el => el.remove())");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
