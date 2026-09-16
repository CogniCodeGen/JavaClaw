package com.javaclaw.server.preview;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.MessageRole;
import com.javaclaw.runtime.ModelImage;
import com.javaclaw.server.turn.AttachmentModelImages;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttachmentModelImagesTest extends PreviewServiceFixture {
    @Test
    void 图片只有当前对话持久消息引用才能进入模型且尺寸不可伪造() throws Exception {
        byte[] png = png();
        var scope = AttachmentScope.workspace(workspace.id());
        var stored =
                attachments.store(scope, identity("attachment/internal/store", Map.of("image", 1)), "image/png", png);
        var reference = new AttachmentRef(stored.digest(), "image/png", "screenshot.png", stored.sizeBytes());
        var item = message(permission(1, List.of(workspace.root())), "查看截图", List.of(reference));
        var thread = core.findTurn(item.turnId()).orElseThrow().threadId();
        var images = new AttachmentModelImages(core, attachments, json);
        var projected =
                images.message(workspace.id(), thread, item, json.decode(item.payload(), CorePayloads.Message.class));
        assertEquals(1, projected.size());
        ModelImage image = projected.getFirst();
        assertArrayEquals(png, images.resolve(image));
        assertArrayEquals(png, images.forTurn(item.turnId()).resolve(image));
        assertThrows(
                SecurityException.class,
                () -> images.resolve(new ModelImage(reference, workspace.id(), thread, image.observationId(), 3, 3)));
        var other = message(permission(1, List.of(workspace.root())), "无附件", List.of());
        var otherThread = core.findTurn(other.turnId()).orElseThrow().threadId();
        assertThrows(
                SecurityException.class, () -> images.forTurn(other.turnId()).resolve(image));
        assertThrows(
                SecurityException.class,
                () -> images.resolve(
                        new ModelImage(reference, workspace.id(), otherThread, image.observationId(), 2, 2)));
    }

    @Test
    void 助手消息附件不能伪装为用户图片输入() throws Exception {
        byte[] png = png();
        var stored = attachments.store(
                AttachmentScope.workspace(workspace.id()),
                identity("attachment/internal/store", Map.of("image", 2)),
                "image/png",
                png);
        var reference = new AttachmentRef(stored.digest(), "image/png", "screenshot.png", stored.sizeBytes());
        var source = message(permission(1, List.of(workspace.root())), "问题", List.of());
        var response = new CorePayloads.Message(MessageRole.ASSISTANT, "附件", List.of(reference), Optional.empty());
        var item = append(source, CoreSchemas.MESSAGE, response);
        var thread = core.findTurn(item.turnId()).orElseThrow().threadId();
        var images = new AttachmentModelImages(core, attachments, json);
        assertThrows(SecurityException.class, () -> images.message(workspace.id(), thread, item, response));
        assertThrows(
                SecurityException.class,
                () -> images.resolve(
                        new ModelImage(reference, workspace.id(), thread, item.id() + ":" + reference.digest(), 2, 2)));
    }

    private static byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}
