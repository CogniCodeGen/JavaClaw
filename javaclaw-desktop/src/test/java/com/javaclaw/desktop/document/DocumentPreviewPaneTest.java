package com.javaclaw.desktop.document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.DocumentChunk;
import com.javaclaw.api.DocumentPreview;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.web.WebSurfaceHost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentPreviewPaneTest {
    @Test
    void 点击含空格的Markdown相对链接向服务端保留原始目标并释放父版本() throws Exception {
        Gateway gateway = new Gateway("readme.md", "text/markdown", "[设计说明](<docs/design notes.md>)");
        var value = gateway.version;
        gateway.enableResource("design notes.md");
        DocumentPreviewPane pane = FxTestSupport.call(() -> new DocumentPreviewPane(gateway, ignored -> {}));
        Stage stage = showDocument(pane, gateway);
        try {
            WebSurfaceHost surface =
                    FxTestSupport.call(() -> (WebSurfaceHost) pane.getChildren().getLast());
            FxTestSupport.await(() -> FxTestSupport.call(() -> surface.acknowledged()
                    && pane.lookupAll(".text-area").stream()
                            .map(TextArea.class::cast)
                            .anyMatch(area -> area.getText().contains("设计说明"))));
            FxTestSupport.run(() -> surface.getChildren().stream()
                    .filter(WebView.class::isInstance)
                    .map(WebView.class::cast)
                    .findFirst()
                    .orElseThrow()
                    .getEngine()
                    .executeScript("document.querySelector('[data-link=\"doc:0\"]').click()"));
            FxTestSupport.await(() -> "docs/design notes.md".equals(gateway.resourceHref));
            FxTestSupport.await(() -> gateway.closed.contains(value.handleId()));
            FxTestSupport.run(() -> assertTrue(pane.getChildren().stream()
                    .filter(Label.class::isInstance)
                    .map(Label.class::cast)
                    .anyMatch(label -> label.getText().equals("design notes.md"))));
        } finally {
            FxTestSupport.run(() -> {
                pane.close();
                stage.close();
            });
        }
    }

    private Stage showDocument(DocumentPreviewPane pane, Gateway gateway) {
        return FxTestSupport.call(() -> {
            Stage window = new Stage();
            Scene scene = new Scene(pane, 500, 540);
            DesktopStylesheets.apply(scene);
            window.setScene(scene);
            window.show();
            gateway.resolved.complete(gateway.version);
            pane.open(DocumentReference.message(gateway.version.workspaceId(), ItemId.random(), "body"));
            return window;
        });
    }

    @Test
    void 不支持格式保留已验证名称和大小并释放句柄且不读取正文() throws Exception {
        Gateway gateway = new Gateway("report.pdf", "application/pdf");
        DocumentPreviewPane pane = FxTestSupport.call(() -> new DocumentPreviewPane(gateway, ignored -> {}));
        Stage stage = FxTestSupport.call(() -> {
            Stage window = new Stage();
            Scene scene = new Scene(pane, 360, 540);
            DesktopStylesheets.apply(scene);
            window.setScene(scene);
            window.show();
            gateway.resolved.complete(gateway.version);
            pane.open(DocumentReference.message(gateway.version.workspaceId(), ItemId.random(), "body"));
            return window;
        });
        try {
            FxTestSupport.await(() -> FxTestSupport.call(() -> pane.getChildren().stream()
                    .filter(Label.class::isInstance)
                    .map(Label.class::cast)
                    .anyMatch(label -> label.getText().contains("此格式暂不支持预览"))));
            FxTestSupport.run(() -> {
                var labels = pane.getChildren().stream()
                        .filter(Label.class::isInstance)
                        .map(Label.class::cast)
                        .map(Label::getText)
                        .toList();
                assertTrue(labels.contains("report.pdf"));
                assertTrue(labels.stream().anyMatch(text -> text.startsWith(gateway.version.sizeBytes() + " 字节 · ")));
                assertTrue(pane.lookupAll(".text-area").stream()
                        .map(TextArea.class::cast)
                        .allMatch(area -> area.getText().isEmpty()));
                assertTrue(pane.lookupAll(".button").stream()
                        .map(Button.class::cast)
                        .anyMatch(button -> button.getText().equals("重新读取") && !button.isDisable()));
                assertTrue(gateway.closed.contains(gateway.version.handleId()));
                assertEquals(0, gateway.reads.get());
            });
        } finally {
            FxTestSupport.run(() -> {
                pane.close();
                stage.close();
            });
        }
    }

    @Test
    void 关闭后迟到的版本必须释放且不能重新展示正文() throws Exception {
        Gateway gateway = new Gateway();
        AtomicReference<DocumentPreviewPane> pane = new AtomicReference<>();
        FxTestSupport.run(() -> {
            pane.set(new DocumentPreviewPane(gateway, ignored -> {}));
            pane.get().open(DocumentReference.message(gateway.version.workspaceId(), ItemId.random(), "body"));
            pane.get().clear();
            gateway.resolved.complete(gateway.version);
        });
        FxTestSupport.await(() -> gateway.closed.contains(gateway.version.handleId()));
        FxTestSupport.run(() -> {
            assertTrue(pane.get().lookupAll(".text-area").stream()
                    .map(TextArea.class::cast)
                    .allMatch(area -> area.getText().isEmpty()));
            pane.get().close();
        });
    }

    @Test
    void 解析完成且版本接收已排队时切换仍释放旧句柄() throws Exception {
        Gateway gateway = new Gateway();
        DocumentPreviewPane pane = FxTestSupport.call(() -> new DocumentPreviewPane(gateway, ignored -> {}));
        try {
            FxTestSupport.run(() -> {
                gateway.resolved.complete(gateway.version);
                pane.open(DocumentReference.message(gateway.version.workspaceId(), ItemId.random(), "body"));
                // Future 完成回调已把接管安排到 FX 队列，当前 FX 回调里的 clear 必须先执行。
                pane.clear();
            });
            FxTestSupport.run(() -> {
                assertTrue(gateway.closed.contains(gateway.version.handleId()));
                assertTrue(pane.lookupAll(".text-area").stream()
                        .map(TextArea.class::cast)
                        .allMatch(area -> area.getText().isEmpty()));
            });
        } finally {
            FxTestSupport.run(pane::close);
        }
    }

    @Test
    void 旧读取占用worker时新解析已完成但未开始读取也必须关闭句柄() throws Exception {
        Gateway gateway = new Gateway();
        gateway.readGate = new CountDownLatch(1);
        gateway.resolved.complete(gateway.version);
        DocumentPreviewPane pane = FxTestSupport.call(() -> new DocumentPreviewPane(gateway, ignored -> {}));
        DocumentPreview pending = new DocumentPreview(
                UUID.randomUUID().toString(),
                gateway.version.workspaceId(),
                "pending.txt",
                "text/plain",
                gateway.version.sizeBytes(),
                gateway.version.digest(),
                gateway.version.origin(),
                gateway.version.expiresAt(),
                Optional.empty(),
                false);
        try {
            FxTestSupport.run(
                    () -> pane.open(DocumentReference.message(gateway.version.workspaceId(), ItemId.random(), "body")));
            assertTrue(gateway.reading.await(5, TimeUnit.SECONDS));
            gateway.selected = pending;
            FxTestSupport.run(() -> {
                pane.open(DocumentReference.message(gateway.version.workspaceId(), ItemId.random(), "body"));
                pane.clear();
            });
            FxTestSupport.run(() -> assertTrue(gateway.closed.contains(pending.handleId())));
            assertEquals(1, gateway.reads.get());
        } finally {
            gateway.readGate.countDown();
            FxTestSupport.run(pane::close);
        }
    }

    @Test
    void 服务端撤权立即清空正文而不会影响不同句柄() throws Exception {
        Gateway gateway = new Gateway();
        AtomicReference<DocumentPreviewPane> pane = new AtomicReference<>();
        FxTestSupport.run(() -> {
            pane.set(new DocumentPreviewPane(gateway, ignored -> {}));
            gateway.resolved.complete(gateway.version);
            pane.get().open(DocumentReference.message(gateway.version.workspaceId(), ItemId.random(), "body"));
        });
        FxTestSupport.await(() -> FxTestSupport.call(() -> pane.get().lookupAll(".text-area").stream()
                .map(TextArea.class::cast)
                .anyMatch(area -> area.getText().contains("只读正文"))));
        FxTestSupport.run(() -> {
            pane.get().invalidate(UUID.randomUUID().toString(), "REVOKED");
            assertTrue(pane.get().lookupAll(".text-area").stream()
                    .map(TextArea.class::cast)
                    .anyMatch(area -> area.getText().contains("只读正文")));
            pane.get().invalidate(gateway.version.handleId(), "REVOKED");
            assertTrue(pane.get().lookupAll(".text-area").stream()
                    .map(TextArea.class::cast)
                    .allMatch(area -> area.getText().isEmpty()));
            assertTrue(pane.get().getChildren().stream()
                    .filter(Label.class::isInstance)
                    .map(Label.class::cast)
                    .anyMatch(label -> label.getText().contains("权限已变化")));
            pane.get().close();
        });
    }

    private static final class Gateway implements DocumentPreviewGateway {
        private final byte[] bytes;
        private final CompletableFuture<DocumentPreview> resolved = new CompletableFuture<>();
        private final CopyOnWriteArrayList<String> closed = new CopyOnWriteArrayList<>();
        private final AtomicInteger reads = new AtomicInteger();
        private final CountDownLatch reading = new CountDownLatch(1);
        private final DocumentPreview version;
        private volatile DocumentPreview selected;
        private volatile CountDownLatch readGate;
        private volatile DocumentPreview resourceVersion;
        private volatile String resourceHref;

        private Gateway() throws Exception {
            this("readme.txt", "text/plain");
        }

        private Gateway(String name, String mediaType) throws Exception {
            this(name, mediaType, "只读正文");
        }

        private Gateway(String name, String mediaType, String text) throws Exception {
            bytes = text.getBytes(StandardCharsets.UTF_8);
            String digest = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            version = new DocumentPreview(
                    UUID.randomUUID().toString(),
                    WorkspaceId.random(),
                    name,
                    mediaType,
                    bytes.length,
                    digest,
                    DocumentPreview.Origin.REFERENCED_VERSION,
                    Instant.now().plusSeconds(300),
                    Optional.empty(),
                    false);
        }

        private void enableResource(String name) {
            resourceVersion = new DocumentPreview(
                    UUID.randomUUID().toString(),
                    version.workspaceId(),
                    name,
                    version.mediaType(),
                    version.sizeBytes(),
                    version.digest(),
                    version.origin(),
                    version.expiresAt(),
                    Optional.empty(),
                    false);
        }

        @Override
        public CompletionStage<DocumentPreview> resolve(DocumentReference reference) {
            return selected == null ? resolved : CompletableFuture.completedFuture(selected);
        }

        @Override
        public CompletionStage<DocumentChunk> read(String handle, long offset) {
            reads.incrementAndGet();
            reading.countDown();
            CountDownLatch gate = readGate;
            boolean interrupted = false;
            while (gate != null && gate.getCount() > 0) {
                try {
                    gate.await();
                } catch (InterruptedException cancellation) {
                    // 精确模拟底层尚未响应取消的旧读取；新句柄所有权不能依赖这个 worker 退出。
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(new DocumentChunk(0, bytes, bytes.length, true, version.digest()));
        }

        @Override
        public CompletionStage<DocumentPreview> resource(String handle, String href) {
            if (resourceVersion != null) {
                assertEquals(version.handleId(), handle);
                resourceHref = href;
                return CompletableFuture.completedFuture(resourceVersion);
            }
            return CompletableFuture.failedFuture(new IllegalStateException("没有相对资源"));
        }

        @Override
        public CompletionStage<DocumentPreview> renew(String handle) {
            return CompletableFuture.completedFuture(version);
        }

        @Override
        public CompletionStage<Void> close(String handle) {
            closed.add(handle);
            return CompletableFuture.completedFuture(null);
        }
    }
}
