package com.javaclaw.sandbox.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A command that has already passed schema, capability and approval checks.
 *
 * @param id 非空白命令关联标识
 * @param argv 命令及参数的顺序列表；不经过 Shell 字符串拼接
 * @param workingDirectory 规范化后的工作目录；非空，解析已存在祖先的符号链接
 * @param environment 显式环境变量快照；null 归一为空 Map
 * @param policy 非空最终沙箱策略；启动前仍需平台能力验证
 * @param standardInput 标准输入文本；null 归一为空字符串
 * @param auxiliaryRole 服务端固定辅助进程角色；null/NONE 不授予附加系统接口，模型参数不能设置此字段
 */
public record SandboxCommand(
        String id,
        List<String> argv,
        Path workingDirectory,
        Map<String, String> environment,
        SandboxPolicy policy,
        String standardInput,
        AuxiliaryRole auxiliaryRole) {

    /** 仅由受信任装配指定的内部辅助进程种类；不是客户端可提交的 Turn 或工具权限选项。 */
    public enum AuxiliaryRole {
        NONE,
        BROWSER
    }

    public static final int MAX_STANDARD_INPUT_BYTES = 4 * 1024 * 1024;

    /** 创建没有标准输入文本的沙箱命令，其他约束与完整构造器一致。 */
    public SandboxCommand(
            String id,
            List<String> argv,
            Path workingDirectory,
            Map<String, String> environment,
            SandboxPolicy policy) {
        this(id, argv, workingDirectory, environment, policy, "", AuxiliaryRole.NONE);
    }

    /** 创建普通工具命令；即使命令名包含 BrowserServiceMain，也不会自动取得浏览器系统接口。 */
    public SandboxCommand(
            String id,
            List<String> argv,
            Path workingDirectory,
            Map<String, String> environment,
            SandboxPolicy policy,
            String standardInput) {
        this(id, argv, workingDirectory, environment, policy, standardInput, AuxiliaryRole.NONE);
    }

    /** 校验 argv、规范化目录并复制环境变量；拒绝超过 4 MiB UTF-8 的标准输入。 */
    public SandboxCommand {
        id = requireText(id, "id");
        argv = List.copyOf(Objects.requireNonNull(argv, "argv"));
        if (argv.isEmpty() || argv.stream().anyMatch(value -> value == null || value.isEmpty())) {
            throw new IllegalArgumentException("argv must contain non-empty values");
        }
        workingDirectory = SandboxPaths.canonicalize(workingDirectory);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        policy = Objects.requireNonNull(policy, "policy");
        standardInput = standardInput == null ? "" : standardInput;
        auxiliaryRole = auxiliaryRole == null ? AuxiliaryRole.NONE : auxiliaryRole;
        if (standardInput.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_STANDARD_INPUT_BYTES) {
            throw new IllegalArgumentException("standard input exceeds " + MAX_STANDARD_INPUT_BYTES + " bytes");
        }
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name).strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
