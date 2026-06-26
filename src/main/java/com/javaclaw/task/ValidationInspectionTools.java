package com.javaclaw.task;

import com.javaclaw.agent.model.ToolResponse;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 子任务验收阶段的只读检查工具集。
 *
 * <p>验收智能体原生只有 {@code accept_subtask} / {@code reject_subtask}——执行智能体的
 * "极简 10 行摘要" 可能丢失关键交付物信息（例如是否真的写了代码文件），验收只能
 * 盲信摘要。实际观察到的一种反复失败模式是：执行方只报告"成功"但没写文件，验收
 * 无从核对，拖到 3 次打回上限把任务整掉。</p>
 *
 * <p>本工具集提供四个能力：</p>
 * <ul>
 *   <li>{@code inspect_list} — 列出目录内容</li>
 *   <li>{@code inspect_read} — 读取文件内容（≤ 64KB）</li>
 *   <li>{@code inspect_compile} — 跑构建工具验证产物可编译（白名单内的 mvn / gradle / javac / npm / tsc / python / go / cargo 等）</li>
 *   <li>{@code inspect_run_smoke} — 短超时运行入口验证可启动（白名单内的 java / node / python / mvn exec 等）</li>
 * </ul>
 *
 * <p>所有方法都强制校验路径位于任务的 {@code workDir} 内，拒绝越界访问；
 * inspect_compile / inspect_run_smoke 通过命令首词白名单 + 危险 shell 语法过滤限制可执行命令，
 * 让 ChallengerAgent 真正能"跑一下看能不能用"，而不是只看文件存不存在。</p>
 */
public final class ValidationInspectionTools {

    private static final Logger log = LoggerFactory.getLogger(ValidationInspectionTools.class);

    /** 编译/构建命令白名单 — 命令首词必须命中 */
    private static final Set<String> COMPILE_WHITELIST = Set.of(
            "javac", "mvn", "mvnw", "./mvnw",
            "gradle", "gradlew", "./gradlew",
            "npm", "pnpm", "yarn", "tsc", "node",
            "python", "python3",
            "go", "cargo", "dotnet", "make"
    );

    /** 冒烟运行命令白名单 — 命令首词必须命中 */
    private static final Set<String> RUN_WHITELIST = Set.of(
            "java", "mvn", "mvnw", "./mvnw",
            "gradle", "gradlew", "./gradlew",
            "node", "npm", "pnpm", "yarn",
            "python", "python3",
            "go", "cargo", "dotnet"
    );

    /** 编译命令最长执行时间（秒）— 大型项目首次拉依赖可能较慢 */
    private static final int COMPILE_TIMEOUT_SECONDS = 300;

    /** 冒烟运行命令最长执行时间（秒）— 跑入口拿首屏即可，超时强杀 */
    private static final int RUN_SMOKE_TIMEOUT_SECONDS = 30;

    /** 单次输出最多保留行数，超过部分截断（避免上下文爆炸） */
    private static final int MAX_OUTPUT_LINES = 200;

    /** 任务工作目录（归一化后的绝对路径）；为 null 表示未设定 → 拒绝一切访问 */
    private final Path workDir;

    public ValidationInspectionTools(String workDirPath) {
        if (workDirPath == null || workDirPath.isBlank()) {
            this.workDir = null;
        } else {
            this.workDir = Path.of(workDirPath).toAbsolutePath().normalize();
        }
    }

    @Tool(name = "inspect_list",
            description = "列出目录内容（只读）。用于验证子任务声称的文件/目录是否真的存在。路径必须在任务工作目录内。")
    public String list(
            @ToolParam(name = "path", description = "目录绝对路径（必须在任务工作目录内）") String path) {
        Path dir = resolveWithinWorkDir(path);
        if (dir == null) {
            return ToolResponse.error("inspect_list", "路径不在任务工作目录内或无效: " + path);
        }
        if (!Files.isDirectory(dir)) {
            return ToolResponse.error("inspect_list", "路径不是目录或不存在: " + path);
        }
        try (Stream<Path> stream = Files.list(dir)) {
            String entries = stream.sorted().map(p -> {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                    String type = attrs.isDirectory() ? "[目录]" : "[文件]";
                    String size = attrs.isDirectory() ? "-" : attrs.size() + "B";
                    return String.format("%-6s %-10s %s", type, size, p.getFileName());
                } catch (Exception e) {
                    return "[?] " + p.getFileName();
                }
            }).collect(Collectors.joining("\n"));
            String body = entries.isEmpty() ? "(空目录)" : entries;
            return ToolResponse.success("inspect_list",
                    "目录: " + dir + "\n" + body);
        } catch (Exception e) {
            return ToolResponse.fromException("inspect_list", e);
        }
    }

    /** 不指定行区间时，inspect_read 最多内联的行数（其余只给指针，避免整文件灌爆上下文）。 */
    private static final int MAX_INLINE_READ_LINES = 200;

    @Tool(name = "inspect_read",
            description = "读取文件内容（只读）。**优先用 from_line/to_line 只读需要的行区间**，避免把整文件灌进上下文。"
                    + "不指定区间时：小文件全文返回，大文件只回前若干行 + 总行数 + 提示你用区间续读。路径必须在任务工作目录内。")
    public String read(
            @ToolParam(name = "path", description = "文件绝对路径（必须在任务工作目录内）") String path,
            @ToolParam(name = "from_line", description = "可选：起始行号（1 起，含）。与 to_line 配合只读区间；留空读全文/头部。") String fromLine,
            @ToolParam(name = "to_line", description = "可选：结束行号（含）。留空则到文件末尾或头部上限。") String toLine) {
        Path file = resolveWithinWorkDir(path);
        if (file == null) {
            return ToolResponse.error("inspect_read", "路径不在任务工作目录内或无效: " + path);
        }
        if (!Files.isRegularFile(file)) {
            return ToolResponse.error("inspect_read", "路径不是文件或不存在: " + path);
        }
        try {
            List<String> lines = Files.readAllLines(file);
            int total = lines.size();
            Integer from = parseLine(fromLine);
            Integer to = parseLine(toLine);

            // 指定了区间：只回该区间（带行号），上下文最省
            if (from != null || to != null) {
                int a = Math.max(1, from == null ? 1 : from);
                int b = Math.min(total, to == null ? a + MAX_INLINE_READ_LINES - 1 : to);
                if (a > total) {
                    return ToolResponse.success("inspect_read",
                            "文件: " + file + "（共 " + total + " 行）\n起始行 " + a + " 超出文件长度。");
                }
                StringBuilder sb = new StringBuilder();
                for (int i = a; i <= b; i++) sb.append(i).append('\t').append(lines.get(i - 1)).append('\n');
                return ToolResponse.success("inspect_read",
                        "文件: " + file + "（共 " + total + " 行，显示 " + a + "-" + b + "）\n\n" + sb);
            }

            // 未指定区间：小文件全文，大文件只回头部 + 指针
            if (total <= MAX_INLINE_READ_LINES) {
                return ToolResponse.success("inspect_read",
                        "文件: " + file + "（共 " + total + " 行）\n\n" + String.join("\n", lines));
            }
            String head = String.join("\n", lines.subList(0, MAX_INLINE_READ_LINES));
            return ToolResponse.success("inspect_read",
                    "文件: " + file + "（共 " + total + " 行，仅显示前 " + MAX_INLINE_READ_LINES + " 行）\n"
                            + "如需后续内容，用 from_line/to_line 读指定区间（如 from_line=" + (MAX_INLINE_READ_LINES + 1)
                            + "）。\n\n" + head);
        } catch (Exception e) {
            return ToolResponse.fromException("inspect_read", e);
        }
    }

    /** 解析行号参数；空/非法返回 null。 */
    private static Integer parseLine(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            int v = Integer.parseInt(s.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Tool(name = "inspect_compile",
            description = "在任务工作目录（或其子目录）内执行**白名单内**的构建/编译命令，验证产物可编译。"
                    + "支持的命令首词：mvn / mvnw / gradle / gradlew / javac / npm / pnpm / yarn / tsc / "
                    + "node / python / python3 / go / cargo / dotnet / make。"
                    + "禁止 shell 管道、重定向、命令串接（;、&&、||、|、>、<、`、$()）。"
                    + "超时 " + COMPILE_TIMEOUT_SECONDS + " 秒；返回退出码 + 截断后的输出。"
                    + "用于核验「能不能编译通过」，编译失败应作为 critical 证据。"
                    + "**重要**：若实际项目位于工作目录的子目录（如 chinese-chess/pom.xml），"
                    + "必须用 subdir 参数指定子目录，否则 mvn / gradle 等会在错误的目录里跑找不到构建文件。")
    public String inspectCompile(
            @ToolParam(name = "command",
                    description = "完整构建命令，例如 'mvn -q -DskipTests compile'、'gradle build'、'npm run build'、'tsc --noEmit'") String command,
            @ToolParam(name = "subdir",
                    description = "可选：相对工作目录的子目录路径作为命令的 cwd（如 'chinese-chess'）。"
                            + "留空 / null 表示直接在工作目录根跑。子目录必须真实存在且在 workDir 内，否则报错。") String subdir) {
        return execWithinWorkDir("inspect_compile", command, subdir, COMPILE_WHITELIST, COMPILE_TIMEOUT_SECONDS);
    }

    @Tool(name = "inspect_run_smoke",
            description = "在任务工作目录（或其子目录）内**短超时**（" + RUN_SMOKE_TIMEOUT_SECONDS + "s）执行**白名单内**的运行命令做冒烟测试，"
                    + "验证产物可以正常启动并产出预期首屏输出。"
                    + "支持的命令首词：java / mvn / gradle / node / npm / python / python3 / go / cargo / dotnet 等。"
                    + "禁止 shell 管道、重定向、命令串接。返回退出码 + 截断后的输出（前 " + MAX_OUTPUT_LINES + " 行）。"
                    + "超时即强杀进程并标注 timeout — 服务器/守护进程类程序冒烟时一定会超时，但若超时前已稳定输出"
                    + "启动成功的横幅/端口监听等可视为已启动；CLI 类一次性程序应在超时前正常退出。"
                    + "项目在子目录时务必用 subdir 参数指定。")
    public String inspectRunSmoke(
            @ToolParam(name = "command",
                    description = "完整运行命令，例如 'java -jar target/app.jar'、'node dist/index.js'、'python main.py --help'") String command,
            @ToolParam(name = "subdir",
                    description = "可选：相对工作目录的子目录路径作为命令的 cwd。"
                            + "留空 / null 表示直接在工作目录根跑。") String subdir) {
        return execWithinWorkDir("inspect_run_smoke", command, subdir, RUN_WHITELIST, RUN_SMOKE_TIMEOUT_SECONDS);
    }

    /**
     * 根据 subdir 参数解析命令的 cwd。
     *
     * <ul>
     *   <li>{@code subdir} 为 null / 空 / "." → 直接用 workDir 根</li>
     *   <li>相对路径 → 相对 workDir 解析；绝对路径必须落在 workDir 内</li>
     *   <li>解析后路径必须真实存在且是目录</li>
     * </ul>
     *
     * @return 命令 cwd 的绝对 Path；非法时返回 null
     */
    private Path resolveCwd(String subdir) {
        if (workDir == null) return null;
        if (subdir == null || subdir.isBlank() || subdir.equals(".") || subdir.equals("./")) {
            return workDir;
        }
        try {
            Path candidate = Path.of(subdir);
            Path resolved = (candidate.isAbsolute() ? candidate : workDir.resolve(subdir))
                    .toAbsolutePath().normalize();
            if (!resolved.startsWith(workDir)) return null;
            if (!Files.isDirectory(resolved)) return null;
            return resolved;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把传入路径归一为绝对路径，并要求它位于 workDir 下；越界或 workDir 未配置时返回 null。
     */
    private Path resolveWithinWorkDir(String path) {
        if (workDir == null || path == null || path.isBlank()) return null;
        try {
            Path resolved = Path.of(path).toAbsolutePath().normalize();
            return resolved.startsWith(workDir) ? resolved : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 在 workDir（或其指定子目录）内执行命令，强制白名单 + 危险语法过滤 + 严格超时。
     *
     * <p>命令以 {@code /bin/sh -c} （或 Windows 的 {@code cmd.exe /c}）执行，
     * 但执行前会把整串命令按 shell 元字符切分，**首段**首词必须命中白名单；
     * 同时拒绝管道 / 重定向 / 命令串接 / 命令替换等可绕过白名单的语法，
     * 避免"白名单首词 + 后续接危险命令"的攻击面（如 {@code mvn || rm -rf /}）。</p>
     *
     * <p>{@code subdir} 参数允许 agent 指定子目录作为命令 cwd（修复"项目在 chinese-chess/
     * 子目录但 mvn 在 workDir 根跑找不到 pom.xml"这类失败模式）；为空时回退到 workDir 根。
     * 子目录必须真实存在且在 workDir 内，越界 / 不存在直接拒绝。</p>
     */
    private String execWithinWorkDir(String tool, String command, String subdir,
                                      Set<String> whitelist, int timeoutSec) {
        if (workDir == null) {
            return ToolResponse.error(tool, "任务工作目录未设置，禁止执行命令");
        }
        if (command == null || command.isBlank()) {
            return ToolResponse.error(tool, "命令不能为空");
        }
        String trimmed = command.trim();

        // 1) 拒绝危险 shell 语法 — 防止白名单首词后接危险命令
        String dangerHit = findDangerousShellSyntax(trimmed);
        if (dangerHit != null) {
            return ToolResponse.error(tool,
                    "禁止使用 shell 元字符 [" + dangerHit + "]；inspect 工具只允许执行单一白名单命令，"
                            + "不允许管道 / 重定向 / 命令串接 / 命令替换。");
        }

        // 2) 命令首词白名单校验
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length == 0) {
            return ToolResponse.error(tool, "命令为空");
        }
        String head = tokens[0];
        String headBare = head.contains("/") ? head.substring(head.lastIndexOf('/') + 1) : head;
        if (!whitelist.contains(head) && !whitelist.contains(headBare)) {
            return ToolResponse.error(tool,
                    "命令首词 [" + head + "] 不在白名单内。允许的命令首词：" + whitelist);
        }

        // 3) 解析 subdir → 命令 cwd（支持留空回退到 workDir 根）
        Path cwd = resolveCwd(subdir);
        if (cwd == null) {
            return ToolResponse.error(tool,
                    "subdir 不在任务工作目录内或不存在: " + subdir
                            + "（workDir=" + workDir + "）");
        }

        // 4) 实际执行
        try {
            ProcessBuilder pb;
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/c", trimmed);
            } else {
                pb = new ProcessBuilder("/bin/sh", "-c", trimmed);
            }
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);

            log.info("{} 执行：{} @ {}", tool, trimmed, cwd);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null && lineCount < MAX_OUTPUT_LINES) {
                    output.append(line).append("\n");
                    lineCount++;
                }
                if (lineCount >= MAX_OUTPUT_LINES) {
                    output.append("\n...（输出已截断，超过 ").append(MAX_OUTPUT_LINES).append(" 行）");
                }
            }

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return ToolResponse.timeout(tool, timeoutSec,
                        "已强制终止。\n命令: " + trimmed
                                + "\n已捕获输出:\n" + truncated(output));
            }

            int exitCode = process.exitValue();
            String body = "命令: " + trimmed
                    + "\n工作目录: " + cwd
                    + (cwd.equals(workDir) ? "" : "（任务根: " + workDir + "）")
                    + "\n退出码: " + exitCode
                    + "\n输出:\n" + truncated(output);
            if (exitCode == 0) {
                return ToolResponse.success(tool, body);
            }
            return ToolResponse.error(tool, body);

        } catch (Exception e) {
            log.warn("{} 执行异常：{}", tool, e.getMessage());
            return ToolResponse.fromException(tool, e);
        }
    }

    /**
     * 检测命令中是否包含会绕过白名单首词检查的危险 shell 语法。
     *
     * <p>白名单只校验首词，但 {@code mvn build || rm -rf /} 整串通过 sh -c 执行时，
     * 后段命令会绕过校验。此处统一拒绝管道、重定向、命令串接、命令替换、反引号等。</p>
     *
     * @return 命中的危险标记字符串；为 null 表示安全
     */
    private static String findDangerousShellSyntax(String command) {
        if (command.contains("&&")) return "&&";
        if (command.contains("||")) return "||";
        if (command.contains(";")) return ";";
        if (command.contains("|")) return "|";
        if (command.contains(">")) return ">";
        if (command.contains("<")) return "<";
        if (command.contains("`")) return "`";
        if (command.contains("$(")) return "$(";
        if (command.contains("&")) return "&";
        return null;
    }

    private static String truncated(StringBuilder sb) {
        String s = sb.toString().trim();
        return s.isEmpty() ? "（无输出）" : s;
    }
}
