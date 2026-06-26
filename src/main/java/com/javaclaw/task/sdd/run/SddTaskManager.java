package com.javaclaw.task.sdd.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.agent.router.RoutingResult;
import com.javaclaw.agent.router.ToolRouter;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.skill.SkillManager;
import com.javaclaw.task.sdd.SddOutcome;
import com.javaclaw.task.sdd.SddProgress;
import com.javaclaw.task.sdd.SddTaskRunner;
import com.javaclaw.task.sdd.TaskContext;
import com.javaclaw.task.sdd.gate.AutoApproveReviewGate;
import com.javaclaw.task.sdd.gate.PortReviewGate;
import com.javaclaw.task.sdd.spec.OpenSpecChange;
import com.javaclaw.task.sdd.spec.SpecPaths;
import com.javaclaw.task.sdd.spec.SpecStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SDD 托管任务管理器 —— 取代 v5 的 {@code TaskManager}（生命周期 + 持久化 + 执行驱动）。
 *
 * <p><b>自包含、增量、不耦合 legacy</b>：用 {@link SddTaskRunner} 真正驱动任务，持久化精简
 * {@link SddManagedTask} 索引到 {@code {dataDir}/sdd-tasks.json}，状态/进度全部从 change 目录
 * （{@code .agent/openspec}）派生或由运行结果回填。恢复 = 扫 change 目录续跑首个未勾任务。</p>
 *
 * <p>本类刻意不接入 {@code TaskFacade}/{@code JavaClawApp}，以免改动 legacy 破坏现有构建——
 * 它是"新侧"完整实现，最终切换时把 app 指向它即可（见类注释外的迁移配方）。</p>
 *
 * <p>线程：每个运行中任务占一个后台线程跑同步的 {@code SddTaskRunner.run()/resume()}；
 * {@code cancel} 在阶段/循环边界生效。</p>
 *
 * @author JavaClaw
 */
public final class SddTaskManager {

    private static final Logger log = LoggerFactory.getLogger(SddTaskManager.class);
    public static final String INDEX_FILE = "sdd-tasks.json";

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final List<SddManagedTask> tasks = new ArrayList<>();
    private final Map<String, SddTaskRunner> running = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sdd-task");
        t.setDaemon(true);
        return t;
    });

    private static final SddTaskManager INSTANCE = new SddTaskManager();

    /** 单例访问（与应用其余单例风格一致）。 */
    public static SddTaskManager getInstance() {
        return INSTANCE;
    }

    private Path indexFile;
    private ModelFactory modelFactory;
    private Map<String, Object> capabilityTools = Map.of();
    private SkillManager skills;
    private UserInteractionPort interactionPort;
    private volatile SddTaskListener listener = new SddTaskListener() {};

    // ==================== 配置 / 持久化 ====================

    /**
     * 注入运行期协作者并从 dataDir 加载任务索引。工作区切换时重新调用即可重定向。
     *
     * @param dataDir         任务索引落盘目录（如 {@code {workspace}/data}）
     * @param modelFactory    模型工厂
     * @param capabilityTools 能力→工具表
     * @param skills          技能管理器（可空）
     * @param interactionPort 人机交互端口（评审闸门用；可空 → 自动放行）
     */
    public synchronized void configure(Path dataDir, ModelFactory modelFactory,
                                       Map<String, Object> capabilityTools, SkillManager skills,
                                       UserInteractionPort interactionPort) {
        this.indexFile = dataDir.resolve(INDEX_FILE);
        this.modelFactory = modelFactory;
        this.capabilityTools = capabilityTools == null ? Map.of() : capabilityTools;
        this.skills = skills;
        this.interactionPort = interactionPort;
        loadAll();
        recoverInterrupted();
    }

    /**
     * 工作区切换时的轻量重配：重定向数据目录与刷新模型工厂/能力工具，
     * 复用既有的技能管理器与交互端口（这两者跨工作区不变）。
     */
    public synchronized void reload(Path dataDir, ModelFactory modelFactory,
                                    Map<String, Object> capabilityTools) {
        configure(dataDir, modelFactory, capabilityTools, this.skills, this.interactionPort);
    }

    public void subscribe(SddTaskListener l) {
        this.listener = l == null ? new SddTaskListener() {} : l;
    }

    private synchronized void loadAll() {
        tasks.clear();
        if (indexFile == null || !Files.isRegularFile(indexFile)) return;
        try {
            SddManagedTask[] arr = mapper.readValue(indexFile.toFile(), SddManagedTask[].class);
            for (SddManagedTask t : arr) tasks.add(t);
        } catch (Exception e) {
            log.warn("[SDD] 加载任务索引失败: {}", e.getMessage());
        }
    }

    private synchronized void saveAll() {
        if (indexFile == null) return;
        try {
            Files.createDirectories(indexFile.getParent());
            mapper.writeValue(indexFile.toFile(), tasks);
        } catch (Exception e) {
            log.warn("[SDD] 保存任务索引失败: {}", e.getMessage());
        }
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
        String id = Integer.toHexString((title + description + nowStamp).hashCode() & 0x7fffffff);
        // 避免碰撞
        while (get(id) != null) id = Integer.toHexString((id + "x").hashCode() & 0x7fffffff);
        SddManagedTask t = new SddManagedTask(id, title, description, workDir,
                capabilities == null ? "auto" : capabilities, tokenBudget, notificationChannel, nowStamp);
        tasks.add(t);
        saveAll();
        listener.onTaskChanged(t);
        log.info("[SDD] 已创建任务: {} ({})", title, id);
        return t;
    }

    /**
     * 用轻量模型从需求描述生成简洁标题（失败/超时回退截断描述）。
     *
     * <p><b>务必在后台线程调用</b>：会发起一次模型请求，不可在 JavaFX 应用线程执行。</p>
     */
    public String generateTitle(String description) {
        return SddTaskTitles.generate(modelFactory, description);
    }

    public void start(String id, String completionStamp) {
        launch(id, completionStamp, false);
    }

    public void resume(String id, String completionStamp) {
        launch(id, completionStamp, true);
    }

    private void launch(String id, String completionStamp, boolean resume) {
        SddManagedTask task = get(id);
        if (task == null) return;
        if (running.containsKey(id) || task.state == SddTaskState.RUNNING) {
            log.warn("[SDD] 任务 {} 已在运行，忽略重复启动", id);
            return;
        }
        if (modelFactory == null) {
            log.warn("[SDD] 未配置 modelFactory，无法启动任务 {}", id);
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
        ensureWorkDir(task);
        // 先置 RUNNING 再进后台：能力路由 + 装配在后台线程做（路由有 15s 阻塞上限，不能卡 UI 线程）
        setState(task, SddTaskState.RUNNING, null);

        pool.submit(() -> {
            SddTaskRunner runner;
            try {
                // 能力按需路由（auto → 具体能力清单），失败回退全量
                String resolvedCaps = resolveCapabilities(task);
                AgentConfig cfg = AgentConfig.getInstance();
                TaskContext ctx = new TaskContext(task.id, task.title, task.description, task.workDir, resolvedCaps);
                SddProgress progress = new ProgressAdapter(task);
                var gate = interactionPort != null ? new PortReviewGate(interactionPort) : new AutoApproveReviewGate();
                runner = new SddTaskRunner(ctx, modelFactory, capabilityTools, skills,
                        (phase, in, out) -> recordTokens(task, phase, in, out), gate, progress, completionStamp)
                        .budgetGuard(() -> isOverBudget(task))
                        .execTimeoutSec(cfg.getSddExecTimeoutSeconds())
                        .structuredTimeoutSec(cfg.getSddStructuredTimeoutSeconds())
                        .execMaxIters(cfg.getSddExecMaxIters());
            } catch (Exception e) {
                // 装配失败不能让任务卡死在 RUNNING
                log.error("[SDD] 任务 {} 启动装配失败", id, e);
                applyOutcome(task, SddOutcome.failed("启动装配失败：" + e.getMessage()));
                return;
            }
            synchronized (this) {
                if (task.state != SddTaskState.RUNNING) {
                    // 路由/装配窗口内被暂停或取消，不再起跑
                    log.info("[SDD] 任务 {} 在启动装配期间被 {}，放弃本次启动", id, task.state);
                    return;
                }
                running.put(id, runner);
            }

            SddOutcome outcome;
            // 进入托管场景：放宽工具确认超时（60s→600s）、绑定任务级"同意全部"白名单，
            // 并把工作目录作为风险评估"影响范围"基准（目录内高风险操作可经评估智能体自动放行）
            ToolConfirmationManager.enterManagedTask(id, task.workDir);
            try {
                outcome = resume ? runner.resume() : runner.run();
            } catch (Exception e) {
                log.error("[SDD] 任务 {} 运行异常", id, e);
                outcome = SddOutcome.failed("运行异常：" + e.getMessage());
            } finally {
                ToolConfirmationManager.exitManagedTask();
                running.remove(id);
            }
            applyOutcome(task, outcome);
        });
    }

    /**
     * 能力按需裁剪 —— 复用 {@link ToolRouter} 的按需获取逻辑：capabilities=auto 时用单次轻量
     * 模型调用预判任务所需工具组，仅把命中的能力注册进执行体 toolkit，避免全量 schema 撑大
     * 上下文、无关工具（如给桌面代码任务挂浏览器）勾走执行体注意力。
     *
     * <p>始终保底 system+command（实现循环离不开文件读写与编译核验）；用户显式指定的能力
     * 清单原样尊重；路由失败/降级时回退 auto（全量，与改造前行为一致）。</p>
     */
    private String resolveCapabilities(SddManagedTask task) {
        String caps = task.capabilities == null ? "auto" : task.capabilities.trim();
        // 哨兵 "all"：强制全量装配，跳过 ToolRouter 裁剪。回退到 "auto"，
        // 因为 AgentScopeSddAgents.buildToolkit 视含 "auto" 的能力串为全量注册。
        if (caps.equalsIgnoreCase("all")) {
            log.info("[SDD] 任务 {} 能力=all：跳过路由，强制全量装配", task.id);
            listener.onLog(task.id, task.title, "[路由] 能力=all：跳过裁剪，全量装配");
            return "auto";
        }
        if (!caps.isBlank() && !caps.equalsIgnoreCase("auto")) return caps;
        try {
            ToolRouter router = new ToolRouter(modelFactory.createLightChatModel());
            RoutingResult r = router.route("【托管任务】" + task.title + "\n" + task.description);
            if (r.isFallback() || !r.hasToolGroups()) return "auto";
            Set<String> keys = new LinkedHashSet<>();
            keys.add("system");   // 保底：文件读写
            keys.add("command");  // 保底：编译/脚本核验
            for (String g : r.toolGroups()) {
                switch (g) {
                    case "web" -> keys.add("web");
                    case "email" -> keys.add("email");
                    case "notification" -> keys.add("notification");
                    // coding/system/command 已被保底覆盖；knowledge/evaluator/dynamic_task/mcp 无对应能力工具
                    default -> { }
                }
            }
            String resolved = String.join(",", keys);
            log.info("[SDD] 任务 {} 能力路由: {} → {}", task.id, r.toolGroups(), resolved);
            listener.onLog(task.id, task.title, "[路由] 能力按需裁剪：" + resolved);
            return resolved;
        } catch (Exception e) {
            log.warn("[SDD] 任务 {} 能力路由失败，回退全量: {}", task.id, e.getMessage());
            return "auto";
        }
    }

    /**
     * 工作目录留空时，默认 {@code {user.dir}/task/{id}/} 并建目录、回填任务（与 SkillManager/WorkspaceManager
     * 基于 {@code user.dir} 建子目录的先例一致）。已指定目录则原样保留。
     */
    private void ensureWorkDir(SddManagedTask task) {
        if (task.workDir != null && !task.workDir.isBlank()) return;
        Path dir = Path.of(System.getProperty("user.dir"), "task", task.id);
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            log.warn("[SDD] 创建默认工作目录失败（继续，落盘时会重试建目录）: {}", e.getMessage());
        }
        task.workDir = dir.toAbsolutePath().normalize().toString();
        log.info("[SDD] 任务 {} 未指定工作目录，默认使用 {}", task.id, task.workDir);
    }

    /** 暂停：取消运行线程并置 PAUSED（change 已落盘，可后续 resume 续跑）。 */
    public void pause(String id) {
        SddTaskRunner r = running.remove(id);
        if (r != null) r.cancel();
        SddManagedTask t = get(id);
        if (t != null && t.state.isActive()) setState(t, SddTaskState.PAUSED, "已暂停");
    }

    public void cancel(String id) {
        SddTaskRunner r = running.remove(id);
        if (r != null) r.cancel();
        ToolConfirmationManager.clearTaskAllowlist(id);
        SddManagedTask t = get(id);
        if (t != null) setState(t, SddTaskState.CANCELLED, "已取消");
    }

    public synchronized void delete(String id) {
        cancel(id);
        tasks.removeIf(t -> t.id.equals(id));
        saveAll();
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

    /** 从完成的托管任务异步蒸馏技能（程序性记忆，借鉴 hermes-agent；失败静默不影响任务终态） */
    private void distillSkillFromTask(SddManagedTask task, SddOutcome outcome) {
        if (modelFactory == null) return;
        try {
            StringBuilder summary = new StringBuilder();
            summary.append("终态：").append(outcome.message() == null ? "完成" : outcome.message());
            readChange(task.id).ifPresent(ch -> {
                if (ch.proposal() != null) {
                    summary.append("\n动机：").append(ch.proposal().why())
                            .append("\n变更：").append(ch.proposal().whatChanges());
                }
            });
            new com.javaclaw.skill.curation.SkillCurator(
                    modelFactory, null,
                    com.javaclaw.skill.curation.SkillProposalQueue.getInstance(),
                    ToolConfirmationManager::getPort)
                    .distillFromSddTask(task.title, task.description, summary.toString())
                    .subscribe();
        } catch (Exception e) {
            log.debug("[SDD] 任务完成后技能蒸馏触发失败（忽略）: {}", e.getMessage());
        }
    }

    private void refreshProgress(SddManagedTask task) {
        try {
            SpecStore store = new SpecStore(task.workDir);
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
            listener.onLog(task.id, task.title, "⚠ token 预算已耗尽（已用 "
                    + (task.totalInputTokens + task.totalOutputTokens) + " / 预算 "
                    + task.tokenBudget + "），将在当前步骤结束后停为待人工");
        }
        listener.onTaskChanged(task);
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
        listener.onTaskChanged(t);
    }

    private synchronized void setState(SddManagedTask task, SddTaskState state, String result) {
        task.state = state;
        if (result != null) task.result = result;
        task.updatedAt = nowStamp();
        saveAll();
        listener.onTaskChanged(task);
    }

    /** 当前时间戳（yyyy-MM-dd HH:mm:ss），用于刷新 updatedAt 以便从索引看出任务最近变更时间。 */
    private static String nowStamp() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public void shutdown() {
        running.values().forEach(SddTaskRunner::cancel);
        running.clear();
        pool.shutdownNow();
    }

    /** 读出某任务的 change 全貌（供 UI 渲染 proposal/spec/tasks 勾选进度）。 */
    public Optional<OpenSpecChange> readChange(String id) {
        SddManagedTask t = get(id);
        if (t == null) return Optional.empty();
        try {
            SpecStore store = new SpecStore(t.workDir);
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
            listener.onLog(task.id, task.title, "[阶段] " + phaseName);
        }
        @Override public void log(String message) {
            listener.onLog(task.id, task.title, message);
        }
        @Override public void progress(int percent) {
            task.progress = percent;
            listener.onTaskChanged(task);
        }
    }
}
