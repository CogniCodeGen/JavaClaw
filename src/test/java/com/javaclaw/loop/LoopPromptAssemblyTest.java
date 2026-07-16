package com.javaclaw.loop;

import com.javaclaw.loop.model.CarryForwardMode;
import com.javaclaw.loop.model.IterationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词组装与哨兵解析的回归测试。
 *
 * <p>背景事故：目标分解被短请求阈值跳过时，目标文本曾从系统提示词与轮次提示词中<b>双双蒸发</b>，
 * 执行体收到一套循环纪律却不知目标为何，空转烧轮。本测试钉死「目标原文必须出现在每一轮
 * 用户提示词里」这条不变量。</p>
 */
class LoopPromptAssemblyTest {

    private static final String GOAL = "抓取最近七天的股市数据生成报表并发送邮件";

    @Test
    void 每轮提示词都必须包含目标原文() {
        CarryContext ctx = new CarryContext(GOAL, CarryForwardMode.LAST_RESULT_ONLY);

        // 首轮
        assertTrue(ctx.assemble(1).contains(GOAL), "首轮提示词必须复述目标");

        // 后续轮（带接力上下文）
        ctx.record(IterationResult.ok("第一轮干了一些活", 0L, 0L));
        String round2 = ctx.assemble(2);
        assertTrue(round2.contains(GOAL), "后续轮提示词必须复述目标");
        assertTrue(round2.contains("第一轮干了一些活"), "后续轮提示词必须携带此前进展");
    }

    @Test
    void 摘要接力模式携带历轮简述与末轮全文() {
        CarryContext ctx = new CarryContext(GOAL, CarryForwardMode.SUMMARY);
        ctx.record(IterationResult.ok("第一轮的完整长产出……", 0L, 0L, java.util.List.of(),
                new com.javaclaw.loop.model.LoopReport(false, "抓取了七天数据", "生成报表", 0L, "")));
        ctx.record(IterationResult.ok("第二轮的完整长产出……", 0L, 0L, java.util.List.of(),
                new com.javaclaw.loop.model.LoopReport(false, "报表已生成", "发送邮件", 0L, "")));

        String round3 = ctx.assemble(3);
        assertTrue(round3.contains(GOAL), "目标原文必须在");
        assertTrue(round3.contains("抓取了七天数据"), "应携带第 1 轮简述（来自 loop_report.summary）");
        assertTrue(round3.contains("报表已生成"), "应携带第 2 轮简述");
        assertTrue(round3.contains("第二轮的完整长产出"), "应携带末轮完整产出");
        assertFalse(round3.contains("第一轮的完整长产出"), "历史轮只留简述，不带全文（上下文有界）");
    }

    @Test
    void 哨兵解析容忍常见分隔符变体() {
        String prefix = LoopConstants.JUDGMENT_LINE_PREFIX;
        String done = LoopConstants.JUDGMENT_DONE;

        // 标准格式
        assertTrue(SentinelParser.proposesDone("正文\n" + prefix + done + "｜依据：xx"));
        // 全角冒号变体
        assertTrue(SentinelParser.proposesDone("正文\n" + prefix + "：" + done));
        // 半角冒号 + 空格变体
        assertTrue(SentinelParser.proposesDone("正文\n" + prefix + ": " + done));
        // 未完成不误判为完成
        assertFalse(SentinelParser.proposesDone(
                "正文\n" + prefix + LoopConstants.JUDGMENT_NOT_DONE + "｜剩余：yy"));
        // 正文中引用判定行时以最后一条为准
        assertTrue(SentinelParser.proposesDone(
                prefix + LoopConstants.JUDGMENT_NOT_DONE + "\n后来又干了活\n" + prefix + done));
    }
}
