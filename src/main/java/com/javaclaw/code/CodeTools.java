package com.javaclaw.code;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.util.AtomicFileWriter;
import com.javaclaw.util.PathGuard;
import com.javaclaw.util.ProcessTerminator;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * 代码工程工具集（注册为 orchestrator 的 {@code coding} 工具组，按路由激活）。
 *
 * <p>补齐通用系统工具（{@code sys_file_*} / {@code cmd_execute}）在编程场景的短板：
 * <ul>
 *   <li>{@code code_read} —— <b>按行区间</b>读取（sys_file_read 只能整读、1MB 上限）</li>
 *   <li>{@code code_grep} —— 原生正则<b>内容检索</b>（免走高风险命令通道，可并行）</li>
 *   <li>{@code code_glob} —— 按模式<b>找文件</b></li>
 *   <li>{@code code_edit} —— <b>唯一片段替换</b>式精确编辑（不再整文件重写；read-before-edit）</li>
 *   <li>{@code code_insert} —— 按行号插入</li>
 *   <li>{@code code_set_project_root} —— 设定「项目根」，之后相对路径以其为基准且操作被 {@link PathGuard} 围栏其内</li>
 * </ul>
 *
 * <p><b>项目根围栏</b>：未设根时行为等同通用文件工具（须给绝对路径，仅基本校验）；一旦设根，
 * 相对路径以根解析、且所有 code_* 操作必须落在根内（symlink 安全，越界拒绝）。每条编排路径
 * 各持一个实例（构造期绑定 {@link ToolCallOrigin}），项目根是<b>会话级</b>状态、路径间不串。</p>
 *
 * <p><b>确认归属</b>：写类工具（code_edit / code_insert）经 {@link ToolConfirmationManager}
 * 按构造期 origin 令牌确认；只读类（read/grep/glob）不登记风险、直接执行。</p>
 *
 * @author JavaClaw
 */
public class CodeTools {

    private static final Logger log = LoggerFactory.getLogger(CodeTools.class);

    /** 检索/读取跳过的目录名（构建产物、VCS、依赖），避免噪声与失控遍历。 */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".hg", ".svn", "node_modules", "target", "build", "dist", "out",
            ".idea", ".gradle", ".mvn", "bin", "__pycache__", ".venv", "venv", ".next", ".cache");

    private static final long MAX_SCAN_FILE_BYTES = 2L * 1024 * 1024; // grep 单文件上限 2MB
    private static final long MAX_READ_FILE_BYTES = 8L * 1024 * 1024; // code_read 单文件上限 8MB
    private static final int MAX_READ_OUTPUT_CHARS = 60_000;          // 读取输出字符上限
    private static final int MAX_SCAN_FILES = 20_000;                 // grep/glob 遍历文件数硬顶

    /** 调用来源令牌（装配期绑定），高风险确认随调用传给 ToolConfirmationManager。 */
    private final ToolCallOrigin origin;

    /** 会话级项目根（可空）。设定后相对路径以其解析、code_* 操作被围栏其内。 */
    private volatile Path projectRoot;

    public CodeTools(ToolCallOrigin origin) {
        this.origin = origin == null ? ToolCallOrigin.UNKNOWN : origin;
    }

    /** 仅供单测：直接设定项目根，绕过 code_set_project_root 的确认交互（生产代码勿用）。 */
    void setProjectRootForTest(Path root) {
        this.projectRoot = root;
    }

    // ==================== 项目根 ====================

    @Tool(name = "code_set_project_root",
            description = "设定当前编程任务的项目根目录。设定后：相对路径以此为基准解析；所有 code_* "
                    + "文件操作被限制在此目录内（越界拒绝，防误改项目外文件）。传入绝对路径，须已存在且为目录。")
    public String setProjectRoot(
            @ToolParam(name = "path", description = "项目根目录的绝对路径") String path) {
        log.debug("工具调用: code_set_project_root('{}')", path);
        if (!ToolConfirmationManager.requestConfirmation(origin, "code_set_project_root",
                "设置项目根目录: " + path)) {
            return ToolResponse.error("code_set_project_root", "用户取消了操作");
        }
        try {
            if (path == null || path.isBlank()) {
                return ToolResponse.error("code_set_project_root", "路径不能为空");
            }
            Path root = Path.of(path).toAbsolutePath().normalize();
            if (!Files.exists(root)) {
                return ToolResponse.error("code_set_project_root", "目录不存在: " + root);
            }
            if (!Files.isDirectory(root)) {
                return ToolResponse.error("code_set_project_root", "路径不是目录: " + root);
            }
            this.projectRoot = root;
            return ToolResponse.success("code_set_project_root",
                    "项目根已设为: " + root + "\n之后相对路径以此解析，code_* 操作限定在此目录内。");
        } catch (Exception e) {
            log.error("code_set_project_root 执行异常", e);
            return ToolResponse.fromException("code_set_project_root", e);
        }
    }

    // ==================== 读取（按行区间） ====================

    @Tool(name = "code_read",
            description = "读取文本文件，可指定行区间（from_line/to_line，1 起、含端点；不传则整读）。"
                    + "输出带行号，便于后续用 code_edit 定位。大文件请用行区间分段读，避免一次拉满上下文。")
    public String read(
            @ToolParam(name = "path", description = "文件路径（相对路径以项目根解析）") String path,
            @ToolParam(name = "from_line", description = "起始行号（1 起，含）；0 或不传表示从头") int fromLine,
            @ToolParam(name = "to_line", description = "结束行号（含）；0 或不传表示到末尾") int toLine) {
        log.debug("工具调用: code_read('{}', {}, {})", path, fromLine, toLine);
        try {
            Path file = resolve(path);
            if (!Files.exists(file)) return ToolResponse.error("code_read", "文件不存在: " + path);
            if (!Files.isRegularFile(file)) return ToolResponse.error("code_read", "路径不是文件: " + path);
            long size = Files.size(file);
            if (size > MAX_READ_FILE_BYTES) {
                return ToolResponse.error("code_read",
                        "文件过大（" + formatSize(size) + "），请用 from_line/to_line 分段读取");
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int total = lines.size();
            int from = fromLine <= 0 ? 1 : fromLine;
            int to = toLine <= 0 ? total : Math.min(toLine, total);
            if (from > total) {
                return ToolResponse.error("code_read",
                        "起始行 " + from + " 超出文件总行数 " + total);
            }
            if (to < from) return ToolResponse.error("code_read", "结束行不能小于起始行");

            StringBuilder sb = new StringBuilder();
            sb.append("文件: ").append(file).append("　总行数: ").append(total)
                    .append("　显示: ").append(from).append('-').append(to).append("\n\n");
            boolean truncated = false;
            int width = Math.max(4, String.valueOf(to).length());
            for (int i = from; i <= to; i++) {
                String ln = String.format("%" + width + "d\t%s%n", i, lines.get(i - 1));
                if (sb.length() + ln.length() > MAX_READ_OUTPUT_CHARS) {
                    truncated = true;
                    sb.append("...(输出达 ").append(MAX_READ_OUTPUT_CHARS)
                            .append(" 字符上限，止于第 ").append(i - 1).append(" 行，请缩小行区间继续)\n");
                    break;
                }
                sb.append(ln);
            }
            String msg = sb.toString();
            return truncated ? ToolResponse.success("code_read", msg)
                    : ToolResponse.success("code_read", msg);
        } catch (PathReject e) {
            return ToolResponse.error("code_read", e.getMessage());
        } catch (Exception e) {
            log.error("code_read 执行异常", e);
            return ToolResponse.fromException("code_read", e);
        }
    }

    // ==================== 内容检索（grep） ====================

    @Tool(name = "code_grep",
            description = "在目录下按正则表达式检索文件内容，返回 文件:行号: 匹配行。跳过 .git/node_modules/target "
                    + "等构建与依赖目录及二进制/超大文件。用 glob 限定文件类型（如 **/*.java）。只读、可并行、免确认。")
    public String grep(
            @ToolParam(name = "pattern", description = "Java 正则表达式（对每行匹配）") String pattern,
            @ToolParam(name = "path", description = "检索起点目录（相对以项目根解析）；不传则用项目根或当前目录") String path,
            @ToolParam(name = "glob", description = "文件名 glob 过滤（如 **/*.java、*.py）；不传则全部文本文件") String glob,
            @ToolParam(name = "ignore_case", description = "是否忽略大小写") boolean ignoreCase,
            @ToolParam(name = "max_results", description = "最多返回匹配行数（默认 100，上限 500）") int maxResults) {
        log.debug("工具调用: code_grep('{}', path='{}', glob='{}')", pattern, path, glob);
        try {
            if (pattern == null || pattern.isBlank()) {
                return ToolResponse.error("code_grep", "检索模式不能为空");
            }
            Pattern re;
            try {
                re = Pattern.compile(pattern, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
            } catch (PatternSyntaxException pse) {
                return ToolResponse.error("code_grep", "正则表达式非法: " + pse.getMessage());
            }
            Path base = resolveDirBase(path);
            if (!Files.isDirectory(base)) {
                return ToolResponse.error("code_grep", "检索起点不是目录: " + base);
            }
            PathMatcher matcher = (glob == null || glob.isBlank()) ? null
                    : FileSystems.getDefault().getPathMatcher("glob:" + glob);
            PathMatcher altMatcher = (glob != null && glob.startsWith("**/"))
                    ? FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3))
                    : null;
            int cap = maxResults <= 0 ? 100 : Math.min(maxResults, 500);

            List<String> hits = new ArrayList<>();
            int[] scanned = {0};
            boolean[] scanCapped = {false};
            try (Stream<Path> walk = Files.walk(base)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (hits.size() >= cap) break;
                    if (Files.isDirectory(p)) continue;
                    if (isSkipped(base, p)) continue;
                    Path rel = base.relativize(p);
                    if (matcher != null && !matcher.matches(rel)
                            && (altMatcher == null || !altMatcher.matches(p.getFileName()))) continue;
                    if (++scanned[0] > MAX_SCAN_FILES) { scanCapped[0] = true; break; }
                    grepFile(base, p, re, hits, cap);
                }
            }
            if (hits.isEmpty()) {
                return ToolResponse.success("code_grep", "无匹配（检索起点: " + base + "）");
            }
            StringBuilder sb = new StringBuilder("匹配 ").append(hits.size())
                    .append(hits.size() >= cap ? "（达上限，可能还有更多）" : "").append("　起点: ").append(base).append("\n\n");
            for (String h : hits) sb.append(h).append('\n');
            if (scanCapped[0]) sb.append("\n(已扫描文件数达上限 ").append(MAX_SCAN_FILES).append("，检索提前结束)");
            return ToolResponse.success("code_grep", sb.toString());
        } catch (PathReject e) {
            return ToolResponse.error("code_grep", e.getMessage());
        } catch (Exception e) {
            log.error("code_grep 执行异常", e);
            return ToolResponse.fromException("code_grep", e);
        }
    }

    private void grepFile(Path base, Path file, Pattern re, List<String> hits, int cap) {
        try {
            if (Files.size(file) > MAX_SCAN_FILE_BYTES) return;
            byte[] head = readHead(file, 8192);
            if (isBinary(head)) return;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String rel = base.relativize(file).toString();
            for (int i = 0; i < lines.size() && hits.size() < cap; i++) {
                String line = lines.get(i);
                if (re.matcher(line).find()) {
                    String trimmed = line.length() > 300 ? line.substring(0, 300) + "…" : line;
                    hits.add(rel + ":" + (i + 1) + ": " + trimmed.strip());
                }
            }
        } catch (IOException ignore) {
            // 编码/权限问题的单个文件跳过，不打断整体检索
        }
    }

    // ==================== 文件名检索（glob） ====================

    @Tool(name = "code_glob",
            description = "按 glob 模式在目录下查找文件（如 **/*.java、src/**/*Test.java）。跳过构建/依赖目录。"
                    + "返回相对路径列表（按路径排序）。只读、免确认。")
    public String glob(
            @ToolParam(name = "pattern", description = "glob 模式（如 **/*.java）") String pattern,
            @ToolParam(name = "base", description = "查找起点目录（相对以项目根解析）；不传则用项目根或当前目录") String base) {
        log.debug("工具调用: code_glob('{}', base='{}')", pattern, base);
        try {
            if (pattern == null || pattern.isBlank()) {
                return ToolResponse.error("code_glob", "glob 模式不能为空");
            }
            Path root = resolveDirBase(base);
            if (!Files.isDirectory(root)) {
                return ToolResponse.error("code_glob", "查找起点不是目录: " + root);
            }
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            // **/*.ext 形式的 glob 不匹配根层文件（字面 / 要求至少一层目录）；补一个剥掉前导 **/ 的
            // 匹配器对文件名兜底，使 **/*.java 也能命中根目录下的 Foo.java
            PathMatcher altMatcher = pattern.startsWith("**/")
                    ? FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3))
                    : null;
            List<String> matches = new ArrayList<>();
            int[] scanned = {0};
            boolean[] capped = {false};
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (matches.size() >= 300) { capped[0] = true; break; }
                    if (Files.isDirectory(p)) continue;
                    if (isSkipped(root, p)) continue;
                    if (++scanned[0] > MAX_SCAN_FILES) { capped[0] = true; break; }
                    Path rel = root.relativize(p);
                    if (matcher.matches(rel) || (altMatcher != null && altMatcher.matches(p.getFileName()))) {
                        matches.add(rel.toString());
                    }
                }
            }
            if (matches.isEmpty()) {
                return ToolResponse.success("code_glob", "无匹配文件（起点: " + root + "）");
            }
            matches.sort(String::compareTo);
            StringBuilder sb = new StringBuilder("匹配 ").append(matches.size())
                    .append(capped[0] ? "（达上限，可能还有更多）" : "").append(" 个文件　起点: ").append(root).append("\n\n");
            for (String m : matches) sb.append(m).append('\n');
            return ToolResponse.success("code_glob", sb.toString());
        } catch (PathReject e) {
            return ToolResponse.error("code_glob", e.getMessage());
        } catch (Exception e) {
            log.error("code_glob 执行异常", e);
            return ToolResponse.fromException("code_glob", e);
        }
    }

    // ==================== 精确编辑（唯一片段替换） ====================

    @Tool(name = "code_edit",
            description = "精确编辑文件：把 old_string 唯一一次替换为 new_string（不整文件重写）。old_string 须在文件中"
                    + "恰好出现一次——0 次报「未找到」，多次报「不唯一，请给更长片段」。先用 code_read 看清上下文再改。")
    public String edit(
            @ToolParam(name = "path", description = "文件路径（相对以项目根解析）") String path,
            @ToolParam(name = "old_string", description = "要被替换的原文片段（须在文件中唯一）") String oldString,
            @ToolParam(name = "new_string", description = "替换后的新文本") String newString) {
        log.debug("工具调用: code_edit('{}')", path);
        if (!ToolConfirmationManager.requestConfirmation(origin, "code_edit", "编辑文件: " + path)) {
            return ToolResponse.error("code_edit", "用户取消了操作");
        }
        try {
            if (oldString == null || oldString.isEmpty()) {
                return ToolResponse.error("code_edit", "old_string 不能为空（新建文件请用 sys_file_write）");
            }
            if (newString == null) newString = "";
            Path file = resolve(path);
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                return ToolResponse.error("code_edit", "文件不存在: " + path);
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int first = content.indexOf(oldString);
            if (first < 0) {
                return ToolResponse.error("code_edit", "未找到 old_string，无法编辑（请用 code_read 核对原文）");
            }
            if (content.indexOf(oldString, first + oldString.length()) >= 0) {
                return ToolResponse.error("code_edit",
                        "old_string 在文件中出现多次，不唯一——请提供包含更多上下文的更长片段");
            }
            String updated = content.substring(0, first) + newString
                    + content.substring(first + oldString.length());
            AtomicFileWriter.writeString(file, updated);
            int oldLines = countLines(oldString), newLines = countLines(newString);
            return ToolResponse.success("code_edit",
                    "已编辑 " + file + "（替换 1 处，行数 " + oldLines + " → " + newLines + "）");
        } catch (PathReject e) {
            return ToolResponse.error("code_edit", e.getMessage());
        } catch (Exception e) {
            log.error("code_edit 执行异常", e);
            return ToolResponse.fromException("code_edit", e);
        }
    }

    @Tool(name = "code_insert",
            description = "在文件指定行后插入文本（line=0 表示插到文件开头）。用于新增代码块而不改动既有行。")
    public String insert(
            @ToolParam(name = "path", description = "文件路径（相对以项目根解析）") String path,
            @ToolParam(name = "line", description = "在此行号之后插入（1 起；0 表示插到开头）") int line,
            @ToolParam(name = "text", description = "要插入的文本（可含多行）") String text) {
        log.debug("工具调用: code_insert('{}', line={})", path, line);
        if (!ToolConfirmationManager.requestConfirmation(origin, "code_insert",
                "在 " + path + " 第 " + line + " 行后插入")) {
            return ToolResponse.error("code_insert", "用户取消了操作");
        }
        try {
            if (text == null) text = "";
            Path file = resolve(path);
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                return ToolResponse.error("code_insert", "文件不存在: " + path);
            }
            List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            if (line < 0 || line > lines.size()) {
                return ToolResponse.error("code_insert",
                        "行号越界: " + line + "（文件共 " + lines.size() + " 行，0~" + lines.size() + " 有效）");
            }
            List<String> toInsert = List.of(text.split("\n", -1));
            lines.addAll(line, toInsert);
            AtomicFileWriter.writeString(file, String.join("\n", lines));
            return ToolResponse.success("code_insert",
                    "已在 " + file + " 第 " + line + " 行后插入 " + toInsert.size() + " 行");
        } catch (PathReject e) {
            return ToolResponse.error("code_insert", e.getMessage());
        } catch (Exception e) {
            log.error("code_insert 执行异常", e);
            return ToolResponse.fromException("code_insert", e);
        }
    }

    // ==================== 结构化构建 / 测试 ====================

    /** 允许的构建/测试工具（首词，去路径后）——限定「runner」边界，杜绝沦为通用命令执行后门。 */
    private static final Set<String> BUILD_TOOLS = Set.of(
            "mvn", "mvnw", "gradle", "gradlew", "npm", "yarn", "pnpm", "npx",
            "tsc", "go", "cargo", "make", "pytest", "dotnet", "bazel", "ninja", "cmake");

    private static final int DEFAULT_BUILD_TIMEOUT_SECONDS = 600;
    private static final int MAX_BUILD_TIMEOUT_SECONDS = 1800;
    private static final int BUILD_MAX_OUTPUT_CHARS = 16_000;
    private static final int BUILD_MAX_ISSUE_LINES = 40;

    /** 从构建/测试输出中抽取「关键错误/失败行」的启发式模式（编译错误、断言失败、构建失败等）。 */
    private static final Pattern ISSUE_LINE = Pattern.compile(
            "(?i)(\\berror\\b|\\bfailed\\b|\\bfailure\\b|\\bFAIL\\b|BUILD FAILURE|cannot find symbol"
                    + "|Exception|Traceback|✗|\\.java:\\[?\\d+|\\.(?:ts|tsx|js|go|rs|py|kt|scala|c|cpp|h):\\d+"
                    + "|:\\d+:\\d+:|Tests?\\s+run:|assert)");

    @Tool(name = "code_build",
            description = "在项目根/当前目录运行构建，并从输出中抽取关键错误行（结构化）。不传 command 则按项目文件"
                    + "自动探测（pom.xml→mvn、build.gradle→gradle、package.json→npm、go.mod→go、Cargo.toml→cargo…）。"
                    + "仅允许构建/测试工具（mvn/gradle/npm/go/cargo/make…），慢构建可调 timeout_seconds（默认 600、上限 1800）。")
    public String build(
            @ToolParam(name = "command", description = "构建命令（如 'mvn -q compile'）；留空则自动探测") String command,
            @ToolParam(name = "timeout_seconds", description = "超时秒数（默认 600，上限 1800）") int timeoutSeconds) {
        return runBuildLike("code_build", command, timeoutSeconds, false);
    }

    @Tool(name = "code_test",
            description = "在项目根/当前目录运行测试，并从输出中抽取失败用例/断言行（结构化）。不传 command 则按项目文件"
                    + "自动探测（pom.xml→mvn test、package.json→npm test、go.mod→go test、Cargo.toml→cargo test…）。"
                    + "仅允许构建/测试工具，慢测试可调 timeout_seconds（默认 600、上限 1800）。")
    public String test(
            @ToolParam(name = "command", description = "测试命令（如 'mvn -q test'）；留空则自动探测") String command,
            @ToolParam(name = "timeout_seconds", description = "超时秒数（默认 600，上限 1800）") int timeoutSeconds) {
        return runBuildLike("code_test", command, timeoutSeconds, true);
    }

    private String runBuildLike(String tool, String command, int timeoutSeconds, boolean testMode) {
        log.debug("工具调用: {}('{}')", tool, command);
        try {
            Path base = (projectRoot != null) ? projectRoot : Path.of("").toAbsolutePath().normalize();
            if (!Files.isDirectory(base)) {
                return ToolResponse.error(tool, "工作目录无效: " + base);
            }
            String cmd = (command == null || command.isBlank()) ? autoDetect(base, testMode) : command.trim();
            if (cmd == null) {
                return ToolResponse.error(tool,
                        "无法自动探测构建/测试命令（未识别项目类型），请显式传 command");
            }
            List<String> argv;
            try {
                argv = parseCommand(cmd);
            } catch (IllegalArgumentException e) {
                return ToolResponse.error(tool, "构建命令格式非法: " + e.getMessage());
            }
            String first = executableName(argv.getFirst());
            if (!BUILD_TOOLS.contains(first)) {
                return ToolResponse.error(tool,
                        "只允许构建/测试工具（" + String.join("/", new java.util.TreeSet<>(BUILD_TOOLS))
                                + "），拒绝: " + first + "。通用命令请用 cmd_execute");
            }
            int timeout = timeoutSeconds <= 0 ? DEFAULT_BUILD_TIMEOUT_SECONDS
                    : Math.min(timeoutSeconds, MAX_BUILD_TIMEOUT_SECONDS);

            String action = testMode ? "运行项目测试" : "运行项目构建";
            ToolConfirmationManager.ConfirmOutcome confirmation =
                    ToolConfirmationManager.requestHighRiskCommandConfirmation(
                            origin, tool, action + "\n"
                                    + ToolConfirmationManager.buildCommandDescription(
                                            cmd, base.toString()));
            if (!confirmation.isAllow()) {
                return ToolResponse.error(tool, "用户拒绝了操作");
            }

            ExecResult r = execArgv(argv, base, timeout);
            if (r.timedOut) {
                return ToolResponse.error(tool, "执行超时（" + timeout + " 秒），已强制终止：" + cmd
                        + "。可调大 timeout_seconds（上限 " + MAX_BUILD_TIMEOUT_SECONDS + "）");
            }

            List<String> issues = extractIssues(r.output);
            StringBuilder sb = new StringBuilder();
            sb.append("命令: ").append(cmd).append("　目录: ").append(base)
                    .append("　退出码: ").append(r.exitCode).append(r.exitCode == 0 ? "（成功）" : "（失败）").append("\n\n");
            if (!issues.isEmpty()) {
                sb.append("关键错误/失败行（共 ").append(issues.size())
                        .append(issues.size() >= BUILD_MAX_ISSUE_LINES ? "，达上限" : "").append("）:\n");
                for (String s : issues) sb.append("  ").append(s).append('\n');
                sb.append('\n');
            }
            sb.append("——原始输出（尾部截断）——\n").append(tailExcerpt(r.output));
            String msg = sb.toString();
            return r.exitCode == 0 ? ToolResponse.success(tool, msg) : ToolResponse.error(tool, msg);
        } catch (Exception e) {
            log.error("{} 执行异常", tool, e);
            return ToolResponse.fromException(tool, e);
        }
    }

    /** 按项目根下的标志文件自动探测构建/测试命令；未识别返回 null。 */
    static String autoDetect(Path base, boolean testMode) {
        if (Files.exists(base.resolve("pom.xml"))) {
            return testMode ? "mvn -q -B test" : "mvn -q -B compile";
        }
        if (Files.exists(base.resolve("build.gradle")) || Files.exists(base.resolve("build.gradle.kts"))) {
            String g = Files.exists(base.resolve("gradlew")) ? "./gradlew" : "gradle";
            return testMode ? g + " test -q" : g + " assemble -q";
        }
        if (Files.exists(base.resolve("package.json"))) {
            return testMode ? "npm test" : "npm run build";
        }
        if (Files.exists(base.resolve("go.mod"))) {
            return testMode ? "go test ./..." : "go build ./...";
        }
        if (Files.exists(base.resolve("Cargo.toml"))) {
            return testMode ? "cargo test" : "cargo build";
        }
        if (Files.exists(base.resolve("Makefile"))) {
            return testMode ? "make test" : "make";
        }
        return null;
    }

    /** 从输出抽取匹配错误/失败启发式的行，去重保序、封顶。 */
    static List<String> extractIssues(String output) {
        List<String> out = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String line : output.split("\n")) {
            String s = line.strip();
            if (s.isEmpty() || !ISSUE_LINE.matcher(s).find()) continue;
            if (s.length() > 300) s = s.substring(0, 300) + "…";
            if (seen.add(s)) {
                out.add(s);
                if (out.size() >= BUILD_MAX_ISSUE_LINES) break;
            }
        }
        return out;
    }

    private static String tailExcerpt(String output) {
        if (output.length() <= BUILD_MAX_OUTPUT_CHARS) return output;
        return "...(前段省略)\n" + output.substring(output.length() - BUILD_MAX_OUTPUT_CHARS);
    }

    static String firstToken(String cmd) {
        String[] parts = cmd.trim().split("\\s+");
        String w = parts.length == 0 ? "" : parts[0];
        return executableName(w);
    }

    private static String executableName(String executable) {
        String w = executable == null ? "" : executable;
        int slash = Math.max(w.lastIndexOf('/'), w.lastIndexOf('\\'));
        return slash >= 0 ? w.substring(slash + 1) : w;
    }

    /**
     * 把构建命令解析为 argv，避免把已通过首词白名单的剩余文本交给 shell 再解释。
     * 支持常见的单/双引号与反斜杠转义；管道、重定向、命令连接等 shell 语法明确拒绝。
     */
    static List<String> parseCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("命令不能为空");
        }
        List<String> argv = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escaping = false;
        boolean tokenStarted = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaping) {
                token.append(c);
                tokenStarted = true;
                escaping = false;
                continue;
            }
            if (c == '\\' && quote != '\'') {
                char next = i + 1 < command.length() ? command.charAt(i + 1) : 0;
                if (next == '\\' || next == '"' || Character.isWhitespace(next)) {
                    escaping = true;
                } else {
                    // Windows 路径中的反斜杠不是转义符（如 .\gradlew、C:\work）。
                    token.append(c);
                }
                tokenStarted = true;
                continue;
            }
            if (quote != 0) {
                if (c == quote) quote = 0;
                else token.append(c);
                tokenStarted = true;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                tokenStarted = true;
            } else if (Character.isWhitespace(c)) {
                if (tokenStarted) {
                    argv.add(token.toString());
                    token.setLength(0);
                    tokenStarted = false;
                }
            } else if ("|&;<>`\n\r".indexOf(c) >= 0 || (c == '$' && i + 1 < command.length() && command.charAt(i + 1) == '(')) {
                throw new IllegalArgumentException("不支持 shell 管道、重定向或命令连接符");
            } else {
                token.append(c);
                tokenStarted = true;
            }
        }
        if (escaping || quote != 0) throw new IllegalArgumentException("引号或转义未闭合");
        if (tokenStarted) argv.add(token.toString());
        if (argv.isEmpty()) throw new IllegalArgumentException("命令不能为空");
        return List.copyOf(argv);
    }

    /** 构建/测试执行结果。 */
    private record ExecResult(int exitCode, String output, boolean timedOut) {}

    /**
     * 直接按 argv 执行（<b>不经 shell</b>），参数原样传给进程——git 提交信息等含特殊字符的
     * 参数不会被 shell 解释/注入。头尾截断收集输出。
     */
    private static ExecResult execArgv(List<String> argv, Path dir, int timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(dir.toFile());
        return runProcess(pb, timeoutSeconds);
    }

    /** 执行已配好命令与工作目录的进程：合并 stderr、stdin 接空设备、头尾环形缓冲收集输出、超时强杀。 */
    private static ExecResult runProcess(ProcessBuilder pb, int timeoutSeconds)
            throws IOException, InterruptedException {
        String os = System.getProperty("os.name", "").toLowerCase();
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File(os.contains("win") ? "NUL" : "/dev/null")));
        Process proc = pb.start();

        List<String> head = new ArrayList<>();
        ArrayDeque<String> tail = new ArrayDeque<>();
        Object lock = new Object();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line; int n = 0;
                while ((line = r.readLine()) != null) {
                    synchronized (lock) {
                        if (n < 120) head.add(line);
                        else { tail.addLast(line); if (tail.size() > 400) tail.removeFirst(); }
                    }
                    n++;
                }
            } catch (IOException ignored) {
            }
        }, "code-build-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished = ProcessTerminator.waitForOrTerminateOnInterrupt(
                proc, timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            ProcessTerminator.destroyTreeForcibly(proc);
            ProcessTerminator.waitForOrTerminateOnInterrupt(
                    proc, 2, TimeUnit.SECONDS);
            reader.interrupt();
            return new ExecResult(-1, "", true);
        }
        reader.join(2000);
        String output;
        synchronized (lock) {
            StringBuilder sb = new StringBuilder();
            for (String l : head) sb.append(l).append('\n');
            if (!tail.isEmpty()) {
                sb.append("...(中段省略)\n");
                for (String l : tail) sb.append(l).append('\n');
            }
            output = sb.toString();
        }
        return new ExecResult(proc.exitValue(), output, false);
    }

    // ==================== 版本控制（git） ====================

    private static final int GIT_TIMEOUT_SECONDS = 60;

    @Tool(name = "git_status",
            description = "显示 git 工作区状态（当前分支 + 变更文件，--short --branch）。在项目根/当前目录执行。只读、免确认。")
    public String gitStatus() {
        return runGit("git_status", List.of("git", "status", "--short", "--branch"));
    }

    @Tool(name = "git_diff",
            description = "显示 git 差异。staged=true 看已暂存(--staged)，否则看工作区未暂存改动；可用 path 限定文件/目录。只读。")
    public String gitDiff(
            @ToolParam(name = "path", description = "限定的文件/目录（相对仓库根）；不传则全部") String path,
            @ToolParam(name = "staged", description = "是否看已暂存区差异（false 看工作区）") boolean staged) {
        List<String> argv = new ArrayList<>(List.of("git", "diff"));
        if (staged) argv.add("--staged");
        if (path != null && !path.isBlank()) {
            argv.add("--");
            argv.add(path.trim());
        }
        return runGit("git_diff", argv);
    }

    @Tool(name = "git_log",
            description = "显示提交历史（--oneline）。max_count 限定条数（默认 20，上限 200），path 限定某文件的历史。只读。")
    public String gitLog(
            @ToolParam(name = "max_count", description = "返回的提交条数（默认 20，上限 200）") int maxCount,
            @ToolParam(name = "path", description = "限定某文件/目录的历史；不传则全部") String path) {
        int n = maxCount <= 0 ? 20 : Math.min(maxCount, 200);
        List<String> argv = new ArrayList<>(List.of("git", "log", "--oneline", "-n", String.valueOf(n)));
        if (path != null && !path.isBlank()) {
            argv.add("--");
            argv.add(path.trim());
        }
        return runGit("git_log", argv);
    }

    @Tool(name = "git_commit",
            description = "提交变更。stage_all=true 先 git add -A 暂存全部改动再提交；false 则只提交已暂存内容。需用户确认。")
    public String gitCommit(
            @ToolParam(name = "message", description = "提交信息") String message,
            @ToolParam(name = "stage_all", description = "是否先暂存全部改动（git add -A）再提交") boolean stageAll) {
        log.debug("工具调用: git_commit(stageAll={})", stageAll);
        if (!ToolConfirmationManager.requestConfirmation(origin, "git_commit",
                "git 提交" + (stageAll ? "（先暂存全部改动）" : "") + ": " + message)) {
            return ToolResponse.error("git_commit", "用户取消了操作");
        }
        try {
            if (message == null || message.isBlank()) {
                return ToolResponse.error("git_commit", "提交信息不能为空");
            }
            Path base = gitBase();
            if (!Files.isDirectory(base)) {
                return ToolResponse.error("git_commit", "工作目录无效: " + base);
            }
            if (stageAll) {
                ExecResult add = execArgv(List.of("git", "add", "-A"), base, GIT_TIMEOUT_SECONDS);
                if (add.timedOut) return ToolResponse.error("git_commit", "git add 超时");
                if (add.exitCode != 0) {
                    return ToolResponse.error("git_commit", "git add 失败（退出码 " + add.exitCode + "）\n" + add.output);
                }
            }
            // 经 argv 直传，提交信息不过 shell，含引号/分号/换行也不会注入
            ExecResult r = execArgv(List.of("git", "commit", "-m", message), base, GIT_TIMEOUT_SECONDS);
            if (r.timedOut) return ToolResponse.error("git_commit", "git commit 超时");
            String out = r.output.isBlank() ? "（无输出）" : tailExcerpt(r.output);
            return r.exitCode == 0
                    ? ToolResponse.success("git_commit", "提交成功　目录: " + base + "\n\n" + out)
                    : ToolResponse.error("git_commit",
                            "git commit 退出码 " + r.exitCode + "（无改动可提交？未配置身份？）\n" + out);
        } catch (Exception e) {
            log.error("git_commit 执行异常", e);
            return ToolResponse.fromException("git_commit", e);
        }
    }

    /** git 仓库基准目录：项目根优先，否则当前工作目录。 */
    private Path gitBase() {
        return (projectRoot != null) ? projectRoot : Path.of("").toAbsolutePath().normalize();
    }

    /** 只读 git 命令统一执行：在 gitBase() 内跑，退出码非 0 折成错误响应（提示可能非仓库）。 */
    private String runGit(String tool, List<String> argv) {
        log.debug("工具调用: {}", tool);
        try {
            Path base = gitBase();
            if (!Files.isDirectory(base)) return ToolResponse.error(tool, "工作目录无效: " + base);
            ExecResult r = execArgv(argv, base, GIT_TIMEOUT_SECONDS);
            if (r.timedOut) return ToolResponse.error(tool, "git 执行超时");
            String out = r.output.isBlank() ? "（无输出）" : tailExcerpt(r.output);
            return r.exitCode == 0
                    ? ToolResponse.success(tool, "目录: " + base + "\n\n" + out)
                    : ToolResponse.error(tool, "git 退出码 " + r.exitCode + "（当前目录是 git 仓库吗？）\n" + out);
        } catch (Exception e) {
            log.error("{} 执行异常", tool, e);
            return ToolResponse.fromException(tool, e);
        }
    }

    // ==================== 内部辅助 ====================

    /** 路径解析异常（越界/非法），由各工具捕获折成错误响应。 */
    private static final class PathReject extends RuntimeException {
        PathReject(String msg) { super(msg); }
    }

    /**
     * 解析文件路径：设了项目根则相对路径以根解析、并围栏其内（越界拒绝）；未设根则按绝对路径处理。
     */
    private Path resolve(String p) {
        if (p == null || p.isBlank()) throw new PathReject("路径不能为空");
        Path path = Path.of(p);
        Path root = projectRoot;
        Path resolved = (!path.isAbsolute() && root != null)
                ? root.resolve(path).normalize()
                : path.toAbsolutePath().normalize();
        if (root != null && !PathGuard.isInside(root, resolved)) {
            throw new PathReject("路径越出项目根 " + root + "，拒绝访问: " + p);
        }
        return resolved;
    }

    /** 解析检索/查找的起点目录：显式给了就解析并围栏；否则取项目根，再否则当前工作目录。 */
    private Path resolveDirBase(String p) {
        if (p != null && !p.isBlank()) return resolve(p);
        if (projectRoot != null) return projectRoot;
        return Path.of("").toAbsolutePath().normalize();
    }

    /** 是否处于应跳过的目录（构建/依赖/VCS）之下——按相对 base 的路径段判断。 */
    private boolean isSkipped(Path base, Path file) {
        Path rel;
        try {
            rel = base.relativize(file);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (Path seg : rel) {
            if (SKIP_DIRS.contains(seg.toString())) return true;
        }
        return false;
    }

    private static byte[] readHead(Path file, int n) throws IOException {
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[n];
            int read = in.readNBytes(buf, 0, n);
            if (read == n) return buf;
            byte[] exact = new byte[read];
            System.arraycopy(buf, 0, exact, 0, read);
            return exact;
        }
    }

    /** 含 NUL 字节即判为二进制，跳过检索。 */
    private static boolean isBinary(byte[] head) {
        for (byte b : head) {
            if (b == 0) return true;
        }
        return false;
    }

    private static int countLines(String s) {
        if (s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
