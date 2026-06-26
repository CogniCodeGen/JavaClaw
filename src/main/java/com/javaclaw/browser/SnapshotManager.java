package com.javaclaw.browser;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 无障碍树快照管理器 — 实现 snapshot-ref 元素引用模式
 *
 * <p>核心功能：捕获页面的无障碍树（Accessibility Tree），为每个交互元素
 * 分配引用标记（如 @e1、@e2），AI 智能体可通过引用直接定位操作元素。
 * 这比 CSS 选择器更可靠，因为引用基于语义角色而非 DOM 结构。</p>
 *
 * <p>参考 agent-browser 项目的 snapshot.rs 实现，支持：
 * <ul>
 *   <li>完整无障碍树捕获（含角色、名称、值、状态）</li>
 *   <li>交互元素过滤（-i 模式只显示可交互元素）</li>
 *   <li>引用分配与解析（@e1 → Locator）</li>
 *   <li>链接 URL 显示</li>
 * </ul>
 * </p>
 *
 * @author JavaClaw
 */
public class SnapshotManager {

    private static final Logger log = LoggerFactory.getLogger(SnapshotManager.class);

    /** 交互角色集合 — 这些角色的元素会被分配引用 */
    private static final Set<String> INTERACTIVE_ROLES = Set.of(
            "button", "link", "textbox", "textarea", "checkbox", "radio",
            "combobox", "listbox", "menuitem", "searchbox", "slider",
            "spinbutton", "switch", "tab", "treeitem", "option",
            "menuitemcheckbox", "menuitemradio", "scrollbar"
    );

    /** 内容角色集合 — 这些角色显示在快照中但不分配引用 */
    private static final Set<String> CONTENT_ROLES = Set.of(
            "heading", "cell", "gridcell", "columnheader", "rowheader",
            "listitem", "article", "region", "main", "navigation",
            "banner", "contentinfo", "complementary", "form", "search",
            "img", "figure", "table", "alert", "status", "dialog",
            "progressbar", "meter", "separator", "toolbar"
    );

    /** 结构角色集合 — 仅在完整模式下显示 */
    private static final Set<String> STRUCTURAL_ROLES = Set.of(
            "generic", "group", "list", "row", "rowgroup",
            "presentation", "none", "document", "application"
    );

    /**
     * 元素引用条目 — 存储引用到 Locator 的映射信息
     */
    public static class RefEntry {
        /** 引用标记，如 @e1 */
        public final String ref;
        /** 元素角色 */
        public final String role;
        /** 元素名称 */
        public final String name;
        /** 用于定位的 ARIA 选择器信息 */
        public final String ariaRole;
        public final String ariaName;
        /** 是否精确匹配名称 */
        public final boolean exact;

        public RefEntry(String ref, String role, String name, String ariaRole, String ariaName, boolean exact) {
            this.ref = ref;
            this.role = role;
            this.name = name;
            this.ariaRole = ariaRole;
            this.ariaName = ariaName;
            this.exact = exact;
        }

        @Override
        public String toString() {
            return String.format("%s [%s] \"%s\"", ref, role, name);
        }
    }

    /** 当前引用映射表（每次 snapshot 后更新） */
    private final Map<String, RefEntry> refMap = new LinkedHashMap<>();

    /** 引用计数器 */
    private int refCounter = 0;

    /**
     * 捕获页面无障碍树快照
     *
     * @param page           目标页面
     * @param interactiveOnly 是否只显示可交互元素
     * @param showUrls       是否显示链接 URL
     * @param maxDepth       最大深度，-1 表示不限
     * @return 格式化的无障碍树文本
     */
    public String snapshot(Page page, boolean interactiveOnly, boolean showUrls, int maxDepth) {
        if (page == null || page.isClosed()) {
            return "[错误] 页面未打开";
        }

        // 重置引用
        refMap.clear();
        refCounter = 0;

        try {
            // 等待页面基本加载完成
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED);

            // 使用 JavaScript 获取无障碍树信息
            // Playwright 的 accessibility.snapshot() 可获取完整的无障碍树
            String snapshotJs = buildSnapshotScript(interactiveOnly, showUrls, maxDepth);
            Object result = page.evaluate(snapshotJs);

            if (result == null) {
                return "[错误] 无法获取页面无障碍树";
            }

            // 解析 JavaScript 返回的无障碍树数据
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) result;

            // 构建格式化输出
            StringBuilder sb = new StringBuilder();
            sb.append("页面: ").append(page.title()).append("\n");
            sb.append("URL: ").append(page.url()).append("\n");
            sb.append("─".repeat(50)).append("\n");

            for (Map<String, Object> node : nodes) {
                String line = formatNode(node, interactiveOnly, showUrls);
                if (line != null) {
                    sb.append(line).append("\n");
                }
            }

            if (refMap.isEmpty()) {
                sb.append("（页面上未发现可交互元素）\n");
            } else {
                sb.append("─".repeat(50)).append("\n");
                sb.append("共 ").append(refMap.size()).append(" 个可交互元素\n");
            }

            log.info("快照完成: {} 个引用, interactiveOnly={}", refMap.size(), interactiveOnly);
            return sb.toString();

        } catch (Exception e) {
            log.error("捕获无障碍树快照失败", e);
            return "[错误] 捕获快照失败: " + e.getMessage();
        }
    }

    /**
     * 通过引用解析元素 Locator
     *
     * @param page 目标页面
     * @param ref  元素引用（如 @e1 或 e1 或 1）
     * @return 对应的 Locator，解析失败返回 null
     */
    public Locator resolveRef(Page page, String ref) {
        if (ref == null || page == null) return null;

        // 规范化引用格式
        String normalizedRef = normalizeRef(ref);
        RefEntry entry = refMap.get(normalizedRef);
        if (entry == null) {
            log.warn("引用 {} 不存在，当前引用范围: @e1 ~ @e{}", ref, refCounter);
            return null;
        }

        try {
            // 通过 ARIA role + name 定位元素
            AriaRole ariaRole = parseAriaRole(entry.ariaRole);
            if (ariaRole != null) {
                Page.GetByRoleOptions options = new Page.GetByRoleOptions();
                if (entry.ariaName != null && !entry.ariaName.isEmpty()) {
                    options.setName(entry.ariaName);
                    options.setExact(entry.exact);
                }
                Locator locator = page.getByRole(ariaRole, options);
                if (locator.count() > 0) {
                    // 如果有多个匹配，取第一个
                    return locator.first();
                }
            }

            // 后备方案：使用通用的文本/标签定位
            if (entry.name != null && !entry.name.isEmpty()) {
                // 尝试 getByText
                Locator textLocator = page.getByText(entry.name, new Page.GetByTextOptions().setExact(true));
                if (textLocator.count() > 0) {
                    return textLocator.first();
                }
                // 尝试 getByLabel
                Locator labelLocator = page.getByLabel(entry.name, new Page.GetByLabelOptions().setExact(true));
                if (labelLocator.count() > 0) {
                    return labelLocator.first();
                }
            }

            log.warn("无法通过引用 {} 定位到元素", ref);
            return null;

        } catch (Exception e) {
            log.error("解析引用 {} 失败", ref, e);
            return null;
        }
    }

    /**
     * 通过 CSS 选择器定位元素
     */
    public Locator resolveSelector(Page page, String selector) {
        if (selector == null || page == null) return null;
        try {
            Locator locator = page.locator(selector);
            if (locator.count() > 0) {
                return locator.first();
            }
            return null;
        } catch (Exception e) {
            log.error("CSS 选择器定位失败: {}", selector, e);
            return null;
        }
    }

    /**
     * 获取当前引用映射表（只读）
     */
    public Map<String, RefEntry> getRefMap() {
        return Collections.unmodifiableMap(refMap);
    }

    /**
     * 清除当前引用
     */
    public void clearRefs() {
        refMap.clear();
        refCounter = 0;
    }

    // ==================== 内部方法 ====================

    /**
     * 规范化引用格式：支持 @e1、e1、1 三种输入
     */
    private String normalizeRef(String ref) {
        ref = ref.trim();
        if (ref.startsWith("@e")) return ref;
        if (ref.startsWith("e")) return "@" + ref;
        try {
            Integer.parseInt(ref);
            return "@e" + ref;
        } catch (NumberFormatException e) {
            return ref;
        }
    }

    /**
     * 解析 ARIA 角色字符串为 Playwright AriaRole 枚举
     */
    private AriaRole parseAriaRole(String role) {
        if (role == null) return null;
        try {
            // Playwright AriaRole 枚举值为大写
            return AriaRole.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 格式化单个无障碍树节点
     */
    private String formatNode(Map<String, Object> node, boolean interactiveOnly, boolean showUrls) {
        String role = getString(node, "role");
        String name = getString(node, "name");
        String value = getString(node, "value");
        int depth = getInt(node, "depth", 0);
        boolean isInteractive = getBool(node, "interactive");
        String url = getString(node, "url");
        String state = getString(node, "state");

        // 过滤模式
        if (interactiveOnly && !isInteractive) {
            return null;
        }

        // 跳过空的结构元素
        if (!isInteractive && STRUCTURAL_ROLES.contains(role)
                && (name == null || name.isEmpty())) {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        // 缩进
        sb.append("  ".repeat(depth));

        // 引用标记（仅交互元素）
        if (isInteractive) {
            String ref = allocateRef(role, name);
            sb.append(ref).append(" ");
        }

        // 角色
        sb.append("[").append(role).append("]");

        // 名称
        if (name != null && !name.isEmpty()) {
            sb.append(" \"").append(truncate(name, 80)).append("\"");
        }

        // 值（输入框等）
        if (value != null && !value.isEmpty()) {
            sb.append(" 值=\"").append(truncate(value, 50)).append("\"");
        }

        // 状态（checked、disabled 等）
        if (state != null && !state.isEmpty()) {
            sb.append(" (").append(state).append(")");
        }

        // URL（链接元素）
        if (showUrls && url != null && !url.isEmpty()) {
            sb.append(" → ").append(truncate(url, 100));
        }

        return sb.toString();
    }

    /**
     * 为交互元素分配引用标记
     */
    private String allocateRef(String role, String name) {
        refCounter++;
        String ref = "@e" + refCounter;
        RefEntry entry = new RefEntry(ref, role, name, role, name, false);
        refMap.put(ref, entry);
        return ref;
    }

    /**
     * 构建无障碍树捕获的 JavaScript 脚本
     */
    private String buildSnapshotScript(boolean interactiveOnly, boolean showUrls, int maxDepth) {
        return """
                (() => {
                    const INTERACTIVE_ROLES = new Set([
                        'button', 'link', 'textbox', 'textarea', 'checkbox', 'radio',
                        'combobox', 'listbox', 'menuitem', 'searchbox', 'slider',
                        'spinbutton', 'switch', 'tab', 'treeitem', 'option',
                        'menuitemcheckbox', 'menuitemradio', 'scrollbar'
                    ]);

                    const INTERACTIVE_TAGS = new Set([
                        'A', 'BUTTON', 'INPUT', 'SELECT', 'TEXTAREA', 'DETAILS', 'SUMMARY'
                    ]);

                    const CONTENT_ROLES = new Set([
                        'heading', 'cell', 'gridcell', 'columnheader', 'rowheader',
                        'listitem', 'article', 'region', 'main', 'navigation',
                        'banner', 'contentinfo', 'complementary', 'form', 'search',
                        'img', 'figure', 'table', 'alert', 'status', 'dialog',
                        'progressbar', 'meter', 'separator', 'toolbar'
                    ]);

                    const interactiveOnly = %s;
                    const showUrls = %s;
                    const maxDepth = %d;
                    const results = [];

                    function getRole(el) {
                        // 优先使用显式 ARIA role
                        const explicitRole = el.getAttribute('role');
                        if (explicitRole) return explicitRole.toLowerCase();

                        // 根据标签推断角色
                        const tag = el.tagName;
                        switch(tag) {
                            case 'A': return el.href ? 'link' : 'generic';
                            case 'BUTTON': return 'button';
                            case 'INPUT': {
                                const type = (el.type || 'text').toLowerCase();
                                switch(type) {
                                    case 'checkbox': return 'checkbox';
                                    case 'radio': return 'radio';
                                    case 'range': return 'slider';
                                    case 'number': return 'spinbutton';
                                    case 'search': return 'searchbox';
                                    case 'submit': case 'reset': case 'button': return 'button';
                                    default: return 'textbox';
                                }
                            }
                            case 'SELECT': return 'combobox';
                            case 'TEXTAREA': return 'textbox';
                            case 'IMG': return 'img';
                            case 'H1': case 'H2': case 'H3': case 'H4': case 'H5': case 'H6': return 'heading';
                            case 'NAV': return 'navigation';
                            case 'MAIN': return 'main';
                            case 'HEADER': return 'banner';
                            case 'FOOTER': return 'contentinfo';
                            case 'ASIDE': return 'complementary';
                            case 'FORM': return 'form';
                            case 'TABLE': return 'table';
                            case 'UL': case 'OL': return 'list';
                            case 'LI': return 'listitem';
                            case 'SECTION': return el.getAttribute('aria-label') ? 'region' : 'generic';
                            case 'ARTICLE': return 'article';
                            case 'DIALOG': return 'dialog';
                            case 'DETAILS': return 'group';
                            case 'SUMMARY': return 'button';
                            case 'OPTION': return 'option';
                            case 'FIGURE': return 'figure';
                            case 'FIGCAPTION': return 'generic';
                            case 'HR': return 'separator';
                            default: return 'generic';
                        }
                    }

                    function getName(el) {
                        // 按优先级获取可访问名称
                        const ariaLabel = el.getAttribute('aria-label');
                        if (ariaLabel) return ariaLabel.trim();

                        const ariaLabelledBy = el.getAttribute('aria-labelledby');
                        if (ariaLabelledBy) {
                            const labelEl = document.getElementById(ariaLabelledBy);
                            if (labelEl) return labelEl.textContent.trim();
                        }

                        if (el.tagName === 'IMG') return el.alt || '';
                        if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT') {
                            // 查找关联的 label
                            if (el.id) {
                                const label = document.querySelector(`label[for="${el.id}"]`);
                                if (label) return label.textContent.trim();
                            }
                            const parentLabel = el.closest('label');
                            if (parentLabel) {
                                const clone = parentLabel.cloneNode(true);
                                clone.querySelector('input,textarea,select')?.remove();
                                return clone.textContent.trim();
                            }
                            return el.placeholder || el.title || el.name || '';
                        }

                        // 按钮和链接使用文本内容
                        const text = el.textContent || '';
                        return text.trim().substring(0, 200);
                    }

                    function getValue(el) {
                        if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                            return el.value || '';
                        }
                        if (el.tagName === 'SELECT') {
                            const selected = el.options[el.selectedIndex];
                            return selected ? selected.text : '';
                        }
                        return '';
                    }

                    function getState(el) {
                        const states = [];
                        if (el.disabled) states.push('disabled');
                        if (el.readOnly) states.push('readonly');
                        if (el.checked) states.push('checked');
                        if (el.required) states.push('required');
                        if (el.getAttribute('aria-expanded') === 'true') states.push('expanded');
                        if (el.getAttribute('aria-selected') === 'true') states.push('selected');
                        if (el.getAttribute('aria-pressed') === 'true') states.push('pressed');
                        return states.join(', ');
                    }

                    function isVisible(el) {
                        if (!el.offsetParent && el.tagName !== 'BODY' && el.tagName !== 'HTML') {
                            const style = window.getComputedStyle(el);
                            if (style.display === 'none' || style.visibility === 'hidden') return false;
                            if (style.position !== 'fixed' && style.position !== 'sticky') return false;
                        }
                        const rect = el.getBoundingClientRect();
                        return rect.width > 0 && rect.height > 0;
                    }

                    function isInteractive(el, role) {
                        if (INTERACTIVE_ROLES.has(role)) return true;
                        if (INTERACTIVE_TAGS.has(el.tagName)) return true;
                        if (el.getAttribute('contenteditable') === 'true') return true;
                        if (el.getAttribute('tabindex') !== null && el.getAttribute('tabindex') !== '-1') return true;
                        if (el.onclick || el.getAttribute('onclick')) return true;
                        const style = window.getComputedStyle(el);
                        if (style.cursor === 'pointer' && el.tagName !== 'HTML' && el.tagName !== 'BODY') return true;
                        return false;
                    }

                    function walk(el, depth) {
                        if (maxDepth >= 0 && depth > maxDepth) return;
                        if (!el || el.nodeType !== 1) return;
                        if (!isVisible(el)) return;

                        // 跳过脚本和样式
                        const tag = el.tagName;
                        if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT' || tag === 'SVG') return;

                        const role = getRole(el);
                        const interactive = isInteractive(el, role);
                        const name = getName(el);

                        // 决定是否输出此节点
                        const isContent = CONTENT_ROLES.has(role);
                        const shouldOutput = interactive || isContent || (role === 'heading');

                        if (shouldOutput) {
                            const node = {
                                role: role,
                                name: name,
                                depth: depth,
                                interactive: interactive
                            };

                            const value = getValue(el);
                            if (value) node.value = value;

                            const state = getState(el);
                            if (state) node.state = state;

                            if (showUrls && role === 'link' && el.href) {
                                node.url = el.href;
                            }

                            results.push(node);
                        }

                        // 递归子元素
                        for (const child of el.children) {
                            walk(child, shouldOutput ? depth + 1 : depth);
                        }
                    }

                    walk(document.body, 0);
                    return results;
                })()
                """.formatted(interactiveOnly, showUrls, maxDepth);
    }

    // ==================== 工具方法 ====================

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return defaultValue;
    }

    private static boolean getBool(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        return false;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
