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

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalDecision;
import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.RoleLifecycle;
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
    private final DesktopConfigurationEvents configurationEvents = new DesktopConfigurationEvents();
    private final DesktopChatActions chatActions = new DesktopChatActions(this, store::state);
    private final DesktopCatalogRefresh catalogRefresh = new DesktopCatalogRefresh(this, store);
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicLong connectionEpoch = new AtomicLong();
    private final DesktopInputCoordinator inputCoordinator;
    private final DesktopInputActions inputActions;
    private final DesktopTurnStreamCoordinator streams;
    private final DesktopConversationCoordinator conversations;
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
        streams = new DesktopTurnStreamCoordinator(store, ui, workers);
        conversations = new DesktopConversationCoordinator(store, ui, workers, streams, () -> client);
        configurationEvents.subscribe(change -> {
            if (change.kind() == DesktopConfigurationChange.Kind.WORKSPACES) {
                catalogRefresh.request();
            }
        });
    }

    /** @return 当前 Desktop 共享的配置失效通知源，随 Presenter 关闭 */
    public DesktopConfigurationEvents configurationEvents() {
        return configurationEvents;
    }

    /** 重新读取工作区目录，不改变当前选择或对话。 */
    public void refreshWorkspaceCatalog() {
        catalogRefresh.request();
    }

    /** @param workspace 固定目标工作区 @param thread 可复用对话 @param model 精确模型 @return 应用完成 */
    public java.util.concurrent.CompletionStage<Void> useModel(
            Optional<com.javaclaw.api.WorkspaceId> workspace, Optional<ThreadId> thread, com.javaclaw.api.ProviderRef model) {
        return chatActions.useModel(workspace, thread, model);
    }

    /**
     * @param workspace 固定工作区 @param thread 固定对话 @param selected 对话覆盖
     * @param options 原始版本 @param remember 是否同时记为日常默认 @return 权威对话配置
     */
    public java.util.concurrent.CompletionStage<com.javaclaw.api.ExecutionConfiguration> rememberChatSelection(
            com.javaclaw.api.WorkspaceId workspace, ThreadId thread, ExecutionOverrides selected,
            CommandOptions options, boolean remember) {
        return chatActions.remember(workspace, thread, selected, options, remember);
    }

    /** @param name 工作区名称 @param root 用户选择的绝对目录 @return 创建完成并通知目录刷新 */
    public java.util.concurrent.CompletionStage<Workspace> createModelWorkspace(String name, Path root) {
        return chatActions.createWorkspace(name, root);
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
        String checkedId = DesktopFailures.requireText(extensionId, "extensionId");
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
     * 关闭旧会话并重新建立 Protocol v3 连接。
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
        conversations.selectWorkspace(workspace);
    }

    /**
     * 选择 Thread 并读取 Item。
     *
     * @param thread 当前 Workspace 中的 Thread
     */
    public void selectThread(ConversationThread thread) {
        Objects.requireNonNull(thread, "thread");
        conversations.selectThread(thread);
    }

    /**
     * 从管理中心导航到父或子 Thread，并在后台读取目录与 Transcript。
     *
     * @param threadId 目标 Thread
     * @return 在 JavaFX 调度器上完成的已选择 Thread
     */
    public CompletableFuture<ConversationThread> navigateToThread(ThreadId threadId) {
        ThreadId checked = Objects.requireNonNull(threadId, "threadId");
        return conversations.navigate(checked);
    }

    /**
     * 创建 Workspace。
     *
     * @param name 名称
     * @param root 绝对根目录
     */
    public void createWorkspace(String name, Path root) {
        createWorkspace(name, root, ExecutionOverrides.empty());
    }

    /** @param name 名称 @param root 绝对根目录 @param execution 独立角色、模型与权限选择 */
    public void createWorkspace(String name, Path root, ExecutionOverrides execution) {
        Objects.requireNonNull(execution, "execution");
        conversations.createWorkspace(name, root, execution);
    }

    /**
     * 在当前 Workspace 创建根 Thread。
     *
     * @param title 标题
     */
    public void createThread(String title) {
        conversations.createThread(title);
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
        Optional<AgentRoleRef> role = store.state()
                .interaction()
                .selectedRole()
                .map(selected -> new AgentRoleRef(selected.id(), selected.revision()));
        send(
                message,
                new ExecutionOverrides(
                        role,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
    }

    /** @param message 用户消息 @param execution 当前显式独立选择，服务端负责解析与冻结 */
    public CompletableFuture<com.javaclaw.api.AgentTurn> send(String message, ExecutionOverrides execution) {
        String prompt = DesktopFailures.requireText(message, "message");
        ExecutionOverrides selection = Objects.requireNonNull(execution, "execution");
        return conversations.send(prompt, selection);
    }

    /** 请求取消当前活动 Turn。 */
    public void cancelActiveTurn() {
        conversations.cancel();
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
                        DesktopFailures.requireText(reason, "reason"),
                        CommandOptions.create(approval.revision()))));
    }

    /**
     * 选择用于后续 Turn 的精确 Agent Role。
     *
     * @param role 最新版本配置
     */
    public void selectRole(AgentRole role) {
        Objects.requireNonNull(role, "role");
        update(state -> DesktopStateProjection.selectRole(state, role));
    }

    /** 清除显式选择，使后续 Turn 重新解析 Workspace 默认 Agent Role。 */
    public void clearRoleSelection() {
        update(DesktopStateProjection::clearRoleSelection);
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
        DesktopFailures.requireText(extensionId, "extensionId");
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
        return DesktopRequests.submit(workers, ui, this::requireClient, request);
    }

    private CompletableFuture<InitializeResult> beginConnection(boolean replaceCurrent) {
        long epoch = connectionEpoch.incrementAndGet();
        inputCoordinator.invalidate();
        conversations.reconnecting();
        CompletableFuture<InitializeResult> result = new CompletableFuture<>();
        update(state -> DesktopStateProjection.connection(state, ConnectionState.connecting()));
        workers.submit(() -> connect(epoch, replaceCurrent, result));
        return result;
    }

    private void connect(long epoch, boolean replaceCurrent, CompletableFuture<InitializeResult> result) {
        JavaClawClient connected = null;
        try {
            if (replaceCurrent) {
                DesktopRequests.closeQuietly(detachClient());
            }
            connected = connector.connect(notification -> acceptNotification(epoch, notification));
            if (!installClient(epoch, connected)) {
                DesktopRequests.closeQuietly(connected);
                completeFailure(result, new IllegalStateException("连接结果已失效"));
                return;
            }
            loadConnectedCatalog(epoch, connected);
            inputCoordinator.connected(epoch, connected);
            InitializeResult initialized = connected.server();
            ui.accept(() -> result.complete(initialized));
        } catch (Exception failure) {
            discardClient(connected);
            if (!closed && connectionEpoch.get() == epoch) {
                fail(DesktopFailures.safeMessage(failure));
            }
            completeFailure(result, failure);
        }
    }

    private void loadConnectedCatalog(long epoch, JavaClawClient connected) {
        List<Workspace> workspaces = connected.workspaces().list();
        List<AgentRole> roles = connected.roles().list().stream()
                .filter(role -> role.lifecycle() == RoleLifecycle.ACTIVE)
                .toList();
        Optional<Workspace> selected = store.state()
                .threads()
                .selectedWorkspace()
                .flatMap(previous -> workspaces.stream()
                        .filter(value -> value.id().equals(previous.id()))
                        .findFirst())
                .or(() -> workspaces.stream().findFirst());
        List<ConversationThread> threads = selected.map(
                        workspace -> connected.threads().list(workspace.id()))
                .orElse(List.of());
        Optional<ConversationThread> thread = store.state()
                .threads()
                .selectedThread()
                .flatMap(previous -> threads.stream()
                        .filter(value -> value.id().equals(previous.id()))
                        .findFirst())
                .or(() -> threads.stream().findFirst());
        String detail =
                connected.server().serverName() + " " + connected.server().serverVersion();
        publishConnected(
                epoch, connected, detail, new DesktopConnectionCatalog(workspaces, selected, threads, thread, roles));
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
        if (checked instanceof ServerNotification.TurnStream) {
            return;
        }
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
        DesktopRequests.closeQuietly(candidate);
    }

    private void completeFailure(CompletableFuture<?> result, Exception failure) {
        ui.accept(() -> result.completeExceptionally(failure));
    }

    /** @param following 是否跟随最新消息；返回最新端时重新读取被分页窗口淘汰的摘要 */
    public void followTranscript(boolean following) {
        conversations.following(following);
    }

    /** 按来源 sequence 向前读取100条；重复点击由当前窗口边界自然幂等归并。 */
    public void loadEarlierTranscript() {
        conversations.earlier();
    }

    private void publishConnected(
            long epoch, JavaClawClient connected, String detail, DesktopConnectionCatalog catalog) {
        ui.accept(() -> {
            if (!closed && connectionEpoch.get() == epoch && client == connected) {
                store.update(state -> DesktopStateProjection.connected(state, detail, Instant.now(clock), catalog));
                catalog.selectedThread().ifPresent(conversations::load);
            }
        });
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
        configurationEvents.close();
        conversations.close();
        streams.close();
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
