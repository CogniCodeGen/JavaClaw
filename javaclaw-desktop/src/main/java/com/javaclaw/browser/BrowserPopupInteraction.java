package com.javaclaw.browser;

import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.framework.spi.ToolEffectCapture;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 处理预先等待的弹窗与一次性原生对话框，不扩大工具审批或页面切换权限。 */
final class BrowserPopupInteraction {
    private static final Logger log = LoggerFactory.getLogger(BrowserPageTools.class);
    private final PlaywrightBrowserManager browserManager;
    private final SnapshotManager snapshotManager;

    BrowserPopupInteraction(PlaywrightBrowserManager browserManager, SnapshotManager snapshotManager) {
        this.browserManager = browserManager;
        this.snapshotManager = snapshotManager;
    }

    String clickExpectingPopup(Page page, Locator locator, int timeout) {
        var started = new java.util.concurrent.atomic.AtomicBoolean();
        var returned = new java.util.concurrent.atomic.AtomicBoolean();
        browserManager.noteInteractionInput(page);
        ToolEffectCapture.noteTarget("web_click", page.url());
        try {
            Page popup = page.waitForPopup(new Page.WaitForPopupOptions().setTimeout(timeout), () -> {
                started.set(true);
                locator.click();
                returned.set(true);
            });
            var candidate = browserManager.expectedPopup(page, popup);
            var data = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            data.put("kind", "browser_click_popup_result");
            data.put("clickReturned", true);
            data.put("popupMatched", candidate.isPresent());
            data.put("selected", false);
            candidate.ifPresent(surface -> data.put("pageId", surface.surfaceId())
                    .put("contextId", surface.contextId()).put("origin", surface.urlOrigin())
                    .put("tabIndex", browserManager.pageIndex(popup)));
            ToolEffectCapture.noteData("web_click", data);
            snapshotManager.clearRefs();
            return ToolResponse.success("web_click", candidate.isPresent()
                    ? "点击已返回，已捕获预先等待的弹窗候选，尚未切换。先web_tab_list核对，再显式web_tab_switch并重新web_snapshot。"
                    : "点击已返回，弹窗已出现但来源或当前存活身份未证实。先web_tab_list/web_snapshot观察，禁止重复点击。");
        } catch (RuntimeException failure) {
            if (!started.get()) return ToolResponse.fromException("web_click", failure);
            snapshotManager.clearRefs();
            var data = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            data.put("kind", "browser_click_popup_result");
            data.put("clickReturned", returned.get());
            data.put("popupMatched", false);
            data.put("selected", false);
            data.put("nextStep", "OBSERVE_WITHOUT_RECLICK");
            ToolEffectCapture.noteData("web_click", data);
            if (returned.get()) return ToolResponse.success("web_click",
                    "点击已返回，但预期弹窗尚未证实（等待结束）。先web_tab_list/web_snapshot观察，不得重新点击。");
            return ToolResponse.uncertain("web_click",
                    "点击已开始但返回无法确认；弹窗也未证实。输入可能已发出，禁止重复点击，先重新观察。");
        }
    }

    String registerDialog(Page page, boolean accept, String promptText) {
        // 注册一次性对话框处理器
        browserManager.noteInteractionInput(page);
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
    }
}
