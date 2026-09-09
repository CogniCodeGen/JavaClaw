package com.javaclaw.desktop.view;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.view.ChatSurface.TemporaryMessage;
import com.javaclaw.desktop.web.MarkdownLinkTarget;
import com.javaclaw.desktop.web.SafeMarkdown;
import com.javaclaw.protocol.CanonicalJson;

/** 单个后台 worker 拥有的有界聊天投影缓存；缓存来源、JSON 与引用表一起更新，禁止跨作用域复用。 */
final class ChatProjectionRenderer {
    private static final long MAX_CACHE_BYTES = 8L * 1024 * 1024;
    private final CanonicalJson json = new CanonicalJson();
    private final TranscriptPresenter presenter = new TranscriptPresenter(json);
    private final Map<String, CachedRow> cache = new LinkedHashMap<>();
    private Scope scope;
    private List<ItemEnvelope> indexedItems = List.of();
    private long itemGeneration;
    private long cachedBytes;
    private long projectedRows;
    private String activeId = "";
    private String activeHtml = "";
    private String activeText = "";
    private long parsedAt;

    Rendered render(Snapshot snapshot, BooleanSupplier current) {
        requireCurrent(current);
        prepare(snapshot);
        Window visible = visible(snapshot);
        retainVisible(visible);
        List<ProjectedRow> rows = new ArrayList<>();
        for (ItemEnvelope item : visible.items()) {
            requireCurrent(current);
            rows.add(item(snapshot.scope(), item));
        }
        for (ItemHistoryEntry entry : visible.summaries()) {
            requireCurrent(current);
            rows.add(summary(snapshot.scope(), entry));
        }
        addTemporary(visible.temporary(), rows, current);
        requireCurrent(current);
        Map<String, DocumentReference> targets = new LinkedHashMap<>();
        Map<String, URI> links = new LinkedHashMap<>();
        StringBuilder payload = new StringBuilder("{\"hasEarlier\":" + snapshot.hasEarlier() + ",\"items\":[");
        for (ProjectedRow row : rows) {
            if (payload.charAt(payload.length() - 1) != '[') {
                payload.append(',');
            }
            payload.append(row.json());
            targets.putAll(row.references());
            links.putAll(row.links());
        }
        return new Rendered(
                snapshot.scope(),
                snapshot.version(),
                payload.append("]}").toString(),
                Map.copyOf(targets),
                Map.copyOf(links));
    }

    private void retainVisible(Window visible) {
        var keys = new HashSet<String>();
        visible.items().forEach(item -> keys.add("item:" + item.id()));
        visible.summaries().forEach(item -> keys.add("summary:" + item.id()));
        var entries = cache.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (!keys.contains(entry.getKey())) {
                cachedBytes -= entry.getValue().bytes();
                entries.remove();
            }
        }
    }

    private static Window visible(Snapshot snapshot) {
        var committed = snapshot.items().stream()
                .map(item -> item.id().toString())
                .collect(java.util.stream.Collectors.toSet());
        snapshot.summaries().forEach(item -> committed.add(item.id().toString()));
        List<TemporaryMessage> temporary = snapshot.temporary().stream()
                .filter(item -> !committed.contains(item.id()) && !item.text().isEmpty())
                .toList();
        int skipped = Math.max(0, snapshot.items().size() + snapshot.summaries().size() + temporary.size() - 500);
        int items = Math.min(skipped, snapshot.items().size());
        skipped -= items;
        int summaries = Math.min(skipped, snapshot.summaries().size());
        skipped -= summaries;
        return new Window(
                snapshot.items().subList(items, snapshot.items().size()),
                snapshot.summaries().subList(summaries, snapshot.summaries().size()),
                temporary.subList(skipped, temporary.size()));
    }

    private void prepare(Snapshot snapshot) {
        if (!snapshot.scope().equals(scope)) {
            scope = snapshot.scope();
            cache.clear();
            cachedBytes = 0;
            indexedItems = List.of();
            presenter.replaceItems(indexedItems);
            activeId = "";
            activeHtml = "";
            activeText = "";
            parsedAt = 0;
        }
        if (!indexedItems.equals(snapshot.items())) {
            indexedItems = snapshot.items();
            presenter.replaceItems(indexedItems);
            itemGeneration++;
        }
    }

    private ProjectedRow item(Scope scope, ItemEnvelope item) {
        String key = "item:" + item.id();
        long generation = CoreSchemas.MESSAGE.equals(item.schemaId()) ? 0 : itemGeneration;
        CachedRow existing = cache.get(key);
        if (existing != null && existing.matches(item, generation)) {
            return existing.row();
        }
        var text = presenter.present(item);
        String id = item.id().toString();
        String body = text.body();
        Map<String, DocumentReference> targets = new LinkedHashMap<>();
        Map<String, URI> links = new LinkedHashMap<>();
        List<Map<String, String>> cards = new ArrayList<>();
        String html = "";
        if (CoreSchemas.MESSAGE.equals(item.schemaId())) {
            CorePayloads.Message value = json.decode(item.payload(), CorePayloads.Message.class);
            html = message(scope.workspace(), item, body, targets, links, cards);
            value.attachments()
                    .forEach(attachment -> card(
                            cards,
                            targets,
                            id + ":attachment:" + attachment.digest(),
                            attachment.fileName(),
                            DocumentReference.attachment(scope.workspace(), item.id(), attachment)));
        }
        ProjectedRow rendered = row(
                Map.of(
                        "id",
                        id,
                        "title",
                        text.title(),
                        "style",
                        text.styleClass(),
                        "text",
                        body.length() > 65_536 ? body.substring(0, 65_536) + "\n[消息较长，可查看完整消息]" : body,
                        "html",
                        html,
                        "references",
                        cards,
                        "streaming",
                        false),
                targets,
                links);
        return remember(key, item, generation, rendered, item.payload().json().length() * 2L);
    }

    private ProjectedRow summary(Scope scope, ItemHistoryEntry entry) {
        String key = "summary:" + entry.id();
        CachedRow existing = cache.get(key);
        if (existing != null && existing.matches(entry, 0)) {
            return existing.row();
        }
        String id = entry.id().toString();
        Map<String, DocumentReference> targets = new LinkedHashMap<>();
        Map<String, URI> links = new LinkedHashMap<>();
        List<Map<String, String>> cards = summaryReferences(scope, entry, targets);
        PresentedItem presented = TranscriptPresenter.presentHistory(entry);
        String html = "";
        if (!entry.truncated() && entry.role().isPresent()) {
            SafeMarkdown.Result result = new SafeMarkdown().render(entry.summary(), id + ":link:", Map.of());
            html = result.html();
            for (int index = 0; index < result.fences().size(); index++) {
                card(
                        cards,
                        targets,
                        id + ":fence:" + index,
                        "查看代码 " + (index + 1),
                        DocumentReference.message(scope.workspace(), entry.id(), "fence:" + index));
            }
            for (int index = 0; index < result.links().size(); index++) {
                addLink(
                        scope.workspace(),
                        entry.id(),
                        id + ":link:" + index,
                        index,
                        result.links().get(index),
                        targets,
                        links);
            }
        }
        ProjectedRow rendered = row(
                Map.of(
                        "id",
                        id,
                        "title",
                        presented.title(),
                        "style",
                        presented.styleClass(),
                        "text",
                        presented.body(),
                        "html",
                        html,
                        "references",
                        cards,
                        "streaming",
                        false),
                targets,
                links);
        return remember(key, entry, 0, rendered, entry.summary().length() * 2L);
    }

    private ProjectedRow row(
            Map<String, Object> values, Map<String, DocumentReference> references, Map<String, URI> links) {
        var encoded = json.encode(values);
        // 引用变化也必须推进宿主版本，不能让旧 DOM 句柄在 50ms 提交间隙绑定到另一份文档。
        String version = encoded.sha256() + ":"
                + json.encode(Map.of("documents", references, "links", links)).sha256();
        String serialized = "{\"version\":\"" + version + "\"," + encoded.json().substring(1);
        return new ProjectedRow(serialized, Map.copyOf(references), Map.copyOf(links));
    }

    private ProjectedRow remember(String key, Object source, long generation, ProjectedRow row, long sourceBytes) {
        projectedRows++;
        CachedRow previous = cache.remove(key);
        if (previous != null) {
            cachedBytes -= previous.bytes();
        }
        long bytes = sourceBytes
                + row.json().length() * 2L
                + row.references().size() * 512L
                + row.links().values().stream()
                        .mapToLong(uri -> uri.toString().length() * 2L)
                        .sum();
        // 保留当前窗口已经缓存的稳定子集；逐行驱逐会使超出字节预算的同一窗口在每帧全部失配。
        if (cache.size() >= 500 || cachedBytes + bytes > MAX_CACHE_BYTES) {
            return row;
        }
        cache.put(key, new CachedRow(source, generation, row, bytes));
        cachedBytes += bytes;
        return row;
    }

    Statistics statistics() {
        return new Statistics(cache.size(), cachedBytes, projectedRows);
    }

    private static void requireCurrent(BooleanSupplier current) {
        if (!current.getAsBoolean()) {
            throw new CancellationException("聊天快照已经被更新替代");
        }
    }

    private ArrayList<Map<String, String>> summaryReferences(
            Scope scope, ItemHistoryEntry entry, Map<String, DocumentReference> targets) {
        String id = entry.id().toString();
        ArrayList<Map<String, String>> cards = new ArrayList<>();
        // 正文引用用于按需读取消息全文，不代表模型生成了文档。
        if (entry.truncated()) {
            entry.bodyReference()
                    .filter(reference -> reference.workspaceId().equals(scope.workspace()))
                    .ifPresent(reference -> card(cards, targets, id + ":body", "查看完整消息", reference));
        }
        for (var attachment : entry.attachments()) {
            card(
                    cards,
                    targets,
                    id + ":attachment:" + attachment.digest(),
                    attachment.fileName(),
                    DocumentReference.attachment(scope.workspace(), entry.id(), attachment));
        }
        for (int index = 0; index < entry.fileReferences().size(); index++) {
            if (!entry.fileReferences().get(index).workspaceId().equals(scope.workspace())) {
                continue;
            }
            card(
                    cards,
                    targets,
                    id + ":file:" + index,
                    "查看引用文件 " + (index + 1),
                    entry.fileReferences().get(index));
        }
        return cards;
    }

    private void addLink(
            WorkspaceId workspace,
            com.javaclaw.api.ItemId item,
            String key,
            int index,
            String href,
            Map<String, DocumentReference> targets,
            Map<String, URI> urls) {
        try {
            URI uri = MarkdownLinkTarget.parse(href);
            if ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) {
                urls.put(key, uri);
            } else if (MarkdownLinkTarget.relative(href)) {
                targets.put(key, DocumentReference.file(workspace, item, "link:" + index));
            }
        } catch (IllegalArgumentException ignored) {
            // 无效 URI 保留文本，不推导宿主文件访问。
        }
    }

    private String message(
            WorkspaceId workspace,
            ItemEnvelope item,
            String body,
            Map<String, DocumentReference> targets,
            Map<String, URI> urls,
            List<Map<String, String>> cards) {
        String id = item.id().toString();
        if (body.length() > 1_048_576) {
            card(cards, targets, id + ":body", "查看完整消息", DocumentReference.message(workspace, item.id(), "body"));
            return "";
        }
        SafeMarkdown.Result rendered = new SafeMarkdown().render(body, id + ":link:", Map.of());
        for (int index = 0; index < rendered.links().size(); index++) {
            String key = id + ":link:" + index;
            String href = rendered.links().get(index);
            addLink(workspace, item.id(), key, index, href, targets, urls);
        }
        for (int index = 0; index < rendered.fences().size(); index++) {
            card(
                    cards,
                    targets,
                    id + ":fence:" + index,
                    "查看代码 " + (index + 1),
                    DocumentReference.message(workspace, item.id(), "fence:" + index));
        }
        return rendered.html();
    }

    private void addTemporary(
            List<TemporaryMessage> temporaryMessages, List<ProjectedRow> messages, BooleanSupplier current) {
        for (TemporaryMessage temporary : temporaryMessages) {
            requireCurrent(current);
            String html = activeMarkdown(temporary);
            messages.add(row(
                    Map.of(
                            "id",
                            temporary.id(),
                            "title",
                            temporary.incomplete() ? "ASSISTANT · 未完成" : "ASSISTANT",
                            "style",
                            "message-assistant",
                            "text",
                            temporary.textOffsetUtf16() > 0 && temporary.text().length() <= 32_768
                                    ? "[正文较长；显示末尾，完成后可打开全文]\n" + temporary.text()
                                    : tail(temporary.text()),
                            "html",
                            html,
                            "references",
                            List.of(),
                            "streaming",
                            true),
                    Map.of(),
                    Map.of()));
        }
    }

    private String activeMarkdown(TemporaryMessage message) {
        if (message.incomplete()
                || message.textOffsetUtf16() > 0
                || message.text().length() > 32_768) {
            return "";
        }
        long now = System.nanoTime();
        if (!activeId.equals(message.id()) || now - parsedAt >= 200_000_000L) {
            activeId = message.id();
            activeHtml = new SafeMarkdown()
                    .render(message.text(), "active:", Map.of())
                    .html();
            activeText = message.text();
            parsedAt = now;
        }
        if (!message.text().startsWith(activeText)) {
            return "";
        }
        // Markdown 至多每 200ms 解析一次；期间的新字仍以转义原文立即可见，不能显示旧正文等待下一块。
        String suffix = message.text().substring(activeText.length());
        return suffix.isEmpty()
                ? activeHtml
                : activeHtml + "<span class=\"plain\">"
                        + suffix.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</span>";
    }

    private static String tail(String text) {
        if (text.length() <= 32_768) {
            return text;
        }
        int start = text.length() - 32_768;
        if (Character.isLowSurrogate(text.charAt(start))) {
            start++;
        }
        return "[正在输出；显示正文末尾]\n" + text.substring(start);
    }

    private static void card(
            List<Map<String, String>> cards,
            Map<String, DocumentReference> targets,
            String id,
            String label,
            DocumentReference reference) {
        cards.add(Map.of("id", id, "label", label));
        targets.put(id, reference);
    }

    /**
     * 上下文与工作区共同决定显示作用域，空对话也不能复用另一个工作区的引用。
     *
     * @param context 非空的会话或空态标识
     * @param workspace 非空的工作区身份
     */
    record Scope(String context, WorkspaceId workspace) {
        Scope {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(workspace, "workspace");
        }

        String identity() {
            return workspace + ":" + context;
        }
    }

    /**
     * 从页面提交的不可变输入；集合由提交边界复制，不允许后台转换期间修改。
     *
     * @param scope 非空的显示作用域
     * @param items 非空的持久消息列表
     * @param summaries 非空的历史摘要列表
     * @param temporary 非空的暂态正文列表
     * @param hasEarlier 是否还有更早历史
     * @param version 在页面实例内递增的请求版本，非时间戳
     */
    record Snapshot(
            Scope scope,
            List<ItemEnvelope> items,
            List<ItemHistoryEntry> summaries,
            List<TemporaryMessage> temporary,
            boolean hasEarlier,
            long version) {}

    /**
     * 一次完整投影；JSON 与引用映射必须同时提交，所有对象组件均非空。
     *
     * @param scope 输入的显示作用域
     * @param version 输入请求版本
     * @param json 最终窗口的合法 JSON 对象
     * @param references 窗口内文档动作及其精确目标
     * @param links 窗口内可打开的外部链接
     */
    record Rendered(
            Scope scope,
            long version,
            String json,
            Map<String, DocumentReference> references,
            Map<String, URI> links) {}

    /**
     * 缓存诊断计数。
     *
     * @param rows 当前缓存行数
     * @param bytes 当前缓存的估算字节数，不代表 JVM 实际堆占用
     * @param projections 持久消息与历史行的累计实际转换次数，不含暂态正文
     */
    record Statistics(int rows, long bytes, long projections) {}

    /** items、summaries、temporary 均为非空列表，依次拼接后构成最多 500 条的最终窗口。 */
    private record Window(
            List<ItemEnvelope> items, List<ItemHistoryEntry> summaries, List<TemporaryMessage> temporary) {}

    /** json 是单行消息的合法 JSON；references 与 links 是其非空、不可变的精确动作映射。 */
    private record ProjectedRow(String json, Map<String, DocumentReference> references, Map<String, URI> links) {}

    /** source 为非空原始事实，generation 为工具关联代次，row 为非空投影，bytes 为估算缓存字节数。 */
    private record CachedRow(Object source, long generation, ProjectedRow row, long bytes) {
        private boolean matches(Object expected, long expectedGeneration) {
            return source.equals(expected) && generation == expectedGeneration;
        }
    }
}
