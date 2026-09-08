/* 应用拥有的单入口协议；正文永远无法提供事件处理器或 Java 调用。 */
"use strict";
window.JavaClawSurface = (() => {
    let generation = 0;
    let context = "";
    let revision = 0;
    let renderer = () => {};
    let themes = () => {};
    function post(action, value = "") {
        window.javaClawBridge.post(JSON.stringify({action, generation, context, revision, value: String(value)}));
    }
    function register(render, theme = () => {}) {
        renderer = render;
        themes = theme;
    }
    function bootstrap(value) {
        generation = value;
        post("ready");
    }
    function apply(identity, version, data, state = "") {
        try {
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
        } catch (error) {
            post("error", "内容显示失败");
        }
    }
    function theme(values) {
        for (const [key, value] of Object.entries(values)) {
            document.documentElement.style.setProperty("--" + key, value);
        }
        themes(values);
    }
    function highlight(root) {
        const languages = new Set(["java", "javascript", "typescript", "python", "bash", "sh", "json", "xml", "html", "css", "sql", "yaml", "markdown", "diff", "plaintext"]);
        root.querySelectorAll("pre code").forEach(code => {
            const language = [...code.classList].find(name => name.startsWith("language-"));
            const name = language ? language.substring(9).toLowerCase() : "plaintext";
            if (code.textContent.length <= 65536 && languages.has(name) && window.hljs.getLanguage(name)) {
                code.innerHTML = window.hljs.highlight(code.textContent, {language: name, ignoreIllegals: true}).value;
            }
        });
    }
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
