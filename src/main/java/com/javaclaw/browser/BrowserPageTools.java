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
import java.util.List;

/** Navigation history, element interaction, waiting and pointer tools. */
@com.javaclaw.framework.spi.ToolContract(group = "web", permissions = {"tool.execute"}, idempotent = false)
final class BrowserPageTools {

    private static final Logger log = LoggerFactory.getLogger(BrowserPageTools.class);

    private final PlaywrightBrowserManager browserManager;
    private final SnapshotManager snapshotManager;
    private final ToolCallOrigin origin;
    private final BrowserOperationGate gate;
    private final BrowserTargetResolver targets;

    BrowserPageTools(
            PlaywrightBrowserManager browserManager,
            SnapshotManager snapshotManager,
            ToolCallOrigin origin,
            BrowserOperationGate gate) {
        this.browserManager = java.util.Objects.requireNonNull(browserManager, "browserManager");
        this.snapshotManager = java.util.Objects.requireNonNull(snapshotManager, "snapshotManager");
        this.origin = java.util.Objects.requireNonNull(origin, "origin");
        this.gate = java.util.Objects.requireNonNull(gate, "gate");
        this.targets = new BrowserTargetResolver(snapshotManager);
    }

    @Tool(name = "web_go_back", description = "浏览器后退到上一页")
    public String goBack() {
        gate.enter();
        try {
            log.debug("工具调用: web_go_back()");
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_go_back", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_go_back", "浏览器后退到上一页")) {
                    return ToolResponse.error("web_go_back", "用户取消了操作");
                }

                page.goBack(new Page.GoBackOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                snapshotManager.clearRefs();
                return ToolResponse.success("web_go_back", "已后退到: " + page.url());
            } catch (Exception e) {
                return ToolResponse.fromException("web_go_back", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_go_forward", description = "浏览器前进到下一页")
    public String goForward() {
        gate.enter();
        try {
            log.debug("工具调用: web_go_forward()");
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_go_forward", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_go_forward", "浏览器前进到下一页")) {
                    return ToolResponse.error("web_go_forward", "用户取消了操作");
                }

                page.goForward(
                        new Page.GoForwardOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                snapshotManager.clearRefs();
                return ToolResponse.success("web_go_forward", "已前进到: " + page.url());
            } catch (Exception e) {
                return ToolResponse.fromException("web_go_forward", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_reload", description = "刷新当前页面")
    public String reload() {
        gate.enter();
        try {
            log.debug("工具调用: web_reload()");
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_reload", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_reload", "刷新页面: " + page.url())) {
                    return ToolResponse.error("web_reload", "用户取消了操作");
                }

                page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                snapshotManager.clearRefs();
                return ToolResponse.success("web_reload", "已刷新页面: " + page.url());
            } catch (Exception e) {
                return ToolResponse.fromException("web_reload", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(
            name = "web_click",
            description = "点击页面元素。通过引用（如 @e1）、CSS选择器或文本内容定位元素。" + "引用来自 web_snapshot 返回的元素列表。")
    public String click(
            @ToolParam( description = "目标元素：引用（@e1）、CSS选择器（#id、.class）或文本内容")
                    String target) {
        gate.enter();
        try {
            log.debug("工具调用: web_click({})", target);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_click", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_click", "点击元素: " + target)) {
                    return ToolResponse.error("web_click", "用户取消了操作");
                }

                Locator locator = targets.resolve(page, target);
                if (locator == null) return ToolResponse.error("web_click", "未找到目标元素: " + target);

                locator.click();

                // 点击后可能页面变化，清除旧引用
                snapshotManager.clearRefs();
                return ToolResponse.success("web_click", "已点击元素: " + target);
            } catch (Exception e) {
                return ToolResponse.fromException("web_click", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_dblclick", description = "双击页面元素。")
    public String doubleClick(
            @ToolParam( description = "目标元素：引用（@e1）、CSS选择器或文本内容") String target) {
        gate.enter();
        try {
            log.debug("工具调用: web_dblclick({})", target);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_dblclick", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_dblclick", "双击元素: " + target)) {
                    return ToolResponse.error("web_dblclick", "用户取消了操作");
                }

                Locator locator = targets.resolve(page, target);
                if (locator == null)
                    return ToolResponse.error("web_dblclick", "未找到目标元素: " + target);

                locator.dblclick();
                snapshotManager.clearRefs();
                return ToolResponse.success("web_dblclick", "已双击元素: " + target);
            } catch (Exception e) {
                return ToolResponse.fromException("web_dblclick", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(
            name = "web_fill",
            description = "在输入框中填充文本（会先清空原有内容）。触发 input 和 change 事件。" + "适用于文本框、搜索框、密码框等。")
    public String fill(
            @ToolParam( description = "目标输入框：引用（@e1）、CSS选择器或标签文本") String target,
            @ToolParam( description = "要填充的文本内容") String text) {
        gate.enter();
        try {
            log.debug("工具调用: web_fill({}, '{}')", target, text);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_fill", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_fill", "在 " + target + " 中填充文本: " + text)) {
                    return ToolResponse.error("web_fill", "用户取消了操作");
                }

                Locator locator = targets.resolve(page, target);
                if (locator == null) return ToolResponse.error("web_fill", "未找到目标元素: " + target);

                locator.fill(text);
                return ToolResponse.success("web_fill", "已在 " + target + " 中填充文本: " + text);
            } catch (Exception e) {
                return ToolResponse.fromException("web_fill", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(
            name = "web_type",
            description = "在当前焦点元素或指定元素中逐字输入文本（模拟键盘输入，不清空原有内容）。" + "适用于需要逐字触发事件的场景（如搜索自动补全）。")
    public String type(
            @ToolParam( description = "目标元素：引用（@e1）、CSS选择器或文本。传空字符串则在当前焦点元素输入")
                    String target,
            @ToolParam( description = "要输入的文本") String text) {
        gate.enter();
        try {
            log.debug("工具调用: web_type({}, '{}')", target, text);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_type", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin,
                        "web_type",
                        (target != null && !target.isBlank() ? "在 " + target + " 中" : "在焦点元素")
                                + "键入: "
                                + text)) {
                    return ToolResponse.error("web_type", "用户取消了操作");
                }

                if (target != null && !target.isBlank()) {
                    Locator locator = targets.resolve(page, target);
                    if (locator == null)
                        return ToolResponse.error("web_type", "未找到目标元素: " + target);
                    locator.pressSequentially(
                            text, new Locator.PressSequentiallyOptions().setDelay(50));
                } else {
                    page.keyboard().type(text, new Keyboard.TypeOptions().setDelay(50));
                }
                return ToolResponse.success("web_type", "已输入文本: " + text);
            } catch (Exception e) {
                return ToolResponse.fromException("web_type", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_hover", description = "将鼠标悬停在指定元素上。可用于触发悬停菜单、提示框等。")
    public String hover(
            @ToolParam( description = "目标元素：引用（@e1）、CSS选择器或文本") String target) {
        gate.enter();
        try {
            log.debug("工具调用: web_hover({})", target);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_hover", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_hover", "悬停元素: " + target)) {
                    return ToolResponse.error("web_hover", "用户取消了操作");
                }

                Locator locator = targets.resolve(page, target);
                if (locator == null) return ToolResponse.error("web_hover", "未找到目标元素: " + target);

                locator.hover();
                return ToolResponse.success("web_hover", "已悬停在元素: " + target);
            } catch (Exception e) {
                return ToolResponse.fromException("web_hover", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_select", description = "在下拉选择框中选择指定选项。可通过值、标签文本或索引选择。")
    public String select(
            @ToolParam( description = "目标下拉框：引用（@e1）、CSS选择器") String target,
            @ToolParam( description = "要选择的选项值或标签文本") String value) {
        gate.enter();
        try {
            log.debug("工具调用: web_select({}, '{}')", target, value);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_select", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_select", "下拉选择 " + target + " = " + value)) {
                    return ToolResponse.error("web_select", "用户取消了操作");
                }

                Locator locator = targets.resolve(page, target);
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

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_check", description = "勾选或取消勾选复选框/开关。")
    public String check(
            @ToolParam( description = "目标复选框：引用（@e1）、CSS选择器") String target,
            @ToolParam( description = "是否勾选，true 为勾选，false 为取消") boolean checked) {
        gate.enter();
        try {
            log.debug("工具调用: web_check({}, {})", target, checked);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_check", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_check", (checked ? "勾选" : "取消勾选") + "复选框: " + target)) {
                    return ToolResponse.error("web_check", "用户取消了操作");
                }

                Locator locator = targets.resolve(page, target);
                if (locator == null) return ToolResponse.error("web_check", "未找到目标元素: " + target);

                locator.setChecked(checked);
                return ToolResponse.success(
                        "web_check", (checked ? "已勾选" : "已取消勾选") + "元素: " + target);
            } catch (Exception e) {
                return ToolResponse.fromException("web_check", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_focus", description = "将焦点移到指定元素上。")
    public String focus(
            @ToolParam( description = "目标元素：引用（@e1）、CSS选择器") String target) {
        gate.enter();
        try {
            log.debug("工具调用: web_focus({})", target);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_focus", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_focus", "聚焦元素: " + target)) {
                    return ToolResponse.error("web_focus", "用户取消了操作");
                }

                Locator locator = targets.resolve(page, target);
                if (locator == null) return ToolResponse.error("web_focus", "未找到目标元素: " + target);

                locator.focus();
                return ToolResponse.success("web_focus", "已聚焦到元素: " + target);
            } catch (Exception e) {
                return ToolResponse.fromException("web_focus", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_upload", description = "上传文件到文件输入框。")
    public String upload(
            @ToolParam( description = "文件输入框：引用（@e1）、CSS选择器") String target,
            @ToolParam( description = "要上传的文件路径") String filePath) {
        gate.enter();
        try {
            log.debug("工具调用: web_upload({}, {})", target, filePath);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_upload", "浏览器未启动");
                Path uploadPath = ProjectAccessPolicy.resolveProjectPath(filePath);
                if (BrowserContentSafety.containsLikelyCredential(uploadPath)) {
                    return ToolResponse.error("web_upload", "项目文件可能包含凭据，已拒绝上传");
                }
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_upload", "上传文件 " + filePath + " 到 " + target)) {
                    return ToolResponse.error("web_upload", "用户取消了操作");
                }

                Locator locator = targets.resolve(page, target);
                if (locator == null) return ToolResponse.error("web_upload", "未找到目标元素: " + target);

                locator.setInputFiles(uploadPath);
                return ToolResponse.success("web_upload", "已上传项目内文件: " + uploadPath.getFileName());
            } catch (Exception e) {
                return ToolResponse.fromException("web_upload", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_drag", description = "将元素拖拽到目标位置。")
    public String drag(
            @ToolParam( description = "源元素：引用（@e1）、CSS选择器") String source,
            @ToolParam( description = "目标元素：引用（@e2）、CSS选择器")
                    String targetElement) {
        gate.enter();
        try {
            log.debug("工具调用: web_drag({}, {})", source, targetElement);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_drag", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_drag", "拖拽 " + source + " 到 " + targetElement)) {
                    return ToolResponse.error("web_drag", "用户取消了操作");
                }

                Locator srcLocator = targets.resolve(page, source);
                Locator tgtLocator = targets.resolve(page, targetElement);
                if (srcLocator == null) return ToolResponse.error("web_drag", "未找到源元素: " + source);
                if (tgtLocator == null)
                    return ToolResponse.error("web_drag", "未找到目标元素: " + targetElement);

                srcLocator.dragTo(tgtLocator);
                snapshotManager.clearRefs();
                return ToolResponse.success("web_drag", "已将 " + source + " 拖拽到 " + targetElement);
            } catch (Exception e) {
                return ToolResponse.fromException("web_drag", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    // ==================== 键盘操作工具 ====================

    @Tool(
            name = "web_press_key",
            description =
                    "按下键盘按键。支持特殊键（Enter、Tab、Escape、ArrowDown 等）"
                            + "和组合键（Control+C、Meta+A、Shift+Tab 等）。")
    public String pressKey(
            @ToolParam(
                            description = "按键名称，如 Enter、Tab、Escape、ArrowDown、Control+A、Meta+C")
                    String key) {
        gate.enter();
        try {
            log.debug("工具调用: web_press_key({})", key);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_press_key", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_press_key", "按下按键: " + key)) {
                    return ToolResponse.error("web_press_key", "用户取消了操作");
                }

                page.keyboard().press(key);
                return ToolResponse.success("web_press_key", "已按下按键: " + key);
            } catch (Exception e) {
                return ToolResponse.fromException("web_press_key", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    // ==================== 滚动工具 ====================

    @Tool(
            name = "web_scroll",
            description =
                    "滚动页面。direction 为 up/down/left/right，amount 为像素数（默认500）。" + "也可指定目标元素，在该元素内滚动。")
    public String scroll(
            @ToolParam( description = "滚动方向：up、down、left、right")
                    String direction,
            @ToolParam( description = "滚动像素数，默认 500") int amount,
            @ToolParam( description = "可选的目标元素（在该元素内滚动），传空字符串则滚动整个页面")
                    String target) {
        gate.enter();
        try {
            log.debug("工具调用: web_scroll({}, {}, {})", direction, amount, target);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_scroll", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin,
                        "web_scroll",
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
                        return ToolResponse.error(
                                "web_scroll", "无效方向: " + direction + "，支持: up/down/left/right");
                    }
                }

                if (target != null && !target.isBlank()) {
                    Locator locator = targets.resolve(page, target);
                    if (locator != null) {
                        locator.evaluate(
                                "(el, [dx, dy]) => el.scrollBy(dx, dy)", List.of(deltaX, deltaY));
                    } else {
                        return ToolResponse.error("web_scroll", "未找到滚动目标元素: " + target);
                    }
                } else {
                    page.mouse().wheel(deltaX, deltaY);
                }

                return ToolResponse.success(
                        "web_scroll", String.format("已向%s滚动 %d 像素", direction, pixels));
            } catch (Exception e) {
                return ToolResponse.fromException("web_scroll", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_scroll_to_element", description = "滚动页面直到指定元素出现在可见区域内。")
    public String scrollToElement(
            @ToolParam( description = "目标元素：引用（@e1）、CSS选择器") String target) {
        gate.enter();
        try {
            log.debug("工具调用: web_scroll_to_element({})", target);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_scroll_to_element", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_scroll_to_element", "滚动到元素: " + target)) {
                    return ToolResponse.error("web_scroll_to_element", "用户取消了操作");
                }

                Locator locator = targets.resolve(page, target);
                if (locator == null)
                    return ToolResponse.error("web_scroll_to_element", "未找到目标元素: " + target);

                locator.scrollIntoViewIfNeeded();
                return ToolResponse.success("web_scroll_to_element", "已滚动到元素: " + target);
            } catch (Exception e) {
                return ToolResponse.fromException("web_scroll_to_element", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_wait_for_element", description = "等待指定元素出现在页面上。超时时间默认 10 秒。")
    public String waitForElement(
            @ToolParam( description = "CSS 选择器") String selector,
            @ToolParam( description = "超时秒数，默认 10") int timeoutSeconds) {
        gate.enter();
        try {
            log.debug("工具调用: web_wait_for_element({}, {}s)", selector, timeoutSeconds);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_wait_for_element", "浏览器未启动");

                int timeout = timeoutSeconds <= 0 ? 10 : timeoutSeconds;
                page.locator(selector)
                        .waitFor(
                                new Locator.WaitForOptions()
                                        .setState(WaitForSelectorState.VISIBLE)
                                        .setTimeout(timeout * 1000.0));
                return ToolResponse.success("web_wait_for_element", "元素已出现: " + selector);
            } catch (TimeoutError e) {
                return ToolResponse.timeout(
                        "web_wait_for_element", timeoutSeconds, "元素 " + selector + " 未在超时时间内出现");
            } catch (Exception e) {
                return ToolResponse.fromException("web_wait_for_element", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_wait_for_text", description = "等待页面上出现指定文本。超时时间默认 10 秒。")
    public String waitForText(
            @ToolParam( description = "要等待的文本内容") String text,
            @ToolParam( description = "超时秒数，默认 10") int timeoutSeconds) {
        gate.enter();
        try {
            log.debug("工具调用: web_wait_for_text('{}', {}s)", text, timeoutSeconds);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_wait_for_text", "浏览器未启动");

                int timeout = timeoutSeconds <= 0 ? 10 : timeoutSeconds;
                page.getByText(text)
                        .first()
                        .waitFor(
                                new Locator.WaitForOptions()
                                        .setState(WaitForSelectorState.VISIBLE)
                                        .setTimeout(timeout * 1000.0));
                return ToolResponse.success("web_wait_for_text", "文本已出现: " + text);
            } catch (TimeoutError e) {
                return ToolResponse.timeout(
                        "web_wait_for_text", timeoutSeconds, "文本 \"" + text + "\" 未在超时时间内出现");
            } catch (Exception e) {
                return ToolResponse.fromException("web_wait_for_text", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_wait_for_url", description = "等待页面 URL 匹配指定模式。超时时间默认 10 秒。")
    public String waitForUrl(
            @ToolParam( description = "URL 匹配模式（支持通配符 *）") String urlPattern,
            @ToolParam( description = "超时秒数，默认 10") int timeoutSeconds) {
        gate.enter();
        try {
            log.debug("工具调用: web_wait_for_url('{}', {}s)", urlPattern, timeoutSeconds);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_wait_for_url", "浏览器未启动");

                int timeout = timeoutSeconds <= 0 ? 10 : timeoutSeconds;
                // 将通配符模式转换为正则
                String regex = urlPattern.replace("*", ".*");
                page.waitForURL(
                        java.util.regex.Pattern.compile(regex),
                        new Page.WaitForURLOptions().setTimeout(timeout * 1000.0));
                return ToolResponse.success("web_wait_for_url", "URL 已匹配: " + page.url());
            } catch (TimeoutError e) {
                return ToolResponse.timeout(
                        "web_wait_for_url", timeoutSeconds, "URL 未匹配模式: " + urlPattern);
            } catch (Exception e) {
                return ToolResponse.fromException("web_wait_for_url", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(
            name = "web_wait_for_load",
            description =
                    "等待页面加载到指定状态。" + "状态可选：load（完全加载）、domcontentloaded（DOM解析完成）、networkidle（网络空闲）。")
    public String waitForLoad(
            @ToolParam( description = "加载状态：load、domcontentloaded、networkidle")
                    String state) {
        gate.enter();
        try {
            log.debug("工具调用: web_wait_for_load({})", state);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_wait_for_load", "浏览器未启动");

                LoadState loadState =
                        switch (state.toLowerCase()) {
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

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_mouse_move", description = "将鼠标移动到页面上的指定坐标位置。")
    public String mouseMove(
            @ToolParam( description = "X 坐标") int x,
            @ToolParam( description = "Y 坐标") int y) {
        gate.enter();
        try {
            log.debug("工具调用: web_mouse_move({}, {})", x, y);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_mouse_move", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_mouse_move", String.format("鼠标移动到 (%d, %d)", x, y))) {
                    return ToolResponse.error("web_mouse_move", "用户取消了操作");
                }

                page.mouse().move(x, y);
                return ToolResponse.success(
                        "web_mouse_move", String.format("鼠标已移动到 (%d, %d)", x, y));
            } catch (Exception e) {
                return ToolResponse.fromException("web_mouse_move", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    @Tool(name = "web_mouse_click_at", description = "在页面上指定坐标位置点击鼠标。")
    public String mouseClickAt(
            @ToolParam( description = "X 坐标") int x,
            @ToolParam( description = "Y 坐标") int y) {
        gate.enter();
        try {
            log.debug("工具调用: web_mouse_click_at({}, {})", x, y);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_mouse_click_at", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin, "web_mouse_click_at", String.format("鼠标在 (%d, %d) 处点击", x, y))) {
                    return ToolResponse.error("web_mouse_click_at", "用户取消了操作");
                }

                page.mouse().click(x, y);
                snapshotManager.clearRefs();
                return ToolResponse.success(
                        "web_mouse_click_at", String.format("已在 (%d, %d) 处点击", x, y));
            } catch (Exception e) {
                return ToolResponse.fromException("web_mouse_click_at", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    // ==================== 对话框处理工具 ====================

    @Tool(
            name = "web_dialog_handle",
            description =
                    "处理浏览器弹出的对话框（alert、confirm、prompt）。"
                            + "accept 为 true 表示接受（确定），false 表示拒绝（取消）。"
                            + "对于 prompt 对话框可以提供输入文本。使用前需要先调用此方法注册处理器，然后触发对话框。")
    public String dialogHandle(
            @ToolParam( description = "true 接受/确定，false 拒绝/取消") boolean accept,
            @ToolParam( description = "prompt 对话框的输入文本，非 prompt 对话框传空字符串")
                    String promptText) {
        gate.enter();
        try {
            log.debug("工具调用: web_dialog_handle(accept={}, text='{}')", accept, promptText);
            try {
                Page page = browserManager.getActivePage();
                if (page == null) return ToolResponse.error("web_dialog_handle", "浏览器未启动");
                if (!ToolConfirmationManager.requestConfirmation(
                        origin,
                        "web_dialog_handle",
                        "处理浏览器原生对话框: "
                                + (accept ? "接受" : "拒绝")
                                + (promptText != null && !promptText.isEmpty()
                                        ? "（输入：" + promptText + "）"
                                        : ""))) {
                    return ToolResponse.error("web_dialog_handle", "用户取消了操作");
                }

                // 注册一次性对话框处理器
                page.onceDialog(
                        dialog -> {
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

                return ToolResponse.success(
                        "web_dialog_handle",
                        "对话框处理器已注册："
                                + (accept ? "接受" : "拒绝")
                                + (promptText != null && !promptText.isEmpty()
                                        ? "，输入: " + promptText
                                        : ""));
            } catch (Exception e) {
                return ToolResponse.fromException("web_dialog_handle", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }

    // ==================== 视口设置工具 ====================

    @Tool(name = "web_set_viewport", description = "设置浏览器视口大小。可用于测试响应式布局或模拟移动设备。")
    public String setViewport(
            @ToolParam( description = "视口宽度（像素）") int width,
            @ToolParam( description = "视口高度（像素）") int height) {
        gate.enter();
        try {
            log.debug("工具调用: web_set_viewport({}, {})", width, height);
            try {
                if (!ToolConfirmationManager.requestConfirmation(
                        origin,
                        "web_set_viewport",
                        String.format("设置浏览器视口为 %dx%d", width, height))) {
                    return ToolResponse.error("web_set_viewport", "用户取消了操作");
                }
                browserManager.setViewport(width, height);
                return ToolResponse.success(
                        "web_set_viewport", String.format("视口已设置为 %dx%d", width, height));
            } catch (Exception e) {
                return ToolResponse.fromException("web_set_viewport", (Exception) e);
            }

        } finally {
            gate.exit();
        }
    }
}
