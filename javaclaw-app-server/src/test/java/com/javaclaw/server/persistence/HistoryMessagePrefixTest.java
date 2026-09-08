package com.javaclaw.server.persistence;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.MessageRole;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 对数据库 CLOB 前缀截断、Unicode 和附件元数据边界执行真实 JSON 回归。 */
class HistoryMessagePrefixTest {
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void 规范消息摘要还原所有控制转义且保留角色与补充平面字符() {
        String original = "引号\"反斜杠\\斜杠/换行\n回车\r制表\t退格\b换页\f😀";
        String payload = message(original, List.of());
        var text = HistoryMessagePrefix.text(payload, "text", 4096);

        assertEquals(original, text.value());
        assertFalse(text.truncated());
        assertEquals(Optional.of(MessageRole.ASSISTANT), HistoryMessagePrefix.role(payload));
        var unicode = HistoryMessagePrefix.text("{\"text\":\"\\u4E2D\\uD83D\\uDE00\"}", "text", 10);
        assertEquals("中😀", unicode.value());
        assertFalse(unicode.truncated());
    }

    @Test
    void 字符限额不会切出孤立代理项并区分刚好完整与仍有后续文本() {
        String payload = message("😀后续", List.of());
        var cutInsideEmoji = HistoryMessagePrefix.text(payload, "text", 1);
        assertEquals("", cutInsideEmoji.value());
        assertTrue(cutInsideEmoji.truncated());
        var cutAfterEmoji = HistoryMessagePrefix.text(payload, "text", 2);
        assertEquals("😀", cutAfterEmoji.value());
        assertTrue(cutAfterEmoji.truncated());
        var exact = HistoryMessagePrefix.text(message("😀", List.of()), "text", 2);
        assertEquals("😀", exact.value());
        assertFalse(exact.truncated());
    }

    @Test
    void CLOB前缀在转义中途结束时只交付已完整解码的文本() {
        for (String ending : List.of("\\", "\\u", "\\u12", "\\uD83D")) {
            var text = HistoryMessagePrefix.text("{\"text\":\"完成" + ending, "text", 100);
            assertEquals("完成", text.value());
            assertTrue(text.truncated());
        }
        var incomplete = HistoryMessagePrefix.text("{\"text\":\"尚未结束", "text", 100);
        assertEquals("尚未结束", incomplete.value());
        assertTrue(incomplete.truncated());
    }

    @Test
    void 缺失未开始或非字符串字段不能冒充完整正文或有效角色() {
        for (String payload : List.of("{}", "{\"text\":", "{\"text\":null}", "{\"text\":17}")) {
            var text = HistoryMessagePrefix.text(payload, "text", 100);
            assertEquals("", text.value());
            assertTrue(text.truncated());
        }
        assertEquals(
                "正常",
                HistoryMessagePrefix.text("{\"text\": \t\"正常\"}", "text", 100).value());
        assertTrue(HistoryMessagePrefix.role("{\"role\":\"ASSIS").isEmpty());
        assertTrue(HistoryMessagePrefix.role("{}").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> HistoryMessagePrefix.role("{\"role\":\"UNKNOWN\"}"));
        assertThrows(
                NumberFormatException.class, () -> HistoryMessagePrefix.text("{\"text\":\"\\uZZZZ\"}", "text", 100));
    }

    @Test
    void 附件名称中的方括号引号和转义不能提前结束附件数组() {
        var attachment = new AttachmentRef("a".repeat(64), "text/plain", "目录\\[附件]\"😀.txt", 42);
        String payload = message("正文", List.of(attachment));

        assertEquals(List.of(attachment), HistoryMessagePrefix.attachments(payload, json));
        int boundary = payload.indexOf("]\\\"");
        assertTrue(boundary > 0);
        assertTrue(HistoryMessagePrefix.attachments(payload.substring(0, boundary + 3), json)
                .isEmpty());
        assertTrue(HistoryMessagePrefix.attachments("{\"text\":\"没有附件\"}", json).isEmpty());
        assertTrue(
                HistoryMessagePrefix.attachments(message("正文", List.of()), json).isEmpty());
    }

    @Test
    void 历史附件投影固定前三十二个且损坏元数据必须明确拒绝() {
        List<AttachmentRef> attachments = IntStream.range(0, 35)
                .mapToObj(index -> new AttachmentRef(String.format("%064x", index), "text/plain", "附件" + index, index))
                .toList();
        assertEquals(attachments.subList(0, 32), HistoryMessagePrefix.attachments(message("正文", attachments), json));
        var failure = assertThrows(
                PersistenceException.class,
                () -> HistoryMessagePrefix.attachments("{\"attachments\":[{\"digest\":\"bad\"}]}", json));
        assertEquals("持久消息附件数据损坏", failure.getMessage());
    }

    private String message(String text, List<AttachmentRef> attachments) {
        return json.encode(new CorePayloads.Message(MessageRole.ASSISTANT, text, attachments, Optional.empty()))
                .json();
    }
}
