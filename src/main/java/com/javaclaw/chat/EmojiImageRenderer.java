package com.javaclaw.chat;

import net.fellbaum.jemoji.EmojiManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/**
 * 对话气泡表情图片化 —— <b>纯 Java</b> 实现，无 JS、源码树零表情资源。
 *
 * <p>两步：
 * <ol>
 *   <li><b>检测</b>：用 {@code net.fellbaum:jemoji}（纯 Java、当前 Unicode 覆盖）在 HTML 文本里识别表情序列
 *       （含 ZWJ/肤色/国旗/键帽），仅处理标签之外的文本节点，不碰标签与属性。</li>
 *   <li><b>渲染</b>：用 Java2D 调系统彩色表情字体（macOS: Apple Color Emoji / Windows: Segoe UI Emoji /
 *       Linux: Noto Color Emoji）把表情画成 PNG 的 {@code data:} URL，替换为 {@code <img class="emoji">}。</li>
 * </ol>
 *
 * <p>为何如此：JavaFX 内嵌 WebKit 把字形走 Prism 管线，对彩色表情表（sbix/COLR）支持残缺，正文字号下内联
 * 表情只出梳齿乱码（实测换字体栈/COLR 矢量字体都不行，选中态彩色既不持久也只出第一个）。而 Java2D 文本走
 * 系统原生 CoreText 等，能合成彩色位图。<b>关键：必须用 {@link TextLayout} 而非 {@link Graphics2D#drawString}</b>
 * —— 后者只做简单字形映射，不对「区域指示符配对→国旗」「数字+U+20E3→键帽」做合并整形（退化成 🄲、孤立方框）；
 * 前者触发完整整形，国旗/键帽/肤色/ZWJ 家庭组合均正确出彩色（已实测）。</p>
 *
 * <p>渲染结果按表情字符串缓存。无彩色表情字体的环境（如裸 Linux）整体降级：原样保留表情文本（不裂图）。
 * 本类用 AWT/Java2D 仅做<b>离屏</b>渲染（不建任何窗口），与 JavaFX 共存安全（本应用本就非 headless 用 AWT 托盘）。</p>
 */
final class EmojiImageRenderer {

    private static final Logger log = LoggerFactory.getLogger(EmojiImageRenderer.class);

    /** 表情字符串 → data URL（空串=渲染失败/无字体）。 */
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    /** HTML 标签（含整段），用于把 HTML 切成「标签」与「文本」交替片段，只对文本做表情替换。 */
    private static final Pattern TAG = Pattern.compile("<[^>]*>", Pattern.DOTALL);

    /** 渲染像素尺寸：取 96（Apple Color Emoji 的 sbix 高分辨率位图档之一，清晰）。
     *  注意：实测 Java2D 渲染 Apple Color Emoji 在 ≥109px 会静默渲染为空，故上限取 96；CSS 再按 1em 缩放显示。 */
    private static final int RENDER_PX = 96;

    private static final Font EMOJI_FONT = pickFont();
    /** 是否有可用彩色表情字体；无则整体降级（imageifyHtml 原样返回）。 */
    private static final boolean FONT_OK = EMOJI_FONT != null;

    private EmojiImageRenderer() {}

    private static Font pickFont() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] candidates = os.contains("mac") ? new String[]{"Apple Color Emoji"}
                : os.contains("win") ? new String[]{"Segoe UI Emoji"}
                : new String[]{"Noto Color Emoji", "Noto Emoji"};
        for (String name : candidates) {
            try {
                Font f = new Font(name, Font.PLAIN, RENDER_PX);
                if (f.getFamily().equalsIgnoreCase(name) || f.canDisplay(0x1F600)) {
                    log.info("表情渲染字体: {}", f.getFamily());
                    return f;
                }
            } catch (Exception ignored) { /* 尝试下一个 */ }
        }
        log.warn("未找到彩色表情字体（候选: {}），表情图片化降级", String.join(", ", candidates));
        return null;
    }

    /**
     * 把 HTML 里（仅标签之外的文本）的表情替换为 Java2D 渲染的 {@code <img>}。
     * 无字体或无表情时原样返回。
     */
    static String imageifyHtml(String html) {
        if (!FONT_OK || html == null || html.isEmpty()) return html;
        if (!EmojiManager.containsEmoji(html)) return html; // 快速路径
        Matcher m = TAG.matcher(html);
        StringBuilder out = new StringBuilder(html.length() + 64);
        int last = 0;
        while (m.find()) {
            out.append(imageifyText(html, last, m.start())); // 标签前的文本
            out.append(html, m.start(), m.end());            // 标签原样
            last = m.end();
        }
        out.append(imageifyText(html, last, html.length()));
        return out.toString();
    }

    private static String imageifyText(String src, int from, int to) {
        if (from >= to) return "";
        String text = src.substring(from, to);
        if (!EmojiManager.containsEmoji(text)) return text;
        return EmojiManager.replaceAllEmojis(text, emoji -> {
            String e = emoji.getEmoji();
            String url = dataUrl(e);
            if (url.isEmpty()) return e; // 渲染失败：保留原表情文本
            return "<img class=\"emoji\" draggable=\"false\" alt=\"" + e + "\" src=\"" + url + "\">";
        });
    }

    /** 把单个表情字符串渲染为 PNG data URL（带缓存）；失败/无字体返回空串。 */
    static String dataUrl(String emoji) {
        if (!FONT_OK || emoji == null || emoji.isEmpty()) return "";
        return CACHE.computeIfAbsent(emoji, EmojiImageRenderer::render);
    }

    private static String render(String emoji) {
        try {
            FontRenderContext frc = new FontRenderContext(null, true, true);
            TextLayout layout = new TextLayout(emoji, EMOJI_FONT, frc);
            Rectangle pb = layout.getPixelBounds(frc, 0, 0);
            if (pb.width <= 0 || pb.height <= 0) return "";
            int pad = 2;
            int w = pb.width + pad * 2, h = pb.height + pad * 2;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.BLACK);
            // 平移：把字形像素包围盒左上角对齐到 (pad,pad)，得到紧裁切的透明底图片
            layout.draw(g, -pb.x + pad, -pb.y + pad);
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Throwable t) {
            log.debug("表情渲染失败 [{}]: {}", emoji, t.toString());
            return "";
        }
    }
}
