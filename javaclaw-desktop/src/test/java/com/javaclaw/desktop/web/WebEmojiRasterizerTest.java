package com.javaclaw.desktop.web;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.MAC)
class WebEmojiRasterizerTest {
    @Test
    void 原生彩色字形生成透明有效图片并保留原始字素() throws IOException {
        List<String> clusters = List.of("😊", "📚", "🎉", "✅", "⚠️", "👨‍👩‍👧‍👦", "👍🏽", "🇨🇳", "1️⃣");

        Map<String, WebEmojiRasterizer.Glyph> result = new WebEmojiRasterizer().render(clusters);

        assertEquals(clusters.size(), result.size());
        for (String cluster : clusters) {
            WebEmojiRasterizer.Glyph glyph = result.get(cluster);
            BufferedImage image = decode(glyph);
            assertTrue(image.getColorModel().hasAlpha(), cluster);
            assertEquals(0, image.getRGB(0, 0) >>> 24, cluster);
            assertTrue(saturatedPixels(image) > 100, cluster);
            assertTrue(glyph.widthEm() >= 0.5 && glyph.widthEm() <= 1.5, cluster);
            assertTrue(glyph.heightEm() >= 0.5 && glyph.heightEm() <= 1.5, cluster);
            assertTrue(glyph.verticalAlignEm() >= -0.6 && glyph.verticalAlignEm() <= 0.2, cluster);
            assertEquals(image.getWidth() / 64.0, glyph.widthEm());
            assertEquals(image.getHeight() / 64.0, glyph.heightEm());
        }
    }

    @Test
    void 拒绝普通文字不完整序列损坏代理项和过长输入() {
        var rasterizer = new WebEmojiRasterizer();
        List<String> invalid = Arrays.asList(
                null,
                "",
                "你好",
                "hello",
                "😊你好",
                "😊😊",
                "1",
                "1️",
                "🇨",
                "🏽",
                "\uD83D",
                "\uDE00",
                "😊\uD83D",
                "\u200D😊",
                "😊\u200D",
                "😊\u200D\u200D😊",
                "😊".repeat(33));

        assertTrue(rasterizer.render(invalid).isEmpty());
        assertTrue(rasterizer.render(null).isEmpty());
        assertTrue(rasterizer.render(List.of("👨‍".repeat(14) + "👨")).isEmpty());
        assertEquals(1, rasterizer.render(List.of("😊")).size());
    }

    @Test
    void 每批最多三十二项且不重复绘制相同字素() {
        var rasterizer = new WebEmojiRasterizer();
        List<String> clusters = IntStream.range(0, 40)
                .mapToObj(index -> new String(Character.toChars(0x1F600 + index)))
                .toList();

        Map<String, WebEmojiRasterizer.Glyph> result = rasterizer.render(clusters);

        assertEquals(32, result.size());
        assertFalse(result.containsKey(clusters.get(32)));
        assertTrue(
                result.get(clusters.getFirst())
                        == rasterizer.render(List.of(clusters.getFirst())).get(clusters.getFirst()),
                "相同字素应复用已缓存的字形对象");
        assertEquals(1, rasterizer.render(List.of("😊", "😊")).size());
    }

    @Test
    void 缓存达到条数或字节上限时保留最近访问项并重新生成被淘汰项() throws ReflectiveOperationException {
        var rasterizer = new WebEmojiRasterizer();
        Map<String, WebEmojiRasterizer.Glyph> initial = rasterizer.render(List.of("😊", "📚"));
        List<String> fillers = new ArrayList<>();
        for (int index = 0; index < 160; index++) {
            fillers.add(new String(Character.toChars(0x1F400 + index)));
        }
        for (int offset = 0; offset < fillers.size(); offset += 16) {
            // 图片大小各异，字节预算可先于条数上限生效；每批前访问目标，验证真实 LRU 而非固定容量。
            assertTrue(initial.get("😊") == rasterizer.render(List.of("😊")).get("😊"), "最近访问字形不应被提前淘汰");
            List<String> batch = fillers.subList(offset, Math.min(offset + 16, fillers.size()));
            assertEquals(batch.size(), rasterizer.render(batch).size());
        }
        assertTrue(initial.get("😊") == rasterizer.render(List.of("😊")).get("😊"), "最后一批后仍应复用最近访问字形");
        List<String> retained = cachedClusters(rasterizer);
        assertTrue(!retained.isEmpty() && retained.size() <= 128, "实际缓存项数必须受上限约束");
        assertFalse(retained.contains("📚"), "长期未访问字形应被淘汰");
        assertTrue(retainedBytes(rasterizer, retained) <= 2 * 1024 * 1024, "URI 与字素键必须符合 UTF-16 字节预算");

        assertTrue(initial.get("📚") != rasterizer.render(List.of("📚")).get("📚"), "重新访问淘汰项时应重新生成字形");
    }

    private List<String> cachedClusters(WebEmojiRasterizer rasterizer) throws ReflectiveOperationException {
        Field field = WebEmojiRasterizer.class.getDeclaredField("cache");
        field.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) field.get(rasterizer);
        return cache.keySet().stream().map(String.class::cast).toList();
    }

    private int retainedBytes(WebEmojiRasterizer rasterizer, List<String> clusters) {
        int bytes = 0;
        for (int offset = 0; offset < clusters.size(); offset += 32) {
            List<String> batch = clusters.subList(offset, Math.min(offset + 32, clusters.size()));
            for (var entry : rasterizer.render(batch).entrySet()) {
                bytes += (entry.getKey().length() + entry.getValue().dataUri().length()) * Character.BYTES;
            }
        }
        return bytes;
    }

    private BufferedImage decode(WebEmojiRasterizer.Glyph glyph) throws IOException {
        assertNotNull(glyph);
        assertTrue(glyph.dataUri().startsWith("data:image/png;base64,"));
        byte[] bytes = Base64.getDecoder().decode(glyph.dataUri().substring("data:image/png;base64,".length()));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(image);
        assertTrue(image.getWidth() <= 512 && image.getHeight() <= 192);
        return image;
    }

    private int saturatedPixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y);
                int red = pixel >>> 16 & 255;
                int green = pixel >>> 8 & 255;
                int blue = pixel & 255;
                if (pixel >>> 24 > 100
                        && Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) > 50) {
                    count++;
                }
            }
        }
        return count;
    }
}
