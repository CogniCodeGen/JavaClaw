package com.javaclaw.memory.correction;

import com.javaclaw.memory.model.CorrectionRecord;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 显式用户纠错检测器。
 *
 * <p>这里刻意采用确定性规则作为第一道闸，而不是让另一次 LLM 调用决定是否拥有“撤销记忆”
 * 的权限。规则只接收强纠错措辞；模糊质疑仍交给正常对话处理，避免把“如果回答不对怎么办”
 * 误判成真实纠错。</p>
 */
public final class CorrectionDetector {

    private static final int CLAIM_CAP = 240;

    private static final Pattern NOT_BUT = Pattern.compile(
            "(?:不是|并不是|并非)\\s*[「『“\\\"']?([^，,。；;！？!?\\n]{1,120}?)[」』”\\\"']?"
                    + "\\s*[,，；;。]?\\s*(?:而是|应为|应该是|正确(?:答案|说法|结论)?(?:是|为)|是)"
                    + "\\s*[「『“\\\"']?([^。！？!?\\n]{1,200})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WRONG_THEN_CORRECT = Pattern.compile(
            "[「『“\\\"']?([^，,。；;！？!?\\n]{1,120}?)[」』”\\\"']?\\s*"
                    + "(?:不对|错了|有误|是错误的)\\s*[,，；;。]?\\s*"
                    + "(?:应该(?:是|为)|应为|正确(?:答案|说法|结论)?(?:是|为))"
                    + "\\s*[:：]?\\s*[「『“\\\"']?([^。！？!?\\n]{1,200})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CORRECT_ONLY = Pattern.compile(
            "(?:正确(?:答案|说法|结论)?(?:是|为)|应该(?:是|为)|应为|请改为|改成)"
                    + "\\s*[:：]?\\s*[「『“\\\"']?([^。！？!?\\n]{1,200})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EXPLICIT_RETRACTION = Pattern.compile(
            "(?:(?:你|助手|模型)(?:刚才|上次|之前)?(?:的)?\\s*)?"
                    + "(?:(?:回答|答案|说法|结论)\\s*)?"
                    + "(?:不对|错了|有误|错误|不正确|说错了|答错了)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HYPOTHETICAL_PREFIX = Pattern.compile(
            "^(?:如果|假如|假设|万一|如何判断|怎么判断)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern METHOD_TERMS = Pattern.compile(
            "(?:做法|方法|步骤|流程|操作|命令|工具|实现方式|处理方式|工作流|方案)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PROJECT_TERMS = Pattern.compile(
            "(?:这个项目|当前项目|本项目|项目|仓库|代码库|代码|工程|工作区|配置)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern USER_TERMS = Pattern.compile(
            "(?:我的|我本人|我偏好|我习惯|对我|以后给我|用户偏好)",
            Pattern.CASE_INSENSITIVE);

    private CorrectionDetector() {}

    public record Candidate(
            CorrectionRecord.Type type,
            CorrectionRecord.Scope scope,
            String wrongClaim,
            String correctClaim,
            String sourceInput) {

        public boolean hasReplacement() {
            return correctClaim != null && !correctClaim.isBlank();
        }

        public boolean isTrustedUserScope() {
            return scope == CorrectionRecord.Scope.USER
                    || scope == CorrectionRecord.Scope.PROJECT;
        }
    }

    public static Optional<Candidate> detect(String userInput) {
        String input = userInput == null ? "" : userInput.strip();
        if (input.isEmpty()) return Optional.empty();
        // 所有替换式规则之前先挡住假设问句，避免“如果不是 X，而是 Y 怎么办”
        // 被当成用户对当前事实的正式更正。
        if (HYPOTHETICAL_PREFIX.matcher(input).find()) return Optional.empty();

        Matcher replacement = NOT_BUT.matcher(input);
        if (replacement.find()) {
            String wrong = cleanClaim(replacement.group(1), true);
            String correct = cleanClaim(replacement.group(2), false);
            return Optional.of(candidate(input, wrong, correct));
        }

        replacement = WRONG_THEN_CORRECT.matcher(input);
        if (replacement.find()) {
            String wrong = cleanClaim(replacement.group(1), true);
            String correct = cleanClaim(replacement.group(2), false);
            return Optional.of(candidate(input, wrong, correct));
        }

        boolean explicit = EXPLICIT_RETRACTION.matcher(input).find();
        if (!explicit) return Optional.empty();

        Matcher correctOnly = CORRECT_ONLY.matcher(input);
        String correct = correctOnly.find() ? cleanClaim(correctOnly.group(1), false) : "";
        CorrectionRecord.Type type = METHOD_TERMS.matcher(input).find()
                ? CorrectionRecord.Type.METHOD_CORRECTION
                : (correct.isBlank()
                        ? CorrectionRecord.Type.RETRACTION
                        : CorrectionRecord.Type.FACT_REPLACEMENT);
        return Optional.of(new Candidate(
                type, scopeOf(input), "", correct, cap(input)));
    }

    public static boolean isExplicitCorrection(String userInput) {
        return detect(userInput).isPresent();
    }

    private static Candidate candidate(String input, String wrong, String correct) {
        CorrectionRecord.Type type = METHOD_TERMS.matcher(input).find()
                ? CorrectionRecord.Type.METHOD_CORRECTION
                : CorrectionRecord.Type.FACT_REPLACEMENT;
        return new Candidate(type, scopeOf(input), wrong, correct, cap(input));
    }

    private static CorrectionRecord.Scope scopeOf(String input) {
        if (USER_TERMS.matcher(input).find()) return CorrectionRecord.Scope.USER;
        if (PROJECT_TERMS.matcher(input).find()) return CorrectionRecord.Scope.PROJECT;
        return CorrectionRecord.Scope.GENERAL;
    }

    private static String cleanClaim(String value, boolean wrong) {
        if (value == null) return "";
        String s = value.strip()
                .replaceAll("^[「『“\\\"']+|[」』”\\\"']+$", "")
                .replaceAll("[，,。；;！？!?\\s]+$", "")
                .strip();
        if (wrong) {
            s = s.replaceFirst(
                    "^(?:你|助手|模型)?(?:刚才|上次|之前)?(?:说|回答|认为|给出)?\\s*",
                    "").strip();
        }
        return cap(s);
    }

    private static String cap(String text) {
        if (text == null) return "";
        String s = text.strip();
        return s.length() <= CLAIM_CAP ? s : s.substring(0, CLAIM_CAP) + "…";
    }
}
