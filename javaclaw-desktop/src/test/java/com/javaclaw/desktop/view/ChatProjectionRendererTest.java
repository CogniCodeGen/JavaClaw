package com.javaclaw.desktop.view;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CanonicalPayload;
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
import com.javaclaw.desktop.view.ChatProjectionRenderer.Rendered;
import com.javaclaw.desktop.view.ChatProjectionRenderer.Scope;
import com.javaclaw.desktop.view.ChatProjectionRenderer.Snapshot;
import com.javaclaw.desktop.view.ChatSurface.TemporaryMessage;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatProjectionRendererTest {
    @Test
    void 五百条历史加暂态连续二十四次更新只投影可见历史一次() {
        ChatProjectionRenderer renderer = new ChatProjectionRenderer();
        Scope scope = new Scope("thread", WorkspaceId.random());
        List<ItemHistoryEntry> history = IntStream.range(0, 500)
                .mapToObj(index -> history(scope, ItemId.random(), "历史 " + index, List.of(), List.of()))
                .toList();
        Rendered latest = null;
        for (int version = 1; version <= 24; version++) {
            latest = renderer.render(
                    new Snapshot(
                            scope,
                            List.of(),
                            history,
                            List.of(new TemporaryMessage("live", "新增正文 " + version, false)),
                            true,
                            version),
                    () -> true);
        }
        assertEquals(499, renderer.statistics().projections());
        assertEquals(499, renderer.statistics().rows());
        assertEquals(500, frame(latest).items().size());
        assertEquals(
                history.get(1).id().toString(), frame(latest).items().getFirst().get("id"));
        assertEquals("新增正文 24", frame(latest).items().getLast().get("text"));
    }

    @Test
    void 持久消息重复快照复用相同行而删除引用会同时改变版本和映射() {
        ChatProjectionRenderer renderer = new ChatProjectionRenderer();
        Scope scope = new Scope("thread", WorkspaceId.random());
        ItemId id = ItemId.random();
        ItemEnvelope item = message(id, "[官网](https://example.test/a)\n```java\nint a=1;\n```", List.of(attachment()));
        Snapshot first = new Snapshot(scope, List.of(item), List.of(), List.of(), false, 1);
        Rendered original = renderer.render(first, () -> true);
        assertEquals(original.json(), renderer.render(first, () -> true).json());
        assertEquals(1, renderer.statistics().projections());
        ItemEnvelope replacement = message(id, "不再引用文件", List.of());
        Rendered updated =
                renderer.render(new Snapshot(scope, List.of(replacement), List.of(), List.of(), false, 2), () -> true);
        assertTrue(updated.references().isEmpty());
        assertTrue(updated.links().isEmpty());
        assertEquals(URI.create("https://example.test/a"), original.links().get(id + ":link:0"));
        assertNotEquals(
                frame(original).items().getFirst().get("version"),
                frame(updated).items().getFirst().get("version"));
    }

    @Test
    void 相同历史身份更新内容和引用时不会命中旧Markdown或继续使用旧句柄() {
        ChatProjectionRenderer renderer = new ChatProjectionRenderer();
        Scope scope = new Scope("thread", WorkspaceId.random());
        ItemId id = ItemId.random();
        ItemHistoryEntry first =
                history(scope, id, "[旧站点](https://old.example.test)", List.of(attachment()), List.of());
        renderer.render(snapshot(scope, List.of(first), 1), () -> true);
        ItemHistoryEntry second = history(scope, id, "[新站点](https://new.example.test)", List.of(), List.of());
        Rendered updated = renderer.render(snapshot(scope, List.of(second), 2), () -> true);
        assertTrue(updated.references().isEmpty());
        assertEquals(Map.of(id + ":link:0", URI.create("https://new.example.test")), updated.links());
        assertTrue(frame(updated).items().getFirst().get("html").toString().contains("新站点"));
        assertFalse(updated.json().contains("旧站点"));
        assertEquals(2, renderer.statistics().projections());
    }

    @Test
    void 文本和链接标签相同但文件目标变化也推进版本避免旧页面绑定新引用() {
        ChatProjectionRenderer renderer = new ChatProjectionRenderer();
        Scope scope = new Scope("thread", WorkspaceId.random());
        ItemId id = ItemId.random();
        DocumentReference first = DocumentReference.file(scope.workspace(), id, "file:0");
        DocumentReference second = DocumentReference.file(scope.workspace(), id, "file:1");
        Rendered original = renderer.render(
                snapshot(scope, List.of(history(scope, id, "已生成文件", List.of(), List.of(first))), 1), () -> true);
        Rendered updated = renderer.render(
                snapshot(scope, List.of(history(scope, id, "已生成文件", List.of(), List.of(second))), 2), () -> true);
        assertNotEquals(original.json(), updated.json());
        assertEquals(first, original.references().get(id + ":file:0"));
        assertEquals(second, updated.references().get(id + ":file:0"));
    }

    @Test
    void 工作区切换清空缓存且显式外区文件引用不进入当前页面() {
        ChatProjectionRenderer renderer = new ChatProjectionRenderer();
        Scope first = new Scope("empty", WorkspaceId.random());
        Scope second = new Scope("empty", WorkspaceId.random());
        ItemId id = ItemId.random();
        ItemEnvelope message = message(id, "[说明](docs/readme.md)", List.of(attachment()));
        Rendered original =
                renderer.render(new Snapshot(first, List.of(message), List.of(), List.of(), false, 1), () -> true);
        ItemHistoryEntry foreign = history(
                first,
                ItemId.random(),
                "外区文件",
                List.of(),
                List.of(DocumentReference.file(first.workspace(), id, "file:0")));
        Rendered updated = renderer.render(
                new Snapshot(second, List.of(message), List.of(foreign), List.of(), false, 2), () -> true);
        assertNotEquals(first.identity(), second.identity());
        assertTrue(original.references().values().stream()
                .allMatch(reference -> reference.workspaceId().equals(first.workspace())));
        assertTrue(updated.references().values().stream()
                .allMatch(reference -> reference.workspaceId().equals(second.workspace())));
        assertFalse(updated.references().containsKey(foreign.id() + ":file:0"));
        assertEquals(3, renderer.statistics().projections());
    }

    @Test
    void 可见窗口保留排序去重和五百上限且字节缓存有界() {
        ChatProjectionRenderer renderer = new ChatProjectionRenderer();
        Scope scope = new Scope("thread", WorkspaceId.random());
        List<ItemHistoryEntry> history = new ArrayList<>();
        for (int index = 0; index < 502; index++) {
            history.add(history(scope, ItemId.random(), "&".repeat(4096), List.of(attachment()), List.of()));
        }
        List<TemporaryMessage> temporary = List.of(
                new TemporaryMessage(history.getFirst().id().toString(), "重复正文", false),
                new TemporaryMessage("empty", "", false),
                new TemporaryMessage("live", "仍在输出", false));
        Rendered result = renderer.render(new Snapshot(scope, List.of(), history, temporary, true, 1), () -> true);
        Frame frame = frame(result);
        assertEquals(500, frame.items().size());
        assertEquals(history.get(3).id().toString(), frame.items().getFirst().get("id"));
        assertEquals("live", frame.items().getLast().get("id"));
        assertEquals(499, result.references().size());
        assertFalse(result.references()
                .containsKey(
                        history.getFirst().id() + ":attachment:" + attachment().digest()));
        assertTrue(renderer.statistics().rows() <= 500);
        assertTrue(renderer.statistics().bytes() <= 8L * 1024 * 1024);
        long projected = renderer.statistics().projections();
        int cached = renderer.statistics().rows();
        assertTrue(cached > 0 && cached < 499, "此场景必须实际超过缓存字节预算");
        renderer.render(new Snapshot(scope, List.of(), history, temporary, true, 2), () -> true);
        assertEquals(499 - cached, renderer.statistics().projections() - projected);
        assertEquals(cached, renderer.statistics().rows());
    }

    @Test
    void 过期快照在逐行转换边界停止而不继续处理其余历史() {
        ChatProjectionRenderer renderer = new ChatProjectionRenderer();
        Scope scope = new Scope("thread", WorkspaceId.random());
        List<ItemHistoryEntry> history = IntStream.range(0, 20)
                .mapToObj(index -> history(scope, ItemId.random(), "历史 " + index, List.of(), List.of()))
                .toList();
        AtomicInteger checks = new AtomicInteger();
        assertThrows(
                CancellationException.class,
                () -> renderer.render(snapshot(scope, history, 1), () -> checks.incrementAndGet() < 4));
        assertEquals(2, renderer.statistics().projections());
    }

    private static Snapshot snapshot(Scope scope, List<ItemHistoryEntry> history, long version) {
        return new Snapshot(scope, List.of(), history, List.of(), false, version);
    }

    private static ItemHistoryEntry history(
            Scope scope, ItemId id, String text, List<AttachmentRef> attachments, List<DocumentReference> files) {
        return new ItemHistoryEntry(
                id,
                TurnId.random(),
                1,
                "message",
                Optional.of(MessageRole.ASSISTANT),
                text,
                Optional.of(DocumentReference.message(scope.workspace(), id, "body")),
                false,
                Instant.EPOCH,
                attachments,
                files);
    }

    private static AttachmentRef attachment() {
        return new AttachmentRef("a".repeat(64), "text/markdown", "说明.md", 20);
    }

    private static ItemEnvelope message(ItemId id, String text, List<AttachmentRef> attachments) {
        return new ItemEnvelope(
                id,
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

    private static Frame frame(Rendered value) {
        return new CanonicalJson().decode(new CanonicalPayload(value.json()), Frame.class);
    }

    private record Frame(List<Map<String, Object>> items, boolean hasEarlier) {}
}
