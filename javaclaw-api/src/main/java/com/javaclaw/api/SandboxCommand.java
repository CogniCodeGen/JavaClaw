package com.javaclaw.api;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 传给原生 Sandbox Host 的无 Shell 拼接命令。
 *
 * @param id 命令 ID
 * @param argv 可执行文件与参数
 * @param workingDirectory Workspace 内工作目录
 * @param environment 经过允许列表过滤的环境变量
 * @param standardInput 标准输入字节；所有权由本对象持有
 * @param mode 批处理或 PTY
 * @param timeout 最大运行时间
 */
public record SandboxCommand(
        String id,
        List<String> argv,
        Path workingDirectory,
        Map<String, String> environment,
        byte[] standardInput,
        SandboxMode mode,
        Duration timeout) {
    /** 复制可变输入并校验执行边界。 */
    public SandboxCommand {
        id = Preconditions.text(id, "id");
        argv = List.copyOf(argv);
        if (argv.isEmpty() || argv.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("argv must contain non-blank values");
        }
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        environment = Map.copyOf(environment);
        standardInput = Objects.requireNonNull(standardInput, "standardInput").clone();
        Objects.requireNonNull(mode, "mode");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public byte[] standardInput() {
        return standardInput.clone();
    }
}
