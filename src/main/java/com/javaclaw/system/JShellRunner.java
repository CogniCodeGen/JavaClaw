package com.javaclaw.system;

import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JShell 求值核心 —— 工具层（{@link JShellTools}）与 UI 层（技能中心脚本测试）共用的执行引擎。
 *
 * <p>一次性求值模型：每次 {@link #run} 新建独立 JShell 实例（默认远程执行引擎，
 * 代码在独立 JVM 进程中求值，与应用进程隔离），结束即关闭，无跨调用状态。
 * {@link #check} 仅做结构检查（片段切分 + 完整性诊断），不执行任何代码。</p>
 *
 * @author JavaClaw
 */
public final class JShellRunner {

    private static final Logger log = LoggerFactory.getLogger(JShellRunner.class);

    /** 输出最大行数（超出截断，与 CommandLineTools 对齐） */
    private static final int MAX_OUTPUT_LINES = 500;

    /** 单次返回最大字符数（防止超大输出炸上下文） */
    private static final int MAX_OUTPUT_CHARS = 8000;

    private JShellRunner() {
    }

    /**
     * 求值结果
     *
     * @param success   是否全部片段成功（无拒绝/无运行异常/未超时）
     * @param timedOut  是否超时中止
     * @param output    捕获的 stdout/stderr（已截断）
     * @param lastValue 最后一个表达式的值（无则空串）
     * @param problems  诊断列表（编译拒绝/运行异常/不完整片段）
     */
    public record ExecResult(boolean success, boolean timedOut, String output,
                             String lastValue, List<String> problems) {
    }

    // ==================== 执行 ====================

    /**
     * 在独立 JShell 实例中求值：preamble（预绑定变量等）→ code 逐段求值。
     * 超时经工作线程控制，到点 {@code stop()} 中止并返回已产生的部分输出。
     */
    public static ExecResult run(String code, List<String> preamble, int timeoutSec) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(outBuf, true, StandardCharsets.UTF_8);

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jshell-exec");
            t.setDaemon(true);
            return t;
        });

        // 默认远程执行引擎：用户代码在独立 JVM 进程求值，与应用进程隔离
        try (JShell shell = JShell.builder().out(capture).err(capture).build()) {
            Future<EvalReport> future = executor.submit(() -> {
                EvalReport report = new EvalReport();
                if (preamble != null) {
                    for (String pre : preamble) {
                        evalOne(shell, pre, report, true);
                    }
                }
                runSnippets(shell, code, report);
                return report;
            });

            EvalReport report;
            try {
                report = future.get(Math.max(1, timeoutSec), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                shell.stop();
                future.cancel(true);
                capture.flush();
                return new ExecResult(false, true,
                        truncate(outBuf.toString(StandardCharsets.UTF_8)), "", List.of());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                shell.stop();
                return new ExecResult(false, false, "", "", List.of("执行被中断"));
            } catch (Exception e) {
                return new ExecResult(false, false, "", "", List.of("执行异常：" + e.getMessage()));
            }

            capture.flush();
            return new ExecResult(!report.hasError, false,
                    truncate(outBuf.toString(StandardCharsets.UTF_8)),
                    report.lastValue == null ? "" : report.lastValue,
                    List.copyOf(report.problems));
        } catch (Exception e) {
            log.warn("JShell 求值失败", e);
            return new ExecResult(false, false, "", "",
                    List.of("JShell 启动/求值失败：" + e.getMessage()));
        } finally {
            executor.shutdownNow();
        }
    }

    // ==================== 结构检查（不执行） ====================

    /**
     * 结构检查：用 SourceCodeAnalysis 把代码切分为片段，报告每段类型与完整性问题。
     * <b>不执行任何代码</b>（不会启动远程求值进程的 eval），适合 UI 侧"检查"按钮。
     *
     * @return 检查报告行列表；首行为总结，后续为每个片段一行
     */
    public static List<String> check(String code) {
        List<String> report = new ArrayList<>();
        if (code == null || code.isBlank()) {
            report.add("（空代码）");
            return report;
        }
        try (JShell shell = JShell.builder().build()) {
            SourceCodeAnalysis analysis = shell.sourceCodeAnalysis();
            String remaining = code;
            int index = 0;
            boolean ok = true;
            List<String> lines = new ArrayList<>();
            while (remaining != null && !remaining.isBlank()) {
                SourceCodeAnalysis.CompletionInfo info = analysis.analyzeCompletion(remaining);
                String source = info.source();
                if (source == null || source.isBlank()) {
                    if (info.completeness() == SourceCodeAnalysis.Completeness.DEFINITELY_INCOMPLETE
                            || info.completeness() == SourceCodeAnalysis.Completeness.CONSIDERED_INCOMPLETE) {
                        lines.add("✗ 末尾片段不完整（括号/引号/语句未闭合）：" + snippetHead(remaining));
                        ok = false;
                    } else if (info.completeness() == SourceCodeAnalysis.Completeness.UNKNOWN) {
                        lines.add("✗ 无法解析的片段：" + snippetHead(remaining));
                        ok = false;
                    }
                    break;
                }
                index++;
                String kind = switch (info.completeness()) {
                    case COMPLETE, COMPLETE_WITH_SEMI -> "✓";
                    case EMPTY -> "·";
                    default -> "?";
                };
                lines.add(kind + " 片段 " + index + "：" + snippetHead(source));
                remaining = info.remaining();
            }
            report.add(ok
                    ? "结构检查通过：共 " + index + " 个片段（注：仅检查语法结构，类型/引用错误需运行测试发现）"
                    : "结构检查发现问题：");
            report.addAll(lines);
        } catch (Exception e) {
            report.add("结构检查失败：" + e.getMessage());
        }
        return report;
    }

    // ==================== 内部求值 ====================

    /** 求值过程的汇总结果 */
    private static final class EvalReport {
        String lastValue;
        final List<String> problems = new ArrayList<>();
        boolean hasError;
    }

    /**
     * 用 SourceCodeAnalysis 把多段代码切分为独立 snippet 逐段求值；
     * 遇到错误记录诊断但继续后续片段（与 jshell 交互行为一致）。
     */
    private static void runSnippets(JShell shell, String code, EvalReport report) {
        SourceCodeAnalysis analysis = shell.sourceCodeAnalysis();
        String remaining = code;
        while (remaining != null && !remaining.isBlank()) {
            SourceCodeAnalysis.CompletionInfo info = analysis.analyzeCompletion(remaining);
            String source = info.source();
            if (source == null || source.isBlank()) {
                if (info.completeness() == SourceCodeAnalysis.Completeness.DEFINITELY_INCOMPLETE
                        || info.completeness() == SourceCodeAnalysis.Completeness.CONSIDERED_INCOMPLETE) {
                    report.problems.add("代码末尾不完整，未求值的残余片段：" + snippetHead(remaining));
                    report.hasError = true;
                }
                break;
            }
            evalOne(shell, source, report, false);
            remaining = info.remaining();
        }
    }

    /** 求值单个片段并把状态/值/异常/诊断写入 report */
    private static void evalOne(JShell shell, String source, EvalReport report, boolean isPreamble) {
        List<SnippetEvent> events = shell.eval(source);
        for (SnippetEvent event : events) {
            Snippet snippet = event.snippet();
            if (event.status() == Snippet.Status.REJECTED) {
                StringBuilder diag = new StringBuilder();
                shell.diagnostics(snippet).forEach(d ->
                        diag.append(d.getMessage(Locale.SIMPLIFIED_CHINESE)).append("；"));
                report.problems.add("片段被拒绝：" + snippetHead(snippet.source())
                        + (diag.isEmpty() ? "" : " — " + diag));
                report.hasError = true;
            } else if (event.exception() != null) {
                report.problems.add("运行异常：" + event.exception().getClass().getSimpleName()
                        + ": " + nz(event.exception().getMessage())
                        + " @ " + snippetHead(snippet.source()));
                report.hasError = true;
            } else if (!isPreamble && event.value() != null && !event.value().isBlank()) {
                // 仅记录用户代码的最后表达式值（预绑定变量的回显不算）
                report.lastValue = event.value();
            }
        }
    }

    // ==================== 公共辅助 ====================

    /** Java 字符串字面量转义（预绑定变量注入用） */
    public static String escapeJava(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 把逗号分隔的 args 构造成 String[] 初始化体 */
    public static String buildArgsLiteral(String args) {
        if (args == null || args.isBlank()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String part : args.split(",")) {
            parts.add("\"" + escapeJava(part.strip()) + "\"");
        }
        return String.join(", ", parts);
    }

    /** 输出截断：先按行数，再按总字符数 */
    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String[] lines = text.split("\n", -1);
        String result = text;
        if (lines.length > MAX_OUTPUT_LINES) {
            result = String.join("\n", java.util.Arrays.copyOf(lines, MAX_OUTPUT_LINES))
                    + "\n...(输出超过 " + MAX_OUTPUT_LINES + " 行已截断)";
        }
        if (result.length() > MAX_OUTPUT_CHARS) {
            result = result.substring(0, MAX_OUTPUT_CHARS) + "\n...(输出超长已截断)";
        }
        return result;
    }

    /** 片段开头摘要（诊断定位用） */
    private static String snippetHead(String source) {
        if (source == null) {
            return "";
        }
        String oneLine = source.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 80) + "…";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
