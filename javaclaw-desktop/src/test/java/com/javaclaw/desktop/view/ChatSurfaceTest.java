package com.javaclaw.desktop.view;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSurfaceTest {
    @Test
    void 历史摘要的围栏附件和相对链接都保留来源身份且危险链接没有业务目标() {
        try (Harness fixture = new Harness()) {
            ItemId id = ItemId.random();
            AttachmentRef attachment = new AttachmentRef("a".repeat(64), "text/markdown", "说明.md", 10);
            String markdown = "[本地](<docs/design notes.md>) [官网](<https://example.com/reference guide>) [锚点](#a)"
                    + " [危险](javascript:alert)\n\n"
                    + "```java\n"
                    + "int count = 1;\n"
                    + "```";
            ItemHistoryEntry entry = new ItemHistoryEntry(
                    id,
                    TurnId.random(),
                    1,
                    "message",
                    Optional.of(MessageRole.USER),
                    markdown,
                    Optional.of(DocumentReference.message(fixture.workspace, id, "body")),
                    false,
                    Instant.EPOCH,
                    List.of(attachment),
                    List.of(DocumentReference.file(fixture.workspace, id, "file:0")));
            fixture.show(List.of(), List.of(entry), List.of());
            fixture.await("查看代码 1");
            FxTestSupport.run(() -> {
                for (String target : List.of(
                        id + ":fence:0",
                        id + ":attachment:" + attachment.digest(),
                        id + ":file:0",
                        id + ":link:0",
                        id + ":body")) {
                    fixture.post("preview", target);
                }
                fixture.post("link", id + ":link:1");
                fixture.post("link", id + ":link:2");
                fixture.post("link", id + ":link:3");
                fixture.post("preview", "伪造句柄");
                fixture.post("history", "");
                fixture.post("following", "false");
                assertEquals(5, fixture.previews.size());
                assertTrue(fixture.previews.stream()
                        .allMatch(ref -> ref.sourceItemId().orElseThrow().equals(id)));
                assertEquals(List.of(URI.create("https://example.com/reference%20guide")), fixture.external);
                assertEquals(1, fixture.history.get());
                assertFalse(fixture.following.getLast());
                assertEquals(
                        "1", fixture.engine().executeScript("String(document.querySelectorAll('article').length)"));
            });
        }
    }

    @Test
    void 暂态按最终身份去重且尾部不作为完整Markdown并保留未完成标志() {
        try (Harness fixture = new Harness()) {
            ItemId committed = ItemId.random();
            var entry = new ItemHistoryEntry(
                    committed,
                    TurnId.random(),
                    1,
                    "message",
                    Optional.empty(),
                    "权威摘要",
                    Optional.empty(),
                    true,
                    Instant.EPOCH,
                    List.of(),
                    List.of());
            fixture.show(
                    List.of(),
                    List.of(entry),
                    List.of(
                            new ChatSurface.TemporaryMessage(committed.toString(), "不得重复", false),
                            new ChatSurface.TemporaryMessage("empty", "", false),
                            new ChatSurface.TemporaryMessage("tail", "**末尾**", false, 10),
                            new ChatSurface.TemporaryMessage("interrupted", "<未完成>", true)));
            fixture.await("未完成");
            FxTestSupport.run(() -> {
                String text = fixture.engine()
                        .executeScript("document.getElementById('surface').textContent")
                        .toString();
                assertFalse(text.contains("不得重复"));
                assertTrue(text.contains("正文较长；显示末尾"));
                assertTrue(text.contains("**末尾**"));
                assertTrue(text.contains("ASSISTANT · 未完成"));
                assertEquals(
                        "3", fixture.engine().executeScript("String(document.querySelectorAll('article').length)"));
                assertEquals(
                        "0",
                        fixture.engine()
                                .executeScript("String(document.querySelectorAll('[data-id=tail] strong').length)"));
            });
            fixture.show(
                    List.of(), List.of(), List.of(new ChatSurface.TemporaryMessage("long", "甲".repeat(40_000), false)));
            fixture.await("显示正文末尾");
            FxTestSupport.run(() -> assertTrue(fixture.engine()
                            .executeScript("document.getElementById('surface').textContent")
                            .toString()
                            .length()
                    < 33_000));
        }
    }

    @Test
    void 完整消息附件和大正文入口可点击且缓存重放不改变来源() {
        try (Harness fixture = new Harness()) {
            AttachmentRef attachment = new AttachmentRef("b".repeat(64), "image/png", "图.png", 1);
            ItemEnvelope shortItem = message(
                    "[相对](<docs/design notes.md>) [远程](<http://example.com/reference guide>)\n\n```java\nint i=0;\n```",
                    List.of(attachment));
            fixture.show(List.of(shortItem), List.of(), List.of());
            fixture.await("查看代码 1");
            fixture.show(List.of(shortItem), List.of(), List.of());
            FxTestSupport.run(() -> {
                fixture.post("preview", shortItem.id() + ":fence:0");
                // 真实 DOM 点击经过 WebView bridge，再按原始 Link 索引解析服务端来源。
                fixture.engine()
                        .executeScript(
                                "document.querySelector('[data-link=\"" + shortItem.id() + ":link:0\"]').click()");
                fixture.engine()
                        .executeScript(
                                "document.querySelector('[data-link=\"" + shortItem.id() + ":link:1\"]').click()");
                assertEquals(2, fixture.previews.size());
                assertEquals(List.of(URI.create("http://example.com/reference%20guide")), fixture.external);
            });
            ItemEnvelope large = message("大".repeat(1_048_577), List.of());
            fixture.show(List.of(large), List.of(), List.of());
            fixture.await("打开完整正文");
            FxTestSupport.run(() -> {
                fixture.post("preview", large.id() + ":body");
                assertEquals(
                        DocumentReference.message(fixture.workspace, large.id(), "body"), fixture.previews.getLast());
                assertTrue(fixture.engine()
                                .executeScript("document.getElementById('surface').textContent")
                                .toString()
                                .length()
                        < 66_000);
            });
        }
    }

    private static ItemEnvelope message(String text, List<AttachmentRef> attachments) {
        return new ItemEnvelope(
                ItemId.random(),
                TurnId.random(),
                1,
                "message",
                CoreSchemas.MESSAGE,
                "core",
                ItemStatus.COMPLETED,
                new CanonicalJson()
                        .encode(new CorePayloads.Message(MessageRole.ASSISTANT, text, attachments, Optional.empty())),
                Instant.EPOCH,
                Optional.of(Instant.EPOCH));
    }

    private static final class Harness implements AutoCloseable {
        private final WorkspaceId workspace = WorkspaceId.random();
        private final List<DocumentReference> previews = new ArrayList<>();
        private final List<URI> external = new ArrayList<>();
        private final List<Boolean> following = new ArrayList<>();
        private final AtomicInteger history = new AtomicInteger();
        private final ChatSurface chat;
        private final Stage stage;

        private Harness() {
            chat = FxTestSupport.call(() -> new ChatSurface(
                    new Label("简版"), previews::add, external::add, history::incrementAndGet, following::add));
            stage = FxTestSupport.call(() -> {
                Stage result = new Stage();
                Scene scene = new Scene(chat.node(), 760, 500);
                DesktopStylesheets.apply(scene);
                result.setScene(scene);
                result.show();
                return result;
            });
        }

        private void show(
                List<ItemEnvelope> items,
                List<ItemHistoryEntry> summaries,
                List<ChatSurface.TemporaryMessage> temporary) {
            chat.show("thread", workspace, items, summaries, temporary, true);
        }

        private void await(String text) {
            FxTestSupport.await(() -> FxTestSupport.call(() -> chat.node().acknowledged()
                    && engine().executeScript("document.getElementById('surface').textContent")
                            .toString()
                            .contains(text)));
        }

        private void post(String action, String value) {
            engine().executeScript("window.JavaClawSurface.post('" + action + "','" + value + "')");
        }

        private WebEngine engine() {
            return chat.node().getChildren().stream()
                    .filter(WebView.class::isInstance)
                    .map(WebView.class::cast)
                    .findFirst()
                    .orElseThrow()
                    .getEngine();
        }

        @Override
        public void close() {
            FxTestSupport.run(() -> {
                chat.close();
                stage.close();
            });
        }
    }
}
