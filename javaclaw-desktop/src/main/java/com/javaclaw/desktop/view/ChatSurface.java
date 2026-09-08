package com.javaclaw.desktop.view;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.Node;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.web.MarkdownLinkTarget;
import com.javaclaw.desktop.web.SafeMarkdown;
import com.javaclaw.desktop.web.WebSurfaceHost;
import com.javaclaw.protocol.CanonicalJson;

/** 聊天展示投影；后台最多保留一个待转换快照，JS仅持有有界窗口，SDK调用由页面Presenter发起。 页面临时状态通过相同消息ID归并，Markdown异常降级为原文而不丢消息。 */
public final class ChatSurface implements AutoCloseable {
    private final CanonicalJson json = new CanonicalJson();
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final AtomicReference<Snapshot> pending = new AtomicReference<>();
    private final WebSurfaceHost host;
    private final Consumer<DocumentReference> previews;
    private final Consumer<URI> external;
    private final Runnable history;
    private final Consumer<Boolean> following;
    private final Map<String, SafeMarkdown.Result> markdownCache = new LinkedHashMap<>();
    private long markdownBytes;
    private String activeId = "";
    private String activeHtml = "";
    private String activeText = "";
    private long parsedAt;
    private Map<String, DocumentReference> references = Map.of();
    private Map<String, URI> links = Map.of();
    private boolean running;
    private volatile boolean closed;
    private volatile String context = "empty";
    private volatile long requestVersion;

    /**
     * @param fallback 原生列表
     * @param previews 文档意图
     * @param external 外部浏览器意图
     * @param history 历史分页意图
     * @param following 跟随策略
     */
    public ChatSurface(
            Node fallback,
            Consumer<DocumentReference> previews,
            Consumer<URI> external,
            Runnable history,
            Consumer<Boolean> following) {
        this.previews = Objects.requireNonNull(previews, "previews");
        this.external = Objects.requireNonNull(external, "external");
        this.history = Objects.requireNonNull(history, "history");
        this.following = Objects.requireNonNull(following, "following");
        host = new WebSurfaceHost("chat", fallback, this::action);
    }

    /** @return 可挂载的稳定宿主 */
    public WebSurfaceHost node() {
        return host;
    }

    /**
     * 提交最新消息快照；同一线程中间版本可被合并，业务归并必须在调用前完成。
     *
     * @param identity 当前Thread或空态身份
     * @param workspace 当前Workspace
     * @param items 已提交Item
     * @param summaries 有来源身份的历史摘要
     * @param temporary 尚未提交的公开正文
     * @param hasEarlier 是否仍有更早历史
     */
    public synchronized void show(
            String identity,
            WorkspaceId workspace,
            List<ItemEnvelope> items,
            List<ItemHistoryEntry> summaries,
            List<TemporaryMessage> temporary,
            boolean hasEarlier) {
        if (closed) {
            return;
        }
        context = identity;
        pending.set(new Snapshot(
                identity,
                workspace,
                List.copyOf(items),
                List.copyOf(summaries),
                List.copyOf(temporary),
                hasEarlier,
                ++requestVersion));
        if (!running) {
            running = true;
            worker.submit(this::drain);
        }
    }

    private void drain() {
        Snapshot next;
        while ((next = pending.getAndSet(null)) != null && !closed) {
            try {
                Rendered rendered = render(next);
                Platform.runLater(() -> apply(rendered));
            } catch (RuntimeException failure) {
                String failedContext = next.context();
                long failedVersion = next.version();
                Platform.runLater(() -> {
                    if (!closed && context.equals(failedContext) && requestVersion == failedVersion) {
                        host.useFallback();
                    }
                });
            }
        }
        synchronized (this) {
            running = false;
            if (pending.get() != null && !closed) {
                running = true;
                worker.submit(this::drain);
            }
        }
    }

    private Rendered render(Snapshot snapshot) {
        TranscriptPresenter presenter = new TranscriptPresenter(json);
        presenter.replaceItems(snapshot.items());
        ArrayList<Object> messages = new ArrayList<>();
        Map<String, DocumentReference> targets = new LinkedHashMap<>();
        Map<String, URI> urls = new LinkedHashMap<>();
        for (ItemEnvelope item : snapshot.items()) {
            var text = presenter.present(item);
            String id = item.id().toString();
            String body = text.body();
            ArrayList<Map<String, String>> cards = new ArrayList<>();
            String html = "";
            if (CoreSchemas.MESSAGE.equals(item.schemaId())) {
                CorePayloads.Message value = json.decode(item.payload(), CorePayloads.Message.class);
                html = message(snapshot.workspace(), item, body, targets, urls, cards);
                value.attachments()
                        .forEach(attachment -> card(
                                cards,
                                targets,
                                id + ":attachment:" + attachment.digest(),
                                attachment.fileName(),
                                DocumentReference.attachment(snapshot.workspace(), item.id(), attachment)));
            }
            messages.add(Map.of(
                    "id",
                    id,
                    "version",
                    item.payload().sha256(),
                    "title",
                    text.title(),
                    "style",
                    text.styleClass(),
                    "text",
                    body.length() > 65_536 ? body.substring(0, 65_536) + "\n[完整内容见文档]" : body,
                    "html",
                    html,
                    "references",
                    cards,
                    "streaming",
                    false));
        }
        addSummaries(snapshot, messages, targets, urls);
        addTemporary(snapshot, messages);
        if (messages.size() > 500) {
            messages.subList(0, messages.size() - 500).clear();
        }
        return new Rendered(
                snapshot.context(),
                json.encode(Map.of("items", messages, "hasEarlier", snapshot.hasEarlier()))
                        .json(),
                Map.copyOf(targets),
                Map.copyOf(urls));
    }

    private void addSummaries(
            Snapshot snapshot, List<Object> messages, Map<String, DocumentReference> targets, Map<String, URI> urls) {
        for (ItemHistoryEntry entry : snapshot.summaries()) {
            String id = entry.id().toString();
            ArrayList<Map<String, String>> cards = summaryReferences(snapshot, entry, targets);
            PresentedItem presented = TranscriptPresenter.presentHistory(entry);
            String html = "";
            if (!entry.truncated() && entry.role().isPresent()) {
                SafeMarkdown.Result result = markdown("summary:" + id, entry.summary(), id);
                html = result.html();
                for (int index = 0; index < result.fences().size(); index++) {
                    card(
                            cards,
                            targets,
                            id + ":fence:" + index,
                            "查看代码 " + (index + 1),
                            DocumentReference.message(snapshot.workspace(), entry.id(), "fence:" + index));
                }
                for (int index = 0; index < result.links().size(); index++) {
                    addLink(
                            snapshot.workspace(),
                            entry.id(),
                            id + ":link:" + index,
                            index,
                            result.links().get(index),
                            targets,
                            urls);
                }
            }
            messages.add(Map.of(
                    "id",
                    id,
                    "version",
                    entry.sequence() + ":" + entry.summary().hashCode(),
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
                    false));
        }
    }

    private ArrayList<Map<String, String>> summaryReferences(
            Snapshot snapshot, ItemHistoryEntry entry, Map<String, DocumentReference> targets) {
        String id = entry.id().toString();
        ArrayList<Map<String, String>> cards = new ArrayList<>();
        entry.bodyReference().ifPresent(reference -> card(cards, targets, id + ":body", "打开文档", reference));
        for (var attachment : entry.attachments()) {
            card(
                    cards,
                    targets,
                    id + ":attachment:" + attachment.digest(),
                    attachment.fileName(),
                    DocumentReference.attachment(snapshot.workspace(), entry.id(), attachment));
        }
        for (int index = 0; index < entry.fileReferences().size(); index++) {
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
            card(cards, targets, id + ":body", "打开完整正文", DocumentReference.message(workspace, item.id(), "body"));
            return "";
        }
        SafeMarkdown.Result rendered = markdown(item, body, id);
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

    private SafeMarkdown.Result markdown(ItemEnvelope item, String body, String id) {
        return markdown(id + ':' + item.payload().sha256(), body, id);
    }

    private SafeMarkdown.Result markdown(String key, String body, String id) {
        SafeMarkdown.Result cached = markdownCache.get(key);
        if (cached != null) {
            return cached;
        }
        SafeMarkdown.Result value = new SafeMarkdown().render(body, id + ":link:", Map.of());
        long size = value.html().length() * 2L;
        while (!markdownCache.isEmpty() && (markdownCache.size() >= 500 || markdownBytes + size > 4L * 1024 * 1024)) {
            String oldest = markdownCache.keySet().iterator().next();
            markdownBytes -= markdownCache.remove(oldest).html().length() * 2L;
        }
        if (size <= 4L * 1024 * 1024) {
            markdownCache.put(key, value);
            markdownBytes += size;
        }
        return value;
    }

    private void addTemporary(Snapshot snapshot, List<Object> messages) {
        var committed = snapshot.items().stream()
                .map(item -> item.id().toString())
                .collect(java.util.stream.Collectors.toSet());
        snapshot.summaries().forEach(item -> committed.add(item.id().toString()));
        for (TemporaryMessage temporary : snapshot.temporary()) {
            if (!committed.contains(temporary.id()) && !temporary.text().isEmpty()) {
                String html = activeMarkdown(temporary);
                messages.add(Map.of(
                        "id",
                        temporary.id(),
                        "version",
                        temporary.text().hashCode() + ":" + html.hashCode() + ":" + temporary.incomplete(),
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
                        true));
            }
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

    private void apply(Rendered rendered) {
        if (!closed && context.equals(rendered.context())) {
            references = rendered.references();
            links = rendered.links();
            host.show(rendered.context(), rendered.json());
        }
    }

    private void action(String action, String value) {
        if (action.equals("history")) {
            history.run();
        } else if (action.equals("following")) {
            following.accept(Boolean.parseBoolean(value));
        } else if (action.equals("link") || action.equals("preview")) {
            if (references.containsKey(value)) {
                previews.accept(references.get(value));
            } else if (links.containsKey(value)) {
                external.accept(links.get(value));
            }
        }
    }

    /** 停止后台投影并释放页面；FX线程调用。 */
    @Override
    public synchronized void close() {
        closed = true;
        pending.set(null);
        worker.shutdownNow();
        host.close();
    }

    /**
     * @param id 与最终Item一致的身份
     * @param text 公开正文
     * @param incomplete 是否未完成
     * @param textOffsetUtf16 尾部开始的 UTF-16 位置，正值不能按全文解析
     */
    public record TemporaryMessage(String id, String text, boolean incomplete, long textOffsetUtf16) {
        /**
         * @param id 最终消息身份
         * @param text 尚未截断的公开正文
         * @param incomplete 是否未完成
         */
        public TemporaryMessage(String id, String text, boolean incomplete) {
            this(id, text, incomplete, 0);
        }
    }

    private record Snapshot(
            String context,
            WorkspaceId workspace,
            List<ItemEnvelope> items,
            List<ItemHistoryEntry> summaries,
            List<TemporaryMessage> temporary,
            boolean hasEarlier,
            long version) {}

    private record Rendered(
            String context, String json, Map<String, DocumentReference> references, Map<String, URI> links) {}
}
