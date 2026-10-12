package com.javaclaw.task.sdd;

import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.task.sdd.spec.Capability;
import com.javaclaw.task.sdd.spec.OpenSpecChange;
import com.javaclaw.task.sdd.spec.Proposal;
import com.javaclaw.task.sdd.spec.Scenario;
import com.javaclaw.task.sdd.spec.SpecStore;
import com.javaclaw.task.sdd.spec.SpecStore.PreparationStage;
import com.javaclaw.task.sdd.spec.TaskItem;
import com.javaclaw.task.sdd.verify.ScenarioVerifier;
import com.javaclaw.task.sdd.verify.VerificationOutcome;
import com.javaclaw.task.sdd.verify.VerifyCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * SDD 任务编排器 —— 取代 v5 的 3881 行 {@code TaskExecutor}。
 *
 * <p>本类是<b>确定性控制流</b>：按 OpenSpec change 生命周期推进六阶段，状态全部经
 * {@link SpecStore} 落在 H2 OpenSpec 文档表，验收统一走
 * {@link ScenarioVerifier}。所有模型驱动的行为（阶段智能体、critic、命令执行、人机评审）
 * 都是注入的端口（{@link SddAgents}/{@link ReviewGate}/verifier 的 runner&critic），
 * 本类不含任何 LLM 调用，因而可独立测试。</p>
 *
 * <p>核心循环：取 H2 tasks.md 文档首个未勾项 → 执行（或就地懒拆解）→ 勾选 → 直到全勾 →
 * 综合核验全部能力场景 → 全过则归档完成；有未过则按未过场景补做（追加任务、保留已完成），
 * 受 {@link #maxReplanRounds} 轮上限约束，超限升级人工而非假完成。</p>
 *
 * <p>没有 FAST/SINGLE/MULTI 预分类：深度由 {@code executeTask} 在遇到过大项时请求懒拆解
 * 长出来。没有独立状态机：进度即 H2 tasks.md 文档勾选折叠。</p>
 *
 * @author JavaClaw
 */
public final class SddOrchestrator {
    private SddAcceptanceEvidence acceptanceEvidence;

    private static final Logger log = LoggerFactory.getLogger(SddOrchestrator.class);

    private final TaskContext ctx;
    private final SpecStore store;
    private final ScenarioVerifier verifier;
    private final SddAgents agents;
    private final ReviewGate gate;
    private final SddProgress progress;
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final String workspaceId;

    /** 单道评审最多返工轮数（提案 / 计划各自）。 */
    private int maxReviewRounds = 3;
    /** 验收未过后的重规划补做轮数上限。 */
    private int maxReplanRounds = 5;
    /** 实现循环单轮迭代硬上限（防失控；正常远小于此）。 */
    private int maxLoopIters = 200;
    /** 完成归档时间戳（由调用方注入，本类不依赖时钟）。 */
    private String completionStamp = "";

    private volatile boolean cancelled = false;

    /**
     * token 预算闸门：返回 true 表示预算已耗尽。由调用方注入累计账目比较逻辑
     * （本类不感知 token 统计），与 {@link #cancelled} 同在阶段/循环边界检查；
     * 触发后以 NEEDS_HUMAN 收束（change 已落盘，调高预算后可续跑），绝不静默继续烧 token。
     */
    private BooleanSupplier budgetExceeded = () -> false;

    public SddOrchestrator(TaskContext ctx, SpecStore store, ScenarioVerifier verifier,
                           SddAgents agents, ReviewGate gate, SddProgress progress,
                           JdbcTemplate jdbc, JsonCodec json, String workspaceId) {
        this.ctx = ctx;
        this.store = store;
        this.verifier = verifier;
        this.agents = agents;
        this.gate = gate;
        this.progress = progress == null ? SddProgress.NOOP : progress;
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    public SddOrchestrator maxReviewRounds(int n) { this.maxReviewRounds = n; return this; }
    public SddOrchestrator maxReplanRounds(int n) { this.maxReplanRounds = n; return this; }
    public SddOrchestrator maxLoopIters(int n) { this.maxLoopIters = n; return this; }
    public SddOrchestrator completionStamp(String s) { this.completionStamp = s; return this; }

    /** 注入 token 预算闸门（空忽略）。 */
    public SddOrchestrator budgetGuard(BooleanSupplier guard) {
        if (guard != null) this.budgetExceeded = guard;
        return this;
    }

    /** 请求取消；在阶段/循环边界生效。 */
    public void cancel() { this.cancelled = true; }

    private boolean overBudget() { return budgetExceeded.getAsBoolean(); }

    /** 预算耗尽的统一收束：停为待人工（非失败），调高预算后可续跑。 */
    private SddOutcome budgetStop() {
        progress.log(SddProgress.LogKind.WARN, "token 预算已耗尽，停止推进");
        return SddOutcome.needsHuman("token 预算已耗尽，需人工介入：调高预算后可续跑");
    }

    // ==================== 主流程 ====================

    public SddOutcome run() {
        SddOutcome stop = prepare();
        return stop == null ? implementPrepared() : stop;
    }

    /**
     * 图执行的前半段真实边界：完成提案、规格、设计和任务拆解。
     *
     * @return {@code null} 表示可以进入实现阶段；非空表示流程需要提前收束
     */
    public SddOutcome prepare() {
        String slug = ctx.slug();
        try {
            if (overBudget()) return budgetStop();
            if (!store.recoverLegacyPreparation(slug)) {
                return SddOutcome.needsHuman("准备阶段恢复快照未能保存");
            }

            // 阶段 1-2：澄清 + 提案（+ 第一道评审）
            Proposal proposal = clarifyProposeWithReview(slug);
            if (cancelled) return SddOutcome.cancelled();
            if (proposal == null) return SddOutcome.needsHuman("提案多轮未获用户确认");
            if (overBudget()) return budgetStop();

            // 阶段 3：规格
            progress.phase("规格");
            List<Capability> caps;
            if (store.preparationComplete(slug, PreparationStage.SPECIFICATIONS)) {
                caps = store.readChange(slug, ctx.id(), ctx.title()).capabilities();
                progress.log("复用已保存的规格");
            } else {
                caps = agents.specify(ctx, proposal);
                if (cancelled) return SddOutcome.cancelled();
                if (!store.writeCapabilitySpecs(slug, caps)) {
                    return SddOutcome.needsHuman("结构化规格缺失或无有效验收场景，无法进入实现阶段");
                }
                checkpoint(slug, PreparationStage.SPECIFICATIONS,
                        store.preparationRevision(new OpenSpecChange(ctx.id(), slug, ctx.title(),
                                proposal, caps, null, List.of()), PreparationStage.SPECIFICATIONS));
            }
            log.info("[SDD] {} 规格产出 {} 个能力", slug, caps.size());
            if (overBudget()) return budgetStop();

            // 阶段 4：设计（按需）
            progress.phase("设计");
            String design;
            if (store.preparationComplete(slug, PreparationStage.DESIGN)) {
                design = store.readChange(slug, ctx.id(), ctx.title()).design();
                progress.log("复用已保存的设计阶段结果");
            } else {
                design = agents.design(ctx, proposal, caps);
                if (cancelled) return SddOutcome.cancelled();
                if (!store.writeDesign(slug, design)) {
                    return SddOutcome.needsHuman("设计阶段结果未能保存");
                }
                checkpoint(slug, PreparationStage.DESIGN,
                        store.preparationRevision(new OpenSpecChange(ctx.id(), slug, ctx.title(),
                                proposal, caps, design, List.of()), PreparationStage.DESIGN));
            }
            if (overBudget()) return budgetStop();

            // 阶段 5：任务拆解（+ 第二道评审）
            if (!planWithReview(slug, proposal, caps, design)) {
                return cancelled ? SddOutcome.cancelled() : SddOutcome.needsHuman("计划多轮未获用户确认");
            }
            return null;
        } catch (Exception e) {
            if (e instanceof com.javaclaw.framework.api.TurnPausedException paused) throw paused;
            log.error("[SDD] {} 编排异常", slug, e);
            return SddOutcome.failed("编排异常：" + e.getMessage());
        }
    }

    /** 图执行的后半段真实边界：从 OpenSpec 真相层读取计划并执行实现、验收和归档。 */
    public SddOutcome implementPrepared() {
        String slug = ctx.slug();
        try {
            if (overBudget()) return budgetStop();
            if (!store.preparationReady(slug)) {
                return SddOutcome.needsHuman("准备阶段尚未完成或审批对应的产物已变更，请继续后重新确认");
            }
            OpenSpecChange change = store.readChange(slug, ctx.id(), ctx.title());
            if (change.tasks().isEmpty()) {
                return SddOutcome.needsHuman("OpenSpec 尚未生成实现任务，无法进入实现阶段");
            }
            if (change.allScenarios().isEmpty()) {
                return SddOutcome.needsHuman("缺少结构化验收场景，无法进入实现阶段");
            }
            return implementAndAccept(slug, change.proposal(), change.capabilities(), change.design());
        } catch (Exception e) {
            if (e instanceof com.javaclaw.framework.api.TurnPausedException paused) throw paused;
            log.error("[SDD] {} 实现与验收异常", slug, e);
            return SddOutcome.failed("实现与验收异常：" + e.getMessage());
        }
    }

    /**
     * 从既有 change 续跑 —— 恢复中断任务的入口。读 H2 中 slug 对应的 OpenSpec 文档：
     * 按已保存且与阶段产物匹配的准备检查点继续；不能用 tasks 存在推断人工评审已通过。
     * 实现循环天然从首个未勾项继续。
     */
    public SddOutcome resume() {
        String slug = ctx.slug();
        try {
            if (overBudget()) return budgetStop();
            OpenSpecChange change = store.readChange(slug, ctx.id(), ctx.title());
            progress.log("从既有 change 续跑（当前 " + change.progressPercent() + "%）");
            return run();
        } catch (Exception e) {
            if (e instanceof com.javaclaw.framework.api.TurnPausedException paused) throw paused;
            log.error("[SDD] {} 续跑异常", slug, e);
            return SddOutcome.failed("续跑异常：" + e.getMessage());
        }
    }

    // ==================== 阶段 1-2：提案 + 评审 ====================

    private Proposal clarifyProposeWithReview(String slug) {
        Proposal saved = store.readChange(slug, ctx.id(), ctx.title()).proposal();
        if (saved != null && store.preparationComplete(slug, PreparationStage.PROPOSAL)) {
            progress.log(SddProgress.LogKind.OK, "复用已确认的提案");
            return saved;
        }
        String feedback = null;
        for (int round = 1; round <= maxReviewRounds && !cancelled; round++) {
            progress.phase("提案");
            boolean reuse = round == 1 && saved != null;
            Proposal proposal = reuse ? saved : agents.clarifyAndPropose(ctx, feedback);
            if (cancelled) return null;
            if (!reuse && !store.writeProposal(slug, ctx.title(), proposal)) {
                throw new com.javaclaw.framework.api.TurnPausedException("提案未能保存，不能进入评审");
            }
            String revision = store.preparationRevision(new OpenSpecChange(ctx.id(), slug, ctx.title(),
                    proposal, List.of(), null, List.of()), PreparationStage.PROPOSAL);
            ReviewGate.Decision d = gate.reviewProposal(ctx, proposal);
            if (cancelled) return null;
            if (d.approved()) {
                checkpoint(slug, PreparationStage.PROPOSAL, revision);
                progress.log(SddProgress.LogKind.OK, "提案已确认（第 " + round + " 轮）");
                return proposal;
            }
            feedback = d.feedback();
            progress.log(SddProgress.LogKind.WARN, "提案被驳回：" + nz(feedback));
        }
        return null;
    }

    // ==================== 阶段 5：任务 + 评审 ====================

    private boolean planWithReview(String slug, Proposal proposal, List<Capability> caps, String design) {
        OpenSpecChange saved = store.readChange(slug, ctx.id(), ctx.title());
        if (!saved.tasks().isEmpty() && store.preparationComplete(slug, PreparationStage.PLAN)) {
            progress.log(SddProgress.LogKind.OK, "复用已确认的计划");
            return true;
        }
        String feedback = null;
        for (int round = 1; round <= maxReviewRounds && !cancelled; round++) {
            progress.phase("任务拆解");
            boolean reuse = round == 1 && !saved.tasks().isEmpty();
            List<TaskItem> tasks = reuse
                    ? saved.tasks() : agents.planTasks(ctx, proposal, caps, design, feedback);
            if (cancelled) return false;
            if (!reuse && !store.writeTasks(slug, tasks)) return false;
            OpenSpecChange change = store.readChange(slug, ctx.id(), ctx.title());
            String revision = store.preparationRevision(change, PreparationStage.PLAN);
            ReviewGate.Decision d = gate.reviewPlan(ctx, change);
            if (cancelled) return false;
            if (d.approved()) {
                checkpoint(slug, PreparationStage.PLAN, revision);
                progress.log(SddProgress.LogKind.OK,
                        "计划已确认（第 " + round + " 轮，共 " + tasks.size() + " 项）");
                return true;
            }
            feedback = d.feedback();
            progress.log(SddProgress.LogKind.WARN, "计划被驳回：" + nz(feedback));
        }
        return false;
    }

    private void checkpoint(String slug, PreparationStage stage, String revision) {
        if (!store.completePreparation(slug, stage, revision)) {
            throw new com.javaclaw.framework.api.TurnPausedException(
                    "阶段检查点未能保存或审批对应的产物已变更：" + stage.name());
        }
    }

    // ==================== 阶段 6：实现 + 验收 + 补做 ====================

    private SddOutcome implementAndAccept(String slug, Proposal proposal,
                                          List<Capability> caps, String design) {
        for (int replan = 1; replan <= maxReplanRounds && !cancelled; replan++) {
            if (overBudget()) return budgetStop();
            progress.phase("实现");
            SddOutcome stop = runImplementLoop(slug, caps);
            if (stop != null) return stop;
            if (cancelled) return SddOutcome.cancelled();
            if (overBudget()) return budgetStop();

            // 综合验收：逐场景核验（可中断 + 预算/取消检查点 + 逐条日志 + 证据缓存复用）
            // —— 不走 verifier.verifyAll 黑盒：那样不响应预算/取消、无逐条日志，多场景时静默长跑、
            //    无法在边界停为 NEEDS_HUMAN（卡 RUNNING 根因）。
            // —— 证据缓存：源码指纹未变且上次已通过的场景直接复用结论、零模型/命令开销
            //    （直击"每次 resume 全量重验"的 token 浪费）；源码一变指纹失配、整体作废、强制重验。
            progress.phase("验收");
            OpenSpecChange change = store.readChange(slug, ctx.id(), ctx.title());
            List<Scenario> scenarios = change.allScenarios();
            if (scenarios.isEmpty()) {
                return SddOutcome.needsHuman("结构化验收场景缺失，不能将空场景视为通过");
            }
            VerifyCache cache = VerifyCache.load(ctx.workDir(), slug, jdbc, json, workspaceId,
                    store.ownerThreadId());
            cache.syncFingerprint(cache.fingerprint());
            int reused = 0;
            List<VerificationOutcome> outcomes = new ArrayList<>(scenarios.size());
            for (int i = 0; i < scenarios.size(); i++) {
                if (cancelled) return SddOutcome.cancelled();
                if (overBudget()) return budgetStop();
                Scenario sc = scenarios.get(i);
                String ck = VerifyCache.key(sc);
                String cached = verifier.cacheEligible(sc) ? cache.reuse(ck) : null;
                if (cached != null) {
                    reused++;
                    outcomes.add(VerificationOutcome.pass(sc, true, "缓存命中（源码未变，上次已通过）：" + cached));
                    continue;
                }
                progress.log("[验收] (" + (i + 1) + "/" + scenarios.size() + ") " + sc.title());
                log.info("[SDD] {} 验收 {}/{}: {}", slug, i + 1, scenarios.size(), sc.title());
                VerificationOutcome o = verifier.verify(sc);
                outcomes.add(o);
                if (o.passed() && verifier.cacheEligible(sc)) cache.recordPass(ck, o.detail());
            }
            cache.save();
            if (reused > 0) {
                progress.log("[验收] 复用缓存通过 " + reused + "/" + scenarios.size() + " 个场景（源码未变）");
                log.info("[SDD] {} 验收复用缓存 {}/{}", slug, reused, scenarios.size());
            }
            List<Scenario> failed = outcomes.stream()
                    .filter(o -> !o.passed()).map(VerificationOutcome::scenario).toList();

            if (failed.isEmpty()) {
                com.javaclaw.framework.api.TaskResult result = acceptanceEvidence == null
                        ? com.javaclaw.framework.api.TaskResult.unverified("SDD 验收阶段结束，未取得关联可信收据")
                        : acceptanceEvidence.accept(outcomes, () -> cancelled);
                if (cancelled) return SddOutcome.cancelled();
                progress.phase("归档");
                if (!store.archive(slug, completionStamp)) {
                    return SddOutcome.needsHuman("验收已通过，但规格归档失败，需检查结构化快照");
                }
                progress.progress(100);
                progress.log(SddProgress.LogKind.OK,
                        "全部 " + scenarios.size() + " 个验收场景通过，已归档完成");
                return new SddOutcome(SddOutcome.Result.COMPLETED,
                        "验收通过（" + scenarios.size() + " 场景），已归档进 specs/", result);
            }

            progress.log(SddProgress.LogKind.WARN,
                    "验收未通过：" + failed.size() + "/" + scenarios.size() + " 场景未达标，进入第 "
                    + replan + " 轮补做");
            // 保留已完成工作，按未过场景追加补做项
            List<String> fixes = agents.remediate(ctx, failed, change);
            if (fixes == null || fixes.isEmpty()) {
                return SddOutcome.needsHuman("验收未通过且智能体无法给出补做项（"
                        + failed.size() + " 场景未达标），需人工介入");
            }
            if (!store.appendTasks(slug, fixes)) {
                return SddOutcome.needsHuman("补做任务未能写入结构化快照");
            }
            progress.log("已追加 " + fixes.size() + " 个补做项");
        }
        return cancelled ? SddOutcome.cancelled()
                : SddOutcome.needsHuman("达重规划轮上限（" + maxReplanRounds + "）仍未通过验收，需人工介入");
    }

    /**
     * 实现循环：反复取 tasks.md 首个未勾项推进，直到全部勾选或被取消。
     * 每次迭代要么懒拆解（裂项后继续）、要么执行并勾选——故循环必然推进。
     *
     * <p>单项执行异常（超时/模型错误）重试一次；同一项连续失败 2 次则返回 NEEDS_HUMAN
     * 收束——change 已落盘，已勾项不重做，续跑从该项重试。绝不让单项异常炸掉整个任务。</p>
     *
     * @return 非 null 表示需要提前收束的终态（单项反复失败 → 待人工）；null 表示循环正常结束
     */
    private SddOutcome runImplementLoop(String slug, List<Capability> caps) {
        int guard = 0;
        int sameItemFailures = 0;
        int lastFailedIndex = -1;
        while (!cancelled && !overBudget() && guard++ < maxLoopIters) {
            OpenSpecChange change = store.readChange(slug, ctx.id(), ctx.title());
            if (change.tasks().isEmpty()) {
                return SddOutcome.needsHuman("结构化实现任务缺失，不能按 Markdown 状态继续");
            }
            progress.progress(change.progressPercent());
            TaskItem next = change.nextPendingTask().orElse(null);
            if (next == null) {
                progress.log(SddProgress.LogKind.OK, "所有实现项已完成");
                return null;
            }
            List<TaskItem> done = change.tasks().stream().filter(TaskItem::done).toList();
            String approvedBasis = store.implementationBasis(slug, ctx.description(), change);
            SddAgents.ExecutionResult r;
            try {
                r = agents.executeTask(ctx, next, done, change.capabilities(), approvedBasis);
                sameItemFailures = 0;
            } catch (Exception e) {
                if (e instanceof com.javaclaw.framework.api.TurnPausedException paused) throw paused;
                if (next.index() != lastFailedIndex) {
                    lastFailedIndex = next.index();
                    sameItemFailures = 0;
                }
                sameItemFailures++;
                log.warn("[SDD] {} 实现项 #{} 执行失败（第 {} 次）: {}", slug, next.index(),
                        sameItemFailures, e.getMessage());
                if (sameItemFailures >= 2) {
                    return SddOutcome.needsHuman("实现项 #" + next.index() + "「" + next.action()
                            + "」连续 " + sameItemFailures + " 次失败（" + e.getMessage()
                            + "），已停为待人工；已完成项不受影响，可续跑重试");
                }
                progress.log(SddProgress.LogKind.WARN,
                        "⚠ 实现项 #" + next.index() + " 执行失败，自动重试一次：" + e.getMessage());
                continue;
            }

            if (r.wantsSplit()) {
                if (!store.splitTask(slug, next.index(), r.splitInto())) {
                    return SddOutcome.needsHuman("拆解结果未能写入结构化任务快照");
                }
                progress.log("实现项 #" + next.index() + "「" + next.action()
                        + "」过大，懒拆解为 " + r.splitInto().size() + " 个子项");
                continue;
            }

            // 每项的廉价确定性自检：声明的产出文件是否真的落盘（不调 LLM）。
            // 未达标只记警告并仍勾选推进——真正的权威门是综合场景核验（会触发补做），
            // 故此处不"屏蔽重做"也不卡死循环。
            String fileCheck = checkDeclaredFiles(next);
            if (acceptanceEvidence != null && !acceptanceEvidence.record(next, approvedBasis, r.executionId())) {
                progress.log(SddProgress.LogKind.WARN,
                        "实现项 #" + next.index() + " 缺少可持久关联的可信执行收据；完成进度不代表最终已核验");
            }
            if (!store.checkTask(slug, next.index())) {
                return SddOutcome.needsHuman("实现项完成状态未能写入结构化任务快照");
            }
            progress.log(SddProgress.LogKind.OK,
                    "✓ 完成实现项 #" + next.index() + "「" + next.action() + "」"
                    + (fileCheck.isEmpty() ? "" : "（注意：" + fileCheck + "）"));
        }
        if (guard >= maxLoopIters) {
            log.warn("[SDD] {} 实现循环达迭代上限 {}，提前退出本轮", slug, maxLoopIters);
            progress.log(SddProgress.LogKind.WARN, "实现循环达迭代上限，转入验收");
        }
        return null;
    }

    /** 返回非空字符串表示声明文件存在缺失（警告文案）；声明为空或全部存在返回空串。 */
    private String checkDeclaredFiles(TaskItem t) {
        if (t.files() == null || t.files().isEmpty()) return "";
        for (String f : t.files()) {
            Path p = resolveInWorkDir(f);
            if (p == null || !Files.exists(p)) {
                return "声明产出 " + f + " 未在工作目录落盘";
            }
        }
        return "";
    }

    private Path resolveInWorkDir(String pathLike) {
        try {
            Path p = Path.of(pathLike);
            if (p.isAbsolute()) return p.normalize();
            if (ctx.workDir() == null || ctx.workDir().isBlank()) return null;
            return Path.of(ctx.workDir()).resolve(p).normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    SddOrchestrator acceptanceEvidence(SddAcceptanceEvidence evidence) {
        acceptanceEvidence = evidence;
        return this;
    }
}
