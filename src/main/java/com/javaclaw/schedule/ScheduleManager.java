package com.javaclaw.schedule;

import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.platform.execution.TaskScope;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 定时任务管理器（基于 Quartz）
 *
 * <p>持久化、调度、执行定时任务。用户任务持久化到全局 H2 数据库的
 * {@code scheduled_tasks} 表，并按 {@code workspace_id} 隔离；启动时只从 H2 读取。
 * 底层显式使用 Quartz 2.5.2，由 Quartz 接管 cron 解析与触发器机制；
 * 任务到点后调用隔离的 {@link ScheduledTaskRunner} 执行。</p>
 *
 * <p>三种触发模式（UI 语义保持不变，内部统一翻译为 Quartz Trigger）：
 * <ul>
 *   <li><b>interval</b> — 固定间隔分钟 → Quartz {@code SimpleTrigger}</li>
 *   <li><b>daily</b> — 每日 HH:mm → 合成为 Quartz Cron {@code "0 mm HH * * ?"}</li>
 *   <li><b>cron</b> — Quartz 标准 6 段表达式（秒 分 时 日 月 周，可选 7 段含年）</li>
 * </ul>
 *
 * <p>注意：5 段 simple-cron（分 时 日 月 周）不是 Quartz Cron；
 * 这类表达式会被启动时拒绝并跳过，需要用户重新填写为 6 段标准 Quartz Cron。</p>
 *
 * @author JavaClaw
 */
public class ScheduleManager {

    private static final Logger log = LoggerFactory.getLogger(ScheduleManager.class);

    /** 定时任务执行专用日志（独立写入 logs/task-YYYY-MM-DD.log） */
    private static final Logger taskLog = LoggerFactory.getLogger("com.javaclaw.schedule.TaskExecution");

    /** Quartz Job/Trigger group name（隔离 JavaClaw 任务与其它可能的 Quartz 使用方）*/
    private static final String JOB_GROUP = "javaclaw-scheduled-tasks";

    private final ScheduledTaskStore store;
    private final List<ScheduledTask> tasks;
    private final BuiltinScheduleRegistry builtins;
    private final ScheduleBackend quartz;
    private final ScheduleTriggerFactory triggerFactory;

    /** Schedule 入口适配器；每次触发均提交统一 RunRequest，不拥有独立 Agent Runtime。 */
    private volatile ScheduledTaskRunner scheduledRunner;

    /** 工作区每次重载递增；旧代队列任务在真正开始及回调落盘前均会被拦截。 */
    private final AtomicLong executionEpoch = new AtomicLong();

    /** 序列化本进程内的配置快照替换与执行结果合并。 */
    private final Object persistenceLock = new Object();

    /** 当前内存任务实际所属工作区；保存时不再临时读取可变的全局工作区。 */
    private volatile String loadedWorkspaceId;

    /** 工作区专属串行作用域：正文使用全局 I/O 虚拟线程，关闭时统一取消并等待退出。 */
    private final ScheduleTaskDispatcher taskDispatcher;

    /** 每个任务最多一个排队或运行实例。 */
    private final Map<String, ScheduledRunControl> activeRunsByTask = new ConcurrentHashMap<>();

    /** 正在执行中的任务 id 集合（实时运行状态：进入 executeTask 至本次完成期间为 true） */
    private final Set<String> runningTaskIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final ScheduleEventHub events = new ScheduleEventHub();
    private final ScheduleCompletionNotifier completionNotifier;

    /**
     * 创建一个工作区定时任务运行时。
     *
     * <p>实例归工作区 Spring Context 所有；关闭后不可复用。Quartz 的单平台工作线程只负责
     * 产生触发信号，任务正文始终转交给 {@code scheduledTasks} 的 I/O 虚拟线程。</p>
     */
    public ScheduleManager(ScheduledTaskStore store, String workspaceId, TaskScope scheduledTasks,
                           com.javaclaw.config.NotificationConfig notificationSettings,
                           com.javaclaw.config.EmailConfig emailSettings) {
        this.store = Objects.requireNonNull(store, "store");
        this.tasks = new CopyOnWriteArrayList<>();
        this.builtins = new BuiltinScheduleRegistry();
        this.triggerFactory = new ScheduleTriggerFactory(JOB_GROUP);
        this.quartz = QuartzScheduleBackendFactory.create(this);
        this.taskDispatcher = ScheduleTaskDispatcher.managed(scheduledTasks);
        this.completionNotifier = new ScheduleCompletionNotifier(notificationSettings, emailSettings);
        loadAll(workspaceId);
    }

    /** 测试构造：通知能力不参与执行模型测试。 */
    ScheduleManager(ScheduledTaskStore store, String workspaceId, TaskScope scheduledTasks) {
        this(store, workspaceId, scheduledTasks, null, null);
    }

    /** 测试构造：允许注入临时 H2、Quartz 与可控 runner，不触碰全局单例数据。 */
    ScheduleManager(ScheduledTaskStore store, String workspaceId, ScheduleBackend quartz,
                    ExecutorService scheduledExec, ScheduledTaskRunner runner) {
        this.store = Objects.requireNonNull(store, "store");
        this.tasks = new CopyOnWriteArrayList<>();
        this.builtins = new BuiltinScheduleRegistry();
        this.triggerFactory = new ScheduleTriggerFactory(JOB_GROUP);
        this.quartz = Objects.requireNonNull(quartz, "quartz");
        this.taskDispatcher = ScheduleTaskDispatcher.testing(scheduledExec);
        this.scheduledRunner = runner;
        this.completionNotifier = new ScheduleCompletionNotifier(null, null);
        loadAll(workspaceId);
    }

    /** 包内测试构造：验证生产 TaskScope/虚拟线程语义，同时用可控 Quartz 后端隔离时间。 */
    ScheduleManager(ScheduledTaskStore store, String workspaceId, ScheduleBackend quartz,
                    TaskScope scheduledTasks, ScheduledTaskRunner runner) {
        this.store = Objects.requireNonNull(store, "store");
        this.tasks = new CopyOnWriteArrayList<>();
        this.builtins = new BuiltinScheduleRegistry();
        this.triggerFactory = new ScheduleTriggerFactory(JOB_GROUP);
        this.quartz = Objects.requireNonNull(quartz, "quartz");
        this.taskDispatcher = ScheduleTaskDispatcher.managed(scheduledTasks);
        this.scheduledRunner = runner;
        this.completionNotifier = new ScheduleCompletionNotifier(null, null);
        loadAll(workspaceId);
    }

    /**
     * 重新加载定时任务（工作区切换时调用）
     */
    public void reload(ScheduledTaskRunner newScheduledAgent) {
        // 先使旧代队列/回调失效，再触碰 Quartz 与内存任务。
        executionEpoch.incrementAndGet();
        cancelAllRuns(CancellationReason.RUNTIME_REBUILD);
        try {
            quartz.clear(); // 删掉所有 job + trigger，但 scheduler 仍运行
        } catch (SchedulerException e) {
            log.warn("清空 Quartz 任务失败（继续）", e);
        }
        loadAll(requireWorkspace());

        ScheduledTaskRunner old = this.scheduledRunner;
        this.scheduledRunner = newScheduledAgent;
        if (old != null && old != newScheduledAgent) old.shutdown();
        taskLog.info("定时任务已重新加载，共 {} 个任务", tasks.size());
        scheduleAllEnabled();
    }

    /**
     * 运行时替换前停止接收新触发并关闭旧编排器。稍后 {@link #reload(ScheduledTaskRunner)}
     * 会用新运行时恢复调度；已入队的旧世代任务由 executionEpoch 自动失效。
     */
    public void suspendForRuntimeTransition() {
        executionEpoch.incrementAndGet();
        try {
            quartz.clear();
        } catch (SchedulerException e) {
            log.warn("运行时切换前清空 Quartz 任务失败（继续）", e);
        }
        cancelAllRuns(CancellationReason.RUNTIME_REBUILD);
        ScheduledTaskRunner old = this.scheduledRunner;
        this.scheduledRunner = null;
        if (old != null) old.shutdown();
        taskLog.info("定时任务已暂停，等待新运行时接管");
    }

    public void init(ScheduledTaskRunner scheduledAgent) {
        this.scheduledRunner = scheduledAgent;
        taskLog.info("定时任务调度器启动，共 {} 个任务", tasks.size());
        scheduleAllEnabled();
    }

    // ==================== 持久化 ====================

    private void loadAll(String workspaceId) {
        synchronized (persistenceLock) {
            loadedWorkspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
            tasks.clear();
            tasks.addAll(store.loadAll(workspaceId));
            log.info("已从 H2 加载 {} 个定时任务", tasks.size());
        }
    }

    // ==================== 系统内置任务（只读） ====================

    /** 该 id 是否为系统内置任务（只读，禁止一切写操作）。 */
    public boolean isBuiltin(String id) {
        return builtins.contains(id);
    }

    /**
     * 系统内置任务的手动触发动作：返回一句结果备注，允许抛异常（会被记为失败）。
     * 由拥有该机制的子系统注册（如 {@link com.javaclaw.system.CommandSessionManager}、
     * {@link com.javaclaw.memory.curation.HabitReviewer} 经 MemoryService）；未注册则该内置任务不支持「立即执行」。
     */
    @FunctionalInterface
    public interface BuiltinRunner {
        String run() throws Exception;
    }

    /** 注册内置任务动作；关闭返回句柄只移除本次注册，避免旧 Context 清理新 Context 的动作。 */
    public AutoCloseable registerBuiltinAction(String builtinId, BuiltinRunner runner) {
        return builtins.register(builtinId, runner);
    }

    /** 该内置任务是否支持「立即执行」（已注册手动动作）。 */
    public boolean hasBuiltinAction(String id) {
        return builtins.hasAction(id);
    }

    /**
     * 记录一次内置任务的执行（手动或自动均可调用）：更新上次时间/状态/计数 + 追加历史 + 通知 UI 刷新。
     * 内置任务不持久化，故记录仅存于本次会话内存。
     */
    public void recordBuiltinRun(String id, boolean success, long durationMs, String note) {
        if (builtins.record(id, success, durationMs, note)) events.completed(id);
    }

    /** 在单写串行器上执行一次内置任务的手动动作，并记录结果。 */
    private RunNowResult runBuiltinNow(String id) {
        BuiltinRunner runner = builtins.action(id);
        ScheduledTask t = builtins.find(id);
        if (runner == null || t == null) {
            log.warn("系统内置任务无手动触发动作，忽略: {}", id);
            return RunNowResult.UNSUPPORTED;
        }
        try {
            taskDispatcher.submit("schedule-builtin-" + id, () -> {
                runningTaskIds.add(id);
                events.started(id);
                long start = System.nanoTime();
                try {
                    String note = runner.run();
                    recordBuiltinRun(id, true, (System.nanoTime() - start) / 1_000_000L, note);
                    taskLog.info("[内置:{}] 手动执行成功：{}", t.getName(), note);
                } catch (Exception failure) {
                    String message = failure.getMessage() == null
                            ? failure.toString() : failure.getMessage();
                    recordBuiltinRun(id, false,
                            (System.nanoTime() - start) / 1_000_000L, message);
                    taskLog.warn("[内置:{}] 手动执行失败：{}", t.getName(), message);
                } finally {
                    runningTaskIds.remove(id);
                }
            }, () -> {});
            return RunNowResult.STARTED;
        } catch (RejectedExecutionException rejected) {
            log.warn("系统内置任务执行队列已关闭，拒绝触发: {}", id);
            return RunNowResult.UNSUPPORTED;
        }
    }

    // ==================== 查询 ====================

    /**
     * 返回全部定时任务：用户任务在前、系统内置任务在后。
     * 内置任务只读，UI/工具据 {@link ScheduledTask#isBuiltin()} 区分呈现与禁改。
     */
    public List<ScheduledTask> getAllTasks() {
        List<ScheduledTask> all = tasks.stream().map(ScheduledTask::copy)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        all.addAll(builtins.snapshots());
        return all;
    }

    public ScheduledTask getTask(String id) {
        ScheduledTask user = findTaskInternal(id);
        if (user != null) return user.copy();
        return builtins.snapshot(id);
    }

    private ScheduledTask findTaskInternal(String id) {
        if (id == null) return null;
        return tasks.stream().filter(t -> id.equals(t.getId())).findFirst().orElse(null);
    }

    private void replaceTaskInternal(ScheduledTask task) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).getId().equals(task.getId())) {
                tasks.set(i, task);
                return;
            }
        }
        tasks.add(task);
    }

    /** 该任务此刻是否正在执行（真实运行状态，非"启用"配置位） */
    public boolean isRunning(String id) {
        return runningTaskIds.contains(id);
    }

    /** 该任务正在排队或执行（用于拒绝重复手动触发）。 */
    public boolean isActive(String id) {
        return activeRunsByTask.containsKey(id) || runningTaskIds.contains(id);
    }

    /**
     * 该任务的下次触发时间（由 Quartz 触发器实时给出）；
     * 未启用 / 未调度 / 触发配置非法时返回 null。
     */
    public LocalDateTime getNextFireTime(String id) {
        try {
            Trigger trigger = quartz.getTrigger(TriggerKey.triggerKey(id, JOB_GROUP));
            if (trigger != null && trigger.getNextFireTime() != null) {
                return LocalDateTime.ofInstant(
                        trigger.getNextFireTime().toInstant(), java.time.ZoneId.systemDefault());
            }
        } catch (SchedulerException e) {
            log.warn("查询下次触发时间失败: {}", id, e);
        }
        return null;
    }

    // ==================== 增删改 ====================

    public enum DisableMode {
        /** 用户/UI/外部命令停用：同时取消当前排队或运行实例。 */
        CANCEL_ACTIVE,
        /** 任务在自身执行中自停：取消未来调度，但允许当前轮正常收尾。 */
        AFTER_CURRENT_RUN
    }

    public enum RunNowResult {
        STARTED, ALREADY_ACTIVE, DISABLED, NOT_FOUND, UNSUPPORTED
    }

    /** 仅创建内存草稿，不加入管理器、不持久化。 */
    public ScheduledTask createDraft(String name) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return new ScheduledTask(id, name);
    }

    /** 首次保存 UI 草稿；保存后才成为正式定时任务。 */
    public synchronized ScheduledTask saveNewTask(ScheduledTask task) {
        if (task == null || task.isBuiltin() || isBuiltin(task.getId())
                || findTaskInternal(task.getId()) != null
                || builtins.find(task.getId()) != null) {
            throw new IllegalArgumentException("无效或重复的定时任务草稿");
        }
        ScheduledTask candidate = task.copy();
        validateDefinition(candidate);
        ScheduledTask persisted;
        synchronized (persistenceLock) {
            persisted = store.insert(requireWorkspace(), candidate);
            tasks.add(persisted);
        }
        if (persisted.isEnabled() && !scheduleTask(persisted)) {
            ScheduledTask disabled = persisted.copy();
            disabled.setEnabled(false);
            synchronized (persistenceLock) {
                disabled = store.updateDefinition(requireWorkspace(), disabled);
                replaceTaskInternal(disabled);
            }
            throw new SchedulePersistenceException("任务已保存，但调度注册失败，已自动保持暂停："
                    + persisted.getName());
        }
        log.info("已创建定时任务: {} ({})", persisted.getName(), persisted.getId());
        taskLog.info("创建定时任务: {} ({})", persisted.getName(), persisted.getId());
        return persisted.copy();
    }

    public ScheduledTask updateTask(ScheduledTask task) {
        return updateTask(task, DisableMode.CANCEL_ACTIVE);
    }

    public ScheduledTask updateTask(ScheduledTask task, DisableMode disableMode) {
        if (task == null) throw new IllegalArgumentException("定时任务不能为空");
        if (task.isBuiltin() || isBuiltin(task.getId())) {
            throw new IllegalArgumentException("系统内置任务不可编辑：" + task.getId());
        }
        validateDefinition(task);
        ScheduledTask before;
        ScheduledTask persisted;
        synchronized (persistenceLock) {
            before = findTaskInternal(task.getId());
            if (before == null) throw new IllegalArgumentException("未找到定时任务：" + task.getId());
            // 停用必须先在内存中可见，防止持久化窗口期间到点的 Quartz tick
            // 读到 enabled=true 并启动新一轮。写入失败时会从库内刷新/回滚。
            if (before.isEnabled() && !task.isEnabled()) replaceTaskInternal(task.copy());
            try {
                persisted = store.updateDefinition(requireWorkspace(), task);
            } catch (RuntimeException failure) {
                refreshTaskAfterFailedWrite(task.getId(), before, failure);
                throw failure;
            }
            replaceTaskInternal(persisted);
        }
        cancelTask(persisted.getId());
        if (persisted.isEnabled() && !scheduleTask(persisted)) {
            persisted = rollbackToDisabled(persisted);
            throw new SchedulePersistenceException("任务配置已保存，但调度注册失败，已自动保持暂停："
                    + persisted.getName());
        }
        if (before.isEnabled() && !persisted.isEnabled()
                && disableMode == DisableMode.CANCEL_ACTIVE) {
            cancelActiveRun(persisted.getId(), CancellationReason.SCHEDULE_DISABLED);
        }
        log.info("已更新定时任务: {} ({})", persisted.getName(), persisted.getId());
        taskLog.info("更新定时任务: {} ({}), 启用: {}, 触发: {}",
                persisted.getName(), persisted.getId(), persisted.isEnabled(), persisted.getTriggerType());
        return persisted.copy();
    }

    public ScheduledTask setEnabled(String id, boolean enabled, DisableMode mode) {
        if (isBuiltin(id)) throw new IllegalArgumentException("系统内置任务不可停用：" + id);
        ScheduledTask persisted;
        synchronized (persistenceLock) {
            ScheduledTask current = findTaskInternal(id);
            if (current == null) throw new IllegalArgumentException("未找到定时任务：" + id);
            if (current.isEnabled() == enabled) {
                persisted = current.copy();
            } else {
                ScheduledTask changed = current.copy();
                changed.setEnabled(enabled);
                if (!enabled) replaceTaskInternal(changed.copy());
                try {
                    persisted = store.updateDefinition(requireWorkspace(), changed);
                } catch (RuntimeException failure) {
                    refreshTaskAfterFailedWrite(id, current, failure);
                    throw failure;
                }
                replaceTaskInternal(persisted);
            }
        }
        cancelTask(id);
        if (enabled && !scheduleTask(persisted)) {
            persisted = rollbackToDisabled(persisted);
            throw new SchedulePersistenceException("启用任务失败，已保持暂停：" + persisted.getName());
        }
        if (!enabled && mode == DisableMode.CANCEL_ACTIVE) {
            cancelActiveRun(id, CancellationReason.SCHEDULE_DISABLED);
        }
        taskLog.info("设置定时任务状态: {} ({}), 启用: {}, 模式: {}",
                persisted.getName(), id, enabled, mode);
        return persisted.copy();
    }

    public void deleteTask(String id) {
        if (isBuiltin(id)) {
            log.warn("系统内置任务不可删除，已忽略: {}", id);
            throw new IllegalArgumentException("系统内置任务不可删除：" + id);
        }
        synchronized (persistenceLock) {
            ScheduledTask current = findTaskInternal(id);
            if (current == null) return;
            ScheduledTask stagedDisabled = current.copy();
            stagedDisabled.setEnabled(false);
            replaceTaskInternal(stagedDisabled);
            try {
                store.delete(requireWorkspace(), id, current.getVersion());
            } catch (RuntimeException failure) {
                refreshTaskAfterFailedWrite(id, current, failure);
                throw failure;
            }
            tasks.removeIf(t -> t.getId().equals(id));
        }
        cancelTask(id);
        cancelActiveRun(id, CancellationReason.SCHEDULE_DISABLED);
        log.info("已删除定时任务: {}", id);
        taskLog.info("删除定时任务: {}", id);
    }

    public void deleteTasks(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        // 过滤掉系统内置任务，仅删除用户任务
        List<String> deletable = ids.stream().filter(id -> !isBuiltin(id)).toList();
        if (deletable.isEmpty()) return;
        for (String id : deletable) deleteTask(id);
        log.info("已批量删除 {} 个定时任务", deletable.size());
        taskLog.info("批量删除定时任务: {}", deletable);
    }

    /**
     * 手动立即执行一次任务 —— 用 Quartz 的 triggerJob 触发已存在的 job；
     * 若任务未启用尚未注册到 Quartz，则直接提交到专用串行队列。
     */
    public RunNowResult runNow(String id, boolean allowDisabled) {
        if (isBuiltin(id)) {
            if (!hasBuiltinAction(id)) return RunNowResult.UNSUPPORTED;
            if (runningTaskIds.contains(id)) return RunNowResult.ALREADY_ACTIVE;
            return runBuiltinNow(id);
        }
        ScheduledTask task = findTaskInternal(id);
        if (task == null) return RunNowResult.NOT_FOUND;
        if (!task.isEnabled() && !allowDisabled) return RunNowResult.DISABLED;
        taskLog.info("手动触发任务: {} ({})", task.getName(), id);
        return enqueueTask(id, !allowDisabled, true);
    }

    /** 向后兼容的显式手动执行；暂停任务仍需调用方先确认。 */
    public RunNowResult runNow(String id) {
        return runNow(id, true);
    }

    public boolean cancelRun(String id) {
        return cancelActiveRun(id, CancellationReason.SCHEDULE_DISABLED);
    }

    private ScheduledTask rollbackToDisabled(ScheduledTask persisted) {
        if (!persisted.isEnabled()) return persisted;
        ScheduledTask disabled = persisted.copy();
        disabled.setEnabled(false);
        synchronized (persistenceLock) {
            replaceTaskInternal(disabled.copy());
            try {
                ScheduledTask result = store.updateDefinition(requireWorkspace(), disabled);
                replaceTaskInternal(result);
                return result;
            } catch (RuntimeException failure) {
                refreshTaskAfterFailedWrite(persisted.getId(), persisted, failure);
                throw failure;
            }
        }
    }

    /**
     * 写入冲突/失败后尽力以数据库为准刷新管理器快照；数据库也不可读时
     * 恢复写入前快照，并把刷新错误挂到原异常上供 UI/工具完整呈现。
     */
    private void refreshTaskAfterFailedWrite(String id, ScheduledTask fallback,
                                             RuntimeException originalFailure) {
        try {
            ScheduledTask latest = store.find(requireWorkspace(), id);
            if (latest == null) tasks.removeIf(t -> t.getId().equals(id));
            else replaceTaskInternal(latest);
        } catch (RuntimeException refreshFailure) {
            originalFailure.addSuppressed(refreshFailure);
            if (fallback != null) replaceTaskInternal(fallback.copy());
        }
    }

    private void validateDefinition(ScheduledTask task) {
        if (task.getId() == null || task.getId().isBlank()) {
            throw new IllegalArgumentException("任务 ID 不能为空");
        }
        if (task.getName() == null || task.getName().isBlank()) {
            throw new IllegalArgumentException("任务名称不能为空");
        }
        if (task.getPrompt() == null || task.getPrompt().isBlank()) {
            throw new IllegalArgumentException("任务提示词不能为空");
        }
        task.normalizeIntervalFields();
        if (task.isEnabled() && triggerFactory.create(task) == null) {
            throw new IllegalArgumentException("任务触发配置无效");
        }
    }

    private String requireWorkspace() {
        String id = loadedWorkspaceId;
        if (id == null || id.isBlank()) {
            throw new SchedulePersistenceException("定时任务尚未绑定工作区");
        }
        return id;
    }

    // ==================== 调度 ====================

    private void scheduleAllEnabled() {
        int ok = 0;
        for (ScheduledTask task : tasks) {
            if (task.isEnabled() && scheduleTask(task)) {
                ok++;
            }
        }
        if (ok > 0) log.info("已启动 {} 个定时任务调度", ok);
    }

    /**
     * 为单个任务在 Quartz 中注册 JobDetail + Trigger。
     *
     * @return true=成功注册；false=配置非法/未启用，已在日志中说明原因
     */
    private boolean scheduleTask(ScheduledTask task) {
        if (!task.isEnabled()) return false;
        try {
            Trigger trigger = triggerFactory.create(task);
            if (trigger == null) {
                log.warn("任务 {} ({}) 触发配置非法，跳过调度", task.getName(), task.getId());
                taskLog.warn("[{}] 触发配置非法，跳过调度（类型: {}）",
                        task.getName(), task.getTriggerType());
                return false;
            }
            JobDetail job = JobBuilder.newJob(QuartzScheduleBackendFactory.ScheduledTaskJob.class)
                    .withIdentity(task.getId(), JOB_GROUP)
                    .usingJobData(new JobDataMap(Map.of(
                            QuartzScheduleBackendFactory.TASK_ID_KEY, task.getId())))
                    .storeDurably(false)
                    .build();
            quartz.scheduleJob(job, trigger);
            log.info("已调度定时任务: {} [{}]", task.getName(), task.getTriggerType());
            return true;
        } catch (SchedulerException e) {
            log.error("调度任务失败: {} ({})", task.getName(), task.getId(), e);
            return false;
        }
    }

    private void cancelTask(String id) {
        try {
            quartz.deleteJob(JobKey.jobKey(id, JOB_GROUP));
        } catch (SchedulerException e) {
            log.warn("取消任务调度失败: {}", id, e);
        }
    }

    // ==================== 执行 ====================

    /** Quartz 入口；重复 tick 会在真正入队前被合并。 */
    void executeTask(String taskId) {
        enqueueTask(taskId, true, false);
    }

    private RunNowResult enqueueTask(String taskId, boolean requireEnabled, boolean manual) {
        ScheduledTaskRunner runner = scheduledRunner;
        ScheduledTask snapshot = findTaskInternal(taskId);
        if (snapshot == null) return RunNowResult.NOT_FOUND;
        if (runner == null) {
            taskLog.warn("[{}] 定时任务执行器未初始化，跳过执行", snapshot.getName());
            return RunNowResult.UNSUPPORTED;
        }
        if (requireEnabled && !snapshot.isEnabled()) return RunNowResult.DISABLED;

        long epoch = executionEpoch.get();
        ScheduledRunControl control = new ScheduledRunControl(taskId);
        if (activeRunsByTask.putIfAbsent(taskId, control) != null) {
            taskLog.info("[{}] 已有运行或排队实例，合并本次{}触发",
                    snapshot.getName(), manual ? "手动" : "定时");
            return RunNowResult.ALREADY_ACTIVE;
        }

        try {
            ScheduleTaskDispatcher.Cancellation cancellation = taskDispatcher.submit(
                    "schedule-run-" + taskId,
                    () -> runQueuedTask(control, runner, epoch, requireEnabled, manual),
                    () -> activeRunsByTask.remove(taskId, control));
            control.attachQueuedCancellation(cancellation);
            return RunNowResult.STARTED;
        } catch (RejectedExecutionException rejected) {
            activeRunsByTask.remove(taskId, control);
            taskLog.warn("[{}] 执行队列已关闭，拒绝触发", snapshot.getName());
            return RunNowResult.UNSUPPORTED;
        }
    }

    private void runQueuedTask(ScheduledRunControl control, ScheduledTaskRunner runner, long epoch,
                               boolean requireEnabled, boolean manual) {
        control.markStarted();
        String taskId = control.taskId();
        ScheduledTask task;
        synchronized (persistenceLock) {
            ScheduledTask current = findTaskInternal(taskId);
            task = current == null ? null : current.copy();
        }
        if (control.isCancelled() || task == null || executionEpoch.get() != epoch
                || (requireEnabled && !task.isEnabled())) {
            String name = task == null ? taskId : task.getName();
            taskLog.info("[{}] 跳过已删除、已停用、已取消或旧工作区的排队执行", name);
            return;
        }

        String prompt = task.getPrompt();
        if (prompt == null || prompt.isBlank()) {
            taskLog.warn("[{}] 提示词为空，跳过执行", task.getName());
            return;
        }

        runningTaskIds.add(taskId);
        events.started(taskId);
        long startNanos = System.nanoTime();
        StringBuilder resultBuilder = new StringBuilder();
        try {
            log.info("开始执行定时任务: {} ({}) runId={}",
                    task.getName(), taskId, control.runId());
            taskLog.info("========== 任务开始 ==========");
            taskLog.info("[{}] 任务ID: {}, runId: {}, 来源: {}, 触发类型: {}",
                    task.getName(), taskId, control.runId(), manual ? "手动" : "定时", task.getTriggerType());
            taskLog.info("[{}] 提示词: {}", task.getName(),
                    prompt.length() > 200 ? prompt.substring(0, 200) + "..." : prompt);
            events.log(task.getName(), "开始执行...");

            String contextualPrompt = "【定时任务上下文】你正在执行定时任务「" + task.getName()
                    + "」（id=" + taskId + "）。若本次检查已满足目标条件，请先用 notify_send 通知用户，"
                    + "再调用 schedule_disable 工具并传入 id=" + taskId + " 停止本定时任务，避免继续轮询。\n\n"
                    + prompt;
            com.javaclaw.agent.ToolCallOrigin runOrigin =
                    com.javaclaw.agent.ToolConfirmationManager.beginAuthorizedScheduledRun(
                            taskId, task.isUnattendedToolsAuthorized());
            java.util.concurrent.atomic.AtomicBoolean terminalRecorded =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            ConversationCallbacks runCallbacks = new ConversationCallbacks() {
                @Override
                public void onEvent(ConversationEvent event) {
                    if (control.isCancelled() || terminalRecorded.get()) return;
                    switch (event) {
                        case ConversationEvent.Reply reply -> resultBuilder.append(reply.chunk());
                        case ConversationEvent.ToolResult tool -> taskLog.info("[{}] 工具调用: {} -> {}",
                                task.getName(), tool.toolName(),
                                tool.result().length() > 300
                                        ? tool.result().substring(0, 300) + "..." : tool.result());
                        case ConversationEvent.Hint hint ->
                                taskLog.info("[{}] 规划提示: {}", task.getName(), hint.text());
                        case ConversationEvent.LoopDetected loop -> {
                            taskLog.warn("[{}] 循环检测: {}", task.getName(), loop.warning());
                            events.log(task.getName(), "循环检测: " + loop.warning());
                        }
                        default -> { }
                    }
                }

                @Override
                public void onTerminal(ConversationOutcome outcome) {
                    if (!terminalRecorded.compareAndSet(false, true)) return;
                    ConversationOutcome effective = control.isCancelled()
                            ? ConversationOutcome.cancelled(control.cancellationReason()) : outcome;
                    recordScheduledOutcome(task, epoch, startNanos, resultBuilder, effective);
                }
            };
            try {
                runner.run(control, runOrigin, contextualPrompt, runCallbacks);
            } catch (Throwable failure) {
                runCallbacks.onTerminal(ConversationOutcome.failed(failure));
                if (failure instanceof Error error) throw error;
            }
            if (!terminalRecorded.get()) {
                runCallbacks.onTerminal(control.isCancelled()
                        ? ConversationOutcome.cancelled(control.cancellationReason())
                        : ConversationOutcome.failed(new IllegalStateException(
                                "定时任务 runner 返回时未上报终态")));
            }
        } finally {
            com.javaclaw.agent.ToolConfirmationManager.endScheduledRun();
            runningTaskIds.remove(taskId);
            autoDisableOnceTask(taskId, epoch);
            events.completed(taskId);
        }
    }

    private void recordScheduledOutcome(ScheduledTask runSnapshot, long epoch, long startNanos,
                                        StringBuilder resultBuilder, ConversationOutcome outcome) {
        if (!isExecutionCurrent(epoch, runSnapshot.getId())) return;
        String duration = formatDuration(startNanos);
        ScheduledTaskStore.ExecutionStatus status;
        String detail;
        Throwable failure = null;
        if (outcome instanceof ConversationOutcome.Failed failed) {
            status = ScheduledTaskStore.ExecutionStatus.FAILURE;
            failure = failed.error();
            detail = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        } else if (outcome instanceof ConversationOutcome.Cancelled cancelled) {
            status = ScheduledTaskStore.ExecutionStatus.CANCELLED;
            detail = "取消原因：" + cancelled.reason();
        } else {
            status = ScheduledTaskStore.ExecutionStatus.SUCCESS;
            detail = resultBuilder.length() > 500
                    ? resultBuilder.substring(0, 500) + "..." : resultBuilder.toString();
        }

        ScheduledTask persisted;
        try {
            synchronized (persistenceLock) {
                if (!isExecutionCurrent(epoch, runSnapshot.getId())) return;
                persisted = store.recordExecution(requireWorkspace(), runSnapshot.getId(),
                        new ScheduledTaskStore.ExecutionResult(status, duration, detail));
                if (persisted == null) return;
                replaceTaskInternal(persisted);
            }
        } catch (SchedulePersistenceException persistenceFailure) {
            log.error("保存定时任务执行结果失败: {}", runSnapshot.getId(), persistenceFailure);
            events.log(runSnapshot.getName(), "执行结果保存失败: " + persistenceFailure.getMessage());
            return;
        }

        switch (status) {
            case SUCCESS -> {
                taskLog.info("[{}] 执行成功（耗时 {}），回复内容: {}",
                        runSnapshot.getName(), duration, detail);
                taskLog.info("========== 任务结束（成功） ==========");
                events.log(runSnapshot.getName(), "执行完成: " + shortText(detail, 200));
                completionNotifier.notifyCompletion(persisted, true, detail);
            }
            case FAILURE -> {
                taskLog.error("[{}] 执行失败（耗时 {}）: {}",
                        runSnapshot.getName(), duration, detail, failure);
                taskLog.info("========== 任务结束（失败） ==========");
                events.log(runSnapshot.getName(), "执行失败: " + detail);
                completionNotifier.notifyCompletion(persisted, false, detail);
            }
            case CANCELLED -> {
                taskLog.info("[{}] 执行已取消（耗时 {}）: {}",
                        runSnapshot.getName(), duration, detail);
                taskLog.info("========== 任务结束（已取消） ==========");
                events.log(runSnapshot.getName(), "执行已取消");
            }
        }
    }

    private void autoDisableOnceTask(String taskId, long epoch) {
        if (!isExecutionCurrent(epoch, taskId)) return;
        ScheduledTask current = findTaskInternal(taskId);
        if (current == null || !"once".equals(current.getTriggerType()) || !current.isEnabled()) return;
        try {
            setEnabled(taskId, false, DisableMode.AFTER_CURRENT_RUN);
            taskLog.info("[{}] 一次性任务已完成并自动停用", current.getName());
        } catch (RuntimeException e) {
            log.error("一次性任务自动停用失败: {}", taskId, e);
        }
    }

    private boolean cancelActiveRun(String taskId, CancellationReason reason) {
        ScheduledRunControl control = activeRunsByTask.get(taskId);
        if (control == null) return false;
        boolean accepted = control.cancel(reason);
        if (accepted) taskLog.info("[{}] 已请求取消 runId={}，原因={}",
                taskId, control.runId(), reason);
        return accepted;
    }

    private void cancelAllRuns(CancellationReason reason) {
        for (ScheduledRunControl control : List.copyOf(activeRunsByTask.values())) {
            control.cancel(reason);
        }
    }

    private boolean isExecutionCurrent(long epoch, String taskId) {
        return executionEpoch.get() == epoch && findTaskInternal(taskId) != null;
    }

    private static String shortText(String text, int max) {
        if (text == null || text.isBlank()) return "—";
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }

    /** 把起始纳秒折算为可读耗时（如 "6.2s" / "850ms"）。 */
    private String formatDuration(long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        if (ms < 1000) return ms + "ms";
        return String.format("%.1fs", ms / 1000.0);
    }

    // ==================== 生命周期 ====================

    public void shutdown() {
        executionEpoch.incrementAndGet();
        cancelAllRuns(CancellationReason.SHUTDOWN);
        try {
            // 退出时不等待正在执行的 job 完成（waitForJobsToComplete=false）：
            // 定时任务可能触发长耗时的智能体运行，若 true 会阻塞应用退出导致卡死。
            quartz.shutdown(false);
        } catch (SchedulerException e) {
            log.warn("关闭 Quartz Scheduler 出错", e);
        }
        taskDispatcher.close();
        ScheduledTaskRunner runner = scheduledRunner;
        scheduledRunner = null;
        if (runner != null) runner.shutdown();
        log.info("定时任务调度器已关闭");
    }

    /**
     * 订阅任务运行事件。监听器在执行线程调用，必须快速返回；关闭句柄后不再接收新事件。
     */
    public AutoCloseable subscribe(TaskListener listener) {
        return events.subscribe(listener);
    }

    public interface TaskListener {
        default void onLog(String taskName, String message) { }
        default void onExecutionStarted(String taskId) { }
        default void onExecutionCompleted(String taskId) { }
    }

}
