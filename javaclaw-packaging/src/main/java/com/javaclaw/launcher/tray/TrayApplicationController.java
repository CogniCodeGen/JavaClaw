package com.javaclaw.launcher.tray;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.launcher.tray.TrayState.ServerState;

/** 串行执行托盘命令并生成不可变视图状态；不持有任何 Schedule 能力。 */
public final class TrayApplicationController implements AutoCloseable {
    private final TrayServerControl server;
    private final MainWindowControl mainWindow;
    private final TrayView view;
    private final Executor executor;
    private final AutoCloseable executorOwner;
    private final AtomicBoolean closed = new AtomicBoolean();
    private TrayState state = new TrayState(ServerState.UNKNOWN, false, false, "正在读取状态", Optional.empty());

    /**
     * 创建使用单线程后台执行器的控制器。
     *
     * @param server App Server supervisor 端口
     * @param mainWindow Desktop 进程端口
     * @param view 被动托盘视图
     */
    public TrayApplicationController(TrayServerControl server, MainWindowControl mainWindow, TrayView view) {
        this(server, mainWindow, view, executor());
    }

    private TrayApplicationController(
            TrayServerControl server, MainWindowControl mainWindow, TrayView view, ExecutorService executor) {
        this(server, mainWindow, view, executor, executor::shutdownNow);
    }

    TrayApplicationController(
            TrayServerControl server,
            MainWindowControl mainWindow,
            TrayView view,
            Executor executor,
            AutoCloseable executorOwner) {
        this.server = Objects.requireNonNull(server, "server");
        this.mainWindow = Objects.requireNonNull(mainWindow, "mainWindow");
        this.view = Objects.requireNonNull(view, "view");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.executorOwner = Objects.requireNonNull(executorOwner, "executorOwner");
    }

    /** 绑定视图并异步读取首个状态。 */
    public void start() {
        requireOpen();
        view.bind(this::submit);
        submit(TrayCommand.REFRESH);
    }

    /**
     * 提交一个固定托盘命令。
     *
     * @param command 命令
     */
    public void submit(TrayCommand command) {
        TrayCommand checked = Objects.requireNonNull(command, "command");
        if (closed.get()) {
            return;
        }
        executor.execute(() -> handle(checked));
    }

    private void handle(TrayCommand command) {
        if (closed.get()) {
            return;
        }
        render(new TrayState(state.server(), mainWindow.running(), true, pendingMessage(command), Optional.empty()));
        try {
            switch (command) {
                case OPEN_MAIN -> openMainWindow();
                case START_SERVER -> startServer();
                case STOP_SERVER -> applyControlResult(server.stop(), "App Server 已停止");
                case RESTART_SERVER -> applyControlResult(server.restart(), "App Server 已重启");
                case REFRESH -> refresh("状态已刷新");
            }
        } catch (Exception failure) {
            fail(failure);
        }
    }

    private void openMainWindow() throws Exception {
        if (!server.running()) {
            server.start();
        }
        if (!mainWindow.running()) {
            mainWindow.open(this::windowClosed);
        }
        refresh("主窗口已打开");
    }

    private void startServer() throws Exception {
        server.start();
        refresh("App Server 已启动");
    }

    private void applyControlResult(TrayServerControl.ControlResult result, String message) throws Exception {
        TrayServerControl.ControlResult checked = Objects.requireNonNull(result, "result");
        if (!checked.accepted()) {
            render(new TrayState(
                    server.running() ? ServerState.RUNNING : ServerState.STOPPED,
                    mainWindow.running(),
                    false,
                    "操作被安全策略拒绝",
                    checked.reason()));
            return;
        }
        refresh(message);
    }

    private void refresh(String message) throws Exception {
        render(new TrayState(
                server.running() ? ServerState.RUNNING : ServerState.STOPPED,
                mainWindow.running(),
                false,
                message,
                Optional.empty()));
    }

    private void windowClosed() {
        if (closed.get()) {
            return;
        }
        executor.execute(() -> {
            if (!closed.get()) {
                render(new TrayState(state.server(), false, false, "主窗口已关闭", Optional.empty()));
            }
        });
    }

    private void fail(Exception failure) {
        String message = failure.getMessage();
        String reason =
                message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
        render(new TrayState(state.server(), mainWindow.running(), false, "操作失败", Optional.of(reason)));
    }

    private void render(TrayState next) {
        state = Objects.requireNonNull(next, "next");
        view.render(next);
    }

    private static String pendingMessage(TrayCommand command) {
        return switch (command) {
            case OPEN_MAIN -> "正在打开主窗口";
            case START_SERVER -> "正在启动 App Server";
            case STOP_SERVER -> "正在安全停止 App Server";
            case RESTART_SERVER -> "正在安全重启 App Server";
            case REFRESH -> "正在读取状态";
        };
    }

    private static ExecutorService executor() {
        return Executors.newSingleThreadExecutor(runnable ->
                Thread.ofPlatform().daemon(false).name("javaclaw-tray-control").unstarted(runnable));
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("tray controller is closed");
        }
    }

    /** 删除托盘图标并停止控制线程；不会停止 App Server。 */
    @Override
    public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Exception failure = null;
        try {
            view.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            executorOwner.close();
        } catch (Exception closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
