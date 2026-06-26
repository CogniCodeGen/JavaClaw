package com.javaclaw.schedule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.javaclaw.agent.ScheduledTaskAgent;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.config.AgentConfig;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 定时任务管理器（基于 Quartz）
 *
 * <p>持久化、调度、执行定时任务。底层使用 Quartz 2.5.2（通过 AgentScope
 * scheduler-quartz 扩展引入），由 Quartz 接管 cron 解析与触发器机制；
 * 任务到点后调用 {@link ChatService#streamChat} 执行（定时任务固定走普通模式）。</p>
 *
 * <p>三种触发模式（UI 语义保持不变，内部统一翻译为 Quartz Trigger）：
 * <ul>
 *   <li><b>interval</b> — 固定间隔分钟 → Quartz {@code SimpleTrigger}</li>
 *   <li><b>daily</b> — 每日 HH:mm → 合成为 Quartz Cron {@code "0 mm HH * * ?"}</li>
 *   <li><b>cron</b> — Quartz 标准 6 段表达式（秒 分 时 日 月 周，可选 7 段含年）</li>
 * </ul>
 *
 * <p>注意：从旧 simple-cron（5 段：分 时 日 月 周）迁移到 Quartz Cron 是破坏性变更；
 * 旧表达式会被启动时拒绝并跳过，需要用户重新填写为 6 段标准 Quartz Cron。</p>
 *
 * @author JavaClaw
 */
public class ScheduleManager {

    private static final Logger log = LoggerFactory.getLogger(ScheduleManager.class);

    /** 定时任务执行专用日志（独立写入 logs/task-YYYY-MM-DD.log） */
    private static final Logger taskLog = LoggerFactory.getLogger("com.javaclaw.schedule.TaskExecution");

    private static final String TASKS_FILE = "scheduled-tasks.json";

    /** Quartz Job/Trigger group name（隔离 JavaClaw 任务与其它可能的 Quartz 使用方）*/
    private static final String JOB_GROUP = "javaclaw-scheduled-tasks";

    /** JobDataMap 中 task id 的 key */
    private static final String JOB_DATA_TASK_ID = "taskId";

    private static ScheduleManager instance;

    private Path tasksFile;
    private final ObjectMapper objectMapper;
    private final List<ScheduledTask> tasks;
    private final Scheduler quartz;

    /** 定时任务专用编排器（与交互聊天完全隔离，独立子智能体/toolkit/订阅，可与聊天并行不互相干扰） */
    private ScheduledTaskAgent scheduledAgent;

    /** 定时执行单线程串行器：所有定时 tick 在此排队顺序执行，复用的 toolkit/子智能体不被并发触碰、tick 不丢 */
    private final java.util.concurrent.ExecutorService scheduledExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "scheduled-agent");
                t.setDaemon(true);
                return t;
            });

    /** 正在执行中的任务 id 集合（实时运行状态：进入 executeTask 至本次完成期间为 true） */
    private final Set<String> runningTaskIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private BiConsumer<String, String> onTaskLog;
    private Consumer<String> onTaskExecutionComplete;
    private Consumer<String> onTaskExecutionStart;

    private ScheduleManager() {
        resolveTasksFile();
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.tasks = new ArrayList<>();
        this.quartz = buildQuartzScheduler();
        ensureDataDir();
        loadAll();
    }

    /**
     * 构造一个独占的 Quartz Scheduler 实例（避免与潜在的全局默认调度器冲突）。
     * RAMJobStore：任务/触发器仅在内存中，重启不残留；持久化由 JavaClaw 自己用 JSON 管。
     */
    private Scheduler buildQuartzScheduler() {
        try {
            int threadCount = Math.max(1, AgentConfig.getInstance().getScheduleThreadPoolSize());
            SimpleThreadPool pool = new SimpleThreadPool(threadCount, Thread.NORM_PRIORITY);
            pool.setThreadNamePrefix("javaclaw-schedule-worker");
            pool.setMakeThreadsDaemons(true);

            String name = "javaclaw-scheduler";
            String id = "javaclaw-instance";
            DirectSchedulerFactory factory = DirectSchedulerFactory.getInstance();
            // 用 createScheduler(name, id, pool, jobStore) 注册到 factory，再 getScheduler 取出
            factory.createScheduler(name, id, pool, new RAMJobStore());
            Scheduler s = factory.getScheduler(name);
            // 注入 JobFactory：让 Quartz 不要 newInstance，直接拿单例的 ScheduledTaskJob
            s.setJobFactory(SingletonJobFactory.INSTANCE);
            s.start();
            return s;
        } catch (SchedulerException e) {
            throw new IllegalStateException("初始化 Quartz Scheduler 失败", e);
        }
    }

    private void resolveTasksFile() {
        this.tasksFile = com.javaclaw.config.WorkspaceManager.getInstance()
                .getCurrentWorkspacePath().resolve("data").resolve(TASKS_FILE);
    }

    /**
     * 重新加载定时任务（工作区切换时调用）
     */
    public void reload(ScheduledTaskAgent newScheduledAgent) {
        try {
            quartz.clear(); // 删掉所有 job + trigger，但 scheduler 仍运行
        } catch (SchedulerException e) {
            log.warn("清空 Quartz 任务失败（继续）", e);
        }
        tasks.clear();

        resolveTasksFile();
        ensureDataDir();
        loadAll();

        ScheduledTaskAgent old = this.scheduledAgent;
        this.scheduledAgent = newScheduledAgent;
        if (old != null && old != newScheduledAgent) old.shutdown();
        taskLog.info("定时任务已重新加载，共 {} 个任务", tasks.size());
        scheduleAllEnabled();
    }

    public static synchronized ScheduleManager getInstance() {
        if (instance == null) {
            instance = new ScheduleManager();
        }
        return instance;
    }

    public void init(ScheduledTaskAgent scheduledAgent) {
        this.scheduledAgent = scheduledAgent;
        taskLog.info("定时任务调度器启动，共 {} 个任务", tasks.size());
        scheduleAllEnabled();
    }

    // ==================== 持久化 ====================

    private void ensureDataDir() {
        try {
            Files.createDirectories(tasksFile.getParent());
        } catch (IOException e) {
            log.error("创建数据目录失败", e);
        }
    }

    private void loadAll() {
        tasks.clear();
        if (!Files.exists(tasksFile)) return;
        try {
            List<ScheduledTask> loaded = objectMapper.readValue(
                    tasksFile.toFile(), new TypeReference<>() {});
            tasks.addAll(loaded);
            log.info("已加载 {} 个定时任务", tasks.size());
        } catch (IOException e) {
            log.warn("加载定时任务失败", e);
        }
    }

    private void saveAll() {
        try {
            objectMapper.writeValue(tasksFile.toFile(), tasks);
        } catch (IOException e) {
            log.error("保存定时任务失败", e);
        }
    }

    // ==================== 查询 ====================

    public List<ScheduledTask> getAllTasks() {
        return new ArrayList<>(tasks);
    }

    public ScheduledTask getTask(String id) {
        return tasks.stream()
                .filter(t -> t.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    /** 该任务此刻是否正在执行（真实运行状态，非"启用"配置位） */
    public boolean isRunning(String id) {
        return runningTaskIds.contains(id);
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

    public ScheduledTask createTask(String name) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        ScheduledTask task = new ScheduledTask(id, name);
        tasks.add(task);
        saveAll();
        log.info("已创建定时任务: {} ({})", name, id);
        taskLog.info("创建定时任务: {} ({})", name, id);
        return task;
    }

    public void updateTask(ScheduledTask task) {
        saveAll();
        cancelTask(task.getId());
        if (task.isEnabled()) {
            scheduleTask(task);
        }
        log.info("已更新定时任务: {} ({})", task.getName(), task.getId());
        taskLog.info("更新定时任务: {} ({}), 启用: {}, 触发: {}",
                task.getName(), task.getId(), task.isEnabled(), task.getTriggerType());
    }

    public void deleteTask(String id) {
        cancelTask(id);
        tasks.removeIf(t -> t.getId().equals(id));
        saveAll();
        log.info("已删除定时任务: {}", id);
        taskLog.info("删除定时任务: {}", id);
    }

    public void deleteTasks(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        ids.forEach(this::cancelTask);
        tasks.removeIf(t -> ids.contains(t.getId()));
        saveAll();
        log.info("已批量删除 {} 个定时任务", ids.size());
        taskLog.info("批量删除定时任务: {}", ids);
    }

    /**
     * 手动立即执行一次任务 —— 用 Quartz 的 triggerJob 触发已存在的 job；
     * 若任务未启用尚未注册到 Quartz，回退到直接同步执行。
     */
    public void runNow(String id) {
        ScheduledTask task = getTask(id);
        if (task == null) return;
        taskLog.info("手动触发任务: {} ({})", task.getName(), id);
        try {
            JobKey jobKey = JobKey.jobKey(id, JOB_GROUP);
            if (quartz.checkExists(jobKey)) {
                quartz.triggerJob(jobKey);
                return;
            }
        } catch (SchedulerException e) {
            log.warn("Quartz 手动触发失败，回退到直接执行", e);
        }
        // 未在 Quartz 中：直接异步执行（同样通过 Quartz 线程池更一致，但简单起见用一次性线程）
        Thread t = new Thread(() -> executeTask(task), "schedule-runnow-" + id);
        t.setDaemon(true);
        t.start();
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
                log.warn("一次性任务 {} 的运行时间已过（{}），跳过调度", task.getName(), dt);
                taskLog.warn("[{}] 一次性时间已过：{}，跳过", task.getName(), dt);
                return null;
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
            return base.withSchedule(CronScheduleBuilder.cronSchedule(cron)).build();
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
        return base.withSchedule(CronScheduleBuilder.cronSchedule(cron)).build();
    }

    private void cancelTask(String id) {
        try {
            quartz.deleteJob(JobKey.jobKey(id, JOB_GROUP));
        } catch (SchedulerException e) {
            log.warn("取消任务调度失败: {}", id, e);
        }
    }

    // ==================== 执行 ====================

    /**
     * 实际执行 —— 由 Quartz Job 或 runNow 调用。
     *
     * <p>不在 Quartz 工作线程上阻塞太久：streamChat 本身是异步流式，
     * onComplete/onError 回调中再做记录与持久化。</p>
     */
    void executeTask(ScheduledTask task) {
        if (scheduledAgent == null) {
            log.warn("定时任务编排器未初始化，跳过任务: {}", task.getName());
            taskLog.warn("[{}] 定时任务编排器未初始化，跳过执行", task.getName());
            return;
        }

        String prompt = task.getPrompt();
        if (prompt == null || prompt.isBlank()) {
            log.warn("任务提示词为空，跳过: {}", task.getName());
            taskLog.warn("[{}] 提示词为空，跳过执行", task.getName());
            return;
        }

        // 提交到单线程串行器：与交互聊天完全隔离（ScheduledTaskAgent 自带独立编排器/子智能体/订阅，
        // 可与聊天并行不互相 dispose），多个定时 tick 在此排队顺序执行、不丢拍；run 阻塞直到本次完成。
        final ScheduledTaskAgent agent = scheduledAgent;
        scheduledExec.submit(() -> {
        runningTaskIds.add(task.getId());
        notifyExecutionStart(task.getId());
        try {
        log.info("开始执行定时任务: {} ({})", task.getName(), task.getId());
        taskLog.info("========== 任务开始 ==========");
        taskLog.info("[{}] 任务ID: {}, 触发类型: {}", task.getName(), task.getId(), task.getTriggerType());
        taskLog.info("[{}] 提示词: {}", task.getName(),
                prompt.length() > 200 ? prompt.substring(0, 200) + "..." : prompt);
        emitLog(task.getName(), "开始执行...");

        final long startNanos = System.nanoTime();
        final StringBuilder resultBuilder = new StringBuilder();

        // 注入定时任务上下文：让执行体知道"本任务 id"，从而能在条件达成后调 schedule_disable 自停。
        String contextualPrompt = "【定时任务上下文】你正在执行定时任务「" + task.getName()
                + "」（id=" + task.getId() + "）。若本次检查已满足目标条件，请先用 notify_send 通知用户，"
                + "再调用 schedule_disable 工具并传入 id=" + task.getId() + " 停止本定时任务，避免继续轮询。\n\n"
                + prompt;

        agent.run(contextualPrompt, new ConversationCallbacks() {
            @Override
            public void onEvent(ConversationEvent event) {
                switch (event) {
                    case ConversationEvent.Reply r -> resultBuilder.append(r.chunk());
                    case ConversationEvent.ToolResult tr -> taskLog.info("[{}] 工具调用: {} -> {}",
                            task.getName(), tr.toolName(),
                            tr.result().length() > 300 ? tr.result().substring(0, 300) + "..." : tr.result());
                    case ConversationEvent.Hint h -> taskLog.info("[{}] 规划提示: {}", task.getName(), h.text());
                    case ConversationEvent.Evaluation ev -> taskLog.info("[{}] GEPA 评估: {}/5.0 — {}",
                            task.getName(), String.format("%.1f", ev.result().getScore()),
                            ev.result().getSummary());
                    case ConversationEvent.LoopDetected ld -> {
                        taskLog.warn("[{}] 循环检测: {}", task.getName(), ld.warning());
                        emitLog(task.getName(), "循环检测: " + ld.warning());
                    }
                    default -> { /* 思考流 / 子智能体增量 / Usage 等事件在定时任务中忽略 */ }
                }
            }

            @Override
            public void onComplete() {
                task.recordExecution(true);
                String dur = formatDuration(startNanos);
                task.setLastDuration(dur);
                String summary = resultBuilder.length() > 500
                        ? resultBuilder.substring(0, 500) + "..." : resultBuilder.toString();
                String note = summary.isBlank() ? "—"
                        : (summary.length() > 60 ? summary.substring(0, 60) + "…" : summary);
                task.addExecRecord(new ScheduledTask.ExecRecord(
                        LocalDateTime.now().format(ScheduledTask.FORMATTER), "成功", dur, note));
                task.addExecutionRecord(LocalDateTime.now().format(ScheduledTask.FORMATTER)
                        + " [成功] " + note);
                saveAll();
                taskLog.info("[{}] 执行成功（耗时 {}），回复内容: {}", task.getName(), dur, summary);
                taskLog.info("========== 任务结束（成功） ==========");
                emitLog(task.getName(), "执行完成: " + (summary.length() > 200 ? summary.substring(0, 200) + "..." : summary));
                log.info("定时任务执行完成: {}", task.getName());
                maybeNotify(task, true, summary);
            }

            @Override
            public void onError(Throwable error) {
                task.recordExecution(false);
                String dur = formatDuration(startNanos);
                task.setLastDuration(dur);
                String msg = error.getMessage() == null ? error.toString() : error.getMessage();
                task.addExecRecord(new ScheduledTask.ExecRecord(
                        LocalDateTime.now().format(ScheduledTask.FORMATTER), "失败", "—", msg));
                task.addExecutionRecord(LocalDateTime.now().format(ScheduledTask.FORMATTER)
                        + " [失败] " + msg);
                saveAll();
                taskLog.error("[{}] 执行失败: {}", task.getName(), msg, error);
                taskLog.info("========== 任务结束（失败） ==========");
                emitLog(task.getName(), "执行失败: " + msg);
                log.error("定时任务执行失败: {}", task.getName(), error);
                maybeNotify(task, false, msg);
            }
        });
        } finally {
            runningTaskIds.remove(task.getId());
            // 一次性任务跑完即自动停用（取消 Quartz job + 持久化），避免残留
            if ("once".equals(task.getTriggerType()) && task.isEnabled()) {
                task.setEnabled(false);
                cancelTask(task.getId());
                saveAll();
                taskLog.info("[{}] 一次性任务已完成并自动停用", task.getName());
            }
            // run() 阻塞返回即本次结束；通知 UI 刷新运行状态/下次时间
            notifyExecutionComplete(task.getId());
        }
        });
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
            String r = new com.javaclaw.notification.NotificationTools().sendByChannel(channel, title, body);
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
        try {
            // 退出时不等待正在执行的 job 完成（waitForJobsToComplete=false）：
            // 定时任务可能触发长耗时的智能体运行，若 true 会阻塞应用退出导致卡死。
            quartz.shutdown(false);
        } catch (SchedulerException e) {
            log.warn("关闭 Quartz Scheduler 出错", e);
        }
        scheduledExec.shutdownNow();
        if (scheduledAgent != null) scheduledAgent.shutdown();
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
            ScheduledTask task = mgr.getTask(taskId);
            if (task == null) {
                log.warn("Quartz 触发了不存在的任务: {}", taskId);
                return;
            }
            mgr.executeTask(task);
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
