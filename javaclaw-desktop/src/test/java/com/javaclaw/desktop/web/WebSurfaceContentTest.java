package com.javaclaw.desktop.web;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSurfaceContentTest {
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void 五百行中只传变化行且基线使用实际提交版本() {
        List<WebSurfaceContent.Row> history = IntStream.range(0, 499)
                .mapToObj(index -> row("history:" + index, "1", "稳定正文".repeat(100)))
                .toList();
        var before = content(history, row("live", "2", "已显示"));
        var latest = content(history, row("live", "9", "合并七次更新后的正文"));

        Map<?, ?> patch = decode(latest.payload(before, 41));

        assertEquals("patch", patch.get("mode"));
        assertEquals(41, patch.get("baseRevision"));
        assertEquals(1, ((List<?>) patch.get("upserts")).size());
        assertEquals(List.of(), patch.get("removedIds"));
        assertFalse(patch.containsKey("order"));
        assertFalse(patch.containsKey("hasEarlier"));
        assertTrue(latest.payload(before, 41).length() < 300);
        assertTrue(latest.fullPayload().length() > 100_000);
    }

    @Test
    void 删除重排和含特殊字符的身份使用完整合法顺序() {
        var first = WebSurfaceContent.chat(List.of(row("a", "1", "甲"), row("b", "1", "乙")), false);
        var next = WebSurfaceContent.chat(List.of(row("c\"\n", "1", "丙"), row("b", "1", "乙")), true);

        Map<?, ?> patch = decode(next.payload(first, 5));

        assertEquals(List.of("a"), patch.get("removedIds"));
        assertEquals(List.of("c\"\n", "b"), patch.get("order"));
        assertEquals(true, patch.get("hasEarlier"));
        assertEquals(1, ((List<?>) patch.get("upserts")).size());
    }

    @Test
    void 恢复总是使用完整行且文档图谱保留原载荷() {
        var chat = WebSurfaceContent.chat(List.of(row("a", "1", "甲")), false);
        var document = WebSurfaceContent.serialized("{\"pages\":[]}");

        assertEquals("replace", decode(chat.payload(null, -1)).get("mode"));
        assertEquals(chat.fullPayload(), chat.payload(document, 4));
        assertEquals("{\"pages\":[]}", document.payload(chat, 4));
        assertTrue(document.sameAs(WebSurfaceContent.serialized("{\"pages\":[]}")));
        assertFalse(document.sameAs(chat));
        assertFalse(chat.sameAs(document));
    }

    @Test
    void 引用版本变化即使正文相同也必须提交并拒绝重复或越界窗口() {
        var first = WebSurfaceContent.chat(List.of(row("a", "text:target1", "正文")), false);
        var changed = WebSurfaceContent.chat(List.of(row("a", "text:target2", "正文")), false);

        assertTrue(first.sameAs(WebSurfaceContent.chat(List.of(row("a", "text:target1", "正文")), false)));
        assertFalse(first.sameAs(changed));
        assertEquals(1, ((List<?>) decode(changed.payload(first, 8)).get("upserts")).size());
        assertThrows(
                IllegalArgumentException.class,
                () -> WebSurfaceContent.chat(List.of(row("a", "1", ""), row("a", "2", "")), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> WebSurfaceContent.chat(
                        IntStream.range(0, 501)
                                .mapToObj(index -> row("r" + index, "1", ""))
                                .toList(),
                        false));
    }

    private WebSurfaceContent content(List<WebSurfaceContent.Row> history, WebSurfaceContent.Row tail) {
        return WebSurfaceContent.chat(
                java.util.stream.Stream.concat(history.stream(), java.util.stream.Stream.of(tail))
                        .toList(),
                true);
    }

    private WebSurfaceContent.Row row(String id, String version, String text) {
        return new WebSurfaceContent.Row(
                id,
                version,
                json.encode(Map.of("id", id, "version", version, "text", text)).json());
    }

    private Map<?, ?> decode(String value) {
        return json.decode(new CanonicalPayload(value), Map.class);
    }
}
