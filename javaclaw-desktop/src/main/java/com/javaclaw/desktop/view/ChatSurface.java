package com.javaclaw.desktop.view;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.Node;

import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.view.ChatProjectionRenderer.Rendered;
import com.javaclaw.desktop.view.ChatProjectionRenderer.Scope;
import com.javaclaw.desktop.view.ChatProjectionRenderer.Snapshot;
import com.javaclaw.desktop.web.WebSurfaceHost;

/**
 * 聊天展示投影的页面 owner；后台快照与 FX 提交分别合并为单槽，引用表仅随当前完整投影更新。
 *
 * <p>临时正文保持原身份归并。切换工作区或对话后立即拒绝旧页面的引用动作，页面呈现仍使用宿主既有的节流和确认。
 */
public final class ChatSurface implements AutoCloseable {
    private final WebSurfaceHost host;
    private final ChatProjectionWork work;
    private final Consumer<DocumentReference> previews;
    private final Consumer<URI> external;
    private final Runnable history;
    private final Consumer<Boolean> following;
    private Map<String, DocumentReference> references = Map.of();
    private Map<String, URI> links = Map.of();
    private Scope appliedScope;
    private boolean closed;
    private long requestVersion;

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
        ChatProjectionRenderer renderer = new ChatProjectionRenderer();
        work = new ChatProjectionWork(
                Executors.newSingleThreadExecutor(Thread.ofVirtual().factory()),
                Platform::runLater,
                renderer::render,
                this::apply,
                this::failed);
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
        work.submit(new Snapshot(
                new Scope(identity, workspace),
                List.copyOf(items),
                List.copyOf(summaries),
                List.copyOf(temporary),
                hasEarlier,
                ++requestVersion));
    }

    private void apply(Rendered rendered) {
        appliedScope = rendered.scope();
        references = rendered.references();
        links = rendered.links();
        host.show(rendered.scope().identity(), rendered.json());
    }

    private void failed() {
        references = Map.of();
        links = Map.of();
        host.useFallback();
    }

    private void action(String action, String value) {
        if (!work.matchesScope(appliedScope)) {
            return;
        }
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

    /** 清空当前聊天并撤销在途投影与引用；FX 线程调用，保留实例供后续工作区继续使用。 */
    public synchronized void clear() {
        if (closed) {
            return;
        }
        work.clear();
        appliedScope = null;
        references = Map.of();
        links = Map.of();
        host.show("empty", "{}");
    }

    /** 停止后台投影并释放页面；FX线程调用。 */
    @Override
    public synchronized void close() {
        closed = true;
        references = Map.of();
        links = Map.of();
        work.close();
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
}
