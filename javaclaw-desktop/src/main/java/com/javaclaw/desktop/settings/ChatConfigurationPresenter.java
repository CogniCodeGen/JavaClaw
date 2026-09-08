package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionPreview;
import com.javaclaw.api.Workspace;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopConfigurationChange;
import com.javaclaw.desktop.DesktopNotificationSubscription;

/**
 * 聊天配置先持久化再显示可发送；服务端是实际模型、思考和阻塞原因的唯一解析者。
 * 自动刷新不会推进失败草稿的写入版本；在途失效合并补读，迟到响应按作用域代次丢弃。
 */
final class ChatConfigurationPresenter implements AutoCloseable {
    private final CoreSettingsGateway gateway;
    private final ExecutionSelectionLoader loader;
    private final DesktopNotificationSubscription subscription;
    private ExecutionSelectionLoader.Scope scope = new ExecutionSelectionLoader.Scope(Optional.empty(), Optional.empty(), false);
    private Optional<ExecutionSelectionLoader.Snapshot> snapshot = Optional.empty();
    private Optional<ExecutionPreview> preview = Optional.empty();
    private ExecutionOverrides baseline = ExecutionOverrides.empty();
    private ExecutionOverrides draft = baseline;
    private Consumer<ChatConfigurationState> listener = ignored -> {};
    private boolean pending;
    private boolean requested;
    private boolean remember;
    private boolean closed;
    private long revision;
    private long epoch;
    private String message = "选择工作区，开始聊天";

    ChatConfigurationPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        loader = new ExecutionSelectionLoader(gateway);
        subscription = gateway.onConfigurationChanged(this::invalidated);
    }

    void subscribe(Consumer<ChatConfigurationState> listener) {
        this.listener = listener;
        publish();
    }

    void bind(Optional<Workspace> workspace, Optional<ConversationThread> thread) {
        var next = new ExecutionSelectionLoader.Scope(workspace, thread, false);
        boolean same = scope.sameBinding(next) && epoch > 0;
        scope = next;
        if (same) {
            return;
        }
        epoch++;
        pending = false;
        requested = false;
        snapshot = Optional.empty();
        preview = Optional.empty();
        baseline = ExecutionOverrides.empty();
        draft = baseline;
        revision = 0;
        refresh();
    }

    void refresh() {
        if (closed) {
            return;
        }
        requested = true;
        if (pending) {
            return;
        }
        requested = false;
        if (scope.workspace().isEmpty()) {
            message = "选择或创建工作区，开始聊天";
            publish();
            return;
        }
        boolean retained = dirty();
        long request = begin("正在更新聊天配置…");
        var frozen = scope;
        loader.load(frozen).whenComplete((loaded, failure) -> {
            if (!current(request)) {
                return;
            }
            if (failure != null) {
                finishFailure(failure);
                return;
            }
            snapshot = Optional.of(loaded);
            if (!retained) {
                Optional<ExecutionConfiguration> saved = loaded.sources().thread();
                baseline = saved.map(ExecutionConfiguration::overrides).orElseGet(ExecutionOverrides::empty);
                draft = baseline;
                revision = saved.map(ExecutionConfiguration::revision).orElse(0L);
            }
            readPreview(request, retained ? "配置已更新，草稿已保留" : "");
        });
    }

    void edit(ExecutionOverrides selected, boolean remember) {
        if (pending || scope.thread().isEmpty()) {
            return;
        }
        draft = Objects.requireNonNull(selected, "selected");
        this.remember = remember;
        save();
    }

    void retry() {
        if (dirty()) {
            save();
        } else {
            refresh();
        }
    }

    void discard() {
        if (!pending) {
            draft = baseline;
            refresh();
        }
    }

    ChatConfigurationState state() {
        return new ChatConfigurationState(draft, snapshot, preview, pending, dirty(), message);
    }

    ExecutionSelectionLoader.Scope scope() {
        return scope;
    }

    private void save() {
        if (pending || scope.thread().isEmpty()) {
            return;
        }
        long request = begin("正在保存选择…");
        var thread = scope.thread().orElseThrow();
        gateway.rememberChatSelection(thread.workspaceId(), thread.id(), draft, CommandOptions.create(revision), remember)
                .whenComplete((saved, failure) -> {
                    if (!current(request)) {
                        return;
                    }
                    if (failure != null) {
                        finishFailure(failure);
                    } else {
                        baseline = saved.overrides();
                        draft = baseline;
                        revision = saved.revision();
                        readPreview(request, remember ? "已记住，新对话将继续使用" : "已更新当前对话");
                    }
                });
    }

    private void readPreview(long request, String success) {
        gateway.previewChatExecution(scope.workspace().orElseThrow().id(), scope.thread().map(ConversationThread::id), draft)
                .whenComplete((resolved, failure) -> {
                    if (!current(request)) {
                        return;
                    }
                    if (failure != null) {
                        finishFailure(failure);
                        return;
                    }
                    preview = Optional.of(resolved);
                    pending = false;
                    message = resolved.ready() ? success : resolved.blockers().getFirst().message();
                    publish();
                    drain();
                });
    }

    private void invalidated(DesktopConfigurationChange change) {
        boolean sameWorkspace = change.workspaceId().isEmpty()
                || change.workspaceId().equals(scope.workspace().map(Workspace::id));
        boolean sameThread = change.threadId().isEmpty()
                || change.threadId().equals(scope.thread().map(ConversationThread::id));
        if (change.kind() != DesktopConfigurationChange.Kind.EXECUTION || sameWorkspace && sameThread) {
            refresh();
        }
    }

    private boolean dirty() {
        return !draft.equals(baseline);
    }

    private boolean current(long request) {
        return !closed && request == epoch;
    }

    private long begin(String text) {
        pending = true;
        preview = Optional.empty();
        message = text;
        long request = ++epoch;
        publish();
        return request;
    }

    private void finishFailure(Throwable failure) {
        pending = false;
        preview = Optional.empty();
        message = SettingsFailures.message(failure) + (dirty() ? "；选择已保留，可重试或放弃更改" : "");
        publish();
        drain();
    }

    private void drain() {
        if (requested) {
            refresh();
        }
    }

    private void publish() {
        listener.accept(state());
    }

    @Override
    public void close() {
        closed = true;
        epoch++;
        subscription.close();
    }
}
