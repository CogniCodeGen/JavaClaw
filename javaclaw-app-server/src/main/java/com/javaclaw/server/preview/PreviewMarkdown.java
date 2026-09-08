package com.javaclaw.server.preview;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;

/** 服务端按同一 CommonMark 语法验证来源链接，模型文字只作数据，不作为路径授权。 */
final class PreviewMarkdown {
    private PreviewMarkdown() {}

    static List<String> messageLinks(String text) {
        List<String> links = new ArrayList<>();
        parse(text).accept(new AbstractVisitor() {
            @Override
            public void visit(Link link) {
                links.add(link.getDestination());
                visitChildren(link);
            }
        });
        return List.copyOf(links);
    }

    static List<String> links(String text) {
        List<String> links = new ArrayList<>();
        parse(text).accept(new AbstractVisitor() {
            @Override
            public void visit(Link link) {
                links.add(link.getDestination());
                visitChildren(link);
            }

            @Override
            public void visit(Image image) {
                links.add(image.getDestination());
                visitChildren(image);
            }
        });
        return List.copyOf(links);
    }

    static String fence(String text, int index) {
        List<String> blocks = new ArrayList<>();
        parse(text).accept(new AbstractVisitor() {
            @Override
            public void visit(FencedCodeBlock block) {
                blocks.add(block.getLiteral());
            }
        });
        return select(blocks, index);
    }

    static String select(List<String> values, int index) {
        if (index < 0 || index >= values.size()) {
            throw new IllegalArgumentException("PREVIEW_SOURCE_MISSING: 来源选择器不存在");
        }
        return values.get(index);
    }

    static Target relative(String href, String parent) {
        URI uri = URI.create(href.replace(" ", "%20"));
        if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getQuery() != null) {
            throw new SecurityException("预览只允许根内相对资源");
        }
        String value = uri.getPath();
        if (value == null
                || value.isBlank()
                || value.indexOf('\\') >= 0
                || value.indexOf('\0') >= 0
                || value.matches("^[A-Za-z]:.*")) {
            throw new SecurityException("预览资源路径无效");
        }
        Path path = Path.of(parent).resolve(value).normalize();
        if (Path.of(value).isAbsolute()
                || path.startsWith("..")
                || path.toString().isBlank()) {
            throw new SecurityException("预览资源越过授权根");
        }
        Optional<Integer> line = Optional.ofNullable(uri.getFragment())
                .filter(fragment -> fragment.matches("L[1-9][0-9]{0,8}(-L?[1-9][0-9]{0,8})?"))
                .map(fragment -> Integer.parseInt(fragment.substring(1).split("-")[0]));
        return new Target(path.toString().replace('\\', '/'), line);
    }

    private static Node parse(String text) {
        if (text.length() > 2 * 1024 * 1024) {
            throw new IllegalArgumentException("PREVIEW_TOO_LARGE: 链接解析来源超过排版预算");
        }
        // 与 Desktop 使用相同扩展和顺序；自动链接也占 Link 索引，不能用基础解析器猜测选择器。
        return Parser.builder()
                .extensions(List.of(
                        org.commonmark.ext.gfm.tables.TablesExtension.create(),
                        org.commonmark.ext.gfm.strikethrough.StrikethroughExtension.create(),
                        org.commonmark.ext.autolink.AutolinkExtension.create()))
                .build()
                .parse(text);
    }

    record Target(String path, Optional<Integer> line) {}
}
