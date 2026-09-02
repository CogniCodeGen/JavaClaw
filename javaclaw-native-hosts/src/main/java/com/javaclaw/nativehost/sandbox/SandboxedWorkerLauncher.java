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

    private static ValidatedSandboxCommand validate(SandboxedWorkerCommand worker) throws IOException {
        Path executable = Path.of(worker.argv().getFirst()).toRealPath();
        PermissionProfile permission = new PermissionProfile(
                "worker-sandbox",
                1,
                new FilePermission(worker.readRoots(), worker.writeRoots(), false, false),
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
        return validated.withExecutableRoots(realExecutableRoots(worker.executableRoots(), validated.readRoots()));
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
