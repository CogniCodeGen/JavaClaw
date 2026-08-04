package com.javaclaw.media;

import com.javaclaw.agent.vision.VisionPreprocessor;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaToolsTest {

    @Test
    void renderedPdfPageUsesInMemoryVisionEntryPoint() {
        AtomicInteger calls = new AtomicInteger();
        VisionPreprocessor vision = new VisionPreprocessor(null) {
            @Override
            public String ocrImage(BufferedImage image) {
                calls.incrementAndGet();
                return "扫描文字";
            }
        };

        String response = new MediaTools(vision).recognizeRenderedPage(null);

        assertTrue(response.contains("扫描文字"), response);
        assertEquals(1, calls.get());
    }
}
