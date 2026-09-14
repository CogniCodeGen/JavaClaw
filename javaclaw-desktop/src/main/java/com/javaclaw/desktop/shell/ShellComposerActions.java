package com.javaclaw.desktop.shell;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.settings.ChatConfigurationPanel;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.state.OutgoingMessage;

/** FX 线程拥有输入草稿；本地接纳迁出正文，异步结果只反馈原作用域，不覆盖输入或抢焦点。 */
final class ShellComposerActions implements AutoCloseable {
    private final TextArea input;
    private final Supplier<DesktopPresenter> presenter;
    private final Supplier<DesktopState> state;
    private final Supplier<ChatConfigurationPanel> configuration;
    private final Consumer<String> errors;
    private final Runnable changed;
    private final ComposerDrafts drafts = new ComposerDrafts();
    private final Map<ComposerDrafts.Scope, String> submitting = new HashMap<>();
    private boolean binding;
    private boolean closed;

    ShellComposerActions(
            TextArea input,
            Supplier<DesktopPresenter> presenter,
            Supplier<DesktopState> state,
            Supplier<ChatConfigurationPanel> configuration,
            Consumer<String> errors,
            Runnable changed) {
        this.input = input;
        this.presenter = presenter;
        this.state = state;
        this.configuration = configuration;
        this.errors = errors;
        this.changed = changed;
    }

    void edited() {
        if (!binding) {
            drafts.edited(input.getText());
        }
    }

    void bind(DesktopState next) {
        setText(drafts.bind(scope(next), input.getText()));
    }

    void accepted(DesktopState next) {
        for (OutgoingMessage outgoing : next.transcript().outgoings()) {
            if (drafts.accepted(outgoing)) {
                setText("");
            }
        }
    }

    boolean readyForTurn() {
        DesktopState current = state.get();
        return current.connection().status() == ConnectionState.Status.CONNECTED
                && current.threads().selectedThread().isPresent()
                && current.threads().activeTurn().isEmpty()
                && !current.interaction().busy()
                && !submitting.containsKey(scope(current));
    }

    boolean ready() {
        return readyForTurn()
                && !input.getText().isBlank()
                && (retrySource().isPresent()
                        || configuration.get() != null && configuration.get().ready());
    }

    Optional<String> retrySource() {
        return drafts.retrySource(drafts.submission(input.getText()));
    }

    void send() {
        if (!ready()) {
            errors.accept(input.getText().isBlank() ? "请输入消息内容" : "当前对话或配置尚未就绪，请稍后重试");
            return;
        }
        ComposerDrafts.Submission submitted = drafts.submission(input.getText());
        Optional<String> restored = drafts.retrySource(submitted);
        if (restored.isPresent()) {
            retry(restored.orElseThrow(), Optional.of(submitted));
            return;
        }
        var execution = configuration.get().execution();
        var options = drafts.options(submitted, execution);
        String id = "outgoing:" + options.idempotencyKey();
        stage(submitted, id);
        submit(id, () -> presenter.get().send(submitted.text(), execution, options));
    }

    void outgoingAction(String action, String id) {
        Optional<OutgoingMessage> candidate = unconfirmed(id);
        if (candidate.isEmpty()) {
            return;
        }
        OutgoingMessage message = candidate.orElseThrow();
        switch (action) {
            case "retrySend" -> retry(id, Optional.empty());
            case "restoreSend" -> restore(message);
            case "copySend" -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(message.text());
                Clipboard.getSystemClipboard().setContent(content);
            }
            default -> {}
        }
    }

    private void restore(OutgoingMessage message) {
        if (!input.getText().isEmpty() || !drafts.restore(message.id(), message.text())) {
            errors.accept("请先发送或清空当前草稿，也可复制原文");
            return;
        }
        setText(message.text());
        input.requestFocus();
        changed.run();
    }

    private void retry(String id, Optional<ComposerDrafts.Submission> submitted) {
        if (!readyForTurn() || unconfirmed(id).isEmpty()) {
            errors.accept("原消息当前无法重试，请等待对话恢复完成");
            return;
        }
        ComposerDrafts.Submission current = drafts.submission(input.getText());
        Optional<ComposerDrafts.Submission> migrating = submitted.or(
                () -> drafts.retrySource(current).filter(id::equals).map(ignored -> current));
        migrating.ifPresent(value -> stage(value, id));
        submit(id, () -> presenter.get().retrySend(id));
    }

    private Optional<OutgoingMessage> unconfirmed(String id) {
        return state.get().transcript().outgoings().stream()
                .filter(message -> message.id().equals(id) && message.status() == OutgoingMessage.Status.UNCONFIRMED)
                .findFirst();
    }

    private void stage(ComposerDrafts.Submission submitted, String id) {
        long previous = state.get().transcript().outgoings().stream()
                .filter(message -> message.id().equals(id))
                .mapToLong(OutgoingMessage::attempt)
                .max()
                .orElse(-1);
        drafts.stage(submitted, id, previous);
    }

    private void submit(String id, Supplier<CompletableFuture<AgentTurn>> request) {
        ComposerDrafts.Scope target = scope(state.get());
        submitting.put(target, id);
        changed.run();
        try {
            request.get().whenComplete((turn, failure) -> {
                Runnable completion = () -> finished(target, id, failure);
                if (Platform.isFxApplicationThread()) {
                    completion.run();
                } else {
                    Platform.runLater(completion);
                }
            });
        } catch (RuntimeException failure) {
            finished(target, id, failure);
        }
    }

    private void finished(ComposerDrafts.Scope target, String id, Throwable failure) {
        if (closed) {
            return;
        }
        submitting.remove(target, id);
        drafts.finished(id);
        if (target.equals(scope(state.get()))) {
            if (failure != null) {
                errors.accept("消息提交未确认：" + safeMessage(failure));
            }
            changed.run();
        }
    }

    private static String safeMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? "请检查连接后重试" : cause.getMessage();
    }

    private void setText(String text) {
        binding = true;
        try {
            if (!input.getText().equals(text)) {
                input.setText(text);
            }
        } finally {
            binding = false;
        }
    }

    private static ComposerDrafts.Scope scope(DesktopState state) {
        return new ComposerDrafts.Scope(
                state.threads().selectedWorkspace().map(Workspace::id),
                state.threads().selectedThread().map(ConversationThread::id));
    }

    @Override
    public void close() {
        closed = true;
    }
}
