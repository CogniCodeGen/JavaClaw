package com.javaclaw.agent.risk;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 确定性只读命令判定 —— 托管任务中零副作用的查询类命令可直接放行，无需人工确认。
 *
 * <p>动机：托管场景下执行体常需读取工作目录之外的环境信息（如 {@code ls ~/.m2} 查本地仓库、
 * {@code which java} 查工具链）。这类命令路径越界但毫无副作用，走人工确认在无人值守时只会
 * 等满超时（默认 600s）按拒绝处理——既浪费时间又诱发执行体换路重试多烧 token。</p>
 *
 * <p>判定是<b>纯确定性白名单</b>（不依赖模型），方向保守：任何解析不确定都判为非只读、
 * 回落到原有的范围评估 + 人工确认路径。规则：</p>
 * <ol>
 *   <li>剥离无害重定向（{@code 2>&1}、{@code >/dev/null} 等）后，不得再含 {@code >} / {@code <}、
 *       命令替换（反引号、{@code $(}）、{@code eval} / {@code exec}</li>
 *   <li>按 {@code |} {@code ;} {@code &&} {@code ||} {@code &} 切分，<b>每段</b>命令头都必须命中只读白名单</li>
 *   <li>{@code find} 额外排除 {@code -exec}/{@code -delete} 等带副作用参数；{@code sed} 排除 {@code -i}</li>
 * </ol>
 */
public final class ReadOnlyCommands {

    private ReadOnlyCommands() {}

    /** 命令头白名单：只列纯查询、无写盘、无网络副作用的命令 */
    private static final Set<String> READ_ONLY_HEADS = Set.of(
            "ls", "cat", "head", "tail", "grep", "egrep", "fgrep", "rg", "wc",
            "stat", "file", "which", "whereis", "type", "pwd", "echo", "printf",
            "du", "df", "tree", "sort", "uniq", "cut", "diff", "cmp", "md5", "md5sum",
            "shasum", "sha256sum", "basename", "dirname", "readlink", "realpath",
            "date", "uname", "hostname", "id", "whoami", "find", "sed", "column", "nl",
            "java", "javac", "mvn", "gradle", "node", "npm", "python", "python3", "git"
    );

    /** 工具链/版本控制类命令头：仅当后续参数整体为查询形态时才算只读 */
    private static final Set<String> GUARDED_HEADS = Set.of(
            "java", "javac", "mvn", "gradle", "node", "npm", "python", "python3", "git");

    /** git 只读子命令 */
    private static final Set<String> GIT_READ_SUBCOMMANDS = Set.of(
            "status", "log", "diff", "show", "blame", "remote", "rev-parse", "ls-files");

    /** 无害重定向形态：丢弃 stderr/stdout 或合并流，均不产生文件写入 */
    private static final Pattern HARMLESS_REDIRECT = Pattern.compile(
            "2>&1|[12&]?>>?\\s*/dev/null");

    /** find 的副作用参数 */
    private static final Pattern FIND_SIDE_EFFECT = Pattern.compile(
            "-(exec|execdir|ok|okdir|delete|fprint\\w*)\\b");

    /**
     * 判定一条 shell 命令是否纯只读（零写盘、零网络发送副作用）。
     *
     * @param command 完整命令文本
     * @return true 仅当能确定性判定为只读；任何不确定均返回 false
     */
    public static boolean isReadOnly(String command) {
        if (command == null || command.isBlank()) return false;
        String cmd = HARMLESS_REDIRECT.matcher(command.trim()).replaceAll(" ");
        // 残留重定向 / 命令替换 / 内联求值：一律判非只读（写文件或可携带任意命令）
        if (cmd.contains(">") || cmd.contains("<")
                || cmd.contains("`") || cmd.contains("$(")
                || cmd.matches(".*\\b(eval|exec|xargs|awk)\\b.*")) {
            return false;
        }
        // 管道/顺序执行的每一段都必须只读
        for (String segment : cmd.split("\\|\\||&&|[|;&]")) {
            if (!segmentReadOnly(segment.trim())) return false;
        }
        return true;
    }

    private static boolean segmentReadOnly(String segment) {
        if (segment.isEmpty()) return false;
        String[] tokens = segment.split("\\s+");
        int i = 0;
        // 跳过前置环境变量赋值（FOO=bar cmd）
        while (i < tokens.length && tokens[i].matches("[A-Za-z_][A-Za-z0-9_]*=.*")) i++;
        if (i >= tokens.length) return false;
        String head = tokens[i].toLowerCase(Locale.ROOT);
        int slash = head.lastIndexOf('/');
        if (slash >= 0) head = head.substring(slash + 1);
        if (!READ_ONLY_HEADS.contains(head)) return false;

        String rest = String.join(" ", java.util.Arrays.asList(tokens).subList(i + 1, tokens.length));
        if ("find".equals(head)) return !FIND_SIDE_EFFECT.matcher(rest).find();
        if ("sed".equals(head)) return !rest.matches(".*(^|\\s)-i\\S*.*");
        if (GUARDED_HEADS.contains(head)) {
            if ("git".equals(head)) {
                return i + 1 < tokens.length
                        && GIT_READ_SUBCOMMANDS.contains(tokens[i + 1].toLowerCase(Locale.ROOT));
            }
            // 工具链命令仅版本/帮助查询算只读（mvn compile、npm install 等都有写副作用）
            return rest.matches("(--?version|--help)(\\s.*)?");
        }
        return true;
    }
}
