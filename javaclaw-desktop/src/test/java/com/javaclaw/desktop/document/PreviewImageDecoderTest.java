package com.javaclaw.desktop.document;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewImageDecoderTest {
    @Test
    void 验证实际格式并将首帧转成有界PNG() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "gif", output);
        image.flush();
        assertTrue(PreviewImageDecoder.decode(output.toByteArray()).startsWith("data:image/png;base64,"));
        assertThrows(java.io.IOException.class, () -> PreviewImageDecoder.decode(output.toByteArray(), 3));
        assertThrows(
                java.io.IOException.class,
                () -> PreviewImageDecoder.decode("not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(java.io.IOException.class, () -> PreviewImageDecoder.decode(new byte[10 * 1024 * 1024 + 1]));
    }
}
