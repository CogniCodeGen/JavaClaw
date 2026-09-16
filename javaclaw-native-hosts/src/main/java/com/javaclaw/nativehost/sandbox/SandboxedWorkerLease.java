package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 可信宿主拥有的常驻 Worker 租约；只由宿主动作续期，页面或 Worker 输出不能延长闲置期限。
 *
 * <p>process 的 destroy 也关闭整个租约。只有监护的成功回执才允许调用者清理独占 scratch，不能仅凭 waitFor 或 exitValue 推断完整回收。close
 * 幂等；根进程在宿主请求关闭前意外退出时无法排除未观察后代，因此拒绝成功回执并保留私有控制证据。
 */
public final class SandboxedWorkerLease implements AutoCloseable {
    private static final Duration CLOSE_GRACE = Duration.ofSeconds(5);

    private final Process monitor;
    private final Process exposed;
    private final SandboxWorkerControl control;
    private final SandboxWorkerTree tree;
    private final CompletableFuture<Process> completion;
    private boolean closed;

    SandboxedWorkerLease(Process monitor, SandboxWorkerControl control, boolean posix) {
        this.monitor = monitor;
        this.control = control;
        tree = new SandboxWorkerTree(monitor, posix);
        exposed = new LeasedProcess();
        completion = monitor.onExit().thenApply(ignored -> {
            verifyCompletion();
            cleanupControl();
            return exposed;
        });
    }

    /** 返回租约拥有的管道进程；onExit 成功仅证明受控组、已观察后代和原生资源已回收，不替代独立的整树发布门禁。 */
    public Process process() {
        return exposed;
    }

    /** 由可信宿主在已授权交互时延长闲置期限；租约关闭、进程已退出或控制通道失效时失败。 */
    public synchronized void touch() {
        if (closed || !monitor.isAlive()) {
            throw new IllegalStateException("Worker lease is closed");
        }
        try {
            control.touch();
        } catch (IOException failure) {
            close();
            throw new UncheckedIOException("Worker lease renewal failed", failure);
        }
    }

    /** 请求监护 helper 回收受控进程组与已观察后代；有界等待后仍未退出时执行兜底回收，失败不会报告成功。 */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (monitor.isAlive()) {
                control.stop();
                if (!monitor.waitFor(CLOSE_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                    tree.terminate();
                }
            }
        } catch (IOException failure) {
            tree.terminate();
            throw new UncheckedIOException("Worker lease closure failed", failure);
        } catch (InterruptedException interrupted) {
            tree.terminate();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Worker lease closure interrupted", interrupted);
        }
        completion.join();
    }

    private void verifyCompletion() {
        try {
            if (monitor.exitValue() != 0 || !"CLOSED".equals(control.status())) {
                throw new IllegalStateException("Worker monitor did not confirm complete cleanup");
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Worker cleanup confirmation unavailable", failure);
        }
    }

    private void cleanupControl() {
        try {
            control.close();
        } catch (IOException failure) {
            System.getLogger(SandboxedWorkerLease.class.getName())
                    .log(System.Logger.Level.WARNING, "Worker 私有监护目录未能回收");
        }
    }

    private final class LeasedProcess extends Process {
        @Override
        public OutputStream getOutputStream() {
            return monitor.getOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return monitor.getInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return monitor.getErrorStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            return monitor.waitFor();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return monitor.waitFor(timeout, unit);
        }

        @Override
        public int exitValue() {
            return monitor.exitValue();
        }

        @Override
        public void destroy() {
            close();
        }

        @Override
        public Process destroyForcibly() {
            close();
            return this;
        }

        @Override
        public boolean isAlive() {
            return monitor.isAlive();
        }

        @Override
        public long pid() {
            return monitor.pid();
        }

        @Override
        public ProcessHandle toHandle() {
            return monitor.toHandle();
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return completion;
        }
    }
}
