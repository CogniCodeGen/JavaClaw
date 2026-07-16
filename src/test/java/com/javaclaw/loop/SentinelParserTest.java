package com.javaclaw.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 哨兵解析测试：重点钉死「剩余」提取只认段首标记——剩余正文自身含「剩余」二字时，
 * 旧的 lastIndexOf 会命中正文内层，把两轮实质不同的剩余清单折叠成相同尾巴，
 * 令进展判定连续误判停滞、误杀正在推进的循环。
 */
class SentinelParserTest {

    @Test
    void 剩余正文含剩余二字_取段首标记而非最后一处() {
        String reply = "本轮清理了两个文件。\n【判定】未完成｜已完成：清理2个｜剩余：清理剩余的3个文件";
        assertEquals("清理剩余的3个文件", SentinelParser.extractRemaining(reply));

        // 两轮实质不同的剩余清单必须提取出不同文本（旧实现都折叠成「的3个文件」）
        String reply2 = "校对完成一半。\n【判定】未完成｜已完成：校对2个｜剩余：校对剩余的3个文件";
        assertEquals("校对剩余的3个文件", SentinelParser.extractRemaining(reply2));
    }

    @Test
    void 常规格式提取剩余() {
        String reply = "干活中。\n【判定】未完成｜已完成：无｜剩余：把报表数据补全";
        assertEquals("把报表数据补全", SentinelParser.extractRemaining(reply));
    }

    @Test
    void 无剩余小节返回null() {
        assertNull(SentinelParser.extractRemaining("【判定】已完成"));
        assertNull(SentinelParser.extractRemaining("没有判定行的普通回复"));
        // 正文里出现「剩余」但没有段首标记：不算剩余小节
        assertNull(SentinelParser.extractRemaining("【判定】未完成｜已完成：处理了剩余任务"));
    }

    @Test
    void 判定行取最后一处_提议解析不受正文引用干扰() {
        String reply = "正文引用了一句 【判定】已完成 的例子。\n【判定】未完成｜剩余：继续";
        assertTrue(SentinelParser.proposesNotDone(reply));
    }

    @Test
    void 已完成小节标题不算提议完成() {
        // 回归缺陷：模型漏写行首「未完成」直接写「已完成：无｜剩余：…」，startsWith 误读为
        // 提议完成——无准则且 judge=off 的循环当轮假完成收工（明明自报还差 3 项）
        assertFalse(SentinelParser.proposesDone("【判定】已完成：无｜剩余：还差3项"));
        // 真正的判定词形态照常识别
        assertTrue(SentinelParser.proposesDone("【判定】已完成"));
        assertTrue(SentinelParser.proposesDone("【判定】已完成｜全部准则通过"));
        assertTrue(SentinelParser.proposesDone("【判定】已完成，所有任务结束"));
    }

    @Test
    void 判定词的边界识别_标点emoji收_空格分隔额外token与构词拒() {
        // 句法完整的独立判定词：紧跟行尾 / 标点 / 竖线 / emoji → 是提议完成
        assertTrue(SentinelParser.proposesDone("【判定】已完成"));
        assertTrue(SentinelParser.proposesDone("【判定】已完成。"));
        assertTrue(SentinelParser.proposesDone("【判定】已完成✅"));
        assertTrue(SentinelParser.proposesDone("【判定】已完成   ")); // 仅尾随空白
        // 空格分隔的额外 token：语义歧义（强调完成 vs 带保留的未尽），保守判非完成——
        // 尤其「已完成 但仍需人工复核」绝不能被读成完全完成而假收工
        assertFalse(SentinelParser.proposesDone("【判定】已完成 但仍需人工复核"));
        assertFalse(SentinelParser.proposesDone("【判定】已完成 全部搞定")); // 保守漏报（安全方向）
        // 构词：紧跟中日韩文字/字母数字 → 是更长词组而非判定词
        assertFalse(SentinelParser.proposesDone("【判定】已完成了初稿，但还需补充结论"));
    }

    @Test
    void 未完成开头的散文不算未完成提议() {
        // 回归缺陷：裸 startsWith("未完成") 把「未完成事项已全部清零」这类散文式的「完成」
        // 误读成明确的未完成提议——阻断完成判定、关掉准则达标宽限，INTERVAL 循环烧到上限才停
        assertFalse(SentinelParser.proposesNotDone("【判定】未完成事项已全部清零，目标已达成"));
        // 真正的判定词形态照常识别
        assertTrue(SentinelParser.proposesNotDone("【判定】未完成"));
        assertTrue(SentinelParser.proposesNotDone("【判定】未完成｜已完成：无｜剩余：补数据"));
        assertTrue(SentinelParser.proposesNotDone("【判定】未完成，还差校对"));
        // 判定词不干净但自报了段首「剩余：」小节：自报剩余即明确未完成（空格分隔变体）
        assertTrue(SentinelParser.proposesNotDone("【判定】未完成 剩余：把报表补全"));
    }

    @Test
    void 剩余小节支持空格分隔_但须紧跟冒号() {
        // 模型用空格代替 ｜ 分隔小节：仍应提取出剩余（此前只认 ｜ 分隔，空格分隔被丢弃、
        // 停滞检测拿不到「剩余」信号）
        assertEquals("把报表补全",
                SentinelParser.extractRemaining("【判定】未完成 剩余：把报表补全"));
        // 空格分隔但无冒号 = 正文里的「剩余」二字，不算小节标记
        assertNull(SentinelParser.extractRemaining("【判定】未完成 清理剩余的3个文件"));
    }
}
