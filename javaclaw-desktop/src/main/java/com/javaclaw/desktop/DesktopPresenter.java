package com.javaclaw.desktop;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalDecision;
import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.ServerNotification;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewLoadRequest;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.InitializeResult;

/** 将 SDK 调用投影为 DesktopState；不持有 JavaFX 控件，也不访问 Server 实现。 */
public final class DesktopPresenter implements AutoCloseable {
    private final DesktopClientConnector connector;
    private final Consumer<Runnable> ui;
    private final Clock clock;
    private final DesktopStore store = new DesktopStore();
    private final DesktopExtensionCoordinator extensions = new DesktopExtensionCoordinator();
    private final DesktopNotificationRegistry notifications = new DesktopNotificationRegistry();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicLong connectionEpoch = new AtomicLong();
    private final DesktopInputCoordinator inputCoordinator;
    private final DesktopInputActions inputActions;
    private volatile JavaClawClient client;
    private volatile boolean closed;

    /**
     * 创建 Presenter。
     *
     * @param connector SDK 连接边界
     * @param ui JavaFX 调度器，通常为 Platform::runLater
     * @param clock 连接时间来源
     */
    public DesktopPresenter(DesktopClientConnector connector, Consumer<Runnable> ui, Clock clock) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.clock = Objects.requireNonNull(clock, "clock");
        inputCoordinator = new DesktopInputCoordinator(
                store,
                workers,
                ui,
                (epoch, candidate) -> !closed && connectionEpoch.get() == epoch && client == candidate);
        inputActions = new DesktopInputActions(inputCoordinator, connectionEpoch::get, this::requireClient);
    }

    /**
     * 订阅完整状态。
     *
     * @param listener JavaFX 视图监听者
     */
    public void subscribe(Consumer<DesktopState> listener) {
        store.subscribe(listener);
    }

    /**
     * 订阅当前及后续重连会话的强类型服务端通知。
     *
     * <p>通知只在 UI 调度器上投递；取消句柄或关闭 Presenter 后不再调用监听者。
     *
     * @param listener 快速、非阻塞的通知监听者
     * @return 幂等取消句柄
     */
    public DesktopNotificationSubscription subscribeNotifications(Consumer<ServerNotification> listener) {
        return notifications.subscribe(listener);
    }

    /** @return Workflow 与 MCP elicitation 共用的异步输入动作 */
    public DesktopInputActions inputs() {
        return inputActions;
    }

    /**
     * 订阅一个已冻结 Workspace 中 Extension 的失效事件。
     *
     * @param workspaceId 设置中心发起订阅时的精确 Workspace
     * @param extensionId 精确 Extension 标识
     * @param listener 资源 revision 事件监听者
     * @return 幂等取消句柄
     */
    public DesktopNotificationSubscription subscribeExtensionEvents(
            com.javaclaw.api.WorkspaceId workspaceId,
            String extensionId,
            Consumer<ExtensionRpcContracts.ExtensionEvent> listener) {
        com.javaclaw.api.WorkspaceId checkedWorkspace = Objects.requireNonNull(workspaceId, "workspaceId");
        String checkedId = requireText(extensionId, "extensionId");
        Consumer<ExtensionRpcContracts.ExtensionEvent> checkedListener = Objects.requireNonNull(listener, "listener");
        return subscribeNotifications(notification -> {
            if (notification instanceof ServerNotification.ExtensionChanged changed
                    && checkedId.equals(changed.event().extensionId())
                    && checkedWorkspace.equals(changed.event().workspaceId())) {
                checkedListener.accept(changed.event());
            }
        });
    }

    /** 建立 SDK 会话并加载首屏目录。 */
    public void connect() {
        beginConnection(false);
    }

    /**
     * 关闭旧会话并重新建立 Protocol v2 连接。
     *
     * <p>该操作可在当前连接已经失败时执行。并发重连只允许最后一次结果进入 Desktop 状态，较早建立的会话会立即关闭。
     *
     * @return 在 JavaFX 调度器上完成的 initialize 结果
     */
    public CompletableFuture<InitializeResult> reconnect() {
        return beginConnection(true);
    }

    /**
     * 选择 Workspace 并加载 Thread。
     *
     * @param workspace 目录中的 Workspace
     */
    public void selectWorkspace(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace");
        update(state -> DesktopStateProjection.selectWorkspace(state, workspace));
        workers.submit(() -> runGuarded(() -> loadThreads(workspace)));
    }

    /**
     * 选择 Thread 并读取 Item。
     *
     * @param thread 当前 Workspace 中的 Thread
     */
    public void selectThread(ConversationThread thread) {
        Objects.requireNonNull(thread, "thread");
        update(state -> DesktopStateProjection.selectThread(state, thread));
        workers.submit(() -> runGuarded(() -> loadTranscript(thread)));
    }

    /**
     * 从管理中心导航到父或子 Thread，并在后台读取目录与 Transcript。
     *
     * @param threadId 目标 Thread
     * @return 在 JavaFX 调度器上完成的已选择 Thread
     */
    public CompletableFuture<ConversationThread> navigateToThread(ThreadId threadId) {
        ThreadId checked = Objects.requireNonNull(threadId, "threadId");
        CompletableFuture<ConversationThread> result = new CompletableFuture<>();
        workers.submit(() -> navigateToThread(checked, result));
        return result;
    }

    /**
     * 创建 Workspace。
     *
     * @param name 名称
     * @param root 绝对根目录
     */
    public void createWorkspace(String name, Path root) {
        workers.submit(() -> runGuarded(() -> {
            Workspace created = requireClient().workspaces().create(name, root, CommandOptions.create(0));
            reloadCatalog(created);
        }));
    }

    /**
     * 在当前 Workspace 创建根 Thread。
     *
     * @param title 标题
     */
    public void createThread(String title) {
        Workspace workspace = store.state().threads().selectedWorkspace().orElseThrow();
        workers.submit(() -> runGuarded(() -> {
            ConversationThread created = requireClient()
                    .threads()
                    .create(workspace.id(), title, CommandOptions.create(workspace.revision()));
            List<ConversationThread> threads = requireClient().threads().list(workspace.id());
            publishThreads(workspace, threads, Optional.of(created));
        }));
    }

    /**
     * 返回主窗口当前 Workspace 的瞬时快照，仅用于设置中心首次选择作用域。
     *
     * <p>设置请求不得在后台重新读取该值；必须显式携带设置中心已冻结的 WorkspaceId。
     *
     * @return 当前 Workspace；尚未连接或未选择时为空
     */
    public Optional<com.javaclaw.api.WorkspaceId> selectedWorkspaceIdSnapshot() {
        return store.state().threads().selectedWorkspace().map(Workspace::id);
    }

    /**
     * 启动一个 Turn。
     *
     * @param message 用户消息
     */
    public void send(String message) {
        DesktopState snapshot = store.state();
        ConversationThread thread = snapshot.threads().selectedThread().orElseThrow();
        Optional<AgentProfileRef> profile = snapshot.interaction()
                .selectedProfile()
                .map(selected -> new AgentProfileRef(selected.id(), selected.revision()));
        String prompt = requireText(message, "message");
        setBusy(true);
        workers.submit(() -> runGuarded(() -> startAndObserve(thread, profile, prompt)));
    }

    /** 请求取消当前活动 Turn。 */
    public void cancelActiveTurn() {
        AgentTurn selected = store.state().threads().activeTurn().orElseThrow();
        workers.submit(() -> runGuarded(() -> {
            AgentTurn current = requireClient().turns().read(selected.id());
            requireClient().turns().cancel(current.id(), "Desktop 用户请求停止", CommandOptions.create(current.revision()));
        }));
    }

    /**
     * 批准或拒绝等待中的工具调用。
     *
     * @param approval 当前 revision 的审批
     * @param decision 决议
     * @param reason 用户原因
     */
    public void resolveApproval(ApprovalRecord approval, ApprovalDecision decision, String reason) {
        Objects.requireNonNull(approval, "approval");
        workers.submit(() -> runGuarded(() -> requireClient()
                .approvals()
                .resolve(
                        approval.request().id(),
                        decision,
                        requireText(reason, "reason"),
                        CommandOptions.create(approval.revision()))));
    }

    /**
     * 选择用于后续 Turn 的精确 Agent Profile。
     *
     * @param profile 最新版本配置
     */
    public void selectProfile(AgentProfile profile) {
        Objects.requireNonNull(profile, "profile");
        update(state -> DesktopStateProjection.selectProfile(state, profile));
    }

    /** 清除显式选择，使后续 Turn 重新解析 Workspace 默认 Agent Profile。 */
    public void clearProfileSelection() {
        update(DesktopStateProjection::clearProfileSelection);
    }

    /**
     * 异步加载指定扩展的 ViewSchema v2 文档。
     *
     * @param extensionId 扩展过滤；为空时读取全部已启用页面
     * @return 在 JavaFX 调度器上完成的页面目录
     */
    public CompletableFuture<List<ExtensionRpcContracts.ViewDocument>> listExtensionViews(
            Optional<String> extensionId) {
        Optional<String> checked = Objects.requireNonNull(extensionId, "extensionId");
        return submitSettingsRequest(connected -> extensions.views(connected, checked));
    }

    /**
     * 使用当前 Workspace 上下文加载 ViewSchema 引用的 query 数据。
     *
     * @param document 页面所属扩展
     * @param schema 已解码页面
     * @param request 分页和选择状态
     * @return 在 JavaFX 调度器上完成的权威页面数据
     */
    public CompletableFuture<ViewData> loadExtensionViewData(
            com.javaclaw.api.WorkspaceId workspaceId,
            ExtensionRpcContracts.ViewDocument document,
            ViewSchema schema,
            ViewLoadRequest request) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(request, "request");
        DesktopState snapshot = store.state();
        return submitSettingsRequest(
                connected -> extensions.load(connected, workspaceId, document, schema, snapshot, request));
    }

    /**
     * 执行 ViewSchema 表单或卡片 command。
     *
     * @param extensionId 扩展标识
     * @param invocation 已完成类型和 revision 绑定的调用
     * @return 在 JavaFX 调度器上完成的命令结果
     */
    public CompletableFuture<ExtensionRpcContracts.CallResult> executeExtensionViewCommand(
            com.javaclaw.api.WorkspaceId workspaceId, String extensionId, ViewCommandInvocation invocation) {
        DesktopState snapshot = store.state();
        Objects.requireNonNull(workspaceId, "workspaceId");
        requireText(extensionId, "extensionId");
        Objects.requireNonNull(invocation, "invocation");
        return submitSettingsRequest(
                connected -> extensions.execute(connected, workspaceId, extensionId, invocation, snapshot));
    }

    /**
     * 在 Desktop 后台执行一个只通过 Java SDK 访问平台的设置请求。
     *
     * <p>返回的 Future 只会在 UI 调度器上完成，因此设置页 Presenter 可以直接发布不可变状态，而不会从后台线程修改 JavaFX 控件。请求失败只交给调用页面处理，不污染主会话的错误状态。
     *
     * @param request 对当前已连接 SDK 的强类型操作
     * @param <T> 结果类型
     * @return 在 UI 调度器上完成的 Future
     */
    public <T> CompletableFuture<T> submitSettingsRequest(Function<JavaClawClient, T> request) {
        Function<JavaClawClient, T> checked = Objects.requireNonNull(request, "request");
        CompletableFuture<T> result = new CompletableFuture<>();
        workers.submit(() -> {
            try {
                T value = checked.apply(requireClient());
                ui.accept(() -> result.complete(value));
            } catch (Exception failure) {
                ui.accept(() -> result.completeExceptionally(failure));
            }
        });
        return result;
    }

    private CompletableFuture<InitializeResult> beginConnection(boolean replaceCurrent) {
        long epoch = connectionEpoch.incrementAndGet();
        inputCoordinator.invalidate();
        CompletableFuture<InitializeResult> result = new CompletableFuture<>();
        update(state -> DesktopStateProjection.connection(state, ConnectionState.connecting()));
        workers.submit(() -> connect(epoch, replaceCurrent, result));
        return result;
    }

    private void connect(long epoch, boolean replaceCurrent, CompletableFuture<InitializeResult> result) {
        JavaClawClient connected = null;
        try {
            if (replaceCurrent) {
                closeQuietly(detachClient());
            }
            connected = connector.connect(notification -> acceptNotification(epoch, notification));
            if (!installClient(epoch, connected)) {
                closeQuietly(connected);
                completeFailure(result, new IllegalStateException("连接结果已失效"));
                return;
            }
            loadConnectedCatalog(connected);
            inputCoordinator.connected(epoch, connected);
            InitializeResult initialized = connected.server();
            ui.accept(() -> result.complete(initialized));
        } catch (Exception failure) {
            discardClient(connected);
            fail(DesktopFailures.safeMessage(failure));
            completeFailure(result, failure);
        }
    }

    private void loadConnectedCatalog(JavaClawClient connected) {
        List<Workspace> workspaces = connected.workspaces().list();
        List<AgentProfile> profiles = connected.profiles().list().stream()
                .filter(profile -> profile.lifecycle() == ProfileLifecycle.ACTIVE)
                .toList();
        Optional<Workspace> selected = workspaces.stream().findFirst();
        List<ConversationThread> threads = selected.map(
                        workspace -> connected.threads().list(workspace.id()))
                .orElse(List.of());
        Optional<ConversationThread> thread = threads.stream().findFirst();
        String detail =
                connected.server().serverName() + " " + connected.server().serverVersion();
        publishConnected(detail, workspaces, selected, threads, thread, profiles);
        thread.ifPresent(this::loadTranscript);
    }

    private synchronized boolean installClient(long epoch, JavaClawClient connected) {
        if (closed || connectionEpoch.get() != epoch) {
            return false;
        }
        client = Objects.requireNonNull(connected, "connected");
        return true;
    }

    private synchronized JavaClawClient detachClient() {
        JavaClawClient detached = client;
        client = null;
        return detached;
    }

    private void acceptNotification(long epoch, ServerNotification notification) {
        ServerNotification checked = Objects.requireNonNull(notification, "notification");
        ui.accept(() -> {
            if (!closed && connectionEpoch.get() == epoch && client != null) {
                notifications.publish(checked);
            }
        });
    }

    private void discardClient(JavaClawClient candidate) {
        if (candidate == null) {
            return;
        }
        synchronized (this) {
            if (client == candidate) {
                client = null;
            }
        }
        closeQuietly(candidate);
    }

    private void completeFailure(CompletableFuture<?> result, Exception failure) {
        ui.accept(() -> result.completeExceptionally(failure));
    }

    private static void closeQuietly(JavaClawClient value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (Exception ignored) {
            // 重连和失败清理必须保留原始失败；旧会话关闭错误不覆盖新连接结果。
        }
    }

    private void startAndObserve(ConversationThread thread, Optional<AgentProfileRef> profile, String message)
            throws InterruptedException {
        CoreRpcContracts.TurnStartPayload payload =
                new CoreRpcContracts.TurnStartPayload(thread.id(), profile, message);
        AgentTurn turn = requireClient().turns().start(payload, CommandOptions.create(thread.revision()));
        publishActiveTurn(turn);
        long cursor = store.state().transcript().nextSequence();
        while (!DesktopStateProjection.terminal(turn.status()) && !closed) {
            Thread.sleep(150);
            CoreRpcContracts.ItemListResult page = requireClient().items().list(thread.id(), cursor, 500);
            cursor = page.nextSequence();
            turn = requireClient().turns().read(turn.id());
            List<ApprovalRecord> approvals = requireClient().approvals().list(Optional.of(turn.id()), false);
            publishObservation(turn, page, approvals);
        }
        setBusy(false);
    }

    private void loadThreads(Workspace workspace) {
        List<ConversationThread> threads = requireClient().threads().list(workspace.id());
        publishThreads(workspace, threads, threads.stream().findFirst());
    }

    private void loadTranscript(ConversationThread thread) {
        CoreRpcContracts.ItemListResult page = requireClient().items().list(thread.id(), 0, 500);
        update(state -> DesktopStateProjection.transcript(state, thread, page));
    }

    private void navigateToThread(ThreadId threadId, CompletableFuture<ConversationThread> result) {
        try {
            JavaClawClient connected = requireClient();
            ConversationThread thread = connected.threads().read(threadId);
            Workspace workspace = connected.workspaces().list().stream()
                    .filter(candidate -> candidate.id().equals(thread.workspaceId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Thread 所属 Workspace 不可用"));
            List<ConversationThread> threads = connected.threads().list(workspace.id());
            CoreRpcContracts.ItemListResult transcript = connected.items().list(thread.id(), 0, 500);
            ui.accept(() -> applyThreadNavigation(workspace, threads, thread, transcript, result));
        } catch (Exception failure) {
            ui.accept(() -> result.completeExceptionally(failure));
        }
    }

    private void applyThreadNavigation(
            Workspace workspace,
            List<ConversationThread> threads,
            ConversationThread thread,
            CoreRpcContracts.ItemListResult transcript,
            CompletableFuture<ConversationThread> result) {
        store.update(state -> DesktopStateProjection.threadCatalog(state, workspace, threads, Optional.of(thread)));
        store.update(state -> DesktopStateProjection.transcript(state, thread, transcript));
        result.complete(thread);
    }

    private void reloadCatalog(Workspace selected) {
        List<Workspace> workspaces = requireClient().workspaces().list();
        List<ConversationThread> threads = requireClient().threads().list(selected.id());
        publishCatalog(workspaces, selected, threads);
    }

    private void publishConnected(
            String detail,
            List<Workspace> workspaces,
            Optional<Workspace> selected,
            List<ConversationThread> threads,
            Optional<ConversationThread> thread,
            List<AgentProfile> profiles) {
        DesktopConnectionCatalog catalog =
                new DesktopConnectionCatalog(workspaces, selected, threads, thread, profiles);
        update(state -> DesktopStateProjection.connected(state, detail, Instant.now(clock), catalog));
    }

    private void publishCatalog(List<Workspace> workspaces, Workspace selected, List<ConversationThread> threads) {
        update(state -> DesktopStateProjection.catalog(state, workspaces, selected, threads));
    }

    private void publishThreads(
            Workspace workspace, List<ConversationThread> threads, Optional<ConversationThread> selected) {
        update(state -> DesktopStateProjection.threadCatalog(state, workspace, threads, selected));
        selected.ifPresent(this::loadTranscript);
    }

    private void publishActiveTurn(AgentTurn turn) {
        update(state -> DesktopStateProjection.activeTurn(state, turn));
    }

    private void publishObservation(
            AgentTurn turn, CoreRpcContracts.ItemListResult page, List<ApprovalRecord> approvals) {
        update(state -> DesktopStateProjection.observation(state, turn, page, approvals));
    }

    private void setBusy(boolean busy) {
        update(state -> DesktopStateProjection.busy(state, busy));
    }

    private void runGuarded(CheckedAction action) {
        try {
            action.run();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            fail("操作已中断");
        } catch (Exception failure) {
            fail(DesktopFailures.safeMessage(failure));
        }
    }

    private void fail(String message) {
        update(state -> DesktopStateProjection.failure(state, message));
        if (client == null) {
            update(state -> DesktopStateProjection.connection(state, ConnectionState.failed(message)));
        }
    }

    private void update(java.util.function.UnaryOperator<DesktopState> change) {
        ui.accept(() -> store.update(change));
    }

    private JavaClawClient requireClient() {
        return Objects.requireNonNull(client, "Desktop 尚未连接 App Server");
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }

    /** 关闭 SDK 和未完成后台任务。 */
    @Override
    public synchronized void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        connectionEpoch.incrementAndGet();
        inputCoordinator.invalidate();
        notifications.close();
        workers.shutdownNow();
        JavaClawClient current = detachClient();
        if (current != null) {
            current.close();
        }
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
