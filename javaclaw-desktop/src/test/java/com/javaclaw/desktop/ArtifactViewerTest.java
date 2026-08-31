package com.javaclaw.desktop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.javaclaw.sdk.model.AttachmentContent;
import com.javaclaw.sdk.model.AttachmentInfo;
import com.javaclaw.sdk.model.ImageItemContent;
import com.javaclaw.sdk.model.ItemContent;
import com.javaclaw.sdk.model.ItemInfo;
import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.TurnInput;
import com.javaclaw.sdk.model.UserMessageItemContent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactViewerTest {
    private static final String SHA = "a".repeat(64);
    private static final JsonDocument DOCUMENT = new JsonDocument("{}");

    @Test
    void onlyTypedContentAddressedReferencesCanBeLoaded() {
        for (String uri : List.of("https://example.test/p.png", "file:///etc/passwd", "attachment:sha256:../../key")) {
            assertTrue(ArtifactViewer.references(item(new ImageItemContent(uri, "", DOCUMENT)))
                    .isEmpty());
        }
        assertEquals(
                SHA,
                ArtifactViewer.references(item(new ImageItemContent("attachment:sha256:" + SHA, "图片", DOCUMENT)))
                        .getFirst()
                        .sha256());
        var reference = new TurnInput.Attachment(SHA, "application/pdf", "文档.pdf");
        assertEquals(
                List.of(reference),
                ArtifactViewer.references(item(new UserMessageItemContent("", List.of(reference), DOCUMENT))));
    }

    @Test
    void bitmapPreviewIsSubsampledAndNeverInterpretsHtmlOrSvg() throws Exception {
        var image = new java.awt.image.BufferedImage(4_096, 3, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        byte[] bytes = output.toByteArray();
        byte[] preview = ArtifactViewer.previewImage(
                new AttachmentContent(new AttachmentInfo(SHA, "image/png", bytes.length, 1), bytes));
        assertEquals(2_048, ImageIO.read(new ByteArrayInputStream(preview)).getWidth());
        assertThrows(
                IllegalArgumentException.class,
                () -> ArtifactViewer.previewImage(
                        new AttachmentContent(new AttachmentInfo(SHA, "image/svg+xml", bytes.length, 1), bytes)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ArtifactViewer.previewImage(
                        new AttachmentContent(new AttachmentInfo(SHA, "image/png", 3, 1), new byte[] {1, 2, 3})));
    }

    private static ItemInfo item(ItemContent content) {
        return new ItemInfo("item", "thread", "turn", 1, "COMPLETED", content, Instant.EPOCH, Instant.EPOCH);
    }
}
