package com.javaclaw.desktop.document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.javaclaw.api.DocumentPreview;
import com.javaclaw.desktop.web.MarkdownLinkTarget;
import com.javaclaw.desktop.web.SafeMarkdown;
import com.javaclaw.protocol.CanonicalJson;

/** 单个预览后台任务拥有的版本读取器；正文、相对资源和解码缓存都不跨句柄共享。 */
final class DocumentPreviewLoader {
    private final DocumentPreviewGateway gateway;
    private final DocumentPreview preview;
    private final CanonicalJson json = new CanonicalJson();
    private final SafeMarkdown markdown = new SafeMarkdown();
    private DocumentTextPages pages;
    private int page;
    private long lastLine;

    DocumentPreviewLoader(DocumentPreviewGateway gateway, DocumentPreview preview) {
        this.gateway = gateway;
        this.preview = preview;
    }

    Content first() throws Exception {
        String type = preview.mediaType().toLowerCase(Locale.ROOT);
        if (type.startsWith("image/")) {
            String image = PreviewImageDecoder.decode(bytes(preview, 10 * 1024 * 1024));
            return new Content(
                    json.encode(Map.of("image", image, "title", preview.fileName()))
                            .json(),
                    "图片预览",
                    List.of(),
                    true,
                    0);
        }
        if (type.contains("markdown")
                || preview.fileName().toLowerCase(Locale.ROOT).endsWith(".md")) {
            if (preview.sizeBytes() <= 2 * 1024 * 1024) {
                return markdown();
            }
        }
        if (type.startsWith("text/") || type.contains("json") || type.contains("xml") || type.contains("javascript")) {
            pages = new DocumentTextPages(preview, gateway);
            Content content = next();
            int target = preview.startLine().orElse(0);
            while (!content.complete() && lastLine < target) {
                content = next();
            }
            return content;
        }
        throw new IOException("此格式暂不支持预览；文件内容不会执行");
    }

    Content next() throws Exception {
        DocumentTextPages.Page result = pages.next();
        lastLine =
                result.lines().isEmpty() ? lastLine : result.lines().getLast().number();
        List<Map<String, Object>> lines = result.lines().stream()
                .map(line -> Map.<String, Object>of(
                        "number", line.number(), "text", line.text(), "continued", line.continued()))
                .toList();
        return new Content(
                json.encode(Map.of(
                                "mode",
                                "lines",
                                "language",
                                language(),
                                "lines",
                                lines,
                                "targetLine",
                                preview.startLine().orElse(0)))
                        .json(),
                result.text(),
                List.of(),
                result.complete(),
                page++);
    }

    Content previous(int target) throws Exception {
        pages = new DocumentTextPages(preview, gateway);
        page = 0;
        Content content = next();
        while (content.page() < target && !content.complete()) {
            content = next();
        }
        return content;
    }

    private String language() {
        String name = preview.fileName().toLowerCase(Locale.ROOT);
        String extension = name.substring(name.lastIndexOf('.') + 1);
        return switch (extension) {
            case "java", "json", "xml", "css", "sql", "diff" -> extension;
            case "js", "mjs" -> "javascript";
            case "ts", "tsx" -> "typescript";
            case "py" -> "python";
            case "sh" -> "bash";
            case "yml", "yaml" -> "yaml";
            default -> "plaintext";
        };
    }

    private Content markdown() throws Exception {
        String source = decode(bytes(preview, 2 * 1024 * 1024));
        SafeMarkdown.Result parsed = markdown.render(source, "doc:", Map.of());
        Map<String, String> images = new HashMap<>();
        // 全页像素和编码资源共同计费；单个解码器串行运行，多个合规图片不能叠加突破预算。
        long remainingPixels = 20_000_000L;
        long remainingBytes = 10L * 1024 * 1024;
        int attempts = 0;
        for (String href : parsed.images().stream().distinct().toList()) {
            if (relative(href) && remainingPixels > 0 && remainingBytes > 0 && attempts++ < 32) {
                long copies = parsed.images().stream().filter(href::equals).count();
                var decoded = loadImage(href, remainingPixels / copies);
                if (decoded != null && decoded.data().length() * 3L / 4 * copies <= remainingBytes) {
                    images.put(href, decoded.data());
                    remainingPixels -= decoded.pixels() * copies;
                    remainingBytes -= decoded.data().length() * 3L / 4 * copies;
                }
            }
        }
        String html = markdown.render(source, "doc:", images).html();
        return new Content(
                json.encode(Map.of("mode", "markdown", "html", html)).json(), source, parsed.links(), true, 0);
    }

    private PreviewImageDecoder.Decoded loadImage(String href, long pixelBudget) throws InterruptedException {
        DocumentPreview resource = null;
        var resolved = gateway.resource(preview.handleId(), href);
        try {
            resource = resolved.toCompletableFuture().get();
            return PreviewImageDecoder.decode(bytes(resource, 10 * 1024 * 1024), pixelBudget);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (Exception unavailable) {
            // 资源不可用只影响对应图片；正文仍可阅读，绝不改用宿主文件读取。
            return null;
        } finally {
            if (resource != null) {
                gateway.close(resource.handleId());
            } else {
                // 取消不能遗失尚在 RPC 中创建的资源租约；迟到成功也必须关闭。
                resolved.whenComplete((late, failure) -> {
                    if (late != null) {
                        gateway.close(late.handleId());
                    }
                });
            }
        }
    }

    private byte[] bytes(DocumentPreview version, int limit) throws Exception {
        if (version.sizeBytes() > limit) {
            throw new IOException("文档超过当前格式的预览上限");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) version.sizeBytes());
        long offset = 0;
        boolean complete = false;
        while (!complete) {
            var chunk = gateway.read(version.handleId(), offset)
                    .toCompletableFuture()
                    .get();
            if (chunk.offsetBytes() != offset
                    || !chunk.digest().equals(version.digest())
                    || chunk.nextOffsetBytes() > version.sizeBytes()
                    || chunk.nextOffsetBytes() > limit) {
                throw new IOException("文档版本或分块游标不一致");
            }
            byte[] content = chunk.content();
            if (chunk.nextOffsetBytes() != offset + content.length || content.length == 0 && !chunk.complete()) {
                throw new IOException("文档分块未推进");
            }
            output.write(content);
            digest.update(content);
            offset = chunk.nextOffsetBytes();
            complete = chunk.complete();
        }
        if (offset != version.sizeBytes()
                || !HexFormat.of().formatHex(digest.digest()).equals(version.digest())) {
            throw new IOException("文档内容校验失败");
        }
        return output.toByteArray();
    }

    private static String decode(byte[] bytes) throws Exception {
        var charset = StandardCharsets.UTF_8;
        int skip = 0;
        if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
            charset = StandardCharsets.UTF_16LE;
            skip = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
            charset = StandardCharsets.UTF_16BE;
            skip = 2;
        } else if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) {
            skip = 3;
        }
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip))
                .toString();
    }

    static boolean relative(String href) {
        return MarkdownLinkTarget.relative(href);
    }

    record Content(String json, String plain, List<String> links, boolean complete, int page) {}
}
