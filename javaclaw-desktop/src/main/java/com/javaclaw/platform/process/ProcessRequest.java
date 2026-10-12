package com.javaclaw.platform.process;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 外部进程的不可变启动要求。
 *
 * @param name           诊断名称
 * @param command        argv；每一项直接交给 {@link ProcessBuilder}，不经过 shell 拼接
 * @param workingDirectory 工作目录；null 表示继承当前目录
 * @param environment    追加或覆盖的环境变量
 * @param timeout        最长运行时间，必须大于零
 * @param outputLimitBytes stdout、stderr 各自保留的最大字节数；管道仍会被完整排空
 * @param charset        输出解码字符集
 */
public record ProcessRequest(
        String name,
        List<String> command,
        Path workingDirectory,
        Map<String, String> environment,
        Duration timeout,
        int outputLimitBytes,
        Charset charset) {

    public static final int DEFAULT_OUTPUT_LIMIT = 1_048_576;

    public ProcessRequest {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("进程名称不能为空");
        }
        if (command == null || command.isEmpty()
                || command.getFirst() == null || command.getFirst().isBlank()
                || command.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("进程 argv 必须包含有效的可执行文件且不能包含 null");
        }
        command = List.copyOf(command);
        workingDirectory = workingDirectory == null
                ? null : workingDirectory.toAbsolutePath().normalize();
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("进程超时必须大于零");
        }
        if (outputLimitBytes < 1) {
            throw new IllegalArgumentException("输出上限必须大于零");
        }
        charset = charset == null ? StandardCharsets.UTF_8 : charset;
    }

    public static ProcessRequest argv(String name, List<String> command, Duration timeout) {
        return new ProcessRequest(name, command, null, Map.of(), timeout,
                DEFAULT_OUTPUT_LIMIT, StandardCharsets.UTF_8);
    }

    /**
     * 显式请求 shell 解释。调用方必须把未经信任的值作为独立 argv 参数传递，不能拼入脚本。
     */
    public static ProcessRequest shell(String name, String script, Duration timeout) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("shell 脚本不能为空");
        }
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win");
        return argv(name, windows
                ? List.of("cmd.exe", "/d", "/s", "/c", script)
                : List.of("/bin/sh", "-c", script), timeout);
    }

    public ProcessRequest withWorkingDirectory(Path value) {
        return new ProcessRequest(name, command, value, environment, timeout,
                outputLimitBytes, charset);
    }

    public ProcessRequest withEnvironment(Map<String, String> value) {
        return new ProcessRequest(name, command, workingDirectory, value, timeout,
                outputLimitBytes, charset);
    }

    public ProcessRequest withOutputLimit(int value) {
        return new ProcessRequest(name, command, workingDirectory, environment, timeout,
                value, charset);
    }
}
