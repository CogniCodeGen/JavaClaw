package com.javaclaw.memory.correction;

import com.javaclaw.memory.model.CorrectionRecord;

import java.util.List;
import java.util.Optional;

/**
 * 已知错误回复守卫。
 *
 * <p>模型提示词只能降低复发概率；本守卫在相关纠错轮次缓冲最终回复，并对明确的旧主张做
 * 确定性检查。检测到重复错误时失败关闭，不把草稿发送给用户，也不把它写回长期记忆。</p>
 */
public final class CorrectionGuard {

    private CorrectionGuard() {}

    public record Violation(CorrectionRecord correction, String wrongClaim) {}

    public static Optional<Violation> findViolation(
            String assistantReply, List<CorrectionRecord> corrections) {
        String answer = normalize(assistantReply);
        if (answer.isEmpty() || corrections == null) return Optional.empty();

        for (CorrectionRecord correction : corrections) {
            if (correction == null || !correction.isEffective() || !correction.hasWrongClaim()) {
                continue;
            }
            String wrong = normalize(correction.wrongClaim);
            if (wrong.length() < 2) continue;
            // 先遮掉当前正确主张，避免 npm→pnpm、Java 21→Java 21.0.2 这类包含关系
            // 把正确答案本身误判为旧错误。
            String answerToCheck = answer;
            if (correction.hasCorrectClaim()) {
                String correct = normalize(correction.correctClaim);
                if (correct.length() >= 2) {
                    answerToCheck = answerToCheck.replace(correct, "");
                }
            }
            if (!hasUnnegatedClaim(answerToCheck, wrong)) continue;
            return Optional.of(new Violation(correction, correction.wrongClaim));
        }
        return Optional.empty();
    }

    public static String safeFallback(Violation violation) {
        CorrectionRecord record = violation.correction();
        if (record.status == CorrectionRecord.Status.ACTIVE && record.hasCorrectClaim()) {
            return "已阻止输出可能重复的旧结论。按用户已确认的更正，当前应以「"
                    + record.correctClaim + "」为准。";
        }
        return "已阻止输出可能重复的旧结论。该事实目前存在明确争议，"
                + "需要先通过可靠来源或工具核验后再回答。";
    }

    /**
     * 判断模型生成的候选长期事实是否违反纠错约束。
     * 除已否定旧主张外，DISPUTED 状态下用户提出但尚未核验的新说法也不得落成有效事实。
     */
    public static Optional<CorrectionRecord> findUnsafeMemoryClaim(
            String candidateFact, List<CorrectionRecord> corrections) {
        Optional<Violation> oldClaim = findViolation(candidateFact, corrections);
        if (oldClaim.isPresent()) return Optional.of(oldClaim.get().correction());
        if (corrections == null) return Optional.empty();
        for (CorrectionRecord correction : corrections) {
            if (correction != null
                    && correction.status == CorrectionRecord.Status.DISPUTED
                    && correction.hasCorrectClaim()
                    && containsClaim(candidateFact, correction.correctClaim)) {
                return Optional.of(correction);
            }
        }
        return Optional.empty();
    }

    static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s\\p{P}\\p{S}]+", "");
    }

    /** 中文主张按子串匹配；ASCII 标识符要求字母数字边界，避免 npm 命中 pnpm。 */
    static boolean containsClaim(String value, String claim) {
        return containsNormalizedClaim(normalize(value), normalize(claim));
    }

    private static boolean containsNormalizedClaim(String value, String claim) {
        return findNextClaim(value, claim, 0) >= 0;
    }

    /** 逐个检查命中点，不能因答案别处恰好有一次否定就放过另一处肯定断言。 */
    private static boolean hasUnnegatedClaim(String value, String claim) {
        int from = 0;
        while (from <= value.length() - claim.length()) {
            int at = findNextClaim(value, claim, from);
            if (at < 0) return false;
            int end = at + claim.length();
            String prefix = value.substring(Math.max(0, at - 10), at);
            String suffix = value.substring(end, Math.min(value.length(), end + 8));
            boolean negated = endsWithAny(prefix,
                    "不是", "并不是", "并非", "不要", "不要再使用", "不再",
                    "已废弃", "否定", "不能使用", "避免使用", "停止使用",
                    "不应使用", "不该使用")
                    || startsWithAny(suffix,
                    "不对", "错误", "有误", "已废弃", "不可用", "不再使用");
            if (!negated) return true;
            from = at + 1;
        }
        return false;
    }

    private static int findNextClaim(String value, String claim, int from) {
        if (value == null || claim == null || claim.isEmpty()) return -1;
        if (!claim.matches("[a-z0-9]+")) return value.indexOf(claim, from);
        for (int cursor = Math.max(0, from);
             cursor <= value.length() - claim.length(); ) {
            int at = value.indexOf(claim, cursor);
            if (at < 0) return -1;
            int end = at + claim.length();
            boolean leftOk = at == 0 || !isAsciiLetterOrDigit(value.charAt(at - 1));
            boolean rightOk = end == value.length()
                    || !isAsciiLetterOrDigit(value.charAt(end));
            if (leftOk && rightOk) return at;
            cursor = at + 1;
        }
        return -1;
    }

    private static boolean endsWithAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.endsWith(candidate)) return true;
        return false;
    }

    private static boolean startsWithAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.startsWith(candidate)) return true;
        return false;
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
