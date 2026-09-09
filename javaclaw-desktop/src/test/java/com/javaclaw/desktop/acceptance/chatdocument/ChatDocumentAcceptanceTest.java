package com.javaclaw.desktop.acceptance.chatdocument;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnId;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.view.ChatSurface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatDocumentAcceptanceTest {
    @Test
    void 普通历史对话不显示文档按钮而真实文档保留可点击超链接并保存截图() {
        try (ChatAcceptanceFixture fixture = new ChatAcceptanceFixture()) {
            AttachmentRef attachment = new AttachmentRef("d".repeat(64), "text/markdown", "交互说明.md", 120);
            ItemHistoryEntry user = history(fixture, 1, MessageRole.USER, "请解释配置完成后怎样开始聊天。", List.of());
            ItemHistoryEntry assistant = history(fixture, 2, MessageRole.ASSISTANT, "保存模型后，回到对话即可开始输入。", List.of());
            ItemHistoryEntry document =
                    history(fixture, 3, MessageRole.ASSISTANT, "交互说明已经生成，可以查看下方文档。", List.of(attachment));
            fixture.chat.show(
                    "history-document-links",
                    fixture.workspace,
                    List.of(),
                    List.of(user, assistant, document),
                    List.of(),
                    false);
            fixture.await("交互说明.md");
            assertEquals("1", fixture.script("String(document.querySelectorAll('article a.attachment').length)"));
            assertEquals("0", fixture.script("String(document.querySelectorAll('article button.attachment').length)"));
            assertFalse(fixture.script("document.getElementById('surface').textContent")
                    .contains("打开文档"));
            assertFalse(fixture.script("document.getElementById('surface').textContent")
                    .contains("查看完整消息"));
            fixture.script("document.querySelector('a.attachment').click()");
            assertEquals(
                    List.of(DocumentReference.attachment(fixture.workspace, document.id(), attachment)),
                    fixture.previews);
            AcceptanceCapture.save(
                    fixture.stage.getScene(),
                    "chat-history-document-links",
                    Map.of("ordinaryMessages", 2, "documentHyperlinks", 1, "typedDocumentClick", true));
        }
    }

    @Test
    void 同一内容复用原生单元格与WebView并保存样式对照() {
        List<ItemEnvelope> items = List.of(
                ChatAcceptanceFixture.message(ItemId.random(), 1, MessageRole.USER, "请说明如何读取聊天中引用的文件。", List.of()),
                ChatAcceptanceFixture.message(
                        ItemId.random(),
                        2,
                        MessageRole.ASSISTANT,
                        "点击文件卡片后，右侧文档面板会显示引用版本。\n输入、审批和工作空间选择仍由原生控件负责。",
                        List.of()));
        Stage nativeWindow = FxTestSupport.call(() -> ChatAcceptanceFixture.nativeTranscript(items));
        try (ChatAcceptanceFixture fixture = new ChatAcceptanceFixture()) {
            fixture.show("comparison", items, List.of());
            fixture.await("工作空间选择");
            AcceptanceCapture.save(
                    nativeWindow.getScene(),
                    "chat-native-reference",
                    Map.of(
                            "source",
                            "当前FXML原生TranscriptCell",
                            "sameItems",
                            true,
                            "nativeStyle",
                            ChatAcceptanceFixture.nativeStyle(nativeWindow)));
            AcceptanceCapture.save(
                    fixture.stage.getScene(),
                    "chat-web-comparison",
                    Map.of(
                            "source",
                            "生产ChatSurface",
                            "sameItems",
                            true,
                            "webStyle",
                            fixture.script(
                                    "JSON.stringify((()=>{const n=document.querySelector('article');const"
                                            + " s=getComputedStyle(n);const"
                                            + " r=getComputedStyle(n.querySelector('.role'));return"
                                            + " {background:s.backgroundColor,border:s.borderColor,padding:s.padding,font:s.font,role:r.font};})())")));
            assertEquals("2", fixture.script("String(document.querySelectorAll('article').length)"));
        } finally {
            FxTestSupport.run(nativeWindow::close);
        }
    }

    @Test
    void 欢迎区居中及角色字号字重沿用原生语义并保存空态对照() {
        Stage nativeWindow = FxTestSupport.call(() -> ChatAcceptanceFixture.nativeTranscript(List.of()));
        try (ChatAcceptanceFixture fixture = new ChatAcceptanceFixture()) {
            fixture.show("welcome", List.of(), List.of());
            fixture.await("有什么我可以帮你的？");
            assertEquals(
                    "24px", fixture.script("getComputedStyle(document.querySelector('.welcome strong')).fontSize"));
            assertEquals("44px", fixture.script("getComputedStyle(document.querySelector('.welcome span')).fontSize"));
            assertEquals(
                    250,
                    Double.parseDouble(fixture.script(
                            "String(document.querySelector('.welcome').getBoundingClientRect().height/2)")),
                    1);
            AcceptanceCapture.save(nativeWindow.getScene(), "chat-native-welcome", Map.of("source", "当前FXML原生欢迎区"));
            AcceptanceCapture.save(
                    fixture.stage.getScene(), "chat-web-welcome", Map.of("titlePx", 24, "markPx", 44, "centerY", 250));
            fixture.show(
                    "welcome",
                    List.of(ChatAcceptanceFixture.message(ItemId.random(), 1, MessageRole.USER, "角色样式", List.of())),
                    List.of());
            fixture.await("角色样式");
            assertEquals("11px", fixture.script("getComputedStyle(document.querySelector('.role')).fontSize"));
            assertEquals("700", fixture.script("getComputedStyle(document.querySelector('.role')).fontWeight"));
        } finally {
            FxTestSupport.run(nativeWindow::close);
        }
    }

    @Test
    void 流式完成替换同一消息且真实代码文件入口长正文和切换均可操作() {
        ItemId id = ItemId.random();
        AttachmentRef file = new AttachmentRef("a".repeat(64), "text/markdown", "说明.md", 20);
        try (ChatAcceptanceFixture fixture = new ChatAcceptanceFixture()) {
            fixture.show(
                    "flow", List.of(), List.of(new ChatSurface.TemporaryMessage(id.toString(), "正在说明读取步骤…", false)));
            fixture.await("正在说明");
            AcceptanceCapture.save(
                    fixture.stage.getScene(), "chat-streaming", Map.of("messageIdentity", id.toString()));
            ItemEnvelope complete = ChatAcceptanceFixture.message(
                    id,
                    1,
                    MessageRole.ASSISTANT,
                    "读取结果如下：\n\n```java\nString title = \"引用文档\";\n```\n\n[查看说明](docs/readme.md)",
                    List.of(file));
            fixture.show(
                    "flow",
                    List.of(complete),
                    List.of(new ChatSurface.TemporaryMessage(id.toString(), "旧暂态不得重复", false)));
            fixture.await("查看代码 1");
            assertEquals("1", fixture.script("String(document.querySelectorAll('article').length)"));
            assertFalse(fixture.script("document.body.textContent").contains("旧暂态"));
            fixture.script("[...document.querySelectorAll('a.attachment')].find(n=>n.textContent==='查看代码 1').click()");
            fixture.script("[...document.querySelectorAll('a.attachment')].find(n=>n.textContent==='说明.md').click()");
            assertEquals(2, fixture.previews.size());
            assertTrue(fixture.previews.stream()
                    .allMatch(ref -> ref.sourceItemId().orElseThrow().equals(id)));
            AcceptanceCapture.save(
                    fixture.stage.getScene(),
                    "chat-completed-references",
                    Map.of("uniqueMessages", 1, "clickedTypedReferences", 2));
            ItemEnvelope large =
                    ChatAcceptanceFixture.message(id, 1, MessageRole.ASSISTANT, "长正文".repeat(350_000), List.of());
            fixture.show("flow", List.of(large), List.of());
            fixture.await("查看完整消息");
            fixture.script("[...document.querySelectorAll('a.attachment')].find(n=>n.textContent==='查看完整消息').click()");
            assertEquals("body", fixture.previews.getLast().selector());
            assertTrue(Integer.parseInt(fixture.script("String(document.getElementById('surface').textContent.length)"))
                    < 66_000);
            AcceptanceCapture.save(
                    fixture.stage.getScene(),
                    "chat-large-body",
                    Map.of("sourceUtf16", 1_050_000, "domTextBounded", true));
            fixture.show("other-thread", List.of(), List.of(new ChatSurface.TemporaryMessage("new", "另一个对话", false)));
            fixture.await("另一个对话");
            assertFalse(fixture.script("document.body.textContent").contains("长正文"));
        }
    }

    @Test
    void 历史阅读时追加正文及重建页面保留同一可见消息和相对位置() {
        List<ItemEnvelope> history = history(101, 280);
        try (ChatAcceptanceFixture fixture = new ChatAcceptanceFixture()) {
            fixture.show("history", history, List.of());
            fixture.await("历史 280");
            fixture.script("window.scrollTo(0,document.documentElement.scrollHeight/2)");
            FxTestSupport.await(() ->
                    fixture.script("!!document.querySelector('.new-messages')").equals("true"));
            String anchor = fixture.script(anchorId());
            double offset = Double.parseDouble(fixture.script(anchorTop()));
            AcceptanceCapture.save(
                    fixture.stage.getScene(), "chat-history-anchor", Map.of("anchor", anchor, "top", offset));
            var stream = List.of(new ChatSurface.TemporaryMessage("live", "末尾新增正文", false));
            fixture.show("history", history, stream);
            fixture.awaitCount(181);
            assertAnchor(fixture, anchor, offset);
            List<ItemEnvelope> expanded = new ArrayList<>(history(1, 100));
            expanded.addAll(history);
            fixture.show("history", expanded, stream);
            fixture.awaitCount(281);
            assertAnchor(fixture, anchor, offset);
            AcceptanceCapture.save(fixture.stage.getScene(), "chat-history-prepended", Map.of("sameAnchor", anchor));
            verifyRecovery(fixture, anchor, offset);
            fixture.script("document.querySelector('.new-messages').click()");
            fixture.await("末尾新增正文");
        }
    }

    private static List<ItemEnvelope> history(int first, int last) {
        List<ItemEnvelope> result = new ArrayList<>();
        for (int index = first; index <= last; index++) {
            result.add(ChatAcceptanceFixture.message(
                    ItemId.random(),
                    index,
                    MessageRole.ASSISTANT,
                    "历史 " + index + "\n" + "这是一段用于检验阅读位置的历史正文。".repeat(12),
                    List.of()));
        }
        return result;
    }

    private static ItemHistoryEntry history(
            ChatAcceptanceFixture fixture,
            long sequence,
            MessageRole role,
            String text,
            List<AttachmentRef> attachments) {
        ItemId id = ItemId.random();
        return new ItemHistoryEntry(
                id,
                TurnId.random(),
                sequence,
                "message",
                Optional.of(role),
                text,
                Optional.of(DocumentReference.message(fixture.workspace, id, "body")),
                false,
                Instant.EPOCH,
                attachments,
                List.of());
    }

    private static void verifyRecovery(ChatAcceptanceFixture fixture, String anchor, double offset) {
        FxTestSupport.run(() -> fixture.chat.node().retry());
        fixture.awaitCount(281);
        AcceptanceCapture.save(fixture.stage.getScene(), "chat-history-after-retry", Map.of("expectedAnchor", anchor));
        assertAnchor(fixture, anchor, offset);
        long generation = FxTestSupport.call(() -> fixture.chat.node().generation());
        fixture.script("window.JavaClawSurface.post('error','验收强制页面重建')");
        FxTestSupport.await(() -> FxTestSupport.call(() -> fixture.chat.node().generation() > generation
                && fixture.chat.node().acknowledged()));
        assertAnchor(fixture, anchor, offset);
        AcceptanceCapture.save(fixture.stage.getScene(), "chat-history-after-recovery", Map.of("sameAnchor", anchor));
    }

    private static void assertAnchor(ChatAcceptanceFixture fixture, String id, double top) {
        assertEquals(id, fixture.script(anchorId()));
        assertEquals(top, Double.parseDouble(fixture.script(anchorTop())), 2);
    }

    private static String anchorId() {
        return "[...document.querySelectorAll('article')].find(n=>n.getBoundingClientRect().bottom>0&&n.getBoundingClientRect().top<innerHeight)?.dataset.id||''";
    }

    private static String anchorTop() {
        return "String([...document.querySelectorAll('article')].find(n=>n.getBoundingClientRect().bottom>0&&n.getBoundingClientRect().top<innerHeight)?.getBoundingClientRect().top||0)";
    }
}
