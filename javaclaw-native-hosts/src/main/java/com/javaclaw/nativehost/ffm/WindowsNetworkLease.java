package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.nativehost.network.SandboxNetworkAccess;

/** 绑定当前挂起进程的系统租约；正常清理严格先撤允许、关闭 Broker 隧道、再终止根 Job。 */
final class WindowsNetworkLease implements AutoCloseable {
    private final Channel pipe;
    private final SandboxNetworkAccess access;
    private final AtomicBoolean closed = new AtomicBoolean();
    private IOException terminalFailure;

    WindowsNetworkLease(Channel pipe, SandboxNetworkAccess access) {
        this.pipe = pipe;
        this.access = access;
    }

    static WindowsNetworkLease attach(
            MemorySegment process, WindowsAppContainerScope scope, WindowsSandboxPaths.Prepared request)
            throws IOException {
        WindowsGuardPipe pipe = WindowsGuardPipe.connect();
        try {
            SandboxNetworkAccess access = request.networkAccess();
            String command = WindowsGuardProtocol.attach(
                    WindowsNetworkGuardNative.processId(process),
                    scope.profileName(),
                    WindowsNetworkGuardNative.sidText(scope.sid()),
                    access.proxyEndpoint().orElseThrow().getPort(),
                    request.timeout(),
                    request.limits());
            pipe.request(command);
            return new WindowsNetworkLease(new PipeChannel(pipe), access);
        } catch (IOException | RuntimeException failure) {
            try {
                pipe.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            if (terminalFailure != null) {
                throw terminalFailure;
            }
            return;
        }
        IOException failure = null;
        try {
            pipe.request("REVOKE");
        } catch (IOException problem) {
            failure = problem;
        }
        try {
            access.closeTunnels().run();
        } catch (RuntimeException problem) {
            failure = append(failure, new IOException("NETWORK_GUARD_TUNNEL_CLEANUP_FAILED", problem));
        }
        try {
            pipe.request("CLOSE");
        } catch (IOException problem) {
            failure = append(failure, problem);
        } finally {
            try {
                pipe.close();
            } catch (IOException problem) {
                failure = append(failure, problem);
            }
        }
        if (failure != null) {
            terminalFailure = failure;
            throw failure;
        }
    }

    private static IOException append(IOException current, IOException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    /** 每个租约独占一个控制连接；测试以同一契约注入失效顺序，不模拟原生授权成功。 */
    interface Channel extends AutoCloseable {
        void request(String operation) throws IOException;

        @Override
        void close() throws IOException;
    }

    private record PipeChannel(WindowsGuardPipe delegate) implements Channel {
        @Override
        public void request(String operation) throws IOException {
            delegate.request(operation);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
