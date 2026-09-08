/* 页面只渲染窗口附近最多128条消息，保留锚点；历史业务由Java Presenter分页。 */
"use strict";
(() => {
    const root = document.getElementById("surface");
    const heights = new Map();
    const nodes = new Map();
    let items = [];
    let following = true;
    let rendering = false;
    let hasEarlier = false;
    let scheduled = false;
    function height(item) { return heights.get(item.id) || 140; }
    function create(item) {
        const old = nodes.get(item.id);
        if (old && old.dataset.version === item.version) { return old; }
        const article = document.createElement("article");
        article.className = "message " + item.style;
        article.dataset.id = item.id;
        article.dataset.version = item.version;
        const role = document.createElement("div");
        role.className = "role";
        role.textContent = item.title;
        const body = document.createElement("div");
        if (item.html) { body.innerHTML = item.html; } else { body.className = "plain"; body.textContent = item.text; }
        article.append(role, body);
        if (!item.streaming) { window.JavaClawSurface.highlight(body); }
        if (item.references && item.references.length) {
            const attachments = document.createElement("div");
            attachments.className = "attachments";
            for (const ref of item.references) {
                const button = document.createElement("button");
                button.className = "attachment";
                button.textContent = ref.label;
                button.addEventListener("click", () => window.JavaClawSurface.post("preview", ref.id));
                attachments.append(button);
            }
            article.append(attachments);
        }
        nodes.set(item.id, article);
        return article;
    }
    function visibleAnchor() {
        const node = [...root.querySelectorAll("article")].find(value => {
            const bounds = value.getBoundingClientRect();
            return bounds.bottom > 0 && bounds.top < window.innerHeight;
        });
        return node ? {id: node.dataset.id, offset: node.getBoundingClientRect().top} : null;
    }
    function remember() {
        window.JavaClawSurface.post("viewState", JSON.stringify({following, anchor: visibleAnchor()}));
    }
    function renderWindow(restored = null) {
        rendering = true;
        const top = window.scrollY;
        const anchor = restored || visibleAnchor();
        const anchorId = anchor && anchor.id;
        const anchorOffset = anchor ? anchor.offset : 0;
        let offset = 0;
        let start = 0;
        while (start < items.length - 1 && offset + height(items[start]) < top - 800) { offset += height(items[start++]); }
        // 插入更早历史或重建 WebKit 后仍以消息身份定位，不能只复用估算 scrollY。
        const anchorIndex = following || !anchorId ? -1 : items.findIndex(item => item.id === anchorId);
        if (anchorIndex >= 0) {
            start = Math.max(0, anchorIndex - 5);
            offset = items.slice(0, start).reduce((total, item) => total + height(item), 0);
        }
        const end = Math.min(items.length, start + 128);
        const fragment = document.createDocumentFragment();
        if (hasEarlier && start === 0) {
            const earlier = document.createElement("button");
            earlier.className = "history";
            earlier.textContent = "加载更早消息";
            earlier.onclick = () => window.JavaClawSurface.post("history");
            fragment.append(earlier);
        }
        const before = document.createElement("div");
        before.style.height = offset + "px";
        fragment.append(before);
        for (let index = start; index < end; index++) { fragment.append(create(items[index])); }
        const after = document.createElement("div");
        after.style.height = items.slice(end).reduce((total, item) => total + height(item), 0) + "px";
        fragment.append(after);
        root.replaceChildren(fragment);
        const mounted = new Set(items.slice(start, end).map(item => item.id));
        for (const id of nodes.keys()) { if (!mounted.has(id)) { nodes.delete(id); } }
        root.querySelectorAll("article").forEach(node => heights.set(node.dataset.id, node.getBoundingClientRect().height + 32));
        if (following) { window.scrollTo(0, document.documentElement.scrollHeight); }
        else if (anchorId) {
            const current = [...root.querySelectorAll("article")].find(node => node.dataset.id === anchorId);
            if (current) { window.scrollBy(0, current.getBoundingClientRect().top - anchorOffset); }
        }
        if (!following) {
            const recent = document.createElement("button");
            recent.className = "new-messages";
            recent.textContent = "回到最新消息";
            recent.onclick = () => {
                following = true;
                window.JavaClawSurface.post("following", "true");
                window.scrollTo(0, document.documentElement.scrollHeight);
                renderWindow();
            };
            root.append(recent);
        }
        root.dataset.heightCacheSize = String(heights.size);
        root.dataset.messageCacheSize = String(items.length);
        rendering = false;
        remember();
    }
    window.addEventListener("scroll", () => {
        if (rendering) { return; }
        const next = document.documentElement.scrollHeight - window.scrollY - window.innerHeight < 70;
        if (next !== following) { following = next; window.JavaClawSurface.post("following", following); }
        if (!scheduled) { scheduled = true; requestAnimationFrame(() => { scheduled = false; renderWindow(); }); }
    }, {passive: true});
    window.JavaClawSurface.register((data, changed, saved) => {
        let restored = null;
        if (changed) {
            nodes.clear(); heights.clear(); following = true; window.scrollTo(0, 0);
            if (saved) {
                try {
                    const state = JSON.parse(saved);
                    following = state.following !== false;
                    if (!following && state.anchor && typeof state.anchor.id === "string"
                            && Number.isFinite(state.anchor.offset)) { restored = state.anchor; }
                } catch (ignored) { following = true; }
            }
        }
        items = data.items || [];
        hasEarlier = !!data.hasEarlier;
        const ids = new Set(items.map(item => item.id));
        for (const id of nodes.keys()) { if (!ids.has(id)) { nodes.delete(id); } }
        for (const id of heights.keys()) { if (!ids.has(id)) { heights.delete(id); } }
        if (!items.length) {
            root.innerHTML = '<div class="welcome"><span>✦</span><strong>有什么我可以帮你的？</strong><span class="muted">对话、规划与执行，都从这里开始。</span></div>';
        } else { renderWindow(restored); }
    });
})();
