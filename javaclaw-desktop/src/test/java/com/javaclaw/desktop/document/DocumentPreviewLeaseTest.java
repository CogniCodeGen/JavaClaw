package com.javaclaw.desktop.document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.DocumentChunk;
import com.javaclaw.api.DocumentPreview;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DocumentPreviewLeaseTest {
    @Test
    void 相对导航后旧父续租不能阻止子版本续租或覆盖子版本() throws Exception {
        try (Fixture fixture = new Fixture()) {
            FxTestSupport.run(() -> {
                due(fixture.pane);
                assertEquals(List.of(fixture.gateway.parent.handleId()), fixture.gateway.handles);
                navigate(fixture.pane);
                fixture.gateway.resource.complete(fixture.gateway.child);
            });
            fixture.awaitChild();
            fixture.gateway.renewals.getFirst().complete(fixture.gateway.parent);
            FxTestSupport.run(() -> {
                assertSame(fixture.gateway.child, value(fixture.pane, "version"));
                due(fixture.pane);
                assertEquals(
                        List.of(fixture.gateway.parent.handleId(), fixture.gateway.child.handleId()),
                        fixture.gateway.handles);
            });
        }
    }

    @Test
    void 相对资源读取未完成时不能用新代次续租旧父句柄() throws Exception {
        try (Fixture fixture = new Fixture()) {
            FxTestSupport.run(() -> {
                navigate(fixture.pane);
                due(fixture.pane);
                assertEquals(List.of(), fixture.gateway.handles);
                fixture.gateway.resource.complete(fixture.gateway.child);
            });
            fixture.awaitChild();
            FxTestSupport.run(() -> {
                due(fixture.pane);
                assertEquals(List.of(fixture.gateway.child.handleId()), fixture.gateway.handles);
                fixture.gateway.renewals.getFirst().complete(fixture.gateway.child);
            });
            FxTestSupport.run(() -> assertSame(fixture.gateway.child, value(fixture.pane, "version")));
        }
    }

    private static void due(DocumentPreviewPane pane) {
        try {
            // 只推进单调时钟读数，生产续租入口及可见性判断保持真实，不等待一分钟墙钟时间。
            var timestamp = DocumentPreviewPane.class.getDeclaredField("lastRenewed");
            timestamp.setAccessible(true);
            timestamp.setLong(pane, System.nanoTime() - 61_000_000_000L);
            var renew = DocumentPreviewPane.class.getDeclaredMethod("renewVisible");
            renew.setAccessible(true);
            renew.invoke(pane);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void navigate(DocumentPreviewPane pane) {
        try {
            var open = DocumentPreviewPane.class.getDeclaredMethod("openResource", String.class);
            open.setAccessible(true);
            open.invoke(pane, "child.txt");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object value(DocumentPreviewPane pane, String name) {
        try {
            var field = DocumentPreviewPane.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(pane);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Gateway gateway = new Gateway();
        private final DocumentPreviewPane pane =
                FxTestSupport.call(() -> new DocumentPreviewPane(gateway, ignored -> {}));
        private final Stage stage;

        private Fixture() throws Exception {
            stage = FxTestSupport.call(() -> {
                Stage window = new Stage();
                window.setScene(new Scene(pane, 500, 600));
                window.show();
                pane.open(DocumentReference.message(gateway.workspace, ItemId.random(), "body"));
                return window;
            });
            FxTestSupport.await(() -> FxTestSupport.call(() -> value(pane, "content") != null));
        }

        private void awaitChild() {
            FxTestSupport.await(() -> FxTestSupport.call(
                    () -> value(pane, "version") == gateway.child && value(pane, "content") != null));
        }

        @Override
        public void close() {
            FxTestSupport.run(() -> {
                pane.close();
                stage.close();
            });
        }
    }

    private static final class Gateway implements DocumentPreviewGateway {
        private final byte[] bytes = "文档内容".getBytes(StandardCharsets.UTF_8);
        private final WorkspaceId workspace = WorkspaceId.random();
        private final DocumentPreview parent = version("parent.txt");
        private final DocumentPreview child = version("child.txt");
        private final CompletableFuture<DocumentPreview> resource = new CompletableFuture<>();
        private final List<String> handles = new ArrayList<>();
        private final List<CompletableFuture<DocumentPreview>> renewals = new ArrayList<>();

        private Gateway() throws Exception {}

        private DocumentPreview version(String name) throws Exception {
            return new DocumentPreview(
                    UUID.randomUUID().toString(),
                    workspace,
                    name,
                    "text/plain",
                    bytes.length,
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    DocumentPreview.Origin.CURRENT_FILE,
                    Instant.now().plusSeconds(300),
                    Optional.empty(),
                    false);
        }

        @Override
        public CompletionStage<DocumentPreview> resolve(DocumentReference reference) {
            return CompletableFuture.completedFuture(parent);
        }

        @Override
        public CompletionStage<DocumentChunk> read(String handle, long offset) {
            return CompletableFuture.completedFuture(new DocumentChunk(0, bytes, bytes.length, true, parent.digest()));
        }

        @Override
        public CompletionStage<DocumentPreview> resource(String handle, String href) {
            return resource;
        }

        @Override
        public CompletionStage<DocumentPreview> renew(String handle) {
            handles.add(handle);
            CompletableFuture<DocumentPreview> pending = new CompletableFuture<>();
            renewals.add(pending);
            return pending;
        }

        @Override
        public CompletionStage<Void> close(String handle) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
