package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import com.javaclaw.api.SandboxFrame;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.api.SandboxSignal;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;

/** 单次命令拥有的原生网络 IPC；PTY 将其所有权移交给会话并在终态释放。 */
final class SandboxNetworkScope implements AutoCloseable {
    private final ValidatedSandboxCommand command;
    private final AutoCloseable bridge;

    private SandboxNetworkScope(ValidatedSandboxCommand command, AutoCloseable bridge) {
        this.command = command;
        this.bridge = bridge;
    }

    static SandboxNetworkScope open(ValidatedSandboxCommand command) throws IOException {
        SandboxNetworkAccess network = command.networkAccess();
        if (network.mode() != SandboxNetworkAccess.Mode.PROXY_ONLY) {
            return new SandboxNetworkScope(command, null);
        }
        if (PlatformSandboxCommandBuilder.current() instanceof WindowsSandboxCommandBuilder) {
            WindowsNetworkScope windows = WindowsNetworkScope.open(network);
            SandboxNetworkAccess prepared = SandboxNetworkAccess.proxyOnly(
                    network.grantId().orElseThrow(),
                    network.proxyEndpoint().orElseThrow(),
                    windows.directory(),
                    network.closeTunnels());
            return new SandboxNetworkScope(command.withNetworkAccess(prepared), windows);
        }
        if (!(PlatformSandboxCommandBuilder.current() instanceof LinuxSandboxCommandBuilder)) {
            return new SandboxNetworkScope(command, null);
        }
        LinuxProxyBridge bridge = LinuxProxyBridge.open(network);
        SandboxNetworkAccess prepared = SandboxNetworkAccess.proxyOnly(
                network.grantId().orElseThrow(),
                network.proxyEndpoint().orElseThrow(),
                bridge.directory(),
                network.closeTunnels());
        return new SandboxNetworkScope(command.withNetworkAccess(prepared), bridge);
    }

    ValidatedSandboxCommand command() {
        return command;
    }

    SandboxSession own(SandboxSession session) {
        return new OwnedSession(session, this, session.completion().whenComplete((result, failure) -> close()));
    }

    @Override
    public void close() {
        if (bridge != null) {
            try {
                bridge.close();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            } catch (Exception failure) {
                throw new IllegalStateException("Sandbox network cleanup failed", failure);
            }
        }
    }

    private record OwnedSession(
            SandboxSession delegate, SandboxNetworkScope scope, CompletionStage<SandboxResult> completion)
            implements SandboxSession {
        @Override
        public Flow.Publisher<SandboxFrame> frames() {
            return delegate.frames();
        }

        @Override
        public CompletionStage<Void> send(byte[] bytes) {
            return delegate.send(bytes);
        }

        @Override
        public CompletionStage<Void> signal(SandboxSignal signal) {
            return delegate.signal(signal);
        }

        @Override
        public CompletionStage<Void> resize(int columns, int rows) {
            return delegate.resize(columns, rows);
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            try {
                delegate.close();
            } catch (RuntimeException problem) {
                failure = problem;
            }
            try {
                scope.close();
            } catch (RuntimeException cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
