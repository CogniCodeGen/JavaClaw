package com.javaclaw.desktop;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.control.Button;

import com.javaclaw.sdk.JavaClawClient;
import com.javaclaw.sdk.model.ThreadInfo;
import com.javaclaw.sdk.model.WorkspaceInfo;

/** 管理页的 SDK 异步边界；有限并发、页面代次和 FX Dispatcher 避免旧请求覆盖新页面。 */
final class ManagementViewModel implements AutoCloseable {
    private final JavaClawClient client;
    private final FxDispatcher fx;
    private final Supplier<WorkspaceInfo> workspace;
    private final Consumer<ThreadInfo> openThread;
    private final Runnable refreshProfiles;
    private final DesktopDialogGateway dialogs;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicInteger pending = new AtomicInteger();
    private final Semaphore permits = new Semaphore(4);
    private final java.util.Set<CompletableFuture<?>> cancellableReads = ConcurrentHashMap.newKeySet();
    private final BooleanProperty busy = new SimpleBooleanProperty();
    private final StringProperty status = new SimpleStringProperty();
    private final javafx.beans.property.ObjectProperty<ManagementFeedback> feedback =
            new SimpleObjectProperty<>(ManagementFeedback.idle());
    private volatile boolean closed;
    private CommandContext activeCommand;

    ManagementViewModel(
            JavaClawClient client,
            FxDispatcher fx,
            Supplier<WorkspaceInfo> workspace,
            Consumer<ThreadInfo> openThread,
            Runnable refreshProfiles,
            DesktopDialogGateway dialogs) {
        this.client = Objects.requireNonNull(client);
        this.fx = Objects.requireNonNull(fx);
        this.workspace = workspace;
        this.openThread = openThread;
        this.refreshProfiles = refreshProfiles;
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
    }

    ReadOnlyBooleanProperty busyProperty() {
        return busy;
    }

    ReadOnlyStringProperty statusProperty() {
        return status;
    }

    ReadOnlyObjectProperty<ManagementFeedback> feedbackProperty() {
        return feedback;
    }

    String workspaceId() {
        var selected = workspace.get();
        if (selected == null) {
            throw new IllegalStateException("请先选择工作区");
        }
        return selected.id();
    }

    String workspaceName() {
        var selected = workspace.get();
        return selected == null ? "未选择工作区" : selected.name();
    }

    java.nio.file.Path workspaceRoot() {
        var selected = workspace.get();
        return selected == null ? null : selected.root();
    }

    DesktopDialogGateway dialogs() {
        return dialogs;
    }

    /** 在当前页面展示本地表单校验错误；不创建请求，也不改变服务端状态。 */
    void validationError(String message) {
        updateFeedback(ManagementFeedback.Severity.VALIDATION_ERROR, "请检查输入：" + Objects.toString(message, "内容无效"));
    }

    /** 用领域操作的准确结果替换通用“已处理”反馈，例如显示级联更新数量。 */
    void operationSucceeded(String message) {
        updateFeedback(ManagementFeedback.Severity.SUCCESS, Objects.toString(message, "操作已完成"));
    }

    /** 报告主写入已成功但后续资源存在部分失败，避免把部分成功描述为整体失败或整体成功。 */
    void operationPartiallyFailed(String message) {
        updateFeedback(ManagementFeedback.Severity.SERVER_ERROR, Objects.toString(message, "部分操作未完成"));
    }

    void newPage() {
        generation.incrementAndGet();
        cancellableReads.forEach(future -> future.cancel(true));
        updateFeedback(ManagementFeedback.Severity.IDLE, "");
    }

    /** 在资源级命令上下文中运行按钮动作；只有实际发起请求的按钮会保持禁用。 */
    void invokeCommand(Button source, Runnable action) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(action, "action");
        if (ManagementForms.commandRunning(source)) {
            return;
        }
        var context = new CommandContext(source);
        CommandContext previous = activeCommand;
        activeCommand = context;
        ManagementForms.setCommandRunning(source, true);
        try {
            action.run();
        } finally {
            activeCommand = previous;
            if (!context.consumed) {
                ManagementForms.setCommandRunning(source, false);
            }
        }
    }

    void showThread(String threadId) {
        execute("读取执行记录", sdk -> sdk.threads().read(threadId), value -> openThread.accept(value.thread()));
    }

    void profilesChanged() {
        refreshProfiles.run();
    }

    void openAuthorization(java.net.URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("授权 URL 必须是 HTTPS");
        }
        dialogs.openExternal(uri);
    }

    <T> void execute(String action, Function<JavaClawClient, CompletableFuture<T>> operation, Consumer<T> receive) {
        execute(action, operation, receive, ignored -> {});
    }

    /** 执行 SDK 操作并分别回传成功与失败；失败回调只在当前页面代次执行，写请求不会因页面离开而自动重放。 */
    <T> void execute(
            String action,
            Function<JavaClawClient, CompletableFuture<T>> operation,
            Consumer<T> receive,
            Consumer<Throwable> reject) {
        if (closed) {
            return;
        }
        CommandContext command = activeCommand;
        if (command != null) {
            command.consumed = true;
        }
        long acceptedGeneration = generation.get();
        // 不无限创建等待请求的虚拟线程；目标按钮由 CommandContext 独立禁用，并发超限时明确拒绝。
        if (!permits.tryAcquire()) {
            updateFeedback(ManagementFeedback.Severity.VALIDATION_ERROR, "已有四个操作在进行，请稍后重试。");
            completeCommand(command);
            return;
        }
        pending.incrementAndGet();
        busy.set(true);
        updateFeedback(ManagementFeedback.Severity.RUNNING, action + "…");
        Thread.startVirtualThread(() -> {
            CompletableFuture<T> request = null;
            try {
                request = operation.apply(client);
                boolean cancellable = pureRead(action);
                if (cancellable) {
                    cancellableReads.add(request);
                }
                T result = request.join();
                fx.execute(() -> {
                    if (!closed && acceptedGeneration == generation.get()) {
                        updateFeedback(ManagementFeedback.Severity.SUCCESS, "已处理：" + action);
                        try {
                            receive.accept(result);
                        } catch (RuntimeException failure) {
                            updateFeedback(
                                    ManagementFeedback.Severity.SERVER_ERROR,
                                    "页面显示失败："
                                            + Objects.toString(
                                                    failure.getMessage(),
                                                    failure.getClass().getSimpleName()));
                        }
                    }
                });
            } catch (Throwable failure) {
                Throwable cause = failure;
                while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                Throwable rootCause = cause;
                String message = Objects.toString(
                        rootCause.getMessage(), rootCause.getClass().getSimpleName());
                fx.execute(() -> {
                    if (!closed && acceptedGeneration == generation.get()) {
                        updateFeedback(ManagementFeedback.Severity.SERVER_ERROR, action + "失败：" + message);
                        try {
                            reject.accept(rootCause);
                        } catch (RuntimeException callbackFailure) {
                            updateFeedback(
                                    ManagementFeedback.Severity.SERVER_ERROR,
                                    "失败状态处理异常："
                                            + Objects.toString(
                                                    callbackFailure.getMessage(),
                                                    callbackFailure.getClass().getSimpleName()));
                        }
                    }
                });
            } finally {
                if (request != null) {
                    cancellableReads.remove(request);
                }
                permits.release();
                pending.decrementAndGet();
                fx.execute(() -> {
                    busy.set(pending.get() != 0);
                    completeCommand(command);
                });
            }
        });
    }

    static String key(String operation) {
        return "desktop-" + operation + "-" + UUID.randomUUID();
    }

    private void updateFeedback(ManagementFeedback.Severity severity, String message) {
        var value = new ManagementFeedback(severity, message);
        feedback.set(value);
        status.set(value.message());
    }

    private static void completeCommand(CommandContext command) {
        if (command != null) {
            ManagementForms.setCommandRunning(command.source, false);
        }
    }

    private static boolean pureRead(String action) {
        return java.util.List.of("读取", "加载", "解析", "检索", "预览", "刷新", "检查", "测试", "发现", "列出").stream()
                .anyMatch(action::startsWith);
    }

    @Override
    public void close() {
        closed = true;
        generation.incrementAndGet();
        cancellableReads.forEach(future -> future.cancel(true));
        cancellableReads.clear();
        // 关闭 UI 不撤销已经提交的写请求，也不把结果未知的操作自动重放。
    }

    private static final class CommandContext {
        private final Button source;
        private boolean consumed;

        private CommandContext(Button source) {
            this.source = source;
        }
    }
}
