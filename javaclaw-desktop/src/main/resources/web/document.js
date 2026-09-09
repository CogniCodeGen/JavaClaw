/* 源码行号与分页来自Java解码状态；图片仅接受已验证的内存资源。 */
"use strict";
window.JavaClawSurface.register(data => {
    const root = document.getElementById("surface");
    root.className = "document";
    root.replaceChildren();
    if (data.image) {
        const image = document.createElement("img");
        image.src = data.image;
        image.alt = data.title || "图片";
        root.append(image);
    } else if (data.html) {
        root.innerHTML = data.html;
        window.JavaClawSurface.highlight(root);
    } else {
        const code = document.createElement("div");
        let highlightBudget = 65536;
        for (const line of data.lines || []) {
            const row = document.createElement("div");
            row.className = "source-line" + (line.number === data.targetLine ? " target-line" : "");
            const number = document.createElement("span");
            number.className = "line-number";
            number.textContent = line.continued ? "↳" : line.number;
            const value = document.createElement("code");
            value.className = "source-code";
            value.textContent = line.text;
            const languages = new Set(["java", "json", "xml", "css", "sql", "diff", "javascript", "typescript", "python", "bash", "yaml"]);
            if (line.text.length * 2 <= highlightBudget && languages.has(data.language) && window.hljs.getLanguage(data.language)) {
                highlightBudget -= line.text.length * 2;
                value.innerHTML = window.hljs.highlight(line.text, {language: data.language, ignoreIllegals: true}).value;
            }
            row.append(number, value);
            code.append(row);
        }
        root.append(code);
    }
    window.JavaClawEmoji.decorate(root);
    const target = root.querySelector(".target-line");
    if (target) { target.scrollIntoView({block: "center"}); }
});
