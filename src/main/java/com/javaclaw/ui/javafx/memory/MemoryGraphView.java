package com.javaclaw.ui.javafx.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.memory.graph.MemoryGraph;
import com.javaclaw.ui.javafx.theme.ThemeManager;
import javafx.concurrent.Worker;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * 记忆图谱视图 —— 在 WebView 中以 canvas 力导向布局渲染 {@link MemoryGraph}。
 *
 * <p><b>零外部依赖</b>：力导向模拟、缩放/拖拽/点击交互、节点详情面板全部由内嵌的纯 JavaScript
 * 在 canvas 上实现，不引入 vis.js/d3 等第三方库，离线可用（桌面应用无法保证联网拉 CDN）。
 * 借鉴 {@code chat.MarkdownBubble} 的 WebView 生命周期范式（loadContent + 待渲染缓存 + dispose）。</p>
 *
 * <p><b>配色随主题</b>：背景/文字/次要/边线从当前 {@link ThemeManager} 主题派生注入（深浅自适应）；
 * 三类节点的类别色（事实/情景/实体）跨主题固定，属语义色（与 CLAUDE.md「状态语义色跨主题固定」一致）。
 * 监听主题切换实时重注配色。</p>
 *
 * @author JavaClaw
 */
public class MemoryGraphView {

    private static final Logger log = LoggerFactory.getLogger(MemoryGraphView.class);

    private final WebView webView;
    private final WebEngine engine;

    private boolean templateLoaded = false;
    private volatile boolean disposed = false;
    /** 模板未加载完成前缓存的待渲染图（JSON） */
    private String pendingGraphJson = null;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 选中节点详情（供外部检视器渲染）。 */
    public record NodeDetail(String id, String label, String type, String group,
                             String detail, List<String> related) {}

    /** 外部检视器选中回调（null=未启用，沿用 WebView 内置详情面板）。 */
    private Consumer<NodeDetail> selectionListener;
    /** 类别可见性 [事实, 情景, 实体]，与聚焦深度一起在加载完成后注入。 */
    private boolean[] visibleTypes = {true, true, true};
    private int focusDepth = 3;
    /** JS→Java 桥（须强引用持有，避免被 GC 后回调失效）。 */
    private final GraphBridge bridge = new GraphBridge();

    /** 主题切换监听：实时重注配色（dispose 时解除，避免泄漏） */
    private final javafx.beans.value.ChangeListener<String> themeListener =
            (obs, o, n) -> { if (!disposed) applyTheme(); };

    public MemoryGraphView() {
        this.webView = new WebView();
        webView.setContextMenuEnabled(false);
        applyPageFill();
        this.engine = webView.getEngine();
        engine.setJavaScriptEnabled(true);

        engine.getLoadWorker().stateProperty().addListener((obs, oldS, newS) -> {
            if (newS == Worker.State.SUCCEEDED) {
                templateLoaded = true;
                applyTheme();
                wireBridge();
                applyFilters();
                if (pendingGraphJson != null) {
                    String json = pendingGraphJson;
                    pendingGraphJson = null;
                    renderJson(json);
                }
            }
        });

        ThemeManager.themeProperty().addListener(themeListener);
        engine.loadContent(HTML_TEMPLATE);
    }

    public WebView getView() {
        return webView;
    }

    /** 渲染一张图谱快照（须在 JavaFX 线程调用）。 */
    public void render(MemoryGraph graph) {
        if (disposed) return;
        String json = (graph == null ? MemoryGraph.empty() : graph).toJson();
        if (templateLoaded) {
            renderJson(json);
        } else {
            pendingGraphJson = json;
        }
    }

    private void renderJson(String json) {
        try {
            engine.executeScript("window.renderGraph(" + json + ");");
        } catch (Exception e) {
            log.warn("记忆图谱渲染失败: {}", e.getMessage());
        }
    }

    // ==================== 外部筛选 / 检视器 ====================

    /** 设置类别可见性（事实 / 情景 / 实体），实时过滤显示。 */
    public void setVisibleTypes(boolean fact, boolean episode, boolean entity) {
        visibleTypes = new boolean[]{fact, episode, entity};
        applyFilters();
    }

    /** 设置聚焦深度：1=直接邻居 / 2=两跳 / 3=全部（仅在选中节点时生效）。 */
    public void setFocusDepth(int depth) {
        focusDepth = Math.max(1, Math.min(3, depth));
        applyFilters();
    }

    /** 注册外部检视器回调：启用后改用外部面板展示节点详情（隐藏 WebView 内置面板）。 */
    public void setOnNodeSelected(Consumer<NodeDetail> cb) {
        this.selectionListener = cb;
    }

    private void applyFilters() {
        if (disposed || !templateLoaded) return;
        try {
            engine.executeScript("window.setVisibleTypes({fact:" + visibleTypes[0]
                    + ",episode:" + visibleTypes[1] + ",entity:" + visibleTypes[2] + "});");
            engine.executeScript("window.setFocusDepth(" + focusDepth + ");");
        } catch (Exception e) {
            log.warn("注入图谱筛选失败: {}", e.getMessage());
        }
    }

    private void wireBridge() {
        try {
            JSObject win = (JSObject) engine.executeScript("window");
            win.setMember("javaGraphBridge", bridge);
            if (selectionListener != null) {
                engine.executeScript("window.useExternalInspector();");
            }
        } catch (Exception e) {
            log.warn("装配图谱选择桥失败: {}", e.getMessage());
        }
    }

    /** JS→Java 选择桥；方法须为 public 供 WebView 反射回调。 */
    public final class GraphBridge {
        public void onSelect(String json) {
            if (disposed || selectionListener == null) return;
            try {
                com.fasterxml.jackson.databind.JsonNode n = MAPPER.readTree(json);
                List<String> related = new java.util.ArrayList<>();
                com.fasterxml.jackson.databind.JsonNode rel = n.get("related");
                if (rel != null && rel.isArray()) {
                    rel.forEach(r -> related.add(r.asText()));
                }
                NodeDetail d = new NodeDetail(
                        text(n, "id"), text(n, "label"), text(n, "type"),
                        text(n, "group"), text(n, "detail"), related);
                selectionListener.accept(d);
            } catch (Exception e) {
                log.warn("解析图谱选择负载失败: {}", e.getMessage());
            }
        }

        private String text(com.fasterxml.jackson.databind.JsonNode n, String f) {
            com.fasterxml.jackson.databind.JsonNode v = n.get(f);
            return v == null || v.isNull() ? "" : v.asText();
        }

        public void onClear() {
            if (disposed || selectionListener == null) return;
            selectionListener.accept(null);
        }
    }

    /** 释放 WebView 与监听（视图关闭时调用）。 */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        ThemeManager.themeProperty().removeListener(themeListener);
        try {
            engine.load(null);
        } catch (Exception ignore) {}
    }

    // ==================== 主题配色 ====================

    private void applyPageFill() {
        try {
            webView.setPageFill(javafx.scene.paint.Color.web(ThemeManager.getCurrentTheme().bg()));
        } catch (Exception e) {
            webView.setPageFill(javafx.scene.paint.Color.web("#FBFAF6"));
        }
    }

    /** 从当前主题派生配色并注入 JS（背景/文字随主题深浅；类别色固定）。 */
    private void applyTheme() {
        applyPageFill();
        if (disposed || !templateLoaded) return;
        ThemeManager.Theme t;
        try {
            t = ThemeManager.getCurrentTheme();
        } catch (Exception e) {
            return;
        }
        boolean dark = luminance(t.bg()) < 0.42;
        String bg = t.bg();
        String text = dark ? "#F3F1EB" : "#27251F";
        String muted = dark ? "#9C9587" : "#706B5F";
        String edge = dark ? "#3A372F" : "#D9D4C8";
        String panel = t.surface();
        // 三类节点类别色（语义固定，深浅各取一组保证对比度）
        String fact = t.brand();                       // 事实 = 品牌色（随主题）
        String episode = dark ? "#D9A23C" : "#C68A1E"; // 情景 = 琥珀
        String entity = dark ? "#9B79D6" : "#7E57C2";  // 实体 = 梅紫
        String pal = "{"
                + "bg:'" + bg + "',text:'" + text + "',muted:'" + muted + "',edge:'" + edge + "',"
                + "panel:'" + panel + "',fact:'" + fact + "',episode:'" + episode + "',entity:'" + entity + "',"
                + "dark:" + dark + "}";
        try {
            engine.executeScript("window.setPalette(" + pal + ");");
        } catch (Exception e) {
            log.warn("注入图谱配色失败: {}", e.getMessage());
        }
    }

    /** 粗略相对亮度（0~1），用于判断深/浅主题。 */
    private static double luminance(String hex) {
        try {
            javafx.scene.paint.Color c = javafx.scene.paint.Color.web(hex);
            return 0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue();
        } catch (Exception e) {
            return 1.0;
        }
    }

    // ==================== HTML / JS 模板 ====================
    // 注：JS 内刻意不使用反斜杠转义与三引号，避免破坏 Java 文本块。

    private static final String HTML_TEMPLATE = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="UTF-8">
            <style>
              * { margin:0; padding:0; box-sizing:border-box; }
              html, body { width:100%; height:100%; overflow:hidden; }
              body { font-family:"Inter","PingFang SC","Microsoft YaHei",system-ui,sans-serif; }
              #cv { display:block; width:100%; height:100%; cursor:grab; }
              #cv.grabbing { cursor:grabbing; }
              #empty { position:absolute; top:50%; left:50%; transform:translate(-50%,-50%);
                       font-size:14px; text-align:center; line-height:1.7; display:none; }
              #legend { position:absolute; top:10px; left:10px; font-size:12px;
                        padding:8px 10px; border-radius:8px; line-height:1.7; }
              #legend .row { display:flex; align-items:center; gap:6px; }
              #legend .dot { width:10px; height:10px; border-radius:50%; display:inline-block; }
              #legend .ln { width:16px; height:0; display:inline-block; }
              #info { position:absolute; right:10px; top:10px; max-width:280px; font-size:12.5px;
                      padding:10px 12px; border-radius:10px; line-height:1.6; display:none;
                      box-shadow:0 4px 16px rgba(0,0,0,0.18); word-break:break-word; }
              #info .t { font-weight:600; margin-bottom:4px; }
              #info .k { opacity:0.7; }
              #hud { position:absolute; left:10px; bottom:10px; font-size:11.5px; opacity:0.65; }
              #reset { position:absolute; right:10px; bottom:10px; font-size:12px; padding:5px 10px;
                       border-radius:7px; cursor:pointer; border:1px solid transparent; }
            </style>
            </head>
            <body>
              <canvas id="cv"></canvas>
              <div id="empty">暂无记忆图谱数据<br><span style="font-size:12px;opacity:.7">随着对话积累事实与实体，这里会逐渐长出关联网络</span></div>
              <div id="legend"></div>
              <div id="info"></div>
              <div id="hud"></div>
              <div id="reset">重置视图</div>
            <script>
            (function(){
              var cv = document.getElementById('cv');
              var ctx = cv.getContext('2d');
              var elEmpty = document.getElementById('empty');
              var elLegend = document.getElementById('legend');
              var elInfo = document.getElementById('info');
              var elHud = document.getElementById('hud');
              var elReset = document.getElementById('reset');

              var P = { bg:'#FBFAF6', text:'#27251F', muted:'#706B5F', edge:'#D9D4C8',
                        panel:'#FFFFFF', fact:'#2E9A6A', episode:'#C68A1E', entity:'#7E57C2', dark:false };

              var nodes = [], edges = [], adj = {}, byId = {};
              var scale = 1, offX = 0, offY = 0;
              var alpha = 0;                 // 模拟温度
              var selected = null, hover = null;
              var vis = {fact:true, episode:true, entity:true};
              var focusDepth = 3, focusSet = null, externalInspector = false;
              var dragNode = null, dragging = false, panning = false;
              var lastX = 0, lastY = 0, downX = 0, downY = 0, moved = false;
              var dpr = window.devicePixelRatio || 1;

              function resize(){
                var w = cv.clientWidth, h = cv.clientHeight;
                cv.width = w * dpr; cv.height = h * dpr;
                ctx.setTransform(dpr,0,0,dpr,0,0);
              }
              window.addEventListener('resize', resize);

              function W(){ return cv.clientWidth; }
              function H(){ return cv.clientHeight; }

              function radius(n){ return Math.min(22, 6 + Math.sqrt(n.weight||1)*2.2); }
              function nodeColor(n){ return n.type==='fact'?P.fact:(n.type==='episode'?P.episode:P.entity); }

              // ---------- 外部筛选：类别可见性 + 聚焦深度 ----------
              function inFocus(nd){ return focusSet===null || focusSet[nd.id]!==undefined; }
              function visible(nd){ return vis[nd.type]!==false && inFocus(nd); }
              function computeFocus(){
                if (!selected || focusDepth >= 3){ focusSet = null; return; }
                var dist = {}; dist[selected.id] = 0; var q = [selected.id];
                while (q.length){
                  var id = q.shift(); var d = dist[id];
                  if (d >= focusDepth) continue;
                  var ns = adj[id] || [];
                  for (var i=0;i<ns.length;i++){ if (dist[ns[i]]===undefined){ dist[ns[i]] = d+1; q.push(ns[i]); } }
                }
                focusSet = dist;
              }
              window.setVisibleTypes = function(v){ vis = v; draw(); };
              window.setFocusDepth = function(d){ focusDepth = d; computeFocus(); draw(); };
              window.useExternalInspector = function(){ externalInspector = true; elInfo.style.display = 'none'; };

              function emitSelect(){
                if (!selected) return;
                var rel = []; var ns = adj[selected.id] || [];
                for (var i=0;i<ns.length && rel.length<8;i++){
                  var nb = byId[ns[i]]; if (nb) rel.push(nb.label);
                }
                var payload = { id:selected.id, label:selected.label, type:selected.type,
                                group:selected.group||'', detail:selected.detail||'', related:rel };
                try { if (window.javaGraphBridge) window.javaGraphBridge.onSelect(JSON.stringify(payload)); } catch(e){}
              }
              function emitClear(){
                try { if (window.javaGraphBridge) window.javaGraphBridge.onClear(); } catch(e){}
              }

              window.setPalette = function(p){
                for (var k in p) P[k] = p[k];
                document.body.style.background = P.bg;
                document.body.style.color = P.text;
                elEmpty.style.color = P.muted;
                elLegend.style.background = P.panel; elLegend.style.color = P.text;
                elLegend.style.border = '1px solid ' + P.edge;
                elInfo.style.background = P.panel; elInfo.style.color = P.text;
                elInfo.style.border = '1px solid ' + P.edge;
                elHud.style.color = P.muted;
                elReset.style.background = P.panel; elReset.style.color = P.text;
                elReset.style.border = '1px solid ' + P.edge;
                renderLegend();
                draw();
              };

              function renderLegend(){
                function row(color, label, isLine, dashed){
                  var mark = isLine
                    ? '<span class="ln" style="border-top:2px '+(dashed?'dashed':'solid')+' '+color+'"></span>'
                    : '<span class="dot" style="background:'+color+'"></span>';
                  return '<div class="row">'+mark+'<span>'+label+'</span></div>';
                }
                elLegend.innerHTML =
                  row(P.fact,'事实',false)+row(P.episode,'情景',false)+row(P.entity,'实体',false)+
                  '<div style="height:4px"></div>'+
                  row(P.muted,'来源',true,false)+row(P.entity,'关联实体',true,true)+row(P.fact,'语义相近',true,false);
              }

              window.renderGraph = function(g){
                var idMap = {};
                nodes = (g.nodes||[]).map(function(n){
                  var node = { id:n.id, label:n.label, type:n.type, group:n.group,
                               detail:n.detail, weight:n.weight||1,
                               x:W()/2 + (Math.random()-0.5)*Math.min(600,W()),
                               y:H()/2 + (Math.random()-0.5)*Math.min(400,H()),
                               vx:0, vy:0 };
                  idMap[n.id] = node; return node;
                });
                adj = {};
                edges = (g.edges||[]).filter(function(e){ return idMap[e.from] && idMap[e.to]; })
                  .map(function(e){
                    var ed = { a:idMap[e.from], b:idMap[e.to], kind:e.kind, weight:e.weight||1 };
                    (adj[e.from]=adj[e.from]||[]).push(e.to);
                    (adj[e.to]=adj[e.to]||[]).push(e.from);
                    return ed;
                  });
                byId = idMap; selected = null; hover = null; focusSet = null;
                elInfo.style.display = 'none';
                elEmpty.style.display = nodes.length ? 'none' : 'block';
                fitView();
                alpha = 1.0;
                ensureLoop();
                updateHud();
              };

              function updateHud(){
                elHud.textContent = nodes.length + ' 节点 · ' + edges.length + ' 边';
              }

              // ---------- 力导向模拟 ----------
              function step(){
                if (alpha < 0.01) return;
                var n = nodes.length;
                var rep = 5200, grav = 0.025, damp = 0.86;
                var cx = W()/2, cy = H()/2;
                for (var i=0;i<n;i++){
                  var a = nodes[i];
                  for (var j=i+1;j<n;j++){
                    var b = nodes[j];
                    var dx = a.x-b.x, dy = a.y-b.y;
                    var d2 = dx*dx+dy*dy+0.01;
                    var d = Math.sqrt(d2);
                    var f = rep/d2;
                    var ux = dx/d, uy = dy/d;
                    a.vx += ux*f; a.vy += uy*f;
                    b.vx -= ux*f; b.vy -= uy*f;
                  }
                }
                for (var e=0;e<edges.length;e++){
                  var ed = edges[e];
                  var len = ed.kind==='semantic'?78:(ed.kind==='about'?92:104);
                  var k = ed.kind==='semantic'?0.012:0.02;
                  var dx2 = ed.b.x-ed.a.x, dy2 = ed.b.y-ed.a.y;
                  var dist = Math.sqrt(dx2*dx2+dy2*dy2)+0.01;
                  var diff = (dist-len)/dist*k;
                  var mx = dx2*diff, my = dy2*diff;
                  ed.a.vx += mx; ed.a.vy += my;
                  ed.b.vx -= mx; ed.b.vy -= my;
                }
                for (var i2=0;i2<n;i2++){
                  var p = nodes[i2];
                  if (p === dragNode) { p.vx=0; p.vy=0; continue; }
                  p.vx += (cx-p.x)*grav; p.vy += (cy-p.y)*grav;
                  p.x += p.vx*alpha; p.y += p.vy*alpha;
                  p.vx *= damp; p.vy *= damp;
                }
                alpha *= 0.985;
              }

              // ---------- 绘制 ----------
              function sx(x){ return x*scale+offX; }
              function sy(y){ return y*scale+offY; }

              function draw(){
                ctx.clearRect(0,0,W(),H());
                // 边
                for (var e=0;e<edges.length;e++){
                  var ed = edges[e];
                  if (!visible(ed.a) || !visible(ed.b)) continue;
                  var hot = selected && (ed.a===selected||ed.b===selected);
                  ctx.beginPath();
                  ctx.moveTo(sx(ed.a.x), sy(ed.a.y));
                  ctx.lineTo(sx(ed.b.x), sy(ed.b.y));
                  if (ed.kind==='semantic'){
                    ctx.strokeStyle = P.fact;
                    ctx.globalAlpha = hot?0.8:(0.12+0.5*(ed.weight||0));
                    ctx.lineWidth = hot?1.6:1.0;
                    ctx.setLineDash([]);
                  } else if (ed.kind==='about'){
                    ctx.strokeStyle = P.entity;
                    ctx.globalAlpha = hot?0.95:0.6;
                    ctx.lineWidth = 1.2;
                    ctx.setLineDash([4,3]);
                  } else {
                    ctx.strokeStyle = P.muted;
                    ctx.globalAlpha = hot?0.9:0.4;
                    ctx.lineWidth = 1.0;
                    ctx.setLineDash([]);
                  }
                  ctx.stroke();
                }
                ctx.globalAlpha = 1; ctx.setLineDash([]);
                // 节点
                var showLabel = scale > 0.75;
                for (var i=0;i<nodes.length;i++){
                  var nd = nodes[i];
                  if (!visible(nd)) continue;
                  var r = radius(nd)*Math.max(0.6,Math.min(1.6,scale));
                  var isSel = nd===selected, isHov = nd===hover;
                  ctx.beginPath();
                  ctx.arc(sx(nd.x), sy(nd.y), r, 0, Math.PI*2);
                  ctx.fillStyle = nodeColor(nd);
                  ctx.globalAlpha = (selected && !isSel && !isNeighbor(nd)) ? 0.3 : 1;
                  ctx.fill();
                  if (isSel || isHov){
                    ctx.lineWidth = 2.5; ctx.strokeStyle = P.text; ctx.globalAlpha = 1; ctx.stroke();
                  }
                  ctx.globalAlpha = 1;
                  if (showLabel || isSel || isHov){
                    ctx.fillStyle = P.text;
                    ctx.globalAlpha = (selected && !isSel && !isNeighbor(nd)) ? 0.35 : 0.95;
                    ctx.font = (isSel?'600 ':'')+'11px sans-serif';
                    ctx.fillText(nd.label, sx(nd.x)+r+3, sy(nd.y)+3);
                    ctx.globalAlpha = 1;
                  }
                }
              }

              function isNeighbor(nd){
                if (!selected) return false;
                var ns = adj[selected.id]; if (!ns) return false;
                return ns.indexOf(nd.id) >= 0;
              }

              var rafOn = false;
              function loop(){
                step(); draw();
                if (alpha >= 0.01){ requestAnimationFrame(loop); } else { rafOn=false; draw(); }
              }
              function ensureLoop(){ if (!rafOn){ rafOn=true; requestAnimationFrame(loop); } }

              // ---------- 视图适配 ----------
              function fitView(){
                if (!nodes.length){ scale=1; offX=0; offY=0; return; }
                scale = 1; offX = 0; offY = 0;
              }
              function reheat(){ alpha = Math.max(alpha,0.5); ensureLoop(); }

              // ---------- 命中测试 ----------
              function pick(mx,my){
                for (var i=nodes.length-1;i>=0;i--){
                  var nd = nodes[i];
                  if (!visible(nd)) continue;
                  var dx = mx-sx(nd.x), dy = my-sy(nd.y);
                  var r = radius(nd)*Math.max(0.6,Math.min(1.6,scale))+3;
                  if (dx*dx+dy*dy <= r*r) return nd;
                }
                return null;
              }

              function showInfo(nd){
                var typeName = nd.type==='fact'?'事实':(nd.type==='episode'?'情景':'实体');
                elInfo.innerHTML = '<div class="t">'+esc(nd.label)+'</div>'+
                  '<div><span class="k">类型：</span>'+typeName+'　<span class="k">分组：</span>'+esc(nd.group||'')+'</div>'+
                  '<div style="margin-top:6px;white-space:pre-wrap">'+esc(nd.detail||'')+'</div>';
                elInfo.style.display = 'block';
              }
              function esc(s){ return (s==null?'':String(s)).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }

              // ---------- 交互事件 ----------
              cv.addEventListener('mousedown', function(ev){
                var mx = ev.offsetX, my = ev.offsetY;
                downX = mx; downY = my; lastX = mx; lastY = my; moved = false;
                var nd = pick(mx,my);
                if (nd){ dragNode = nd; dragging = true; }
                else { panning = true; cv.classList.add('grabbing'); }
              });
              window.addEventListener('mousemove', function(ev){
                var rect = cv.getBoundingClientRect();
                var mx = ev.clientX-rect.left, my = ev.clientY-rect.top;
                if (Math.abs(mx-downX)+Math.abs(my-downY) > 3) moved = true;
                if (dragging && dragNode){
                  dragNode.x = (mx-offX)/scale; dragNode.y = (my-offY)/scale;
                  reheat(); return;
                }
                if (panning){
                  offX += mx-lastX; offY += my-lastY; lastX = mx; lastY = my; draw(); return;
                }
                var h = pick(mx,my);
                if (h !== hover){ hover = h; cv.style.cursor = h?'pointer':'grab'; draw(); }
              });
              window.addEventListener('mouseup', function(ev){
                if (dragging && !moved && dragNode){
                  selected = (selected===dragNode)?null:dragNode;
                  computeFocus();
                  if (selected){
                    if (externalInspector) emitSelect(); else showInfo(selected);
                  } else {
                    elInfo.style.display='none'; emitClear();
                  }
                  draw();
                } else if (panning && !moved){
                  selected = null; computeFocus();
                  elInfo.style.display='none'; emitClear(); draw();
                }
                dragging = false; panning = false; dragNode = null; cv.classList.remove('grabbing');
              });
              cv.addEventListener('wheel', function(ev){
                ev.preventDefault();
                var mx = ev.offsetX, my = ev.offsetY;
                var factor = ev.deltaY < 0 ? 1.1 : 0.9;
                var ns = Math.max(0.2, Math.min(4, scale*factor));
                // 以光标为锚点缩放
                offX = mx - (mx-offX)*(ns/scale);
                offY = my - (my-offY)*(ns/scale);
                scale = ns; draw();
              }, {passive:false});
              elReset.addEventListener('click', function(){
                fitView(); reheat(); draw();
              });

              resize();
              setPalette(P);
              draw();
            })();
            </script>
            </body>
            </html>
            """;
}
