package com.javaclaw.desktop.web;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * 将 macOS 原生彩色 emoji 转为离线透明 PNG，绕开 WebKit 的彩色字形绘制异常。
 *
 * <p>仅在后台调用；同步保护按访问顺序淘汰的缓存，最多保留 128 个字形和 2 MiB 字符数据。 每批最多处理 32 个、每个最多 64 个 UTF-16 单元，失败时保留调用方的原始文字。
 */
final class WebEmojiRasterizer {
    private static final int FONT_SIZE = 64;
    private static final int MAX_BATCH = 32;
    private static final int MAX_ENTRIES = 128;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private static final Pattern KEYCAP = Pattern.compile("[0-9#*]\\uFE0F?\\u20E3");
    private static final int[][] PICTOGRAPHIC_RANGES = {
        {0xA9, 0xA9}, {0xAE, 0xAE}, {0x203C, 0x203C}, {0x2049, 0x2049},
        {0x2122, 0x2122}, {0x2139, 0x2139}, {0x2194, 0x21FF}, {0x2300, 0x23FF},
        {0x25A0, 0x27BF}, {0x2934, 0x2935}, {0x2B05, 0x2B07}, {0x2B1B, 0x2B1C},
        {0x2B50, 0x2B50}, {0x2B55, 0x2B55}, {0x3030, 0x3030}, {0x303D, 0x303D},
        {0x3297, 0x3297}, {0x3299, 0x3299}, {0x1F000, 0x1FAFF}
    };
    private final LinkedHashMap<String, CachedGlyph> cache = new LinkedHashMap<>(16, 0.75f, true);
    private int cachedBytes;

    /**
     * 获取单个 emoji 字素簇的图片；不修改、规范化或联网查询原始字符。
     *
     * @param clusters 字素簇列表；空值、无效值和超出批次限额的项会被忽略
     * @return 原始字素簇到图片的不可变映射；字体不可用或绘制失败时对应项缺省
     */
    synchronized Map<String, Glyph> render(List<String> clusters) {
        if (clusters == null || !fontAvailable()) {
            return Map.of();
        }
        Map<String, Glyph> result = new LinkedHashMap<>();
        for (int index = 0; index < Math.min(clusters.size(), MAX_BATCH); index++) {
            String cluster = clusters.get(index);
            if (!validCluster(cluster)) {
                continue;
            }
            CachedGlyph cached = cache.get(cluster);
            if (cached == null) {
                cached = rasterize(cluster);
                if (cached != null) {
                    remember(cluster, cached);
                }
            }
            if (cached != null) {
                result.put(cluster, cached.glyph());
            }
        }
        return Map.copyOf(result);
    }

    private boolean fontAvailable() {
        try {
            return NativeFont.FONT.getFamily(Locale.ROOT).equals("Apple Color Emoji");
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private static boolean validCluster(String cluster) {
        if (cluster == null || cluster.isEmpty() || cluster.length() > 64) {
            return false;
        }
        if (!GRAPHEME.matcher(cluster).matches()) {
            return false;
        }
        int[] points = cluster.codePoints().toArray();
        if (regional(points[0])) {
            return points.length == 2 && regional(points[1]);
        }
        if (KEYCAP.matcher(cluster).matches()) {
            return true;
        }
        return pictographic(points[0]) && validContinuation(points);
    }

    private static boolean validContinuation(int[] points) {
        for (int index = 1; index < points.length; index++) {
            int point = points[index];
            if (point == 0x200D) {
                if (index + 1 == points.length || !pictographic(points[index + 1])) {
                    return false;
                }
            } else if (!pictographic(point) && !component(point)) {
                return false;
            }
        }
        return true;
    }

    private static boolean pictographic(int point) {
        if (regional(point) || point >= 0x1F3FB && point <= 0x1F3FF) {
            return false;
        }
        for (int[] range : PICTOGRAPHIC_RANGES) {
            if (point >= range[0] && point <= range[1]) {
                return true;
            }
        }
        return false;
    }

    private static boolean regional(int point) {
        return point >= 0x1F1E6 && point <= 0x1F1FF;
    }

    private static boolean component(int point) {
        return point == 0xFE0F
                || point == 0xFE0E
                || point >= 0x1F3FB && point <= 0x1F3FF
                || point >= 0xE0020 && point <= 0xE007F;
    }

    private CachedGlyph rasterize(String cluster) {
        try {
            TextLayout layout = new TextLayout(cluster, NativeFont.FONT, NativeFont.CONTEXT);
            Rectangle2D bounds = layout.getBounds();
            int left = (int) Math.floor(Math.min(0, bounds.getMinX())) - 1;
            // 彩色位图字体没有矢量轮廓，getBounds 可能为空；字体 ascent/descent 才能保留完整像素。
            int top = (int) Math.floor(Math.min(-layout.getAscent(), bounds.getMinY())) - 1;
            int right = (int) Math.ceil(Math.max(layout.getAdvance(), bounds.getMaxX())) + 1;
            int bottom = (int) Math.ceil(Math.max(layout.getDescent(), bounds.getMaxY())) + 1;
            int width = right - left;
            int height = bottom - top;
            if (width <= 0 || width > 512 || height <= 0 || height > 192) {
                return null;
            }
            EncodedGlyph encoded = encode(paint(layout, left, top, width, height), top);
            if (encoded == null || encoded.png().length == 0 || encoded.png().length > MAX_BYTES) {
                return null;
            }
            Glyph glyph = new Glyph(
                    "data:image/png;base64," + Base64.getEncoder().encodeToString(encoded.png()),
                    (double) width / FONT_SIZE,
                    (double) encoded.height() / FONT_SIZE,
                    -(double) encoded.bottom() / FONT_SIZE);
            int retainedBytes = (glyph.dataUri().length() + cluster.length()) * Character.BYTES;
            return retainedBytes <= MAX_BYTES ? new CachedGlyph(glyph, retainedBytes) : null;
        } catch (IOException | RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private BufferedImage paint(TextLayout layout, int left, int top, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            graphics.setColor(Color.BLACK);
            layout.draw(graphics, -left, -top);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private EncodedGlyph encode(BufferedImage image, int baselineTop) throws IOException {
        int first = 0;
        int last = image.getHeight() - 1;
        while (first <= last && !visibleRow(image, first)) {
            first++;
        }
        while (last >= first && !visibleRow(image, last)) {
            last--;
        }
        if (first > last) {
            return null;
        }
        // 保留一像素透明边界；移除字体预留空白，避免 emoji 抬高普通正文行距。
        int top = Math.max(0, first - 1);
        int bottom = Math.min(image.getHeight(), last + 2);
        BufferedImage cropped = image.getSubimage(0, top, image.getWidth(), bottom - top);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        // 显式使用内存流，避免 ImageIO 默认为每个新字形创建磁盘缓存；不改写全局缓存配置。
        try (MemoryCacheImageOutputStream memory = new MemoryCacheImageOutputStream(output)) {
            ImageIO.write(cropped, "png", memory);
        }
        return new EncodedGlyph(output.toByteArray(), bottom - top, baselineTop + bottom);
    }

    private boolean visibleRow(BufferedImage image, int y) {
        for (int x = 0; x < image.getWidth(); x++) {
            if (image.getRGB(x, y) >>> 24 != 0) {
                return true;
            }
        }
        return false;
    }

    private void remember(String cluster, CachedGlyph glyph) {
        cache.put(cluster, glyph);
        cachedBytes += glyph.bytes();
        while (cache.size() > MAX_ENTRIES || cachedBytes > MAX_BYTES) {
            CachedGlyph removed = cache.pollFirstEntry().getValue();
            cachedBytes -= removed.bytes();
        }
    }

    /** 字形的透明 PNG data URI，以及以字号为单位的宽、高和 CSS vertical-align 偏移；全部非空且有限。 verticalAlignEm 为图片底边相对正文基线的位置，负值表示图片底边低于基线。 */
    record Glyph(String dataUri, double widthEm, double heightEm, double verticalAlignEm) {}

    /** 缓存项包含非空字形及图片 URI 和字素键按 UTF-16 保守计算的字节数。 */
    private record CachedGlyph(Glyph glyph, int bytes) {}

    /** 临时 PNG 编码结果；包含非空数据、像素高度以及图片底边相对基线的像素位置。 */
    private record EncodedGlyph(byte[] png, int height, int bottom) {}

    /** 字体仅在后台第一次绘制时初始化，构造宿主不触发 AWT 原生字体加载。 */
    private static final class NativeFont {
        private static final Font FONT = new Font("Apple Color Emoji", Font.PLAIN, FONT_SIZE);
        private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);
    }
}
