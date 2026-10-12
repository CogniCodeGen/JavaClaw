package com.javaclaw.memory.correction;

import com.javaclaw.memory.model.CorrectionRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrectionGuardTest {

    @Test
    void 再次断言已废弃主张会被拦截() {
        CorrectionRecord record = active("npm", "pnpm");

        var violation = CorrectionGuard.findViolation(
                "这个项目应该继续使用 npm 管理依赖。", List.of(record));

        assertTrue(violation.isPresent());
        assertEquals("npm", violation.orElseThrow().wrongClaim());
        // 这类命中只用于记忆写入闸门与审计，不会拦截发给用户的文字。
        assertTrue(CorrectionGuard.findUnsafeMemoryClaim(
                "这个项目继续使用 npm 管理依赖", List.of(record)).isPresent());
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
    void 劝退旧值的正确答案不算重申旧主张() {
        CorrectionRecord record = active("npm", "pnpm");

        // 这四句都是「正确答案」：原先的精确后缀白名单会把它们全判成重申旧结论。
        assertFalse(CorrectionGuard.findViolation(
                "不要用 npm，请用 pnpm 安装依赖", List.of(record)).isPresent());
        assertFalse(CorrectionGuard.findViolation(
                "别再用 npm，改用 pnpm", List.of(record)).isPresent());
        assertFalse(CorrectionGuard.findViolation(
                "npm 已不推荐，请使用 pnpm", List.of(record)).isPresent());
        assertFalse(CorrectionGuard.findViolation(
                "请勿使用 npm，统一用 pnpm", List.of(record)).isPresent());
    }

    @Test
    void 一处否定不能掩盖答案中另一处重复断言() {
        CorrectionRecord record = active("npm", "pnpm");

        assertTrue(CorrectionGuard.findViolation(
                "npm 不对；不过另一个模块仍应继续使用 npm。", List.of(record)).isPresent());
    }

    @Test
    void 无关词语不得让记忆闸门放过已否定主张() {
        CorrectionRecord record = active("npm", "pnpm");

        // 这些都是模型可能蒸馏出的句子：含「分别/类别/避免/停止」等常见词，
        // 但仍在断言用户已否定的 npm，必须拦住（否则错误主张会写回长期记忆）。
        for (String fact : List.of(
                "分别使用 npm 和 yarn 管理依赖",
                "构建工具的类别是 npm 脚本",
                "为避免依赖冲突，统一使用 npm 安装",
                "停止服务后再用 npm 安装",
                "项目已迁到 pnpm，但 CI 仍使用 npm 安装依赖")) {
            assertTrue(CorrectionGuard.findUnsafeMemoryClaim(fact, List.of(record)).isPresent(),
                    "应拦住: " + fact);
        }
    }

    @Test
    void 正确主张是旧主张子串时闸门依然有效() {
        // 用户说「不是 pnpm，而是 npm」：正确主张 npm 是旧主张 pnpm 的子串。
        // 若靠遮蔽正确主张实现对比判定，会把 pnpm 一并毁掉，令该条纠错永久失效。
        CorrectionRecord record = active("pnpm", "npm");

        assertTrue(CorrectionGuard.findUnsafeMemoryClaim(
                "项目继续使用 pnpm 安装依赖", List.of(record)).isPresent());
        // 反向包含（旧 npm / 新 pnpm）时，落在 pnpm 内部的 npm 不算重申
        CorrectionRecord reverse = active("npm", "pnpm");
        assertFalse(CorrectionGuard.findViolation(
                "项目使用 pnpm 安装依赖", List.of(reverse)).isPresent());
    }

    @Test
    void 争议纠错的新旧两种说法都不得直接落成有效记忆() {
        CorrectionRecord record = active("旧结论", "用户提出的新结论");
        record.status = CorrectionRecord.Status.DISPUTED;

        // 旧说法：用户已明确否定
        assertTrue(CorrectionGuard.findUnsafeMemoryClaim(
                "仍然采用旧结论", List.of(record)).isPresent());
        // 新说法：尚未核验，同样不能写成有效事实
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
