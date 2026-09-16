package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.javaclaw.nativehost.ffm.MacBootstrapNamespace;

/**
 * 常驻 Worker 的独立可信监护入口；只继承管道，不解析页面数据，控制文件始终在目标 Sandbox 外。
 *
 * <p>监护 helper 保留 Mach requestor 权利；宿主退出、闲置到期、进程树超限或显式关闭均先终止目标树，再释放 namespace。 默认 stderr 丢弃，状态文件只能包含固定错误类别，不能记录页面、凭据或目标
 * argv。
 */
public final class SandboxWorkerMonitorMain {
    private static final int FAILURE = 72;
    private static final Duration MAX_LIFETIME = Duration.ofHours(24);

    private SandboxWorkerMonitorMain() {}

    /**
     * 读取可信宿主生成的参数，验证原生前置条件后启动并持续监护隔离 backend。
     *
     * @param arguments 控制目录、资源预算和平台 builder 生成的 argv
     */
    public static void main(String[] arguments) {
        System.exit(run(arguments));
    }

    // 返回值与控制文件共同构成宿主握手；入口只负责退出，监护失败不能丢失固定状态类别。
    static int run(String[] arguments) {
        int result = FAILURE;
        try {
            SandboxWorkerMonitorArguments request = SandboxWorkerMonitorArguments.parse(arguments);
            SandboxWorkerControl control = SandboxWorkerControl.open(request.control());
            result = execute(request, control);
        } catch (IOException | RuntimeException failure) {
            // 握手未能建立时父进程以 helper 退出作为失败；不把异常信息送入 Worker 协议流。
        }
        return result;
    }

    private static int execute(SandboxWorkerMonitorArguments request, SandboxWorkerControl control) throws IOException {
        try (MacBootstrapNamespace namespace = namespace(request.bootstrap())) {
            if (namespace != null) {
                namespace.installForChildren();
            }
            supervise(request, control);
        } catch (IOException failure) {
            String message = failure.getMessage();
            control.status(
                    message != null && message.startsWith("MAC_BOOTSTRAP_UNAVAILABLE:")
                            ? "FAILED:" + message
                            : "FAILED:NATIVE_WORKER_STARTUP");
            return FAILURE;
        } catch (RuntimeException failure) {
            control.status("FAILED:NATIVE_WORKER_MONITOR");
            return FAILURE;
        }
        control.status("CLOSED");
        return 0;
    }

    private static MacBootstrapNamespace namespace(boolean required) throws IOException {
        if (!required) {
            return null;
        }
        MacBootstrapNamespace.verifyIsolation();
        return MacBootstrapNamespace.open();
    }

    private static int supervise(SandboxWorkerMonitorArguments request, SandboxWorkerControl control)
            throws IOException {
        ProcessHandle owner = ProcessHandle.current().parent().orElseThrow();
        Process process = new ProcessBuilder(request.target()).inheritIO().start();
        boolean posix =
                !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
        SandboxWorkerTree tree = new SandboxWorkerTree(process, posix);
        Thread shutdown = new Thread(tree::terminate, "worker-monitor-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            control.status("READY:" + process.pid());
            await(request, control, owner, process, tree);
            return 0;
        } finally {
            tree.terminate();
            Runtime.getRuntime().removeShutdownHook(shutdown);
        }
    }

    private static void await(
            SandboxWorkerMonitorArguments request,
            SandboxWorkerControl control,
            ProcessHandle owner,
            Process process,
            SandboxWorkerTree tree)
            throws IOException {
        long started = System.nanoTime();
        long activity = started;
        String previous = control.state();
        while (process.isAlive() && owner.isAlive()) {
            String current = control.state();
            if (!current.startsWith("RUN:")) {
                return;
            }
            if (!current.equals(previous)) {
                previous = current;
                activity = System.nanoTime();
            }
            long now = System.nanoTime();
            if (now - activity >= request.idle().toNanos() || now - started >= MAX_LIFETIME.toNanos()) {
                return;
            }
            if (tree.refresh() > request.childProcesses() + 1L) {
                throw new IOException("Worker child process budget exceeded");
            }
            if (!request.nativeTreeLimits() && SandboxMemoryMeter.treeBytes(process) > request.memoryBytes()) {
                throw new IOException("Worker memory budget exceeded");
            }
            try {
                process.waitFor(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        requireControlledExit(process);
    }

    private static void requireControlledExit(Process process) throws IOException {
        if (!process.isAlive()) {
            // 未收到宿主关闭前根已退出时，短命子进程可能逃过观察；只能尝试回收，不能签发完整清理回执。
            throw new IOException("Worker exited before controlled shutdown");
        }
    }
}
