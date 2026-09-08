package com.javaclaw.desktop.document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** 串行解码单个已下载图片，先验证实际格式与像素上限；GIF只取首帧，不信任扩展名或MIME。 */
final class PreviewImageDecoder {
    private static final Semaphore DECODE = new Semaphore(1);

    private PreviewImageDecoder() {}

    static String decode(byte[] bytes) throws Exception {
        return decode(bytes, 20_000_000L).data();
    }

    static Decoded decode(byte[] bytes, long remainingPixels) throws Exception {
        if (bytes.length > 10 * 1024 * 1024) {
            throw new IOException("图片超过 10 MiB 预览上限");
        }
        DECODE.acquire();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("不支持的图片格式");
            }
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!Set.of("png", "jpeg", "jpg", "gif").contains(format)
                        || (long) reader.getWidth(0) * reader.getHeight(0) > Math.min(20_000_000L, remainingPixels)) {
                    throw new IOException("图片格式或像素超过预览上限");
                }
                var image = reader.read(0);
                try (var output = new ByteArrayOutputStream()) {
                    ImageIO.write(image, "png", output);
                    if (output.size() > 10 * 1024 * 1024) {
                        throw new IOException("图片展开后超过预览资源上限");
                    }
                    return new Decoded(
                            "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray()),
                            (long) image.getWidth() * image.getHeight());
                } finally {
                    image.flush();
                }
            } finally {
                reader.dispose();
            }
        } finally {
            DECODE.release();
        }
    }

    record Decoded(String data, long pixels) {}
}
