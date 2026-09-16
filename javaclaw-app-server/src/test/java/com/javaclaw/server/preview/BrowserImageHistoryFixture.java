package com.javaclaw.server.preview;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BrowserResult;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.runtime.ModelImage;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.turn.AttachmentModelImages;

/** 真实 H2 浏览器截图历史夹具；所有图片为本地生成的 2×2 像素测试内容。 */
abstract class BrowserImageHistoryFixture extends PreviewServiceFixture {
    AttachmentModelImages resolver() {
        return new AttachmentModelImages(core, attachments, json);
    }

    ImageFixture image() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        byte[] png = output.toByteArray();
        var stored = attachments.store(
                AttachmentScope.workspace(workspace.id()),
                identity("attachment/internal/store", Map.of("image", "browser")),
                "image/png",
                png);
        var reference = new AttachmentRef(stored.digest(), "image/png", "page.png", stored.sizeBytes());
        var source = message(permission(1, List.of(workspace.root())), "观察页面", List.of());
        var thread = core.findTurn(source.turnId()).orElseThrow().threadId();
        var image = new ModelImage(reference, workspace.id(), thread, "frame", 2, 2);
        return new ImageFixture(source, image, png);
    }

    void call(ImageFixture fixture, String id, ItemStatus status) {
        new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock)
                .append(
                        fixture.source().turnId(),
                        CoreSchemas.TOOL_CALL,
                        CoreSchemas.TOOL_CALL,
                        new CorePayloads.ToolCall(
                                id, BuiltinExtensionIds.SITE, "browser_screenshot", 1, json.parse("{}")),
                        status);
    }

    void result(ImageFixture fixture, String id, boolean success) {
        var image = fixture.image();
        var owner = new BrowserContracts.Owner(workspace.id(), image.threadId(), Optional.empty());
        var uri = URI.create("https://example.test/");
        var lease = new BrowserContracts.AccessLease(
                BrowserContracts.ControlMode.ASSISTANT,
                "lease",
                1,
                clock.instant().plusSeconds(60),
                Set.of(uri));
        var session = new BrowserContracts.SessionView(
                "session", owner, BrowserContracts.SessionState.OPEN, lease, List.of());
        var page = new BrowserContracts.PageSnapshot("page", uri, "页面", "正文", List.of(), List.of());
        var frame = new BrowserContracts.Frame(
                image.observationId(), "page", 1, 1, new BrowserContracts.Viewport(2, 2, 0, 0, 1), 2, 2);
        var artifact = new BrowserContracts.Artifact(
                new BrowserContracts.FileSpec("page.png", "image/png"),
                image.attachment().sizeBytes());
        var result = new BrowserResult(
                1,
                new BrowserContracts.Observation(session, page, Optional.of(frame), Optional.of(artifact)),
                Optional.of(image.attachment()));
        append(
                fixture.source(),
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult(id, success, json.encode(result), Optional.empty()));
    }

    record ImageFixture(ItemEnvelope source, ModelImage image, byte[] png) {}
}
