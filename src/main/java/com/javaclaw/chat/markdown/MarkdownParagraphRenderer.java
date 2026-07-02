package com.javaclaw.chat.markdown;

import com.javaclaw.ui.javafx.theme.FontManager;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.node.Block;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown → {@link RichParagraph} 渲染器（2B 方案核心）。
 *
 * <p>把 CommonMark AST 的顶层块渲染为孵化 RichTextArea 的段落列表：
 * 文字类块（段落/标题/列表/引用）渲染为可选中复制的文本段落（颜色全走 chat.css
 * 的 {@code .md-*} 样式类 → -jc-* 令牌，随主题换肤零成本）；结构类块
 * （围栏代码/表格/分隔线/图片）经 {@code RichParagraph.of(Supplier&lt;Region&gt;)}
 * 整段嵌入原生节点。</p>
 *
 * <p>按「顶层块」为单位输出并提供源文本指纹 {@link #sourceKey}，供 MarkdownBubble
 * 做流式增量渲染：已定稿块直接复用缓存段落，每个节流 tick 只重建尾部变化块。</p>
 */
public final class MarkdownParagraphRenderer {

    private MarkdownParagraphRenderer() {}

    private static final List<Extension> EXTENSIONS = List.of(
            org.commonmark.ext.gfm.tables.TablesExtension.create(),
            StrikethroughExtension.create(),
            AutolinkExtension.create());

    /** 带源位置信息的解析器（增量渲染的块指纹依赖 SourceSpan） */
    private static final Parser PARSER = Parser.builder()
            .extensions(EXTENSIONS)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();

    /** 链接命中区间：段落下标（块内相对）+ 字符区间 + 目标 URL */
    public record LinkRange(int paragraphIndex, int startOffset, int endOffset, String url) {}

    /** 单个顶层块的渲染产物 */
    public record BlockRender(List<RichParagraph> paragraphs, List<LinkRange> links) {}

    // ==================== 解析与指纹 ====================

    /** 解析出顶层块列表 */
    public static List<Block> parseTopLevelBlocks(String markdown) {
        List<Block> blocks = new ArrayList<>();
        Node doc = PARSER.parse(markdown == null ? "" : markdown);
        for (Node n = doc.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof LinkReferenceDefinition) continue; // 定义不产出内容
            if (n instanceof Block b) blocks.add(b);
        }
        return blocks;
    }

    /**
     * 块的源文本指纹：源码区间原文。相同指纹 = 块未变化，可复用缓存渲染结果。
     * 无 SourceSpan 时返回 null（调用方视为不可缓存，每次重渲染）。
     */
    public static String sourceKey(Block block, String markdown) {
        List<SourceSpan> spans = block.getSourceSpans();
        if (spans == null || spans.isEmpty()) return null;
        SourceSpan first = spans.get(0);
        SourceSpan last = spans.get(spans.size() - 1);
        int start = first.getInputIndex();
        int end = last.getInputIndex() + last.getLength();
        if (start < 0 || end > markdown.length() || start > end) return null;
        return markdown.substring(start, end);
    }

    // ==================== 块渲染 ====================

    /** 渲染单个顶层块 */
    public static BlockRender renderBlock(Block block) {
        Ctx ctx = new Ctx();
        renderBlockInto(block, ctx, 0);
        ctx.flushIfPending();
        return new BlockRender(ctx.paras, ctx.links);
    }

    /** 渲染期上下文：段落累积 + 当前段构建状态 */
    private static final class Ctx {
        final List<RichParagraph> paras = new ArrayList<>();
        final List<LinkRange> links = new ArrayList<>();
        RichParagraph.Builder pb;
        int len;                    // 当前段已写入字符数（含前缀）
        boolean quote;              // 引用块内（文字走 md-quote-text）
        double fontSize = FontManager.chatFontPx();
        String baseClass = "md-body";

        void ensureParagraph() {
            if (pb == null) {
                pb = RichParagraph.builder();
                len = 0;
            }
        }

        /** 收束当前段落（带段落级属性：行距/缩进/段距） */
        void flush(double indent, double spaceAbove, double spaceBelow) {
            ensureParagraph();
            StyleAttributeMap.Builder pa = StyleAttributeMap.builder()
                    .setLineSpacing(Math.max(0, (FontManager.chatLineHeight() - 1) * fontSize * 0.6))
                    .setSpaceAbove(spaceAbove)
                    .setSpaceBelow(spaceBelow);
            if (indent > 0) pa.setSpaceLeft(indent);
            pb.setParagraphAttributes(pa.build());
            paras.add(pb.build());
            pb = null;
            len = 0;
        }

        void flushIfPending() {
            if (pb != null) flush(0, 0, 0);
        }

        /** 嵌入整段 Region（表格/代码块/分隔线/图片） */
        void addRegion(java.util.function.Supplier<Region> factory) {
            flushIfPending();
            paras.add(RichParagraph.of(factory));
        }
    }

    /** 内联样式状态（沿 AST 下行累积） */
    private record InlineStyle(boolean bold, boolean italic, boolean strike, boolean code, String linkUrl) {
        static final InlineStyle BASE = new InlineStyle(false, false, false, false, null);
        InlineStyle withBold()   { return new InlineStyle(true, italic, strike, code, linkUrl); }
        InlineStyle withItalic() { return new InlineStyle(bold, true, strike, code, linkUrl); }
        InlineStyle withStrike() { return new InlineStyle(bold, italic, true, code, linkUrl); }
        InlineStyle withCode()   { return new InlineStyle(bold, italic, strike, true, linkUrl); }
        InlineStyle withLink(String url) { return new InlineStyle(bold, italic, strike, code, url); }
    }

    private static void renderBlockInto(Node block, Ctx ctx, double indent) {
        switch (block) {
            case Heading h -> {
                double fs = ctx.fontSize;
                int lv = Math.min(h.getLevel(), 4);
                ctx.fontSize = switch (lv) {
                    case 1 -> fs + 4; case 2 -> fs + 1.5; default -> fs - 1;
                };
                ctx.baseClass = "md-h" + lv;
                renderInlines(h, ctx, InlineStyle.BASE.withBold());
                ctx.flush(indent, 10, 4);
                ctx.fontSize = fs;
                ctx.baseClass = ctx.quote ? "md-quote-text" : "md-body";
            }
            case Paragraph p -> {
                // 纯图片段落 → 图片 Region
                if (p.getFirstChild() instanceof org.commonmark.node.Image img && img.getNext() == null) {
                    String url = img.getDestination();
                    ctx.addRegion(() -> imageRegion(url));
                    return;
                }
                renderInlines(p, ctx, InlineStyle.BASE);
                ctx.flush(indent, 0, 6);
            }
            case BulletList list -> renderList(list, ctx, indent, -1);
            case OrderedList list -> renderList(list, ctx, indent,
                    list.getMarkerStartNumber() == null ? 1 : list.getMarkerStartNumber());
            case FencedCodeBlock f -> {
                String code = trimTrailingNewline(f.getLiteral());
                String lang = f.getInfo() == null ? "" : f.getInfo().trim();
                ctx.addRegion(() -> codeCard(code, lang));
            }
            case IndentedCodeBlock icb -> {
                String code = trimTrailingNewline(icb.getLiteral());
                ctx.addRegion(() -> codeCard(code, ""));
            }
            case BlockQuote q -> {
                boolean prevQuote = ctx.quote;
                String prevBase = ctx.baseClass;
                ctx.quote = true;
                ctx.baseClass = "md-quote-text";
                for (Node child = q.getFirstChild(); child != null; child = child.getNext()) {
                    renderBlockInto(child, ctx, indent + 14);
                }
                ctx.quote = prevQuote;
                ctx.baseClass = prevBase;
            }
            case ThematicBreak tb -> ctx.addRegion(MarkdownParagraphRenderer::hrRegion);
            case TableBlock table -> {
                List<List<String>> head = new ArrayList<>(), body = new ArrayList<>();
                collectTable(table, head, body);
                ctx.addRegion(() -> tableRegion(head, body));
            }
            case HtmlBlock hb -> {
                String prevBase = ctx.baseClass;
                ctx.baseClass = "md-muted";
                ctx.ensureParagraph();
                emitText(ctx, trimTrailingNewline(hb.getLiteral()), InlineStyle.BASE);
                ctx.flush(indent, 0, 6);
                ctx.baseClass = prevBase;
            }
            default -> {
                // 未覆盖的块类型按纯文本兜底
                renderInlines(block, ctx, InlineStyle.BASE);
                ctx.flush(indent, 0, 6);
            }
        }
    }

    private static void renderList(Node list, Ctx ctx, double indent, int startNumber) {
        int n = startNumber;
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem li)) continue;
            String marker = startNumber < 0 ? "•  " : (n++) + ". ";
            boolean firstBlock = true;
            for (Node child = li.getFirstChild(); child != null; child = child.getNext()) {
                if (firstBlock && child instanceof Paragraph p) {
                    ctx.ensureParagraph();
                    // 列表标记与正文同段，保证选中复制时结构完整
                    ctx.pb.addWithInlineAndStyleNames(marker,
                            "-fx-font-size: " + fmt(ctx.fontSize) + ";", "md-list-marker");
                    ctx.len += marker.length();
                    renderInlines(p, ctx, InlineStyle.BASE);
                    ctx.flush(indent + 8, 0, 2);
                    firstBlock = false;
                } else {
                    renderBlockInto(child, ctx, indent + 22);
                    firstBlock = false;
                }
            }
        }
    }

    // ==================== 内联渲染 ====================

    private static void renderInlines(Node parent, Ctx ctx, InlineStyle st) {
        ctx.ensureParagraph();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            switch (n) {
                case org.commonmark.node.Text t -> emitText(ctx, t.getLiteral(), st);
                case StrongEmphasis se -> renderInlines(se, ctx, st.withBold());
                case Emphasis em -> renderInlines(em, ctx, st.withItalic());
                case Strikethrough sk -> renderInlines(sk, ctx, st.withStrike());
                case Code c -> emitText(ctx, c.getLiteral(), st.withCode());
                case Link link -> {
                    InlineStyle ls = st.withLink(link.getDestination());
                    if (link.getFirstChild() == null) {
                        emitText(ctx, link.getDestination(), ls);
                    } else {
                        renderInlines(link, ctx, ls);
                    }
                }
                case org.commonmark.node.Image img -> {
                    // 行内图片降级为链接文本（块级图片已在 Paragraph 分支特判为 Region）
                    String alt = plainText(img);
                    emitText(ctx, (alt.isEmpty() ? "图片" : alt),
                            st.withLink(img.getDestination()));
                }
                case SoftLineBreak sb -> emitText(ctx, " ", st);
                case HardLineBreak hb -> {
                    ctx.flush(0, 0, 0);
                    ctx.ensureParagraph();
                }
                case HtmlInline hi -> emitText(ctx, hi.getLiteral(), st);
                default -> renderInlines(n, ctx, st);
            }
        }
    }

    /** 输出一个文本段：样式类管颜色（随主题），内联样式管字号/字重/斜体/删除线/下划线 */
    private static void emitText(Ctx ctx, String text, InlineStyle st) {
        if (text == null || text.isEmpty()) return;
        ctx.ensureParagraph();
        // 折叠连排全角/不换行空格（与旧 WebView 版 collapseWideSpaces 同因：模型排版常用其对齐）
        if (!st.code()) text = WIDE_SPACE_RUN.matcher(text).replaceAll(" ");

        List<String> classes = new ArrayList<>(2);
        StringBuilder css = new StringBuilder();
        double fs = ctx.fontSize;
        if (st.code()) {
            classes.add("md-inline-code");
            css.append("-fx-font-family: '").append(monoFamily()).append("';");
            fs = fs - 1;
        } else if (st.linkUrl() != null) {
            classes.add("md-link");
            css.append("-fx-underline: true;");
        } else {
            classes.add(ctx.baseClass);
        }
        css.append("-fx-font-size: ").append(fmt(fs)).append(";");
        if (st.bold()) css.append("-fx-font-weight: bold;");
        if (st.italic()) css.append("-fx-font-style: italic;");
        if (st.strike()) css.append("-fx-strikethrough: true;");

        if (st.linkUrl() != null) {
            ctx.links.add(new LinkRange(ctx.paras.size(), ctx.len, ctx.len + text.length(), st.linkUrl()));
        }
        ctx.pb.addWithInlineAndStyleNames(text, css.toString(), classes.toArray(String[]::new));
        ctx.len += text.length();
    }

    // ==================== Region 工厂（供 RichParagraph.of 反复调用，须每次新建） ====================

    /** 代码块卡片：字面深色（chat.css 深色控制台块惯例，勿令牌化）+ 语言标签 + 复制按钮 */
    private static Region codeCard(String code, String lang) {
        javafx.scene.text.Text text = new javafx.scene.text.Text(code);
        text.getStyleClass().add("md-code-text");
        text.setStyle("-fx-font-family: '" + monoFamily() + "'; -fx-font-size: "
                + fmt(FontManager.chatFontPx() - 2) + ";");
        TextFlow flow = new TextFlow(text);
        flow.setMaxWidth(Double.MAX_VALUE);

        Label langLabel = new Label(lang.isEmpty() ? "code" : lang);
        langLabel.getStyleClass().add("md-code-lang");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button copy = new Button("复制");
        copy.getStyleClass().add("md-code-copy");
        copy.setFocusTraversable(false);
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(code);
            Clipboard.getSystemClipboard().setContent(cc);
            copy.setText("已复制");
            javafx.animation.PauseTransition reset =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
            reset.setOnFinished(ev -> copy.setText("复制"));
            reset.play();
        });
        HBox header = new HBox(6, langLabel, spacer, copy);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(6, header, flow);
        card.getStyleClass().add("md-code-card");
        VBox wrapper = new VBox(card);
        wrapper.setPadding(new javafx.geometry.Insets(4, 0, 6, 0));
        return wrapper;
    }

    /** GFM 表格：GridPane + Label 单元格（md-table-* 样式类，随主题换肤） */
    private static Region tableRegion(List<List<String>> head, List<List<String>> body) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("md-table");
        grid.setHgap(1);
        grid.setVgap(1);
        int row = 0;
        for (List<String> cells : head) {
            addTableRow(grid, cells, row++, true, false);
        }
        boolean stripe = false;
        for (List<String> cells : body) {
            addTableRow(grid, cells, row++, false, stripe);
            stripe = !stripe;
        }
        VBox wrapper = new VBox(grid);
        wrapper.setPadding(new javafx.geometry.Insets(4, 0, 6, 0));
        return wrapper;
    }

    private static void addTableRow(GridPane grid, List<String> cells, int row, boolean header, boolean stripe) {
        for (int c = 0; c < cells.size(); c++) {
            Label cell = new Label(cells.get(c));
            cell.setWrapText(true);
            cell.getStyleClass().add(header ? "md-table-header"
                    : (stripe ? "md-table-cell-stripe" : "md-table-cell"));
            cell.setMaxWidth(Double.MAX_VALUE);
            GridPane.setHgrow(cell, Priority.ALWAYS);
            grid.add(cell, c, row);
        }
    }

    /** 分隔线 */
    private static Region hrRegion() {
        Region line = new Region();
        line.getStyleClass().add("md-hr");
        line.setPrefHeight(1);
        line.setMaxWidth(Double.MAX_VALUE);
        VBox wrapper = new VBox(line);
        wrapper.setPadding(new javafx.geometry.Insets(8, 0, 8, 0));
        return wrapper;
    }

    /** 图片：异步加载 + 宽度上限自适应 + 双击放大（本地文件走 ImageViewerDialog） */
    private static Region imageRegion(String url) {
        ImageView iv = new ImageView();
        iv.setPreserveRatio(true);
        iv.getStyleClass().add("md-image");
        try {
            Image img = new Image(url, true); // backgroundLoading：不阻塞 FX 线程
            iv.setImage(img);
            Runnable fit = () -> {
                double w = img.getWidth();
                if (w > 0) iv.setFitWidth(Math.min(w, 460));
            };
            if (img.getProgress() >= 1.0) {
                fit.run();
            } else {
                img.progressProperty().addListener((obs, o, p) -> {
                    if (p.doubleValue() >= 1.0) fit.run();
                });
            }
            iv.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && url.startsWith("file:")) {
                    try {
                        java.io.File f = new java.io.File(java.net.URI.create(url));
                        if (f.exists() && iv.getScene() != null) {
                            com.javaclaw.chat.ImageViewerDialog.show(iv.getScene().getWindow(), f);
                        }
                    } catch (Exception ignore) {}
                }
            });
        } catch (Exception ignore) {
            // URL 非法时保持空 ImageView，不中断整块渲染
        }
        VBox wrapper = new VBox(iv);
        wrapper.setPadding(new javafx.geometry.Insets(4, 0, 6, 0));
        return wrapper;
    }

    // ==================== 工具 ====================

    private static void collectTable(TableBlock table, List<List<String>> head, List<List<String>> body) {
        for (Node sec = table.getFirstChild(); sec != null; sec = sec.getNext()) {
            boolean isHead = sec instanceof TableHead;
            if (!isHead && !(sec instanceof TableBody)) continue;
            for (Node r = sec.getFirstChild(); r != null; r = r.getNext()) {
                if (!(r instanceof TableRow)) continue;
                List<String> cells = new ArrayList<>();
                for (Node c = r.getFirstChild(); c != null; c = c.getNext()) {
                    if (c instanceof TableCell) cells.add(plainText(c));
                }
                (isHead ? head : body).add(cells);
            }
        }
    }

    /** 提取节点子树的纯文本（表格单元格/图片 alt 用） */
    private static String plainText(Node node) {
        StringBuilder sb = new StringBuilder();
        collectPlainText(node, sb);
        return sb.toString();
    }

    private static void collectPlainText(Node node, StringBuilder sb) {
        for (Node n = node.getFirstChild(); n != null; n = n.getNext()) {
            switch (n) {
                case org.commonmark.node.Text t -> sb.append(t.getLiteral());
                case Code c -> sb.append(c.getLiteral());
                case SoftLineBreak s -> sb.append(' ');
                case HardLineBreak h -> sb.append(' ');
                default -> collectPlainText(n, sb);
            }
        }
    }

    private static String trimTrailingNewline(String s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
        return s.substring(0, end);
    }

    /** 连续 2 个及以上的全角空格 / 不换行空格 / 窄不换行空格 */
    private static final java.util.regex.Pattern WIDE_SPACE_RUN =
            java.util.regex.Pattern.compile("[\\u3000\\u00A0\\u202F]{2,}");

    /** 等宽字体首选族（FontManager 的栈取第一项） */
    private static String monoFamily() {
        String stack = FontManager.monoStack();
        String first = stack.split(",")[0].trim();
        return first.replace("\"", "").replace("'", "");
    }

    private static String fmt(double v) {
        return (v == Math.floor(v)) ? String.valueOf((int) v) : String.valueOf(v);
    }
}
