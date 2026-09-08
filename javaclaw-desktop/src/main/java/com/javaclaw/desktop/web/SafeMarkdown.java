package com.javaclaw.desktop.web;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/** 后台 Markdown 转换；转义原始 HTML 并移除真实导航及远程图片请求，只产生平台可验证的引用动作。 */
public final class SafeMarkdown {
    private static final List<Extension> EXTENSIONS =
            List.of(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create());
    private final Parser parser = Parser.builder().extensions(EXTENSIONS).build();

    /** 创建独立解析器；实例由单个后台渲染任务拥有。 */
    public SafeMarkdown() {}

    /**
     * 生成受限 HTML；调用者在 FX 线程之外执行。
     *
     * @param source UTF-8 语义正文，最多2MiB字符预算由调用方限定
     * @param linkPrefix 平台上下文前缀；不会作为 URL 导航
     * @param images 已验证并有界解码的 data 图片，以原始 href 为键
     * @return 安全 HTML 和稳定链接／围栏清单
     */
    public Result render(String source, String linkPrefix, Map<String, String> images) {
        Node document = parser.parse(source);
        ArrayList<String> links = new ArrayList<>();
        ArrayList<String> resources = new ArrayList<>();
        ArrayList<String> fences = new ArrayList<>();
        Map<Node, Integer> linkIds = new IdentityHashMap<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Link link) {
                linkIds.put(link, links.size());
                links.add(link.getDestination());
                visitChildren(link);
            }

            @Override
            public void visit(Image image) {
                resources.add(image.getDestination());
                visitChildren(image);
            }

            @Override
            public void visit(FencedCodeBlock block) {
                fences.add(block.getInfo());
            }
        });
        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(EXTENSIONS)
                .escapeHtml(true)
                .sanitizeUrls(true)
                .attributeProviderFactory(context -> (node, tag, attributes) -> {
                    if (node instanceof Link) {
                        attributes.remove("href");
                        attributes.put("role", "link");
                        attributes.put("tabindex", "0");
                        attributes.put("data-link", linkPrefix + linkIds.get(node));
                    } else if (node instanceof Image image) {
                        attributes.remove("src");
                        String content = images.get(image.getDestination());
                        if (content != null && content.startsWith("data:image/png;base64,")) {
                            attributes.put("src", content);
                        } else {
                            attributes.put("alt", "[图片未加载] " + attributes.getOrDefault("alt", ""));
                        }
                    }
                })
                .build();
        return new Result(renderer.render(document), List.copyOf(links), List.copyOf(resources), List.copyOf(fences));
    }

    /**
     * @param html 受限HTML
     * @param links Link遍历顺序
     * @param images 图片资源href
     * @param fences 围栏语言信息
     */
    public record Result(String html, List<String> links, List<String> images, List<String> fences) {}
}
