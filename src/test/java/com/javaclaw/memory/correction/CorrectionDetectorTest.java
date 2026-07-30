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
    void 极短明确否定也能进入纠错链路() {
        var candidate = CorrectionDetector.detect("错了").orElseThrow();

        assertEquals(CorrectionRecord.Type.RETRACTION, candidate.type());
        assertEquals(CorrectionRecord.Scope.GENERAL, candidate.scope());
        assertFalse(candidate.hasReplacement());
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
    void 我们一词不会把公共事实误升级为项目事实() {
        var candidate = CorrectionDetector.detect(
                "我们之前说地球是平的，这不对，应该是球体").orElseThrow();

        assertEquals(CorrectionRecord.Scope.GENERAL, candidate.scope());
        assertFalse(candidate.isTrustedUserScope());
    }
}
