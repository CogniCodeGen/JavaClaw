package com.javaclaw.loop;

import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.loop.model.Cadence;
import com.javaclaw.loop.model.CarryForwardMode;
import com.javaclaw.loop.model.IterationResult;
import com.javaclaw.loop.model.LoopSpec;
import com.javaclaw.loop.model.Decision;
import com.javaclaw.loop.model.LoopStatus;
import com.javaclaw.loop.model.StopConditions;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 决策漏斗的离线单测：用脚本化假执行体驱动 {@link LoopController}，
 * 不碰任何模型，验证「完成 / 达到轮数上限 / 无进展」三种终态。
 */
class LoopControllerTest {

    // ==================== 测试替身 ====================

    /** 按脚本逐轮返回回复的假执行体。 */
    private static final class ScriptedRunner implements LoopIterationRunner {
        private final List<String> replies;
        private final AtomicInteger cursor = new AtomicInteger(0);

        ScriptedRunner(List<String> replies) {
            this.replies = replies;
        }

        @Override
        public IterationResult runOnce(String prompt, ConversationCallbacks callbacks) {
            int i = cursor.getAndIncrement();
            // 脚本用尽后重复最后一条（供轮数/无进展场景持续产出）
            String reply = replies.get(Math.min(i, replies.size() - 1));
            return IterationResult.ok(reply, 0L, 0L);
        }
    }

    /** 收集终态状态事件的回调。 */
    private static final class CapturingCallbacks implements ConversationCallbacks {
        final List<LoopStatus> statuses = new ArrayList<>();
        boolean completed;
        Throwable error;

        @Override
        public void onEvent(ConversationEvent event) {
            if (event instanceof ConversationEvent.Custom c
                    && LoopConstants.EVENT_STATUS_KIND.equals(c.kind())
                    && c.payload() instanceof LoopStatus s) {
                statuses.add(s);
            }
        }

        @Override
        public void onTerminal(ConversationOutcome outcome) {
            if (outcome instanceof ConversationOutcome.Completed) completed = true;
            else if (outcome instanceof ConversationOutcome.Failed failed) error = failed.error();
        }

        LoopStatus last() {
            return statuses.get(statuses.size() - 1);
        }
    }

    // ==================== 辅助 ====================

    private static String doneLine(String tail) {
        return tail + "\n" + LoopConstants.JUDGMENT_LINE_PREFIX + LoopConstants.JUDGMENT_DONE + "｜依据：已完成";
    }

    private static String notDoneLine(String tail) {
        return tail + "\n" + LoopConstants.JUDGMENT_LINE_PREFIX + LoopConstants.JUDGMENT_NOT_DONE + "｜剩余：还差一点";
    }

    /** 无客观准则、不用验收员、自驱、无墙钟限制的默认规格。 */
    private static LoopSpec spec(StopConditions conditions) {
        return new LoopSpec("测试目标", null, List.of(),
                Cadence.selfPaced(), conditions, CarryForwardMode.LAST_RESULT_ONLY, false);
    }

    private static LoopController controller(LoopSpec spec, LoopIterationRunner runner) {
        return LoopController.create(spec, runner, null, CompletionJudge.CONSERVATIVE_DENY,
                Clock.systemUTC());
    }

    // ==================== 用例 ====================

    @Test
    void 第三轮自报完成则判定完成() {
        ScriptedRunner runner = new ScriptedRunner(List.of(
                notDoneLine("干活A"), notDoneLine("干活B"), doneLine("干活C")));
        CapturingCallbacks cb = new CapturingCallbacks();

        controller(spec(new StopConditions(25, 0L, 0L)), runner).run(cb);

        assertTrue(cb.completed, "循环应正常结束");
        assertEquals(3, cb.statuses.size(), "应发出三轮状态");
        assertEquals(Decision.DONE, cb.last().decision());
        assertEquals(3, cb.last().iteration());
    }

    @Test
    void 达到最大轮数上限则停止() {
        // 每轮输出不同（避免先触发无进展），且始终「未完成」
        ScriptedRunner runner = new ScriptedRunner(List.of(
                notDoneLine("A"), notDoneLine("B"), notDoneLine("C"), notDoneLine("D")));
        CapturingCallbacks cb = new CapturingCallbacks();

        controller(spec(new StopConditions(2, 0L, 0L)), runner).run(cb);

        assertTrue(cb.completed);
        assertEquals(Decision.STOP, cb.last().decision());
        // 轮数上限 2：第 3 轮轮前被护栏拦截，终态轮次归属实际跑过的最后一轮（第 2 轮），
        // 报 3 会让用户以为第 3 轮跑过
        assertEquals(2, cb.last().iteration());
        assertTrue(cb.last().reason().contains("迭代"), "停止原因应为达到轮数上限");
    }

    /** 直接按脚本返回 IterationResult 的假执行体（可携带结构化汇报）。 */
    private static final class ResultScriptedRunner implements LoopIterationRunner {
        private final List<IterationResult> results;
        private final AtomicInteger cursor = new AtomicInteger(0);

        ResultScriptedRunner(List<IterationResult> results) {
            this.results = results;
        }

        @Override
        public IterationResult runOnce(String prompt, ConversationCallbacks callbacks) {
            int i = cursor.getAndIncrement();
            return results.get(Math.min(i, results.size() - 1));
        }
    }

    /** 带结构化汇报的一轮（未完成）。 */
    private static IterationResult reported(String reply, String remaining, long delaySeconds) {
        return IterationResult.ok(reply, 0L, 0L, List.of(),
                new com.javaclaw.loop.model.LoopReport(false, reply, remaining, delaySeconds, "等外部条件"));
    }

    /** 带结构化汇报的一轮（完成）。 */
    private static IterationResult reportedDone(String reply) {
        return IterationResult.ok(reply, 0L, 0L, List.of(),
                new com.javaclaw.loop.model.LoopReport(true, reply, "", 0L, ""));
    }

    @Test
    void 结构化汇报替代哨兵驱动完成() {
        // 回复正文完全没有哨兵判定行，done 信号仅来自 loop_report 工具的结构化汇报
        ResultScriptedRunner runner = new ResultScriptedRunner(List.of(
                reported("第一轮干活", "还差收尾", 0L),
                reportedDone("全部搞定")));
        CapturingCallbacks cb = new CapturingCallbacks();

        controller(spec(new StopConditions(25, 0L, 0L)), runner).run(cb);

        assertTrue(cb.completed);
        assertEquals(Decision.DONE, cb.last().decision());
        assertEquals(2, cb.last().iteration());
    }

    @Test
    void 定时轮询节奏豁免停滞_逐轮重复检查不被误杀() {
        // INTERVAL 节奏：每轮做同样的检查、报同样的「剩余」——轮询的本分，不该被判原地打转。
        // 无豁免时第 3 轮即被 NO_PROGRESS 拦停；豁免后应活到轮数上限（第 4 轮轮前拦截）。
        IterationResult poll = reported("查了一次构建，还是红的", "等构建变绿", 0L);
        ResultScriptedRunner runner = new ResultScriptedRunner(List.of(poll, poll, poll, poll));
        CapturingCallbacks cb = new CapturingCallbacks();
        LoopSpec spec = new LoopSpec("盯着构建直到变绿", null, List.of(),
                Cadence.interval(0), new StopConditions(3, 0L, 0L),
                CarryForwardMode.LAST_RESULT_ONLY, false);

        controller(spec, runner).run(cb);

        assertTrue(cb.completed);
        assertEquals(Decision.STOP, cb.last().decision());
        assertTrue(cb.last().reason().contains("迭代"), "轮询循环应由轮数上限兜底，而非无进展误杀");
        assertEquals(3, cb.last().iteration(), "终态轮次归属实际跑过的最后一轮（上限 3）");
    }

    @Test
    void 自报延迟的等待轮不计停滞() {
        // SELF_PACED 下执行体经 loop_report 自报「等 1 秒再查」：声明式等待轮跳过停滞计数，
        // 连续三轮一模一样的等待也不该触发 NO_PROGRESS，最终等到完成
        IterationResult wait = reported("还没好，等会儿再查", "等外部任务完成", 1L);
        ResultScriptedRunner runner = new ResultScriptedRunner(List.of(
                wait, wait, wait, reportedDone("外部任务完成，收工")));
        CapturingCallbacks cb = new CapturingCallbacks();

        controller(spec(new StopConditions(25, 0L, 0L)), runner).run(cb);

        assertTrue(cb.completed);
        assertEquals(Decision.DONE, cb.last().decision());
        assertEquals(4, cb.last().iteration(), "三轮声明式等待不应累计停滞，第 4 轮完成");
    }

    @Test
    void 用量额度按轮累计_耗尽则轮前拦停() {
        // 每轮 6 tokens、额度 10：第 1、2 轮跑完累计 12 ≥ 10，第 3 轮轮前被拦。
        // 用量来自 IterationResult 逐轮累加（不读全局会话计数，并行聊天不污染循环预算）
        java.util.function.IntFunction<IterationResult> round = i -> IterationResult.ok(
                "第" + i + "轮干活", 3L, 3L, List.of(),
                new com.javaclaw.loop.model.LoopReport(false, "干活", "剩余" + i, 0L, ""));
        ResultScriptedRunner runner = new ResultScriptedRunner(List.of(
                round.apply(1), round.apply(2), round.apply(3)));
        CapturingCallbacks cb = new CapturingCallbacks();

        controller(spec(new StopConditions(25, 10L, 0L)), runner).run(cb);

        assertTrue(cb.completed);
        assertEquals(Decision.STOP, cb.last().decision());
        assertTrue(cb.last().reason().contains("额度"), "应因用量额度拦停");
        assertEquals(2, cb.last().iteration(), "第 3 轮轮前拦截，终态轮次归属实际跑过的第 2 轮");
        assertEquals(12L, cb.last().tokensUsed(), "只累计循环自身的轮次用量");
    }

    @Test
    void 输出包含类准则对累计输出匹配_早前轮内容不因收尾未复述而丢失() {
        // 准则要求输出包含「报表已生成」：第 1 轮说了，第 2 轮收尾只报完成、不复述——
        // 若只匹配本轮输出，准则在收尾轮会「丢失」导致 done 永不可达
        LoopSpec spec = new LoopSpec("生成报表", null,
                List.of(new com.javaclaw.agent.goal.SuccessCriterion("output_contains", "报表已生成")),
                Cadence.selfPaced(), new StopConditions(25, 0L, 0L),
                CarryForwardMode.LAST_RESULT_ONLY, false);
        ResultScriptedRunner runner = new ResultScriptedRunner(List.of(
                reported("干完了大半，报表已生成，还差校对", "校对报表", 0L),
                reportedDone("校对完成，收工")));
        CapturingCallbacks cb = new CapturingCallbacks();

        controller(spec, runner).run(cb);

        assertTrue(cb.completed);
        assertEquals(Decision.DONE, cb.last().decision(), "第 1 轮已满足的输出准则应粘住，第 2 轮判完成");
        assertEquals(2, cb.last().iteration());
    }

    /** 可脚本化的假验收员：达成判定恒通过（配合哨兵），进展仲裁结果可配置。 */
    private static final class ScriptedJudge implements CompletionJudge {
        private final boolean pardonStall;

        ScriptedJudge(boolean pardonStall) {
            this.pardonStall = pardonStall;
        }

        @Override
        public Verdict goalMet(String goal, String transcript) {
            return new Verdict(true, "测试：达成判定通过");
        }

        @Override
        public Verdict criterionMet(com.javaclaw.agent.goal.SuccessCriterion c, String t) {
            return new Verdict(true, "测试：准则判定通过");
        }

        @Override
        public Verdict progressMade(String goal, String previousOutput, String currentOutput) {
            return new Verdict(pardonStall, pardonStall ? "测试：确认有实质进展" : "测试：维持停滞");
        }
    }

    /** 启用验收员的规格（无客观准则的纯文本目标场景）。 */
    private static LoopSpec judgeSpec() {
        return new LoopSpec("润色文案", null, List.of(),
                Cadence.selfPaced(), new StopConditions(25, 0L, 0L),
                CarryForwardMode.LAST_RESULT_ONLY, true);
    }

    @Test
    void 停滞仲裁赦免_持续打磨的循环能活到完成() {
        // 前三轮「剩余」原样重复 → 确定性判定连续停滞；仲裁赦免后循环存活到第四轮完成。
        // （若无仲裁：第 3 轮即累计 2 次停滞被 NO_PROGRESS 拦停，到不了第 4 轮）
        String polishing = notDoneLine("打磨中");
        ScriptedRunner runner = new ScriptedRunner(List.of(
                polishing, polishing, polishing, doneLine("打磨完成")));
        CapturingCallbacks cb = new CapturingCallbacks();

        LoopController.create(judgeSpec(), runner, null, new ScriptedJudge(true),
                Clock.systemUTC()).run(cb);

        assertTrue(cb.completed);
        assertEquals(Decision.DONE, cb.last().decision());
        assertEquals(4, cb.last().iteration(), "仲裁赦免后应活到第 4 轮完成");
    }

    @Test
    void 停滞仲裁维持_空转循环仍被拦停() {
        // 同样的停滞脚本，仲裁维持原判 → 第 3 轮累计 2 次停滞照常拦停，护栏不被仲裁架空
        String spinning = notDoneLine("打磨中");
        ScriptedRunner runner = new ScriptedRunner(List.of(
                spinning, spinning, spinning, doneLine("不会到达")));
        CapturingCallbacks cb = new CapturingCallbacks();

        LoopController.create(judgeSpec(), runner, null, new ScriptedJudge(false),
                Clock.systemUTC()).run(cb);

        assertTrue(cb.completed);
        assertEquals(Decision.STOP, cb.last().decision());
        assertEquals(3, cb.last().iteration(), "仲裁维持原判时第 3 轮应被无进展护栏拦停");
        assertTrue(cb.last().reason().contains("无进展"));
    }

    /** 带一条 output_contains 客观准则的规格（宽限/连败交互场景用）。 */
    private static LoopSpec criteriaSpec() {
        return new LoopSpec("产出含关键词的结果", null,
                List.of(new com.javaclaw.agent.goal.SuccessCriterion("output_contains", "关键词")),
                Cadence.selfPaced(), new StopConditions(25, 0L, 0L),
                CarryForwardMode.LAST_RESULT_ONLY, false);
    }

    @Test
    void 宽限期内单次瞬时失败不被误判收敛不了() {
        // 回归缺陷：准则已全满足、处于沉默宽限时来一次瞬时失败（超时/异常）——失败轮
        // 因 !threw 跳过宽限分支、连败计数才 1 不触发护栏、报不出剩余清单，落到兜底
        // stop(NO_PROGRESS)：离完成一步之遥的循环以「收敛不了」错误终态收场，
        // 且连败护栏承诺的「先给一次重试机会」从未兑现。修复后失败轮应直接重试续行
        ResultScriptedRunner runner = new ResultScriptedRunner(List.of(
                IterationResult.ok("干完了，输出含关键词", 0L, 0L),   // 准则满足+沉默 → 宽限 1
                IterationResult.failed(),                              // 瞬时失败 → 应重试而非 NO_PROGRESS
                IterationResult.ok("还是那个关键词结果", 0L, 0L),      // 宽限重新累计 1
                IterationResult.ok("保持关键词结果", 0L, 0L)));        // 宽限 2 → 客观核验判完成
        CapturingCallbacks cb = new CapturingCallbacks();

        controller(criteriaSpec(), runner).run(cb);

        assertTrue(cb.completed);
        assertEquals(Decision.DONE, cb.last().decision(), "瞬时失败应重试续行，最终按宽限判完成");
        assertEquals(4, cb.last().iteration());
    }

    @Test
    void 被成功宽限轮隔开的两次失败不算连续失败() {
        // 回归缺陷：宽限分支在 postflight 之前 return，成功的宽限轮不重置连败计数——
        // 失败→宽限成功→失败 被当作「连续」两败误触发 CONSECUTIVE_FAILURE，
        // 而此时距宽限判 DONE 只差一轮
        ResultScriptedRunner runner = new ResultScriptedRunner(List.of(
                IterationResult.failed(),                              // 连败 1
                IterationResult.ok("产出了关键词", 0L, 0L),            // 宽限成功轮：应重置连败
                IterationResult.failed(),                              // 应为连败 1（非 2），重试
                IterationResult.ok("关键词结果确认", 0L, 0L),          // 宽限 1
                IterationResult.ok("关键词结果保持", 0L, 0L)));        // 宽限 2 → 完成
        CapturingCallbacks cb = new CapturingCallbacks();

        controller(criteriaSpec(), runner).run(cb);

        assertTrue(cb.completed);
        assertEquals(Decision.DONE, cb.last().decision(), "被成功轮隔开的失败不应累计连败");
        assertEquals(5, cb.last().iteration());
    }

    @Test
    void 验收员用量计入循环预算() {
        // 回归缺陷：TOKEN_BUDGET 只累计执行体 IterationResult 用量，验收员/仲裁的模型
        // 调用完全逃逸——judge=on 下实际花费可达预算数倍循环仍不停。修复后控制器逐轮
        // drainUsedTokens 并入护栏：每轮执行体 0 用量 + 验收员 6 tokens，额度 10 应在
        // 第 3 轮轮前拦停（累计 12 ≥ 10）
        CompletionJudge meteredJudge = new CompletionJudge() {
            @Override
            public Verdict goalMet(String goal, String transcript) {
                return new Verdict(false, "测试：未达成");
            }

            @Override
            public Verdict criterionMet(com.javaclaw.agent.goal.SuccessCriterion c, String t) {
                return new Verdict(false, "测试：未达成");
            }

            @Override
            public Verdict progressMade(String goal, String previousOutput, String currentOutput) {
                return new Verdict(true, "测试：有进展");
            }

            @Override
            public long drainUsedTokens() {
                return 6L; // 每轮验收/仲裁烧 6 tokens
            }
        };
        ScriptedRunner runner = new ScriptedRunner(List.of(
                notDoneLine("A"), notDoneLine("B"), notDoneLine("C")));
        LoopSpec spec = new LoopSpec("测试目标", null, List.of(),
                Cadence.selfPaced(), new StopConditions(25, 10L, 0L),
                CarryForwardMode.LAST_RESULT_ONLY, true);
        CapturingCallbacks cb = new CapturingCallbacks();

        LoopController.create(spec, runner, null, meteredJudge, Clock.systemUTC()).run(cb);

        assertTrue(cb.completed);
        assertEquals(Decision.STOP, cb.last().decision());
        assertTrue(cb.last().reason().contains("额度"), "验收员用量应触发用量额度拦停");
        assertEquals(2, cb.last().iteration(), "第 3 轮轮前被用量护栏拦截");
        assertEquals(12L, cb.last().tokensUsed(), "状态卡累计用量应含验收员消耗");
    }

    @Test
    void 连续无进展则停止() {
        // 每轮完全相同的「未完成」输出 → 第 2、3 轮判无进展，累计到上限停止
        String same = notDoneLine("一模一样");
        ScriptedRunner runner = new ScriptedRunner(List.of(same, same, same, same, same));
        CapturingCallbacks cb = new CapturingCallbacks();

        controller(spec(new StopConditions(25, 0L, 0L)), runner).run(cb);

        assertTrue(cb.completed);
        assertEquals(Decision.STOP, cb.last().decision());
        assertTrue(cb.last().reason().contains("无进展"), "停止原因应为无进展");
        assertEquals(3, cb.last().iteration(), "首轮恒有进展，第 2、3 轮无进展达上限");
    }
}
