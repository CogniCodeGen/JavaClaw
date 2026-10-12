package com.javaclaw.code;

import com.javaclaw.platform.process.ProcessRequest;
import com.javaclaw.platform.process.ProcessResult;
import com.javaclaw.platform.process.ProcessRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes the deliberately restricted build and Git commands exposed by {@link CodeTools}.
 *
 * <p>The caller supplies an already-authorized argv. This class never invokes a shell, preserves
 * argument boundaries, bounds captured output and delegates timeout/cancellation semantics to the
 * shared {@link ProcessRunner}. Instances are thread-safe when their runner is thread-safe.</p>
 */
final class CodeProcessExecutor {

    private static final int MAX_CAPTURE_BYTES = 4 * 1024 * 1024;
    private static final int RETAINED_HEAD_LINES = 120;
    private static final int RETAINED_TAIL_LINES = 400;

    private final ProcessRunner processes;

    CodeProcessExecutor(ProcessRunner processes) {
        this.processes = Objects.requireNonNull(processes, "processes");
    }

    Result run(List<String> argv, Path directory, int timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessRequest request = new ProcessRequest(
                "code-" + Path.of(argv.getFirst()).getFileName(), argv, directory, Map.of(),
                Duration.ofSeconds(timeoutSeconds), MAX_CAPTURE_BYTES, StandardCharsets.UTF_8);
        ProcessResult result = processes.run(request);
        StringBuilder captured = new StringBuilder(result.stdout());
        appendStderr(captured, result.stderr());
        if (result.outputTruncated()) {
            captured.append("\n...(输出超过 4 MiB，后续省略)\n");
        }
        return new Result(result.exitCode(), retainHeadAndTail(captured.toString()), result.timedOut());
    }

    private static void appendStderr(StringBuilder captured, String stderr) {
        if (stderr.isBlank()) return;
        if (!captured.isEmpty() && captured.charAt(captured.length() - 1) != '\n') {
            captured.append('\n');
        }
        captured.append("[stderr]\n").append(stderr);
    }

    static List<String> parseArgv(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("命令不能为空");
        }
        List<String> argv = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escaping = false;
        boolean tokenStarted = false;
        for (int i = 0; i < command.length(); i++) {
            char current = command.charAt(i);
            if (escaping) {
                token.append(current);
                escaping = false;
                tokenStarted = true;
                continue;
            }
            if (current == '\\' && quote != '\'') {
                char next = i + 1 < command.length() ? command.charAt(i + 1) : 0;
                if (next == '\\' || next == '"' || Character.isWhitespace(next)) {
                    escaping = true;
                } else {
                    token.append(current); // Windows 路径中的反斜杠不是转义符。
                }
                tokenStarted = true;
                continue;
            }
            if (quote != 0) {
                if (current == quote) quote = 0;
                else token.append(current);
                tokenStarted = true;
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
                tokenStarted = true;
            } else if (Character.isWhitespace(current)) {
                if (tokenStarted) {
                    argv.add(token.toString());
                    token.setLength(0);
                    tokenStarted = false;
                }
            } else if (isShellSyntax(command, i, current)) {
                throw new IllegalArgumentException("不支持 shell 管道、重定向或命令连接符");
            } else {
                token.append(current);
                tokenStarted = true;
            }
        }
        if (escaping || quote != 0) throw new IllegalArgumentException("引号或转义未闭合");
        if (tokenStarted) argv.add(token.toString());
        if (argv.isEmpty()) throw new IllegalArgumentException("命令不能为空");
        return List.copyOf(argv);
    }

    static String executableName(String executable) {
        String value = executable == null ? "" : executable;
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private static boolean isShellSyntax(String command, int index, char current) {
        return "|&;<>`\n\r".indexOf(current) >= 0
                || (current == '$' && index + 1 < command.length()
                && command.charAt(index + 1) == '(');
    }

    private static String retainHeadAndTail(String output) {
        List<String> lines = output.lines().toList();
        int retainedLines = RETAINED_HEAD_LINES + RETAINED_TAIL_LINES;
        if (lines.size() <= retainedLines) return output;
        StringBuilder retained = new StringBuilder();
        lines.subList(0, RETAINED_HEAD_LINES)
                .forEach(line -> retained.append(line).append('\n'));
        retained.append("...(中段省略)\n");
        lines.subList(lines.size() - RETAINED_TAIL_LINES, lines.size())
                .forEach(line -> retained.append(line).append('\n'));
        return retained.toString();
    }

    record Result(int exitCode, String output, boolean timedOut) { }
}
