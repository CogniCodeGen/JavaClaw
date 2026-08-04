package com.javaclaw.memory.correction;

import com.javaclaw.memory.model.CorrectionRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrectionDetectorTest {

    @Test
    void 识别项目事实替换并抽取新旧主张() {
        var candidate = CorrectionDetector.detect("这个项目不是 npm，而是 pnpm").orElseThrow();

        assertEquals(CorrectionRecord.Type.FACT_REPLACEMENT, candidate.type());
        assertEquals(CorrectionRecord.Scope.PROJECT, candidate.scope());
        assertEquals("npm", candidate.wrongClaim());
        assertEquals("pnpm", candidate.correctClaim());
        assertTrue(candidate.isTrustedUserScope());
    }

    @Test
    void 裸否定不构成可执行纠错() {
        // 没有说“对的是什么”，就无从定位旧事实、也无从写入新结论：一律按普通对话处理。
        assertTrue(CorrectionDetector.detect("错了").isEmpty());
        assertTrue(CorrectionDetector.detect("你回答错了").isEmpty());
        assertTrue(CorrectionDetector.detect("这里不对").isEmpty());
        assertTrue(CorrectionDetector.detect("你刚才的结论有误").isEmpty());
    }

    @Test
    void 日常报错抱怨不会被当成纠错() {
        // 实测过的高频误判输入：编程助手里这类话每天都在说，绝不能产生记忆副作用。
        assertTrue(CorrectionDetector.detect("编译的时候报错了，帮我看看").isEmpty());
        assertTrue(CorrectionDetector.detect("启动报错误：NoClassDefFoundError").isEmpty());
        assertTrue(CorrectionDetector.detect("为什么这里不对").isEmpty());
        assertTrue(CorrectionDetector.detect("帮我查一下哪里写错了").isEmpty());
        assertTrue(CorrectionDetector.detect("日志里有 error，是不是配置有误").isEmpty());
        assertTrue(CorrectionDetector.detect("这个测试失败了，怎么修").isEmpty());
    }

    @Test
    void 疑问句是在提问而不是授权改记忆() {
        assertTrue(CorrectionDetector.detect("这个方法调用顺序不对吗？").isEmpty());
        assertTrue(CorrectionDetector.detect("刚才那个命令执行错了吗").isEmpty());
        assertTrue(CorrectionDetector.detect("这个项目不是 npm，而是 pnpm，对吗？").isEmpty());
    }

    @Test
    void 指代词不能充当被否定的旧主张() {
        // 「这个」这类主张会命中所有含该词的事实与回复，等于无限连带，必须拒绝。
        assertTrue(CorrectionDetector.detect("这不是这个，是那个").isEmpty());
        assertTrue(CorrectionDetector.detect("不是这个而是那个").isEmpty());
        assertTrue(CorrectionDetector.detect("这不是 bug，是设计如此").isEmpty());
        // 逗号切断后只捕获到「这」的句子同样不该产生纠错
        assertTrue(CorrectionDetector.detect("我们之前说地球是平的，这不对，应该是球体").isEmpty());
    }

    @Test
    void 识别做法纠正而不是普通事实() {
        var candidate = CorrectionDetector.detect(
                "你刚才的实现方法不对，应该是先验证再写文件").orElseThrow();

        assertEquals(CorrectionRecord.Type.METHOD_CORRECTION, candidate.type());
        assertEquals("先验证再写文件", candidate.correctClaim());
    }

    @Test
    void 假设性问题不会误伤为真实纠错() {
        assertTrue(CorrectionDetector.detect("如果模型回答不对应该怎么办？").isEmpty());
        assertTrue(CorrectionDetector.detect("如果不是 npm，而是 pnpm，会发生什么？").isEmpty());
    }

    @Test
    void 先断言后寻求确认仍是有效纠错() {
        // 反问词出现在新主张之后（「明白吗」只是要个确认），不该整条丢弃
        var candidate = CorrectionDetector.detect(
                "记住：我的名字不是张三，而是李四。明白吗").orElseThrow();

        assertEquals("张三", candidate.wrongClaim());
        assertEquals("李四", candidate.correctClaim());
        assertEquals(CorrectionRecord.Scope.USER, candidate.scope());
    }

    @Test
    void 版本号与端口这类单字符主张有效() {
        var jdk = CorrectionDetector.detect("JDK 不是 8，而是 17").orElseThrow();
        assertEquals("8", jdk.wrongClaim());
        assertEquals("17", jdk.correctClaim());

        var port = CorrectionDetector.detect("端口不是 3，而是 8").orElseThrow();
        assertEquals("3", port.wrongClaim());
        assertEquals("8", port.correctClaim());
    }

    @Test
    void 替换从句里夹着反问词时保守放弃() {
        // 「而是」后的贪婪捕获会把反问尾巴一并吃进新主张（得到「pnpm，是不是很简单」），
        // 与其把这种脏主张写进记忆，不如放弃本次识别——漏判可由用户重述，误判要人工清理。
        assertTrue(CorrectionDetector.detect("这个不是 npm，而是 pnpm，是不是很简单").isEmpty());
    }

    @Test
    void 我们一词不会把公共事实误升级为项目事实() {
        var candidate = CorrectionDetector.detect("地球不是平的，而是球体").orElseThrow();

        assertEquals(CorrectionRecord.Scope.GENERAL, candidate.scope());
        assertFalse(candidate.isTrustedUserScope());
        assertEquals("平的", candidate.wrongClaim());
        assertEquals("球体", candidate.correctClaim());
    }
}
