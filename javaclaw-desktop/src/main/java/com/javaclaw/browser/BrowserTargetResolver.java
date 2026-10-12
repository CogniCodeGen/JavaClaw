package com.javaclaw.browser;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Resolves an explicitly typed browser target to one Playwright locator. */
final class BrowserTargetResolver {

    private static final Logger log = LoggerFactory.getLogger(BrowserTargetResolver.class);
    static final String TOOL_FORMAT = "目标格式：@e1 快照引用；css:#id；xpath://button；"
            + "text:文字；label:标签；placeholder:占位提示。无前缀仅按文字匹配。";

    private final SnapshotManager snapshots;

    BrowserTargetResolver(SnapshotManager snapshots) {
        this.snapshots = java.util.Objects.requireNonNull(snapshots, "snapshots");
    }

    Locator resolve(Page page, String target) {
        if (target == null || target.isBlank()) return null;
        TargetRef reference = parse(target);
        Locator located = switch (reference.kind()) {
            case SNAPSHOT -> snapshots.resolveRef(page, reference.value());
            case CSS -> snapshots.resolveSelector(page, reference.value());
            case XPATH -> snapshots.resolveSelector(page, "xpath=" + reference.value());
            case TEXT -> byText(page, reference.value());
            case LABEL -> first(page.getByLabel(reference.value()));
            case PLACEHOLDER -> first(page.getByPlaceholder(reference.value()));
        };
        if (located == null) log.warn("无法定位目标元素: {}", target);
        return located;
    }

    private static Locator byText(Page page, String value) {
        Locator exact = first(page.getByText(value, new Page.GetByTextOptions().setExact(true)));
        return exact != null ? exact : first(page.getByText(value));
    }

    private static Locator first(Locator locator) {
        return locator.count() > 0 ? locator.first() : null;
    }

    enum Kind { SNAPSHOT, CSS, XPATH, TEXT, LABEL, PLACEHOLDER }

    record TargetRef(Kind kind, String value) {
        TargetRef {
            java.util.Objects.requireNonNull(kind, "kind");
            value = java.util.Objects.requireNonNull(value, "value").strip();
            if (value.isEmpty()) throw new IllegalArgumentException("browser target value is blank");
        }
    }

    /** Plain values are text. Other modes require an explicit prefix. */
    static TargetRef parse(String target) {
        String value = java.util.Objects.requireNonNull(target, "target").strip();
        if (value.matches("@e[1-9][0-9]*")) return new TargetRef(Kind.SNAPSHOT, value);
        for (Kind kind : Kind.values()) {
            if (kind == Kind.SNAPSHOT) continue;
            String prefix = kind.name().toLowerCase(java.util.Locale.ROOT) + ':';
            if (value.startsWith(prefix)) return new TargetRef(kind, value.substring(prefix.length()));
        }
        return new TargetRef(Kind.TEXT, value);
    }
}
