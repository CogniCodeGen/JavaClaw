package com.javaclaw.desktop.view;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.extension.spi.ViewAttachmentPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewAttachmentFieldControlTest {
    private static final ViewAttachmentPolicy POLICY = new ViewAttachmentPolicy(java.util.Set.of("text/*"), 16);

    @Test
    void 初值只接受策略内Attachment且清除不泄露宿主路径() {
        FxTestSupport.run(() -> {
            UploadInteractions interactions = new UploadInteractions();
            Map<String, Object> initial = Map.of(
                    "digest", "a".repeat(64),
                    "mediaType", "text/plain",
                    "fileName", "note.txt",
                    "sizeBytes", "5");
            ViewAttachmentFieldControl field = control(initial, interactions);

            assertEquals("note.txt", field.value().orElseThrow().fileName());
            assertTrue(labels(field).stream().anyMatch(value -> value.contains("SHA-256")));
            button(field, "清除").fire();
            assertTrue(field.value().isEmpty());
            assertTrue(labels(field).stream().anyMatch(value -> value.contains("未选择文件")));

            assertThrows(IllegalArgumentException.class, () -> control("/tmp/secret", interactions));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> control(new AttachmentRef("b".repeat(64), "image/png", "image.png", 5), interactions));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> control(new AttachmentRef("c".repeat(64), "text/plain", "empty.txt", 0), interactions));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> control(new AttachmentRef("d".repeat(64), "text/plain", "large.txt", 17), interactions));
        });
    }

    @Test
    void 上传成功策略拒绝取消和异常都产生可理解状态() {
        FxTestSupport.run(() -> {
            UploadInteractions interactions = new UploadInteractions();
            ViewAttachmentFieldControl field = control(null, interactions);

            CompletableFuture<AttachmentRef> accepted = interactions.next();
            field.upload(Path.of("accepted.txt"));
            assertTrue(field.pending());
            accepted.complete(new AttachmentRef("a".repeat(64), "text/plain", "accepted.txt", 8));
            assertEquals("accepted.txt", field.value().orElseThrow().fileName());

            CompletableFuture<AttachmentRef> rejected = interactions.next();
            field.upload(Path.of("rejected.png"));
            rejected.complete(new AttachmentRef("b".repeat(64), "image/png", "rejected.png", 8));
            assertTrue(labels(field).stream().anyMatch(value -> value.contains("不符合 ViewSchema")));
            assertEquals("accepted.txt", field.value().orElseThrow().fileName());

            CompletableFuture<AttachmentRef> failed = interactions.next();
            field.upload(Path.of("failed.txt"));
            failed.completeExceptionally(new CompletionException(new IllegalStateException()));
            assertTrue(labels(field).stream().anyMatch(value -> value.contains("IllegalStateException")));

            CompletableFuture<AttachmentRef> cancelled = interactions.next();
            field.upload(Path.of("cancelled.txt"));
            cancelled.completeExceptionally(new TurnCancelledException("服务端取消"));
            assertTrue(labels(field).contains("上传已取消"));

            interactions.next();
            field.upload(Path.of("manual-cancel.txt"));
            button(field, "取消").fire();
            assertTrue(interactions.requests.getLast().cancellation().isCancelled());
            assertFalse(field.pending());

            interactions.throwSynchronously = true;
            field.upload(Path.of("sync-failure.txt"));
            assertTrue(labels(field).stream().anyMatch(value -> value.contains("同步失败")));
        });
    }

    @Test
    void 非JavaFX线程完成的SDK响应被安全调度回控件() {
        UploadInteractions interactions = new UploadInteractions();
        CompletableFuture<AttachmentRef> result = interactions.next();
        ViewAttachmentFieldControl[] holder = new ViewAttachmentFieldControl[1];
        FxTestSupport.run(() -> {
            holder[0] = control(null, interactions);
            holder[0].upload(Path.of("background.txt"));
        });

        result.complete(new AttachmentRef("e".repeat(64), "text/plain", "background.txt", 10));
        FxTestSupport.run(() ->
                assertEquals("background.txt", holder[0].value().orElseThrow().fileName()));
    }

    private static ViewAttachmentFieldControl control(Object initial, UploadInteractions interactions) {
        return new ViewAttachmentFieldControl("文件", POLICY, initial, interactions, new PlatformComponentFactory());
    }

    private static Button button(ViewAttachmentFieldControl field, String text) {
        return field.getChildren().stream()
                .filter(javafx.scene.layout.HBox.class::isInstance)
                .map(javafx.scene.layout.HBox.class::cast)
                .flatMap(value -> value.getChildren().stream())
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(value -> text.equals(value.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> labels(ViewAttachmentFieldControl field) {
        return field.getChildren().stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .map(Label::getText)
                .toList();
    }

    private static final class UploadInteractions implements ViewInteractionHandler {
        private final Queue<CompletableFuture<AttachmentRef>> results = new ArrayDeque<>();
        private final java.util.ArrayList<ViewAttachmentUploadRequest> requests = new java.util.ArrayList<>();
        private boolean throwSynchronously;

        private CompletableFuture<AttachmentRef> next() {
            CompletableFuture<AttachmentRef> result = new CompletableFuture<>();
            results.add(result);
            return result;
        }

        @Override
        public void dirty(String formId, boolean dirty) {}

        @Override
        public void execute(ViewCommandInvocation invocation) {}

        @Override
        public void reload() {}

        @Override
        public void page(String sourceId, ViewPageDirection direction) {}

        @Override
        public void select(String sourceId, Optional<String> selectedKey) {}

        @Override
        public CompletableFuture<AttachmentRef> upload(ViewAttachmentUploadRequest request) {
            requests.add(request);
            if (throwSynchronously) {
                throw new IllegalStateException("同步失败");
            }
            return results.remove();
        }
    }
}
