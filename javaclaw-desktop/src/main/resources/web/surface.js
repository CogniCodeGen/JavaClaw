/* 应用拥有的单入口协议；正文永远无法提供事件处理器或 Java 调用。 */
"use strict";
window.JavaClawSurface = (() => {
    let generation = 0;
    let context = "";
    let revision = 0;
    let renderer = () => {};
    let themes = () => {};
    let canApply = () => true;
    function post(action, value = "") {
        window.javaClawBridge.post(JSON.stringify({action, generation, context, revision, value: String(value)}));
    }
    function register(render, theme = () => {}, preflight = () => true) {
        renderer = render;
        themes = theme;
        canApply = preflight;
    }
    function bootstrap(value, nativeEmoji = false) {
        generation = value;
        window.JavaClawEmoji.configure(nativeEmoji);
        post("ready");
    }
    function apply(identity, version, data, state = "") {
        try {
            if (!canApply(data, {context, revision, identity, version})) { return false; }
            const changed = context !== identity;
            context = identity;
            revision = version;
            renderer(data, changed, state);
            const expected = revision;
            requestAnimationFrame(() => requestAnimationFrame(() => {
                if (expected === revision && document.documentElement.clientWidth > 0) {
                    post("ack");
                }
            }));
            return true;
        } catch (error) {
            post("error", "内容显示失败");
            return false;
        }
    }
    function theme(values) {
        for (const [key, value] of Object.entries(values)) {
            document.documentElement.style.setProperty("--" + key, value);
        }
        themes(values);
    }
    const languages = new Set(["java", "javascript", "typescript", "python", "bash", "sh", "json", "xml", "html", "css", "sql", "yaml", "markdown", "diff", "plaintext"]);
    const highlighted = new WeakMap();
    const highlightQueue = new Set();
    const highlightRoots = new Set();
    const selectedCodes = new Set();
    const tokenCache = new Map();
    const maximumTokenBytes = 2 * 1024 * 1024;
    let tokenBytes = 0;
    let highlightScheduled = false;
    function languageOf(code) {
        const language = [...code.classList].find(name => name.startsWith("language-"));
        return language ? language.substring(9).toLowerCase() : "plaintext";
    }
    function highlight(root) {
        if (root.id === "surface") { highlightRoots.clear(); }
        if (highlightRoots.size < 128) { highlightRoots.add(root); }
        scheduleHighlights();
    }
    function scheduleHighlights() {
        if (!highlightScheduled && (highlightQueue.size || highlightRoots.size)) {
            highlightScheduled = true;
            requestAnimationFrame(drainHighlights);
        }
    }
    function drainHighlights() {
        highlightScheduled = false;
        for (const root of highlightRoots) {
            if (!root.isConnected) { continue; }
            root.querySelectorAll("pre code").forEach(code => {
                if (highlightQueue.size < 256 && !highlighted.has(code)
                        && !code.closest('article[data-streaming="true"]') && codeVisible(code)) {
                    highlightQueue.add(code);
                }
            });
        }
        highlightRoots.clear();
        const started = performance.now();
        let rendered = 0;
        let remainingVisible = false;
        for (const code of highlightQueue) {
            if (!code.isConnected) { highlightQueue.delete(code); continue; }
            if (!codeVisible(code)) { highlightQueue.delete(code); continue; }
            // 单次词法分析不可中断；两块与4ms是帧间软预算，离屏任务等待滚动唤醒而不空转。
            if (rendered >= 2 || performance.now() - started >= 4) { remainingVisible = true; break; }
            highlightQueue.delete(code);
            highlightCode(code);
            rendered++;
        }
        if (remainingVisible) { scheduleHighlights(); }
    }
    function codeVisible(code) {
        const bounds = code.getBoundingClientRect();
        return bounds.bottom >= 0 && bounds.top <= innerHeight && bounds.right >= 0 && bounds.left <= innerWidth;
    }
    function highlightCode(code) {
        if (highlighted.has(code)) { return; }
        const selection = window.getSelection();
        if (selection && !selection.isCollapsed && selection.rangeCount
                && selection.getRangeAt(0).intersectsNode(code)) {
            // 选区里的代码保留原节点，等选区变化再尝试；不为等待用户操作持续唤醒。
            if (selectedCodes.size < 256) { selectedCodes.add(code); }
            return;
        }
        const text = code.textContent;
        const language = languageOf(code);
        highlighted.set(code, true);
        if (text.length > 65536 || !languages.has(language) || !window.hljs.getLanguage(language)) { return; }
        const key = language + "\u0000" + text;
        let cached = tokenCache.get(key);
        if (cached) {
            tokenCache.delete(key);
            tokenCache.set(key, cached);
        } else {
            const html = window.hljs.highlight(text, {language, ignoreIllegals: true}).value;
            cached = {html, bytes: (key.length + html.length) * 2};
            if (cached.bytes <= maximumTokenBytes) {
                while (tokenCache.size >= 128 || tokenBytes + cached.bytes > maximumTokenBytes) {
                    const oldest = tokenCache.keys().next().value;
                    tokenBytes -= tokenCache.get(oldest).bytes;
                    tokenCache.delete(oldest);
                }
                tokenCache.set(key, cached);
                tokenBytes += cached.bytes;
            }
        }
        const pre = code.closest("pre");
        const scrollLeft = pre ? pre.scrollLeft : 0;
        code.innerHTML = cached.html;
        window.JavaClawEmoji.decorate(code);
        if (pre) { pre.scrollLeft = scrollLeft; }
    }
    document.addEventListener("selectionchange", () => {
        if (!selectedCodes.size) { return; }
        for (const code of selectedCodes) {
            if (code.isConnected && highlightQueue.size < 256) { highlightQueue.add(code); }
        }
        selectedCodes.clear();
        scheduleHighlights();
    });
    document.addEventListener("scroll", () => {
        const root = document.getElementById("surface");
        if (root) { highlight(root); }
    }, {capture: true, passive: true});
    document.addEventListener("click", event => {
        const link = event.target.closest("a[data-link]");
        if (link) {
            event.preventDefault();
            post("link", link.dataset.link);
        }
    });
    document.addEventListener("keydown", event => {
        const link = event.target.closest("a[data-link]");
        if (link && event.key === "Enter") { event.preventDefault(); post("link", link.dataset.link); }
    });
    window.addEventListener("error", () => post("error", "页面运行失败"));
    return {bootstrap, apply, theme, register, post, highlight};
})();
