package com.javaclaw.media;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.agent.vision.VisionPreprocessor;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskResult;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaToolsTest {

    @Test
    void renderedPdfPageUsesRunOwnedMultimodalGatewayWithoutTemporaryFiles() {
        AtomicInteger calls = new AtomicInteger();
        ModelTaskGateway gateway = request -> {
            calls.incrementAndGet();
            assertEquals("vision.ocr", request.purpose());
            assertEquals(1, request.mediaInputs().size());
            assertTrue(request.mediaInputs().getFirst().data().hasNonNull("base64"));
            var output = JsonNodeFactory.instance.objectNode().put("text", "扫描文字");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    output, "fake-vision", 2, 1, false, Map.of()));
        };
        VisionPreprocessor vision = new VisionPreprocessor(gateway, RunId.random());

        String response = new MediaTools(vision).recognizeRenderedPage(
                new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB));

        assertTrue(response.contains("扫描文字"), response);
        assertEquals(1, calls.get());
    }
}
