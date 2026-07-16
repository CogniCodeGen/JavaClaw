package com.javaclaw.loop;

import com.javaclaw.loop.model.CompletionCheck;
import com.javaclaw.loop.model.IterationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多信号进展判定的专项测试：钉死「进展必须以状态变化为证据，而非文本变化」。
 *
 * <p>背景缺陷：旧实现把「输出文本与上轮不同」当进展证据，LLM 换措辞即可空转刷进展。</p>
 */
class ProgressGateTest {

    /** 有准则目标的核验结果（未完成，satisfied/total 可指定）。 */
    private static CompletionCheck criteria(int satisfied, int total) {
        return new CompletionCheck(false, satisfied, total, List.of("剩余准则"));
    }

    /** 无准则目标的核验结果。 */
    private static CompletionCheck noCriteria() {
        return new CompletionCheck(false, 0, 0, List.of("执行体尚未确认完成"));
    }

    private static IterationResult reply(String text, String... tools) {
        return IterationResult.ok(text, 0L, 0L, List.of(tools));
    }

    private static String withRemaining(String body, String remaining) {
        return body + "\n" + LoopConstants.JUDGMENT_LINE_PREFIX + LoopConstants.JUDGMENT_NOT_DONE
                + "｜已完成：无｜" + LoopConstants.JUDGMENT_REMAINING_MARKER + "：" + remaining;
    }

    @Test
    void 有准则目标_纯文本变化不算进展() {
        ProgressGate gate = new ProgressGate();
        assertTrue(gate.madeProgress(criteria(1, 3), reply("第一轮干活")), "首轮恒有进展");
        // 第二轮：准则没动、没有工具调用，只是换了套说法 → 不算进展
        assertFalse(gate.madeProgress(criteria(1, 3), reply("我换个角度阐述一下这个问题的思路")),
                "有准则目标下，措辞变化不能证明进展");
    }

    @Test
    void 准则振荡不算进展_高水位判定() {
        ProgressGate gate = new ProgressGate();
        assertTrue(gate.madeProgress(criteria(1, 3), reply("修好了", "cmd#a1")));
        // 坏了（1→0）再修好（0→1）：都没有超过高水位 1，且第三轮行动指纹与第二轮不同才有救
        assertFalse(gate.madeProgress(criteria(0, 3), reply("坏了", "cmd#a1")),
                "回退轮：准则下降且行动与上轮相同 → 停滞");
        assertFalse(gate.madeProgress(criteria(1, 3), reply("又修好了", "cmd#a1")),
                "振荡回到高水位不算创新高，且行动重复 → 停滞");
        // 真正创新高（1→2）才算进展
        assertTrue(gate.madeProgress(criteria(2, 3), reply("推进了", "cmd#b2")));
    }

    @Test
    void 复读机行动判停滞_新行动算进展() {
        ProgressGate gate = new ProgressGate();
        assertTrue(gate.madeProgress(criteria(0, 2), reply("干活A", "web#x1", "cmd#y1")));
        // 同一组工具指纹原样重放 → 复读机，哪怕文本完全不同
        assertFalse(gate.madeProgress(criteria(0, 2), reply("完全不同的话术", "web#x1", "cmd#y1")),
                "行动指纹一模一样 → 停滞");
        // 换了新动作 → 进展（即使准则还没动）
        assertTrue(gate.madeProgress(criteria(0, 2), reply("试新方案", "cmd#z9")),
                "新行动指纹 → 有进展");
    }

    @Test
    void 新行动优先于剩余卡住_强肯定证据不被弱否定压过() {
        // 回归缺陷：remainingStuck（弱否定）先于 actedNew（强肯定）返回，执行体每轮真干了
        // 不同的事、只是「剩余」措辞照抄上轮时，正在推进的循环两轮就被 NO_PROGRESS 误杀
        ProgressGate gate = new ProgressGate();
        assertTrue(gate.madeProgress(noCriteria(),
                reply(withRemaining("处理文件A", "继续整理剩余文件"), "edit#a1")));
        assertTrue(gate.madeProgress(noCriteria(),
                reply(withRemaining("处理文件B", "继续整理剩余文件"), "edit#b2")),
                "工具指纹全新（真干了新的事）时，剩余措辞照抄不构成停滞");
        // 无新行动 + 剩余照抄 → 才是停滞
        assertFalse(gate.madeProgress(noCriteria(),
                reply(withRemaining("空聊一轮", "继续整理剩余文件"))),
                "无行动且剩余卡住 → 停滞");
    }

    @Test
    void 错误轮不算进展_且不污染比对基准() {
        // 回归缺陷：失败轮的空回复对任何非空文本相似度为 0，被误判「输出全新」重置停滞计数；
        // 「复读 ↔ 报错」交替可同时绕开停滞与连败两道护栏，空转烧满轮数上限
        ProgressGate gate = new ProgressGate();
        assertTrue(gate.madeProgress(noCriteria(), reply("第一轮正常干活")), "首轮恒有进展");
        assertFalse(gate.madeProgress(noCriteria(), IterationResult.failed()),
                "异常/超时轮不可能构成进展");
        // 错误轮不推进基准：本轮复读与「上一正常轮」比对，相似度高 → 停滞（而非与空串比出全新）
        assertFalse(gate.madeProgress(noCriteria(), reply("第一轮正常干活")),
                "隔着错误轮的复读仍应判停滞");
    }

    @Test
    void 无准则目标_剩余清单卡住判停滞() {
        ProgressGate gate = new ProgressGate();
        assertTrue(gate.madeProgress(noCriteria(), reply(withRemaining("干活A", "把报表数据补全"))));
        // 第二轮剩余原样重复 → 自我供认卡住（哪怕正文换了说法）
        assertFalse(gate.madeProgress(noCriteria(), reply(withRemaining("换了套说法", "把报表数据补全"))),
                "连续两轮剩余相同 → 停滞");
        // 剩余实质变化 → 进展
        assertTrue(gate.madeProgress(noCriteria(), reply(withRemaining("推进了", "发送邮件到指定邮箱"))),
                "剩余清单变化 → 有进展");
    }
}
