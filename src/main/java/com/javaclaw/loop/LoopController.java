package com.javaclaw.loop;

import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.loop.model.CompletionCheck;
import com.javaclaw.loop.model.Decision;
import com.javaclaw.loop.model.IterationResult;
import com.javaclaw.loop.model.LoopSpec;
import com.javaclaw.loop.model.LoopStatus;
import com.javaclaw.loop.model.LoopVerdict;
import com.javaclaw.loop.model.StopReason;
import com.javaclaw.task.sdd.verify.CommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 循环控制器：确定性引擎，零 LLM 调用，承载「完成 / 继续 / 停止」的决策漏斗与主循环。
 *
 * <p>模型相关能力全部经端口注入（{@link LoopIterationRunner} 干活、{@link CompletionJudge}
 * 验收、{@link CommandRunner} 跑命令），因此本类可用脚本化假实现离线单测。</p>
 *
 * <p>每轮的裁决顺序（漏斗，命中即定）：</p>
 * <ol>
 *   <li>轮前预算护栏（{@link Guardrails#preflight(int)}）——命中直接停，连这轮都不开；</li>
 *   <li>跑完本轮 → 完成判定（{@link CompletionChecker}）通过 → 完成；</li>
 *   <li>轮后护栏（连败/空转）命中 → 停；</li>
 *   <li>仍有剩余且已确认在前进 → 继续；</li>
 *   <li>否则（说不出剩余、也没完成）→ 判收敛不了、停。</li>
 * </ol>
 *
 * <p>线程模型：{@link #run(ConversationCallbacks)} 阻塞在调用线程（由服务层放到
 * {@code boundedElastic} 上跑）；{@link #cancel()} 可跨线程调用，会同时置取消标志并唤醒
 * 定时节奏的轮间等待。</p>
 */
public final class LoopController {

    private static final Logger log = LoggerFactory.getLogger(LoopController.class);

    private final LoopSpec spec;
    private final LoopIterationRunner runner;
    private final CompletionChecker checker;
    private final ProgressGate progress;
    private final Guardrails guards;
    private final CarryContext carry;
    /** 验收/仲裁端口（未启用时为保守空实现，仲裁一律维持停滞原判）。 */
    private final CompletionJudge judge;

    /** 取消闩：定时节奏轮间等待时挂在此上，cancel() 会 countDown 立即唤醒。 */
    private final CountDownLatch cancelLatch = new CountDownLatch(1);

    /**
     * 最后一次完成判定的准则进度：预算停止/取消/异常路径没有当轮 check 可用，沿用这两个值——
     * 硬编码 0/0 会让终态状态卡把用户看着的「已满足 2/3」进度整行抹掉（UI 在 total==0 时隐藏准则行）。
     * 首轮尚未判定时 total 取准则数，满足数为 0，如实呈现「0/N」。
     */
    private int lastSatisfied;
    private int lastTotal;

    /**
     * 「全部客观准则已满足、但执行体沉默（无提议）」的连续轮数计数。达到
     * {@link LoopConstants#CRITERIA_MET_SILENT_GRACE_ROUNDS} 即以客观核验判完成，
     * 避免 INTERVAL（免停滞计数）下无限空等确认。任何其它裁决路径都清零（只累计连续沉默）。
     */
    private int criteriaMetSilentRounds;

    private LoopController(LoopSpec spec, LoopIterationRunner runner, CompletionChecker checker,
                           ProgressGate progress, Guardrails guards, CarryContext carry,
                           CompletionJudge judge) {
        this.spec = spec;
        this.runner = runner;
        this.checker = checker;
        this.progress = progress;
        this.guards = guards;
        this.carry = carry;
        this.judge = judge;
        this.lastTotal = spec.criteria().size();
    }

    /**
     * 工厂：从规格 + 端口装配好全部确定性内件。
     *
     * @param spec          循环规格
     * @param runner        单轮执行端口
     * @param commandRunner 命令执行端口（command_exit_zero 准则用；无则传 null）
     * @param judge         模型验收端口（不启用可传 {@link CompletionJudge#CONSERVATIVE_DENY}）
     * @param clock         时钟
     */
    public static LoopController create(LoopSpec spec, LoopIterationRunner runner,
                                        CommandRunner commandRunner, CompletionJudge judge,
                                        Clock clock) {
        CompletionJudge effectiveJudge = judge == null ? CompletionJudge.CONSERVATIVE_DENY : judge;
        CarryContext carry = new CarryContext(spec.goalPrompt(), spec.carryForward());
        CriterionVerifier verifier =
                new CriterionVerifier(commandRunner, effectiveJudge, spec.workDir(), spec.useJudge());
        CompletionChecker checker =
                new CompletionChecker(spec.criteria(), verifier, effectiveJudge, spec.useJudge());
        ProgressGate progress = new ProgressGate();
        Guardrails guards = new Guardrails(spec.stopConditions(), clock);
        return new LoopController(spec, runner, checker, progress, guards, carry, effectiveJudge);
    }

    /** 取消当前循环（可跨线程）。 */
    public void cancel() {
        guards.cancel();
        cancelLatch.countDown();
    }

    /**
     * 跑完整个循环，阻塞直到达成完成 / 触发停止 / 异常。终态经 {@code callbacks} 通告。
     */
    public void run(ConversationCallbacks callbacks) {
        int iteration = 1;
        // 本轮的进度阶段是否已开启未收尾：取消/异常出口据此补发收尾事件，
        // 否则 UI 上该轮永远停在「进行中」转圈，与旁边「已停止」的终态卡自相矛盾
        boolean stageOpen = false;
        try {
            for (; ; iteration++) {
                // 1) 轮前预算护栏：拦截发生在第 N+1 轮开跑之前，终态轮次归属实际跑过的
                // 最后一轮（N）——报 N+1 会让状态卡/日志声称一轮从未跑过的轮次。
                // 首轮即被拦（如启动瞬间取消）时钳制为 1：UI 轮次口径从 1 起算，「第 0 轮」无法解释
                StopReason pre = guards.preflight(iteration);
                if (pre != null) {
                    emitStop(callbacks, Math.max(1, iteration - 1), pre, pre.description(),
                            lastSatisfied, lastTotal);
                    callbacks.onTerminal(ConversationOutcome.completed());
                    return;
                }

                // 2) 跑一轮
                emitIterationStart(callbacks, iteration);
                stageOpen = true;
                IterationResult result = runner.runOnce(carry.assemble(iteration), callbacks);
                guards.addRoundUsage(result.inputTokens() + result.outputTokens()); // 只累计循环自己的用量（含失败/被取消轮）
                // 取消可能恰落在本轮执行中：漏斗里的命令核验（分钟级真实副作用）与验收员
                // 模型调用在取消后一步都不该再走，立即以 CANCELLED 终态收束
                if (guards.isCancelled()) {
                    closeStage(callbacks, iteration, ConversationEvent.Progress.Status.DONE, "已取消");
                    stageOpen = false;
                    emitStop(callbacks, iteration, StopReason.CANCELLED,
                            StopReason.CANCELLED.description(), lastSatisfied, lastTotal);
                    callbacks.onTerminal(ConversationOutcome.completed());
                    return;
                }
                String previousOutput = carry.lastOutput(); // 在并入前留存「上一轮」，供停滞仲裁对比
                carry.record(result); // 先并入上下文，供核验/接力看到本轮结果（失败轮在内部被跳过）

                // 3) 决策漏斗
                // 声明式等待轮：执行体明示在等外部条件（loop_report 报了延迟），或用户本就选了
                // 定时轮询节奏——重复同样的检查动作是轮询的本分，不参与停滞计数，也不必仲裁；
                // 必须先于完成判定裁出：等待轮上外部世界可能已变化，命令/文件类核验缓存要失效重测
                boolean waitRound = (result.report() != null && result.report().isWaitRound())
                        || spec.cadence().mode() == com.javaclaw.loop.model.CadenceMode.INTERVAL;
                CompletionCheck check = checker.check(result, carry, waitRound);
                lastSatisfied = check.satisfied();
                lastTotal = check.total();
                // 完成核验（命令真跑 / 验收员模型调用）可能是分钟级开销，取消常恰落在其执行期间：
                // 一返回就复检，先于停滞仲裁（另一次模型调用）与决策漏斗短路收束，
                // 否则取消要拖到下一轮 awaitBeforeNext 才被观测，白烧一次仲裁模型调用
                if (guards.isCancelled()) {
                    closeStage(callbacks, iteration, ConversationEvent.Progress.Status.DONE, "已取消");
                    stageOpen = false;
                    emitStop(callbacks, iteration, StopReason.CANCELLED,
                            StopReason.CANCELLED.description(), lastSatisfied, lastTotal);
                    callbacks.onTerminal(ConversationOutcome.completed());
                    return;
                }
                boolean madeProgress = progress.madeProgress(check, result);
                if (!waitRound) {
                    madeProgress = arbitrateStallIfNeeded(iteration, madeProgress, check,
                            previousOutput, result);
                }
                // 验收员/停滞仲裁是循环自己发起的模型调用（本轮 check/arbitrate 内可能多次），
                // 其用量必须并入预算护栏与状态卡——否则 judge=on 下实际花费可达执行体用量的
                // 数倍而 TOKEN_BUDGET 永不触发、状态卡数字系统性低估
                guards.addRoundUsage(judge.drainUsedTokens());
                LoopVerdict verdict = decide(result, check, madeProgress, waitRound);
                long nextDelay = resolveNextDelay(result);
                emitIterationEnd(callbacks, iteration, result, verdict);
                stageOpen = false;
                // 每轮裁决必须落日志：终态（完成/停止）不经 emitStop，缺这行会导致排障时
                // 无法从日志判断循环如何结束（曾因此靠旁证定位问题）
                log.info("循环第 {} 轮裁决：{}（准则 {}/{}，进展={}，等待轮={}，下轮延迟={}s）— {}",
                        iteration, verdict.decision(), check.satisfied(), check.total(),
                        madeProgress, waitRound, nextDelay, verdict.message());
                emitStatus(callbacks, iteration, verdict, check, guards.tokensUsed(),
                        verdict.decision() == Decision.CONTINUE ? nextDelay : 0L);

                switch (verdict.decision()) {
                    case DONE, STOP -> {
                        log.info("循环结束：共 {} 轮，终态={}，用量={} tokens",
                                iteration, verdict.decision(), guards.tokensUsed());
                        callbacks.onTerminal(ConversationOutcome.completed());
                        return;
                    }
                    case CONTINUE -> {
                        if (!awaitBeforeNext(nextDelay)) {
                            emitStop(callbacks, iteration, StopReason.CANCELLED,
                                    StopReason.CANCELLED.description(), check.satisfied(), check.total());
                            callbacks.onTerminal(ConversationOutcome.completed());
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("循环执行异常", e);
            // 异常出口也要发终态状态：否则聊天记录里的循环状态卡永远停在「进行中」，
            // 与循环已终止（单活跃闸已释放）的事实矛盾。已开启未收尾的轮次阶段
            // （如 checker.check 内命令核验抛异常）同样要补 ERROR 收尾，不留转圈残影
            if (stageOpen) {
                closeStage(callbacks, iteration, ConversationEvent.Progress.Status.ERROR, e.getMessage());
            }
            emitStop(callbacks, iteration, StopReason.ERROR,
                    StopReason.ERROR.description() + "：" + e.getMessage(), lastSatisfied, lastTotal);
            callbacks.onTerminal(ConversationOutcome.failed(e));
        }
    }

    /**
     * 停滞仲裁：确定性进展判定说「停滞」时，在记入停滞计数<b>之前</b>给一次复核机会。
     *
     * <p>只在三条件交点触发（把仲裁成本压到最低）：① 确定性判定为停滞；② 目标无结构化准则
     * （有准则时确定性证据足够硬，停滞可信，不赦免）；③ 用户启用了验收员。专治纯文本目标
     * 「持续打磨被相似度误杀」的假停滞；仲裁判无进展则停滞成立，异常/不确定一律不赦免。
     * 异常/超时轮不仲裁：本轮无有效产出，拿上轮输出对比空串是白烧一次模型调用，仲裁误判
     * met=true 还会把失败轮洗成「有进展」、抹掉空转计数（与 {@link ProgressGate} 对失败轮
     * 直接判停滞的口径一致）。</p>
     *
     * @return 仲裁后的进展结论
     */
    private boolean arbitrateStallIfNeeded(int iteration, boolean madeProgress,
                                           CompletionCheck check, String previousOutput,
                                           IterationResult result) {
        if (madeProgress || result.threw() || check.total() > 0 || !spec.useJudge()) {
            return madeProgress;
        }
        CompletionJudge.Verdict verdict =
                judge.progressMade(spec.goalPrompt(), previousOutput, result.finalReply());
        if (verdict.met()) {
            log.info("循环第 {} 轮停滞仲裁：赦免（确认有实质进展）— {}", iteration, verdict.reason());
            return true;
        }
        log.info("循环第 {} 轮停滞仲裁：维持停滞 — {}", iteration, verdict.reason());
        return false;
    }

    /**
     * 决策漏斗：把完成/继续/停止三判合成唯一裁决。
     *
     * <p>注意：{@code postflight} 会推进护栏内部的连败/空转计数，故每轮只在此调用一次。</p>
     */
    private LoopVerdict decide(IterationResult result, CompletionCheck check,
                               boolean madeProgress, boolean waitRound) {
        if (check.done()) {
            criteriaMetSilentRounds = 0;
            return LoopVerdict.done("全部成功准则通过且执行体确认完成");
        }
        // 「准则全满足·执行体沉默」宽限必须先于轮后护栏：这类轮次在 ProgressGate 看来是「无进展」
        // （无新行动、纯文本雷同），若先走 postflight，自驱节奏下停滞计数（阈值 2）会在宽限攒够之前
        // 抢先判 NO_PROGRESS——目标明明已客观达成却收「收敛不了」失败卡。沉默 = 无 loop_report 也无
        // 哨兵的主动「未完成」提议（hasRemaining=false）；失败/超时轮不算沉默（无有效产出，交由下方
        // 连败护栏计数）。宽限轮不推进护栏计数（decide 至多再宽限 1 轮即完成，预算护栏仍逐轮兜底）
        if (!result.threw() && check.total() > 0 && check.missing().isEmpty()
                && !progress.hasRemaining(check, result)) {
            // 宽限轮是成功轮：必须重置连败计数（宽限提前 return 不经 postflight，若不重置，
            // 被成功宽限轮隔开的两次失败会被当作「连续」失败误触发 CONSECUTIVE_FAILURE——
            // 此时距宽限判 DONE 只差一轮，循环却以失败终态收场）
            guards.noteSuccess();
            if (++criteriaMetSilentRounds >= LoopConstants.CRITERIA_MET_SILENT_GRACE_ROUNDS) {
                criteriaMetSilentRounds = 0;
                return LoopVerdict.done("全部成功准则持续满足，执行体虽未按协议确认，据客观核验判定完成");
            }
            return LoopVerdict.continueLoop("全部准则已满足，等待执行体确认完成（宽限 "
                    + criteriaMetSilentRounds + "/" + LoopConstants.CRITERIA_MET_SILENT_GRACE_ROUNDS + "）");
        }
        criteriaMetSilentRounds = 0; // 宽限只累计「连续」沉默轮，任何其它形态都清零
        StopReason post = guards.postflight(result, madeProgress, waitRound);
        if (post != null) {
            return LoopVerdict.stop(post);
        }
        // 失败/超时轮到此说明连败护栏尚未拉闸（刻意先给一次重试机会）：直接重试续行，
        // 不再落入下方「无剩余 ⇒ NO_PROGRESS」兜底——失败轮无有效产出，报不出剩余清单是常态，
        // 用收敛判据处置一次瞬时错误，会让准则已全满足、只差确认的循环以「收敛不了」错误终态收场
        if (result.threw()) {
            return LoopVerdict.continueLoop("本轮执行失败，按连败护栏给予重试机会");
        }
        if (waitRound && result.report() != null && !result.report().done()) {
            // 执行体主动汇报仍在等待（done=false）：尊重其判断继续等。done=true 但核验未过
            // （谎报完成被客观核验拆穿）不走此路——落到下方 hasRemaining 分支展示未满足清单，
            // 否则 INTERVAL 下状态卡永远显示「等待中：等待外部条件」，误导排障方向
            String why = result.report().reason().isBlank() ? "等待外部条件" : result.report().reason();
            return LoopVerdict.continueLoop("等待中：" + why);
        }
        if (progress.hasRemaining(check, result)) {
            // 未满足数以「真实准则数」check.total() 为准，而非 missing().size()：无结构化准则的自由
            // 目标下，CompletionChecker 会塞一条合成 missing 占位（"执行体尚未确认完成"），照 missing
            // 计数会印出「尚有 1 项未满足」谈论并不存在的准则；有准则但全满足仅差确认时 missing 为空。
            // 两种情形统一用「执行体尚未确认完成」，仅在确有未满足准则时才报具体条数
            int unmet = check.total() > 0 ? check.missing().size() : 0;
            return LoopVerdict.continueLoop(unmet > 0
                    ? "尚有 " + unmet + " 项未满足，继续推进"
                    : "执行体尚未确认完成，继续推进");
        }
        return LoopVerdict.stop(StopReason.NO_PROGRESS, "既未完成也无明确剩余，判定收敛不了");
    }

    /**
     * 解析下一轮延迟（仿 Claude Code ScheduleWakeup 的自节奏）：
     * INTERVAL 节奏用户定的固定间隔说了算；SELF_PACED 采纳执行体经 loop_report
     * 自报的建议延迟（按上限截断，防报出离谱的等待），未汇报则立即开下一轮。
     */
    private long resolveNextDelay(IterationResult result) {
        if (spec.cadence().mode() == com.javaclaw.loop.model.CadenceMode.INTERVAL) {
            return spec.cadence().delaySeconds();
        }
        if (result.report() != null && result.report().nextDelaySeconds() > 0) {
            return Math.min(result.report().nextDelaySeconds(),
                    LoopConstants.MAX_SELF_PACED_DELAY_SECONDS);
        }
        return spec.cadence().delaySeconds();
    }

    /**
     * 轮间等待：延迟为 0 立即返回；否则挂在取消闩上等待（cancel() 会立即唤醒）。
     *
     * @return 是否可以继续下一轮（false 表示等待期间被取消）
     */
    private boolean awaitBeforeNext(long delaySeconds) {
        if (guards.isCancelled()) {
            return false;
        }
        if (delaySeconds <= 0) {
            return true;
        }
        try {
            boolean cancelledDuringWait = cancelLatch.await(delaySeconds, TimeUnit.SECONDS);
            return !cancelledDuringWait && !guards.isCancelled();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ==================== 事件发射（喂给 UI 的进度/状态流） ====================

    private void emitIterationStart(ConversationCallbacks cb, int iteration) {
        // 轮分隔线：直发外层回调（不经 runner 截获，不会混进 finalReply），
        // 让多轮回复在同一气泡内以 markdown 分隔可读呈现
        if (iteration > 1) {
            cb.onEvent(new ConversationEvent.Reply(
                    "\n\n---\n\n**🔁 第 " + iteration + " 轮**\n\n"));
        }
        cb.onEvent(new ConversationEvent.Progress(
                LoopConstants.EVENT_STAGE_PREFIX + iteration,
                LoopConstants.EVENT_STAGE_LABEL_PREFIX + iteration + LoopConstants.EVENT_STAGE_LABEL_SUFFIX,
                ConversationEvent.Progress.Status.RUNNING,
                null));
    }

    /** 轮次收尾：把该轮的进度阶段标记为完成/失败（否则 UI 上永远停在「进行中」）。 */
    private void emitIterationEnd(ConversationCallbacks cb, int iteration,
                                  IterationResult result, LoopVerdict verdict) {
        closeStage(cb, iteration,
                result.threw() ? ConversationEvent.Progress.Status.ERROR
                               : ConversationEvent.Progress.Status.DONE,
                verdict.message());
    }

    /** 发出指定轮次进度阶段的收尾事件（正常轮末 / 取消 / 异常三个出口共用）。 */
    private void closeStage(ConversationCallbacks cb, int iteration,
                            ConversationEvent.Progress.Status status, String message) {
        cb.onEvent(new ConversationEvent.Progress(
                LoopConstants.EVENT_STAGE_PREFIX + iteration,
                LoopConstants.EVENT_STAGE_LABEL_PREFIX + iteration + LoopConstants.EVENT_STAGE_LABEL_SUFFIX,
                status, message));
    }

    private void emitStatus(ConversationCallbacks cb, int iteration, LoopVerdict verdict,
                            CompletionCheck check, long tokensUsed, long nextDelaySeconds) {
        cb.onEvent(new ConversationEvent.Custom(
                LoopConstants.EVENT_STATUS_KIND,
                new LoopStatus(iteration, verdict.decision(), verdict.message(),
                        check.satisfied(), check.total(), tokensUsed, nextDelaySeconds)));
    }

    private void emitStop(ConversationCallbacks cb, int iteration, StopReason reason,
                          String message, int satisfied, int total) {
        cb.onEvent(new ConversationEvent.Custom(
                LoopConstants.EVENT_STATUS_KIND,
                new LoopStatus(iteration, Decision.STOP, message, satisfied, total,
                        guards.tokensUsed(), 0L)));
        log.info("循环停止：轮次={} 原因={} 说明={}", iteration, reason, message);
    }
}
