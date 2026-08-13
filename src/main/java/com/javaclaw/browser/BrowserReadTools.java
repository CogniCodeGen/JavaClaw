package com.javaclaw.browser;

import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.util.ProjectAccessPolicy;
import com.javaclaw.util.SensitiveDataRedactor;
import com.microsoft.playwright.*;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Page snapshots, screenshots, content extraction and element-state inspection tools. */
@com.javaclaw.framework.spi.ToolContract(group = "web", permissions = {"tool.read"}, idempotent = true)
final class BrowserReadTools {

    private static final Logger log = LoggerFactory.getLogger(BrowserReadTools.class);
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final PlaywrightBrowserManager browserManager;
    private final SnapshotManager snapshotManager;
    private final BrowserOperationGate gate;
    private final BrowserTargetResolver targets;

    BrowserReadTools(
            PlaywrightBrowserManager browserManager,
            SnapshotManager snapshotManager,
            BrowserOperationGate gate) {
        this.browserManager = java.util.Objects.requireNonNull(browserManager, "browserManager");
        this.snapshotManager = java.util.Objects.requireNonNull(snapshotManager, "snapshotManager");
        this.gate = java.util.Objects.requireNonNull(gate, "gate");
        this.targets = new BrowserTargetResolver(snapshotManager);
    }

    @Tool(
            name = "web_snapshot",
            description =
                    "获取当前页面的无障碍树快照。为每个可交互元素分配引用标记（如 @e1、@e2），后续可通过引用直接操作元素（如 web_click"
                        + " @e1）。这是与页面交互前的必要步骤。参数 interactive_only 控制是否只显示可交互元素（默认 true），show_urls"
                        + " 控制是否显示链接 URL（默认 false）。")
    public String snapshot(
            @ToolParam( description = "是否只显示可交互元素，默认 true")
                    boolean interactiveOnly,
            @ToolParam( description = "是否显示链接的 URL 地址，默认 false")
                    boolean showUrls) {
        gate.enter();
        try {
            log.debug(
                    "工具调用: web_snapshot(interactiveOnly={}, showUrls={})",
                    interactiveOnly,
                    showUrls);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_snapshot", "浏览器未启动");

                String result = snapshotManager.snapshot(page, interactiveOnly, showUrls, -1);
                return ToolResponse.success(
                        "web_snapshot",
                        com.javaclaw.util.ExternalContentGuard.wrap("网页 " + page.url(), result));
            } catch (Exception e) {
                return ToolResponse.fromException("web_snapshot", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @com.javaclaw.framework.spi.ToolContract(group = "web", permissions = {"tool.execute"}, idempotent = false)
    @Tool(
            name = "web_screenshot",
            description = "对当前页面进行截图并保存为 PNG 文件。" + "可选参数 full_page 控制是否截取整个页面（默认 false 只截取可见区域）。")
    public String screenshot(
            @ToolParam( description = "是否截取整个页面（含滚动区域），默认 false")
                    boolean fullPage) {
        gate.enter();
        try {
            log.debug("工具调用: web_screenshot(fullPage={})", fullPage);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_screenshot", "浏览器未启动");

                Path screenshotDir = browserManager.getScreenshotDir();
                if (screenshotDir == null) return ToolResponse.error("web_screenshot", "截图目录未配置");
                screenshotDir = ProjectAccessPolicy.requireProjectFilePath(screenshotDir);

                // 确保目录存在
                screenshotDir.toFile().mkdirs();

                String filename =
                        "screenshot_" + TIMESTAMP_FMT.format(LocalDateTime.now()) + ".png";
                Path filePath =
                        ProjectAccessPolicy.requireProjectFilePath(screenshotDir.resolve(filename));

                page.screenshot(
                        new Page.ScreenshotOptions().setPath(filePath).setFullPage(fullPage));

                log.info("截图已保存: {}", filePath);
                return ToolResponse.success(
                        "web_screenshot", "截图已保存: " + filePath.toAbsolutePath());
            } catch (Exception e) {
                return ToolResponse.fromException("web_screenshot", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @com.javaclaw.framework.spi.ToolContract(group = "web", permissions = {"tool.execute"}, idempotent = false)
    @Tool(
            name = "web_screenshot_annotated",
            description =
                    "对当前页面进行标注截图：在每个可交互元素上叠加红色边框和编号标签。"
                            + "标注与快照引用对应（@e1 → 标签1），方便视觉定位元素。截图保存为 PNG 文件。")
    public String screenshotAnnotated() {
        gate.enter();
        try {
            log.debug("工具调用: web_screenshot_annotated()");
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_screenshot_annotated", "浏览器未启动");

                Path screenshotDir = browserManager.getScreenshotDir();
                if (screenshotDir == null)
                    return ToolResponse.error("web_screenshot_annotated", "截图目录未配置");
                screenshotDir = ProjectAccessPolicy.requireProjectFilePath(screenshotDir);
                screenshotDir.toFile().mkdirs();

                // 注入标注覆盖层
                int annotationCount = injectAnnotationOverlay(page);

                // 截图
                String filename = "annotated_" + TIMESTAMP_FMT.format(LocalDateTime.now()) + ".png";
                Path filePath =
                        ProjectAccessPolicy.requireProjectFilePath(screenshotDir.resolve(filename));
                page.screenshot(new Page.ScreenshotOptions().setPath(filePath));

                // 移除标注覆盖层
                removeAnnotationOverlay(page);

                log.info("标注截图已保存: {}，标注 {} 个元素", filePath, annotationCount);
                return ToolResponse.success(
                        "web_screenshot_annotated",
                        String.format(
                                "标注截图已保存: %s\n标注了 %d 个可交互元素",
                                filePath.toAbsolutePath(), annotationCount));
            } catch (Exception e) {
                return ToolResponse.fromException("web_screenshot_annotated", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    // ==================== 元素交互工具 ====================

    @Tool(name = "web_get_text", description = "获取指定元素的文本内容。")
    public String getText(
            @ToolParam( description = "目标元素：引用（@e1）、CSS选择器") String target) {
        gate.enter();
        try {
            log.debug("工具调用: web_get_text({})", target);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_get_text", "浏览器未启动");

                Locator locator = targets.resolve(page, target);
                if (locator == null)
                    return ToolResponse.error("web_get_text", "未找到目标元素: " + target);

                String text = locator.innerText();
                if (SensitiveDataRedactor.containsLikelyCredential(text)) {
                    return ToolResponse.error("web_get_text", "网页文本包含疑似凭据，已阻止返回");
                }
                return ToolResponse.success(
                        "web_get_text",
                        com.javaclaw.util.ExternalContentGuard.wrap("网页 " + page.url(), text));
            } catch (Exception e) {
                return ToolResponse.fromException("web_get_text", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_get_html", description = "获取指定元素的 HTML 内容。")
    public String getHtml(
            @ToolParam( description = "目标元素：引用（@e1）、CSS选择器") String target) {
        gate.enter();
        try {
            log.debug("工具调用: web_get_html({})", target);
            if (ProjectAccessPolicy.strictIsolationEnabled()) {
                return ToolResponse.error(
                        "web_get_html", "严格隔离已禁用原始 HTML 读取，请使用 web_snapshot 获取脱敏后的页面结构");
            }
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_get_html", "浏览器未启动");

                Locator locator = targets.resolve(page, target);
                if (locator == null)
                    return ToolResponse.error("web_get_html", "未找到目标元素: " + target);

                String html = locator.innerHTML();
                // 截断过长的 HTML
                if (html.length() > 5000) {
                    html =
                            html.substring(0, 5000)
                                    + "\n... (HTML 内容已截断，共 "
                                    + html.length()
                                    + " 字符)";
                }
                return ToolResponse.success(
                        "web_get_html",
                        com.javaclaw.util.ExternalContentGuard.wrap("网页 " + page.url(), html));
            } catch (Exception e) {
                return ToolResponse.fromException("web_get_html", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_get_attribute", description = "获取指定元素的属性值。")
    public String getAttribute(
            @ToolParam( description = "目标元素：引用（@e1）、CSS选择器") String target,
            @ToolParam( description = "属性名称，如 href、src、class、value 等")
                    String attribute) {
        gate.enter();
        try {
            log.debug("工具调用: web_get_attribute({}, {})", target, attribute);
            if (ProjectAccessPolicy.strictIsolationEnabled()
                    && "value".equalsIgnoreCase(attribute == null ? "" : attribute.strip())) {
                return ToolResponse.error("web_get_attribute", "严格隔离已禁止读取元素 value 属性");
            }
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_get_attribute", "浏览器未启动");

                Locator locator = targets.resolve(page, target);
                if (locator == null)
                    return ToolResponse.error("web_get_attribute", "未找到目标元素: " + target);

                String value = locator.getAttribute(attribute);
                if (SensitiveDataRedactor.containsLikelyCredential(value)) {
                    return ToolResponse.error("web_get_attribute", "属性值包含疑似凭据，已阻止返回");
                }
                return ToolResponse.success(
                        "web_get_attribute",
                        attribute + "=\"" + (value != null ? value : "(null)") + "\"");
            } catch (Exception e) {
                return ToolResponse.fromException("web_get_attribute", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_get_url", description = "获取当前页面的 URL 地址")
    public String getUrl() {
        gate.enter();
        try {
            log.debug("工具调用: web_get_url()");
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_get_url", "浏览器未启动");
                String url = page.url();
                return SensitiveDataRedactor.containsLikelyCredential(url)
                        ? ToolResponse.error("web_get_url", "当前 URL 包含疑似凭据参数，已阻止返回")
                        : ToolResponse.success("web_get_url", url);
            } catch (Exception e) {
                return ToolResponse.fromException("web_get_url", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_get_title", description = "获取当前页面的标题")
    public String getTitle() {
        gate.enter();
        try {
            log.debug("工具调用: web_get_title()");
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_get_title", "浏览器未启动");
                return ToolResponse.success("web_get_title", page.title());
            } catch (Exception e) {
                return ToolResponse.fromException("web_get_title", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_get_value", description = "获取输入框当前的值。")
    public String getValue(
            @ToolParam( description = "目标输入框：引用（@e1）、CSS选择器") String target) {
        gate.enter();
        try {
            log.debug("工具调用: web_get_value({})", target);
            if (ProjectAccessPolicy.strictIsolationEnabled()) {
                return ToolResponse.error("web_get_value", "严格隔离已禁止读取输入框值");
            }
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_get_value", "浏览器未启动");

                Locator locator = targets.resolve(page, target);
                if (locator == null)
                    return ToolResponse.error("web_get_value", "未找到目标元素: " + target);

                String value = locator.inputValue();
                return ToolResponse.success("web_get_value", "值=\"" + value + "\"");
            } catch (Exception e) {
                return ToolResponse.fromException("web_get_value", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_get_count", description = "获取匹配指定 CSS 选择器的元素数量。")
    public String getCount(@ToolParam( description = "CSS 选择器") String selector) {
        gate.enter();
        try {
            log.debug("工具调用: web_get_count({})", selector);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_get_count", "浏览器未启动");

                int count = page.locator(selector).count();
                return ToolResponse.success("web_get_count", "匹配元素数量: " + count);
            } catch (Exception e) {
                return ToolResponse.fromException("web_get_count", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    // ==================== 状态检查工具 ====================

    @Tool(name = "web_is_visible", description = "检查指定元素是否可见。")
    public String isVisible(
            @ToolParam( description = "目标元素：引用（@e1）、CSS选择器") String target) {
        gate.enter();
        try {
            log.debug("工具调用: web_is_visible({})", target);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_is_visible", "浏览器未启动");

                Locator locator = targets.resolve(page, target);
                if (locator == null) return ToolResponse.success("web_is_visible", "元素不存在，不可见");

                boolean visible = locator.isVisible();
                return ToolResponse.success(
                        "web_is_visible", target + " " + (visible ? "可见" : "不可见"));
            } catch (Exception e) {
                return ToolResponse.fromException("web_is_visible", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_is_enabled", description = "检查指定元素是否启用（非 disabled 状态）。")
    public String isEnabled(
            @ToolParam( description = "目标元素：引用（@e1）、CSS选择器") String target) {
        gate.enter();
        try {
            log.debug("工具调用: web_is_enabled({})", target);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_is_enabled", "浏览器未启动");

                Locator locator = targets.resolve(page, target);
                if (locator == null)
                    return ToolResponse.error("web_is_enabled", "未找到目标元素: " + target);

                boolean enabled = locator.isEnabled();
                return ToolResponse.success(
                        "web_is_enabled", target + " " + (enabled ? "已启用" : "已禁用"));
            } catch (Exception e) {
                return ToolResponse.fromException("web_is_enabled", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_is_checked", description = "检查复选框/单选按钮是否被选中。")
    public String isChecked(
            @ToolParam( description = "目标元素：引用（@e1）、CSS选择器") String target) {
        gate.enter();
        try {
            log.debug("工具调用: web_is_checked({})", target);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_is_checked", "浏览器未启动");

                Locator locator = targets.resolve(page, target);
                if (locator == null)
                    return ToolResponse.error("web_is_checked", "未找到目标元素: " + target);

                boolean checked = locator.isChecked();
                return ToolResponse.success(
                        "web_is_checked", target + " " + (checked ? "已选中" : "未选中"));
            } catch (Exception e) {
                return ToolResponse.fromException("web_is_checked", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    private int injectAnnotationOverlay(Page page) {
        // 先确保有引用数据
        Map<String, SnapshotManager.RefEntry> refs = snapshotManager.getRefMap();
        if (refs.isEmpty()) {
            // 自动执行一次快照
            snapshotManager.snapshot(page, true, false, -1);
            refs = snapshotManager.getRefMap();
        }

        // 注入标注 JS
        String annotateJs =
                """
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

    /** 移除标注覆盖层 */
    private void removeAnnotationOverlay(Page page) {
        page.evaluate(
                "() => document.querySelectorAll('[data-jc-annotation]').forEach(el =>"
                    + " el.remove())");
    }
}
