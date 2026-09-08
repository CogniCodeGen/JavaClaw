package com.javaclaw.desktop.document;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.DocumentChunk;
import com.javaclaw.api.DocumentPreview;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentPreviewLoaderTest {
    @Test
    void 含空格的真实Markdown图片按原始目标加载且闭合资源句柄() throws Exception {
        Gateway gateway = new Gateway(
                "![流程图](<images/flow chart.png>) [设计](<docs/design notes.md>)",
                "readme.md",
                "text/markdown",
                Optional.empty());
        var content = new DocumentPreviewLoader(gateway, gateway.version).first();
        assertTrue(content.json().contains("data:image/png;base64,"));
        assertEquals(List.of("images/flow chart.png"), gateway.resources);
        assertEquals(List.of("docs/design notes.md"), content.links());
        assertEquals(List.of(gateway.image.handleId()), gateway.closed);
    }

    @Test
    void Markdown转义原始HTML并仅通过受限相对引用读取图片且释放资源() throws Exception {
        Gateway gateway = new Gateway(
                "# 标题\n\n<script>alert(1)</script>\n\n![图](a.png)\n\n[链接](next.md)",
                "readme.md",
                "text/markdown",
                Optional.empty());
        var content = new DocumentPreviewLoader(gateway, gateway.version).first();
        Map<?, ?> data = new CanonicalJson().decode(new com.javaclaw.api.CanonicalPayload(content.json()), Map.class);
        String html = data.get("html").toString();
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("data:image/png;base64,"));
        assertFalse(html.contains("href="));
        assertEquals(List.of("next.md"), content.links());
        assertEquals(List.of("a.png"), gateway.resources);
        assertTrue(gateway.closed.contains(gateway.image.handleId()));
    }

    @Test
    void 引用行号定位真实分页并提供代码语言且上一页仍保持源行号() throws Exception {
        String source = java.util.stream.IntStream.rangeClosed(1, 900)
                .mapToObj(line -> "int value" + line + " = " + line + ";\n")
                .collect(java.util.stream.Collectors.joining());
        Gateway gateway = new Gateway(source, "Source.java", "text/plain", Optional.of(550));
        DocumentPreviewLoader loader = new DocumentPreviewLoader(gateway, gateway.version);
        var content = loader.first();
        assertEquals(1, content.page());
        assertTrue(content.plain().contains("value550"));
        assertTrue(content.json().contains("\"language\":\"java\""));
        var previous = loader.previous(0);
        assertEquals(0, previous.page());
        assertTrue(previous.plain().contains("value1"));
        assertFalse(previous.complete());
        assertTrue(loader.next().complete());
    }

    @Test
    void 缺失可选图片不阻断正文且不尝试外链图片() throws Exception {
        Gateway gateway = new Gateway(
                "正文 ![缺失](missing.png) ![远程](https://example.com/a.png)",
                "readme.md",
                "text/markdown",
                Optional.empty());
        var content = new DocumentPreviewLoader(gateway, gateway.version).first();
        assertTrue(content.plain().contains("正文"));
        assertEquals(List.of("missing.png"), gateway.resources);
        assertFalse(content.json().contains("data:image"));
        assertFalse(DocumentPreviewLoader.relative("file:///tmp/secret"));
        assertFalse(DocumentPreviewLoader.relative("//example.com/a.png"));
        assertFalse(DocumentPreviewLoader.relative("#anchor"));
        assertThrows(
                java.io.IOException.class,
                () -> new DocumentPreviewLoader(
                                gateway,
                                version(new byte[0], "binary.bin", "application/octet-stream", Optional.empty()))
                        .first());
    }

    private static DocumentPreview version(byte[] bytes, String name, String type, Optional<Integer> line)
            throws Exception {
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new DocumentPreview(
                UUID.randomUUID().toString(),
                WorkspaceId.random(),
                name,
                type,
                bytes.length,
                digest,
                DocumentPreview.Origin.REFERENCED_VERSION,
                Instant.now().plusSeconds(300),
                line,
                false);
    }

    private static final class Gateway implements DocumentPreviewGateway {
        private final byte[] source;
        private final byte[] pixels;
        private final DocumentPreview version;
        private final DocumentPreview image;
        private final List<String> resources = new ArrayList<>();
        private final List<String> closed = new ArrayList<>();

        private Gateway(String text, String name, String type, Optional<Integer> line) throws Exception {
            source = text.getBytes(StandardCharsets.UTF_8);
            version = version(source, name, type, line);
            var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            image.flush();
            pixels = output.toByteArray();
            this.image = version(pixels, "a.png", "image/png", Optional.empty());
        }

        @Override
        public CompletionStage<DocumentPreview> resolve(DocumentReference reference) {
            return CompletableFuture.completedFuture(version);
        }

        @Override
        public CompletionStage<DocumentChunk> read(String handle, long offset) {
            boolean picture = handle.equals(image.handleId());
            byte[] bytes = picture ? pixels : source;
            int end = (int) Math.min(bytes.length, offset + 256 * 1024);
            return CompletableFuture.completedFuture(new DocumentChunk(
                    offset,
                    Arrays.copyOfRange(bytes, (int) offset, end),
                    end,
                    end == bytes.length,
                    picture ? image.digest() : version.digest()));
        }

        @Override
        public CompletionStage<DocumentPreview> resource(String handle, String href) {
            resources.add(href);
            return (href.equals("a.png") || href.equals("images/flow chart.png"))
                    ? CompletableFuture.completedFuture(image)
                    : CompletableFuture.failedFuture(new IllegalArgumentException("来源未声明该图片"));
        }

        @Override
        public CompletionStage<DocumentPreview> renew(String handle) {
            return CompletableFuture.completedFuture(version);
        }

        @Override
        public CompletionStage<Void> close(String handle) {
            closed.add(handle);
            return CompletableFuture.completedFuture(null);
        }
    }
}
