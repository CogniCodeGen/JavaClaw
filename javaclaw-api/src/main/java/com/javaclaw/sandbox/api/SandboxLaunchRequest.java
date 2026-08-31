package com.javaclaw.sandbox.api;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One-shot launcher wire model; no security policy is read from a temporary file.
 *
 * @param nonce 父进程生成的非空白一次性关联值，用于核验管道消息来源
 * @param commandId 非空白命令关联标识
 * @param argv 命令及参数的顺序列表；不经过 Shell 字符串拼接
 * @param workingDirectory 非空白工作目录字符串，恢复命令时再次规范化
 * @param environment 显式环境变量快照；null 归一为空 Map
 * @param standardInput 标准输入文本；null 归一为空字符串
 * @param policy 非空、可跨进程传输的策略快照
 * @param sessionOptions 长生命周期会话参数；一次性执行为 null
 * @param auxiliaryRole 服务端固定辅助角色；缺失表示 NONE，不能从临时文件或工具参数推断
 */
public record SandboxLaunchRequest(
        String nonce,
        String commandId,
        List<String> argv,
        String workingDirectory,
        Map<String, String> environment,
        String standardInput,
        WirePolicy policy,
        SandboxSessionOptions sessionOptions,
        SandboxCommand.AuxiliaryRole auxiliaryRole) {

    /** 复制进程输入并校验 nonce、argv 和策略；仅用于继承管道，不从可篡改临时文件加载。 */
    public SandboxLaunchRequest {
        nonce = require(nonce, "nonce");
        commandId = require(commandId, "commandId");
        argv = List.copyOf(Objects.requireNonNull(argv, "argv"));
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("argv must not be empty");
        }
        workingDirectory = require(workingDirectory, "workingDirectory");
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        standardInput = standardInput == null ? "" : standardInput;
        policy = Objects.requireNonNull(policy, "policy");
        auxiliaryRole = auxiliaryRole == null ? SandboxCommand.AuxiliaryRole.NONE : auxiliaryRole;
    }

    /** 将已校验命令映射为一次性启动请求；路径集合排序以稳定线协议表示。 */
    public static SandboxLaunchRequest from(String nonce, SandboxCommand command) {
        SandboxPolicy policy = command.policy();
        return new SandboxLaunchRequest(
                nonce,
                command.id(),
                command.argv(),
                command.workingDirectory().toString(),
                command.environment(),
                command.standardInput(),
                new WirePolicy(
                        policy.mode().name(),
                        strings(policy.readableRoots()),
                        strings(policy.writableRoots()),
                        strings(policy.protectedRoots()),
                        policy.network().mode().name(),
                        policy.network().allowedHosts(),
                        policy.inheritedEnvironment(),
                        policy.timeout().toMillis(),
                        policy.outputLimitBytes()),
                null,
                command.auxiliaryRole());
    }

    /** 创建携带非空会话参数的启动请求；保留与一次性命令相同的策略边界。 */
    public static SandboxLaunchRequest forSession(String nonce, SandboxCommand command, SandboxSessionOptions options) {
        SandboxLaunchRequest request = from(nonce, command);
        return new SandboxLaunchRequest(
                request.nonce(),
                request.commandId(),
                request.argv(),
                request.workingDirectory(),
                request.environment(),
                request.standardInput(),
                request.policy(),
                Objects.requireNonNull(options, "options"),
                command.auxiliaryRole());
    }

    /** 将线协议策略还原为领域命令并再次校验；未知模式或非法路径/预算会拒绝恢复。 */
    public SandboxCommand toCommand() {
        SandboxMode sandboxMode = SandboxMode.valueOf(policy.mode());
        NetworkPolicy network =
                new NetworkPolicy(NetworkPolicy.Mode.valueOf(policy.networkMode()), policy.allowedHosts());
        SandboxPolicy value = new SandboxPolicy(
                sandboxMode,
                paths(policy.readableRoots()),
                paths(policy.writableRoots()),
                paths(policy.protectedRoots()),
                network,
                policy.inheritedEnvironment(),
                Duration.ofMillis(policy.timeoutMillis()),
                policy.outputLimitBytes());
        return new SandboxCommand(
                commandId, argv, Path.of(workingDirectory), environment, value, standardInput, auxiliaryRole);
    }

    private static List<String> strings(Set<Path> paths) {
        return paths.stream().map(Path::toString).sorted().toList();
    }

    private static Set<Path> paths(List<String> paths) {
        return paths.stream().map(Path::of).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String require(String value, String name) {
        value = Objects.requireNonNull(value, name).strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * Launcher 管道中传递的策略表示；反序列化后仍需恢复为 SandboxPolicy 才能执行。
     *
     * @param mode 非空白 SandboxMode 名称
     * @param readableRoots 可读路径根集合；空集合不授予额外读取权限
     * @param writableRoots 可写路径根集合；不得覆盖受保护根目录
     * @param protectedRoots 只读保护根目录集合；权限求交时只能追加保护
     * @param networkMode 非空白 NetworkPolicy.Mode 名称
     * @param allowedHosts ALLOWLIST 主机集合；其他网络模式必须为空
     * @param inheritedEnvironment 允许继承的环境变量名集合；不会继承未列出的变量
     * @param timeoutMillis 执行超时，单位毫秒，必须为正数
     * @param outputLimitBytes 允许保留的输出字节上限，必须为正数
     */
    public record WirePolicy(
            String mode,
            List<String> readableRoots,
            List<String> writableRoots,
            List<String> protectedRoots,
            String networkMode,
            Set<String> allowedHosts,
            Set<String> inheritedEnvironment,
            long timeoutMillis,
            long outputLimitBytes) {
        /** 复制所有权限集合并校验正数预算；不因缺失集合而授予默认权限。 */
        public WirePolicy {
            mode = require(mode, "mode");
            readableRoots = readableRoots == null ? List.of() : List.copyOf(readableRoots);
            writableRoots = writableRoots == null ? List.of() : List.copyOf(writableRoots);
            protectedRoots = protectedRoots == null ? List.of() : List.copyOf(protectedRoots);
            networkMode = require(networkMode, "networkMode");
            allowedHosts = allowedHosts == null ? Set.of() : Set.copyOf(allowedHosts);
            inheritedEnvironment = inheritedEnvironment == null ? Set.of() : Set.copyOf(inheritedEnvironment);
            if (timeoutMillis < 1 || outputLimitBytes < 1) {
                throw new IllegalArgumentException("launcher limits must be positive");
            }
        }
    }
}
