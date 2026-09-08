/* Cytoscape属于平台；扩展只提供经过Java校验的节点/边，不能指定样式、布局或事件。 */
"use strict";
(() => {
    let graph;
    let colors = {};
    function styles() {
        // Canvas 不继承 DOM 字体，必须显式使用同一主题探针，才能随字号预览和取消一起更新。
        const fontSize = parseFloat(colors.fontSize) || 13;
        const fontFamily = getComputedStyle(document.body).fontFamily;
        return [
            {selector: "node", style: {"label": "data(label)", "background-color": colors.primary, "color": colors.body, "font-size": fontSize, "font-family": fontFamily, "text-valign": "bottom", "text-margin-y": 7, "width": 24, "height": 24}},
            {selector: "edge", style: {"width": 1, "line-color": colors.border, "target-arrow-color": colors.border, "target-arrow-shape": "triangle", "curve-style": "bezier"}},
            {selector: ":selected", style: {"border-width": 3, "border-color": colors.warning}}
        ];
    }
    window.JavaClawSurface.register((data, changed) => {
        if (!graph || changed) {
            if (graph) { graph.destroy(); }
            const root = document.getElementById("surface");
            const container = document.createElement("div");
            container.id = "graph";
            root.replaceChildren(container);
            graph = cytoscape({container, elements: [], style: styles(), layout: {name: "preset"}, minZoom: .2, maxZoom: 3});
            // JavaFX26 的 Path2D/ellipse 可能产生空路径；用普通 Bézier 路径，由原渲染器负责填充和边框。
            const renderer = graph.renderer();
            renderer.path2dEnabled(false);
            renderer.drawEllipsePath = (ctx, x, y, width, height) => {
                const rx = width / 2;
                const ry = height / 2;
                const kx = rx * .5522847498307936;
                const ky = ry * .5522847498307936;
                ctx.beginPath();
                ctx.moveTo(x + rx, y);
                ctx.bezierCurveTo(x + rx, y + ky, x + kx, y + ry, x, y + ry);
                ctx.bezierCurveTo(x - kx, y + ry, x - rx, y + ky, x - rx, y);
                ctx.bezierCurveTo(x - rx, y - ky, x - kx, y - ry, x, y - ry);
                ctx.bezierCurveTo(x + kx, y - ry, x + rx, y - ky, x + rx, y);
                ctx.closePath();
            };
            graph.on("tap", "node", event => window.JavaClawSurface.post("select", event.target.id()));
        }
        const wanted = new Set((data.elements || []).map(element => element.data.id));
        const previous = new Set(graph.nodes().map(node => node.id()));
        graph.batch(() => {
            graph.elements().filter(element => !wanted.has(element.id())).remove();
            for (const element of data.elements || []) {
                const current = graph.getElementById(element.data.id);
                if (current.length) { current.data(element.data); } else { graph.add(element); }
            }
        });
        if (!previous.size) {
            graph.layout({name: "breadthfirst", animate: false, directed: true, padding: 25}).run();
            // 少量节点首次适配时不放大成巨字；用户仍可主动放大到原有上限。
            if (graph.zoom() > 1) { graph.zoom(1); graph.center(); }
        }
        else {
            graph.nodes().filter(node => !previous.has(node.id())).forEach((node, index) => {
                const neighbor = node.neighborhood().nodes().filter(candidate => previous.has(candidate.id())).first();
                const base = neighbor.length ? neighbor.position() : {x: 80, y: 80};
                node.position({x: base.x + 45 + index * 9, y: base.y + 45});
            });
        }
        graph.elements().unselect();
        if (data.selected) { graph.getElementById(data.selected).select(); }
        graph.resize();
    }, values => { colors = values; if (graph) { graph.style(styles()); } });
})();
