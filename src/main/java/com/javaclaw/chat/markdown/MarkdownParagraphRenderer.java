package com.javaclaw.chat.markdown;

import com.javaclaw.ui.javafx.theme.FontManager;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;
import jfx.incubator.scene.control.richtext.model.SimpleViewOnlyStyledModel;
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
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
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
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 将一条已经完成的 Markdown 消息渲染为 JavaFX 只读富文本模型。
 *
 * <p>解析、样式计算和模型构建均不创建 JavaFX Node，因此可以在后台线程执行。
 * 代码块、表格、图片等结构只在模型中保存 {@link Supplier}；RichTextArea 在 FX
 * 线程请求 supplier 时才会真正创建 Region。</p>
 */
public final class MarkdownParagraphRenderer {

    private MarkdownParagraphRenderer() {}

    private static final List<Extension> EXTENSIONS = List.of(
            org.commonmark.ext.gfm.tables.TablesExtension.create(),
            StrikethroughExtension.create(),
            AutolinkExtension.create());

    /** Parser 构建后是不可变且线程安全的，可供两个后台 worker 共享。 */
    private static final Parser PARSER = Parser.builder()
            .extensions(EXTENSIONS)
            .build();

    /**
     * FX 线程取得的渲染配置快照。后台 renderer 不得再读取 FontManager 或 JavaFX 属性。
     */
    public record RenderStyleSnapshot(
            double fontSize,
            double lineHeight,
            String uiFontStack,
            String monoFontStack) {

        public RenderStyleSnapshot {
            if (fontSize <= 0 || lineHeight <= 0) {
                throw new IllegalArgumentException("字体大小和行高必须为正数");
            }
            Objects.requireNonNull(uiFontStack, "uiFontStack");
            Objects.requireNonNull(monoFontStack, "monoFontStack");
        }

        /** 只允许调用方在 FX 线程进入后台任务之前获取一次。 */
        public static RenderStyleSnapshot capture() {
            if (!Platform.isFxApplicationThread()) {
                throw new IllegalStateException("渲染样式必须在 JavaFX Application Thread 快照");
            }
            return new RenderStyleSnapshot(
                    FontManager.chatFontPx(),
                    FontManager.chatLineHeight(),
                    FontManager.uiStack(),
                    FontManager.monoStack());
        }

        String monoFamily() {
            String first = monoFontStack.split(",")[0].trim();
            return first.replace("\"", "").replace("'", "");
        }
    }

    /** 链接命中区间：段落下标、字符区间和目标 URL。 */
    public record LinkRange(int paragraphIndex, int startOffset, int endOffset, String url) {}

    /** 后台阶段的完整产物；模型尚未连接到任何 JavaFX Node。 */
    public record RenderedMarkdown(
            SimpleViewOnlyStyledModel model,
            List<LinkRange> links) {

        public RenderedMarkdown {
            Objects.requireNonNull(model, "model");
            links = List.copyOf(links);
        }
    }

    /** 一次性解析并构建整条消息。 */
    public static RenderedMarkdown render(String markdown, RenderStyleSnapshot style) {
        Objects.requireNonNull(style, "style");
        Node document = PARSER.parse(markdown == null ? "" : markdown);
        Ctx ctx = new Ctx(style);
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof LinkReferenceDefinition) continue;
            renderBlockInto(node, ctx, 0);
        }
        ctx.finish();
        return new RenderedMarkdown(ctx.model, ctx.links);
    }

    /** 渲染期上下文，仅持有纯数据、样式对象和延迟 Region supplier。 */
    private static final class Ctx {
        final RenderStyleSnapshot style;
        final SimpleViewOnlyStyledModel model = new SimpleViewOnlyStyledModel();
        final List<LinkRange> links = new ArrayList<>();
        boolean paragraphOpen;
        int paragraphIndex = -1;
        int len;
        boolean quote;
        double fontSize;
        String baseClass = "md-body";

        Ctx(RenderStyleSnapshot style) {
            this.style = style;
            this.fontSize = style.fontSize();
        }

        void ensureParagraph() {
            if (paragraphOpen) return;
            if (model.size() == 0) {
                model.addSegment("");
            } else {
                model.nl();
            }
            paragraphOpen = true;
            paragraphIndex = model.size() - 1;
            len = 0;
        }

        void flush(double indent, double spaceAbove, double spaceBelow) {
            ensureParagraph();
            StyleAttributeMap.Builder attributes = StyleAttributeMap.builder()
                    .setLineSpacing(Math.max(0, (style.lineHeight() - 1) * fontSize * 0.6))
                    .setSpaceAbove(spaceAbove)
                    .setSpaceBelow(spaceBelow);
            if (indent > 0) attributes.setSpaceLeft(indent);
            model.setParagraphAttributes(attributes.build());
            paragraphOpen = false;
            len = 0;
        }

        void flushIfPending() {
            if (paragraphOpen) flush(0, 0, 0);
        }

        void addRegion(Supplier<Region> factory) {
            flushIfPending();
            model.addParagraph(factory);
        }

        void finish() {
            flushIfPending();
            if (model.size() == 0) model.addSegment("");
        }
    }

    /** 内联样式状态（沿 AST 下行累积）。 */
    private record InlineStyle(boolean bold, boolean italic, boolean strike, boolean code, String linkUrl) {
        static final InlineStyle BASE = new InlineStyle(false, false, false, false, null);
        InlineStyle withBold() { return new InlineStyle(true, italic, strike, code, linkUrl); }
        InlineStyle withItalic() { return new InlineStyle(bold, true, strike, code, linkUrl); }
        InlineStyle withStrike() { return new InlineStyle(bold, italic, true, code, linkUrl); }
        InlineStyle withCode() { return new InlineStyle(bold, italic, strike, true, linkUrl); }
        InlineStyle withLink(String url) { return new InlineStyle(bold, italic, strike, code, url); }
    }

    private static void renderBlockInto(Node block, Ctx ctx, double indent) {
        switch (block) {
            case Heading heading -> {
                double previousSize = ctx.fontSize;
                int level = Math.min(heading.getLevel(), 4);
                ctx.fontSize = switch (level) {
                    case 1 -> previousSize + 4;
                    case 2 -> previousSize + 1.5;
                    default -> previousSize - 1;
                };
                ctx.baseClass = "md-h" + level;
                renderInlines(heading, ctx, InlineStyle.BASE.withBold());
                ctx.flush(indent, 10, 4);
                ctx.fontSize = previousSize;
                ctx.baseClass = ctx.quote ? "md-quote-text" : "md-body";
            }
            case Paragraph paragraph -> {
                if (paragraph.getFirstChild() instanceof org.commonmark.node.Image image
                        && image.getNext() == null) {
                    String url = image.getDestination();
                    ctx.addRegion(() -> imageRegion(url));
                    return;
                }
                renderInlines(paragraph, ctx, InlineStyle.BASE);
                ctx.flush(indent, 0, 6);
            }
            case BulletList list -> renderList(list, ctx, indent, -1);
            case OrderedList list -> renderList(list, ctx, indent,
                    list.getMarkerStartNumber() == null ? 1 : list.getMarkerStartNumber());
            case FencedCodeBlock fenced -> {
                String code = trimTrailingNewline(fenced.getLiteral());
                String language = fenced.getInfo() == null ? "" : fenced.getInfo().trim();
                RenderStyleSnapshot style = ctx.style;
                ctx.addRegion(() -> codeCard(code, language, style));
            }
            case IndentedCodeBlock indented -> {
                String code = trimTrailingNewline(indented.getLiteral());
                RenderStyleSnapshot style = ctx.style;
                ctx.addRegion(() -> codeCard(code, "", style));
            }
            case BlockQuote quote -> {
                boolean previousQuote = ctx.quote;
                String previousBase = ctx.baseClass;
                ctx.quote = true;
                ctx.baseClass = "md-quote-text";
                for (Node child = quote.getFirstChild(); child != null; child = child.getNext()) {
                    renderBlockInto(child, ctx, indent + 14);
                }
                ctx.quote = previousQuote;
                ctx.baseClass = previousBase;
            }
            case ThematicBreak ignored -> ctx.addRegion(MarkdownParagraphRenderer::hrRegion);
            case TableBlock table -> {
                List<List<String>> head = new ArrayList<>();
                List<List<String>> body = new ArrayList<>();
                collectTable(table, head, body);
                List<List<String>> immutableHead = immutableRows(head);
                List<List<String>> immutableBody = immutableRows(body);
                ctx.addRegion(() -> tableRegion(immutableHead, immutableBody));
            }
            case HtmlBlock html -> {
                String previousBase = ctx.baseClass;
                ctx.baseClass = "md-muted";
                ctx.ensureParagraph();
                emitText(ctx, trimTrailingNewline(html.getLiteral()), InlineStyle.BASE);
                ctx.flush(indent, 0, 6);
                ctx.baseClass = previousBase;
            }
            default -> {
                renderInlines(block, ctx, InlineStyle.BASE);
                ctx.flush(indent, 0, 6);
            }
        }
    }

    private static void renderList(Node list, Ctx ctx, double indent, int startNumber) {
        int number = startNumber;
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem listItem)) continue;
            String marker = startNumber < 0 ? "•  " : (number++) + ". ";
            boolean firstBlock = true;
            for (Node child = listItem.getFirstChild(); child != null; child = child.getNext()) {
                if (firstBlock && child instanceof Paragraph paragraph) {
                    ctx.ensureParagraph();
                    ctx.model.addWithInlineAndStyleNames(marker,
                            "-fx-font-size: " + fmt(ctx.fontSize) + ";", "md-list-marker");
                    ctx.len += marker.length();
                    renderInlines(paragraph, ctx, InlineStyle.BASE);
                    ctx.flush(indent + 8, 0, 2);
                } else {
                    renderBlockInto(child, ctx, indent + 22);
                }
                firstBlock = false;
            }
        }
    }

    private static void renderInlines(Node parent, Ctx ctx, InlineStyle style) {
        ctx.ensureParagraph();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            switch (node) {
                case org.commonmark.node.Text text -> emitTextWithPercentBoundaryFix(ctx, text, style);
                case StrongEmphasis strong -> renderInlines(strong, ctx, style.withBold());
                case Emphasis emphasis -> renderInlines(emphasis, ctx, style.withItalic());
                case Strikethrough strike -> renderInlines(strike, ctx, style.withStrike());
                case Code code -> emitText(ctx, code.getLiteral(), style.withCode());
                case Link link -> {
                    InlineStyle linkStyle = style.withLink(link.getDestination());
                    if (link.getFirstChild() == null) {
                        emitText(ctx, link.getDestination(), linkStyle);
                    } else {
                        renderInlines(link, ctx, linkStyle);
                    }
                }
                case org.commonmark.node.Image image -> {
                    String alt = plainText(image);
                    emitText(ctx, alt.isEmpty() ? "图片" : alt,
                            style.withLink(image.getDestination()));
                }
                case SoftLineBreak ignored -> emitText(ctx, " ", style);
                case HardLineBreak ignored -> {
                    ctx.flush(0, 0, 0);
                    ctx.ensureParagraph();
                }
                case HtmlInline html -> emitText(ctx, html.getLiteral(), style);
                default -> renderInlines(node, ctx, style);
            }
        }
    }

    private static void emitTextWithPercentBoundaryFix(
            Ctx ctx, org.commonmark.node.Text node, InlineStyle style) {
        String literal = node.getLiteral();
        if (literal == null || literal.isEmpty()) return;
        if (startsWithPercent(literal) && node.getPrevious() instanceof StrongEmphasis) {
            emitText(ctx, literal.substring(0, 1), style.withBold());
            literal = literal.substring(1);
        }
        emitText(ctx, literal, style);
    }

    private static boolean startsWithPercent(String text) {
        return text.startsWith("%") || text.startsWith("％");
    }

    private static void emitText(Ctx ctx, String text, InlineStyle style) {
        if (text == null || text.isEmpty()) return;
        ctx.ensureParagraph();
        if (!style.code()) text = WIDE_SPACE_RUN.matcher(text).replaceAll(" ");

        List<String> classes = new ArrayList<>(2);
        StringBuilder css = new StringBuilder();
        double fontSize = ctx.fontSize;
        if (style.code()) {
            classes.add("md-inline-code");
            css.append("-fx-font-family: '").append(ctx.style.monoFamily()).append("';");
            fontSize--;
        } else if (style.linkUrl() != null) {
            classes.add("md-link");
            css.append("-fx-underline: true;");
        } else {
            classes.add(ctx.baseClass);
        }
        if (!style.code()) {
            css.append("-fx-font-family: ").append(ctx.style.uiFontStack()).append(';');
        }
        css.append("-fx-font-size: ").append(fmt(fontSize)).append(';');
        if (style.bold()) css.append("-fx-font-weight: bold;");
        if (style.italic()) css.append("-fx-font-style: italic;");
        if (style.strike()) css.append("-fx-strikethrough: true;");

        if (style.linkUrl() != null) {
            ctx.links.add(new LinkRange(
                    ctx.paragraphIndex, ctx.len, ctx.len + text.length(), style.linkUrl()));
        }
        ctx.model.addWithInlineAndStyleNames(
                text, css.toString(), classes.toArray(String[]::new));
        ctx.len += text.length();
    }

    /** Region 工厂：这些方法只能由 RichTextArea 在 FX 线程调用。 */
    private static Region codeCard(String code, String language, RenderStyleSnapshot style) {
        requireFxThread();
        javafx.scene.text.Text text = new javafx.scene.text.Text(code);
        text.getStyleClass().add("md-code-text");
        text.setStyle("-fx-font-family: '" + style.monoFamily() + "'; -fx-font-size: "
                + fmt(style.fontSize() - 2) + ";");
        TextFlow flow = new TextFlow(text);
        flow.setMaxWidth(Double.MAX_VALUE);

        Label languageLabel = new Label(language.isEmpty() ? "code" : language);
        languageLabel.getStyleClass().add("md-code-lang");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button copy = new Button("复制");
        copy.getStyleClass().add("md-code-copy");
        copy.setFocusTraversable(false);
        copy.setOnAction(event -> {
            ClipboardContent clipboard = new ClipboardContent();
            clipboard.putString(code);
            Clipboard.getSystemClipboard().setContent(clipboard);
            copy.setText("已复制");
            javafx.animation.PauseTransition reset =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
            reset.setOnFinished(ignored -> copy.setText("复制"));
            reset.play();
        });
        HBox header = new HBox(6, languageLabel, spacer, copy);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(6, header, flow);
        card.getStyleClass().add("md-code-card");
        VBox wrapper = new VBox(card);
        wrapper.setPadding(new javafx.geometry.Insets(4, 0, 6, 0));
        return wrapper;
    }

    private static Region tableRegion(List<List<String>> head, List<List<String>> body) {
        requireFxThread();
        GridPane grid = new GridPane();
        grid.getStyleClass().add("md-table");
        grid.setHgap(1);
        grid.setVgap(1);
        int row = 0;
        for (List<String> cells : head) addTableRow(grid, cells, row++, true, false);
        boolean stripe = false;
        for (List<String> cells : body) {
            addTableRow(grid, cells, row++, false, stripe);
            stripe = !stripe;
        }
        VBox wrapper = new VBox(grid);
        wrapper.setPadding(new javafx.geometry.Insets(4, 0, 6, 0));
        return wrapper;
    }

    private static void addTableRow(
            GridPane grid, List<String> cells, int row, boolean header, boolean stripe) {
        for (int column = 0; column < cells.size(); column++) {
            Label cell = new Label(cells.get(column));
            cell.setWrapText(true);
            cell.getStyleClass().add(header ? "md-table-header"
                    : (stripe ? "md-table-cell-stripe" : "md-table-cell"));
            cell.setMaxWidth(Double.MAX_VALUE);
            GridPane.setHgrow(cell, Priority.ALWAYS);
            grid.add(cell, column, row);
        }
    }

    private static Region hrRegion() {
        requireFxThread();
        Region line = new Region();
        line.getStyleClass().add("md-hr");
        line.setPrefHeight(1);
        line.setMaxWidth(Double.MAX_VALUE);
        VBox wrapper = new VBox(line);
        wrapper.setPadding(new javafx.geometry.Insets(8, 0, 8, 0));
        return wrapper;
    }

    private static Region imageRegion(String url) {
        requireFxThread();
        ImageView imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.getStyleClass().add("md-image");
        try {
            Image image = new Image(url, true);
            imageView.setImage(image);
            Runnable fit = () -> {
                double width = image.getWidth();
                if (width > 0) imageView.setFitWidth(Math.min(width, 460));
            };
            if (image.getProgress() >= 1.0) {
                fit.run();
            } else {
                image.progressProperty().addListener((observable, oldValue, progress) -> {
                    if (progress.doubleValue() >= 1.0) fit.run();
                });
            }
            imageView.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && url.startsWith("file:")) {
                    try {
                        java.io.File file = new java.io.File(java.net.URI.create(url));
                        if (file.exists() && imageView.getScene() != null) {
                            com.javaclaw.chat.ImageViewerDialog.show(
                                    imageView.getScene().getWindow(), file);
                        }
                    } catch (Exception ignored) {
                        // 非法本地 URI 不影响其余 Markdown。
                    }
                }
            });
        } catch (Exception ignored) {
            // URL 非法时保持空 ImageView，不中断整条消息。
        }
        VBox wrapper = new VBox(imageView);
        wrapper.setPadding(new javafx.geometry.Insets(4, 0, 6, 0));
        return wrapper;
    }

    private static void collectTable(
            TableBlock table, List<List<String>> head, List<List<String>> body) {
        for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            boolean isHead = section instanceof TableHead;
            if (!isHead && !(section instanceof TableBody)) continue;
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (!(row instanceof TableRow)) continue;
                List<String> cells = new ArrayList<>();
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (cell instanceof TableCell) cells.add(plainText(cell));
                }
                (isHead ? head : body).add(cells);
            }
        }
    }

    private static List<List<String>> immutableRows(List<List<String>> rows) {
        return rows.stream().map(List::copyOf).toList();
    }

    private static String plainText(Node node) {
        StringBuilder text = new StringBuilder();
        collectPlainText(node, text);
        return text.toString();
    }

    private static void collectPlainText(Node node, StringBuilder text) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            switch (child) {
                case org.commonmark.node.Text literal -> text.append(literal.getLiteral());
                case Code code -> text.append(code.getLiteral());
                case SoftLineBreak ignored -> text.append(' ');
                case HardLineBreak ignored -> text.append(' ');
                default -> collectPlainText(child, text);
            }
        }
    }

    private static String trimTrailingNewline(String text) {
        if (text == null) return "";
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) end--;
        return text.substring(0, end);
    }

    private static final java.util.regex.Pattern WIDE_SPACE_RUN =
            java.util.regex.Pattern.compile("[\\u3000\\u00A0\\u202F]{2,}");

    private static String fmt(double value) {
        return value == Math.floor(value) ? String.valueOf((int) value) : String.valueOf(value);
    }

    private static void requireFxThread() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Markdown Region 必须在 JavaFX Application Thread 创建");
        }
    }
}
