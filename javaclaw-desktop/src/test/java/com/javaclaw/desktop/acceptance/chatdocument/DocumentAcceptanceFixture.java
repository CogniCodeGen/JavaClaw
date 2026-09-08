package com.javaclaw.desktop.acceptance.chatdocument;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import com.javaclaw.api.DocumentChunk;
import com.javaclaw.api.DocumentPreview;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.document.DocumentPreviewGateway;
import com.javaclaw.desktop.document.DocumentPreviewPane;
import com.javaclaw.desktop.web.WebSurfaceHost;

final class DocumentAcceptanceFixture implements AutoCloseable {
    final Gateway gateway = new Gateway();
    final DocumentPreviewPane pane;
    final Stage stage;

    DocumentAcceptanceFixture() {
        this(520);
    }

    DocumentAcceptanceFixture(int width) {
        pane = FxTestSupport.call(() -> new DocumentPreviewPane(gateway, gateway.external::add));
        stage = FxTestSupport.call(() -> {
            Scene scene = new Scene(pane, width, 620);
            DesktopStylesheets.apply(scene);
            Stage window = new Stage();
            window.setScene(scene);
            window.show();
            return window;
        });
    }

    DocumentPreview open(String name, String mediaType, byte[] content, Optional<Integer> line) throws Exception {
        DocumentPreview preview = gateway.add(name, mediaType, content, line);
        gateway.selected = preview;
        FxTestSupport.run(() -> pane.open(DocumentReference.message(gateway.workspace, ItemId.random(), "body")));
        return preview;
    }

    void await(String text) {
        FxTestSupport.await(() -> FxTestSupport.call(() -> surface().acknowledged()
                && engine().executeScript("document.getElementById('surface').textContent")
                        .toString()
                        .contains(text)));
    }

    void awaitBlank() {
        FxTestSupport.await(() -> FxTestSupport.call(() -> surface().acknowledged()
                && engine().executeScript("document.getElementById('surface').textContent.trim()")
                        .toString()
                        .isEmpty()
                && engine().executeScript("document.querySelector('#surface img')===null")
                        .equals(Boolean.TRUE)));
    }

    void awaitMetadata(String text) {
        FxTestSupport.await(() -> FxTestSupport.call(() -> pane.getChildren().stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .anyMatch(label -> label.getText().contains(text))));
    }

    String script(String code) {
        return FxTestSupport.call(() -> String.valueOf(engine().executeScript(code)));
    }

    void click(String text) {
        FxTestSupport.run(() -> pane.lookupAll(".button").stream()
                .map(Button.class::cast)
                .filter(button -> button.getText().equals(text))
                .findFirst()
                .orElseThrow()
                .fire());
    }

    WebSurfaceHost surface() {
        return pane.getChildren().stream()
                .filter(WebSurfaceHost.class::isInstance)
                .map(WebSurfaceHost.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private WebEngine engine() {
        return surface().getChildren().stream()
                .filter(WebView.class::isInstance)
                .map(WebView.class::cast)
                .findFirst()
                .orElseThrow()
                .getEngine();
    }

    static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] image(String format) throws Exception {
        BufferedImage picture = new BufferedImage(360, 180, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < picture.getHeight(); y++) {
            for (int x = 0; x < picture.getWidth(); x++) {
                picture.setRGB(x, y, (x / 60 + y / 60) % 2 == 0 ? 0x276C58 : 0xEDF7F1);
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(picture, format, bytes);
        return bytes.toByteArray();
    }

    @Override
    public void close() {
        FxTestSupport.run(() -> {
            pane.close();
            stage.close();
        });
    }

    static final class Gateway implements DocumentPreviewGateway {
        final WorkspaceId workspace = WorkspaceId.random();
        final List<String> closed = new CopyOnWriteArrayList<>();
        final List<String> resources = new CopyOnWriteArrayList<>();
        final List<java.net.URI> external = new CopyOnWriteArrayList<>();
        final Map<String, DocumentPreview> named = new ConcurrentHashMap<>();
        private final Map<String, byte[]> bytes = new ConcurrentHashMap<>();
        private final Map<String, DocumentPreview> versions = new ConcurrentHashMap<>();
        DocumentPreview selected;

        DocumentPreview add(String name, String type, byte[] data, Optional<Integer> line) throws Exception {
            String digest = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            DocumentPreview version = new DocumentPreview(
                    UUID.randomUUID().toString(),
                    workspace,
                    name,
                    type,
                    data.length,
                    digest,
                    DocumentPreview.Origin.REFERENCED_VERSION,
                    Instant.now().plusSeconds(300),
                    line,
                    false);
            named.put(name, version);
            bytes.put(version.handleId(), data);
            versions.put(version.handleId(), version);
            return version;
        }

        @Override
        public CompletionStage<DocumentPreview> resolve(DocumentReference reference) {
            return CompletableFuture.completedFuture(selected);
        }

        @Override
        public CompletionStage<DocumentChunk> read(String handle, long offset) {
            byte[] source = bytes.get(handle);
            int end = (int) Math.min(source.length, offset + 32_768);
            return CompletableFuture.completedFuture(new DocumentChunk(
                    offset,
                    Arrays.copyOfRange(source, (int) offset, end),
                    end,
                    end == source.length,
                    versions.get(handle).digest()));
        }

        @Override
        public CompletionStage<DocumentPreview> resource(String handle, String href) {
            resources.add(href);
            DocumentPreview result = named.get(href);
            return result == null
                    ? CompletableFuture.failedFuture(new IllegalArgumentException("引用不可用"))
                    : CompletableFuture.completedFuture(result);
        }

        @Override
        public CompletionStage<DocumentPreview> renew(String handle) {
            return CompletableFuture.completedFuture(versions.get(handle));
        }

        @Override
        public CompletionStage<Void> close(String handle) {
            closed.add(handle);
            return CompletableFuture.completedFuture(null);
        }
    }
}
