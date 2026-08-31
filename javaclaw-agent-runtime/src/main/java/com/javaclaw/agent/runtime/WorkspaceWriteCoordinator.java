package com.javaclaw.agent.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.sandbox.api.SandboxMode;

/**
 * Enforces the multi-agent writer rules independently of model/tool behavior. Git worktrees may write concurrently; a
 * non-Git workspace has one writer.
 */
final class WorkspaceWriteCoordinator {
    private final Object gate = new Object();
    private final Map<String, ThreadId> workspaceWriters = new HashMap<>();
    private final Map<Path, ThreadId> worktreeWriters = new HashMap<>();

    Lease acquire(AgentThread thread, TurnConfig config, Function<ThreadId, Optional<AgentThread>> threads) {
        if (config.sandboxPolicy().mode() == SandboxMode.READ_ONLY) {
            return Lease.NOOP;
        }
        validateWritablePolicy(thread, config);
        AgentThread root = rootOf(thread, threads);

        if (config.sandboxPolicy().mode() == SandboxMode.HOST_FULL_ACCESS) {
            return acquireWorkspace(thread.workspaceId(), thread.id());
        }

        Optional<Path> rootGit = commonGitDirectory(root.workingDirectory());
        if (rootGit.isEmpty()) {
            return acquireWorkspace(thread.workspaceId(), thread.id());
        }

        if (thread.parentThreadId() != null) {
            if (!isLinkedWorktree(thread.workingDirectory())) {
                throw new IllegalStateException(
                        "a Git subagent with write access requires a dedicated linked worktree");
            }
            Path childGit = commonGitDirectory(thread.workingDirectory())
                    .orElseThrow(
                            () -> new IllegalStateException("subagent working directory is not a valid Git worktree"));
            if (!sameFile(rootGit.get(), childGit)) {
                throw new IllegalStateException("subagent worktree does not belong to the parent Git repository");
            }
            if (sameFile(root.workingDirectory(), thread.workingDirectory())) {
                throw new IllegalStateException("subagent worktree must differ from the parent working directory");
            }
        }
        return acquireWorktree(realPath(thread.workingDirectory()), thread.id());
    }

    private Lease acquireWorkspace(String workspaceId, ThreadId owner) {
        synchronized (gate) {
            ThreadId existing = workspaceWriters.putIfAbsent(workspaceId, owner);
            if (existing != null) {
                throw new IllegalStateException("non-Git workspace already has an active writer: " + workspaceId);
            }
        }
        return new Lease(() -> {
            synchronized (gate) {
                workspaceWriters.remove(workspaceId, owner);
            }
        });
    }

    private Lease acquireWorktree(Path directory, ThreadId owner) {
        synchronized (gate) {
            ThreadId existing = worktreeWriters.putIfAbsent(directory, owner);
            if (existing != null) {
                throw new IllegalStateException("Git worktree already has an active writer: " + directory);
            }
        }
        return new Lease(() -> {
            synchronized (gate) {
                worktreeWriters.remove(directory, owner);
            }
        });
    }

    private static void validateWritablePolicy(AgentThread thread, TurnConfig config) {
        Path cwd = realPath(thread.workingDirectory());
        if (config.sandboxPolicy().mode() == SandboxMode.WORKSPACE_WRITE) {
            if (config.sandboxPolicy().writableRoots().isEmpty()) {
                throw new IllegalArgumentException("WORKSPACE_WRITE requires a writable root");
            }
            for (Path writable : config.sandboxPolicy().writableRoots()) {
                if (!resolvedLocation(writable).startsWith(cwd)) {
                    throw new IllegalArgumentException(
                            "writable root escapes the thread working directory: " + writable);
                }
            }
            requireProtected(config, thread.workingDirectory().resolve(".git"));
            requireProtected(config, thread.workingDirectory().resolve(".javaclaw"));
        }
    }

    private static void requireProtected(TurnConfig config, Path required) {
        Path normalized = required.toAbsolutePath().normalize();
        boolean covered = config.sandboxPolicy().protectedRoots().stream().anyMatch(normalized::startsWith);
        if (!covered) {
            throw new IllegalArgumentException("sandbox policy must protect " + normalized);
        }
    }

    private static AgentThread rootOf(AgentThread thread, Function<ThreadId, Optional<AgentThread>> threads) {
        AgentThread current = thread;
        Set<ThreadId> visited = new HashSet<>();
        while (current.parentThreadId() != null) {
            if (!visited.add(current.id())) {
                throw new IllegalStateException("cycle in thread ancestry");
            }
            ThreadId parentId = current.parentThreadId();
            current = threads.apply(parentId)
                    .orElseThrow(() -> new NoSuchElementException("parent thread not found: " + parentId));
        }
        return current;
    }

    private static Optional<Path> commonGitDirectory(Path workingDirectory) {
        Path dotGit = workingDirectory.resolve(".git");
        try {
            if (Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(dotGit.toRealPath());
            }
            if (!Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            Path gitDirectory = gitDirectoryFromFile(dotGit);
            Path common = gitDirectory.resolve("commondir");
            if (!Files.isRegularFile(common, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            String value = boundedFirstLine(common);
            Path commonDirectory = gitDirectory.resolve(value).normalize().toRealPath();
            return Files.isDirectory(commonDirectory) ? Optional.of(commonDirectory) : Optional.empty();
        } catch (IOException failure) {
            return Optional.empty();
        }
    }

    private static boolean isLinkedWorktree(Path workingDirectory) {
        Path dotGit = workingDirectory.resolve(".git");
        if (!Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            Path gitDirectory = gitDirectoryFromFile(dotGit);
            return Files.isDirectory(gitDirectory, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(gitDirectory.resolve("commondir"), LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            return false;
        }
    }

    private static Path gitDirectoryFromFile(Path dotGit) throws IOException {
        String line = boundedFirstLine(dotGit);
        if (!line.startsWith("gitdir:")) {
            throw new IOException("invalid .git link");
        }
        String location = line.substring("gitdir:".length()).strip();
        if (location.isEmpty()) {
            throw new IOException("empty .git link");
        }
        Path value = Path.of(location);
        if (!value.isAbsolute()) {
            value = dotGit.getParent().resolve(value);
        }
        return value.normalize().toRealPath();
    }

    private static String boundedFirstLine(Path path) throws IOException {
        if (Files.size(path) > 8 * 1024) {
            throw new IOException("Git metadata link is too large");
        }
        String value = Files.readString(path, StandardCharsets.UTF_8);
        int newline = value.indexOf('\n');
        return (newline < 0 ? value : value.substring(0, newline)).strip();
    }

    private static Path resolvedLocation(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path existing = normalized;
        while (existing != null && Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IllegalArgumentException("path has no existing ancestor");
        }
        try {
            Path real = existing.toRealPath();
            return real.resolve(existing.relativize(normalized)).normalize();
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot resolve path securely: " + path, failure);
        }
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException("working directory must exist and be resolvable: " + path, failure);
        }
    }

    private static boolean sameFile(Path first, Path second) {
        try {
            return Files.isSameFile(first, second);
        } catch (IOException failure) {
            return false;
        }
    }

    static final class Lease implements AutoCloseable {
        private static final Lease NOOP = new Lease(() -> {});
        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(Runnable release) {
            this.release = release;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release.run();
            }
        }
    }
}
