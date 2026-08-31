package com.javaclaw.desktop;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

/** 与 FX 无关的有界 Markdown 展示树；HTML 只作文字，远程图片不加载，链接不能获得执行权限。 */
final class MarkdownDocument {
    static final int MAXIMUM_CHARACTERS = 131_072;
    private static final int MAXIMUM_NODES = 4_096;
    private static final int MAXIMUM_DEPTH = 24;
    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create(), StrikethroughExtension.create()))
            .build();

    private MarkdownDocument() {}

    static List<Block> parse(String source) {
        if (source.length() > MAXIMUM_CHARACTERS) {
            return List.of(new Block(
                    "paragraph",
                    0,
                    "",
                    "",
                    List.of(new Span("内容较长，请使用“查看内容”或“复制原文”查看完整记录。\n" + source.substring(0, 4_096), Style.PLAIN, null)),
                    List.of()));
        }
        var budget = new Budget();
        try {
            return blocks(PARSER.parse(source), budget, 0, false);
        } catch (TooComplex ignored) {
            // 富文本复杂度不能拖垮 FX 线程；退化为明确的纯文本预览，不改变持久 Item。
            return List.of(new Block(
                    "paragraph",
                    0,
                    "",
                    "",
                    List.of(new Span(
                            "富文本结构过于复杂，显示纯文本预览：\n" + source.substring(0, Math.min(source.length(), 8_192)),
                            Style.PLAIN,
                            null)),
                    List.of()));
        }
    }

    private static List<Block> blocks(Node parent, Budget budget, int depth, boolean quoted) {
        budget.check(depth);
        var result = new ArrayList<Block>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            budget.check(depth);
            if (node instanceof FencedCodeBlock code) {
                result.add(new Block(
                        "code",
                        0,
                        "",
                        code.getInfo(),
                        List.of(new Span(code.getLiteral(), Style.PLAIN, null)),
                        List.of()));
            } else if (node instanceof IndentedCodeBlock code) {
                result.add(new Block(
                        "code", 0, "", "", List.of(new Span(code.getLiteral(), Style.PLAIN, null)), List.of()));
            } else if (node instanceof ThematicBreak) {
                result.add(new Block("rule", 0, "", "", List.of(), List.of()));
            } else if (node instanceof HtmlBlock html) {
                result.add(paragraph(List.of(new Span(html.getLiteral(), Style.PLAIN, null)), quoted));
            } else if (node instanceof BlockQuote) {
                result.addAll(blocks(node, budget, depth + 1, true));
            } else if (node instanceof BulletList || node instanceof OrderedList) {
                int ordinal = node instanceof OrderedList list && list.getMarkerStartNumber() != null
                        ? list.getMarkerStartNumber()
                        : 1;
                for (Node item = node.getFirstChild(); item != null; item = item.getNext()) {
                    if (!(item instanceof ListItem)) {
                        throw new TooComplex();
                    }
                    result.add(new Block(
                            "list",
                            depth,
                            node instanceof OrderedList ? ordinal++ + "." : "•",
                            "",
                            List.of(),
                            blocks(item, budget, depth + 1, quoted)));
                }
            } else if (node instanceof TableBlock) {
                var rows = new ArrayList<Block>();
                for (Node section = node.getFirstChild(); section != null; section = section.getNext()) {
                    for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                        if (!(row instanceof TableRow)) {
                            throw new TooComplex();
                        }
                        var cells = new ArrayList<Block>();
                        for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                            if (!(cell instanceof TableCell value) || cells.size() >= 24 || rows.size() >= 100) {
                                throw new TooComplex();
                            }
                            cells.add(new Block(
                                    value.isHeader() ? "header" : "cell",
                                    0,
                                    "",
                                    "",
                                    inlines(value, Style.PLAIN, null, budget, depth + 1),
                                    List.of()));
                        }
                        rows.add(new Block("row", 0, "", "", List.of(), cells));
                    }
                }
                result.add(new Block("table", 0, "", "", List.of(), rows));
            } else {
                result.add(new Block(
                        node instanceof Heading ? "heading" : quoted ? "quote" : "paragraph",
                        node instanceof Heading heading ? heading.getLevel() : 0,
                        "",
                        "",
                        inlines(node, Style.PLAIN, null, budget, depth + 1),
                        List.of()));
            }
        }
        return List.copyOf(result);
    }

    private static Block paragraph(List<Span> spans, boolean quoted) {
        return new Block(quoted ? "quote" : "paragraph", 0, "", "", spans, List.of());
    }

    private static List<Span> inlines(Node parent, Style style, URI link, Budget budget, int depth) {
        budget.check(depth);
        var spans = new ArrayList<Span>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            budget.check(depth);
            switch (node) {
                case Text text -> spans.add(new Span(text.getLiteral(), style, link));
                case Code code ->
                    spans.add(new Span(
                            code.getLiteral(), new Style(style.bold(), style.italic(), style.strike(), true), link));
                case HtmlInline html -> spans.add(new Span(html.getLiteral(), style, null));
                case SoftLineBreak ignored -> spans.add(new Span("\n", style, link));
                case HardLineBreak ignored -> spans.add(new Span("\n", style, link));
                case StrongEmphasis ignored ->
                    spans.addAll(inlines(
                            node,
                            new Style(true, style.italic(), style.strike(), style.code()),
                            link,
                            budget,
                            depth + 1));
                case Emphasis ignored ->
                    spans.addAll(inlines(
                            node,
                            new Style(style.bold(), true, style.strike(), style.code()),
                            link,
                            budget,
                            depth + 1));
                case Strikethrough ignored ->
                    spans.addAll(inlines(
                            node,
                            new Style(style.bold(), style.italic(), true, style.code()),
                            link,
                            budget,
                            depth + 1));
                case Link value ->
                    spans.addAll(inlines(node, style, safeLink(value.getDestination()), budget, depth + 1));
                case Image ignored -> {
                    spans.add(new Span("[图片：", style, null));
                    spans.addAll(inlines(node, style, null, budget, depth + 1));
                    spans.add(new Span("；外部图片不自动加载]", style, null));
                }
                default -> spans.addAll(inlines(node, style, link, budget, depth + 1));
            }
        }
        return List.copyOf(spans);
    }

    static URI safeLink(String value) {
        if (value.length() > 2_048 || value.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        try {
            var uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    ? uri
                    : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    record Block(String kind, int level, String marker, String language, List<Span> spans, List<Block> children) {}

    record Span(String text, Style style, URI link) {}

    record Style(boolean bold, boolean italic, boolean strike, boolean code) {
        static final Style PLAIN = new Style(false, false, false, false);
    }

    private static final class Budget {
        private int remaining = MAXIMUM_NODES;

        private void check(int depth) {
            if (--remaining < 0 || depth > MAXIMUM_DEPTH) {
                throw new TooComplex();
            }
        }
    }

    private static final class TooComplex extends RuntimeException {}
}
