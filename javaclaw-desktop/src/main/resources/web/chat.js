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
    let layoutPending = false;
    let mountedStart = 0;
    let mountedEnd = 0;
    let latestButton = null;
    let lastScrollY = 0;
    let lastWidth = window.innerWidth;
    let lastHeight = window.innerHeight;
    let lastDocumentHeight = 0;
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
                const link = document.createElement("a");
                link.className = "attachment";
                link.href = "#";
                link.textContent = ref.label;
                link.addEventListener("click", event => {
                    event.preventDefault();
                    window.JavaClawSurface.post("preview", ref.id);
                });
                attachments.append(link);
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
    function setFollowing(value) {
        if (following !== value) {
            following = value;
            window.JavaClawSurface.post("following", following);
        }
    }
    function observeScroll() {
        const top = Math.max(0, window.scrollY);
        const movement = top - lastScrollY;
        const layoutChanged = lastWidth !== window.innerWidth || lastHeight !== window.innerHeight
                || lastDocumentHeight !== document.documentElement.scrollHeight;
        // 任何真实上移都退出跟随，不能用底部附近的宽容区吞掉触控板的小步输入。
        // 窗口或字体变化会自动夹紧 scrollY；这不是用户改变方向，输入意图已由 wheel/keydown 单独记录。
        if (!layoutChanged && movement < -0.5) { setFollowing(false); }
        else if (!layoutChanged && movement > 0.5
                && document.documentElement.scrollHeight - top - window.innerHeight <= 2) {
            setFollowing(true);
        }
        rememberGeometry();
    }
    function rememberGeometry() {
        lastScrollY = window.scrollY;
        lastWidth = window.innerWidth;
        lastHeight = window.innerHeight;
        lastDocumentHeight = document.documentElement.scrollHeight;
    }
    function scheduleRender(layout = false) {
        layoutPending = layoutPending || layout;
        if (!scheduled && items.length) {
            scheduled = true;
            requestAnimationFrame(() => {
                scheduled = false;
                const changed = layoutPending;
                layoutPending = false;
                if (!items.length) { return; }
                if (changed || needsWindow()) { renderWindow(); }
                else { updateLatest(); remember(); }
            });
        }
    }
    function needsWindow() {
        if (items.length <= 128) { return false; }
        const articles = root.querySelectorAll("article");
        if (!articles.length) { return true; }
        // 缓冲区内只让 WebKit 滚动；反复移除节点或写 scrollY 会中断原生惯性动画。
        return (mountedStart > 0 && articles[0].getBoundingClientRect().top > -400)
                || (mountedEnd < items.length
                        && articles[articles.length - 1].getBoundingClientRect().bottom < window.innerHeight + 400);
    }
    function updateLatest() {
        if (following) {
            if (latestButton) { latestButton.remove(); }
            latestButton = null;
        } else if (!latestButton || !latestButton.isConnected) {
            latestButton = document.createElement("button");
            latestButton.className = "new-messages";
            latestButton.textContent = "回到最新消息";
            latestButton.onclick = () => {
                setFollowing(true);
                renderWindow();
            };
            root.append(latestButton);
        }
    }
    function reconcile(children) {
        const retained = new Set(children);
        // 流式更新也保留未变化消息的节点，只插入、替换或移除实际发生变化的内容。
        for (const child of [...root.childNodes]) {
            if (!retained.has(child)) { child.remove(); }
        }
        let cursor = root.firstChild;
        for (const child of children) {
            if (child === cursor) { cursor = cursor.nextSibling; }
            else { root.insertBefore(child, cursor); }
        }
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
        if (following) {
            start = Math.max(0, items.length - 128);
            offset = items.slice(0, start).reduce((total, item) => total + height(item), 0);
        }
        // 插入更早历史或重建 WebKit 后仍以消息身份定位，不能只复用估算 scrollY。
        const anchorIndex = following || !anchorId ? -1 : items.findIndex(item => item.id === anchorId);
        // 短对话始终保留全部节点，避免占位高度与锚点修正干扰原生滚轮动画。
        if (items.length <= 128) {
            start = 0;
            offset = 0;
        } else if (anchorIndex >= 0) {
            start = Math.max(0, anchorIndex - 32);
            offset = items.slice(0, start).reduce((total, item) => total + height(item), 0);
        }
        const end = Math.min(items.length, start + 128);
        const children = [];
        if (hasEarlier && start === 0) {
            const earlier = document.createElement("button");
            earlier.className = "history";
            earlier.textContent = "加载更早消息";
            earlier.onclick = () => window.JavaClawSurface.post("history");
            children.push(earlier);
        }
        const before = document.createElement("div");
        before.style.height = offset + "px";
        children.push(before);
        for (let index = start; index < end; index++) { children.push(create(items[index])); }
        const after = document.createElement("div");
        after.style.height = items.slice(end).reduce((total, item) => total + height(item), 0) + "px";
        children.push(after);
        if (!following && latestButton) { children.push(latestButton); }
        reconcile(children);
        mountedStart = start;
        mountedEnd = end;
        const mounted = new Set(items.slice(start, end).map(item => item.id));
        for (const id of nodes.keys()) { if (!mounted.has(id)) { nodes.delete(id); } }
        root.querySelectorAll("article").forEach(node => heights.set(node.dataset.id, node.getBoundingClientRect().height + 32));
        if (following) { window.scrollTo(0, document.documentElement.scrollHeight); }
        else if (anchorId) {
            const current = [...root.querySelectorAll("article")].find(node => node.dataset.id === anchorId);
            if (current) {
                const correction = current.getBoundingClientRect().top - anchorOffset;
                if (Math.abs(correction) > 0.5) { window.scrollBy(0, correction); }
            }
        }
        updateLatest();
        root.dataset.heightCacheSize = String(heights.size);
        root.dataset.messageCacheSize = String(items.length);
        // 锚点修正及自动跟随产生的异步 scroll 事件，不得被当成用户改变阅读方向。
        rememberGeometry();
        rendering = false;
        remember();
    }
    window.addEventListener("wheel", event => {
        // wheel 先于默认滚动发生；同一帧的新快照也必须让位于用户上滚意图。
        if (event.deltaY < 0 && window.scrollY > 0) { setFollowing(false); }
    }, {passive: true});
    window.addEventListener("keydown", event => {
        const upward = ["ArrowUp", "PageUp", "Home"].includes(event.key) || (event.key === " " && event.shiftKey);
        const editing = event.target instanceof Element && event.target.closest("input, textarea, [contenteditable='true']");
        if (upward && window.scrollY > 0 && !editing) {
            setFollowing(false);
        }
    });
    window.addEventListener("scroll", () => {
        if (rendering) { return; }
        observeScroll();
        scheduleRender();
    }, {passive: true});
    window.addEventListener("resize", () => scheduleRender(true));
    window.JavaClawSurface.register((data, changed, saved) => {
        let restored = null;
        if (changed) {
            nodes.clear(); heights.clear(); following = true; window.scrollTo(0, 0);
            mountedStart = 0; mountedEnd = 0;
            latestButton = null;
            rememberGeometry();
            if (saved) {
                try {
                    const state = JSON.parse(saved);
                    following = state.following !== false;
                    if (!following && state.anchor && typeof state.anchor.id === "string"
                            && Number.isFinite(state.anchor.offset)) { restored = state.anchor; }
                } catch (ignored) { following = true; }
            }
        } else { observeScroll(); }
        items = data.items || [];
        hasEarlier = !!data.hasEarlier;
        const ids = new Set(items.map(item => item.id));
        for (const id of nodes.keys()) { if (!ids.has(id)) { nodes.delete(id); } }
        for (const id of heights.keys()) { if (!ids.has(id)) { heights.delete(id); } }
        if (!items.length) {
            root.innerHTML = '<div class="welcome"><span>✦</span><strong>有什么我可以帮你的？</strong><span class="muted">对话、规划与执行，都从这里开始。</span></div>';
            rememberGeometry();
        } else { renderWindow(restored); }
    }, () => scheduleRender(true));
})();
