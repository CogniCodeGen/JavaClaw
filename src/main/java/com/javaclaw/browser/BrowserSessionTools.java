package com.javaclaw.browser;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.util.ProjectAccessPolicy;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/** Tab, JavaScript, cookie and document-export tools. */
@com.javaclaw.framework.spi.ToolContract(group = "web", permissions = {"tool.execute"}, idempotent = false)
final class BrowserSessionTools {

    private static final Logger log = LoggerFactory.getLogger(BrowserSessionTools.class);
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final PlaywrightBrowserManager browserManager;
    private final SnapshotManager snapshotManager;
    private final ToolCallOrigin origin;
    private final BrowserOperationGate gate;

    BrowserSessionTools(
            PlaywrightBrowserManager browserManager,
            SnapshotManager snapshotManager,
            ToolCallOrigin origin,
            BrowserOperationGate gate) {
        this.browserManager = java.util.Objects.requireNonNull(browserManager, "browserManager");
        this.snapshotManager = java.util.Objects.requireNonNull(snapshotManager, "snapshotManager");
        this.origin = java.util.Objects.requireNonNull(origin, "origin");
        this.gate = java.util.Objects.requireNonNull(gate, "gate");
    }

    @Tool(name = "web_tab_new", description = "新建浏览器 Tab 页。可选指定初始 URL。")
    public String tabNew(@ToolParam( description = "初始 URL，传空字符串打开空白页") String url) {
        gate.enter();
        try {
            log.debug("工具调用: web_tab_new({})", url);
            try {
                if (!ToolConfirmationManager.requestConfirmation(
                        origin,
                        "web_tab_new",
                        "新建浏览器 Tab" + (url != null && !url.isBlank() ? ": " + url : ""))) {
                    return ToolResponse.error("web_tab_new", "用户取消了操作");
                }
                int index = browserManager.newTab(url);
                snapshotManager.clearRefs();
                return ToolResponse.success(
                        "web_tab_new",
                        "已创建新 Tab["
                                + index
                                + "]"
                                + (url != null && !url.isBlank() ? "，已导航到: " + url : ""));
            } catch (Exception e) {
                return ToolResponse.fromException("web_tab_new", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @com.javaclaw.framework.spi.ToolContract(group = "web", permissions = {"tool.read"}, idempotent = true)
    @Tool(name = "web_tab_list", description = "列出所有打开的 Tab 页信息（索引、标题、URL）。")
    public String tabList() {
        gate.enter();
        try {
            log.debug("工具调用: web_tab_list()");
            try {
                List<String> tabs = browserManager.listTabs();
                String result = String.join("\n", tabs);
                return ToolResponse.success(
                        "web_tab_list", "共 " + tabs.size() + " 个 Tab:\n" + result);
            } catch (Exception e) {
                return ToolResponse.fromException("web_tab_list", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_tab_close", description = "关闭指定 Tab 页。传 -1 关闭当前 Tab。至少保留一个 Tab。")
    public String tabClose(
            @ToolParam( description = "Tab 索引，-1 表示关闭当前 Tab") int index) {
        gate.enter();
        try {
            log.debug("工具调用: web_tab_close({})", index);
            try {
                if (!ToolConfirmationManager.requestConfirmation(
                        origin,
                        "web_tab_close",
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

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_tab_switch", description = "切换到指定索引的 Tab 页。")
    public String tabSwitch(@ToolParam( description = "目标 Tab 索引") int index) {
        gate.enter();
        try {
            log.debug("工具调用: web_tab_switch({})", index);
            try {
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_tab_switch", "切换到浏览器 Tab 索引 " + index)) {
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

        } finally {
            gate.exit();
        }
    }

    // ==================== JavaScript 执行工具 ====================

    @Tool(
            name = "web_eval_js",
            description = "在当前页面中执行 JavaScript 代码并返回结果。" + "可用于获取复杂数据、操作 DOM、调用页面 API 等。")
    public String evalJs(
            @ToolParam( description = "要执行的 JavaScript 代码") String script) {
        gate.enter();
        try {
            log.debug(
                    "工具调用: web_eval_js({})",
                    script == null
                            ? ""
                            : script.length() > 100 ? script.substring(0, 100) + "..." : script);
            if (ProjectAccessPolicy.strictIsolationEnabled()) {
                return ToolResponse.error(
                        "web_eval_js", "严格隔离已禁用任意页面 JavaScript，防止读取 Cookie 或本地存储");
            }
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_eval_js", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin,
                        "web_eval_js",
                        "执行 JS: "
                                + (script != null && script.length() > 120
                                        ? script.substring(0, 120) + "..."
                                        : script))) {
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

        } finally {
            gate.exit();
        }
    }

    // ==================== Cookie 管理工具 ====================

    @com.javaclaw.framework.spi.ToolContract(group = "web", permissions = {"tool.read"}, idempotent = true)
    @Tool(name = "web_cookie_get", description = "获取当前浏览器的所有 Cookie 或指定 URL 的 Cookie。")
    public String cookieGet(
            @ToolParam( description = "可选的 URL 过滤，传空字符串获取所有 Cookie") String url) {
        gate.enter();
        try {
            log.debug("工具调用: web_cookie_get({})", url);
            if (ProjectAccessPolicy.strictIsolationEnabled()) {
                return ToolResponse.error("web_cookie_get", "Cookie 只能由专用站点会话工具管理，禁止读取明文");
            }
            try {
                List<Cookie> cookies;
                if (url != null && !url.isBlank()) {
                    cookies =
                            browserManager.getCookies().stream()
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
                    sb.append(
                            String.format(
                                    "  %s=%s (domain=%s, path=%s, secure=%s, httpOnly=%s)\n",
                                    cookie.name,
                                    truncate(cookie.value, 50),
                                    cookie.domain,
                                    cookie.path,
                                    cookie.secure,
                                    cookie.httpOnly));
                }
                return ToolResponse.success("web_cookie_get", sb.toString());
            } catch (Exception e) {
                return ToolResponse.fromException("web_cookie_get", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_cookie_set", description = "设置一个 Cookie。")
    public String cookieSet(
            @ToolParam( description = "Cookie 名称") String name,
            @ToolParam( description = "Cookie 值") String value,
            @ToolParam( description = "Cookie 域名") String domain,
            @ToolParam( description = "Cookie 路径，默认 /") String path) {
        gate.enter();
        try {
            log.debug("工具调用: web_cookie_set({}, {}, {})", name, domain, path);
            if (ProjectAccessPolicy.strictIsolationEnabled()) {
                return ToolResponse.error("web_cookie_set", "Cookie 只能由专用站点会话工具管理");
            }
            try {
                if (!ToolConfirmationManager.requestConfirmation(
                        origin,
                        "web_cookie_set",
                        String.format("设置 Cookie %s=%s (domain=%s)", name, value, domain))) {
                    return ToolResponse.error("web_cookie_set", "用户取消了操作");
                }
                Cookie cookie = new Cookie(name, value);
                cookie.setDomain(domain);
                cookie.setPath(path != null && !path.isBlank() ? path : "/");
                browserManager.setCookie(cookie);
                return ToolResponse.success(
                        "web_cookie_set",
                        String.format("已设置 Cookie: %s=%s (domain=%s)", name, value, domain));
            } catch (Exception e) {
                return ToolResponse.fromException("web_cookie_set", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_cookie_clear", description = "清除所有浏览器 Cookie。")
    public String cookieClear() {
        gate.enter();
        try {
            log.debug("工具调用: web_cookie_clear()");
            if (ProjectAccessPolicy.strictIsolationEnabled()) {
                return ToolResponse.error("web_cookie_clear", "请使用专用站点会话删除工具清理登录会话");
            }
            try {
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_cookie_clear", "清除所有浏览器 Cookie")) {
                    return ToolResponse.error("web_cookie_clear", "用户取消了操作");
                }
                browserManager.clearCookies();
                return ToolResponse.success("web_cookie_clear", "已清除所有 Cookie");
            } catch (Exception e) {
                return ToolResponse.fromException("web_cookie_clear", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    // ==================== PDF 生成工具 ====================

    @Tool(name = "web_save_pdf", description = "将当前页面保存为 PDF 文件。仅在无头模式下可用。")
    public String savePdf() {
        gate.enter();
        try {
            log.debug("工具调用: web_save_pdf()");
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_save_pdf", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_save_pdf", "保存当前页面为 PDF: " + page.url())) {
                    return ToolResponse.error("web_save_pdf", "用户取消了操作");
                }

                Path screenshotDir = browserManager.getScreenshotDir();
                if (screenshotDir == null) return ToolResponse.error("web_save_pdf", "保存目录未配置");
                screenshotDir = ProjectAccessPolicy.requireProjectFilePath(screenshotDir);
                screenshotDir.toFile().mkdirs();

                String filename = "page_" + TIMESTAMP_FMT.format(LocalDateTime.now()) + ".pdf";
                Path filePath =
                        ProjectAccessPolicy.requireProjectFilePath(screenshotDir.resolve(filename));

                page.pdf(
                        new Page.PdfOptions()
                                .setPath(filePath)
                                .setFormat("A4")
                                .setPrintBackground(true));

                return ToolResponse.success(
                        "web_save_pdf", "PDF 已保存: " + filePath.toAbsolutePath());
            } catch (Exception e) {
                return ToolResponse.fromException("web_save_pdf", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
