package com.javaclaw.task.sdd.run;

import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.platform.execution.TaskHandle;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.platform.process.ProcessRunner;
import com.javaclaw.skill.SkillRuntimeServices;
import com.javaclaw.skill.curation.SkillCurator;
import com.javaclaw.task.sdd.SddOutcome;
import com.javaclaw.task.sdd.SddProgress;
import com.javaclaw.task.sdd.SddTaskRunner;
import com.javaclaw.task.sdd.TaskContext;
import com.javaclaw.task.sdd.gate.AutoApproveReviewGate;
import com.javaclaw.task.sdd.gate.PortReviewGate;
import com.javaclaw.task.sdd.spec.OpenSpecChange;
import com.javaclaw.task.sdd.spec.SpecPaths;
import com.javaclaw.task.sdd.spec.SpecStore;
import com.javaclaw.util.ProjectAccessPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SDD 托管任务管理器 —— 取代 v5 的 {@code TaskManager}（生命周期 + 持久化 + 执行驱动）。
 *
 * <p><b>自包含、增量</b>：用 {@link SddTaskRunner} 真正驱动任务，持久化精简
 * {@link SddManagedTask} 索引到全局 H2 {@code sdd_tasks} 表，OpenSpec 文档存到
 * {@code sdd_spec_docs} 表，并按 {@code workspace_id} 隔离。状态/进度从 H2 文档派生或由运行结果回填。</p>
 *
 * <p>本类通过 H2 管理任务索引与 OpenSpec 文档，工作区差异由 {@code workspace_id} 隔离。</p>
 *
 * <p>实例由工作区 Spring Context 管理，不跨工作区重绑。每个运行中任务都通过
 * {@link TaskScope} 登记到 I/O 虚拟线程；暂停、取消或 Context 关闭会同时取消句柄并通知
 * {@link SddTaskRunner}，且世代号会丢弃迟到结果。</p>
 *
 * @author JavaClaw
 */
public final class SddTaskManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SddTaskManager.class);

    private final List<SddManagedTask> tasks = new ArrayList<>();
    private final Map<String, SddTaskRunner> running = new ConcurrentHashMap<>();
    private final Map<String, TaskHandle<Void>> runHandles = new ConcurrentHashMap<>();
    /** 每次启动的世代号；暂停/取消会使旧执行线程的迟到结果失效。 */
    private final Map<String, Long> runEpochs = new ConcurrentHashMap<>();
    private final AtomicLong epochSequence = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<SddTaskListener> listeners = new CopyOnWriteArrayList<>();

    private final com.javaclaw.framework.api.AgentClient agents;
    private final com.javaclaw.framework.spi.ModelTaskGateway modelTasks;
    private final com.javaclaw.runtime.WorkspaceContext workspace;
    private final SkillCurator skillCurator;
    private final com.javaclaw.workflow.service.WorkflowService workflowService;
    private final SkillRuntimeServices skillRuntime;
    private final AgentConfig settings;
    private final TaskScope taskScope;
    private final UserInteractionPort interactionPort;
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final ProcessRunner processes;
    private final String workspaceId;
    private final SddTaskStore store;

    // ==================== 配置 / 持久化 ====================

    public SddTaskManager(
            com.javaclaw.framework.api.AgentClient agents,
            com.javaclaw.framework.spi.ModelTaskGateway modelTasks,
            com.javaclaw.runtime.WorkspaceContext workspace,
            SkillRuntimeServices skillRuntime,
            SkillCurator skillCurator,
            AgentConfig settings,
            TaskScope taskScope,
            UserInteractionPort interactionPort,
            com.javaclaw.workflow.service.WorkflowService workflowService,
            JdbcTemplate jdbc,
            JsonCodec json,
            ProcessRunner processes,
            String workspaceId,
            SddTaskStore store) {
        this.agents = java.util.Objects.requireNonNull(agents, "agents");
        this.modelTasks = java.util.Objects.requireNonNull(modelTasks, "modelTasks");
        this.workspace = java.util.Objects.requireNonNull(workspace, "workspace");
        this.skillRuntime = java.util.Objects.requireNonNull(skillRuntime, "skillRuntime");
        this.skillCurator = java.util.Objects.requireNonNull(skillCurator, "skillCurator");
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        this.taskScope = java.util.Objects.requireNonNull(taskScope, "taskScope");
        this.interactionPort = interactionPort;
        this.workflowService = workflowService;
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.processes = java.util.Objects.requireNonNull(processes, "processes");
        this.workspaceId = java.util.Objects.requireNonNull(workspaceId, "workspaceId");
        this.store = java.util.Objects.requireNonNull(store, "store");
        tasks.addAll(store.loadAll());
        recoverInterrupted();
    }

    /**
     * 订阅当前工作区的任务事件。返回句柄关闭幂等；回调可能在任意托管线程执行。
     */
    public AutoCloseable subscribe(SddTaskListener listener) {
        SddTaskListener checked = java.util.Objects.requireNonNull(listener, "listener");
        listeners.add(checked);
        AtomicBoolean removed = new AtomicBoolean();
        return () -> {
            if (removed.compareAndSet(false, true)) listeners.remove(checked);
        };
    }

    private synchronized void saveAll() {
        store.replaceAll(tasks);
    }

    // ==================== 查询 ====================

    public synchronized List<SddManagedTask> list() {
        return new ArrayList<>(tasks);
    }

    public synchronized SddManagedTask get(String id) {
        return tasks.stream().filter(t -> t.id.equals(id)).findFirst().orElse(null);
    }

    // ==================== 生命周期 ====================

    public synchronized SddManagedTask create(String title, String description, String capabilities,
                                              String workDir, long tokenBudget, String notificationChannel,
                                              String nowStamp) {
        ensureOpen();
        String safeWorkDir = workDir;
        if (workDir != null && !workDir.isBlank()) {
            Path resolved = ProjectAccessPolicy.resolveProjectPath(workDir);
            if (!Files.isDirectory(resolved)) {
                throw new IllegalArgumentException("任务工作目录不存在或不是目录: " + resolved);
            }
            safeWorkDir = resolved.toString();
        }
        String id = Integer.toHexString((title + description + nowStamp).hashCode() & 0x7fffffff);
        // 避免碰撞
        while (get(id) != null) id = Integer.toHexString((id + "x").hashCode() & 0x7fffffff);
        SddManagedTask t = new SddManagedTask(id, title, description, safeWorkDir,
                capabilities == null ? "auto" : capabilities, tokenBudget, notificationChannel, nowStamp);
        tasks.add(t);
        saveAll();
        notifyTaskChanged(t);
        log.info("[SDD] 已创建任务: {} ({})", title, id);
        return t;
    }

    /**
     * 用轻量模型从需求描述生成简洁标题（失败/超时回退截断描述）。
     *
     * <p><b>务必在后台线程调用</b>：会发起一次模型请求，不可在 JavaFX 应用线程执行。</p>
     */
    public String generateTitle(String description) {
        ensureOpen();
        return SddTaskTitles.fallback(description);
    }

    public void start(String id, String completionStamp) {
        launch(id, completionStamp, false);
    }

    public void resume(String id, String completionStamp) {
        launch(id, completionStamp, true);
    }

    private void launch(String id, String completionStamp, boolean resume) {
        ensureOpen();
        final SddManagedTask task;
        final long epoch;
        synchronized (this) {
            task = get(id);
            if (task == null) return;
            if (runEpochs.containsKey(id) || running.containsKey(id) || task.state == SddTaskState.RUNNING) {
                log.warn("[SDD] 任务 {} 已在启动或运行，忽略重复启动", id);
                return;
            }
            if (isOverBudget(task)) {
                // 启动前已超预算：不进线程白跑，直接停为待人工（调高预算后再启动）
                setState(task, SddTaskState.NEEDS_HUMAN, "token 预算已耗尽（已用 "
                        + (task.totalInputTokens + task.totalOutputTokens) + " / 预算 "
                        + task.tokenBudget + "），请调高预算后续跑");
                return;
            }
            // 工作目录留空：默认在程序运行目录的 task/ 下为本任务建独立子目录（spec 产物落盘地）
            try {
                ensureWorkDir(task);
            } catch (RuntimeException e) {
                setState(task, SddTaskState.NEEDS_HUMAN, e.getMessage());
                return;
            }
            epoch = epochSequence.incrementAndGet();
            runEpochs.put(id, epoch);
            // 先置 RUNNING 再进后台：能力路由 + 装配在后台线程做（路由有 15s 阻塞上限，不能卡 UI 线程）
            setState(task, SddTaskState.RUNNING, null);
        }

        try {
            TaskHandle<Void> handle = taskScope.submit(
                    TaskSpec.io("sdd-task-" + id), context -> {
                        context.cancellation().throwIfCancellationRequested();
                        runTask(task, epoch, completionStamp, resume);
                        return null;
                    });
            runHandles.put(id, handle);
            handle.completion().whenComplete((ignored, failure) -> runHandles.remove(id, handle));
            if (!java.util.Objects.equals(runEpochs.get(id), epoch)) {
                handle.cancel();
            }
        } catch (RejectedExecutionException e) {
            applyOutcomeIfCurrent(task, epoch, SddOutcome.failed("任务执行器已关闭"));
        }
    }

    private void runTask(SddManagedTask task, long epoch, String completionStamp, boolean resume) {
        String id = task.id;
        SddTaskRunner runner;
        try {
            TaskContext context = new TaskContext(
                    task.id, task.title, task.description, task.workDir,
                    task.capabilities == null ? "auto" : task.capabilities);
            SddProgress progress = new ProgressAdapter(task);
            var gate = interactionPort != null
                    ? new PortReviewGate(interactionPort) : new AutoApproveReviewGate();
            runner = new SddTaskRunner(context, settings, skillRuntime,
                    (phase, in, out) -> recordTokens(task, phase, in, out), gate, progress,
                    completionStamp, workflowService, jdbc, json, processes, workspaceId,
                    agents, modelTasks, workspace)
                    .budgetGuard(() -> isOverBudget(task))
                    .execTimeoutSec(settings.getSddExecTimeoutSeconds())
                    .structuredTimeoutSec(settings.getSddStructuredTimeoutSeconds())
                    .execMaxIters(settings.getSddExecMaxIters());
        } catch (Exception failure) {
            log.error("[SDD] 任务 {} 启动装配失败", id, failure);
            applyOutcomeIfCurrent(task, epoch,
                    SddOutcome.failed("启动装配失败：" + failure.getMessage()));
            return;
        }

        synchronized (this) {
            if (task.state != SddTaskState.RUNNING
                    || !java.util.Objects.equals(runEpochs.get(id), epoch)) {
                log.info("[SDD] 任务 {} 在启动装配期间被 {}，放弃本次启动", id, task.state);
                runner.close();
                return;
            }
            running.put(id, runner);
        }

        SddOutcome outcome;
        try {
            outcome = resume ? runner.resume() : runner.run();
        } catch (CancellationException cancelled) {
            outcome = SddOutcome.cancelled();
        } catch (Exception failure) {
            log.error("[SDD] 任务 {} 运行异常", id, failure);
            outcome = SddOutcome.failed("运行异常：" + failure.getMessage());
        } finally {
            running.remove(id, runner);
            runner.close();
        }
        applyOutcomeIfCurrent(task, epoch, outcome);
    }

    /**
     * 能力按需裁剪：capabilities=auto 时由 AgentCompiler 根据 CapabilityBinding
     * 模型调用预判任务所需工具组，仅把命中的能力注册进执行体 toolkit，避免全量 schema 撑大
     * 上下文、无关工具（如给桌面代码任务挂浏览器）勾走执行体注意力。
     *
     * <p>始终保底 system+command（实现循环离不开文件读写与编译核验）；用户显式指定的能力
     * 清单原样尊重；路由失败/降级时回退 auto（全量，与改造前行为一致）。</p>
     */
    /**
     * 工作目录留空时，默认 {@code {user.dir}/task/{id}/} 并建目录、回填任务（与 SkillManager/WorkspaceManager
     * 基于 {@code user.dir} 建子目录的先例一致）。已指定目录则原样保留。
     */
    private void ensureWorkDir(SddManagedTask task) {
        Path dir;
        if (task.workDir != null && !task.workDir.isBlank()) {
            dir = ProjectAccessPolicy.resolveProjectPath(task.workDir);
            if (!Files.isDirectory(dir)) {
                throw new IllegalStateException("严格项目隔离：任务工作目录不存在或位于项目外");
            }
            task.workDir = dir.toString();
            return;
        }
        dir = ProjectAccessPolicy.projectRoot().resolve("task").resolve(task.id);
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new IllegalStateException("创建项目内默认任务目录失败: " + e.getMessage(), e);
        }
        task.workDir = dir.toAbsolutePath().normalize().toString();
        log.info("[SDD] 任务 {} 未指定工作目录，默认使用 {}", task.id, task.workDir);
    }

    /** 暂停：取消运行线程并置 PAUSED（change 已落盘，可后续 resume 续跑）。 */
    public synchronized void pause(String id) {
        stopRun(id);
        SddManagedTask t = get(id);
        if (t != null && t.state.isActive()) setState(t, SddTaskState.PAUSED, "已暂停");
    }

    /**
     * 运行时替换前把所有运行中任务安全停在 PAUSED；OpenSpec 真相层已落盘，用户可在新运行时
     * 接管后继续。包括仍处于能力路由/装配窗口、尚未放入 running map 的任务。
     */
    public synchronized void suspendForRuntimeTransition() {
        java.util.List<String> activeIds = tasks.stream()
                .filter(task -> task.state == SddTaskState.RUNNING
                        || runEpochs.containsKey(task.id)
                        || running.containsKey(task.id))
                .map(task -> task.id)
                .toList();
        activeIds.forEach(this::pause);
        log.info("[SDD] 运行时切换前已暂停 {} 个运行中任务", activeIds.size());
    }

    public synchronized void cancel(String id) {
        stopRun(id);
        ToolConfirmationManager.clearTaskAllowlist(id);
        SddManagedTask t = get(id);
        if (t != null) setState(t, SddTaskState.CANCELLED, "已取消");
    }

    public synchronized void delete(String id) {
        cancel(id);
        SddManagedTask task = get(id);
        if (task != null) {
            store.deleteArtifacts(task);
        }
        tasks.removeIf(t -> t.id.equals(id));
        saveAll();
    }

    private void stopRun(String id) {
        runEpochs.remove(id);
        SddTaskRunner runner = running.remove(id);
        if (runner != null) runner.cancel();
        TaskHandle<Void> handle = runHandles.remove(id);
        if (handle != null) handle.cancel();
    }

    // ==================== 启动恢复 ====================

    private void recoverInterrupted() {
        for (SddManagedTask t : list()) {
            if (t.state == SddTaskState.RUNNING) {
                // 上次退出时仍在跑 → 降为 PAUSED 等用户手动续跑（不自动烧 token）
                setState(t, SddTaskState.PAUSED, "[启动恢复] 上次未正常结束，已暂停，可手动续跑");
            }
        }
    }

    // ==================== 结果回填 / 进度 / token ====================

    private void applyOutcome(SddManagedTask task, SddOutcome outcome) {
        SddTaskState st = switch (outcome.result()) {
            case COMPLETED -> SddTaskState.COMPLETED;
            case NEEDS_HUMAN -> SddTaskState.NEEDS_HUMAN;
            case CANCELLED -> SddTaskState.CANCELLED;
            case FAILED -> SddTaskState.FAILED;
        };
        // 刷新进度缓存
        refreshProgress(task);
        // 真正终结（非 NEEDS_HUMAN 可续跑态）时清掉任务级"同意全部"授权
        if (st != SddTaskState.NEEDS_HUMAN) ToolConfirmationManager.clearTaskAllowlist(task.id);
        setState(task, st, outcome.message());
        log.info("[SDD] 任务 {} 终态: {} — {}", task.id, st, outcome.message());
        // 任务成功完成 = "复杂任务成功"的最强学习信号：异步蒸馏可沉淀的工作流经验（失败静默）
        if (st == SddTaskState.COMPLETED) {
            distillSkillFromTask(task, outcome);
        }
    }

    /** 仅接纳当前启动世代的结果，防止暂停后旧线程把 PAUSED 覆盖成 CANCELLED。 */
    private synchronized void applyOutcomeIfCurrent(SddManagedTask task, long epoch, SddOutcome outcome) {
        if (!java.util.Objects.equals(runEpochs.get(task.id), epoch)
                || task.state != SddTaskState.RUNNING) {
            log.info("[SDD] 忽略任务 {} 的迟到结果（epoch={}，当前状态={}）",
                    task.id, epoch, task.state);
            return;
        }
        runEpochs.remove(task.id, epoch);
        applyOutcome(task, outcome);
    }

    /** 从完成的托管任务异步蒸馏技能（程序性记忆，借鉴 hermes-agent；失败静默不影响任务终态） */
    private void distillSkillFromTask(SddManagedTask task, SddOutcome outcome) {
        try {
            StringBuilder summary = new StringBuilder();
            summary.append("终态：").append(outcome.message() == null ? "完成" : outcome.message());
            readChange(task.id).ifPresent(ch -> {
                if (ch.proposal() != null) {
                    summary.append("\n动机：").append(ch.proposal().why())
                            .append("\n变更：").append(ch.proposal().whatChanges());
                }
            });
            skillCurator.distillFromSddTask(task.title, task.description, summary.toString())
                    .subscribe();
        } catch (Exception e) {
            log.debug("[SDD] 任务完成后技能蒸馏触发失败（忽略）: {}", e.getMessage());
        }
    }

    private void refreshProgress(SddManagedTask task) {
        try {
            SpecStore store = new SpecStore(task.workDir, jdbc, workspaceId);
            String slug = SpecPaths.makeSlug(task.id, task.title);
            OpenSpecChange ch = store.readChange(slug, task.id, task.title);
            task.progress = ch.progressPercent();
        } catch (Exception ignore) {
            // 进度缓存刷新失败不致命
        }
    }

    private synchronized void recordTokens(SddManagedTask task, String phase, long in, long out) {
        boolean wasOver = isOverBudget(task);
        task.totalInputTokens += in;
        task.totalOutputTokens += out;
        if (phase != null && !phase.isBlank()) {
            task.phaseInputTokens.merge(phase, in, Long::sum);
            task.phaseOutputTokens.merge(phase, out, Long::sum);
        }
        if (!wasOver && isOverBudget(task)) {
            // 越限只在跨过阈值的一刻提示一次；编排器在阶段/循环边界据闸门停为待人工
            log.warn("[SDD] 任务 {} token 预算耗尽：已用 {} / 预算 {}", task.id,
                    task.totalInputTokens + task.totalOutputTokens, task.tokenBudget);
            notifyLog(task.id, task.title, "⚠ token 预算已耗尽（已用 "
                    + (task.totalInputTokens + task.totalOutputTokens) + " / 预算 "
                    + task.tokenBudget + "），将在当前步骤结束后停为待人工");
        }
        notifyTaskChanged(task);
    }

    /** 任务级累计预算判断：预算 ≤0 表示不限制。 */
    private static boolean isOverBudget(SddManagedTask t) {
        return t.tokenBudget > 0 && t.totalInputTokens + t.totalOutputTokens >= t.tokenBudget;
    }

    /** 调整任务 token 预算（超限停为待人工后，调高预算即可续跑；0 表示不限制）。 */
    public synchronized void updateTokenBudget(String id, long newBudget) {
        SddManagedTask t = get(id);
        if (t == null) return;
        t.tokenBudget = Math.max(0, newBudget);
        saveAll();
        notifyTaskChanged(t);
    }

    private synchronized void setState(SddManagedTask task, SddTaskState state, String result) {
        task.state = state;
        if (result != null) task.result = result;
        task.updatedAt = nowStamp();
        saveAll();
        notifyTaskChanged(task);
    }

    /** 当前时间戳（yyyy-MM-dd HH:mm:ss），用于刷新 updatedAt 以便从索引看出任务最近变更时间。 */
    private static String nowStamp() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private void ensureOpen() {
        if (closed.get()) throw new RejectedExecutionException("SDD 任务运行时已关闭");
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        suspendForRuntimeTransition();
        runHandles.values().forEach(TaskHandle::cancel);
        runHandles.clear();
        listeners.clear();
    }

    /** 读出某任务的 change 全貌（供 UI 渲染 proposal/spec/tasks 勾选进度）。 */
    public Optional<OpenSpecChange> readChange(String id) {
        SddManagedTask t = get(id);
        if (t == null) return Optional.empty();
        try {
            SpecStore store = new SpecStore(t.workDir, jdbc, workspaceId);
            return Optional.of(store.readChange(SpecPaths.makeSlug(t.id, t.title), t.id, t.title));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ==================== 进度适配 ====================

    /** 把 {@link SddProgress} 回调翻译成对 {@link SddManagedTask} 的更新 + 监听通知。 */
    private final class ProgressAdapter implements SddProgress {
        private final SddManagedTask task;
        ProgressAdapter(SddManagedTask task) { this.task = task; }

        @Override public void phase(String phaseName) {
            notifyLog(task.id, task.title, "[阶段] " + phaseName);
        }
        @Override public void log(String message) {
            notifyLog(task.id, task.title, message);
        }
        @Override public void progress(int percent) {
            task.progress = percent;
            notifyTaskChanged(task);
        }
    }

    private void notifyTaskChanged(SddManagedTask task) {
        for (SddTaskListener listener : listeners) {
            try {
                listener.onTaskChanged(task);
            } catch (RuntimeException failure) {
                log.debug("[SDD] 任务监听回调失败: {}", failure.getMessage());
            }
        }
    }

    private void notifyLog(String taskId, String taskTitle, String message) {
        for (SddTaskListener listener : listeners) {
            try {
                listener.onLog(taskId, taskTitle, message);
            } catch (RuntimeException failure) {
                log.debug("[SDD] 日志监听回调失败: {}", failure.getMessage());
            }
        }
    }
}
