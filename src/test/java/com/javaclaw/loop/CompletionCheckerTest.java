package com.javaclaw.loop;

import com.javaclaw.agent.goal.SuccessCriterion;
import com.javaclaw.loop.model.CarryForwardMode;
import com.javaclaw.loop.model.IterationResult;
import com.javaclaw.loop.model.LoopReport;
import com.javaclaw.task.sdd.verify.CommandRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 核验缓存的失效策略测试：命令类核验（可能分钟级开销）只在「本轮有行动」或
 * 「声明式等待外部变化」时重跑，纯对话轮复用缓存结论。
 */
class CompletionCheckerTest {

    @Test
    void 命令类核验按行动与等待失效_纯对话轮复用缓存() {
        AtomicInteger runs = new AtomicInteger(0);
        CommandRunner counting = (cmd, dir) -> {
            runs.incrementAndGet();
            return new CommandRunner.Result(1, "still failing"); // 恒不通过，保持循环未完成
        };
        CriterionVerifier verifier =
                new CriterionVerifier(counting, CompletionJudge.CONSERVATIVE_DENY, null, false);
        CompletionChecker checker = new CompletionChecker(
                List.of(new SuccessCriterion(LoopConstants.CRITERION_COMMAND_EXIT_ZERO, "mvn test")),
                verifier, CompletionJudge.CONSERVATIVE_DENY, false);
        CarryContext ctx = new CarryContext("让测试通过", CarryForwardMode.LAST_RESULT_ONLY);

        // 第 1 轮：首次必测（有行动）
        IterationResult acted1 = IterationResult.ok("改了代码", 0L, 0L, List.of("edit#a1"));
        ctx.record(acted1);
        checker.check(acted1, ctx, false);
        assertEquals(1, runs.get(), "首轮必测");

        // 第 2 轮：纯对话（零工具调用）——世界没变，复用缓存，不重跑命令
        IterationResult talkOnly = IterationResult.ok("我再想想思路", 0L, 0L, List.of());
        ctx.record(talkOnly);
        checker.check(talkOnly, ctx, false);
        assertEquals(1, runs.get(), "纯对话轮应复用缓存");

        // 第 3 轮：又有行动——世界可能被改，重测
        IterationResult acted2 = IterationResult.ok("又改了代码", 0L, 0L, List.of("edit#b2"));
        ctx.record(acted2);
        checker.check(acted2, ctx, false);
        assertEquals(2, runs.get(), "有行动的轮应重测");

        // 第 4 轮：声明式等待轮（等外部条件，如 CI）——等的就是外部变化，重测
        IterationResult waiting = IterationResult.ok("等 CI", 0L, 0L, List.of(),
                new LoopReport(false, "等待", "等 CI 变绿", 60L, "CI 构建中"));
        ctx.record(waiting);
        checker.check(waiting, ctx, true);
        assertEquals(3, runs.get(), "等待轮应重测（外部状态可能已变化）");
    }

    @Test
    void 定时轮询节奏下每轮都是等待轮_文件类核验缓存逐轮失效() {
        // 回归缺陷：INTERVAL 轮询的主用例（等外部文件/命令状态变化）里，执行体往往只回
        // 「还在等」且不自报延迟——缓存若只认「有行动或自报等待」，外部条件达成也永远测不到
        AtomicInteger runs = new AtomicInteger(0);
        CommandRunner counting = (cmd, dir) -> {
            runs.incrementAndGet();
            return new CommandRunner.Result(1, "not yet");
        };
        CriterionVerifier verifier =
                new CriterionVerifier(counting, CompletionJudge.CONSERVATIVE_DENY, null, false);
        CompletionChecker checker = new CompletionChecker(
                List.of(new SuccessCriterion(LoopConstants.CRITERION_COMMAND_EXIT_ZERO, "test -f report.pdf")),
                verifier, CompletionJudge.CONSERVATIVE_DENY, false);
        CarryContext ctx = new CarryContext("等 report.pdf 出现", CarryForwardMode.LAST_RESULT_ONLY);

        // 连续三轮纯对话（无行动、无自报延迟），但节奏是 INTERVAL → 控制器裁定每轮都是等待轮
        for (int i = 1; i <= 3; i++) {
            IterationResult stillWaiting = IterationResult.ok("还在等第" + i + "轮", 0L, 0L, List.of());
            ctx.record(stillWaiting);
            checker.check(stillWaiting, ctx, true);
            assertEquals(i, runs.get(), "INTERVAL 轮询下每轮应重测世界状态");
        }
    }

    @Test
    void 世界状态核验在执行体提议完成时失效重测_外部达成的目标可判完() {
        // 回归缺陷：目标是「等外部进程产出某产物」——执行体无事可做，每轮零工具调用、
        // 非等待轮、只报 done=true；命令/文件类核验缓存若不认「提议完成」为失效信号，
        // 首轮缓存的否定结论将永远复用，外部条件达成后 done 仍不可达，循环以 NO_PROGRESS 误终
        AtomicInteger runs = new AtomicInteger(0);
        boolean[] worldReady = {false};
        CommandRunner external = (cmd, dir) -> {
            runs.incrementAndGet();
            return new CommandRunner.Result(worldReady[0] ? 0 : 1, worldReady[0] ? "ok" : "not yet");
        };
        CriterionVerifier verifier =
                new CriterionVerifier(external, CompletionJudge.CONSERVATIVE_DENY, null, false);
        CompletionChecker checker = new CompletionChecker(
                List.of(new SuccessCriterion(LoopConstants.CRITERION_COMMAND_EXIT_ZERO, "test -f build/output.jar")),
                verifier, CompletionJudge.CONSERVATIVE_DENY, false);
        CarryContext ctx = new CarryContext("等 build/output.jar 出现即报完成", CarryForwardMode.LAST_RESULT_ONLY);

        // 第 1 轮：提议完成但产物尚未出现——首测不通过
        IterationResult claim1 = IterationResult.ok("产物应该快好了", 0L, 0L, List.of(),
                new LoopReport(true, "等产物", "", 0L, ""));
        ctx.record(claim1);
        assertEquals(false, checker.check(claim1, ctx, false).done());
        assertEquals(1, runs.get(), "首轮必测");

        // 外部进程产出了产物（世界状态被本循环之外的力量改变）
        worldReady[0] = true;

        // 第 2 轮：仍是零行动、非等待轮的 done 提议——终审语义应失效缓存重测并判完成
        IterationResult claim2 = IterationResult.ok("再次确认完成", 0L, 0L, List.of(),
                new LoopReport(true, "确认完成", "", 0L, ""));
        ctx.record(claim2);
        assertEquals(true, checker.check(claim2, ctx, false).done(),
                "提议完成应触发世界状态重测，外部达成的准则不得被陈旧缓存压住");
        assertEquals(2, runs.get(), "提议完成的轮应重测");
    }

    @Test
    void 无准则目标_验收员只在执行体提议完成时触发() {
        // 回归缺陷：`!useJudge || judge.goalMet(...)` 短路失效——执行体自报未完成时也每轮
        // 跑一次完整验收（结论注定被丢弃），全循环时延与 token 成本翻倍
        AtomicInteger judgeCalls = new AtomicInteger(0);
        CompletionJudge counting = new CompletionJudge() {
            @Override
            public Verdict goalMet(String goal, String transcript) {
                judgeCalls.incrementAndGet();
                return new Verdict(true, "达成");
            }

            @Override
            public Verdict criterionMet(SuccessCriterion criterion, String transcript) {
                return new Verdict(false, "不适用");
            }

            @Override
            public Verdict progressMade(String goal, String previousOutput, String currentOutput) {
                return new Verdict(false, "不适用");
            }
        };
        CompletionChecker checker = new CompletionChecker(List.of(), null, counting, true);
        CarryContext ctx = new CarryContext("润色文案", CarryForwardMode.LAST_RESULT_ONLY);

        // 未提议完成的轮：不得触发验收
        IterationResult notDone = IterationResult.ok("继续打磨", 0L, 0L, List.of(),
                new LoopReport(false, "打磨中", "还差结尾", 0L, ""));
        ctx.record(notDone);
        assertEquals(false, checker.check(notDone, ctx, false).done());
        assertEquals(0, judgeCalls.get(), "执行体未提议完成时不应触发验收员（终审语义）");

        // 提议完成的轮：触发终审，验收通过 → 完成
        IterationResult done = IterationResult.ok("完工", 0L, 0L, List.of(),
                new LoopReport(true, "完工", "", 0L, ""));
        ctx.record(done);
        assertEquals(true, checker.check(done, ctx, false).done());
        assertEquals(1, judgeCalls.get(), "提议完成才触发验收员");
    }
}
