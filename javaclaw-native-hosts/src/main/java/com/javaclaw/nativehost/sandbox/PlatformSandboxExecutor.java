package com.javaclaw.nativehost.sandbox;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.SandboxFrame;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;

/** macOS Seatbelt、Linux bubblewrap 与 Windows AppContainer 的 fail-closed SandboxExecutor。 */
public final class PlatformSandboxExecutor implements SandboxExecutor {
    /** 创建按当前平台选择已审计 backend 的执行器。 */
    public PlatformSandboxExecutor() {}

    /**
     * 在 OS 隔离、资源限制、输出上限和协作式取消共同约束下执行批处理命令。
     *
     * <p>实现说明：wrapper 环境被清空；命令不会经过 shell。Windows 代理执行先请求受治理清理，再强制回收残留 helper。
     */
    @Override
    public SandboxResult execute(SandboxCommand command, PermissionProfile permission, CancellationToken cancellation)
            throws Exception {
        return execute(command, permission, cancellation, SandboxRuntimeAccess.empty(), SandboxNetworkAccess.offline());
    }

    /**
     * 使用服务端冻结的托管运行库与网络租约执行批处理命令。
     *
     * @param command 已批准的命令及参数
     * @param permission 本次有效项目权限
     * @param cancellation 撤销或 Turn 结束时取消的令牌
     * @param runtimeAccess 受信任工具链及缓存访问范围，不接受模型路径
     * @param networkAccess 服务端持有并覆盖执行生命周期的网络租约
     * @return 已结束进程的有界输出与状态
     * @throws Exception 原生隔离、路径校验、取消或执行失败；不回退无隔离运行
     */
    public SandboxResult execute(
            SandboxCommand command,
            PermissionProfile permission,
            CancellationToken cancellation,
            SandboxRuntimeAccess runtimeAccess,
            SandboxNetworkAccess networkAccess)
            throws Exception {
        return execute(command, permission, cancellation, runtimeAccess, networkAccess, frame -> {});
    }

    /**
     * 执行批处理并同步观察共享输出预算内的 stdout/stderr 帧，不额外要求 PTY 权限。
     *
     * <p>观察者在专属排空线程中串行调用，返回前不继续读取该流，形成有界背压；调用方须保证回调有界且自行持久化游标。 首个观察异常只取消本次执行，经既有网络租约与 Windows helper
     * 协议终止进程，不中断调用方线程；清理异常保留为 suppressed。 观察者未在有界清理期限内退出时明确报告仍有所有者活跃；其专属线程继续负责完成当前回调，不被强制中断。
     *
     * @param command 已批准的命令及参数
     * @param permission 本次有效项目权限
     * @param cancellation 撤销或 Turn 结束时取消的令牌
     * @param runtimeAccess 受信任工具链及缓存访问范围
     * @param networkAccess 覆盖执行生命周期的服务端网络租约
     * @param observer 非空的同步输出观察者；只收到最终结果会保留的字节，两个流之间没有进程写入顺序保证
     * @return 已结束进程的同一有界输出与状态
     * @throws Exception 原生执行、观察或清理失败；观察异常不能回退为成功结果
     */
    public SandboxResult execute(
            SandboxCommand command,
            PermissionProfile permission,
            CancellationToken cancellation,
            SandboxRuntimeAccess runtimeAccess,
            SandboxNetworkAccess networkAccess,
            Consumer<SandboxFrame> observer)
            throws Exception {
        SandboxOutputObservation observation = new SandboxOutputObservation(cancellation, observer);
        observation.throwIfCancelled();
        ValidatedSandboxCommand validated =
                SandboxPolicyValidator.validate(command, permission, SandboxMode.BATCH, runtimeAccess, networkAccess);
        try (SandboxNetworkScope network = SandboxNetworkScope.open(validated)) {
            if (PlatformSandboxCommandBuilder.current() instanceof WindowsSandboxCommandBuilder windows) {
                try (WindowsHelperControl control = WindowsHelperControl.open()) {
                    SandboxLaunchPlan plan = windows.build(network.command(), control.directory());
                    return run(network.command(), plan, observation, control);
                }
            }
            SandboxLaunchPlan plan = PlatformSandboxCommandBuilder.current().build(network.command());
            return run(network.command(), plan, observation, null);
        }
    }

    /** 打开由 OS Sandbox、controlling terminal、进程树资源核算与单订阅者背压共同约束的 PTY 会话。 */
    @Override
    public SandboxSession open(SandboxCommand command, PermissionProfile permission, CancellationToken cancellation)
            throws Exception {
        return open(command, permission, cancellation, SandboxRuntimeAccess.empty(), SandboxNetworkAccess.offline());
    }

    /**
     * 打开带托管运行库及精确代理边界的 PTY；调用方须在所属 Turn 终态前关闭会话。
     *
     * @param command 已批准的 PTY 命令
     * @param permission 本次有效项目权限，必须允许 PTY
     * @param cancellation 撤销或 Turn 结束时取消的令牌
     * @param runtimeAccess 受信任工具链及缓存范围
     * @param networkAccess 覆盖整个会话生命周期的网络租约
     * @return 调用方独占拥有的有界会话
     * @throws Exception 隔离、校验或启动失败，不回退普通进程
     */
    public SandboxSession open(
            SandboxCommand command,
            PermissionProfile permission,
            CancellationToken cancellation,
            SandboxRuntimeAccess runtimeAccess,
            SandboxNetworkAccess networkAccess)
            throws Exception {
        CancellationToken checkedCancellation = Objects.requireNonNull(cancellation, "cancellation");
        checkedCancellation.throwIfCancelled();
        ValidatedSandboxCommand validated =
                SandboxPolicyValidator.validate(command, permission, SandboxMode.PTY, runtimeAccess, networkAccess);
        SandboxNetworkScope network = SandboxNetworkScope.open(validated);
        try {
            SandboxSession session = PlatformSandboxCommandBuilder.current() instanceof WindowsSandboxCommandBuilder
                    ? WindowsSandboxSession.start(network.command(), checkedCancellation)
                    : PlatformSandboxSession.start(network.command(), checkedCancellation);
            return network.own(session);
        } catch (Exception failure) {
            try {
                network.close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static SandboxResult run(
            ValidatedSandboxCommand command,
            SandboxLaunchPlan plan,
            SandboxOutputObservation observation,
            WindowsHelperControl control)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(plan.command());
        builder.directory(command.workingDirectory().toFile());
        builder.environment().clear();
        builder.environment().putAll(plan.environment());
        long started = System.nanoTime();
        Process process = builder.start();
        if (control != null) {
            control.started();
        }
        Exception failure = null;
        try {
            return collect(command, plan, observation, process, started, control);
        } catch (Exception executionFailure) {
            failure = executionFailure;
            throw executionFailure;
        } finally {
            terminateRemaining(process, command, control, failure);
        }
    }

    private static void terminateRemaining(
            Process process, ValidatedSandboxCommand command, WindowsHelperControl control, Exception failure) {
        if (process.isAlive()) {
            try {
                SandboxProcessMonitor.terminate(process, command, control);
            } catch (RuntimeException cleanup) {
                if (failure == null) {
                    throw cleanup;
                }
                failure.addSuppressed(cleanup);
            }
        }
    }

    private static SandboxResult collect(
            ValidatedSandboxCommand command,
            SandboxLaunchPlan plan,
            SandboxOutputObservation observation,
            Process process,
            long started,
            WindowsHelperControl control)
            throws Exception {
        try (SandboxBatchTasks tasks = new SandboxBatchTasks(process, command, observation)) {
            Exception failure = null;
            try {
                SandboxProcessMonitor.Outcome outcome =
                        SandboxProcessMonitor.await(process, command, plan, observation, control);
                tasks.awaitInput();
                byte[] standardOutput = tasks.stdout();
                byte[] standardError = tasks.stderr();
                observation.throwIfFailed();
                if (outcome.processLimitExceeded()) {
                    throw new IllegalStateException("sandbox child process limit exceeded");
                }
                if (outcome.memoryLimitExceeded()) {
                    throw new IllegalStateException("sandbox process tree memory limit exceeded");
                }
                int exitCode = outcome.timedOut() || outcome.cancelled() ? -1 : process.exitValue();
                return new SandboxResult(
                        exitCode,
                        standardOutput,
                        standardError,
                        outcome.timedOut(),
                        outcome.cancelled(),
                        Duration.ofNanos(System.nanoTime() - started));
            } catch (Exception executionFailure) {
                failure = executionFailure;
                throw executionFailure;
            } finally {
                // 必须先走网络租约/Windows helper 的受控终止，再等待管道观察者释放所有权。
                terminateRemaining(process, command, control, failure);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw observation.preferFailure(interrupted);
        } catch (Exception failure) {
            throw observation.preferFailure(failure);
        }
    }
}
