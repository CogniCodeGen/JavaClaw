package com.javaclaw.server.collaboration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.javaclaw.agent.collaboration.CollaborationGateway;
import com.javaclaw.agent.conversation.ProfileUseCases;
import com.javaclaw.agent.runtime.ThreadMaintenanceUseCases;
import com.javaclaw.agent.runtime.ThreadUseCases;
import com.javaclaw.agent.runtime.TurnUseCases;
import com.javaclaw.agent.runtime.WorkspaceUseCases;
import com.javaclaw.agent.runtime.persistence.AttachmentRepository;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ProfileKind;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadSnapshot;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.core.api.Workspace;
import com.javaclaw.core.api.WorkspaceId;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxExecutor;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxResult;

/** Production parent/child Thread and managed Git worktree implementation. */
public final class CollaborationService implements CollaborationGateway, WorktreeRecoveryUseCases {
    private static final long MAX_PATCH_BYTES = 16L * 1024L * 1024L;
    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(2);

    private final WorkspaceUseCases workspaces;
    private final ThreadUseCases threads;
    private final TurnUseCases turns;
    private final ThreadMaintenanceUseCases maintenance;
    private final ProfileUseCases profiles;
    private final CollaborationRepository spawns;
    private final WorktreeRepository worktrees;
    private final AttachmentRepository attachments;
    private final SandboxExecutor sandbox;
    private final SandboxPolicy ceiling;
    private final Path cacheRoot;
    private final List<String> gitPrefix;

    /**
     * 装配协作用例和受控 Git 基础设施，准备私有 cacheRoot；不直接创建不受监督的 Agent 进程。
     *
     * @throws java.io.IOException 无法安全准备 worktree 缓存目录
     */
    public CollaborationService(
            WorkspaceUseCases workspaces,
            ThreadUseCases threads,
            TurnUseCases turns,
            ThreadMaintenanceUseCases maintenance,
            ProfileUseCases profiles,
            CollaborationRepository spawns,
            WorktreeRepository worktrees,
            AttachmentRepository attachments,
            SandboxExecutor sandbox,
            SandboxPolicy ceiling,
            Path cacheRoot,
            List<String> gitPrefix)
            throws IOException {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.spawns = Objects.requireNonNull(spawns, "spawns");
        this.worktrees = Objects.requireNonNull(worktrees, "worktrees");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.ceiling = Objects.requireNonNull(ceiling, "ceiling");
        this.cacheRoot = prepareCacheRoot(Objects.requireNonNull(cacheRoot, "cacheRoot"));
        this.gitPrefix = List.copyOf(Objects.requireNonNull(gitPrefix, "gitPrefix"));
    }

    @Override
    public AgentThread spawn(SpawnRequest request) {
        Objects.requireNonNull(request, "request");
        String idempotencyKey = requireKey(request.idempotencyKey());
        Optional<CollaborationRepository.SpawnRecord> replay =
                spawns.findSpawn(request.parentThreadId(), idempotencyKey);
        if (replay.isPresent()) {
            CollaborationRepository.SpawnRecord existing = replay.get();
            if (existing.writable() != request.writable()
                    || !existing.profileId().equals(request.profileId())
                    || !existing.task().equals(request.task())) {
                throw new IllegalStateException("spawn idempotency key was reused with different parameters");
            }
            return threads.readThread(existing.childThreadId())
                    .orElseThrow(() -> new IllegalStateException("idempotent child thread is missing"))
                    .thread();
        }

        AgentThread parent = requireThread(request.parentThreadId());
        Workspace workspace = workspaces
                .readWorkspace(new WorkspaceId(parent.workspaceId()))
                .orElseThrow(() -> new NoSuchElementException("workspace not found: " + parent.workspaceId()));
        ManagedBaseline baseline = null;
        AgentThread child = null;
        try {
            Path childRoot = parent.workingDirectory();
            boolean gitWorkspace = hasGitMetadata(parent.workingDirectory());
            if (request.writable() && gitWorkspace) {
                baseline = createWorktree(parent);
                childRoot = baseline.path();
            }
            child = threads.startChildThread(parent.id(), request.task(), childRoot);
            Workspace childWorkspace = workspaceAt(workspace, childRoot);
            var resolved = profiles.resolve(request.profileId(), childWorkspace, ApprovalPolicy.ON_RISK, "medium");
            if (resolved.profile().kind() != ProfileKind.SUBAGENT) {
                throw new IllegalArgumentException("subagent spawn requires a SUBAGENT profile");
            }
            TurnConfig config = request.writable()
                    ? requireWritable(resolved.turnConfig())
                    : forceReadOnly(resolved.turnConfig(), childRoot);
            // 非 Git 目录不能伪造工作树隔离；已有写者时明确收窄为只读，最终竞争仍由 Runtime 写租约拒绝。
            if (request.writable() && !gitWorkspace && hasWorkspaceWriter(parent.workspaceId())) {
                config = forceReadOnly(config, childRoot);
            }
            if (baseline != null) {
                worktrees.create(new WorktreeRepository.WorktreeDraft(
                        baseline.id(),
                        parent.workspaceId(),
                        parent.id(),
                        child.id(),
                        baseline.path(),
                        baseline.commit()));
            }
            spawns.recordSpawn(
                    parent.id(), child.id(), request.writable(), request.profileId(), request.task(), idempotencyKey);
            turns.startTurn(new TurnStartCommand(
                    child.id(), List.of(new TurnInput.Text(request.task())), config, idempotencyKey + ":turn"));
            return child;
        } catch (Exception failure) {
            if (child != null) {
                try {
                    threads.deleteThread(child.id());
                } catch (RuntimeException ignored) {
                }
            }
            if (baseline != null) {
                removeUntrackedWorktree(parent, baseline.path());
                if (child != null) {
                    worktrees.findByChild(child.id()).ifPresent(record -> {
                        try {
                            worktrees.setState(
                                    record.id(),
                                    WorktreeRepository.State.CLEANED,
                                    false,
                                    "spawn rolled back",
                                    record.revision());
                        } catch (RuntimeException ignored) {
                        }
                    });
                }
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("cannot spawn subagent", failure);
        }
    }

    /** Completes only safe post-crash cleanup; active/conflicted worktrees stay recoverable. */
    public void reconcile() {
        for (WorktreeRepository.WorktreeRecord record : worktrees.listRequiringCleanup()) {
            if (record.state() != WorktreeRepository.State.MERGED) {
                continue;
            }
            try (var lease = maintenance.acquireInactiveDirectory(record.childThreadId())) {
                AgentThread parent = requireThread(record.parentThreadId());
                removeWorktree(parent.workingDirectory(), record.path());
                worktrees.setState(
                        record.id(), WorktreeRepository.State.CLEANED, false, record.details(), record.revision());
            } catch (Exception ignored) {
                // Keep cleanup_required=true. A later startup or explicit cleanup can retry.
            }
        }
    }

    @Override
    public boolean steer(TurnId turnId, TurnInput input) {
        return turns.steer(turnId, input);
    }

    @Override
    public Optional<ThreadSnapshot> read(ThreadId childThreadId) {
        return child(childThreadId).flatMap(ignored -> threads.readThread(childThreadId));
    }

    @Override
    public Optional<ThreadSnapshot> waitForTerminal(ThreadId childThreadId, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("wait timeout must be between zero and 24 hours");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            Optional<ThreadSnapshot> snapshot = read(childThreadId);
            if (snapshot.isEmpty()) {
                return Optional.empty();
            }
            if (!snapshot.get().turns().isEmpty()
                    && snapshot.get().turns().stream()
                            .allMatch(value -> value.status().terminal())) {
                return snapshot;
            }
            if (System.nanoTime() >= deadline) {
                return Optional.empty();
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        } while (true);
    }

    @Override
    public boolean cancel(ThreadId childThreadId) {
        Optional<ThreadSnapshot> snapshot = child(childThreadId).flatMap(ignored -> threads.readThread(childThreadId));
        if (snapshot.isEmpty()) {
            return false;
        }
        boolean interrupted = false;
        for (AgentTurn turn : snapshot.get().turns()) {
            if (!turn.status().terminal()) {
                interrupted |= turns.interrupt(turn.id());
            }
        }
        return interrupted;
    }

    @Override
    public List<AgentThread> children(ThreadId parentThreadId) {
        requireThread(parentThreadId);
        return threads.listThreads(true).stream()
                .filter(value -> parentThreadId.equals(value.parentThreadId()))
                .toList();
    }

    @Override
    public PatchResult diff(ThreadId childThreadId) {
        if (worktrees.findByChild(childThreadId).isEmpty()) {
            child(childThreadId).orElseThrow(() -> new NoSuchElementException("subagent not found"));
            return new PatchResult(
                    "NO_ISOLATED_WORKTREE",
                    null,
                    List.of(),
                    "non-Git subagents share the workspace under the single-writer policy; no isolated patch exists");
        }
        try (var lease = maintenance.acquireInactiveDirectory(childThreadId)) {
            return diffInactive(childThreadId);
        }
    }

    private PatchResult diffInactive(ThreadId childThreadId) {
        WorktreeRepository.WorktreeRecord record = requireWorktree(childThreadId);
        if (record.state() == WorktreeRepository.State.CLEANED) {
            throw new IllegalStateException("worktree has been cleaned; use the archived patch attachment");
        }
        try (Scratch scratch = scratch("diff")) {
            byte[] patch = patch(record.path(), scratch.index());
            if (patch.length == 0) {
                return new PatchResult("CLEAN", null, List.of(), "child worktree has no changes");
            }
            String sha = attachments
                    .put(new ByteArrayInputStream(patch), "text/x-diff; charset=utf-8")
                    .sha256();
            return new PatchResult(
                    "READY", sha, List.of(), "bounded binary patch generated from the synthetic baseline");
        } catch (Exception failure) {
            throw new IllegalStateException("cannot generate child patch", failure);
        }
    }

    @Override
    public PatchResult apply(ThreadId parentThreadId, ThreadId childThreadId, String idempotencyKey) {
        try (var lease = maintenance.acquireInactiveDirectory(childThreadId)) {
            return applyInactive(parentThreadId, childThreadId, idempotencyKey);
        }
    }

    private PatchResult applyInactive(ThreadId parentThreadId, ThreadId childThreadId, String idempotencyKey) {
        requireKey(idempotencyKey);
        WorktreeRepository.WorktreeRecord record = requireWorktree(childThreadId);
        if (!record.parentThreadId().equals(parentThreadId)) {
            throw new IllegalArgumentException("child worktree belongs to another parent thread");
        }
        if (record.state() == WorktreeRepository.State.CLEANED || record.state() == WorktreeRepository.State.MERGED) {
            boolean applied = record.state() == WorktreeRepository.State.MERGED
                    || (record.details().contains("applyKey=")
                            && record.details().contains("; patch="))
                    || record.details().equals("no changes");
            return new PatchResult(
                    applied ? "ALREADY_APPLIED" : "DISCARDED",
                    null,
                    List.of(),
                    applied ? "patch was already applied" : "worktree was explicitly discarded; no patch was applied");
        }
        AgentThread parent = requireThread(parentThreadId);
        try (Scratch childScratch = scratch("child-diff");
                Scratch parentScratch = scratch("parent-apply")) {
            byte[] patch = patch(record.path(), childScratch.index());
            if (patch.length == 0) {
                WorktreeRepository.WorktreeRecord clean = worktrees.setState(
                        record.id(), WorktreeRepository.State.MERGED, true, "no changes", record.revision());
                cleanupAfterMerge(parent, clean);
                return new PatchResult("CLEAN", null, List.of(), "child had no changes");
            }
            byte[] backup = patch(parent.workingDirectory(), parentScratch.index());
            String backupSha = backup.length == 0
                    ? null
                    : attachments
                            .put(new ByteArrayInputStream(backup), "text/x-diff; charset=utf-8")
                            .sha256();
            Path patchFile = parentScratch.directory().resolve("child.patch");
            Files.write(patchFile, patch);
            ownerOnly(patchFile, false);
            prepareIndex(parent.workingDirectory(), parentScratch.index());
            SandboxResult applied = git(
                    parent.workingDirectory(),
                    List.of("apply", "--3way", "--index", "--binary", "--whitespace=nowarn", patchFile.toString()),
                    parentScratch.index(),
                    false,
                    MAX_PATCH_BYTES);
            if (applied.exitCode() != 0) {
                List<String> conflicts = conflictPaths(parent.workingDirectory(), parentScratch.index());
                String details = "applyKey=" + idempotencyKey + "; backup=" + Objects.toString(backupSha, "none") + "; "
                        + bounded(applied.stderr());
                worktrees.setState(record.id(), WorktreeRepository.State.CONFLICT, true, details, record.revision());
                return new PatchResult("CONFLICT", null, conflicts, details);
            }
            String patchSha = attachments
                    .put(new ByteArrayInputStream(patch), "text/x-diff; charset=utf-8")
                    .sha256();
            String details = "applyKey=" + idempotencyKey + "; patch=" + patchSha + "; backup="
                    + Objects.toString(backupSha, "none");
            WorktreeRepository.WorktreeRecord merged =
                    worktrees.setState(record.id(), WorktreeRepository.State.MERGED, true, details, record.revision());
            boolean cleaned = cleanupAfterMerge(parent, merged);
            return new PatchResult(cleaned ? "APPLIED" : "APPLIED_CLEANUP_REQUIRED", patchSha, List.of(), details);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot apply child patch", failure);
        }
    }

    @Override
    public boolean cleanup(ThreadId childThreadId, boolean discardUnmerged) {
        try (var lease = maintenance.acquireInactiveDirectory(childThreadId)) {
            return cleanupInactive(childThreadId, discardUnmerged);
        }
    }

    private boolean cleanupInactive(ThreadId childThreadId, boolean discardUnmerged) {
        WorktreeRepository.WorktreeRecord record = requireWorktree(childThreadId);
        if (record.state() == WorktreeRepository.State.CLEANED) {
            return true;
        }
        if (record.state() != WorktreeRepository.State.MERGED && !discardUnmerged) {
            throw new IllegalStateException("unmerged worktree cleanup requires explicit discardUnmerged=true");
        }
        AgentThread parent = requireThread(record.parentThreadId());
        try {
            removeWorktree(parent.workingDirectory(), record.path());
            worktrees.setState(
                    record.id(), WorktreeRepository.State.CLEANED, false, record.details(), record.revision());
            return true;
        } catch (Exception failure) {
            worktrees.setState(
                    record.id(),
                    WorktreeRepository.State.ABANDONED,
                    true,
                    "cleanup failed: " + bounded(failure.getMessage()),
                    record.revision());
            return false;
        }
    }

    @Override
    public List<RecoveryInfo> listRecovery(WorkspaceId workspaceId) {
        workspaces.readWorkspace(workspaceId).orElseThrow(() -> new NoSuchElementException("workspace not found"));
        return worktrees.listByWorkspace(workspaceId.value(), 256).stream()
                .map(this::recoveryInfo)
                .toList();
    }

    @Override
    public PatchResult exportPatch(ThreadId childThreadId, long expectedRevision) {
        try (var lease = maintenance.acquireInactiveDirectory(childThreadId)) {
            if (requireWorktree(childThreadId).revision() != expectedRevision) {
                throw new IllegalStateException("worktree revision conflict");
            }
            return diffInactive(childThreadId);
        }
    }

    @Override
    public RecoveryInfo cleanup(CleanupRequest request) {
        var replay = worktrees.replayCleanup(request);
        if (replay.isPresent()) {
            return recoveryInfo(replay.get());
        }
        try (var lease = maintenance.acquireInactiveDirectory(request.childThreadId())) {
            WorktreeRepository.WorktreeRecord current = requireWorktree(request.childThreadId());
            if (current.revision() != request.expectedRevision()) {
                throw new IllegalStateException("worktree revision conflict");
            }
            if (!request.discardUnmerged()
                    && current.state() != WorktreeRepository.State.MERGED
                    && current.state() != WorktreeRepository.State.CLEANED) {
                throw new IllegalStateException("unmerged worktree requires explicit discard confirmation");
            }
            var pending = worktrees.pendingCleanup(request);
            if (pending.isEmpty()) {
                String backup = current.state() == WorktreeRepository.State.CLEANED
                        ? null
                        : diffInactive(request.childThreadId()).patchAttachmentSha256();
                worktrees.beginCleanup(request, backup);
            }
            boolean cleaned = current.state() == WorktreeRepository.State.CLEANED;
            if (!cleaned) {
                try {
                    removeWorktree(requireThread(current.parentThreadId()).workingDirectory(), current.path());
                    cleaned = true;
                } catch (Exception failure) {
                    // 固定状态说明避免 Git stderr 中的路径/文件内容进入诊断；备份与意图仍由 H2 保留。
                    cleaned = false;
                }
            }
            return recoveryInfo(worktrees.finishCleanup(request, cleaned));
        }
    }

    @Override
    public void assertCanDeleteThread(ThreadId threadId) {
        if (worktrees.listRequiringCleanup().stream()
                .anyMatch(record -> record.childThreadId().equals(threadId)
                        || record.parentThreadId().equals(threadId))) {
            throw new IllegalStateException("export or clean managed worktrees before deleting their Thread");
        }
    }

    private RecoveryInfo recoveryInfo(WorktreeRepository.WorktreeRecord record) {
        boolean running = threads.readThread(record.childThreadId())
                .map(snapshot -> snapshot.turns().stream()
                        .anyMatch(turn -> !turn.status().terminal()))
                .orElse(false);
        var hashes = java.util.regex.Pattern.compile("(?:^|; )(?:(?:discardBackup)|backup)=([0-9a-f]{64})(?:;|$)")
                .matcher(record.details());
        String backup = null;
        while (hashes.find()) {
            backup = hashes.group(1);
        }
        String details =
                switch (record.state()) {
                    case ACTIVE -> running ? "子任务仍在运行，请先中断并等待退出。" : "子任务已结束，工作树保留，尚未合并。";
                    case CONFLICT -> "合并冲突；父文件与子工作树保留，请查看补丁和备份后处理。";
                    case ABANDONED -> "清理未完成；备份和现场保留，可显式重试。";
                    case MERGED -> "补丁已合并，等待清理隔离工作树。";
                    case CLEANED -> "隔离工作树已清理；导出及备份附件仍保留。";
                };
        return new RecoveryInfo(
                record.id(),
                record.workspaceId(),
                record.parentThreadId(),
                record.childThreadId(),
                record.state().name(),
                record.revision(),
                running,
                backup,
                details,
                record.updatedAt());
    }

    private boolean hasWorkspaceWriter(String workspaceId) {
        return threads.listThreads(true).stream()
                .filter(thread -> workspaceId.equals(thread.workspaceId()))
                .map(thread -> threads.readThread(thread.id()))
                .flatMap(Optional::stream)
                .flatMap(snapshot -> snapshot.turns().stream())
                .anyMatch(turn -> !turn.status().terminal()
                        && turn.config().sandboxPolicy().mode() != SandboxMode.READ_ONLY);
    }

    private static boolean hasGitMetadata(Path directory) {
        for (Path current = directory; current != null; current = current.getParent()) {
            if (Files.exists(current.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
        }
        return false;
    }

    private ManagedBaseline createWorktree(AgentThread parent) throws Exception {
        if (gitPrefix.isEmpty()) {
            throw new IllegalStateException(
                    "writable subagents are unavailable because controlled Git is not installed");
        }
        Path repository = parent.workingDirectory().toRealPath();
        Path common = commonGitDirectory(repository);
        try (Scratch first = scratch("baseline-a");
                Scratch second = scratch("baseline-b")) {
            String firstTree = writeTree(repository, first.index());
            String secondTree = writeTree(repository, second.index());
            if (!firstTree.equals(secondTree)) {
                throw new IllegalStateException("workspace changed while synthesizing the subagent baseline");
            }
            String head = optionalGit(repository, List.of("rev-parse", "--verify", "HEAD"), null, false, 4_096)
                    .map(value -> requireObjectId(value.stdout()))
                    .orElse(null);
            ArrayList<String> commit = new ArrayList<>(List.of("commit-tree", firstTree));
            if (head != null) {
                commit.addAll(List.of("-p", head));
            }
            commit.addAll(List.of("-m", "JavaClaw synthetic subagent baseline"));
            LinkedHashMap<String, String> identity = new LinkedHashMap<>();
            identity.put("GIT_AUTHOR_NAME", "JavaClaw");
            identity.put("GIT_AUTHOR_EMAIL", "runtime@localhost");
            identity.put("GIT_COMMITTER_NAME", "JavaClaw");
            identity.put("GIT_COMMITTER_EMAIL", "runtime@localhost");
            String baseline = requireObjectId(
                    git(repository, commit, null, true, 4_096, identity).stdout());
            String id = "worktree_" + UUID.randomUUID().toString().replace("-", "");
            Path path = cacheRoot.resolve(id).normalize();
            if (!path.startsWith(cacheRoot) || Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("managed worktree target is unsafe");
            }
            git(repository, List.of("worktree", "add", "--detach", path.toString(), baseline), null, true, 1024 * 1024);
            return new ManagedBaseline(id, path.toRealPath(), baseline, common);
        }
    }

    private String writeTree(Path repository, Path index) throws Exception {
        prepareIndex(repository, index);
        return requireObjectId(
                git(repository, List.of("write-tree"), index, true, 4_096).stdout());
    }

    private void prepareIndex(Path repository, Path index) throws Exception {
        Optional<SandboxResult> head =
                optionalGit(repository, List.of("rev-parse", "--verify", "HEAD"), index, false, 4_096);
        if (head.isPresent()) {
            git(repository, List.of("read-tree", "HEAD"), index, true, 64 * 1024);
        } else {
            git(repository, List.of("read-tree", "--empty"), index, true, 64 * 1024);
        }
        git(repository, List.of("add", "-A", "--", "."), index, true, 1024 * 1024);
    }

    private byte[] patch(Path repository, Path index) throws Exception {
        prepareIndex(repository, index);
        Optional<SandboxResult> head =
                optionalGit(repository, List.of("rev-parse", "--verify", "HEAD"), index, false, 4_096);
        List<String> args = head.isPresent()
                ? List.of(
                        "diff", "--cached", "--binary", "--full-index", "--no-ext-diff", "--no-textconv", "HEAD", "--")
                : List.of("diff", "--cached", "--binary", "--full-index", "--no-ext-diff", "--no-textconv", "--");
        SandboxResult result = git(repository, args, index, true, MAX_PATCH_BYTES);
        byte[] value = result.stdout().getBytes(StandardCharsets.UTF_8);
        if (value.length > MAX_PATCH_BYTES || result.truncated()) {
            throw new IOException("Git patch exceeds 16 MiB");
        }
        return value;
    }

    private List<String> conflictPaths(Path repository, Path index) {
        try {
            SandboxResult result =
                    git(repository, List.of("diff", "--name-only", "--diff-filter=U", "-z"), index, false, 1024 * 1024);
            if (result.exitCode() != 0) {
                return List.of();
            }
            return java.util.Arrays.stream(result.stdout().split("\\u0000", -1))
                    .filter(value -> !value.isEmpty())
                    .limit(1_000)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private boolean cleanupAfterMerge(AgentThread parent, WorktreeRepository.WorktreeRecord record) {
        try {
            removeWorktree(parent.workingDirectory(), record.path());
            worktrees.setState(
                    record.id(), WorktreeRepository.State.CLEANED, false, record.details(), record.revision());
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private void removeUntrackedWorktree(AgentThread parent, Path path) {
        try {
            removeWorktree(parent.workingDirectory(), path);
        } catch (Exception ignored) {
        }
    }

    private void removeWorktree(Path repository, Path path) throws Exception {
        Path managed = path.toAbsolutePath().normalize();
        if (!cacheRoot.equals(managed.getParent())
                || !managed.getFileName().toString().startsWith("worktree_")
                || Files.isSymbolicLink(managed)) {
            throw new IOException("refusing to remove worktree outside managed cache");
        }
        git(repository, List.of("worktree", "remove", "--force", managed.toString()), null, true, 1024 * 1024);
    }

    private Path commonGitDirectory(Path repository) throws Exception {
        SandboxResult inside = git(repository, List.of("rev-parse", "--is-inside-work-tree"), null, false, 4_096);
        if (inside.exitCode() != 0 || !"true".equals(inside.stdout().strip())) {
            throw new IllegalStateException("writable subagents require a Git worktree");
        }
        String value = git(repository, List.of("rev-parse", "--git-common-dir"), null, true, 16 * 1024)
                .stdout()
                .strip();
        Path common = Path.of(value);
        if (!common.isAbsolute()) {
            common = repository.resolve(common);
        }
        return common.normalize().toRealPath();
    }

    private SandboxResult git(
            Path repository, List<String> arguments, Path index, boolean requireSuccess, long outputLimit)
            throws Exception {
        return git(repository, arguments, index, requireSuccess, outputLimit, Map.of());
    }

    private SandboxResult git(
            Path repository,
            List<String> arguments,
            Path index,
            boolean requireSuccess,
            long outputLimit,
            Map<String, String> extraEnvironment)
            throws Exception {
        Path root = repository.toRealPath();
        Path common = commonGitDirectoryWithoutGit(root);
        LinkedHashSet<Path> readable = new LinkedHashSet<>(List.of(root, common, cacheRoot));
        LinkedHashSet<Path> writable = new LinkedHashSet<>(List.of(common, cacheRoot));
        if (arguments.getFirst().equals("apply")) {
            writable.add(root);
        }
        Set<String> inherited = new LinkedHashSet<>(List.of(
                "PATH",
                "LANG",
                "LC_ALL",
                "GIT_INDEX_FILE",
                "GIT_AUTHOR_NAME",
                "GIT_AUTHOR_EMAIL",
                "GIT_COMMITTER_NAME",
                "GIT_COMMITTER_EMAIL"));
        SandboxPolicy requested = new SandboxPolicy(
                SandboxMode.WORKSPACE_WRITE,
                readable,
                writable,
                ceiling.protectedRoots(),
                NetworkPolicy.disabled(),
                inherited,
                GIT_TIMEOUT,
                Math.max(1, outputLimit));
        SandboxPolicy policy = ceiling.intersect(requested);
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        for (String name : List.of("PATH", "LANG", "LC_ALL")) {
            String value = System.getenv(name);
            if (value != null) {
                environment.put(name, value);
            }
        }
        if (index != null) {
            environment.put("GIT_INDEX_FILE", index.toString());
        }
        environment.putAll(extraEnvironment);
        ArrayList<String> argv = new ArrayList<>(gitPrefix);
        argv.addAll(List.of(
                "-c",
                "core.hooksPath=" + cacheRoot.resolve("empty-hooks"),
                "-c",
                "protocol.file.allow=never",
                "-c",
                "core.fsmonitor=false",
                "-c",
                "core.untrackedCache=false",
                "-c",
                "core.quotepath=true"));
        argv.addAll(arguments);
        SandboxResult result = sandbox.execute(new SandboxCommand(
                "git_" + UUID.randomUUID().toString().replace("-", ""), argv, root, environment, policy));
        if (result.timedOut()) {
            throw new IOException("controlled Git command timed out");
        }
        if (result.truncated()) {
            throw new IOException("controlled Git output exceeded limit");
        }
        if (requireSuccess && result.exitCode() != 0) {
            throw new IOException("controlled Git command failed: " + bounded(result.stderr()));
        }
        return result;
    }

    private Optional<SandboxResult> optionalGit(
            Path repository, List<String> args, Path index, boolean ignored, long outputLimit) throws Exception {
        SandboxResult result = git(repository, args, index, false, outputLimit);
        return result.exitCode() == 0 ? Optional.of(result) : Optional.empty();
    }

    /** Resolves .git without invoking Git, avoiding recursion while constructing Git policy. */
    private static Path commonGitDirectoryWithoutGit(Path root) throws IOException {
        Path dotGit = root.resolve(".git");
        if (Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS)) {
            return dotGit.toRealPath();
        }
        if (!Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS) || Files.size(dotGit) > 8 * 1024) {
            throw new IOException("working directory is not a managed Git worktree");
        }
        String line = Files.readString(dotGit, StandardCharsets.UTF_8)
                .lines()
                .findFirst()
                .orElse("")
                .strip();
        if (!line.startsWith("gitdir:")) {
            throw new IOException("invalid .git worktree link");
        }
        Path directory = Path.of(line.substring("gitdir:".length()).strip());
        if (!directory.isAbsolute()) {
            directory = root.resolve(directory);
        }
        directory = directory.normalize().toRealPath();
        Path common = directory.resolve("commondir");
        if (!Files.isRegularFile(common, LinkOption.NOFOLLOW_LINKS)) {
            return directory;
        }
        String relative = Files.readString(common, StandardCharsets.UTF_8)
                .lines()
                .findFirst()
                .orElse("")
                .strip();
        return directory.resolve(relative).normalize().toRealPath();
    }

    private Optional<AgentThread> child(ThreadId id) {
        return threads.readThread(id).map(ThreadSnapshot::thread).filter(value -> value.parentThreadId() != null);
    }

    private AgentThread requireThread(ThreadId id) {
        return threads.readThread(id)
                .orElseThrow(() -> new NoSuchElementException("thread not found: " + id))
                .thread();
    }

    private WorktreeRepository.WorktreeRecord requireWorktree(ThreadId child) {
        child(child).orElseThrow(() -> new IllegalArgumentException("thread is not a child thread: " + child));
        return worktrees
                .findByChild(child)
                .orElseThrow(() -> new IllegalStateException("child thread has no managed writable worktree"));
    }

    private static TurnConfig requireWritable(TurnConfig value) {
        if (value.sandboxPolicy().mode() != SandboxMode.WORKSPACE_WRITE) {
            throw new IllegalArgumentException("writable subagent requires a WORKSPACE_WRITE SUBAGENT profile");
        }
        return value;
    }

    private static TurnConfig forceReadOnly(TurnConfig value, Path root) {
        Set<Path> protectedRoots = Set.of(root.resolve(".git"), root.resolve(".javaclaw"));
        return new TurnConfig(
                value.model(),
                value.provider(),
                value.reasoningEffort(),
                root,
                SandboxPolicy.readOnly(Set.of(root), protectedRoots),
                value.approvalPolicy(),
                value.enabledTools(),
                value.attributes());
    }

    private static Workspace workspaceAt(Workspace source, Path root) {
        return new Workspace(
                source.id(),
                source.name(),
                root,
                source.revision(),
                source.locked(),
                source.lockReason(),
                source.createdAt(),
                source.updatedAt());
    }

    private Scratch scratch(String label) throws IOException {
        Path directory = Files.createTempDirectory(cacheRoot, label + "-");
        ownerOnly(directory, true);
        return new Scratch(directory, directory.resolve("index"));
    }

    private static Path prepareCacheRoot(Path requested) throws IOException {
        // 与 H2 保存的实际目录使用同一规范路径，避免 macOS /var 别名导致合法清理永久失败。
        Path root = com.javaclaw.server.security.PrivateDirectories.create(requested);
        com.javaclaw.server.security.PrivateDirectories.create(root.resolve("empty-hooks"));
        return root;
    }

    private static void ownerOnly(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows package ACLs are established by its native launcher/installer.
        }
    }

    private static String requireObjectId(String value) {
        String result = value.strip();
        if (!result.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("Git returned an invalid object id");
        }
        return result.toLowerCase(java.util.Locale.ROOT);
    }

    private static String requireKey(String value) {
        String result = Objects.requireNonNull(value, "idempotencyKey").strip();
        if (result.isEmpty() || result.length() > 500) {
            throw new IllegalArgumentException("idempotencyKey is invalid");
        }
        return result;
    }

    private static String bounded(String value) {
        if (value == null) {
            return "";
        }
        String result = value.strip();
        return result.length() <= 2_000 ? result : result.substring(0, 2_000);
    }

    private record ManagedBaseline(String id, Path path, String commit, Path commonGit) {}

    private static final class Scratch implements AutoCloseable {
        private final Path directory;
        private final Path index;

        private Scratch(Path directory, Path index) {
            this.directory = directory;
            this.index = index;
        }

        private Path directory() {
            return directory;
        }

        private Path index() {
            return index;
        }

        @Override
        public void close() {
            try {
                Files.deleteIfExists(directory.resolve("index.lock"));
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(index);
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(directory.resolve("child.patch"));
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(directory);
            } catch (IOException ignored) {
            }
        }
    }
}
