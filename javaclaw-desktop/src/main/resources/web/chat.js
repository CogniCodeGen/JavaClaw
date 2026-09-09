/* Java 提交有版本的行补丁；DOM 只维护附近128条，正文、选区和阅读锚点由原节点连续承载。 */
"use strict";
(() => {
    const root = document.getElementById("surface");
    root.classList.add("chat");
    const heights = new Map();
    const nodes = new Map();
    const rows = new Map();
    const dirty = new Set();
    const before = document.createElement("div");
    const after = document.createElement("div");
    before.className = after.className = "chat-spacer";
    const earlier = document.createElement("button");
    earlier.className = "history";
    earlier.textContent = "加载更早消息";
    earlier.onclick = () => window.JavaClawSurface.post("history");
    let items = [];
    let mounted = [];
    let following = true;
    let rendering = false;
    let hasEarlier = false;
    let scheduled = false;
    let layoutPending = false;
    let mountedStart = 0;
    let mountedEnd = 0;
    let latestButton = null;
    let lastAnchor = null;
    let lastScrollY = 0;
    let lastWidth = window.innerWidth;
    let lastHeight = window.innerHeight;
    let lastDocumentHeight = 0;
    let geometry = "";
    let emojiAnchor = null;
    function height(item) { return heights.get(item.id) || 140; }
    function sum(start, end) {
        let total = 0;
        for (let index = start; index < end; index++) { total += height(items[index]); }
        return total;
    }
    function roleTitle(title) {
        const names = {USER: "你", ASSISTANT: "助手", SYSTEM: "系统", TOOL: "工具"};
        return String(title || "").replace(/^(USER|ASSISTANT|SYSTEM|TOOL)(?=\s|$)/, name => names[name]);
    }
    function selection(body) {
        const selected = window.getSelection();
        if (!selected.rangeCount || selected.isCollapsed) { return null; }
        const range = selected.getRangeAt(0);
        const startInside = body.contains(range.startContainer);
        const endInside = body.contains(range.endContainer);
        if (!startInside && !endInside) { return null; }
        const local = range.cloneRange();
        if (!startInside) { local.setStart(body, 0); }
        if (!endInside) { local.setEnd(body, body.childNodes.length); }
        const prefix = document.createRange();
        prefix.selectNodeContents(body);
        prefix.setEnd(local.startContainer, local.startOffset);
        // 只重建当前正文内的端点；另一条消息的端点继续绑定原节点，并保留反向拖选方向。
        return {offset: prefix.toString().length, text: local.toString(),
            start: startInside ? null : {node: range.startContainer, offset: range.startOffset},
            end: endInside ? null : {node: range.endContainer, offset: range.endOffset},
            backward: selected.anchorNode === range.endContainer && selected.anchorOffset === range.endOffset};
    }
    function restoreSelection(body, saved) {
        if (!saved) { return; }
        const text = body.textContent;
        let start = Math.min(saved.offset, text.length);
        if (text.substring(start, start + saved.text.length) !== saved.text) { start = text.indexOf(saved.text); }
        if (start < 0) { return; }
        const first = saved.start || textBoundary(body, start);
        const last = saved.end || textBoundary(body, start + saved.text.length);
        if (!first || !last || !first.node.isConnected || !last.node.isConnected) { return; }
        const anchor = saved.backward ? last : first;
        const focus = saved.backward ? first : last;
        window.getSelection().setBaseAndExtent(anchor.node, anchor.offset, focus.node, focus.offset);
    }
    function textBoundary(body, position) {
        const walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
        let offset = 0;
        let node;
        while ((node = walker.nextNode())) {
            const end = offset + node.length;
            if (position <= end) { return {node, offset: position - offset}; }
            offset = end;
        }
        return position === 0 ? {node: body, offset: 0} : null;
    }
    function codePositions(body) {
        return [...body.querySelectorAll("pre")].map(pre => ({text: pre.textContent, left: pre.scrollLeft}));
    }
    function restoreCodePositions(body, positions) {
        [...body.querySelectorAll("pre")].forEach((pre, index) => {
            const previous = positions[index];
            if (previous && pre.textContent.startsWith(previous.text)) { pre.scrollLeft = previous.left; }
        });
    }
    function body(entry, item) {
        const split = item.streaming && typeof item.streamHtml === "string" && typeof item.streamSuffix === "string";
        const mode = split ? "stream" : item.html ? "markdown" : "plain";
        const html = split ? item.streamHtml : item.html || "";
        const text = split ? item.streamSuffix : item.text || "";
        const reformatted = entry.mode !== mode || (mode !== "plain" && entry.html !== html);
        const replacingText = mode !== "markdown" && entry.mode === mode && !text.startsWith(entry.text.data);
        const changingText = mode !== "markdown" && entry.mode === mode && entry.text.data !== text;
        const saved = reformatted || replacingText || changingText ? selection(entry.body) : null;
        const scrolls = reformatted ? codePositions(entry.body) : null;
        if (entry.mode !== mode) {
            entry.body.className = "message-body" + (mode === "plain" ? " plain" : "");
            entry.body.removeAttribute("data-emoji-text");
            entry.body.replaceChildren();
            entry.markdown = mode === "stream" ? document.createElement("div") : entry.body;
            if (mode === "stream") {
                const suffix = document.createElement("span");
                suffix.className = "plain";
                entry.text = window.JavaClawEmoji.createText(suffix);
                entry.body.append(entry.markdown, suffix);
            } else if (mode === "plain") {
                entry.text = window.JavaClawEmoji.createText(entry.body);
            }
            entry.mode = mode;
            entry.html = null;
        }
        if (mode !== "plain" && entry.html !== html) {
            entry.markdown.innerHTML = html;
            window.JavaClawEmoji.decorate(entry.markdown);
            entry.html = html;
        }
        if (mode !== "markdown") { window.JavaClawEmoji.appendText(entry.text, text); }
        if (reformatted) { restoreCodePositions(entry.body, scrolls); }
        if (saved) { restoreSelection(entry.body, saved); }
        if (!item.streaming && mode === "markdown") { window.JavaClawSurface.highlight(entry.body); }
    }
    function references(entry, values) {
        const key = JSON.stringify(values || []);
        if (entry.references === key) { return; }
        entry.references = key;
        if (entry.attachments) { entry.attachments.remove(); entry.attachments = null; }
        if (!values || !values.length) { return; }
        const attachments = document.createElement("div");
        attachments.className = "attachments";
        for (const ref of values) {
            const link = document.createElement("a");
            link.className = "attachment";
            link.href = "#";
            link.textContent = ref.label;
            link.addEventListener("click", event => {
                event.preventDefault(); window.JavaClawSurface.post("preview", ref.id);
            });
            attachments.append(link);
        }
        entry.attachments = attachments;
        entry.node.append(attachments);
    }
    function create(item) {
        let entry = nodes.get(item.id);
        if (!entry) {
            const node = document.createElement("article");
            node.dataset.id = item.id;
            const role = document.createElement("div");
            role.className = "role";
            const content = document.createElement("div");
            node.append(role, content);
            entry = {node, role, body: content};
            nodes.set(item.id, entry);
        }
        if (entry.row !== item) {
            const previous = entry.row;
            const style = "message " + (item.style || "");
            if (entry.node.className !== style) { entry.node.className = style; }
            if (entry.node.dataset.version !== String(item.version || "")) {
                entry.node.dataset.version = item.version || "";
            }
            const streaming = String(!!item.streaming);
            if (entry.node.dataset.streaming !== streaming) { entry.node.dataset.streaming = streaming; }
            const title = roleTitle(item.title);
            if (entry.role.textContent !== title) { entry.role.textContent = title; }
            body(entry, item);
            references(entry, item.references);
            entry.row = item;
            if (!previous || previous.title !== item.title || previous.style !== item.style
                    || previous.html !== item.html || previous.text !== item.text
                    || previous.streamHtml !== item.streamHtml || previous.streamSuffix !== item.streamSuffix
                    || JSON.stringify(previous.references) !== JSON.stringify(item.references)) { dirty.add(item.id); }
        }
        return entry.node;
    }
    function visibleAnchor() {
        let low = 0;
        let high = mounted.length;
        while (low < high) {
            const middle = Math.floor((low + high) / 2);
            if (mounted[middle].getBoundingClientRect().bottom <= 0) { low = middle + 1; }
            else { high = middle; }
        }
        const node = mounted[low];
        if (!node) { return null; }
        const bounds = node.getBoundingClientRect();
        return bounds.top < window.innerHeight ? {id: node.dataset.id, offset: bounds.top} : null;
    }
    function remember() {
        lastAnchor = visibleAnchor();
        window.JavaClawSurface.post("viewState", JSON.stringify({following, anchor: lastAnchor}));
    }
    function setFollowing(value) {
        if (following !== value) { following = value; window.JavaClawSurface.post("following", following); }
    }
    function rememberGeometry() {
        lastScrollY = window.scrollY;
        lastWidth = window.innerWidth;
        lastHeight = window.innerHeight;
        lastDocumentHeight = document.documentElement.scrollHeight;
    }
    function observeScroll() {
        const top = Math.max(0, window.scrollY);
        const movement = top - lastScrollY;
        const layoutChanged = lastWidth !== window.innerWidth || lastHeight !== window.innerHeight
                || lastDocumentHeight !== document.documentElement.scrollHeight;
        // 原生惯性属于用户输入；窗口与字体变化造成的自动夹紧不能切换跟随状态。
        if (!layoutChanged && movement < -0.5) { setFollowing(false); }
        else if (!layoutChanged && movement > 0.5
                && document.documentElement.scrollHeight - top - window.innerHeight <= 2) { setFollowing(true); }
        rememberGeometry();
    }
    function needsWindow() {
        return items.length > 128 && (!mounted.length
                || (mountedStart > 0 && mounted[0].getBoundingClientRect().top > -400)
                || (mountedEnd < items.length
                        && mounted[mounted.length - 1].getBoundingClientRect().bottom < window.innerHeight + 400));
    }
    function scheduleRender(layout = false) {
        layoutPending = layoutPending || layout;
        if (scheduled || !items.length) { return; }
        scheduled = true;
        requestAnimationFrame(() => {
            scheduled = false;
            const changed = layoutPending;
            layoutPending = false;
            if (!items.length) { return; }
            const anchor = changed ? lastAnchor : emojiAnchor;
            emojiAnchor = null;
            if (changed || dirty.size || needsWindow()) { renderWindow(anchor, changed); }
            else { updateLatest(); remember(); }
            window.JavaClawSurface.highlight(root);
        });
    }
    function updateLatest() {
        if (following) {
            if (latestButton) { latestButton.remove(); latestButton = null; }
        } else if (!latestButton || !latestButton.isConnected) {
            latestButton = document.createElement("button");
            latestButton.className = "new-messages";
            latestButton.textContent = "回到最新消息";
            latestButton.onclick = () => { setFollowing(true); renderWindow(); };
            root.append(latestButton);
        }
    }
    function reconcile(children) {
        const retained = new Set(children);
        for (const child of [...root.childNodes]) { if (!retained.has(child)) { child.remove(); } }
        let cursor = root.firstChild;
        for (const child of children) {
            if (child === cursor) { cursor = cursor.nextSibling; }
            else { root.insertBefore(child, cursor); }
        }
    }
    function spacerHeight(node, value) {
        const pixels = value + "px";
        if (node.style.height !== pixels) { node.style.height = pixels; }
    }
    function layoutKey() {
        const style = getComputedStyle(document.body);
        const card = mounted.length ? getComputedStyle(mounted[0]) : null;
        return [root.clientWidth, style.fontFamily, style.fontSize, style.lineHeight,
            card && card.margin, card && card.padding, card && card.borderWidth].join("|");
    }
    function invalidateLayout() {
        const next = layoutKey();
        if (geometry === next) { return; }
        geometry = next;
        heights.clear();
        mounted.forEach(node => dirty.add(node.dataset.id));
    }
    function measure() {
        for (const node of mounted) {
            if (!dirty.has(node.dataset.id) && heights.has(node.dataset.id)) { continue; }
            const style = getComputedStyle(node);
            // chat 使用不折叠外边距的纵向 flex；占位必须包含此时实际字号/密度对应的两侧外边距。
            heights.set(node.dataset.id, node.getBoundingClientRect().height
                    + parseFloat(style.marginTop) + parseFloat(style.marginBottom));
            dirty.delete(node.dataset.id);
        }
    }
    function windowRange(anchor, rearrange) {
        if (items.length <= 128) { return 0; }
        if (following) { return Math.max(0, items.length - 128); }
        if (!rearrange && mounted.length && !needsWindow()) { return mountedStart; }
        const anchorIndex = anchor ? items.findIndex(item => item.id === anchor.id) : -1;
        if (anchorIndex >= 0) { return Math.max(0, anchorIndex - 32); }
        let start = 0;
        let offset = 0;
        while (start < items.length - 1 && offset + height(items[start]) < window.scrollY - 800) {
            offset += height(items[start++]);
        }
        return start;
    }
    function renderWindow(restored = null, rearrange = false) {
        rendering = true;
        const anchor = restored || (!following ? visibleAnchor() : null);
        if (rearrange) { invalidateLayout(); }
        const start = windowRange(anchor, rearrange);
        const end = Math.min(items.length, start + 128);
        const children = [];
        if (hasEarlier && start === 0) { children.push(earlier); }
        spacerHeight(before, sum(0, start));
        spacerHeight(after, sum(end, items.length));
        children.push(before);
        mounted = [];
        for (let index = start; index < end; index++) {
            const node = create(items[index]); mounted.push(node); children.push(node);
        }
        children.push(after);
        if (!following && latestButton) { children.push(latestButton); }
        reconcile(children);
        mountedStart = start; mountedEnd = end;
        const retained = new Set(mounted.map(node => node.dataset.id));
        for (const id of nodes.keys()) {
            if (!retained.has(id)) { nodes.delete(id); dirty.delete(id); }
        }
        measure();
        spacerHeight(before, sum(0, start));
        spacerHeight(after, sum(end, items.length));
        if (following) { window.scrollTo(0, document.documentElement.scrollHeight); }
        else if (anchor && nodes.has(anchor.id)) {
            const correction = nodes.get(anchor.id).node.getBoundingClientRect().top - anchor.offset;
            if (Math.abs(correction) > 0.5) { window.scrollBy(0, correction); }
        }
        updateLatest();
        root.dataset.heightCacheSize = String(heights.size);
        root.dataset.messageCacheSize = String(items.length);
        if (!geometry || rearrange) { geometry = layoutKey(); }
        rememberGeometry();
        rendering = false;
        remember();
        window.JavaClawSurface.highlight(root);
    }
    function canApply(data, state) {
        if (data.mode !== "patch") {
            return (data.mode === undefined && data.items === undefined)
                    || ((data.mode === undefined || data.mode === "replace") && Array.isArray(data.items));
        }
        if (state.context !== state.identity || data.baseRevision !== state.revision
                || !Array.isArray(data.upserts) || !Array.isArray(data.removedIds)) { return false; }
        const ids = new Set(items.map(item => item.id));
        data.removedIds.forEach(id => ids.delete(id));
        for (const item of data.upserts) { ids.add(item.id); }
        if (data.order) {
            return data.order.length === ids.size && new Set(data.order).size === ids.size
                    && data.order.every(id => ids.has(id));
        }
        return data.upserts.every(item => rows.has(item.id));
    }
    function retainRow(item) {
        const previous = rows.get(item.id);
        if (previous && typeof item.version === "string" && previous.version === item.version) { return previous; }
        if (!nodes.has(item.id)) { heights.delete(item.id); }
        return item;
    }
    function applyRows(data) {
        const previousOrder = items.map(item => item.id);
        if (data.mode === "patch") {
            data.removedIds.forEach(id => rows.delete(id));
            data.upserts.forEach(item => rows.set(item.id, retainRow(item)));
            items = (data.order || previousOrder.filter(id => rows.has(id))).map(id => rows.get(id));
            if (typeof data.hasEarlier === "boolean") { hasEarlier = data.hasEarlier; }
        } else {
            const retained = (data.items || []).map(retainRow);
            rows.clear();
            retained.forEach(item => rows.set(item.id, item));
            items = [...rows.values()];
            hasEarlier = !!data.hasEarlier;
        }
        for (const id of heights.keys()) { if (!rows.has(id)) { heights.delete(id); } }
        for (const id of dirty) { if (!rows.has(id)) { dirty.delete(id); } }
        return previousOrder.length !== items.length || previousOrder.some((id, index) => id !== items[index].id);
    }
    window.addEventListener("wheel", event => {
        if (event.deltaY < 0 && window.scrollY > 0) { setFollowing(false); }
    }, {passive: true});
    window.addEventListener("keydown", event => {
        const upward = ["ArrowUp", "PageUp", "Home"].includes(event.key) || (event.key === " " && event.shiftKey);
        const editing = event.target instanceof Element && event.target.closest("input, textarea, [contenteditable='true']");
        if (upward && window.scrollY > 0 && !editing) { setFollowing(false); }
    });
    window.addEventListener("scroll", () => {
        if (!rendering) { observeScroll(); scheduleRender(); }
    }, {passive: true});
    window.addEventListener("resize", () => scheduleRender(true));
    document.addEventListener("javaclaw-emoji-before-layout", () => {
        if (!following && !emojiAnchor) { emojiAnchor = visibleAnchor(); }
    });
    document.addEventListener("javaclaw-emoji-layout", event => {
        for (const span of event.detail || []) {
            const article = span.closest("article[data-id]");
            if (article) { dirty.add(article.dataset.id); }
        }
        scheduleRender();
    });
    root.addEventListener("load", event => {
        const article = event.target.closest && event.target.closest("article");
        if (article) { dirty.add(article.dataset.id); scheduleRender(); }
    }, true);
    window.JavaClawSurface.register((data, changed, saved) => {
        let restored = null;
        if (changed) {
            nodes.clear(); rows.clear(); heights.clear(); dirty.clear(); mounted = []; items = [];
            following = true; window.scrollTo(0, 0); mountedStart = 0; mountedEnd = 0; latestButton = null;
            emojiAnchor = null;
            if (saved) {
                try {
                    const state = JSON.parse(saved);
                    following = state.following !== false;
                    if (!following && state.anchor && typeof state.anchor.id === "string"
                            && Number.isFinite(state.anchor.offset)) { restored = state.anchor; }
                } catch (ignored) { following = true; }
            }
            rememberGeometry();
        } else { observeScroll(); }
        const incoming = data.mode === "patch" ? data.upserts : data.items;
        // 主动发送立即回到尾部；确认及后台刷新不能反复打断用户随后开始的阅读。
        const submitted = (incoming || []).some(item => item.outgoing && (!rows.has(item.id)
                || item.sendAttempt !== rows.get(item.id).sendAttempt));
        if (submitted && (!changed || !saved)) { following = true; restored = null; }
        const reordered = applyRows(data);
        if (!items.length) {
            root.innerHTML = '<div class="welcome"><span>✦</span><strong>有什么我可以帮你的？</strong><span class="muted">对话、规划与执行，都从这里开始。</span></div>';
            nodes.clear(); mounted = []; mountedStart = 0; mountedEnd = 0; latestButton = null;
            rememberGeometry();
        } else { renderWindow(restored, reordered); }
    }, () => scheduleRender(true), canApply);
})();
