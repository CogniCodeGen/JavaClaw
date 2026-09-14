package com.javaclaw.desktop.view;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.Node;

import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.state.OutgoingMessage;
import com.javaclaw.desktop.view.ChatProjectionRenderer.Rendered;
import com.javaclaw.desktop.view.ChatProjectionRenderer.Scope;
import com.javaclaw.desktop.view.ChatProjectionRenderer.Snapshot;
import com.javaclaw.desktop.web.WebSurfaceContent;
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
    private BiConsumer<String, String> outgoingActions = (action, id) -> {};
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
        host = new WebSurfaceHost("chat", fallback, (action, value) -> {});
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
     * 设置当前会话未确认消息的操作回调；页面仅提交展示身份，正文及幂等键由宿主快照解析。
     *
     * @param actions 接收 retrySend、restoreSend、copySend 及原发送身份的非空回调
     */
    public void onOutgoingAction(BiConsumer<String, String> actions) {
        outgoingActions = Objects.requireNonNull(actions, "actions");
    }

    /**
     * 更新发送恢复按钮，不重建聊天正文；宿主业务层仍独立验证实际动作条件。
     *
     * @param ready 当前是否允许重试发送
     * @param restoreAllowed 当前草稿是否为空并允许恢复
     */
    public void setOutgoingAvailability(boolean ready, boolean restoreAllowed) {
        host.setOutgoingAvailability(ready, restoreAllowed);
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
        show(identity, workspace, items, summaries, temporary, hasEarlier, Optional.empty());
    }

    /**
     * 提交包含本地发送回显的展示快照；本地消息不生成持久 Item 或文件引用。
     *
     * @param identity 当前 Thread 或空态身份
     * @param workspace 当前 Workspace
     * @param items 已提交 Item
     * @param summaries 有来源身份的历史摘要
     * @param temporary 助手尚未提交的公开正文
     * @param hasEarlier 是否仍有更早历史
     * @param outgoing 本地提交及确认状态，可缺省；由状态层与权威消息去重
     */
    public synchronized void show(
            String identity,
            WorkspaceId workspace,
            List<ItemEnvelope> items,
            List<ItemHistoryEntry> summaries,
            List<TemporaryMessage> temporary,
            boolean hasEarlier,
            Optional<OutgoingMessage> outgoing) {
        show(
                identity,
                workspace,
                items,
                summaries,
                temporary,
                hasEarlier,
                outgoing.stream().toList());
    }

    /**
     * 提交有序本地发送记录，避免后续失败覆盖先前未确认消息；所有列表在 FX 提交边界复制。
     *
     * @param identity 当前 Thread 或空态身份，不可空
     * @param workspace 当前 Workspace，不可空
     * @param items 已提交 Item，不可空
     * @param summaries 带来源身份的历史摘要，不可空
     * @param temporary 助手公开正文，不可空
     * @param hasEarlier 是否仍有更早历史
     * @param outgoings 未被权威历史替换的有序发送记录，不可空
     */
    public synchronized void show(
            String identity,
            WorkspaceId workspace,
            List<ItemEnvelope> items,
            List<ItemHistoryEntry> summaries,
            List<TemporaryMessage> temporary,
            boolean hasEarlier,
            List<OutgoingMessage> outgoings) {
        if (closed) {
            return;
        }
        work.submit(new Snapshot(
                new Scope(identity, workspace),
                List.copyOf(items),
                List.copyOf(summaries),
                List.copyOf(temporary),
                hasEarlier,
                ++requestVersion,
                List.copyOf(outgoings)));
    }

    private void apply(Rendered rendered) {
        host.show(rendered.scope().identity(), rendered.content(), (action, value) -> action(rendered, action, value));
    }

    private void failed() {
        host.useFallback();
    }

    private void action(Rendered rendered, String action, String value) {
        if (!work.matchesScope(rendered.scope())) {
            return;
        }
        if (action.equals("history")) {
            history.run();
        } else if (action.equals("following")) {
            following.accept(Boolean.parseBoolean(value));
        } else if (List.of("retrySend", "restoreSend", "copySend").contains(action)) {
            String id = rendered.outgoingActions().get(value);
            if (id != null) {
                outgoingActions.accept(action, id);
            }
        } else if (action.equals("link") || action.equals("preview")) {
            if (rendered.references().containsKey(value)) {
                previews.accept(rendered.references().get(value));
            } else if (rendered.links().containsKey(value)) {
                external.accept(rendered.links().get(value));
            }
        }
    }

    /** 清空当前聊天并撤销在途投影与引用；FX 线程调用，保留实例供后续工作区继续使用。 */
    public synchronized void clear() {
        if (closed) {
            return;
        }
        work.clear();
        host.show("empty", WebSurfaceContent.chat(List.of(), false), (action, value) -> {});
    }

    /** 停止后台投影并释放页面；FX线程调用。 */
    @Override
    public synchronized void close() {
        closed = true;
        work.close();
        host.close();
    }

    /** 助手暂态行的表现阶段；动画只由 Web 页面消费，不推进业务状态。 */
    public enum Activity {
        /** 模型调用已建立或 Turn 正在排队，尚无公开正文。 */
        WAITING,
        /** 模型正在产生公开正文。 */
        STREAMING,
        /** 调用已经提交或关闭，不再播放动画。 */
        SETTLED
    }

    /**
     * @param id 与最终 Item 一致的身份，纯等待占位使用 Turn 派生身份
     * @param text 公开正文
     * @param incomplete 是否未完成
     * @param textOffsetUtf16 尾部开始的 UTF-16 位置，正值不能按全文解析
     * @param turnId 来源 Turn，旧调用方未提供时为空，不能当作新发送的回复
     * @param activity 当前表现阶段
     */
    public record TemporaryMessage(
            String id,
            String text,
            boolean incomplete,
            long textOffsetUtf16,
            Optional<TurnId> turnId,
            Activity activity) {
        /** 校验来源容器和表现阶段非空；缺少身份不推测为当前发送的回复。 */
        public TemporaryMessage {
            turnId = Objects.requireNonNull(turnId, "turnId");
            activity = Objects.requireNonNull(activity, "activity");
        }

        /**
         * @param id 最终消息身份
         * @param text 公开正文
         * @param incomplete 是否未完成
         * @param textOffsetUtf16 尾部开始的 UTF-16 位置
         * @param turnId 来源 Turn
         */
        public TemporaryMessage(
                String id, String text, boolean incomplete, long textOffsetUtf16, Optional<TurnId> turnId) {
            this(id, text, incomplete, textOffsetUtf16, turnId, Activity.STREAMING);
        }

        /**
         * @param id 最终消息身份
         * @param text 公开正文
         * @param incomplete 是否未完成
         * @param textOffsetUtf16 尾部开始的 UTF-16 位置
         */
        public TemporaryMessage(String id, String text, boolean incomplete, long textOffsetUtf16) {
            this(id, text, incomplete, textOffsetUtf16, Optional.empty());
        }

        /**
         * @param id 最终消息身份
         * @param text 尚未截断的公开正文
         * @param incomplete 是否未完成
         */
        public TemporaryMessage(String id, String text, boolean incomplete) {
            this(id, text, incomplete, 0);
        }

        /**
         * @param message 非空的当前发送回显
         * @return 已有权威 Turn 身份且与该发送一致时为 true，才可排在该用户消息之后
         */
        public boolean belongsTo(OutgoingMessage message) {
            return turnId.isPresent() && turnId.equals(message.turnId());
        }
    }
}
