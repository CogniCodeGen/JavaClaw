package com.javaclaw.server.sandbox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.agent.runtime.persistence.WorkspaceRepository;
import com.javaclaw.core.api.Workspace;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxExecutor;
import com.javaclaw.sandbox.api.SandboxResult;
import com.javaclaw.sandbox.api.SandboxSession;
import com.javaclaw.sandbox.api.SandboxSessionFrame;
import com.javaclaw.sandbox.api.SandboxSessionOptions;
import com.javaclaw.sandbox.api.SandboxSignal;

/** Permanently locks a Workspace if Windows reports that temporary sandbox ACLs did not restore. */
public final class WorkspaceLockingSandboxExecutor implements SandboxExecutor {
    static final String ACL_FAILURE = "JAVACLAW_WINDOWS_ACL_RESTORE_FAILURE:";
    private static final String LOCK_REASON = "Windows sandbox ACL restoration failed; manual ACL repair is required";

    private final SandboxExecutor delegate;
    private final WorkspaceRepository workspaces;

    /** 包装唯一沙箱执行端口，检测 Windows ACL 恢复失败后持久锁定 Workspace，禁止默默降级继续写入。 */
    public WorkspaceLockingSandboxExecutor(SandboxExecutor delegate, WorkspaceRepository workspaces) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    }

    @Override
    public SandboxResult execute(SandboxCommand command) throws Exception {
        try {
            SandboxResult result = delegate.execute(command);
            if (result.stderr().contains(ACL_FAILURE)) {
                lock(command.workingDirectory());
            }
            return result;
        } catch (Exception failure) {
            if (messages(failure).contains(ACL_FAILURE)) {
                lock(command.workingDirectory());
            }
            throw failure;
        }
    }

    @Override
    public SandboxSession openSession(SandboxCommand command, SandboxSessionOptions options) throws Exception {
        return new LockingSession(delegate.openSession(command, options), command.workingDirectory());
    }

    private void lock(Path workingDirectory) {
        Path cwd = workingDirectory.toAbsolutePath().normalize();
        Workspace target = workspaces.list().stream()
                .filter(value -> cwd.startsWith(value.root()))
                .max(Comparator.comparingInt(value -> value.root().getNameCount()))
                .orElse(null);
        if (target == null) {
            return;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            Workspace current = workspaces.find(target.id()).orElse(null);
            if (current == null || current.locked()) {
                return;
            }
            try {
                workspaces.setLocked(current.id(), true, LOCK_REASON, current.revision());
                return;
            } catch (IllegalStateException conflict) {
                if (attempt == 2) {
                    throw conflict;
                }
            }
        }
    }

    private static String messages(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                result.append(current.getMessage()).append('\n');
            }
        }
        return result.toString();
    }

    private final class LockingSession implements SandboxSession {
        private final SandboxSession delegateSession;
        private final Path workingDirectory;
        private final AtomicBoolean locked = new AtomicBoolean();

        private LockingSession(SandboxSession delegateSession, Path workingDirectory) {
            this.delegateSession = delegateSession;
            this.workingDirectory = workingDirectory;
        }

        @Override
        public String id() {
            return delegateSession.id();
        }

        @Override
        public SandboxSessionFrame read(Duration timeout) throws Exception {
            SandboxSessionFrame frame = delegateSession.read(timeout);
            boolean reportsAclFailure = frame != null
                    && ((frame.kind() == SandboxSessionFrame.Kind.STDERR
                                    && new String(frame.data(), StandardCharsets.UTF_8).contains(ACL_FAILURE))
                            || (frame.detail() != null && frame.detail().contains(ACL_FAILURE)));
            if (reportsAclFailure && locked.compareAndSet(false, true)) {
                lock(workingDirectory);
            }
            return frame;
        }

        @Override
        public void write(byte[] input) throws Exception {
            delegateSession.write(input);
        }

        @Override
        public void closeInput() throws Exception {
            delegateSession.closeInput();
        }

        @Override
        public void resize(int columns, int rows) throws Exception {
            delegateSession.resize(columns, rows);
        }

        @Override
        public void signal(SandboxSignal signal) throws Exception {
            delegateSession.signal(signal);
        }

        @Override
        public boolean isAlive() {
            return delegateSession.isAlive();
        }

        @Override
        public void terminate() {
            delegateSession.terminate();
        }
    }
}
