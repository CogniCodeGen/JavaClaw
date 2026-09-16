package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;

/** 通过平台原生 backend 启动无原始网络、无宿主环境的长生命周期 Worker。 */
public final class SandboxedWorkerLauncher {
    /** 创建无状态启动器。 */
    public SandboxedWorkerLauncher() {}

    /**
     * 启动 Worker 并把 stdin/stdout 所有权交给调用方。
     *
     * <p>stderr 直接丢弃，避免 Worker 异常把页面 URL、Cookie 或 storage state 写入宿主日志。任一原生 backend 不可用时本方法失败，不回退普通 ProcessBuilder。
     *
     * @param command 已收窄启动描述
     * @return 调用方拥有的受 Sandbox 约束进程
     * @throws IOException 路径解析或进程启动失败
     */
    public Process start(SandboxedWorkerCommand command) throws IOException {
        return start(command, SandboxErrorMode.DISCARD);
    }

    /**
     * 启动 Worker 并显式选择标准错误策略。
     *
     * <p>只有用户明确运行且输出会被严格限长的代码可使用 {@link SandboxErrorMode#MERGE_WITH_OUTPUT}；处理凭据、 Browser state 或文档的 Worker 必须保持默认丢弃策略。
     *
     * @param command 已收窄启动描述
     * @param errorMode 标准错误处理策略
     * @return 调用方拥有的受 Sandbox 约束进程
     * @throws IOException 路径解析或进程启动失败
     */
    public Process start(SandboxedWorkerCommand command, SandboxErrorMode errorMode) throws IOException {
        SandboxedWorkerCommand checked = Objects.requireNonNull(command, "command");
        SandboxErrorMode checkedErrorMode = Objects.requireNonNull(errorMode, "errorMode");
        ValidatedSandboxCommand validated = validate(checked);
        SandboxLaunchPlan plan = PlatformSandboxCommandBuilder.current().build(validated);
        ProcessBuilder builder = new ProcessBuilder(plan.command());
        builder.directory(validated.workingDirectory().toFile());
        builder.environment().clear();
        builder.environment().putAll(plan.environment());
        if (checkedErrorMode == SandboxErrorMode.MERGE_WITH_OUTPUT) {
            builder.redirectErrorStream(true);
        } else {
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        }
        return builder.start();
    }

    /**
     * 验证当前平台可见 Worker 的额外原生 IPC 前置条件；成功不代表浏览器或网络 Broker 验收通过。
     *
     * @throws IOException macOS 私有 namespace 不可用或实例隔离断言失败
     */
    public static void verifyInteractiveIsolation() throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
            com.javaclaw.nativehost.ffm.MacBootstrapNamespace.verifyIsolation();
        }
    }

    /**
     * 验证常驻可见 Worker 的整树回收发布前置条件。
     *
     * <p>现有 Linux PID namespace 与 Windows Job Object 是可复用的内核边界，但尚无本轮常驻链的真实 逃逸后代回收证据。macOS 仅靠 ProcessHandle
     * 观察也不能证明任意后代回收，因此三平台均明确拒绝发布。 不接受环境变量、能力文件或普通 Worker 测试代替这项证明。
     *
     * @throws IOException 当前平台尚未完成常驻可见进程树证明；不会启动目标或扩大原生权限
     */
    public static void verifyInteractiveProcessContainment() throws IOException {
        throw new IOException(
                "INTERACTIVE_TREE_CONTAINMENT_UNVERIFIED: resident browser process-tree proof is required");
    }

    /**
     * 启动由可信宿主续期的可见交互 Worker；原生 GUI/IPC 前置条件失败时不启动目标。
     *
     * <p>需要独占 private scratch。idleTimeout 是独立墙钟闲置期限，command.lifetime 继续限定原有执行资源预算； 不通过放宽普通 Worker
     * 描述获得常驻能力。页面后台流量不得调用续期方法。
     *
     * @param command 已冻结镜像、执行根和实例 scratch 的描述
     * @param idleTimeout 1 秒至 15 分钟，只有可信宿主动作可续期
     * @return 独占管道、监护进程树和原生租约的句柄
     * @throws IOException 原生隔离、私有 IPC 或监护启动失败
     */
    public SandboxedWorkerLease startInteractive(SandboxedWorkerCommand command, java.time.Duration idleTimeout)
            throws IOException {
        SandboxedWorkerCommand checked = Objects.requireNonNull(command, "command");
        java.time.Duration idle = Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idle.compareTo(java.time.Duration.ofSeconds(1)) < 0
                || idle.compareTo(java.time.Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("Worker idle timeout must be between 1 second and 15 minutes");
        }
        if (checked.privateScratch().isEmpty()) {
            throw new SecurityException("Interactive Worker requires exclusive private scratch");
        }
        verifyInteractiveIsolation();
        verifyInteractiveProcessContainment();
        return SandboxResidentWorkerLauncher.start(validate(checked), idle);
    }

    private static ValidatedSandboxCommand validate(SandboxedWorkerCommand worker) throws IOException {
        Path executable = Path.of(worker.argv().getFirst()).toRealPath();
        PermissionProfile permission = new PermissionProfile(
                "worker-sandbox",
                1,
                new FilePermission(
                        worker.readRoots(),
                        worker.writeRoots(),
                        worker.privateScratch().isPresent(),
                        false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(executable.getFileName().toString()), false, worker.lifetime()),
                new ToolPermission(Set.of(), ToolRisk.PROCESS, ApprovalRequirement.EVERY_CALL),
                worker.limits());
        SandboxCommand command = new SandboxCommand(
                worker.id(),
                replaceExecutable(worker.argv(), executable),
                worker.workingDirectory(),
                worker.environment(),
                new byte[0],
                SandboxMode.BATCH,
                worker.lifetime());
        ValidatedSandboxCommand validated = SandboxPolicyValidator.validate(command, permission, SandboxMode.BATCH);
        List<Path> executables = realExecutableRoots(worker.executableRoots(), validated.readRoots());
        if (worker.privateScratch().isPresent()) {
            worker.privateScratch()
                    .orElseThrow()
                    .verify(
                            validated.workingDirectory(),
                            validated.readRoots(),
                            validated.writeRoots(),
                            java.util.stream.Stream.concat(executables.stream(), java.util.stream.Stream.of(executable))
                                    .toList());
        }
        return validated.withExecutableRoots(executables);
    }

    private static List<String> replaceExecutable(List<String> argv, Path executable) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>(argv);
        result.set(0, executable.toString());
        return List.copyOf(result);
    }

    private static List<Path> realExecutableRoots(List<Path> roots, List<Path> readRoots) throws IOException {
        java.util.ArrayList<Path> result = new java.util.ArrayList<>();
        for (Path root : roots) {
            Path real = root.toRealPath();
            if (readRoots.stream().noneMatch(real::startsWith)) {
                throw new SecurityException("Worker executable root must be inside a read root");
            }
            result.add(real);
        }
        return List.copyOf(result);
    }
}
