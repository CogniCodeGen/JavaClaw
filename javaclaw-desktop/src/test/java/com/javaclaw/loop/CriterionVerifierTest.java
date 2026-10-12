package com.javaclaw.loop;

import com.javaclaw.agent.goal.SuccessCriterion;
import com.javaclaw.loop.model.CarryForwardMode;
import com.javaclaw.task.sdd.verify.CommandRunner;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单条准则核验器测试：重点覆盖 command_exit_zero 的空谓词防护。
 *
 * <p>回归缺陷：command_exit_zero 分支曾不校验 predicate 是否 null/空（artifact_exists 与
 * output_contains 都校验），LLM 漏给命令文本时会对空命令裸跑——要么抛异常终结循环，要么
 * 永远非成功让 done 不可达、烧满轮数上限。修复后空谓词直接判否且不触碰 CommandRunner。</p>
 */
class CriterionVerifierTest {

    private static CarryContext ctx() {
        return new CarryContext("让测试通过", CarryForwardMode.LAST_RESULT_ONLY);
    }

    @Test
    void 命令类空谓词直接判否且不调用命令执行器() {
        AtomicInteger runs = new AtomicInteger(0);
        CommandRunner counting = (cmd, dir) -> {
            runs.incrementAndGet();
            return new CommandRunner.Result(0, "ok"); // 若被误调则会「假通过」
        };
        CriterionVerifier verifier =
                new CriterionVerifier(counting, CompletionJudge.CONSERVATIVE_DENY, null, false);

        for (String blank : new String[]{null, "", "   "}) {
            SuccessCriterion c = new SuccessCriterion(LoopConstants.CRITERION_COMMAND_EXIT_ZERO, blank);
            assertFalse(verifier.verify(c, ctx()), "空谓词命令准则应判否: [" + blank + "]");
        }
        assertEquals(0, runs.get(), "空谓词不得触碰 CommandRunner（不裸跑空命令）");
    }

    @Test
    void 命令类正常谓词按退出码判定并去除首尾空白() {
        AtomicInteger runs = new AtomicInteger(0);
        CommandRunner runner = (cmd, dir) -> {
            runs.incrementAndGet();
            assertEquals("mvn test", cmd, "谓词应去除首尾空白再执行");
            return new CommandRunner.Result(0, "BUILD SUCCESS");
        };
        CriterionVerifier verifier =
                new CriterionVerifier(runner, CompletionJudge.CONSERVATIVE_DENY, null, false);

        SuccessCriterion c = new SuccessCriterion(LoopConstants.CRITERION_COMMAND_EXIT_ZERO, "  mvn test  ");
        assertTrue(verifier.verify(c, ctx()), "退出码 0 应判通过");
        assertEquals(1, runs.get());
    }

    @Test
    void 无命令执行器时命令类准则判否() {
        CriterionVerifier verifier =
                new CriterionVerifier(null, CompletionJudge.CONSERVATIVE_DENY, null, false);
        SuccessCriterion c = new SuccessCriterion(LoopConstants.CRITERION_COMMAND_EXIT_ZERO, "mvn test");
        assertFalse(verifier.verify(c, ctx()), "无 CommandRunner 时应判否而非 NPE");
    }
}
