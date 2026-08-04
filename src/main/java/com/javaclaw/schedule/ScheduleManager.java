package com.javaclaw.schedule;

import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.AppDatabase;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.DirectSchedulerFactory;
import org.quartz.simpl.RAMJobStore;
import org.quartz.simpl.SimpleThreadPool;
import org.quartz.spi.JobFactory;
import org.quartz.spi.TriggerFiredBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 定时任务管理器（基于 Quartz）
 *
 * <p>持久化、调度、执行定时任务。用户任务持久化到全局 H2 数据库的
 * {@code scheduled_tasks} 表，并按 {@code workspace_id} 隔离；启动时只从 H2 读取。
 * 底层使用 Quartz 2.5.2（通过 AgentScope
 * scheduler-quartz 扩展引入），由 Quartz 接管 cron 解析与触发器机制；
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

    /** JobDataMap 中 task id 的 key */
    private static final String JOB_DATA_TASK_ID = "taskId";

    /** 系统内置任务 id 前缀（据此识别只读内置项，拒绝一切写操作） */
    private static final String BUILTIN_ID_PREFIX = "sys:";

    private static ScheduleManager instance;

    private final ScheduledTaskStore store;
    private final List<ScheduledTask> tasks;
    /**
     * 系统内置定时任务（只读）——把「代码内部的周期性机制」纳入定时任务模块统一呈现。
     * 这些任务仍在各自模块原地运行；此处仅为只读展示，不参与 Quartz 调度、不写入
     * H2 scheduled_tasks 表，也不可被编辑 / 停用 / 删除 / 手动运行。
     */
    private final List<ScheduledTask> builtinTasks;
    private final ScheduleBackend quartz;

    /** 定时任务专用编排器（与交互聊天完全隔离，独立子智能体/toolkit/订阅，可与聊天并行不互相干扰） */
    private volatile ScheduledTaskRunner scheduledRunner;

    /** 工作区每次重载递增；旧代队列任务在真正开始及回调落盘前均会被拦截。 */
    private final AtomicLong executionEpoch = new AtomicLong();

    /** 序列化本进程内的配置快照替换与执行结果合并。 */
    private final Object persistenceLock = new Object();

    /** 当前内存任务实际所属工作区；保存时不再临时读取可变的全局工作区。 */
    private volatile String loadedWorkspaceId;

    /** 定时执行单线程串行器：不同任务仍串行，同一任务的重复 tick 在入队前合并。 */
    private final ExecutorService scheduledExec;

    /** 每个任务最多一个排队或运行实例。 */
    private final Map<String, ScheduledRunControl> activeRunsByTask = new ConcurrentHashMap<>();

    /** 正在执行中的任务 id 集合（实时运行状态：进入 executeTask 至本次完成期间为 true） */
    private final Set<String> runningTaskIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private BiConsumer<String, String> onTaskLog;
    private Consumer<String> onTaskExecutionComplete;
    private Consumer<String> onTaskExecutionStart;

    private ScheduleManager() {
        this.store = new ScheduledTaskStore(AppDatabase::getConnection);
        this.tasks = new CopyOnWriteArrayList<>();
        this.builtinTasks = buildBuiltinTasks();
        this.quartz = buildQuartzScheduler();
        this.scheduledExec = newScheduledExecutor();
        loadAll();
        wireBuiltinActions();
    }

    /** 测试构造：允许注入临时 H2、Quartz 与可控 runner，不触碰全局单例数据。 */
    ScheduleManager(ScheduledTaskStore store, String workspaceId, ScheduleBackend quartz,
                    ExecutorService scheduledExec, ScheduledTaskRunner runner) {
        this.store = Objects.requireNonNull(store, "store");
        this.tasks = new CopyOnWriteArrayList<>();
        this.builtinTasks = buildBuiltinTasks();
        this.quartz = Objects.requireNonNull(quartz, "quartz");
        this.scheduledExec = Objects.requireNonNull(scheduledExec, "scheduledExec");
        this.scheduledRunner = runner;
        loadAll(workspaceId);
    }

    private static ExecutorService newScheduledExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "scheduled-agent");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 注册就近可达的内置任务手动动作（命令会话清理为单例，直接接线）。
     * 习惯回顾由 MemoryService 在创建/重载时经 {@link #registerBuiltinAction} 注册（需其模型/存储引用）。
     */
    private void wireBuiltinActions() {
        // 命令会话清理：单例，可直接手动触发
        registerBuiltinAction(BUILTIN_ID_PREFIX + "cmd-session-cleanup", () -> {
            int n = com.javaclaw.system.CommandSessionManager.getInstance().cleanupIdleNow();
            return "回收 " + n + " 个空闲/失效会话";
        });
        // 自动回收（每 5 分钟）也记入执行记录：仅在有回收时记，避免刷屏
        com.javaclaw.system.CommandSessionManager.getInstance().setOnCleanup(n -> {
            if (n > 0) recordBuiltinRun(BUILTIN_ID_PREFIX + "cmd-session-cleanup", true, 0,
                    "自动回收 " + n + " 个空闲/失效会话");
        });
    }

    /**
     * 构造一个独占的 Quartz Scheduler 实例（避免与潜在的全局默认调度器冲突）。
     * RAMJobStore：任务/触发器仅在内存中，重启不残留；持久化由 JavaClaw 自己写入 H2。
     */
    private ScheduleBackend buildQuartzScheduler() {
        try {
            int threadCount = Math.max(1, AgentConfig.getInstance().getScheduleThreadPoolSize());
            SimpleThreadPool pool = new SimpleThreadPool(threadCount, Thread.NORM_PRIORITY);
            pool.setThreadNamePrefix("javaclaw-schedule-worker");
            pool.setMakeThreadsDaemons(true);

            String suffix = UUID.randomUUID().toString().substring(0, 8);
            String name = "javaclaw-scheduler-" + suffix;
            String id = "javaclaw-instance-" + suffix;
            DirectSchedulerFactory factory = DirectSchedulerFactory.getInstance();
            // 用 createScheduler(name, id, pool, jobStore) 注册到 factory，再 getScheduler 取出
            factory.createScheduler(name, id, pool, new RAMJobStore());
            Scheduler s = factory.getScheduler(name);
            // 注入 JobFactory：让 Quartz 不要 newInstance，直接拿单例的 ScheduledTaskJob
            s.setJobFactory(SingletonJobFactory.INSTANCE);
            s.start();
            return new QuartzScheduleBackend(s);
        } catch (SchedulerException e) {
            throw new IllegalStateException("初始化 Quartz Scheduler 失败", e);
        }
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
        loadAll();

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

    public static synchronized ScheduleManager getInstance() {
        if (instance == null) {
            instance = new ScheduleManager();
        }
        return instance;
    }

    public void init(ScheduledTaskRunner scheduledAgent) {
        this.scheduledRunner = scheduledAgent;
        taskLog.info("定时任务调度器启动，共 {} 个任务", tasks.size());
        scheduleAllEnabled();
    }

    // ==================== 持久化 ====================

    private void loadAll() {
        loadAll(AppDatabase.currentWorkspaceId());
    }

    private void loadAll(String workspaceId) {
        synchronized (persistenceLock) {
            loadedWorkspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
            tasks.clear();
            tasks.addAll(store.loadAll(workspaceId));
            log.info("已从 H2 加载 {} 个定时任务", tasks.size());
        }
    }

    // ==================== 系统内置任务（只读） ====================

    /**
     * 构建系统内置定时任务清单（只读）。把代码内部真正的周期性后台机制纳入定时任务模块统一呈现，
     * 供用户可见与审计；它们仍在各自模块原地运行，此处不接管其真实调度。
     */
    private List<ScheduledTask> buildBuiltinTasks() {
        List<ScheduledTask> list = new ArrayList<>();
        list.add(builtin(BUILTIN_ID_PREFIX + "cmd-session-cleanup", "命令会话清理",
                "定期清理空闲的命令行会话，回收 PTY/进程资源", "每 5 分钟", "CommandSessionManager"));
        list.add(builtin(BUILTIN_ID_PREFIX + "habit-review", "习惯回顾",
                "跨轮归纳重复模式为「习惯偏好」事实，补逐轮蒸馏只见单轮的盲区", "每轮对话后检查（间隔 ≥24h 且情景数达标才归纳）", "HabitReviewer"));
        return list;
    }

    private ScheduledTask builtin(String id, String name, String desc, String triggerSummary, String sourceModule) {
        ScheduledTask t = new ScheduledTask(id, name);
        t.setDescription(desc);
        t.setBuiltin(true);
        t.setEnabled(true);         // 内置机制常驻运行
        t.setTriggerSummary(triggerSummary);
        t.setSourceModule(sourceModule);
        t.setPrompt("");
        return t;
    }

    /** 该 id 是否为系统内置任务（只读，禁止一切写操作）。 */
    public boolean isBuiltin(String id) {
        return id != null && id.startsWith(BUILTIN_ID_PREFIX);
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

    /** 内置任务 id → 手动触发动作 */
    private final java.util.Map<String, BuiltinRunner> builtinActions =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 注册内置任务的手动触发动作（同 id 覆盖，供工作区重载时更新引用）。 */
    public void registerBuiltinAction(String builtinId, BuiltinRunner runner) {
        if (builtinId == null || runner == null) return;
        builtinActions.put(builtinId, runner);
    }

    /** 该内置任务是否支持「立即执行」（已注册手动动作）。 */
    public boolean hasBuiltinAction(String id) {
        return builtinActions.containsKey(id);
    }

    /**
     * 记录一次内置任务的执行（手动或自动均可调用）：更新上次时间/状态/计数 + 追加历史 + 通知 UI 刷新。
     * 内置任务不持久化，故记录仅存于本次会话内存。
     */
    public void recordBuiltinRun(String id, boolean success, long durationMs, String note) {
        ScheduledTask t = findBuiltinInternal(id);
        if (t == null || !t.isBuiltin()) return;
        t.recordExecution(success);
        String dur = durationMs <= 0 ? "—"
                : (durationMs < 1000 ? durationMs + "ms" : String.format("%.1fs", durationMs / 1000.0));
        t.setLastDuration(dur);
        String n = (note == null || note.isBlank()) ? "—"
                : (note.length() > 60 ? note.substring(0, 60) + "…" : note);
        t.addExecRecord(new ScheduledTask.ExecRecord(
                LocalDateTime.now().format(ScheduledTask.FORMATTER), success ? "成功" : "失败", dur, n));
        notifyExecutionComplete(id);
    }

    /** 在单写串行器上执行一次内置任务的手动动作，并记录结果。 */
    private void runBuiltinNow(String id) {
        BuiltinRunner runner = builtinActions.get(id);
        ScheduledTask t = findBuiltinInternal(id);
        if (runner == null || t == null) {
            log.warn("系统内置任务无手动触发动作，忽略: {}", id);
            return;
        }
        scheduledExec.submit(() -> {
            runningTaskIds.add(id);
            notifyExecutionStart(id);
            long start = System.nanoTime();
            try {
                String note = runner.run();
                recordBuiltinRun(id, true, (System.nanoTime() - start) / 1_000_000L, note);
                taskLog.info("[内置:{}] 手动执行成功：{}", t.getName(), note);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                recordBuiltinRun(id, false, (System.nanoTime() - start) / 1_000_000L, msg);
                taskLog.warn("[内置:{}] 手动执行失败：{}", t.getName(), msg);
            } finally {
                runningTaskIds.remove(id);
                notifyExecutionComplete(id);
            }
        });
    }

    // ==================== 查询 ====================

    /**
     * 返回全部定时任务：用户任务在前、系统内置任务在后。
     * 内置任务只读，UI/工具据 {@link ScheduledTask#isBuiltin()} 区分呈现与禁改。
     */
    public List<ScheduledTask> getAllTasks() {
        List<ScheduledTask> all = tasks.stream().map(ScheduledTask::copy)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        builtinTasks.stream().map(ScheduledTask::copy).forEach(all::add);
        return all;
    }

    public ScheduledTask getTask(String id) {
        ScheduledTask user = findTaskInternal(id);
        if (user != null) return user.copy();
        ScheduledTask builtin = findBuiltinInternal(id);
        return builtin == null ? null : builtin.copy();
    }

    private ScheduledTask findTaskInternal(String id) {
        if (id == null) return null;
        return tasks.stream().filter(t -> id.equals(t.getId())).findFirst().orElse(null);
    }

    private ScheduledTask findBuiltinInternal(String id) {
        if (id == null) return null;
        return builtinTasks.stream().filter(t -> id.equals(t.getId())).findFirst().orElse(null);
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

    /**
     * 兼容旧调用的草稿工厂。任务必须完整配置后再调用
     * {@link #saveNewTask(ScheduledTask)}，避免先写入半成品再二次更新。
     */
    @Deprecated
    public ScheduledTask createTask(String name) {
        return createDraft(name);
    }

    /** 仅创建内存草稿，不加入管理器、不持久化。 */
    public ScheduledTask createDraft(String name) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return new ScheduledTask(id, name);
    }

    /** 首次保存 UI 草稿；保存后才成为正式定时任务。 */
    public synchronized ScheduledTask saveNewTask(ScheduledTask task) {
        if (task == null || task.isBuiltin() || findTaskInternal(task.getId()) != null
                || findBuiltinInternal(task.getId()) != null) {
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
        if (before.isEnabled() && !persisted.isEnabled()) {
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
            runBuiltinNow(id);
            return RunNowResult.STARTED;
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
        if (task.isEnabled() && buildTrigger(task) == null) {
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
            Trigger trigger = buildTrigger(task);
            if (trigger == null) {
                log.warn("任务 {} ({}) 触发配置非法，跳过调度", task.getName(), task.getId());
                taskLog.warn("[{}] 触发配置非法，跳过调度（类型: {}）",
                        task.getName(), task.getTriggerType());
                return false;
            }
            JobDetail job = JobBuilder.newJob(ScheduledTaskJob.class)
                    .withIdentity(task.getId(), JOB_GROUP)
                    .usingJobData(new JobDataMap(Map.of(JOB_DATA_TASK_ID, task.getId())))
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

    /**
     * 按触发类型生成 Quartz Trigger；配置非法时返回 null
     */
    private Trigger buildTrigger(ScheduledTask task) {
        TriggerBuilder<Trigger> base = TriggerBuilder.newTrigger()
                .withIdentity(task.getId(), JOB_GROUP);

        return switch (task.getTriggerType()) {
            case "once" -> buildOnceTrigger(task, base);
            case "daily" -> buildDailyTrigger(task, base);
            case "cron" -> buildCronTrigger(task, base);
            default -> buildIntervalTrigger(task, base);
        };
    }

    /** 一次性触发：到点跑一次，不重复；运行结束后由 executeTask 自动停用。 */
    private Trigger buildOnceTrigger(ScheduledTask task, TriggerBuilder<Trigger> base) {
        String dt = task.getOnceDateTime();
        if (dt == null || dt.isBlank()) {
            log.warn("一次性任务 {} 未设置运行时间，跳过", task.getName());
            return null;
        }
        try {
            LocalDateTime when = LocalDateTime.parse(dt.trim(),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            java.util.Date fireAt = java.util.Date.from(
                    when.atZone(java.time.ZoneId.systemDefault()).toInstant());
            if (fireAt.before(new java.util.Date())) {
                // RAMJobStore 重启后不保留 misfire 状态，因此过期的一次性任务
                // 需由重建触发器主动补触发一次，执行收尾后会自动停用。
                taskLog.info("[{}] 一次性时间已过：{}，启动后补触发一次", task.getName(), dt);
                fireAt = new java.util.Date();
            }
            return base.startAt(fireAt)
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                            .withMisfireHandlingInstructionFireNow())
                    .build();
        } catch (Exception e) {
            log.warn("解析一次性运行时间失败: {}（任务 {}）", dt, task.getName());
            return null;
        }
    }

    private Trigger buildIntervalTrigger(ScheduledTask task, TriggerBuilder<Trigger> base) {
        int min = task.getIntervalMinutes();
        if (min <= 0) min = 60; // 默认 1 小时
        // 首次触发推迟一个间隔，而非 startNow()：避免「创建即刻触发」在创建它的那轮聊天仍在进行时
        // 并发再入 ChatService.streamChat，互相 dispose 订阅导致聊天卡死（当前需求的即时检查已由
        // 创建前的对话流程完成，定时任务负责后续每隔 N 分钟的轮询）。
        java.util.Date firstFire = new java.util.Date(System.currentTimeMillis() + min * 60_000L);
        return base.startAt(firstFire)
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMinutes(min)
                        .repeatForever()
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();
    }

    private Trigger buildDailyTrigger(ScheduledTask task, TriggerBuilder<Trigger> base) {
        String dailyTime = task.getDailyTime();
        if (dailyTime == null || dailyTime.isBlank()) dailyTime = "09:00";
        try {
            LocalTime t = LocalTime.parse(dailyTime, DateTimeFormatter.ofPattern("HH:mm"));
            // Quartz Cron 6 段：秒 分 时 日 月 周；周用 ? 表示不指定
            String cron = String.format("0 %d %d * * ?", t.getMinute(), t.getHour());
            return base.withSchedule(CronScheduleBuilder.cronSchedule(cron)
                    .withMisfireHandlingInstructionDoNothing()).build();
        } catch (Exception e) {
            log.warn("解析每日时间失败: {}，跳过", dailyTime);
            return null;
        }
    }

    private Trigger buildCronTrigger(ScheduledTask task, TriggerBuilder<Trigger> base) {
        String cron = task.getCronExpression();
        if (cron == null || cron.isBlank()) return null;
        if (!CronExpression.isValidExpression(cron)) {
            log.warn("Cron 表达式非法: 「{}」（任务 {}）。" +
                    "Quartz 要求 6 段：秒 分 时 日 月 周（日/周二选一用 ?）",
                    cron, task.getName());
            taskLog.warn("[{}] Cron 非法「{}」 — Quartz 需 6 段，旧 5 段表达式需重写",
                    task.getName(), cron);
            return null;
        }
        return base.withSchedule(CronScheduleBuilder.cronSchedule(cron)
                .withMisfireHandlingInstructionDoNothing()).build();
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

        FutureTask<Void> future = new FutureTask<>(() -> {
            runQueuedTask(control, runner, epoch, requireEnabled, manual);
            return null;
        }) {
            @Override
            protected void done() {
                activeRunsByTask.remove(taskId, control);
            }
        };
        control.attachFuture(future);
        try {
            scheduledExec.execute(future);
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
        notifyExecutionStart(taskId);
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
            emitLog(task.getName(), "开始执行...");

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
                            emitLog(task.getName(), "循环检测: " + loop.warning());
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
            notifyExecutionComplete(taskId);
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
            emitLog(runSnapshot.getName(), "执行结果保存失败: " + persistenceFailure.getMessage());
            return;
        }

        switch (status) {
            case SUCCESS -> {
                taskLog.info("[{}] 执行成功（耗时 {}），回复内容: {}",
                        runSnapshot.getName(), duration, detail);
                taskLog.info("========== 任务结束（成功） ==========");
                emitLog(runSnapshot.getName(), "执行完成: " + shortText(detail, 200));
                maybeNotify(persisted, true, detail);
            }
            case FAILURE -> {
                taskLog.error("[{}] 执行失败（耗时 {}）: {}",
                        runSnapshot.getName(), duration, detail, failure);
                taskLog.info("========== 任务结束（失败） ==========");
                emitLog(runSnapshot.getName(), "执行失败: " + detail);
                maybeNotify(persisted, false, detail);
            }
            case CANCELLED -> {
                taskLog.info("[{}] 执行已取消（耗时 {}）: {}",
                        runSnapshot.getName(), duration, detail);
                taskLog.info("========== 任务结束（已取消） ==========");
                emitLog(runSnapshot.getName(), "执行已取消");
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

    /** 若任务开启了完成通知，按其渠道推送一条执行结果（失败静默，不影响主流程）。 */
    private void maybeNotify(ScheduledTask task, boolean success, String detail) {
        if (!task.isNotifyEnabled()) return;
        String channel = task.getNotifyChannel();
        if (channel == null || channel.isBlank() || "none".equalsIgnoreCase(channel)) return;
        try {
            String title = "定时任务「" + task.getName() + "」" + (success ? "执行完成" : "执行失败");
            String body = (success ? "✅ " : "⚠️ ") + title + "\n"
                    + (detail == null || detail.isBlank() ? "" : detail);
            String r = new com.javaclaw.notification.NotificationTools(
                    com.javaclaw.agent.ToolCallOrigin.SCHEDULED).sendByChannel(channel, title, body);
            taskLog.info("[{}] 完成通知（{}）: {}", task.getName(), channel, r);
        } catch (Exception e) {
            log.warn("定时任务完成通知发送失败: {}", task.getName(), e);
        }
    }

    private void emitLog(String taskName, String message) {
        if (onTaskLog != null) {
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            onTaskLog.accept(taskName, "[" + time + "] " + message);
        }
    }

    private void notifyExecutionComplete(String taskId) {
        if (onTaskExecutionComplete != null) {
            onTaskExecutionComplete.accept(taskId);
        }
    }

    private void notifyExecutionStart(String taskId) {
        if (onTaskExecutionStart != null) {
            onTaskExecutionStart.accept(taskId);
        }
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
        scheduledExec.shutdownNow();
        ScheduledTaskRunner runner = scheduledRunner;
        scheduledRunner = null;
        if (runner != null) runner.shutdown();
        log.info("定时任务调度器已关闭");
    }

    public void setOnTaskLog(BiConsumer<String, String> callback) {
        this.onTaskLog = callback;
    }

    public void setOnTaskExecutionComplete(Consumer<String> callback) {
        this.onTaskExecutionComplete = callback;
    }

    public void setOnTaskExecutionStart(Consumer<String> callback) {
        this.onTaskExecutionStart = callback;
    }

    // ==================== Quartz Job / JobFactory ====================

    /**
     * Quartz Job：到点时由 Quartz 工作线程调用，把执行委派回 ScheduleManager。
     * 单例复用（通过 {@link SingletonJobFactory}），不需要状态。
     */
    public static class ScheduledTaskJob implements org.quartz.Job {
        @Override
        public void execute(JobExecutionContext context) {
            String taskId = context.getMergedJobDataMap().getString(JOB_DATA_TASK_ID);
            ScheduleManager mgr = ScheduleManager.getInstance();
            mgr.executeTask(taskId);
        }
    }

    /**
     * Quartz JobFactory：让 Quartz 不通过 newInstance() 创建 Job，
     * 直接返回单例（ScheduledTaskJob 无状态）。
     */
    private enum SingletonJobFactory implements JobFactory {
        INSTANCE;
        private final ScheduledTaskJob job = new ScheduledTaskJob();
        @Override
        public org.quartz.Job newJob(TriggerFiredBundle bundle, Scheduler scheduler) {
            return job;
        }
    }
}
