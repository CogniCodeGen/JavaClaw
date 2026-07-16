package com.javaclaw.loop;

/**
 * 收尾标记解析器：从执行体本轮回复里提取结尾的「自报判定」。
 *
 * <p>只认以 {@link LoopConstants#JUDGMENT_LINE_PREFIX} 开头的行；取<b>最后一处</b>为准
 * （结尾判定行才是最终态度，正文里的引用不算）。这里得到的只是执行体的「提议」，
 * 是否采信由 {@code CompletionChecker} 结合客观核验决定。</p>
 */
public final class SentinelParser {

    private SentinelParser() {}

    /** 执行体是否提议「已完成」。 */
    public static boolean proposesDone(String reply) {
        String judgment = extractJudgment(reply);
        return judgment != null && isVerdictWord(judgment, LoopConstants.JUDGMENT_DONE);
    }

    /** 执行体是否提议「未完成」。 */
    public static boolean proposesNotDone(String reply) {
        String judgment = extractJudgment(reply);
        if (judgment == null) {
            return false;
        }
        // 与 proposesDone 同一判定词边界检查：裸 startsWith 会把「未完成事项已全部清零，
        // 目标已达成」这类以「未完成」开头的散文误读成明确的未完成提议——既阻断完成判定
        // 又关掉准则达标的静默宽限，INTERVAL 循环（豁免空转护栏）会在目标已达成后逐 tick
        // 烧模型调用直到轮数/墙钟上限。判定词不干净但带出段首「剩余：」小节的
        // （如空格分隔变体「未完成 剩余：xxx」），自报剩余本身就是未完成的明确表达，仍算
        return isVerdictWord(judgment, LoopConstants.JUDGMENT_NOT_DONE)
                || extractRemaining(reply) != null;
    }

    /**
     * 从判定行提取自报「剩余」清单文本；无判定行或无剩余小节返回 null。
     *
     * <p>供进展判定比对：连续两轮「剩余」高度相似 = 执行体自己承认卡在同一处。
     * 格式约定 {@code 未完成｜已完成：xxx｜剩余：yyy}，取「剩余」标记后至行尾的内容。</p>
     */
    public static String extractRemaining(String reply) {
        String judgment = extractJudgment(reply);
        if (judgment == null) {
            return null;
        }
        // 只认位于段首（行首或 ｜/| 分隔之后）的「剩余」标记：正文自身含「剩余」二字时
        // （如「清理剩余的3个文件」），lastIndexOf 会命中正文内层、把两轮实质不同的剩余
        // 清单折叠成相同尾巴，令进展判定连续误判停滞、误杀正在推进的循环
        int idx = indexOfSegmentHeadMarker(judgment);
        if (idx < 0) {
            return null;
        }
        String rest = judgment.substring(idx + LoopConstants.JUDGMENT_REMAINING_MARKER.length());
        // 剥掉标记后的分隔符（剩余：/剩余:）
        int start = 0;
        while (start < rest.length() && TOLERATED_SEPARATORS.indexOf(rest.charAt(start)) >= 0) {
            start++;
        }
        String remaining = rest.substring(start).trim();
        return remaining.isEmpty() ? null : remaining;
    }

    /** 前缀与判定词之间容忍的分隔符（模型常见的格式变体：全/半角冒号、竖线、空格）。 */
    private static final String TOLERATED_SEPARATORS = "：:｜| ";

    /**
     * 判定内容是否以给定<b>判定词</b>开头——「已完成：xxx」是小节标题不是判定词。
     *
     * <p>格式约定 {@code 未完成｜已完成：xxx｜剩余：yyy} 里「已完成：」是小节标题；降级到
     * 哨兵解析的场景本就是模型不守协议之时，漏写行首「未完成」直接写「已完成：无｜剩余：…」
     * 若被读成提议完成，无准则且 judge=off 的循环当轮即假完成。判定词后只允许行尾、
     * 分隔竖线或语句标点，紧跟冒号的一律视为小节标题。</p>
     */
    private static boolean isVerdictWord(String judgment, String word) {
        if (!judgment.startsWith(word)) {
            return false;
        }
        int p = word.length();
        if (p >= judgment.length()) {
            return true; // 恰好以判定词结束
        }
        char c = judgment.charAt(p);
        // 紧跟空白：跳过所有空白，若到行尾则是干净判定词（true）；若空白后仍有内容，则这是
        // 空格分隔的额外 token——脱离格式、语义歧义（「已完成 全部搞定」是强调完成，「已完成
        // 但仍需人工复核」是带保留的未尽），启发式无法可靠区分，一律保守判「非干净的完成提议」
        // （宁可漏报让循环靠停滞/上限收，绝不误报导致无准则 judge=off 循环假完成）
        if (Character.isWhitespace(c)) {
            int q = p;
            while (q < judgment.length() && Character.isWhitespace(judgment.charAt(q))) {
                q++;
            }
            return q >= judgment.length();
        }
        // 紧跟冒号 = 小节标题（已完成：xxx），不是判定
        if (c == '：' || c == ':') {
            return false;
        }
        // 紧跟文字/字母/数字（含中日韩表意文字）= 构成更长词组（已完成了初稿 / 已完成初稿），不是判定；
        // 紧跟其余（语句标点、竖线、emoji 等非文字符号）= 句法完整的独立判定词的自然边界，是判定
        // （如「已完成，所有任务结束」「已完成｜…」「已完成✅」「已完成。」）
        return !Character.isLetterOrDigit(c);
    }

    /**
     * 定位首个处于「段首」位置的剩余标记：往前跳过空格后是行首或全/半角竖线才算
     * （格式约定各小节以 ｜ 分隔）；正文里出现的「剩余」二字不构成段首，跳过继续找。
     */
    private static int indexOfSegmentHeadMarker(String judgment) {
        int marker = LoopConstants.JUDGMENT_REMAINING_MARKER.length();
        int from = 0;
        int idx;
        while ((idx = judgment.indexOf(LoopConstants.JUDGMENT_REMAINING_MARKER, from)) >= 0) {
            // 段首：往前跳过空白后是行首 / 全半角竖线，或标记前本就有空白分隔（模型常用空格代替 ｜）
            int b = idx - 1;
            while (b >= 0 && judgment.charAt(b) == ' ') {
                b--;
            }
            boolean atSegmentHead = b < 0 || judgment.charAt(b) == '｜' || judgment.charAt(b) == '|'
                    || b < idx - 1; // b < idx-1 表示标记前存在至少一个空格
            // 且标记后须紧跟冒号（剩余：yyy）才算真·小节，滤除正文「处理了剩余任务」这类无冒号用法
            // （否则空格分隔一放开，正文里的「剩余」二字会被误当小节标记）
            int a = idx + marker;
            while (a < judgment.length() && judgment.charAt(a) == ' ') {
                a++;
            }
            boolean colonFollows = a < judgment.length()
                    && (judgment.charAt(a) == '：' || judgment.charAt(a) == ':');
            if (atSegmentHead && colonFollows) {
                return idx;
            }
            from = idx + marker;
        }
        return -1;
    }

    /**
     * 提取最后一条判定行去掉前缀后的内容；无判定行返回 null。
     *
     * <p>容忍 {@code 【判定】：已完成} / {@code 【判定】| 已完成} 等分隔符变体——
     * 只剥离前缀后的常见分隔符，不做更激进的模糊匹配（保持判定的可预测性）。</p>
     */
    private static String extractJudgment(String reply) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        String[] lines = reply.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith(LoopConstants.JUDGMENT_LINE_PREFIX)) {
                String rest = trimmed.substring(LoopConstants.JUDGMENT_LINE_PREFIX.length());
                int start = 0;
                while (start < rest.length()
                        && TOLERATED_SEPARATORS.indexOf(rest.charAt(start)) >= 0) {
                    start++;
                }
                return rest.substring(start).trim();
            }
        }
        return null;
    }
}
