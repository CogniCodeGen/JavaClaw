package com.javaclaw.task.sdd;

import com.javaclaw.task.sdd.spec.Capability;
import com.javaclaw.task.sdd.spec.OpenSpecChange;
import com.javaclaw.task.sdd.spec.Proposal;
import com.javaclaw.task.sdd.spec.Scenario;
import com.javaclaw.task.sdd.spec.SpecStore;
import com.javaclaw.task.sdd.spec.TaskItem;
import com.javaclaw.task.sdd.verify.ScenarioVerifier;
import com.javaclaw.task.sdd.verify.VerificationOutcome;
import com.javaclaw.task.sdd.verify.VerifyCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * SDD 任务编排器 —— 取代 v5 的 3881 行 {@code TaskExecutor}。
 *
 * <p>本类是<b>确定性控制流</b>：按 OpenSpec change 生命周期推进六阶段，状态全部经
 * {@link SpecStore} 落在 {@code .agent/openspec/}（markdown 即真相），验收统一走
 * {@link ScenarioVerifier}。所有模型驱动的行为（阶段智能体、critic、命令执行、人机评审）
 * 都是注入的端口（{@link SddAgents}/{@link ReviewGate}/verifier 的 runner&critic），
 * 本类不含任何 LLM 调用，因而可独立测试。</p>
 *
 * <p>核心循环：取 tasks.md 首个未勾项 → 执行（或就地懒拆解）→ 勾选 → 直到全勾 →
 * 综合核验全部能力场景 → 全过则归档完成；有未过则按未过场景补做（追加任务、保留已完成），
 * 受 {@link #maxReplanRounds} 轮上限约束，超限升级人工而非假完成。</p>
 *
 * <p>没有 FAST/SINGLE/MULTI 预分类：深度由 {@code executeTask} 在遇到过大项时请求懒拆解
 * 长出来。没有独立状态机：进度即 tasks.md 勾选折叠。</p>
 *
 * @author JavaClaw
 */
public final class SddOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SddOrchestrator.class);

    private final TaskContext ctx;
    private final SpecStore store;
    private final ScenarioVerifier verifier;
    private final SddAgents agents;
    private final ReviewGate gate;
    private final SddProgress progress;

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
                           SddAgents agents, ReviewGate gate, SddProgress progress) {
        this.ctx = ctx;
        this.store = store;
        this.verifier = verifier;
        this.agents = agents;
        this.gate = gate;
        this.progress = progress == null ? SddProgress.NOOP : progress;
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
        progress.log("token 预算已耗尽，停止推进");
        return SddOutcome.needsHuman("token 预算已耗尽，需人工介入：调高预算后可续跑");
    }

    // ==================== 主流程 ====================

    public SddOutcome run() {
        String slug = ctx.slug();
        try {
            if (overBudget()) return budgetStop();

            // 阶段 1-2：澄清 + 提案（+ 第一道评审）
            Proposal proposal = clarifyProposeWithReview(slug);
            if (cancelled) return SddOutcome.cancelled();
            if (proposal == null) return SddOutcome.needsHuman("提案多轮未获用户确认");
            if (overBudget()) return budgetStop();

            // 阶段 3：规格
            progress.phase("规格");
            List<Capability> caps = agents.specify(ctx, proposal);
            store.writeCapabilitySpecs(slug, caps);
            log.info("[SDD] {} 规格产出 {} 个能力", slug, caps.size());
            if (overBudget()) return budgetStop();

            // 阶段 4：设计（按需）
            progress.phase("设计");
            String design = agents.design(ctx, proposal, caps);
            if (design != null && !design.isBlank()) store.writeDesign(slug, design);
            if (overBudget()) return budgetStop();

            // 阶段 5：任务拆解（+ 第二道评审）
            if (!planWithReview(slug, proposal, caps, design)) {
                return cancelled ? SddOutcome.cancelled() : SddOutcome.needsHuman("计划多轮未获用户确认");
            }

            // 阶段 6：实现循环 + 综合验收 + 有界补做
            return implementAndAccept(slug, proposal, caps, design);
        } catch (Exception e) {
            log.error("[SDD] {} 编排异常", slug, e);
            return SddOutcome.failed("编排异常：" + e.getMessage());
        }
    }

    /**
     * 从既有 change 续跑 —— 恢复中断任务的入口。读 {@code .agent/openspec/changes/{slug}/}：
     * 若已拆解出 tasks 则跳过澄清/规格/计划，直接进实现+验收循环（实现循环天然从首个未勾项继续）；
     * 否则回退到从头 {@link #run()}。
     */
    public SddOutcome resume() {
        String slug = ctx.slug();
        try {
            if (overBudget()) return budgetStop();
            OpenSpecChange change = store.readChange(slug, ctx.id(), ctx.title());
            if (change.tasks().isEmpty()) {
                progress.log("既有 change 尚未拆解任务，从头开始");
                return run();
            }
            progress.log("从既有 change 续跑（当前 " + change.progressPercent() + "%）");
            return implementAndAccept(slug, change.proposal(), change.capabilities(), change.design());
        } catch (Exception e) {
            log.error("[SDD] {} 续跑异常", slug, e);
            return SddOutcome.failed("续跑异常：" + e.getMessage());
        }
    }

    // ==================== 阶段 1-2：提案 + 评审 ====================

    private Proposal clarifyProposeWithReview(String slug) {
        String feedback = null;
        for (int round = 1; round <= maxReviewRounds && !cancelled; round++) {
            progress.phase("提案");
            Proposal proposal = agents.clarifyAndPropose(ctx, feedback);
            store.writeProposal(slug, ctx.title(), proposal);
            ReviewGate.Decision d = gate.reviewProposal(ctx, proposal);
            if (d.approved()) {
                progress.log("提案已确认（第 " + round + " 轮）");
                return proposal;
            }
            feedback = d.feedback();
            progress.log("提案被驳回：" + nz(feedback));
        }
        return null;
    }

    // ==================== 阶段 5：任务 + 评审 ====================

    private boolean planWithReview(String slug, Proposal proposal, List<Capability> caps, String design) {
        String feedback = null;
        for (int round = 1; round <= maxReviewRounds && !cancelled; round++) {
            progress.phase("任务拆解");
            List<TaskItem> tasks = agents.planTasks(ctx, proposal, caps, design, feedback);
            store.writeTasks(slug, tasks);
            OpenSpecChange change = store.readChange(slug, ctx.id(), ctx.title());
            ReviewGate.Decision d = gate.reviewPlan(ctx, change);
            if (d.approved()) {
                progress.log("计划已确认（第 " + round + " 轮，共 " + tasks.size() + " 项）");
                return true;
            }
            feedback = d.feedback();
            progress.log("计划被驳回：" + nz(feedback));
        }
        return false;
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
            VerifyCache cache = VerifyCache.load(ctx.workDir(), slug);
            cache.syncFingerprint(cache.fingerprint());
            int reused = 0;
            List<VerificationOutcome> outcomes = new ArrayList<>(scenarios.size());
            for (int i = 0; i < scenarios.size(); i++) {
                if (cancelled) return SddOutcome.cancelled();
                if (overBudget()) return budgetStop();
                Scenario sc = scenarios.get(i);
                String ck = VerifyCache.key(sc);
                String cached = cache.reuse(ck);
                if (cached != null) {
                    reused++;
                    outcomes.add(VerificationOutcome.pass(sc, true, "缓存命中（源码未变，上次已通过）：" + cached));
                    continue;
                }
                progress.log("[验收] (" + (i + 1) + "/" + scenarios.size() + ") " + sc.title());
                log.info("[SDD] {} 验收 {}/{}: {}", slug, i + 1, scenarios.size(), sc.title());
                VerificationOutcome o = verifier.verify(sc);
                outcomes.add(o);
                if (o.passed()) cache.recordPass(ck, o.detail());
            }
            cache.save();
            if (reused > 0) {
                progress.log("[验收] 复用缓存通过 " + reused + "/" + scenarios.size() + " 个场景（源码未变）");
                log.info("[SDD] {} 验收复用缓存 {}/{}", slug, reused, scenarios.size());
            }
            List<Scenario> failed = outcomes.stream()
                    .filter(o -> !o.passed()).map(VerificationOutcome::scenario).toList();

            if (failed.isEmpty()) {
                progress.phase("归档");
                store.archive(slug, completionStamp);
                progress.progress(100);
                progress.log("全部 " + scenarios.size() + " 个验收场景通过，已归档完成");
                return SddOutcome.completed("验收通过（" + scenarios.size() + " 场景），已归档进 specs/");
            }

            progress.log("验收未通过：" + failed.size() + "/" + scenarios.size() + " 场景未达标，进入第 "
                    + replan + " 轮补做");
            // 保留已完成工作，按未过场景追加补做项
            List<String> fixes = agents.remediate(ctx, failed, change);
            if (fixes == null || fixes.isEmpty()) {
                return SddOutcome.needsHuman("验收未通过且智能体无法给出补做项（"
                        + failed.size() + " 场景未达标），需人工介入");
            }
            store.appendTasks(slug, fixes);
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
            progress.progress(change.progressPercent());
            TaskItem next = change.nextPendingTask().orElse(null);
            if (next == null) {
                progress.log("所有实现项已完成");
                return null;
            }
            List<TaskItem> done = change.tasks().stream().filter(TaskItem::done).toList();
            SddAgents.ExecutionResult r;
            try {
                r = agents.executeTask(ctx, next, done, caps);
                sameItemFailures = 0;
            } catch (Exception e) {
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
                progress.log("⚠ 实现项 #" + next.index() + " 执行失败，自动重试一次：" + e.getMessage());
                continue;
            }

            if (r.wantsSplit()) {
                store.splitTask(slug, next.index(), r.splitInto());
                progress.log("实现项 #" + next.index() + "「" + next.action()
                        + "」过大，懒拆解为 " + r.splitInto().size() + " 个子项");
                continue;
            }

            // 每项的廉价确定性自检：声明的产出文件是否真的落盘（不调 LLM）。
            // 未达标只记警告并仍勾选推进——真正的权威门是综合场景核验（会触发补做），
            // 故此处不"屏蔽重做"也不卡死循环。
            String fileCheck = checkDeclaredFiles(next);
            store.checkTask(slug, next.index());
            progress.log("✓ 完成实现项 #" + next.index() + "「" + next.action() + "」"
                    + (fileCheck.isEmpty() ? "" : "（注意：" + fileCheck + "）"));
        }
        if (guard >= maxLoopIters) {
            log.warn("[SDD] {} 实现循环达迭代上限 {}，提前退出本轮", slug, maxLoopIters);
            progress.log("实现循环达迭代上限，转入验收");
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
}
