package com.javaclaw.browser;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Resolves snapshot references, selectors and accessible text to one Playwright locator. */
final class BrowserTargetResolver {

    private static final Logger log = LoggerFactory.getLogger(BrowserTargetResolver.class);

    private final SnapshotManager snapshots;

    BrowserTargetResolver(SnapshotManager snapshots) {
        this.snapshots = java.util.Objects.requireNonNull(snapshots, "snapshots");
    }

    Locator resolve(Page page, String target) {
        if (target == null || target.isBlank()) return null;
        target = target.trim();

        // 1. 引用格式
        if (target.startsWith("@e")
                || (target.startsWith("e")
                        && target.length() > 1
                        && Character.isDigit(target.charAt(1)))) {
            return snapshots.resolveRef(page, target);
        }
        // 纯数字也视为引用
        if (target.matches("\\d+")) {
            return snapshots.resolveRef(page, target);
        }

        // 2. CSS 选择器
        if (looksLikeSelector(target)) {
            Locator selectorLocator;
            if (target.startsWith("//")) {
                selectorLocator = snapshots.resolveSelector(page, "xpath=" + target);
            } else {
                selectorLocator = snapshots.resolveSelector(page, target);
            }
            if (selectorLocator != null) return selectorLocator;
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

    static boolean looksLikeSelector(String target) {
        return target.startsWith("#")
                || target.startsWith(".")
                || target.startsWith("[")
                || target.startsWith(">")
                || target.contains("::")
                || target.startsWith("//")
                || target.matches("^[A-Za-z][A-Za-z0-9_-]*$")
                || target.matches("^[a-z]+[\\[.#>~+ ].*");
    }
}
