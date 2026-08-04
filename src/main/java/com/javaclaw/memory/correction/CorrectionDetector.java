package com.javaclaw.memory.correction;

import com.javaclaw.memory.model.CorrectionRecord;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 显式用户纠错检测器。
 *
 * <p>这里刻意采用确定性规则作为第一道闸，而不是让另一次 LLM 调用决定是否拥有“撤销记忆”
 * 的权限。</p>
 *
 * <p><b>判定标准只有一档</b>：必须同时给出“错的是什么”和“对的是什么”（如「不是 X，而是 Y」
 * 「你说的 X 错了，应该是 Y」）才算纠错。裸的否定（「报错了」「这里不对」）、只给新说法、
 * 以及各种疑问句，都当作普通对话——不写库、不改事实状态。猜测不产生副作用，是这个模块
 * 的基本约束。</p>
 */
public final class CorrectionDetector {

    private static final int CLAIM_CAP = 240;

    /*
     * 只接受“旧主张 + 新主张”都写全的替换句型。原先末尾还有一个裸「是」备选，
     * 会把「这不是这个，是那个」这类日常措辞也当成正式事实替换，故移除。
     */
    private static final Pattern NOT_BUT = Pattern.compile(
            "(?:不是|并不是|并非)\\s*[「『“\\\"']?([^，,。；;！？!?\\n]{1,120}?)[」』”\\\"']?"
                    + "\\s*[,，；;。]?\\s*(?:而是|应为|应该是|正确(?:答案|说法|结论)?(?:是|为))"
                    + "\\s*[「『“\\\"']?([^。！？!?\\n]{1,200})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WRONG_THEN_CORRECT = Pattern.compile(
            "[「『“\\\"']?([^，,。；;！？!?\\n]{1,120}?)[」』”\\\"']?\\s*"
                    + "(?:不对|错了|有误|是错误的)\\s*[,，；;。]?\\s*"
                    + "(?:应该(?:是|为)|应为|正确(?:答案|说法|结论)?(?:是|为))"
                    + "\\s*[:：]?\\s*[「『“\\\"']?([^。！？!?\\n]{1,200})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HYPOTHETICAL_PREFIX = Pattern.compile(
            "^(?:如果|假如|假设|万一|如何判断|怎么判断)",
            Pattern.CASE_INSENSITIVE);

    /*
     * 疑问句只是“在问是不是错了”，不是“断言错了”。用户提问不构成对长期记忆的授权。
     * 判定只在“承载替换句型的那一句”内进行，且反问词必须出现在替换主张之前或之中——
     * 否则「我的名字不是张三，而是李四。明白吗」这类先断言后确认的正常纠错会被整条丢弃。
     */
    private static final Pattern QUESTION_MARK = Pattern.compile("[？?]");

    private static final Pattern QUESTION_TAIL = Pattern.compile("(?:吗|呢|吧)[。！!，,\\s]*$");

    private static final Pattern HEDGE_WORDS = Pattern.compile(
            "是不是|难道|对吗|对不对", Pattern.CASE_INSENSITIVE);

    /** 句子边界：用于把疑问判定限制在替换句型所在的那一句内。 */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[。！!？?\\n]");

    /** 纯指代词无法定位任何事实，不能作为“被否定的旧主张”。 */
    private static final Pattern PRONOUN_ONLY = Pattern.compile(
            "^(?:这|那|它|他|她|其|此)(?:个|些|里|边|样|条|句|段|种|次|点|儿)?$");

    /** 其它指代性/无信息量措辞黑名单。 */
    private static final java.util.Set<String> VAGUE_CLAIMS = java.util.Set.of(
            "刚才", "上次", "之前", "上面", "下面", "前面", "后面",
            "如此", "一样", "这样子", "那样子", "全错", "都不对");

    /** 中文主张最短长度：再短就只能是指代或语气词。 */
    private static final int MIN_CLAIM_CHARS = 2;

    /** 纯字母数字主张可以只有一个字符（版本号、端口、JDK 8 这类），匹配时另有词边界保护。 */
    private static final Pattern ALNUM_CLAIM = Pattern.compile("[0-9A-Za-z.]+");

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

    /**
     * 识别“可执行的明确纠错”：旧主张与新主张都写全，且不是提问或假设。
     *
     * <p>其余情形（裸「错了 / 不对 / 有误」、只给新说法、疑问句）一律返回空。它们不产生
     * 任何持久副作用——用户那句话本身就在当轮上下文里，由正常对话处理即可。判定从宽的
     * 代价远高于漏判：一句「编译报错了」若被当成纠错，会把真实事实标成“用户已否定”，
     * 且该状态不会自行恢复。</p>
     */
    public static Optional<Candidate> detect(String userInput) {
        String input = userInput == null ? "" : userInput.strip();
        if (input.isEmpty()) return Optional.empty();
        // 假设句：“如果不是 X 而是 Y 会怎样”不是对当前事实的更正。
        if (HYPOTHETICAL_PREFIX.matcher(input).find()) return Optional.empty();

        for (Pattern pattern : new Pattern[]{NOT_BUT, WRONG_THEN_CORRECT}) {
            Matcher m = pattern.matcher(input);
            if (!m.find()) continue;
            // 疑问句：用户在询问而不是断言，不构成对长期记忆的授权。
            if (isQuestioning(input, m.start(), m.end())) return Optional.empty();
            String wrong = cleanClaim(m.group(1), true);
            String correct = cleanClaim(m.group(2), false);
            // 两个主张都必须实质：缺旧主张无法定位要废弃的事实，缺新主张无从写入结论。
            if (isSubstantiveClaim(wrong) && isSubstantiveClaim(correct)) {
                return Optional.of(candidate(input, wrong, correct));
            }
        }
        return Optional.empty();
    }

    /**
     * 判断承载替换句型的那一句是否其实是个问句。
     *
     * <p>只看替换句型所在的句子，且反问词必须出现在新主张给出之前——「…而是李四。明白吗」
     * 是先断言后寻求确认，仍属有效纠错；「…而是 pnpm，对吗？」才是在征询。</p>
     */
    private static boolean isQuestioning(String input, int matchStart, int matchEnd) {
        int from = 0;
        int to = input.length();
        Matcher boundary = SENTENCE_SPLIT.matcher(input);
        while (boundary.find()) {
            if (boundary.start() < matchStart) {
                from = boundary.end();
            } else if (boundary.start() >= matchEnd) {
                // 句末标点本身属于当前句，需一并纳入（用于识别句尾问号）
                to = Math.min(input.length(), boundary.end());
                break;
            }
        }
        if (from >= to) return false;
        String sentence = input.substring(from, to);
        if (QUESTION_MARK.matcher(sentence).find()) return true;
        if (QUESTION_TAIL.matcher(sentence).find()) return true;
        Matcher hedge = HEDGE_WORDS.matcher(sentence);
        // 反问词落在新主张之前/之中才算征询语气
        return hedge.find() && from + hedge.start() < matchEnd;
    }

    /** 是否为可用于定位/写入的实质主张（排除纯指代词与无信息量措辞）。 */
    private static boolean isSubstantiveClaim(String claim) {
        if (claim == null) return false;
        String s = claim.strip();
        if (s.isEmpty()) return false;
        if (PRONOUN_ONLY.matcher(s).matches() || VAGUE_CLAIMS.contains(s)) return false;
        // 「JDK 不是 8，而是 17」「端口不是 3，而是 8」：单字符的版本号/端口也是明确主张，
        // 匹配时由 CorrectionGuard 的 ASCII 词边界保证不会误命中 18、3.8 之类。
        if (ALNUM_CLAIM.matcher(s).matches()) return true;
        return s.length() >= MIN_CLAIM_CHARS;
    }

    /** 是否构成可写入长期记忆的明确纠错（供蒸馏等旁路复用同一判定）。 */
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
