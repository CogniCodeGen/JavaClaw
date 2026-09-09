/* 原生字形只覆盖绘制；DOM 始终保留原始 Unicode，复制和引用不经过图像转换。 */
"use strict";
window.JavaClawEmoji = (() => {
    const cache = new Map();
    const pending = new Set();
    const failed = new Set();
    const maximumBytes = 2 * 1024 * 1024;
    const maximumSpans = 4096;
    let bytes = 0;
    let inFlight = [];
    let enabled = false;
    let scheduled = false;
    let segmenter = null;
    let emojiPattern = null;
    try {
        segmenter = new Intl.Segmenter(undefined, {granularity: "grapheme"});
        emojiPattern = new RegExp("[\\p{Emoji_Presentation}\\p{Regional_Indicator}\\p{Emoji_Modifier}]|\\uFE0F|\\u20E3", "u");
    } catch (ignored) {
        // 缺少字素分段能力时保留系统文本，不能把未知序列拆成不同含义的图形。
    }
    function configure(nativeEnabled) {
        enabled = nativeEnabled === true && segmenter !== null && emojiPattern !== null;
        if (enabled) { schedule(); }
    }
    function candidate(text) {
        if (!text || text.length > 64 || text.includes("\uFE0E") || !emojiPattern.test(text)) { return false; }
        for (const character of text) {
            const point = character.codePointAt(0);
            if (point >= 0xD800 && point <= 0xDFFF) { return false; }
        }
        return true;
    }
    function cached(cluster) {
        const value = cache.get(cluster);
        if (value) { cache.delete(cluster); cache.set(cluster, value); }
        return value;
    }
    function paint(span, value) {
        if (!value || span.dataset.native === "true") { return false; }
        span.style.backgroundImage = 'url("' + value.dataUri + '")';
        span.style.width = value.widthEm + "em";
        span.style.height = value.heightEm + "em";
        span.style.verticalAlign = value.verticalAlignEm + "em";
        span.classList.add("emoji-native");
        span.dataset.native = "true";
        return true;
    }
    function glyph(cluster) {
        const outer = document.createElement("span");
        outer.className = "emoji-glyph";
        const original = document.createElement("span");
        original.className = "emoji-original";
        original.textContent = cluster;
        outer.append(original);
        paint(outer, cached(cluster));
        schedule();
        return outer;
    }
    function appendPlain(parent, source, state, offset, lastIndex) {
        if (!source) { return; }
        let plain = parent.lastChild;
        if (!plain || plain.nodeType !== Node.TEXT_NODE) {
            plain = document.createTextNode("");
            parent.append(plain);
        }
        if (state) {
            state.tailNode = plain;
            state.tailOffset = plain.length + lastIndex;
            state.tailStart = offset + lastIndex;
        }
        plain.appendData(source);
    }
    function trailingHighSurrogate(text) {
        const last = text.charCodeAt(text.length - 1);
        return last >= 0xD800 && last <= 0xDBFF;
    }
    function lastBoundary(source, parts = segmenter.segment(source)) {
        if (!source) { return 0; }
        if (typeof parts.containing === "function") {
            const last = parts.containing(source.length - 1).index;
            return trailingHighSurrogate(source) && last > 0 ? parts.containing(last - 1).index : last;
        }
        let previous = 0;
        let last = 0;
        for (const part of parts) { previous = last; last = part.index; }
        return trailingHighSurrogate(source) ? previous : last;
    }
    function appendSegments(parent, source, state, offset, budget = maximumSpans) {
        if (!source) { return; }
        const parts = segmenter.segment(source);
        if (!emojiPattern.test(source)) {
            appendPlain(parent, source, state, offset, state ? lastBoundary(source, parts) : 0);
            return;
        }
        let glyphCount = 0;
        let plainStart = 0;
        let lastPlainIndex = 0;
        let previousPart = null;
        let delayedTail = null;
        for (const part of parts) {
            const text = part.segment;
            if (candidate(text) && glyphCount < budget && parent.childNodes.length < maximumSpans) {
                // 普通连续正文只写入一次 DOM；长片段不能按字素产生数万次 appendData。
                appendPlain(parent, source.substring(plainStart, part.index), state,
                    offset + plainStart, lastPlainIndex - plainStart);
                const span = glyph(text);
                parent.append(span);
                glyphCount++;
                if (state) { state.tailNode = span; state.tailOffset = 0; state.tailStart = offset + part.index; }
                plainStart = part.index + text.length;
                previousPart = {index: part.index, span};
            } else {
                const incomplete = text.length === 1 && part.index + 1 === source.length && trailingHighSurrogate(text);
                if (incomplete && previousPart && previousPart.span) {
                    delayedTail = {node: previousPart.span, start: offset + previousPart.index};
                }
                lastPlainIndex = incomplete && previousPart && !previousPart.span ? previousPart.index : part.index;
                previousPart = {index: part.index};
            }
        }
        appendPlain(parent, source.substring(plainStart), state, offset + plainStart, lastPlainIndex - plainStart);
        if (state && delayedTail) {
            // 高代理后续可能是肤色或国旗的低代理，下一块必须把它前面的完整字素也重新合并。
            state.tailNode = delayedTail.node;
            state.tailOffset = 0;
            state.tailStart = delayedTail.start;
        }
    }
    function createText(parent) {
        const node = document.createTextNode("");
        parent.append(node);
        parent.dataset.emojiText = "true";
        return {parent, data: "", tailStart: 0, tailNode: node, tailOffset: 0};
    }
    function retainPrefix(parent, length) {
        let offset = 0;
        for (const node of [...parent.childNodes]) {
            const end = offset + node.textContent.length;
            if (length < end || (length === end && node.nodeType === Node.TEXT_NODE)) {
                while (node.nextSibling) { node.nextSibling.remove(); }
                if (node.nodeType === Node.TEXT_NODE) { node.deleteData(length - offset, end - length); }
                else { node.remove(); }
                return;
            }
            offset = end;
        }
    }
    function appendText(state, text) {
        if (state.data === text) { return; }
        if (!enabled) {
            const node = state.parent.firstChild;
            if (text.startsWith(state.data)) { node.appendData(text.substring(state.data.length)); }
            else { node.data = text; }
            state.data = text;
            return;
        }
        const extending = text.startsWith(state.data);
        const truncating = !extending && state.data.startsWith(text);
        const start = extending ? state.tailStart : truncating ? lastBoundary(text) : 0;
        if (extending && state.tailNode && state.tailNode.parentNode === state.parent) {
            const tail = state.tailNode;
            while (tail.nextSibling) { tail.nextSibling.remove(); }
            if (tail.nodeType === Node.TEXT_NODE) { tail.deleteData(state.tailOffset, tail.length - state.tailOffset); }
            else { tail.remove(); }
        } else if (truncating) {
            // 截断只改最后的字素及之后的节点，稳定前缀继续承载选区和已有 DOM 身份。
            retainPrefix(state.parent, start);
        } else {
            state.parent.replaceChildren();
        }
        state.tailStart = start;
        state.tailNode = null;
        state.tailOffset = 0;
        // 只重分段上一块最后的字素簇，允许肤色、ZWJ、旗帜及代理对继续扩展它。
        appendSegments(state.parent, text.substring(start), state, start);
        state.data = text;
    }
    function decorate(root) {
        if (!enabled || !root) { return; }
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
            acceptNode(node) {
                const parent = node.parentElement;
                return parent && !parent.closest(".emoji-glyph, [data-emoji-text], script, style, textarea, input")
                        && emojiPattern.test(node.data) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
            }
        });
        const selected = window.getSelection();
        const range = selected && !selected.isCollapsed && selected.rangeCount ? selected.getRangeAt(0) : null;
        const texts = [];
        let node;
        while (texts.length < maximumSpans && (node = walker.nextNode())) {
            // 已选中的旧文本暂不拆节点；下一次内容或选择事件会重新尝试。
            if (!range || !range.intersectsNode(node)) { texts.push(node); }
        }
        let remaining = Math.max(0, maximumSpans - root.querySelectorAll(".emoji-glyph").length);
        for (const text of texts) {
            if (remaining <= 0) { break; }
            const fragment = document.createDocumentFragment();
            appendSegments(fragment, text.data, null, 0, remaining);
            remaining -= fragment.querySelectorAll(".emoji-glyph").length;
            text.replaceWith(fragment);
        }
        schedule();
    }
    function schedule() {
        if (!enabled || scheduled) { return; }
        scheduled = true;
        requestAnimationFrame(() => { scheduled = false; refresh(true); });
    }
    function visible(span) {
        const bounds = span.getBoundingClientRect();
        return bounds.bottom >= -300 && bounds.top <= innerHeight + 300;
    }
    function refresh(enqueue) {
        if (!enabled) { return; }
        const changed = [];
        const spans = document.querySelectorAll(".emoji-glyph");
        for (let index = 0; index < Math.min(spans.length, maximumSpans); index++) {
            const span = spans[index];
            if (span.dataset.native === "true") { continue; }
            const cluster = span.textContent;
            const value = cached(cluster);
            if (value) { changed.push([span, value]); }
            else if (enqueue && span.dataset.failed !== "true" && pending.size < 128 && !failed.has(cluster)
                    && !inFlight.includes(cluster) && visible(span)) { pending.add(cluster); }
        }
        if (changed.length) {
            document.dispatchEvent(new CustomEvent("javaclaw-emoji-before-layout"));
            changed.forEach(([span, value]) => paint(span, value));
            document.dispatchEvent(new CustomEvent("javaclaw-emoji-layout", {detail: changed.map(value => value[0])}));
            updateSelection();
        }
        dispatch();
    }
    function dispatch() {
        if (!enabled || inFlight.length || !pending.size) { return; }
        inFlight = [...pending].slice(0, 32);
        inFlight.forEach(cluster => pending.delete(cluster));
        window.JavaClawSurface.post("emoji", JSON.stringify({clusters: inFlight}));
    }
    function valid(value) {
        return value && typeof value.dataUri === "string"
                && value.dataUri.length <= 131072 && /^data:image\/png;base64,[A-Za-z0-9+/=]+$/.test(value.dataUri)
                && Number.isFinite(value.widthEm) && value.widthEm > 0 && value.widthEm <= 8
                && Number.isFinite(value.heightEm) && value.heightEm > 0 && value.heightEm <= 8
                && Number.isFinite(value.verticalAlignEm) && Math.abs(value.verticalAlignEm) <= 8;
    }
    function install(values) {
        if (!enabled) { return; }
        const missing = new Set();
        for (const cluster of inFlight) {
            const value = values && Object.prototype.hasOwnProperty.call(values, cluster) ? values[cluster] : null;
            if (!valid(value)) {
                if (failed.size >= 128) { failed.delete(failed.values().next().value); }
                failed.add(cluster);
                missing.add(cluster);
                continue;
            }
            const size = (cluster.length + value.dataUri.length) * 2;
            while (cache.size >= 128 || bytes + size > maximumBytes) {
                const oldest = cache.keys().next().value;
                bytes -= cache.get(oldest).bytes;
                cache.delete(oldest);
            }
            cache.set(cluster, {...value, bytes: size});
            bytes += size;
        }
        const spans = document.querySelectorAll(".emoji-glyph");
        for (let index = 0; index < Math.min(spans.length, maximumSpans); index++) {
            if (missing.has(spans[index].textContent)) { spans[index].dataset.failed = "true"; }
        }
        inFlight = [];
        // 完成一批才推进下一批；缺项节点保留可见原文，不为同一失败字形反复唤醒。
        refresh(true);
    }
    function updateSelection() {
        const selected = window.getSelection();
        const range = selected && !selected.isCollapsed && selected.rangeCount ? selected.getRangeAt(0) : null;
        const spans = document.querySelectorAll(".emoji-native");
        for (let index = 0; index < Math.min(spans.length, maximumSpans); index++) {
            const span = spans[index];
            span.classList.toggle("emoji-selected", !!range && range.intersectsNode(span));
        }
    }
    document.addEventListener("selectionchange", () => {
        if (!enabled) { return; }
        updateSelection();
        const selected = window.getSelection();
        if (!selected || selected.isCollapsed) { decorate(document.getElementById("surface")); }
    });
    document.addEventListener("scroll", schedule, {capture: true, passive: true});
    return {configure, decorate, createText, appendText, install};
})();
