package com.javaclaw.system;

import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.skill.Skill;
import com.javaclaw.skill.SkillManager;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JShell 执行工具 —— 基于 JDK 内置 jdk.jshell 的 Java 代码求值通道（求值核心见 {@link JShellRunner}）。
 *
 * <p>两个入口：
 * <ul>
 *   <li>{@code jshell_exec} — 求值任意 Java 代码片段（变量/方法/类/表达式混排均可）</li>
 *   <li>{@code jshell_run_script} — 运行技能 {@code scripts/} 目录下的 .jsh/.java 脚本，
 *       使技能从"纯提示词"升级为"提示词 + 可执行能力"；脚本内可使用预绑定变量
 *       {@code SKILL_DIR}（技能目录绝对路径）与 {@code ARGS}（入参数组）</li>
 * </ul>
 *
 * <p>安全：两个工具均在 ToolRiskRegistry 登记为 CONFIRM——尤其 jshell_run_script 不能放行，
 * 否则 agent 经 skill_write_file（NOTIFY）写入脚本再执行会形成无确认的任意代码执行链。</p>
 *
 * @author JavaClaw
 */
public final class JShellTools {

    private static final Logger log = LoggerFactory.getLogger(JShellTools.class);

    /** 超时硬上限（秒） */
    private static final int MAX_TIMEOUT_SECONDS = 600;

    /** 可执行的脚本扩展名 */
    private static final List<String> SCRIPT_EXTENSIONS = List.of(".jsh", ".java");

    // ==================== 工具 1：任意片段求值 ====================

    @Tool(name = "jshell_exec",
            description = "用 JShell（JDK 内置 Java REPL）求值一段 Java 代码并返回输出与结果。" +
                    "支持变量声明、方法/类定义、表达式、System.out 打印的任意组合，按顺序逐段求值。" +
                    "代码在独立 JVM 进程中执行，每次调用相互独立（变量不跨调用保留）。" +
                    "适合：快速计算、验证 Java API 行为、数据转换等无需建工程的 Java 任务。")
    public String execJava(
            @ToolParam(name = "code", description = "要求值的 Java 代码（可多段：声明/表达式/打印混排）") String code,
            @ToolParam(name = "timeout_seconds",
                    description = "可选：执行超时秒数（默认读配置 jshell.exec.timeout.seconds=60，上限 600）",
                    required = false) Integer timeoutSeconds) {
        if (code == null || code.isBlank()) {
            return ToolResponse.error("jshell_exec", "code 为空，请提供要求值的 Java 代码。");
        }
        return toResponse("jshell_exec",
                JShellRunner.run(code, List.of(), resolveTimeout(timeoutSeconds)),
                resolveTimeout(timeoutSeconds));
    }

    // ==================== 工具 2：技能脚本运行 ====================

    @Tool(name = "jshell_run_script",
            description = "用 JShell 运行某技能 scripts/ 目录下的 Java 脚本（.jsh 或 .java）。" +
                    "当技能指令要求执行其自带脚本时调用本工具。脚本内可直接使用两个预绑定变量：" +
                    "SKILL_DIR（String，技能目录绝对路径，可用于访问技能的 assets/ 等文件）、" +
                    "ARGS（String[]，调用方传入的参数数组）。每次调用独立求值，不跨调用保留状态。")
    public String runSkillScript(
            @ToolParam(name = "skill_name", description = "技能名称，须与「可用技能目录」中展示的名称一致") String skillName,
            @ToolParam(name = "script", description = "脚本文件名（scripts/ 下，如 process.jsh）") String script,
            @ToolParam(name = "args", description = "可选：传给脚本的参数，逗号分隔（脚本内经 ARGS 数组读取）",
                    required = false) String args,
            @ToolParam(name = "timeout_seconds",
                    description = "可选：执行超时秒数（默认读配置 jshell.exec.timeout.seconds=60，上限 600）",
                    required = false) Integer timeoutSeconds) {
        Skill skill = SkillManager.getInstance().getSkillByName(skillName == null ? "" : skillName.strip());
        if (skill == null || !skill.isEnabled()) {
            return ToolResponse.error("jshell_run_script",
                    "未找到名为「" + (skillName == null ? "" : skillName.strip()) + "」的已启用技能。");
        }

        Path scriptFile = resolveScript(skill, script);
        if (scriptFile == null) {
            List<String> available = listScripts(skill);
            return ToolResponse.error("jshell_run_script",
                    "技能「" + skill.getName() + "」中不存在脚本「" + script + "」（或非 .jsh/.java 文件）。"
                            + "可用脚本：" + (available.isEmpty() ? "（无）" : String.join("、", available)));
        }

        String code;
        try {
            code = Files.readString(scriptFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return ToolResponse.error("jshell_run_script", "读取脚本失败：" + e.getMessage());
        }

        int timeout = resolveTimeout(timeoutSeconds);
        log.info("jshell_run_script 运行技能脚本: {}/scripts/{}", skill.getId(), scriptFile.getFileName());
        return toResponse("jshell_run_script",
                JShellRunner.run(code, buildPreamble(skill, args), timeout), timeout);
    }

    // ==================== 结果包装 ====================

    /** 把 ExecResult 包装为 ToolResponse 三态文案 */
    private static String toResponse(String toolName, JShellRunner.ExecResult result, int timeoutSec) {
        if (result.timedOut()) {
            return ToolResponse.timeout(toolName, timeoutSec,
                    "Java 代码执行超时已中止。"
                            + (result.output().isBlank() ? "" : "\n已产生的输出：\n" + result.output()));
        }

        StringBuilder sb = new StringBuilder();
        if (!result.output().isBlank()) {
            sb.append("[输出]\n").append(result.output().stripTrailing()).append("\n");
        }
        if (!result.lastValue().isBlank()) {
            sb.append("[最后表达式值] ").append(result.lastValue()).append("\n");
        }
        if (!result.problems().isEmpty()) {
            sb.append("[诊断]\n");
            for (String problem : result.problems()) {
                sb.append("- ").append(problem).append("\n");
            }
        }
        if (sb.isEmpty()) {
            sb.append("（执行完成，无输出）");
        }

        return result.success()
                ? ToolResponse.success(toolName, sb.toString())
                : ToolResponse.error(toolName, "代码执行存在错误：\n" + sb);
    }

    // ==================== 脚本解析（工具层与 UI 层共用） ====================

    /** 构造技能脚本的预绑定变量片段：SKILL_DIR + ARGS */
    public static List<String> buildPreamble(Skill skill, String args) {
        List<String> preamble = new ArrayList<>();
        preamble.add("String SKILL_DIR = \"" + JShellRunner.escapeJava(
                skill.getDirectory().toAbsolutePath().normalize().toString()) + "\";");
        preamble.add("String[] ARGS = new String[]{" + JShellRunner.buildArgsLiteral(args) + "};");
        return preamble;
    }

    /**
     * 把脚本名解析到技能 scripts/ 目录内（穿越防护），非脚本扩展名/越界/不存在返回 null
     */
    public static Path resolveScript(Skill skill, String script) {
        if (skill.getDirectory() == null || script == null || script.isBlank()) {
            return null;
        }
        String cleaned = script.strip().replace('\\', '/');
        if (cleaned.startsWith(Skill.SCRIPTS_DIR + "/")) {
            cleaned = cleaned.substring(Skill.SCRIPTS_DIR.length() + 1);
        }
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (SCRIPT_EXTENSIONS.stream().noneMatch(lower::endsWith)) {
            return null;
        }
        Path scriptsDir = skill.getDirectory().resolve(Skill.SCRIPTS_DIR).toAbsolutePath().normalize();
        Path target = scriptsDir.resolve(cleaned).normalize();
        if (!target.startsWith(scriptsDir) || !Files.isRegularFile(target)) {
            return null;
        }
        return target;
    }

    /** 列出技能 scripts/ 下可执行的脚本文件名 */
    public static List<String> listScripts(Skill skill) {
        List<String> names = new ArrayList<>();
        if (skill.getDirectory() == null) {
            return names;
        }
        Path scriptsDir = skill.getDirectory().resolve(Skill.SCRIPTS_DIR);
        if (!Files.isDirectory(scriptsDir)) {
            return names;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(scriptsDir)) {
            for (Path file : files) {
                String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(file) && SCRIPT_EXTENSIONS.stream().anyMatch(lower::endsWith)) {
                    names.add(file.getFileName().toString());
                }
            }
        } catch (Exception e) {
            log.debug("列出技能脚本失败: {}", scriptsDir);
        }
        return names;
    }

    // ==================== 辅助 ====================

    private static int resolveTimeout(Integer timeoutSeconds) {
        int configured = AgentConfig.getInstance().getJshellExecTimeoutSeconds();
        int effective = (timeoutSeconds == null || timeoutSeconds <= 0) ? configured : timeoutSeconds;
        return Math.min(Math.max(1, effective), MAX_TIMEOUT_SECONDS);
    }
}
