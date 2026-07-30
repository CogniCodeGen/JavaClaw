package com.javaclaw.memory.correction;

import com.javaclaw.memory.model.CorrectionRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrectionGuardTest {

    @Test
    void 再次断言已废弃主张会被拦截() {
        CorrectionRecord record = active("npm", "pnpm");

        var violation = CorrectionGuard.findViolation(
                "这个项目应该继续使用 npm 管理依赖。", List.of(record));

        assertTrue(violation.isPresent());
        assertTrue(CorrectionGuard.safeFallback(violation.orElseThrow()).contains("pnpm"));
    }

    @Test
    void 为解释纠错而否定提及旧主张不会误拦截() {
        CorrectionRecord record = active("npm", "pnpm");

        assertFalse(CorrectionGuard.findViolation(
                "这个项目应使用 pnpm。", List.of(record)).isPresent());
        assertFalse(CorrectionGuard.findViolation(
                "这个项目不是 npm，而是 pnpm。", List.of(record)).isPresent());
        assertFalse(CorrectionGuard.findViolation(
                "npm 不对，应改用 pnpm。", List.of(record)).isPresent());
        assertFalse(CorrectionGuard.findViolation(
                "不要再使用 npm，请统一改用 pnpm。", List.of(record)).isPresent());
    }

    @Test
    void 一处否定不能掩盖答案中另一处重复断言() {
        CorrectionRecord record = active("npm", "pnpm");

        assertTrue(CorrectionGuard.findViolation(
                "npm 不对；不过另一个模块仍应继续使用 npm。", List.of(record)).isPresent());
    }

    @Test
    void 争议事实的失败关闭文案不会冒充已验证结论() {
        CorrectionRecord record = active("旧结论", "用户提出的新结论");
        record.status = CorrectionRecord.Status.DISPUTED;

        String fallback = CorrectionGuard.safeFallback(
                CorrectionGuard.findViolation("仍然采用旧结论", List.of(record)).orElseThrow());

        assertTrue(fallback.contains("需要先"));
        assertFalse(fallback.contains("用户提出的新结论"));
        assertTrue(CorrectionGuard.findUnsafeMemoryClaim(
                "公共事实是用户提出的新结论", List.of(record)).isPresent());
    }

    private static CorrectionRecord active(String wrong, String correct) {
        CorrectionRecord record = new CorrectionRecord();
        record.id = "test";
        record.type = CorrectionRecord.Type.FACT_REPLACEMENT;
        record.scope = CorrectionRecord.Scope.PROJECT;
        record.status = CorrectionRecord.Status.ACTIVE;
        record.wrongClaim = wrong;
        record.correctClaim = correct;
        return record;
    }
}
