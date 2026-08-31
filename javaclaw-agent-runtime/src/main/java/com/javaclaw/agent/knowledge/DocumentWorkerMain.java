package com.javaclaw.agent.knowledge;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/** 一次性文档 Worker 入口；仅由 Sandbox Supervisor 在无网络、无凭据的受限 JVM 中启动。 */
public final class DocumentWorkerMain {
    private static final int MAGIC = 0x4a434457;

    private DocumentWorkerMain() {}

    /** 接受有界二进制帧并输出长度帧；解析错误只报告类型，不将文档内容回显到诊断。 */
    public static void main(String[] args) {
        try {
            var input = new DataInputStream(System.in);
            var output = new DataOutputStream(System.out);
            // 第三方解析库可能向 System.out 写诊断；二进制协议独占原 stdout，诊断只进入受限 stderr。
            System.setOut(System.err);
            if (input.readInt() != MAGIC) {
                throw new IllegalArgumentException("invalid worker protocol");
            }
            String operation = input.readUTF();
            String mediaType = input.readUTF();
            String displayName = input.readUTF();
            int firstPage = input.readInt();
            int pageCount = input.readInt();
            int length = input.readInt();
            if (length < 1 || length > DocumentExtractor.MAX_SOURCE_BYTES) {
                throw new IllegalArgumentException("invalid document length");
            }
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length || input.read() != -1) {
                throw new IllegalArgumentException("document frame is truncated or contains trailing data");
            }
            output.writeInt(MAGIC);
            if ("extract".equals(operation)) {
                String extracted;
                try {
                    extracted = new DocumentExtractor().extract(bytes, mediaType, displayName);
                } catch (IllegalArgumentException unavailable) {
                    if (!"document contains no text".equals(unavailable.getMessage())) {
                        throw unavailable;
                    }
                    extracted = "";
                }
                byte[] text = extracted.getBytes(StandardCharsets.UTF_8);
                output.writeInt(text.length);
                output.write(text);
            } else if ("render".equals(operation)) {
                if (firstPage < 1 || pageCount < 1 || pageCount > 20) {
                    throw new IllegalArgumentException("OCR must request 1..20 explicit pages");
                }
                try (var document = Loader.loadPDF(bytes)) {
                    if (firstPage > document.getNumberOfPages()) {
                        throw new IllegalArgumentException("first page exceeds document length");
                    }
                    int count = Math.min(pageCount, document.getNumberOfPages() - firstPage + 1);
                    output.writeInt(count);
                    var renderer = new PDFRenderer(document);
                    long total = 0;
                    for (int index = firstPage - 1; index < firstPage - 1 + count; index++) {
                        var box = document.getPage(index).getCropBox();
                        float scale = Math.min(1.5f, 2_048f / Math.max(box.getWidth(), box.getHeight()));
                        if (!Float.isFinite(scale) || scale <= 0) {
                            throw new IllegalArgumentException("invalid PDF page dimensions");
                        }
                        var image = renderer.renderImage(index, scale, ImageType.RGB);
                        var encoded = new ByteArrayOutputStream();
                        javax.imageio.ImageIO.write(image, "PNG", encoded);
                        byte[] png = encoded.toByteArray();
                        total += png.length;
                        if (png.length > 5 * 1024 * 1024 || total > 24 * 1024 * 1024) {
                            throw new IllegalArgumentException(
                                    "rendered pages exceed output budget; request fewer pages");
                        }
                        output.writeInt(index + 1);
                        output.writeInt(png.length);
                        output.write(png);
                    }
                }
            } else {
                throw new IllegalArgumentException("unknown document operation");
            }
            output.flush();
        } catch (Throwable failure) {
            System.err.println("document worker failed: " + failure.getClass().getSimpleName());
            System.exit(2);
        }
    }
}
