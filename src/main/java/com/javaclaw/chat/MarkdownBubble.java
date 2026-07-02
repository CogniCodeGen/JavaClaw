package com.javaclaw.chat;

import com.javaclaw.chat.markdown.ChatDocModel;
import com.javaclaw.chat.markdown.MarkdownParagraphRenderer;
import com.javaclaw.chat.markdown.MarkdownParagraphRenderer.BlockRender;
import com.javaclaw.chat.markdown.MarkdownParagraphRenderer.LinkRange;
import com.javaclaw.ui.javafx.theme.FontManager;
import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.scene.Cursor;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import org.commonmark.node.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 渲染气泡组件（纯 JavaFX 节点版）。
 *
 * <p>基于 JavaFX 25 孵化 RichTextArea + 自定义 {@link ChatDocModel}：
 * 文字原生可选中复制，表格/代码块/分隔线/图片经 Region 段落嵌入，
 * 颜色全走 chat.css 的 {@code .md-*} 样式类（-jc-* 令牌，随主题换肤零成本）。
 * 取代旧 WebView + CommonMark→HTML 方案，WebKit 的 macOS 空白重绘/焦点兜底/
 * 表情图片化等补丁全部随之移除。</p>
 *
 * <p><b>流式增量渲染</b>：100ms 节流 tick 内全文重新解析（CommonMark 解析很快），
 * 按顶层块的源文本指纹与上次结果对比——已定稿块直接复用缓存段落，
 * 只重建尾部变化块并经 {@code ChatDocModel.replaceFrom} 增量刷新视图，
 * 长消息下每 tick 成本恒定（POC 实测 p50≈3ms@9k字）。</p>
 */
public class MarkdownBubble {

    private static final Logger log = LoggerFactory.getLogger(MarkdownBubble.class);

    // ==================== 实例字段 ====================

    private final RichTextArea view;
    private final ChatDocModel model = new ChatDocModel();
    private final StringBuilder content = new StringBuilder();

    /** 渲染节流器（流式输入时限制渲染频率） */
    private final PauseTransition renderThrottle;

    /** 节流间隔（毫秒） */
    private static final long RENDER_INTERVAL_MS = 100;

    /** 上次实际渲染时间戳：追加间隔小于节流间隔时防止渲染被无限推迟（真节流而非防抖） */
    private long lastRenderAtMs = 0;

    /** 已释放标记，阻止 dispose 后的节流回调继续渲染 */
    private volatile boolean disposed = false;

    /** 顶层块渲染缓存：源文本指纹相同的块跨 tick 复用，实现稳定前缀零重建 */
    private record CachedBlock(String key, List<RichParagraph> paras, List<LinkRange> links) {}
    private final List<CachedBlock> blockCache = new ArrayList<>();

    /** 当前文档的链接命中区间（paragraphIndex 为文档绝对下标） */
    private final List<LinkRange> absoluteLinks = new ArrayList<>();

    /** 字体密度变更 → 字号是内联样式，需全量重渲染；保留引用以便 dispose 解除 */
    private final ChangeListener<Number> fontRevListener =
            (obs, o, n) -> { if (!disposed) rerenderAll(); };

    /**
     * 创建 Markdown 渲染气泡
     *
     * @param prefWidth 首选宽度（像素）
     */
    public MarkdownBubble(double prefWidth) {
        view = new RichTextArea(model);
        view.setEditable(false);
        view.setWrapText(true);
        view.setUseContentHeight(true);
        view.setFocusTraversable(false);
        view.setPrefWidth(prefWidth);
        view.getStyleClass().add("md-bubble");

        // 中文右键菜单（内置菜单为硬编码英文）
        ContextMenu menu = new ContextMenu();
        MenuItem copySel = new MenuItem("复制");
        copySel.setOnAction(e -> view.copy());
        MenuItem selectAll = new MenuItem("全选");
        selectAll.setOnAction(e -> view.selectAll());
        MenuItem copyRaw = new MenuItem("复制原文 (Markdown)");
        copyRaw.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(getText());
            Clipboard.getSystemClipboard().setContent(cc);
        });
        menu.getItems().addAll(copySel, selectAll, new SeparatorMenuItem(), copyRaw);
        view.setContextMenu(menu);

        // 链接：点击打开系统浏览器，悬停显示手型
        view.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 1
                    || !e.isStillSincePress()) return;
            LinkRange link = linkAt(e.getX(), e.getY());
            if (link != null) openLink(link.url());
        });
        view.addEventHandler(MouseEvent.MOUSE_MOVED, e ->
                view.setCursor(linkAt(e.getX(), e.getY()) != null ? Cursor.HAND : Cursor.TEXT));

        // 渲染节流：流式输入时最多每 100ms 渲染一次
        renderThrottle = new PauseTransition(Duration.millis(100));
        renderThrottle.setOnFinished(e -> render());

        // 字体密度切换（设置 › 字体）实时生效
        FontManager.revisionProperty().addListener(fontRevListener);

        // 将 MarkdownBubble 实例附加到视图节点，便于外部遍历定位并在清空场景图时 dispose
        view.getProperties().put("markdownBubble", this);
    }

    // ==================== 对外 API（与旧 WebView 版保持兼容） ====================

    /**
     * 获取渲染视图节点（用于添加到场景图）
     */
    public Region getView() {
        return view;
    }

    /**
     * 追加文本内容（流式调用）
     */
    public void appendText(String chunk) {
        if (chunk == null || chunk.isEmpty() || disposed) return;
        content.append(chunk);
        if (System.currentTimeMillis() - lastRenderAtMs >= RENDER_INTERVAL_MS) {
            // 距上次渲染已满一个节流窗：立即渲染，保证高频流式下仍按节奏出画面
            renderThrottle.stop();
            render();
        } else {
            // 窗内追加：挂起等窗口收尾（同时兜底流结束后的最终渲染）
            renderThrottle.playFromStart();
        }
    }

    /**
     * 替换全部文本内容（历史回放等需即时生效的场景）
     */
    public void replaceText(String text) {
        if (disposed) return;
        content.setLength(0);
        if (text != null) {
            content.append(text);
        }
        renderThrottle.stop();
        render();
    }

    /**
     * 强制全量重渲染当前内容（主题/字体等外部环境变化后调用；平时增量渲染无需手动刷新）
     */
    public void refresh() {
        if (disposed) return;
        rerenderAll();
    }

    /**
     * 获取原始 Markdown 文本内容
     */
    public String getText() {
        return content.toString();
    }

    /**
     * 获取文本长度
     */
    public int getLength() {
        return content.length();
    }

    /**
     * 释放此气泡持有的资源：停止节流器、解除 FontManager 监听、清空缓存。
     * 纯 JavaFX 节点无本地资源，其余交给 GC。
     */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        renderThrottle.stop();
        FontManager.revisionProperty().removeListener(fontRevListener);
        view.getProperties().remove("markdownBubble");
        blockCache.clear();
        absoluteLinks.clear();
        content.setLength(0);
    }

    // ==================== 内部渲染 ====================

    /** 清缓存全量重渲染（字号等内联样式变化时缓存失效） */
    private void rerenderAll() {
        blockCache.clear();
        render();
    }

    private void render() {
        if (disposed) return;
        lastRenderAtMs = System.currentTimeMillis();
        String md = content.toString();
        try {
            renderIncremental(md);
        } catch (Throwable t) {
            log.warn("Markdown 增量渲染失败，回退全量重建", t);
            try {
                blockCache.clear();
                renderIncremental(md);
            } catch (Throwable t2) {
                log.error("Markdown 渲染失败", t2);
            }
        }
    }

    /**
     * 增量渲染：与块缓存比对源文本指纹，找到最长公共前缀，只重建其后的段落。
     * 流式追加场景下通常只有最后一个未闭合块变化。
     */
    private void renderIncremental(String md) {
        List<Block> blocks = MarkdownParagraphRenderer.parseTopLevelBlocks(md);

        // 最长公共前缀（指纹为 null 的块不可缓存，视为变化）
        int prefix = 0;
        List<String> keys = new ArrayList<>(blocks.size());
        for (Block b : blocks) {
            keys.add(MarkdownParagraphRenderer.sourceKey(b, md));
        }
        while (prefix < blocks.size() && prefix < blockCache.size()) {
            String key = keys.get(prefix);
            if (key == null || !key.equals(blockCache.get(prefix).key())) break;
            prefix++;
        }

        // 丢弃失效缓存，重建尾部块
        while (blockCache.size() > prefix) {
            blockCache.remove(blockCache.size() - 1);
        }
        int paraBase = 0;
        for (int i = 0; i < prefix; i++) {
            paraBase += blockCache.get(i).paras().size();
        }
        List<RichParagraph> tail = new ArrayList<>();
        for (int i = prefix; i < blocks.size(); i++) {
            BlockRender br = MarkdownParagraphRenderer.renderBlock(blocks.get(i));
            blockCache.add(new CachedBlock(keys.get(i), br.paragraphs(), br.links()));
            tail.addAll(br.paragraphs());
        }
        model.replaceFrom(paraBase, tail);

        // 重建绝对链接区间（块内相对下标 + 各块段落基址）
        absoluteLinks.clear();
        int base = 0;
        for (CachedBlock cb : blockCache) {
            for (LinkRange lr : cb.links()) {
                absoluteLinks.add(new LinkRange(
                        base + lr.paragraphIndex(), lr.startOffset(), lr.endOffset(), lr.url()));
            }
            base += cb.paras().size();
        }
    }

    // ==================== 链接 ====================

    /** 视图坐标命中链接区间（无命中返回 null） */
    private LinkRange linkAt(double x, double y) {
        if (absoluteLinks.isEmpty()) return null;
        TextPos pos = view.getTextPosition(x, y);
        if (pos == null) return null;
        for (LinkRange lr : absoluteLinks) {
            if (pos.index() == lr.paragraphIndex()
                    && pos.offset() >= lr.startOffset() && pos.offset() < lr.endOffset()) {
                return lr;
            }
        }
        return null;
    }

    /** 打开系统默认浏览器（后台线程，避免阻塞 FX 线程） */
    private static void openLink(String url) {
        Thread.ofVirtual().name("md-link-open").start(() -> {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            } catch (Exception e) {
                log.warn("打开链接失败: {}", url, e);
            }
        });
    }
}
