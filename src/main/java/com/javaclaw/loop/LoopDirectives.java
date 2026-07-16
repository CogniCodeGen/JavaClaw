package com.javaclaw.loop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 循环指令解析：无参数 UI 时，从输入首行的 {@code @loop ...} 里解析循环参数，其余行为目标正文。
 *
 * <p>示例：{@code @loop interval=5m max=20 judge=on\n盯着构建，直到 mvn test 通过}。
 * 也支持单行写法：只把行首<b>连续的已知 {@code key=value} 前缀</b>解析为指令，
 * 首个非指令词起全部并入目标（如 {@code @loop max=5 盯着构建直到通过}；
 * 目标正文里出现的 {@code max=3} 等同形词不会被吃掉）。未写指令行时整段输入即目标，
 * 各参数取「未指定」由服务层套用配置默认。</p>
 *
 * @param goal            目标正文
 * @param intervalSeconds 轮间间隔秒数；{@code -1} 表示未指定（走自驱节奏）；
 *                        {@link #INTERVAL_INVALID} 表示写了 interval 但值解析失败
 *                        （用户意图明确是定时轮询，服务层退配置默认间隔而非零延迟自驱）
 * @param maxIterations   最大轮数；{@code -1} 表示未指定（用配置默认）；
 *                        {@link #MAX_INVALID} 表示写了 max 但值非法（解析失败或非正数，
 *                        服务层退配置默认并明示告警，预算键不做静默降级）
 * @param judge           是否启用验收员；{@code null} 表示未指定（用配置默认）
 * @param workDir         工作目录（准则核验基准）；{@code null} 表示未指定（用应用目录）。
 *                        指令按空白切分，不支持含空格路径
 */
public record LoopDirectives(String goal, long intervalSeconds, int maxIterations,
                             Boolean judge, String workDir) {

    private static final Logger log = LoggerFactory.getLogger(LoopDirectives.class);

    /** interval 写了但值非法的标记（区别于 -1 的「未指定」）。 */
    public static final long INTERVAL_INVALID = -2L;

    /** max 写了但值非法的标记（解析失败或非正数，区别于 -1 的「未指定」）。 */
    public static final int MAX_INVALID = -2;

    /** 解析输入。 */
    public static LoopDirectives parse(String input) {
        if (input == null) {
            return new LoopDirectives("", -1L, -1, null, null);
        }
        String text = input.strip();
        if (!isDirective(text)) {
            return new LoopDirectives(text, -1L, -1, null, null);
        }
        int newline = text.indexOf('\n');
        String directiveLine = newline < 0 ? text : text.substring(0, newline);
        String bodyGoal = newline < 0 ? "" : text.substring(newline + 1).strip();

        long interval = -1L;
        int max = -1;
        Boolean judge = null;
        String workDir = null;
        // 指令只解析行首连续的「已知键=值」前缀：遇到首个非指令词即认定目标正文开始，
        // 此后所有 token（含 max=3 这类恰与已知键同形的目标词）一律并入目标——
        // 从目标正文中间吃走 key=value 等于无声篡改用户目标（如「把配置里的 max=3 改成
        // max=10」会被吃成「把配置里的 改成」且轮数预算被设成文件内容里的数字）。
        // 含「=」的未知键目标词（targetSdk=34、URL 参数等）同理并入目标不丢弃
        StringBuilder inlineGoal = new StringBuilder();
        String body = directiveLine.substring(LoopConstants.DIRECTIVE_PREFIX.length()).trim();
        boolean goalStarted = false;
        for (String token : body.split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            int eq = token.indexOf('=');
            String key = (!goalStarted && eq > 0) ? token.substring(0, eq).trim() : "";
            String value = (!goalStarted && eq > 0) ? token.substring(eq + 1).trim() : "";
            boolean recognized = true;
            switch (key) {
                case LoopConstants.DIRECTIVE_KEY_INTERVAL -> {
                    long parsed = parseSeconds(value);
                    interval = parsed >= 0 ? parsed : INTERVAL_INVALID;
                }
                case LoopConstants.DIRECTIVE_KEY_MAX -> {
                    int parsed = parseIntSafe(value);
                    max = parsed > 0 ? parsed : MAX_INVALID;
                }
                case LoopConstants.DIRECTIVE_KEY_JUDGE -> {
                    judge = parseOnOff(value);
                    // 写了 judge= 但值无法识别（如 judge=yes）：明示忽略而非静默退默认——与 interval/max
                    // 的非法值告警一致。null 既表未指定又表非法，故在此有原始 value 时就地告警
                    if (judge == null && !value.isBlank()) {
                        log.warn("@loop judge 值无法识别「{}」，已忽略并退回配置默认"
                                + "（支持 on/off / true/false / 1/0）", value);
                    }
                }
                case LoopConstants.DIRECTIVE_KEY_WORKDIR -> workDir = value.isBlank() ? null : value;
                default -> recognized = false;
            }
            if (!recognized) {
                goalStarted = true;
                if (!inlineGoal.isEmpty()) {
                    inlineGoal.append(' ');
                }
                inlineGoal.append(token);
            }
        }
        String goal = inlineGoal.isEmpty()
                ? bodyGoal
                : (bodyGoal.isEmpty() ? inlineGoal.toString()
                                      : inlineGoal + "\n" + bodyGoal);
        return new LoopDirectives(goal, interval, max, judge, workDir);
    }

    /**
     * 是否为 {@code @loop} 指令：前缀后必须是空白或行尾（词边界）。
     * 只用 startsWith 会把「@loopback 服务…」「@loop间隔5分钟…」从词中间截断、静默篡改目标。
     */
    private static boolean isDirective(String text) {
        if (!text.startsWith(LoopConstants.DIRECTIVE_PREFIX)) {
            return false;
        }
        return text.length() == LoopConstants.DIRECTIVE_PREFIX.length()
                || Character.isWhitespace(text.charAt(LoopConstants.DIRECTIVE_PREFIX.length()));
    }

    /** 解析时长：支持 {@code s}/{@code m}/{@code min}/{@code h} 后缀，无后缀按秒；非法返回 -1。 */
    private static long parseSeconds(String value) {
        if (value == null || value.isBlank()) {
            return -1L;
        }
        try {
            String v = value.trim();
            long factor;
            String number;
            if (v.toLowerCase().endsWith("min")) { // 常见写法 5min，与 5m 等价
                factor = 60L;
                number = v.substring(0, v.length() - 3);
            } else {
                char unit = v.charAt(v.length() - 1);
                factor = switch (unit) {
                    case 's', 'S' -> 1L;
                    case 'm', 'M' -> 60L;
                    case 'h', 'H' -> 3600L;
                    default -> 0L; // 无后缀
                };
                number = factor == 0L ? v : v.substring(0, v.length() - 1);
            }
            long n = Long.parseLong(number.trim());
            if (n < 0) {
                return -1L;
            }
            return n * (factor == 0L ? 1L : factor);
        } catch (Exception e) {
            return -1L;
        }
    }

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static Boolean parseOnOff(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase();
        if (v.equals("on") || v.equals("true") || v.equals("1")) {
            return Boolean.TRUE;
        }
        if (v.equals("off") || v.equals("false") || v.equals("0")) {
            return Boolean.FALSE;
        }
        return null;
    }
}
