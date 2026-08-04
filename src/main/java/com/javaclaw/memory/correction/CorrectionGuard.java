package com.javaclaw.memory.correction;

import com.javaclaw.memory.model.CorrectionRecord;

import java.util.List;
import java.util.Optional;

/**
 * 已废弃主张的确定性检查。
 *
 * <p><b>作用范围只有副作用，不含措辞</b>：用于拦住“把已被用户否定的主张重新写进长期记忆”
 * （见 {@link #findUnsafeMemoryClaim}），以及给助手回复里疑似复发的情况留一条审计。
 * 它<b>不</b>拦截、不改写发给用户的文字——拦错一段正确回复的代价（答案被吞、用户无从知晓）
 * 远高于它能避免的危害；降低复发概率由提示词承担。</p>
 */
public final class CorrectionGuard {

    /** 判定“否定语境”时，在命中点两侧查找劝退措辞的窗口宽度（归一化后字符数）。 */
    private static final int NEGATION_WINDOW = 6;

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
            String correct = correction.hasCorrectClaim()
                    ? normalize(correction.correctClaim) : "";
            if (!hasUnnegatedClaim(answer, wrong, correct)) continue;
            return Optional.of(new Violation(correction, correction.wrongClaim));
        }
        return Optional.empty();
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

    /**
     * 逐个检查命中点，不能因文本别处恰好有一次否定就放过另一处肯定断言。
     *
     * <p>只有命中点<b>紧邻</b>劝退措辞才算“在说明旧值已废弃”。这里的误判成本是不对称的：
     * 本方法唯一的消费者是记忆写入闸门，漏判会把用户明确否定的主张写回长期记忆（要用户
     * 手动清理），错判只是少存一条蒸馏事实。因此词表取多字、无歧义的短语，窗口也很窄——
     * 单字「别」会命中类别/分别/特别，「避免」「停止」「改用」这类泛化词会被无关句子触发。</p>
     */
    private static boolean hasUnnegatedClaim(String value, String claim, String correctClaim) {
        int from = 0;
        while (from <= value.length() - claim.length()) {
            int at = findNextClaim(value, claim, from);
            if (at < 0) return false;
            int end = at + claim.length();
            if (isInsideCorrectClaim(value, at, end, correctClaim) || isNegated(value, at, end)) {
                from = at + 1;
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * 命中点是否整体落在“当前正确主张”的一次出现之内（如 Java 21 落在 Java 21.0.2 里）。
     *
     * <p>不能反过来用遮蔽实现：若正确主张是旧主张的子串（用户说「不是 pnpm，而是 npm」），
     * 先把 npm 遮掉会顺带毁掉 pnpm，导致该条纠错的闸门永久失效。逐命中判断包含关系对两个
     * 方向都成立。ASCII 标识符的边界另由 {@link #findNextClaim} 保证。</p>
     */
    private static boolean isInsideCorrectClaim(
            String value, int at, int end, String correctClaim) {
        if (correctClaim == null || correctClaim.length() <= end - at) return false;
        int from = 0;
        while (true) {
            int found = value.indexOf(correctClaim, from);
            if (found < 0) return false;
            if (found <= at && found + correctClaim.length() >= end) return true;
            from = found + 1;
        }
    }

    /** 命中点紧邻处是否带劝退/废弃措辞。 */
    private static boolean isNegated(String value, int at, int end) {
        String prefix = value.substring(Math.max(0, at - NEGATION_WINDOW), at);
        String suffix = value.substring(end, Math.min(value.length(), end + NEGATION_WINDOW));
        return containsAny(prefix,
                "不是", "并非", "不要", "不再", "别再", "别用", "请勿", "禁止",
                "弃用", "废弃", "不应", "不该", "不建议", "停止使用", "避免使用",
                "不能使用", "而非")
                || containsAny(suffix,
                "不对", "错误", "有误", "已废弃", "已弃用", "已过时",
                "不可用", "不再使用", "已不推荐");
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

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
