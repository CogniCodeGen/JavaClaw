package com.javaclaw.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import com.javaclaw.sdk.ApprovalRequestedNotification;
import com.javaclaw.sdk.AttachmentClient;
import com.javaclaw.sdk.ClientNotification;
import com.javaclaw.sdk.ConnectionStatus;
import com.javaclaw.sdk.EventNotification;
import com.javaclaw.sdk.ItemDeltaNotification;
import com.javaclaw.sdk.JavaClawClient;
import com.javaclaw.sdk.ResyncRequiredNotification;
import com.javaclaw.sdk.UserInputRequestedNotification;
import com.javaclaw.sdk.model.AttachmentInfo;
import com.javaclaw.sdk.model.EventInfo;
import com.javaclaw.sdk.model.ItemInfo;
import com.javaclaw.sdk.model.ProfileInfo;
import com.javaclaw.sdk.model.ThreadInfo;
import com.javaclaw.sdk.model.ThreadSnapshot;
import com.javaclaw.sdk.model.TurnInfo;
import com.javaclaw.sdk.model.TurnInput;
import com.javaclaw.sdk.model.TurnStartRequest;
import com.javaclaw.sdk.model.WorkspaceInfo;

/** SDK-only UI state. Every blocking SDK call runs on a virtual thread. */
public final class DesktopViewModel implements AutoCloseable {
    private final JavaClawClient client;
    private final FxDispatcher fx;
    private final boolean connectionInitialized;
    private final ManagementViewModel management;
    private final ObservableList<WorkspaceInfo> workspaces = FXCollections.observableArrayList();
    private final ObservableList<ThreadInfo> threads = FXCollections.observableArrayList();
    private final ObservableList<ProfileInfo> profiles = FXCollections.observableArrayList();
    private final ObservableList<TranscriptBlock> transcript = FXCollections.observableArrayList();
    private final javafx.collections.ObservableMap<String, com.javaclaw.sdk.model.TurnExecutionSummaryInfo>
            executionSummaries = FXCollections.observableHashMap();
    private final StringProperty connection = new SimpleStringProperty("正在连接…");
    private final StringProperty error = new SimpleStringProperty("");
    private final StringProperty stream = new SimpleStringProperty("");
    private final BooleanProperty busy = new SimpleBooleanProperty();
    private final StringProperty theme = new SimpleStringProperty(DesktopTheme.DEFAULT_ID);
    private final ObjectProperty<ThreadInfo> selection = new SimpleObjectProperty<>();
    private final ObjectProperty<ComposerActivity> composerActivity =
            new SimpleObjectProperty<>(ComposerActivity.idle());
    private final ObjectProperty<ThreadScope> threadScope = new SimpleObjectProperty<>(ThreadScope.ACTIVE);
    private final ObjectProperty<DesktopProgressSnapshot> progress =
            new SimpleObjectProperty<>(DesktopProgressSnapshot.empty());
    private final AtomicInteger backgroundCount = new AtomicInteger();
    private final java.util.concurrent.ConcurrentMap<SubmissionKey, SubmissionContext> activeSubmissions =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> steeringTurns = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicLong selectionRevision = new AtomicLong();
    private final TranscriptStreamBuffer streamBuffer = new TranscriptStreamBuffer();
    private final AtomicBoolean streamUpdateQueued = new AtomicBoolean();
    private long displayedSequence;
    private String search = "";
    private final CopyOnWriteArrayList<AutoCloseable> subscriptions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final AtomicBoolean refreshRequested = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final InteractionSubmissionTracker interactionSubmissions = new InteractionSubmissionTracker();
    private final ExecutorService attachmentUploads = Executors.newFixedThreadPool(
            2, Thread.ofVirtual().name("javaclaw-desktop-upload-", 0).factory());
    private volatile List<ThreadInfo> allThreads = List.of();
    private volatile WorkspaceInfo selectedWorkspace;
    private volatile ThreadInfo selectedThread;

    /** 使用非空 SDK 客户端和 FX Dispatcher 创建 UI 状态，并登记连接、通知和恢复监听；close 只解除监听，不关闭外部持有的客户端。 */
    public DesktopViewModel(JavaClawClient client, FxDispatcher fx) {
        this(client, fx, false, new JavaFxDesktopDialogGateway());
    }

    DesktopViewModel(JavaClawClient client, FxDispatcher fx, boolean connectionInitialized) {
        this(client, fx, connectionInitialized, new JavaFxDesktopDialogGateway());
    }

    DesktopViewModel(
            JavaClawClient client, FxDispatcher fx, boolean connectionInitialized, DesktopDialogGateway dialogs) {
        this.client = Objects.requireNonNull(client, "client");
        this.fx = Objects.requireNonNull(fx, "fx");
        this.connectionInitialized = connectionInitialized;
        this.management = new ManagementViewModel(
                client,
                fx,
                () -> selectedWorkspace,
                this::selectThread,
                this::reloadProfiles,
                Objects.requireNonNull(dialogs, "dialogs"));
        subscriptions.add(client.onConnectionState(this::connectionChanged));
        subscriptions.add(client.onNotification(this::notification));
        subscriptions.add(client.onRecoveredThread(ignored -> scheduleThreadRefresh()));
    }

    /** 异步加载初始列表；仅对未由启动组件完成握手的 SDK 发送 initialize，避免同一连接重复初始化。 */
    public void initialize() {
        background("initialize", () -> {
            if (!connectionInitialized) {
                client.initialize("javaclaw-desktop", "4.0.0-SNAPSHOT").join();
            }
            List<WorkspaceInfo> loadedWorkspaces = client.workspaces().list().join();
            List<ThreadInfo> loadedThreads = client.threads().list(true).join();
            List<ProfileInfo> loadedProfiles = client.models().listProfiles().join();
            allThreads = loadedThreads;
            fx.execute(() -> {
                workspaces.setAll(loadedWorkspaces);
                profiles.setAll(loadedProfiles.stream()
                        .filter(value -> java.util.Set.of("CHAT", "PLAN").contains(value.kind()))
                        .toList());
                if (selectedWorkspace == null && !loadedWorkspaces.isEmpty()) {
                    selectedWorkspace = loadedWorkspaces.getFirst();
                }
                filterThreads();
                connection.set("已连接");
            });
        });
    }

    /** 返回供 UI 绑定的 Workspace 列表；视图不应直接修改，读取与监听应在 JavaFX 线程进行。 */
    public ObservableList<WorkspaceInfo> workspaces() {
        return workspaces;
    }

    /** 返回按当前 Workspace 过滤的 Thread 列表；视图仅绑定，由 ViewModel 在 JavaFX 线程更新。 */
    public ObservableList<ThreadInfo> threads() {
        return threads;
    }

    /** 返回服务端 Profile 列表供 UI 选择；视图仅绑定，不在本地修改服务端配置。 */
    public ObservableList<ProfileInfo> profiles() {
        return profiles;
    }

    /** 返回当前 Thread 的持久记录投影；列表由 ViewModel 更新，实时 delta 另见 streamProperty。 */
    ObservableList<TranscriptBlock> transcript() {
        return transcript;
    }

    /** 返回指定 Turn 的安全执行摘要；旧服务端或尚未加载时为 null。 */
    com.javaclaw.sdk.model.TurnExecutionSummaryInfo executionSummary(String turnId) {
        return executionSummaries.get(turnId);
    }

    /** 返回可监听的执行摘要投影；视图只读使用。 */
    javafx.collections.ObservableMap<String, com.javaclaw.sdk.model.TurnExecutionSummaryInfo> executionSummaries() {
        return executionSummaries;
    }

    /** 返回只读连接状态属性；连接、重连和失败状态经 FX Dispatcher 更新。 */
    public ReadOnlyStringProperty connectionProperty() {
        return connection;
    }

    /** 返回最近后台操作错误的只读展示属性；新操作启动时清空。 */
    public ReadOnlyStringProperty errorProperty() {
        return error;
    }

    /** 返回当前 Thread 的即时文本流属性；delta 用于展示，持久记录以服务端快照为准。 */
    public ReadOnlyStringProperty streamProperty() {
        return stream;
    }

    /** 返回由后台任务计数计算的忙碌展示标志；重复提交另由独立原子状态保护。 */
    public ReadOnlyBooleanProperty busyProperty() {
        return busy;
    }

    /** 返回当前会话选择，控制器通过该属性同步新建及刷新后的列表选择。 */
    public ReadOnlyObjectProperty<ThreadInfo> selectedThreadProperty() {
        return selection;
    }

    /** 返回输入区与当前 Turn 的独立活动状态；页面刷新等后台任务不会禁用输入区。 */
    ReadOnlyObjectProperty<ComposerActivity> composerActivityProperty() {
        return composerActivity;
    }

    /** 返回会话列表当前的归档范围，供筛选控件同步。 */
    ReadOnlyObjectProperty<ThreadScope> threadScopeProperty() {
        return threadScope;
    }

    /** 返回当前会话由持久快照和事件生成的安全进度摘要；不包含隐藏推理或原始 JSON。 */
    ReadOnlyObjectProperty<DesktopProgressSnapshot> progressProperty() {
        return progress;
    }

    /** 返回服务端保存的主题标识，未知主题由视图回退为翡翠。 */
    public ReadOnlyStringProperty themeProperty() {
        return theme;
    }

    /** 异步读取外观偏好；不触及旧版配置文件。 */
    public void loadAppearance() {
        background("读取主题", () -> {
            String value = client.administration().readDesktopTheme().join();
            fx.execute(() -> theme.set(value));
        });
    }

    /** 读取当前工作区内的提示词构成，ready 在 FX 线程执行；预览不产生模型调用。 */
    public void previewPrompt(
            ProfileInfo profile, java.util.function.Consumer<com.javaclaw.sdk.model.PromptPreview> ready) {
        WorkspaceInfo workspace = selectedWorkspace;
        if (workspace == null) {
            fail("请先选择工作区。");
            return;
        }
        background("预览提示词", () -> {
            var preview =
                    client.models().previewPrompt(profile.id(), workspace.id()).join();
            fx.execute(() -> ready.accept(preview));
        });
    }

    /** 创建独立草稿 Thread 并等待受预算约束的优化 Turn；不自动保存 Profile，不共享当前对话的私有上下文。 */
    public void optimizePrompt(
            ProfileInfo profile,
            String draft,
            java.util.function.Consumer<com.javaclaw.sdk.model.PromptDraftItemContent> ready,
            Runnable finished) {
        WorkspaceInfo workspace = selectedWorkspace;
        if (workspace == null) {
            fail("请先选择工作区。");
            finished.run();
            return;
        }
        background("优化提示词", () -> {
            try {
                var thread = client.threads()
                        .start(workspace.id(), "提示词草稿 · " + profile.name(), key("prompt-thread"))
                        .join();
                var turn = client.models()
                        .optimizePrompt(thread.id(), profile.id(), draft, profile.revision(), key("prompt-optimize"))
                        .join();
                reloadThreads();
                var snapshot = client.threads()
                        .awaitTurn(thread.id(), turn.id(), java.time.Duration.ofMinutes(6))
                        .join();
                var result = snapshot.items().stream()
                        .filter(item -> turn.id().equals(item.turnId()))
                        .map(ItemInfo::content)
                        .filter(com.javaclaw.sdk.model.PromptDraftItemContent.class::isInstance)
                        .map(com.javaclaw.sdk.model.PromptDraftItemContent.class::cast)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("草稿未生成成功，请查看对应会话的执行记录。原 Profile 未改变。"));
                fx.execute(() -> ready.accept(result));
            } finally {
                fx.execute(finished);
            }
        });
    }

    /** 用户显式选择主题后保存到 App Server；失败时保留原主题并显示错误。 */
    public void setTheme(String id) {
        background("保存主题", () -> {
            client.administration().setDesktopTheme(id).join();
            fx.execute(() -> theme.set(id));
        });
    }

    /** 在当前工作区内按标题搜索；仅改变本地列表投影，不隐式加载其他工作区内容。 */
    public void searchThreads(String query) {
        search = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        filterThreads();
    }

    /** 切换活跃、已归档或全部会话范围；只改变本地列表投影。 */
    void setThreadScope(ThreadScope scope) {
        threadScope.set(scope == null ? ThreadScope.ACTIVE : scope);
        filterThreads();
    }

    /** 用户确认编辑后按原 revision 保存 Profile；发生冲突时服务端拒绝覆盖新版本。 */
    public void saveProfile(ProfileInfo profile) {
        background("保存 Agent", () -> {
            client.models()
                    .putProfile(profile, profile.revision(), key("profile"))
                    .join();
            List<ProfileInfo> values = client.models().listProfiles().join();
            fx.execute(() -> profiles.setAll(values));
        });
    }

    /** 返回当前选中的 Workspace；尚未选择时为 null。 */
    public WorkspaceInfo selectedWorkspace() {
        return selectedWorkspace;
    }

    /** 返回当前选中的 Thread；尚未选择或切换 Workspace 后为 null。 */
    public ThreadInfo selectedThread() {
        return selectedThread;
    }

    /** 在 JavaFX 线程切换 Workspace（可为 null），重新过滤 Thread 并清空选择与 transcript；不删除服务端记录。 */
    public void selectWorkspace(WorkspaceInfo workspace) {
        if (Objects.equals(selectedWorkspace, workspace)) {
            return;
        }
        selectedWorkspace = workspace;
        selectThread(null);
        filterThreads();
    }

    /** 在 JavaFX 线程切换 Thread 并清空即时流；非空选择异步恢复订阅和读取快照，null 清空 transcript。 */
    public void selectThread(ThreadInfo thread) {
        if (thread != null && selectedThread != null && thread.id().equals(selectedThread.id())) {
            selectedThread = thread;
            selection.set(thread);
            return;
        }
        if (Objects.equals(selectedThread, thread)) {
            return;
        }
        selectionRevision.incrementAndGet();
        selectedThread = thread;
        selection.set(thread);
        displayedSequence = 0;
        streamBuffer.select(thread == null ? null : thread.id());
        stream.set("");
        executionSummaries.clear();
        composerActivity.set(submissionActivity(selectedWorkspace, thread));
        if (thread == null) {
            transcript.clear();
            progress.set(DesktopProgressSnapshot.empty());
            return;
        }
        background("read thread", () -> {
            client.threads().resume(thread.id(), 0).join();
            showSnapshot(client.threads().read(thread.id()).join(), readProgressEvents(thread.id()));
            readExecutionSummary(thread);
        });
    }

    private void readExecutionSummary(ThreadInfo thread) {
        try {
            var summary = client.threads().executionSummary(thread.id()).join();
            fx.execute(() -> {
                if (selectedThread != null && selectedThread.id().equals(thread.id())) {
                    executionSummaries.clear();
                    executionSummaries.putAll(summary.turns().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    com.javaclaw.sdk.model.TurnExecutionSummaryInfo::turnId,
                                    java.util.function.Function.identity(),
                                    (first, ignored) -> first,
                                    LinkedHashMap::new)));
                }
            });
        } catch (RuntimeException unavailable) {
            // 向后兼容旧服务端：消息仍可展示，只是不臆测 Profile、Provider、模型和 Token。
        }
    }

    /** 异步创建并选中 Workspace；名称空白或 root 为 null 时忽略，目录规范化和访问校验由服务端负责。 */
    public void createWorkspace(String name, Path root) {
        if (name == null || name.isBlank() || root == null) {
            return;
        }
        background("create workspace", () -> {
            WorkspaceInfo created =
                    client.workspaces().create(name, root, key("workspace")).join();
            List<WorkspaceInfo> loaded = client.workspaces().list().join();
            fx.execute(() -> {
                workspaces.setAll(loaded);
                selectedWorkspace = created;
                filterThreads();
            });
        });
    }

    /** 按当前 revision 重命名工作区登记；根目录和项目文件不改变。 */
    void renameWorkspace(WorkspaceInfo workspace, String name) {
        if (workspace == null || name == null || name.isBlank()) {
            return;
        }
        background("重命名工作区", () -> {
            WorkspaceInfo updated = client.workspaces()
                    .update(workspace.id(), name.strip(), workspace.revision(), key("workspace-rename"))
                    .join();
            List<WorkspaceInfo> loaded = client.workspaces().list().join();
            fx.execute(() -> {
                workspaces.setAll(loaded);
                if (selectedWorkspace != null && selectedWorkspace.id().equals(updated.id())) {
                    selectedWorkspace = updated;
                }
            });
        });
    }

    /** 删除工作区登记但不删除项目文件；存在关联 Thread 时由服务端拒绝并保留当前选择。 */
    void deleteWorkspace(WorkspaceInfo workspace) {
        if (workspace == null) {
            return;
        }
        background("删除工作区登记", () -> {
            client.workspaces()
                    .delete(workspace.id(), workspace.revision(), key("workspace-delete"))
                    .join();
            List<WorkspaceInfo> loaded = client.workspaces().list().join();
            fx.execute(() -> {
                workspaces.setAll(loaded);
                if (selectedWorkspace != null && selectedWorkspace.id().equals(workspace.id())) {
                    selectedWorkspace = loaded.isEmpty() ? null : loaded.getFirst();
                    selectThread(null);
                    filterThreads();
                }
            });
        });
    }

    /** 在已选 Workspace 下异步创建并选中 Thread；空标题使用默认名称，未选择 Workspace 时显示错误。 */
    public void createThread(String title) {
        WorkspaceInfo workspace = selectedWorkspace;
        if (workspace == null) {
            fail("请先选择或创建工作区。");
            return;
        }
        background("create thread", () -> {
            ThreadInfo created = client.threads()
                    .start(workspace.id(), title == null || title.isBlank() ? "新对话" : title.strip(), key("thread"))
                    .join();
            reloadThreads();
            fx.execute(() -> selectThread(created));
        });
    }

    /** 按当前 revision 重命名 Thread；服务端冲突时保留列表中的较新标题。 */
    void renameThread(ThreadInfo thread, String title) {
        if (thread == null || title == null || title.isBlank()) {
            return;
        }
        background("重命名对话", () -> {
            ThreadInfo updated = client.threads()
                    .update(thread.id(), title.strip(), thread.revision(), key("thread-rename"))
                    .join();
            reloadThreads();
            fx.execute(() -> {
                if (selectedThread != null && selectedThread.id().equals(updated.id())) {
                    selectThread(updated);
                }
            });
        });
    }

    /** 从完整 Thread 末尾创建独立分支并选中；源 Thread 和历史 Item 保持不变。 */
    void forkThread(ThreadInfo thread) {
        if (thread == null) {
            return;
        }
        background("创建对话分支", () -> {
            ThreadInfo forked = client.threads()
                    .fork(thread.id(), null, thread.title() + " · 分支", key("thread-fork"))
                    .join();
            reloadThreads();
            fx.execute(() -> selectThread(forked));
        });
    }

    /** 从目标 Turn 之前创建只读历史分支；源 Thread 不改变，后续输入仍需用户显式提交。 */
    void forkThreadAtTurn(ThreadInfo thread, String turnId) {
        if (thread == null || turnId == null || turnId.isBlank()) {
            return;
        }
        background("从消息创建分支", () -> {
            ThreadInfo forked = client.threads()
                    .fork(thread.id(), turnId, thread.title() + " · 消息分支", key("thread-message-fork"))
                    .join();
            reloadThreads();
            fx.execute(() -> selectThread(forked));
        });
    }

    /** 从目标 Turn 前建立分支并立即重试；replacementText 为 null 时复用原输入。 */
    void retryInNewBranch(String turnId, String replacementText, ProfileInfo profile) {
        ThreadInfo source = selectedThread;
        if (source == null || turnId == null || turnId.isBlank() || profile == null) {
            return;
        }
        background("在新分支重试", () -> {
            var result = client.threads()
                    .retryInNewBranch(source.id(), turnId, replacementText, profile.id(), key("thread-retry-branch"))
                    .join();
            reloadThreads();
            fx.execute(() -> selectThread(result.thread()));
        });
    }

    /** 归档或恢复没有活动 Turn 的 Thread；归档记录仍保留在当前工作区列表中。 */
    void setThreadArchived(ThreadInfo thread, boolean archived) {
        if (thread == null) {
            return;
        }
        background(archived ? "归档对话" : "恢复对话", () -> {
            ThreadInfo updated = (archived
                            ? client.threads().archive(thread.id(), thread.revision(), key("thread-archive"))
                            : client.threads().unarchive(thread.id(), thread.revision(), key("thread-unarchive")))
                    .join();
            reloadThreads();
            fx.execute(() -> {
                if (selectedThread != null && selectedThread.id().equals(updated.id())) {
                    selectedThread = updated;
                    selection.set(updated);
                }
            });
        });
    }

    /** 删除 Thread 持久记录并清除当前选择；项目文件和其他 Thread 不受影响。 */
    void deleteThread(ThreadInfo thread) {
        if (thread == null) {
            return;
        }
        background("删除对话", () -> {
            client.threads()
                    .delete(thread.id(), thread.revision(), key("thread-delete"))
                    .join();
            reloadThreads();
            fx.execute(() -> {
                if (selectedThread != null && selectedThread.id().equals(thread.id())) {
                    selectThread(null);
                }
            });
        });
    }

    /** 批量归档或恢复 Thread，并逐项返回成功与失败；失败项不会被成功汇总掩盖。 */
    void batchSetArchived(List<ThreadInfo> selected, boolean archived, Consumer<BatchOperationResult> completed) {
        batchThreads(
                selected,
                archived ? "批量归档对话" : "批量恢复对话",
                thread -> {
                    if (archived) {
                        client.threads()
                                .archive(thread.id(), thread.revision(), key("thread-batch-archive"))
                                .join();
                    } else {
                        client.threads()
                                .unarchive(thread.id(), thread.revision(), key("thread-batch-unarchive"))
                                .join();
                    }
                },
                completed);
    }

    /** 批量删除 Thread 持久记录，并逐项返回结果；项目文件始终不在删除范围内。 */
    void batchDeleteThreads(List<ThreadInfo> selected, Consumer<BatchOperationResult> completed) {
        batchThreads(
                selected,
                "批量删除对话",
                thread -> client.threads()
                        .delete(thread.id(), thread.revision(), key("thread-batch-delete"))
                        .join(),
                completed);
    }

    private void batchThreads(
            List<ThreadInfo> selected,
            String operation,
            Consumer<ThreadInfo> action,
            Consumer<BatchOperationResult> completed) {
        List<ThreadInfo> targets = selected == null ? List.of() : List.copyOf(selected);
        if (targets.isEmpty()) {
            completed.accept(BatchOperationResult.empty());
            return;
        }
        background(operation, () -> {
            ArrayList<ThreadInfo> succeeded = new ArrayList<>();
            LinkedHashMap<ThreadInfo, String> failed = new LinkedHashMap<>();
            for (ThreadInfo target : targets) {
                try {
                    action.accept(target);
                    succeeded.add(target);
                } catch (RuntimeException failure) {
                    failed.put(target, message(failure));
                }
            }
            reloadThreads();
            BatchOperationResult result = new BatchOperationResult(succeeded, failed);
            fx.execute(() -> {
                completed.accept(result);
                if (!failed.isEmpty()) {
                    error.set("有 " + failed.size() + " 个对话操作失败；失败项已保留选择。");
                }
                if (selectedThread != null
                        && succeeded.stream().anyMatch(value -> value.id().equals(selectedThread.id()))) {
                    selectThread(null);
                }
            });
        });
    }

    /** 向当前 Thread 异步提交非空文本；profile 为 null 时使用 profile_chat，权限取自服务端 Profile。 */
    public void sendText(String text, ProfileInfo profile) {
        submitMessage(text, List.of(), profile, ignored -> {});
    }

    /** 先经 SDK 上传本地 file，再提交附件摘要及可选文本；不把本地文件路径作为 Turn 输入。 */
    public void sendAttachment(Path file, String text, ProfileInfo profile) {
        submitMessage(text, file == null ? List.of() : List.of(file), profile, ignored -> {});
    }

    /** 保留单附件调用方兼容性；新界面使用多附件重载。 */
    public void submitMessage(String text, Path file, ProfileInfo profile, Runnable accepted) {
        submitMessage(text, file == null ? List.of() : List.of(file), profile, ignored -> accepted.run());
    }

    /**
     * 提交文本及多个附件，必要时创建当前工作区的首个 Thread。
     *
     * <p>附件在本地预检后以最多两个并发上传。任一上传或启动失败都不会启动半成品 Turn，并尽力释放本次已上传但尚未被 Turn 引用的附件。accepted 只在服务端接受 Turn 后于 FX
     * 线程调用，即使用户已切换会话也会调用，以便原 Thread 草稿按快照安全清除；迟到请求不会抢回焦点。
     */
    public void submitMessage(String text, List<Path> files, ProfileInfo profile, Consumer<ThreadInfo> accepted) {
        WorkspaceInfo workspace = selectedWorkspace;
        ThreadInfo current = selectedThread;
        long revision = selectionRevision.get();
        List<Path> attachments = normalizedAttachments(files);
        if ((text == null || text.isBlank()) && attachments.isEmpty()) {
            return;
        }
        if (workspace == null) {
            fail("请先选择或创建工作区。");
            return;
        }
        try {
            validateAttachments(attachments);
        } catch (RuntimeException invalid) {
            fail(message(invalid));
            return;
        }
        SubmissionKey submissionKey = SubmissionKey.of(workspace, current);
        SubmissionContext submission = new SubmissionContext();
        if (activeSubmissions.putIfAbsent(submissionKey, submission) != null) {
            fail("当前会话已有消息正在上传或提交；草稿已保留。");
            return;
        }
        fx.execute(() -> {
            if (revision == selectionRevision.get()) {
                composerActivity.set(ComposerActivity.submitting());
            }
        });
        background("发送消息", () -> {
            boolean referenced = false;
            ThreadInfo createdThread = null;
            try {
                List<UploadedAttachment> uploaded = uploadAttachments(attachments, submission);
                submission.ensureActive();
                ThreadInfo target = current;
                if (target == null) {
                    String title = text == null || text.isBlank() ? "附件对话" : text.strip();
                    title = title.substring(0, Math.min(48, title.length()));
                    target = client.threads()
                            .start(workspace.id(), title, key("thread"))
                            .join();
                    createdThread = target;
                }
                // 创建首个 Thread 可能跨越一次用户取消；进入 turn/start 前必须再次原子检查。
                submission.beginTurn();
                ArrayList<TurnInput> input = new ArrayList<>();
                if (text != null && !text.isBlank()) {
                    input.add(new TurnInput.Text(text));
                }
                for (UploadedAttachment value : uploaded) {
                    input.add(new TurnInput.Attachment(
                            value.info().sha256(),
                            value.info().mediaType(),
                            value.path().getFileName().toString()));
                }
                TurnInfo turn = startTurnNow(target, profile, input);
                referenced = true;
                ThreadInfo submitted = target;
                reloadThreads();
                fx.execute(() -> {
                    accepted.accept(submitted);
                    // 用户切换会话后，旧请求只在原会话完成，不能抢回焦点或清空新会话草稿。
                    if (revision == selectionRevision.get()) {
                        selectThread(submitted);
                        composerActivity.set(new ComposerActivity(ComposerActivity.Phase.ACTIVE, turn.id()));
                    }
                });
            } catch (RuntimeException failure) {
                if (!referenced) {
                    submission.releaseUnreferenced(client);
                }
                if (createdThread != null && !submission.turnStarting()) {
                    try {
                        client.threads()
                                .delete(createdThread.id(), createdThread.revision(), key("cancelled-empty-thread"))
                                .join();
                    } catch (RuntimeException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            } finally {
                activeSubmissions.remove(submissionKey, submission);
                fx.execute(() -> {
                    if (revision == selectionRevision.get()
                            && (composerActivity.get().phase() == ComposerActivity.Phase.SUBMITTING
                                    || composerActivity.get().phase()
                                            == ComposerActivity.Phase.CANCELLING_SUBMISSION)) {
                        composerActivity.set(ComposerActivity.idle());
                    }
                });
            }
        });
    }

    private TurnInfo startTurnNow(ThreadInfo thread, ProfileInfo profile, List<TurnInput> input) {
        TurnInfo turn = client.threads()
                .startTurn(new TurnStartRequest(
                        thread.id(),
                        profile == null ? "profile_chat" : profile.id(),
                        input,
                        TurnStartRequest.ApprovalMode.PROFILE_DEFAULT,
                        TurnStartRequest.ReasoningMode.PROFILE_DEFAULT,
                        key("turn")))
                .join();
        scheduleThreadRefresh();
        return turn;
    }

    /** 取消尚未被 Turn 引用的附件上传；完成中的 SDK 请求结束后仍会统一释放成功结果。 */
    void cancelSubmission() {
        SubmissionContext current = activeSubmissions.get(SubmissionKey.of(selectedWorkspace, selectedThread));
        if (current == null || composerActivity.get().phase() != ComposerActivity.Phase.SUBMITTING) {
            return;
        }
        if (current.cancel()) {
            composerActivity.set(ComposerActivity.cancellingSubmission());
        }
    }

    /** 向当前活动 Turn 追加非空文本；服务端拒绝时保留草稿并恢复活动状态。 */
    void steer(String text, Consumer<Boolean> accepted) {
        ThreadInfo thread = selectedThread;
        ComposerActivity state = composerActivity.get();
        long revision = selectionRevision.get();
        if (thread == null || text == null || text.isBlank() || !state.acceptsSteering()) {
            return;
        }
        if (!steeringTurns.add(state.turnId())) {
            return;
        }
        composerActivity.set(new ComposerActivity(ComposerActivity.Phase.STEERING, state.turnId()));
        background("追加指令", () -> {
            boolean result = false;
            try {
                result = client.threads().steer(state.turnId(), text).join();
                if (!result) {
                    throw new IllegalStateException("活动 Turn 已不再接受追加指令，请刷新后重试。");
                }
                boolean received = result;
                fx.execute(() -> accepted.accept(received));
                scheduleThreadRefresh();
            } finally {
                steeringTurns.remove(state.turnId());
                fx.execute(() -> {
                    if (revision == selectionRevision.get()
                            && composerActivity.get().phase() == ComposerActivity.Phase.STEERING
                            && composerActivity.get().turnId().equals(state.turnId())) {
                        composerActivity.set(state);
                    }
                });
            }
        });
    }

    /** 异步读取当前 Thread 并中断其非终态 Turn；没有选中 Thread 时不执行操作。 */
    public void interrupt() {
        ThreadInfo thread = selectedThread;
        ComposerActivity state = composerActivity.get();
        long revision = selectionRevision.get();
        if (thread == null || !state.canInterrupt()) {
            return;
        }
        composerActivity.set(new ComposerActivity(ComposerActivity.Phase.INTERRUPTING, state.turnId()));
        background("停止任务", () -> {
            try {
                boolean interrupted = client.threads().interrupt(state.turnId()).join();
                if (!interrupted) {
                    scheduleThreadRefresh();
                    throw new IllegalStateException("任务已经结束或无法中断。");
                }
                scheduleThreadRefresh();
            } catch (RuntimeException failure) {
                fx.execute(() -> {
                    if (revision == selectionRevision.get()
                            && composerActivity.get().phase() == ComposerActivity.Phase.INTERRUPTING
                            && composerActivity.get().turnId().equals(state.turnId())) {
                        composerActivity.set(state);
                    }
                });
                throw failure;
            }
        });
    }

    private void notification(ClientNotification notification) {
        ThreadInfo current = selectedThread;
        if (current == null || !current.id().equals(notificationThread(notification))) {
            return;
        }
        if (notification instanceof ItemDeltaNotification delta) {
            if (streamBuffer.append(delta) && streamUpdateQueued.compareAndSet(false, true)) {
                // 合并多次 delta，只保留一个待执行的 FX 更新，慢 UI 不形成按 token 增长的任务队列。
                fx.execute(() -> {
                    streamUpdateQueued.set(false);
                    stream.set(streamBuffer.text());
                });
            }
            return;
        }
        // 通知只负责唤醒快照刷新。持久 Approval/UserInput Item 才是重连、去重和恢复后的权威展示来源。
        scheduleThreadRefresh();
    }

    /** 提交 transcript 内联审批；同一 approvalId 在响应完成前只允许一个在途请求。 */
    void respondToApproval(String approvalId, boolean approved, Consumer<Boolean> completed) {
        if (!interactionSubmissions.begin(approvalId)) {
            return;
        }
        background("提交审批", () -> {
            try {
                boolean accepted =
                        client.threads().respondToApproval(approvalId, approved).join();
                interactionSubmissions.completed(approvalId, accepted);
                fx.execute(() -> completed.accept(accepted));
                if (!accepted) {
                    fail("该审批已处理或不再有效；已刷新服务端状态。");
                }
                scheduleThreadRefresh();
            } catch (RuntimeException failure) {
                interactionSubmissions.failed(approvalId);
                fx.execute(() -> completed.accept(false));
                throw failure;
            }
        });
    }

    /** 提交 transcript 内联用户输入；取消与空回答保持不同语义，并按 requestId 防止重复副作用。 */
    void respondToUserInput(String requestId, String value, boolean cancelled, Consumer<Boolean> completed) {
        if (!interactionSubmissions.begin(requestId)) {
            return;
        }
        background("提交回答", () -> {
            try {
                boolean accepted = client.threads()
                        .respondToUserInput(requestId, Objects.toString(value, ""), cancelled)
                        .join();
                interactionSubmissions.completed(requestId, accepted);
                fx.execute(() -> completed.accept(accepted));
                if (!accepted) {
                    fail("该问题已回答或不再有效；已刷新服务端状态。");
                }
                scheduleThreadRefresh();
            } catch (RuntimeException failure) {
                interactionSubmissions.failed(requestId);
                fx.execute(() -> completed.accept(false));
                throw failure;
            }
        });
    }

    /** 返回指定持久交互是否正在提交；用于 ListCell 复用时恢复正确禁用状态。 */
    boolean interactionSubmitting(String id) {
        return interactionSubmissions.blocked(id);
    }

    private static String notificationThread(ClientNotification value) {
        if (value instanceof ItemDeltaNotification delta) {
            return delta.threadId();
        }
        if (value instanceof EventNotification event) {
            return event.event().threadId();
        }
        if (value instanceof ApprovalRequestedNotification approval) {
            return approval.threadId();
        }
        if (value instanceof UserInputRequestedNotification input) {
            return input.threadId();
        }
        if (value instanceof ResyncRequiredNotification resync) {
            return resync.threadId();
        }
        return "";
    }

    private void scheduleThreadRefresh() {
        // 多条持久事件合并成一次快照刷新；delta 仍走即时展示，避免每个 token 都触发 SDK 查询。
        refreshRequested.set(true);
        if (!refreshQueued.compareAndSet(false, true)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                do {
                    refreshRequested.set(false);
                    Thread.sleep(40);
                    ThreadInfo thread = selectedThread;
                    if (thread != null) {
                        showSnapshot(client.threads().read(thread.id()).join(), readProgressEvents(thread.id()));
                    }
                    reloadThreads();
                } while (refreshRequested.get());
            } catch (Throwable failure) {
                fail(message(failure));
            } finally {
                refreshQueued.set(false);
                // 事件可能恰好落在最后一次循环检查和释放 queued 之间；不能静默漏掉这次恢复。
                if (refreshRequested.get() && !closed.get()) {
                    scheduleThreadRefresh();
                }
            }
        });
    }

    private void reloadThreads() {
        allThreads = client.threads().list(true).join();
        fx.execute(this::filterThreads);
    }

    private void filterThreads() {
        WorkspaceInfo workspace = selectedWorkspace;
        threads.setAll(
                workspace == null
                        ? List.of()
                        : allThreads.stream()
                                .filter(value -> workspace.id().equals(value.workspaceId()))
                                .filter(value -> switch (threadScope.get()) {
                                    case ACTIVE -> !archived(value);
                                    case ARCHIVED -> archived(value);
                                    case ALL -> true;
                                })
                                .filter(value -> value.title()
                                        .toLowerCase(java.util.Locale.ROOT)
                                        .contains(search))
                                .sorted(Comparator.comparing(
                                                ThreadInfo::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                                        .reversed())
                                .toList());
    }

    private static boolean archived(ThreadInfo thread) {
        return "ARCHIVED".equalsIgnoreCase(thread.status());
    }

    private void showSnapshot(ThreadSnapshot snapshot, List<EventInfo> events) {
        InteractionStateProjection interactionState = InteractionStateProjection.project(snapshot, events);
        List<TranscriptBlock> entries = TranscriptProjector.project(snapshot.items(), interactionState);
        DesktopProgressSnapshot projected = DesktopProgressProjector.project(snapshot, events);
        ComposerActivity projectedActivity = activity(snapshot);
        interactionSubmissions.reconcile(interactionState.resolvedIds());
        fx.execute(() -> {
            if (selectedThread == null
                    || !selectedThread.id().equals(snapshot.thread().id())
                    || snapshot.thread().lastSequence() < displayedSequence) {
                return;
            }
            transcript.setAll(entries);
            progress.set(projected);
            composerActivity.set(mergeActivity(composerActivity.get(), projectedActivity));
            displayedSequence = snapshot.thread().lastSequence();
            snapshot.items().stream()
                    .filter(item -> !"STARTED".equals(item.state()))
                    .forEach(item -> streamBuffer.completed(item.id()));
            stream.set(streamBuffer.text());
        });
    }

    private static ComposerActivity activity(ThreadSnapshot snapshot) {
        for (int index = snapshot.turns().size() - 1; index >= 0; index--) {
            TurnInfo turn = snapshot.turns().get(index);
            if (isTerminal(turn.status())) {
                continue;
            }
            ComposerActivity.Phase phase =
                    switch (turn.status()) {
                        case "WAITING_FOR_APPROVAL", "WAITING_FOR_INPUT", "WAITING", "AWAITING_APPROVAL" ->
                            ComposerActivity.Phase.WAITING_INTERACTION;
                        default -> ComposerActivity.Phase.ACTIVE;
                    };
            return new ComposerActivity(phase, turn.id());
        }
        return ComposerActivity.idle();
    }

    private static ComposerActivity mergeActivity(ComposerActivity current, ComposerActivity projected) {
        if (current.phase() == ComposerActivity.Phase.SUBMITTING
                || current.phase() == ComposerActivity.Phase.CANCELLING_SUBMISSION) {
            return current;
        }
        if ((current.phase() == ComposerActivity.Phase.STEERING
                        || current.phase() == ComposerActivity.Phase.INTERRUPTING)
                && projected.hasActiveTurn()
                && current.turnId().equals(projected.turnId())) {
            return current;
        }
        return projected;
    }

    private List<EventInfo> readProgressEvents(String threadId) {
        try {
            return client.threads().events(threadId, 0, 10_000).join();
        } catch (RuntimeException unavailable) {
            // 旧服务端或暂时断线时快照仍可展示；Token 明确显示未知，不把不可用误报为 0。
            return List.of();
        }
    }

    private void connectionChanged(ConnectionStatus status) {
        fx.execute(() -> connection.set(
                switch (status.state()) {
                    case CONNECTED -> "已连接";
                    case RECONNECTING -> "正在重连（第 " + status.reconnectAttempt() + " 次）";
                    case RESYNC_REQUIRED -> "正在恢复会话记录…";
                    case CLOSED -> "连接已断开";
                }));
    }

    private void background(String operation, Runnable action) {
        if (closed.get()) {
            return;
        }
        backgroundCount.incrementAndGet();
        fx.execute(() -> {
            busy.set(backgroundCount.get() > 0);
            error.set("");
        });
        // 等待 SDK future 只发生在虚拟线程；所有可观察 JavaFX 属性写入仍统一经过 Dispatcher。
        Thread.startVirtualThread(() -> {
            try {
                action.run();
            } catch (Throwable failure) {
                fail(operation + ": " + message(failure));
            } finally {
                backgroundCount.decrementAndGet();
                fx.execute(() -> busy.set(backgroundCount.get() > 0));
            }
        });
    }

    private void fail(String value) {
        fx.execute(() -> error.set(value));
    }

    private static boolean isTerminal(String status) {
        return status != null
                && List.of("COMPLETED", "SUCCEEDED", "FAILED", "INTERRUPTED", "CANCELLED")
                        .contains(status.toUpperCase(java.util.Locale.ROOT));
    }

    private List<UploadedAttachment> uploadAttachments(List<Path> paths, SubmissionContext submission) {
        List<CompletableFuture<UploadedAttachment>> uploads = paths.stream()
                .map(path -> CompletableFuture.supplyAsync(
                        () -> {
                            submission.ensureActive();
                            AttachmentInfo info = client.attachments()
                                    .upload(path, mediaType(path), key("upload"))
                                    .join();
                            UploadedAttachment uploaded = new UploadedAttachment(path, info);
                            submission.uploaded(uploaded);
                            submission.ensureActive();
                            return uploaded;
                        },
                        attachmentUploads))
                .toList();
        List<UploadAttempt> attempts = uploads.stream()
                .map(upload -> upload.handle(UploadAttempt::new).join())
                .toList();
        RuntimeException failed = attempts.stream()
                .filter(value -> value.failure() != null)
                .map(value -> new IllegalStateException("附件上传失败：" + message(value.failure()), value.failure()))
                .findFirst()
                .orElse(null);
        if (failed != null) {
            throw failed;
        }
        submission.ensureActive();
        return attempts.stream().map(UploadAttempt::uploaded).toList();
    }

    private static List<Path> normalizedAttachments(List<Path> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<Path, Path> unique = new LinkedHashMap<>();
        files.stream()
                .filter(Objects::nonNull)
                .map(value -> value.toAbsolutePath().normalize())
                .forEach(value -> unique.putIfAbsent(value, value));
        return List.copyOf(unique.values());
    }

    private ComposerActivity submissionActivity(WorkspaceInfo workspace, ThreadInfo thread) {
        SubmissionContext submission = activeSubmissions.get(SubmissionKey.of(workspace, thread));
        if (submission == null) {
            return ComposerActivity.idle();
        }
        return submission.cancelled() ? ComposerActivity.cancellingSubmission() : ComposerActivity.submitting();
    }

    private static void validateAttachments(List<Path> files) {
        for (Path file : files) {
            try {
                if (!Files.isRegularFile(file)) {
                    throw new IllegalArgumentException("附件不是可读取的普通文件：" + file.getFileName());
                }
                long bytes = Files.size(file);
                if (bytes > AttachmentClient.MAX_BYTES) {
                    throw new IllegalArgumentException("附件超过 256 MiB 协议上限：" + file.getFileName());
                }
            } catch (java.io.IOException failure) {
                throw new IllegalArgumentException("无法读取附件：" + file.getFileName(), failure);
            }
        }
    }

    private static String mediaType(Path path) {
        try {
            String value = Files.probeContentType(path);
            return value == null ? "application/octet-stream" : value;
        } catch (java.io.IOException ignored) {
            return "application/octet-stream";
        }
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String message(Throwable failure) {
        Throwable value = failure;
        while (value.getCause() != null
                && (value instanceof java.util.concurrent.CompletionException
                        || value instanceof java.util.concurrent.ExecutionException)) {
            value = value.getCause();
        }
        return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        subscriptions.forEach(value -> {
            try {
                value.close();
            } catch (Exception ignored) {
            }
        });
        subscriptions.clear();
        activeSubmissions.values().forEach(SubmissionContext::cancel);
        activeSubmissions.clear();
        attachmentUploads.shutdownNow();
        management.close();
    }

    ManagementViewModel management() {
        return management;
    }

    void readPlan(String itemId, java.util.function.Consumer<com.javaclaw.sdk.model.PlanItemContent> receive) {
        readItem(itemId, item -> {
            if ("COMPLETED".equals(item.state())
                    && item.content() instanceof com.javaclaw.sdk.model.PlanItemContent plan) {
                receive.accept(plan);
            } else {
                error.set("计划尚未完成，不能采用。");
            }
        });
    }

    void readItem(String itemId, java.util.function.Consumer<ItemInfo> receive) {
        ThreadInfo thread = selectedThread;
        if (thread == null) {
            return;
        }
        background("读取执行记录", () -> {
            var snapshot = client.threads().read(thread.id()).join();
            var content = snapshot.items().stream()
                    .filter(item -> item.id().equals(itemId))
                    .findFirst()
                    .orElseThrow();
            fx.execute(() -> {
                if (selectedThread != null && selectedThread.id().equals(thread.id())) {
                    receive.accept(content);
                }
            });
        });
    }

    void readItems(List<String> itemIds, java.util.function.Consumer<String> receive) {
        ThreadInfo thread = selectedThread;
        if (thread == null || itemIds == null || itemIds.isEmpty()) {
            return;
        }
        background("读取执行过程", () -> {
            var requested = new java.util.LinkedHashSet<>(itemIds);
            String content = client.threads().read(thread.id()).join().items().stream()
                    .filter(item -> requested.contains(item.id()))
                    .sorted(java.util.Comparator.comparingLong(ItemInfo::ordinal))
                    .map(ItemPresenter::text)
                    .distinct()
                    .collect(java.util.stream.Collectors.joining("\n\n——\n\n"));
            fx.execute(() -> {
                if (selectedThread != null && selectedThread.id().equals(thread.id())) {
                    receive.accept(content.isBlank() ? "没有可展示的执行详情。" : content);
                }
            });
        });
    }

    /** 读取同一 Turn 的类型化展示文本，供消息的“关联执行”查看器使用。 */
    void readTurnItems(String turnId, Consumer<String> receive) {
        ThreadInfo thread = selectedThread;
        if (thread == null || turnId == null || turnId.isBlank()) {
            return;
        }
        background("读取关联执行", () -> {
            String content = client.threads().read(thread.id()).join().items().stream()
                    .filter(item -> turnId.equals(item.turnId()))
                    .sorted(Comparator.comparingLong(ItemInfo::ordinal))
                    .map(ItemPresenter::text)
                    .distinct()
                    .collect(java.util.stream.Collectors.joining("\n\n——\n\n"));
            fx.execute(() -> {
                if (selectedThread != null && selectedThread.id().equals(thread.id())) {
                    receive.accept(content.isBlank() ? "该 Turn 没有可展示的执行详情。" : content);
                }
            });
        });
    }

    /** 将用户选择导出的消息正文写入目标文件；文件选择和覆盖确认由视图完成。 */
    void exportText(Path target, String text, Runnable completed) {
        if (target == null) {
            return;
        }
        background("导出消息", () -> {
            try {
                Files.writeString(target, Objects.toString(text, ""));
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("无法写入导出文件", failure);
            }
            fx.execute(completed);
        });
    }

    void adoptPlan(String itemId, ProfileInfo profile, String decisions) {
        ThreadInfo thread = selectedThread;
        if (thread == null || profile == null) {
            return;
        }
        background("采用并执行计划", () -> {
            client.threads()
                    .adoptPlan(thread.id(), itemId, profile.id(), profile.revision(), decisions, key("plan-adopt"))
                    .join();
            scheduleThreadRefresh();
        });
    }

    void reloadProfiles() {
        background("刷新 Profile", () -> {
            var values = client.models().listProfiles().join();
            fx.execute(() -> profiles.setAll(values.stream()
                    .filter(value -> java.util.Set.of("CHAT", "PLAN").contains(value.kind()))
                    .toList()));
        });
    }

    void compactThread() {
        ThreadInfo thread = selectedThread;
        if (thread == null) {
            return;
        }
        background("压缩会话上下文", () -> {
            client.threads().startCompaction(thread.id()).join();
            scheduleThreadRefresh();
        });
    }

    /** 会话列表归档范围。 */
    enum ThreadScope {
        ACTIVE("活跃"),
        ARCHIVED("已归档"),
        ALL("全部");

        private final String label;

        ThreadScope(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** 批量会话操作的逐项结果；成功和失败集合均为不可变快照。 */
    record BatchOperationResult(List<ThreadInfo> succeeded, Map<ThreadInfo, String> failed) {
        BatchOperationResult {
            succeeded = List.copyOf(succeeded == null ? List.of() : succeeded);
            failed = Map.copyOf(failed == null ? Map.of() : failed);
        }

        static BatchOperationResult empty() {
            return new BatchOperationResult(List.of(), Map.of());
        }
    }

    private record UploadedAttachment(Path path, AttachmentInfo info) {}

    private record UploadAttempt(UploadedAttachment uploaded, Throwable failure) {}

    private record SubmissionKey(String workspaceId, String threadId) {
        private SubmissionKey {
            workspaceId = Objects.toString(workspaceId, "");
            threadId = Objects.toString(threadId, "");
        }

        private static SubmissionKey of(WorkspaceInfo workspace, ThreadInfo thread) {
            return new SubmissionKey(workspace == null ? "" : workspace.id(), thread == null ? "" : thread.id());
        }
    }

    private static final class SubmissionContext {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();
        private final CopyOnWriteArrayList<UploadedAttachment> uploaded = new CopyOnWriteArrayList<>();
        private boolean turnStarting;

        private synchronized boolean cancel() {
            if (turnStarting) {
                return false;
            }
            cancelled.set(true);
            return true;
        }

        private void ensureActive() {
            if (cancelled.get()) {
                throw new java.util.concurrent.CancellationException("附件上传已取消");
            }
        }

        private boolean cancelled() {
            return cancelled.get();
        }

        private synchronized void beginTurn() {
            ensureActive();
            turnStarting = true;
        }

        private synchronized boolean turnStarting() {
            return turnStarting;
        }

        private void uploaded(UploadedAttachment value) {
            uploaded.add(value);
        }

        private void releaseUnreferenced(JavaClawClient client) {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            for (UploadedAttachment value : uploaded) {
                try {
                    client.attachments().release(value.info().sha256()).join();
                } catch (RuntimeException ignored) {
                    // 释放是尽力清理；主失败仍应原样报告，不能被清理错误覆盖。
                }
            }
        }
    }
}
