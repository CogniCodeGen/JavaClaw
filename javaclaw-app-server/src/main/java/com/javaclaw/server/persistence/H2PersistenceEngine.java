package com.javaclaw.server.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;

import com.javaclaw.agent.conversation.ConversationWindow;
import com.javaclaw.agent.runtime.persistence.AttachmentRepository;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.AttachmentMetadata;
import com.javaclaw.core.api.AttachmentReadChunk;
import com.javaclaw.core.api.AttachmentReconciliation;
import com.javaclaw.core.api.AttachmentUpload;
import com.javaclaw.core.api.AttemptId;
import com.javaclaw.core.api.ItemId;
import com.javaclaw.core.api.ItemState;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ProviderConversationState;
import com.javaclaw.core.api.StoredItem;
import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ThreadSnapshot;
import com.javaclaw.core.api.ThreadStatus;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.core.api.TurnStatus;
import com.javaclaw.core.api.Workspace;
import com.javaclaw.core.api.WorkspaceId;
import com.javaclaw.sandbox.api.SandboxPaths;

/** H2-backed append-only thread journal with projections and outbox in one transaction. */
final class H2PersistenceEngine {
    private static final long MAX_ATTACHMENT_BYTES = 256L * 1024L * 1024L;
    private static final long ORPHAN_GRACE_MILLIS =
            java.time.Duration.ofHours(24).toMillis();

    private final H2Database database;
    private final Clock clock;
    private final ThreadJsonCodec json;
    private final H2IdempotencyStore idempotency;
    private final H2EventWriter events;
    private final Path dataRoot;
    private final Path attachmentRoot;
    private final Path uploadRoot;
    private final Object attachmentGate = new Object();

    H2PersistenceEngine(Path dataRoot) {
        this(dataRoot, Clock.systemUTC());
    }

    H2PersistenceEngine(Path dataRoot, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = new ThreadJsonCodec();
        this.idempotency = new H2IdempotencyStore();
        this.events = new H2EventWriter(this.clock, this.json);
        Path root = V4DataRoot.initialize(Objects.requireNonNull(dataRoot, "dataRoot"));
        this.dataRoot = root;
        this.attachmentRoot = initializeAttachmentRoot(root);
        this.uploadRoot = initializeUploadRoot(root);
        JdbcDataSource configured = new JdbcDataSource();
        configured.setURL("jdbc:h2:file:" + root.resolve("javaclaw-v4").toString()
                + ";AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000");
        configured.setUser("sa");
        configured.setPassword("");
        this.database = new H2Database(configured);
        backfillLegacyCompactions();
    }

    H2PersistenceEngine(DataSource dataSource, Clock clock) {
        this.database = new H2Database(Objects.requireNonNull(dataSource, "dataSource"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = new ThreadJsonCodec();
        this.idempotency = new H2IdempotencyStore();
        this.events = new H2EventWriter(this.clock, this.json);
        this.dataRoot = null;
        this.attachmentRoot = null;
        this.uploadRoot = null;
        backfillLegacyCompactions();
    }

    /** Shared transaction owner used by feature repository adapters. */
    public H2Database database() {
        requireOpen();
        return database;
    }

    /** Credential keys must live in a platform configuration directory outside this data root. */
    public H2SecretStore secretStore(Path platformConfigurationDirectory) {
        requireOpen();
        return new H2SecretStore(database, platformConfigurationDirectory);
    }

    /** Canonical authority root used for blobs, database files and sandbox protection. */
    public Path dataRoot() {
        requireOpen();
        if (dataRoot == null) {
            throw new IllegalStateException("an injected DataSource has no filesystem data root");
        }
        return dataRoot;
    }

    public AgentThread createThread(
            String workspaceId, Path workingDirectory, String title, ThreadId parentThreadId, TurnId forkedFromTurnId) {
        return createThread(workspaceId, workingDirectory, title, parentThreadId, forkedFromTurnId, null);
    }

    public AgentThread createThread(
            String workspaceId,
            Path workingDirectory,
            String title,
            ThreadId parentThreadId,
            TurnId forkedFromTurnId,
            String idempotencyKey) {
        Path requested = SandboxPaths.canonicalize(workingDirectory);
        String safeTitle = title == null ? "" : title.strip();
        String requestHash =
                H2IdempotencyStore.requestHash(workspaceId, requested, safeTitle, parentThreadId, forkedFromTurnId);
        return transaction(connection -> {
            Optional<String> replay =
                    idempotency.replay(connection, "thread/start", idempotencyKey, requestHash, String.class);
            if (replay.isPresent()) {
                return requireThread(connection, new ThreadId(replay.get()), false);
            }
            Workspace workspace = requireWorkspace(connection, new WorkspaceId(workspaceId), true);
            if (workspace.locked()) {
                throw new IllegalStateException("workspace is locked: " + workspace.lockReason());
            }
            if (parentThreadId == null && !workspace.root().equals(requested)) {
                throw new IllegalArgumentException("thread working directory must equal the registered workspace root");
            }
            AgentThread created =
                    createThread(connection, workspaceId, requested, safeTitle, parentThreadId, forkedFromTurnId);
            idempotency.record(
                    connection,
                    "thread/start",
                    idempotencyKey,
                    requestHash,
                    created.id().value(),
                    clock.millis());
            return created;
        });
    }

    public AgentThread forkThread(ThreadId source, TurnId throughTurn, String title, Path workingDirectory) {
        return forkThread(source, throughTurn, title, workingDirectory, null);
    }

    public AgentThread forkThread(
            ThreadId source, TurnId throughTurn, String title, Path workingDirectory, String idempotencyKey) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(throughTurn, "throughTurn");
        Path requestedDirectory = workingDirectory == null
                ? null
                : workingDirectory.toAbsolutePath().normalize();
        String safeTitle = title == null ? "" : title.strip();
        String requestHash = H2IdempotencyStore.requestHash(source, throughTurn, safeTitle, requestedDirectory);
        return transaction(connection -> {
            Optional<String> replay =
                    idempotency.replay(connection, "thread/fork", idempotencyKey, requestHash, String.class);
            if (replay.isPresent()) {
                return requireThread(connection, new ThreadId(replay.get()), false);
            }
            AgentThread parent = requireThread(connection, source, true);
            List<AgentTurn> sourceTurns = turns(connection, source);
            int throughIndex = -1;
            for (int index = 0; index < sourceTurns.size(); index++) {
                if (sourceTurns.get(index).id().equals(throughTurn)) {
                    throughIndex = index;
                }
            }
            if (throughIndex < 0) {
                throw new NoSuchElementException("turn does not belong to source thread: " + throughTurn);
            }
            if (!sourceTurns.get(throughIndex).status().terminal()) {
                throw new IllegalStateException("cannot fork through a non-terminal turn");
            }
            Path forkDirectory = requestedDirectory == null ? parent.workingDirectory() : requestedDirectory;
            AgentThread fork = insertThread(
                    connection,
                    parent.workspaceId(),
                    forkDirectory,
                    safeTitle.isBlank() ? parent.title() + " (fork)" : safeTitle,
                    source,
                    throughTurn,
                    turnBoundarySequence(connection, source, throughTurn));
            Map<TurnId, TurnId> copiedTurns = new LinkedHashMap<>();
            for (int index = 0; index <= throughIndex; index++) {
                AgentTurn original = sourceTurns.get(index);
                TurnId copyId = TurnId.random();
                copiedTurns.put(original.id(), copyId);
                insertCopiedTurn(connection, fork.id(), copyId, original);
                appendCopiedTurnEvent(connection, source, fork.id(), original.id(), copyId);
                try (var refs = connection.prepareStatement(
                        "SELECT content_sha256 FROM attachment_references WHERE owner_type='TURN_INPUT' AND owner_id=?")) {
                    refs.setString(1, original.id().value());
                    try (var rows = refs.executeQuery()) {
                        while (rows.next()) {
                            retainOwnedReference(
                                    connection, "TURN_INPUT", copyId.value(), rows.getString(1), clock.millis());
                        }
                    }
                }
            }
            copyItems(connection, source, fork.id(), copiedTurns);
            appendEvent(
                    connection,
                    fork.id(),
                    null,
                    "thread/forked",
                    Map.of(
                            "sourceThreadId",
                            source.value(),
                            "throughTurnId",
                            throughTurn.value(),
                            "baseSequence",
                            Long.toString(fork.baseSequence()),
                            "workingDirectory",
                            forkDirectory.toString()),
                    fork.id().value(),
                    null);
            AgentThread result = requireThread(connection, fork.id(), false);
            idempotency.record(
                    connection,
                    "thread/fork",
                    idempotencyKey,
                    requestHash,
                    result.id().value(),
                    clock.millis());
            return result;
        });
    }

    public AgentThread forkBeforeTurn(
            ThreadId source, TurnId targetTurn, String title, Path workingDirectory, String idempotencyKey) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(targetTurn, "targetTurn");
        Path requestedDirectory = workingDirectory == null
                ? null
                : workingDirectory.toAbsolutePath().normalize();
        String safeTitle = title == null ? "" : title.strip();
        String requestHash = H2IdempotencyStore.requestHash(source, targetTurn, safeTitle, requestedDirectory);
        return transaction(connection -> {
            Optional<String> replay =
                    idempotency.replay(connection, "thread/fork-before", idempotencyKey, requestHash, String.class);
            if (replay.isPresent()) {
                return requireThread(connection, new ThreadId(replay.get()), false);
            }
            AgentThread parent = requireThread(connection, source, true);
            List<AgentTurn> sourceTurns = turns(connection, source);
            int targetIndex = -1;
            for (int index = 0; index < sourceTurns.size(); index++) {
                if (sourceTurns.get(index).id().equals(targetTurn)) {
                    targetIndex = index;
                    break;
                }
            }
            if (targetIndex < 0) {
                throw new NoSuchElementException("turn does not belong to source thread: " + targetTurn);
            }
            if (!sourceTurns.get(targetIndex).status().terminal()) {
                throw new IllegalStateException("cannot retry a non-terminal turn");
            }
            long baseSequence = targetIndex == 0
                    ? 0
                    : turnBoundarySequence(
                            connection, source, sourceTurns.get(targetIndex - 1).id());
            Path forkDirectory = requestedDirectory == null ? parent.workingDirectory() : requestedDirectory;
            AgentThread fork = insertThread(
                    connection,
                    parent.workspaceId(),
                    forkDirectory,
                    safeTitle.isBlank() ? parent.title() + " (retry)" : safeTitle,
                    source,
                    targetTurn,
                    baseSequence);
            Map<TurnId, TurnId> copiedTurns = new LinkedHashMap<>();
            for (int index = 0; index < targetIndex; index++) {
                AgentTurn original = sourceTurns.get(index);
                TurnId copyId = TurnId.random();
                copiedTurns.put(original.id(), copyId);
                insertCopiedTurn(connection, fork.id(), copyId, original);
                appendCopiedTurnEvent(connection, source, fork.id(), original.id(), copyId);
                copyTurnAttachmentReferences(connection, original.id(), copyId);
            }
            copyItems(connection, source, fork.id(), copiedTurns);
            appendEvent(
                    connection,
                    fork.id(),
                    null,
                    "thread/forkedBeforeTurn",
                    Map.of(
                            "sourceThreadId",
                            source.value(),
                            "targetTurnId",
                            targetTurn.value(),
                            "baseSequence",
                            Long.toString(baseSequence),
                            "workingDirectory",
                            forkDirectory.toString()),
                    fork.id().value(),
                    null);
            AgentThread result = requireThread(connection, fork.id(), false);
            idempotency.record(
                    connection,
                    "thread/fork-before",
                    idempotencyKey,
                    requestHash,
                    result.id().value(),
                    clock.millis());
            return result;
        });
    }

    private void copyTurnAttachmentReferences(Connection connection, TurnId original, TurnId copy) throws SQLException {
        try (var refs = connection.prepareStatement(
                "SELECT content_sha256 FROM attachment_references WHERE owner_type='TURN_INPUT' AND owner_id=?")) {
            refs.setString(1, original.value());
            try (var rows = refs.executeQuery()) {
                while (rows.next()) {
                    retainOwnedReference(connection, "TURN_INPUT", copy.value(), rows.getString(1), clock.millis());
                }
            }
        }
    }

    private void appendCopiedTurnEvent(
            Connection connection, ThreadId source, ThreadId fork, TurnId original, TurnId copy) throws SQLException {
        appendEvent(
                connection,
                fork,
                copy,
                "turn/copied",
                Map.of("sourceThreadId", source.value(), "sourceTurnId", original.value(), "turnId", copy.value()),
                copy.value(),
                original.value());
    }

    public Optional<AgentThread> findThread(ThreadId id) {
        return query(connection -> optionalThread(connection, id, false));
    }

    public List<AgentThread> listThreads(boolean includeArchived) {
        return query(connection -> {
            String sql = includeArchived
                    ? "SELECT * FROM threads WHERE status <> 'DELETED' ORDER BY updated_at DESC, thread_id"
                    : "SELECT * FROM threads WHERE status = 'ACTIVE' ORDER BY updated_at DESC, thread_id";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                    ResultSet rows = statement.executeQuery()) {
                List<AgentThread> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(readThread(rows));
                }
                return List.copyOf(result);
            }
        });
    }

    public AgentThread updateThreadTitle(ThreadId id, String title) {
        return updateThreadTitleInternal(id, title, -1, null, false);
    }

    public AgentThread updateThreadTitle(ThreadId id, String title, long expectedRevision, String idempotencyKey) {
        return updateThreadTitleInternal(id, title, expectedRevision, idempotencyKey, true);
    }

    private AgentThread updateThreadTitleInternal(
            ThreadId id, String title, long expectedRevision, String idempotencyKey, boolean guarded) {
        title = title == null ? "" : title.strip();
        if (title.length() > 1_000) {
            throw new IllegalArgumentException("thread title exceeds 1000 characters");
        }
        String finalTitle = title;
        String method = "thread/update";
        String requestHash = H2IdempotencyStore.requestHash(id, finalTitle, expectedRevision);
        return transaction(connection -> {
            if (guarded) {
                Optional<String> replay =
                        idempotency.replay(connection, method, idempotencyKey, requestHash, String.class);
                if (replay.isPresent()) {
                    return requireThread(connection, new ThreadId(replay.get()), false);
                }
            }
            AgentThread current = requireThread(connection, id, true);
            requireThreadRevision(current, expectedRevision, guarded);
            long now = clock.millis();
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE threads SET title = ?, revision = revision + 1, updated_at = ?
                    WHERE thread_id = ? AND revision = ?
                    """)) {
                statement.setString(1, finalTitle);
                statement.setLong(2, now);
                statement.setString(3, id.value());
                statement.setLong(4, current.revision());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("thread revision conflict: " + id);
                }
            }
            appendEvent(connection, id, null, "thread/updated", Map.of("title", finalTitle), id.value(), null);
            AgentThread result = requireThread(connection, current.id(), false);
            if (guarded) {
                idempotency.record(
                        connection,
                        method,
                        idempotencyKey,
                        requestHash,
                        result.id().value(),
                        clock.millis());
            }
            return result;
        });
    }

    public AgentThread setThreadStatus(ThreadId id, ThreadStatus status) {
        return setThreadStatusInternal(id, status, -1, null, false);
    }

    public AgentThread setThreadStatus(ThreadId id, ThreadStatus status, long expectedRevision, String idempotencyKey) {
        return setThreadStatusInternal(id, status, expectedRevision, idempotencyKey, true);
    }

    private AgentThread setThreadStatusInternal(
            ThreadId id, ThreadStatus status, long expectedRevision, String idempotencyKey, boolean guarded) {
        Objects.requireNonNull(status, "status");
        if (status == ThreadStatus.DELETED) {
            throw new IllegalArgumentException("use deleteThread for hard deletion");
        }
        String method = status == ThreadStatus.ARCHIVED ? "thread/archive" : "thread/unarchive";
        String requestHash = H2IdempotencyStore.requestHash(id, status, expectedRevision);
        return transaction(connection -> {
            if (guarded) {
                Optional<String> replay =
                        idempotency.replay(connection, method, idempotencyKey, requestHash, String.class);
                if (replay.isPresent()) {
                    return requireThread(connection, new ThreadId(replay.get()), false);
                }
            }
            AgentThread current = requireThread(connection, id, true);
            requireThreadRevision(current, expectedRevision, guarded);
            if (current.status() == status) {
                if (guarded) {
                    idempotency.record(
                            connection,
                            method,
                            idempotencyKey,
                            requestHash,
                            current.id().value(),
                            clock.millis());
                }
                return current;
            }
            if (hasActiveTurn(connection, id)) {
                throw new IllegalStateException("thread has an active turn: " + id);
            }
            long now = clock.millis();
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE threads SET status = ?, revision = revision + 1, updated_at = ?
                    WHERE thread_id = ? AND revision = ?
                    """)) {
                statement.setString(1, status.name());
                statement.setLong(2, now);
                statement.setString(3, id.value());
                statement.setLong(4, current.revision());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("thread revision conflict: " + id);
                }
            }
            appendEvent(
                    connection,
                    id,
                    null,
                    status == ThreadStatus.ARCHIVED ? "thread/archived" : "thread/unarchived",
                    Map.of("status", status.name()),
                    id.value(),
                    null);
            AgentThread result = requireThread(connection, id, false);
            if (guarded) {
                idempotency.record(
                        connection,
                        method,
                        idempotencyKey,
                        requestHash,
                        result.id().value(),
                        clock.millis());
            }
            return result;
        });
    }

    public void deleteThread(ThreadId id) {
        deleteThreadInternal(id, -1, null, false);
    }

    public void deleteThread(ThreadId id, long expectedRevision, String idempotencyKey) {
        deleteThreadInternal(id, expectedRevision, idempotencyKey, true);
    }

    private void deleteThreadInternal(ThreadId id, long expectedRevision, String idempotencyKey, boolean guarded) {
        String requestHash = H2IdempotencyStore.requestHash(id, expectedRevision);
        transaction(connection -> {
            if (guarded) {
                Optional<Boolean> replay =
                        idempotency.replay(connection, "thread/delete", idempotencyKey, requestHash, Boolean.class);
                if (replay.isPresent()) {
                    return null;
                }
            }
            AgentThread current = requireThread(connection, id, true);
            requireThreadRevision(current, expectedRevision, guarded);
            deleteThreadRows(connection, current);
            if (guarded) {
                idempotency.record(
                        connection, "thread/delete", idempotencyKey, requestHash, Boolean.TRUE, clock.millis());
            }
            return null;
        });
    }

    /** 回滚重试 RPC 已创建但未成功启动的分支，并在同一事务清除两段内部幂等结果，使调用方可用原 key 安全重试。 */
    public void rollbackRetryBranch(ThreadId id, String branchIdempotencyKey, String turnIdempotencyKey) {
        Objects.requireNonNull(id, "id");
        transaction(connection -> {
            AgentThread current = requireThread(connection, id, true);
            deleteThreadRows(connection, current);
            idempotency.forget(connection, "thread/fork-before", branchIdempotencyKey);
            idempotency.forget(connection, "turn/start", turnIdempotencyKey);
            return null;
        });
    }

    private void deleteThreadRows(Connection connection, AgentThread current) throws SQLException {
        ThreadId id = current.id();
        if (hasActiveTurn(connection, id)) {
            throw new IllegalStateException("cannot delete a thread with an active turn: " + id);
        }
        try (PreparedStatement turns = connection.prepareStatement("SELECT turn_id FROM turns WHERE thread_id = ?")) {
            turns.setString(1, id.value());
            try (ResultSet rows = turns.executeQuery()) {
                while (rows.next()) {
                    releaseOwnedReferences(connection, "TURN_INPUT", rows.getString(1), clock.millis());
                }
            }
        }
        try (PreparedStatement statement =
                connection.prepareStatement("DELETE FROM threads WHERE thread_id = ? AND revision = ?")) {
            statement.setString(1, id.value());
            statement.setLong(2, current.revision());
            if (statement.executeUpdate() != 1) {
                throw new NoSuchElementException("thread not found: " + id);
            }
        }
    }

    public AgentTurn startTurn(TurnStartCommand command) {
        Objects.requireNonNull(command, "command");
        String inputJson = json.inputs(command.input());
        String configJson = json.config(command.config());
        String requestHash = H2IdempotencyStore.requestHash(command.threadId(), inputJson, configJson);
        return transaction(connection -> {
            Optional<String> replay =
                    idempotency.replay(connection, "turn/start", command.idempotencyKey(), requestHash, String.class);
            if (replay.isPresent()) {
                return requireTurn(connection, new TurnId(replay.get()), false);
            }
            AgentThread thread = requireThread(connection, command.threadId(), true);
            if (thread.status() != ThreadStatus.ACTIVE) {
                throw new IllegalStateException("thread is not active: " + command.threadId());
            }
            if (hasActiveTurn(connection, command.threadId())) {
                throw new IllegalStateException("thread already has a non-terminal turn: " + command.threadId());
            }
            TurnId turnId = TurnId.random();
            AttemptId attemptId = AttemptId.random();
            long now = clock.millis();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO turns(
                        turn_id, thread_id, attempt_id, status, input_json, config_json,
                        idempotency_key, error_text, started_at, completed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL)
                    """)) {
                statement.setString(1, turnId.value());
                statement.setString(2, command.threadId().value());
                statement.setString(3, attemptId.value());
                statement.setString(4, TurnStatus.QUEUED.name());
                statement.setString(5, inputJson);
                statement.setString(6, configJson);
                statement.setString(7, command.idempotencyKey());
                statement.setLong(8, now);
                statement.executeUpdate();
            }
            insertAttempt(
                    connection,
                    turnId,
                    attemptId,
                    1,
                    TurnStatus.QUEUED,
                    command.config().provider(),
                    command.config().model(),
                    now,
                    null,
                    null);
            appendEvent(
                    connection,
                    command.threadId(),
                    turnId,
                    "turn/queued",
                    Map.of("turnId", turnId.value(), "status", TurnStatus.QUEUED.name()),
                    turnId.value(),
                    null);
            if (!"COMPACTION".equals(command.config().attributes().get("invocationPurpose"))) {
                for (TurnInput input : command.input()) {
                    if (input instanceof TurnInput.AttachmentRef attachment) {
                        AttachmentMetadata metadata = requireAttachment(connection, attachment.sha256(), true);
                        if (!metadata.mediaType().equalsIgnoreCase(attachment.mediaType())) {
                            throw new IllegalArgumentException("attachment media type does not match stored metadata");
                        }
                        retainOwnedReference(connection, "TURN_INPUT", turnId.value(), attachment.sha256(), now);
                    }
                    appendItem(
                            connection,
                            command.threadId(),
                            turnId,
                            new ThreadItem.UserMessage(
                                    describe(input),
                                    input instanceof TurnInput.AttachmentRef reference
                                            ? List.of(reference)
                                            : List.of()),
                            ItemState.COMPLETED);
                }
            }
            AgentTurn result = requireTurn(connection, turnId, false);
            idempotency.record(
                    connection,
                    "turn/start",
                    command.idempotencyKey(),
                    requestHash,
                    result.id().value(),
                    clock.millis());
            return result;
        });
    }

    public Optional<AgentTurn> findTurn(TurnId id) {
        return query(connection -> optionalTurn(connection, id, false));
    }

    public Optional<StoredItem> findItem(ItemId id) {
        Objects.requireNonNull(id, "id");
        return query(connection -> optionalItem(connection, id));
    }

    public Optional<AgentTurn> findTurnByIdempotencyKey(ThreadId threadId, String idempotencyKey) {
        Objects.requireNonNull(threadId, "threadId");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return query(connection -> findByIdempotencyKey(connection, threadId, idempotencyKey.strip()));
    }

    public AgentTurn transitionTurn(TurnId id, TurnStatus expected, TurnStatus next, String error) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        return transaction(connection -> {
            AgentTurn current = requireTurn(connection, id, true);
            if (current.status() != expected) {
                throw new IllegalStateException(
                        "turn state conflict: expected " + expected + " but was " + current.status());
            }
            if (!validTransition(expected, next)) {
                throw new IllegalArgumentException("invalid turn transition: " + expected + " -> " + next);
            }
            long now = clock.millis();
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE turns
                    SET status = ?, error_text = ?, completed_at = ?
                    WHERE turn_id = ? AND status = ?
                    """)) {
                statement.setString(1, next.name());
                statement.setString(2, error);
                if (next.terminal()) {
                    statement.setLong(3, now);
                } else {
                    statement.setNull(3, java.sql.Types.BIGINT);
                }
                statement.setString(4, id.value());
                statement.setString(5, expected.name());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("concurrent turn transition: " + id);
                }
            }
            updateAttempt(connection, current.attemptId(), next, now, error);
            if (next.terminal()) {
                cancelPendingInteractions(connection, current, next, now);
            }
            String type =
                    switch (next) {
                        case IN_PROGRESS -> expected == TurnStatus.QUEUED ? "turn/started" : "turn/resumed";
                        case WAITING_FOR_APPROVAL -> "turn/waitingForApproval";
                        case WAITING_FOR_INPUT -> "turn/waitingForInput";
                        default -> "turn/completed";
                    };
            Map<String, String> payload = error == null
                    ? Map.of("turnId", id.value(), "status", next.name())
                    : Map.of("turnId", id.value(), "status", next.name(), "error", error);
            appendEvent(connection, current.threadId(), id, type, payload, id.value(), null);
            return requireTurn(connection, id, false);
        });
    }

    private void cancelPendingInteractions(Connection connection, AgentTurn turn, TurnStatus terminalStatus, long now)
            throws SQLException {
        cancelPendingInteractions(
                connection, turn, terminalStatus, now, "approvals", "approval_id", "approval/cancelled", "approvalId");
        cancelPendingInteractions(
                connection,
                turn,
                terminalStatus,
                now,
                "user_input_requests",
                "request_id",
                "userInput/cancelled",
                "requestId");
    }

    private void cancelPendingInteractions(
            Connection connection,
            AgentTurn turn,
            TurnStatus terminalStatus,
            long now,
            String table,
            String idColumn,
            String eventType,
            String payloadKey)
            throws SQLException {
        List<String> pending = new ArrayList<>();
        String selectSql =
                "SELECT " + idColumn + " FROM " + table + " WHERE turn_id = ? AND state = 'PENDING' FOR UPDATE";
        try (PreparedStatement select = connection.prepareStatement(selectSql)) {
            select.setString(1, turn.id().value());
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    pending.add(rows.getString(1));
                }
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        String response = "{\"cancelled\":true,\"terminalStatus\":\"" + terminalStatus.name() + "\"}";
        String updateSql = "UPDATE " + table
                + " SET state = 'CANCELLED', response_json = ?, resolved_at = ?"
                + " WHERE " + idColumn + " = ? AND state = 'PENDING'";
        try (PreparedStatement update = connection.prepareStatement(updateSql)) {
            for (String id : pending) {
                update.setString(1, response);
                update.setLong(2, now);
                update.setString(3, id);
                update.addBatch();
            }
            int[] updated = update.executeBatch();
            for (int index = 0; index < updated.length; index++) {
                if (updated[index] != 1 && updated[index] != Statement.SUCCESS_NO_INFO) {
                    throw new IllegalStateException("pending interaction changed concurrently: " + pending.get(index));
                }
            }
        }
        for (String id : pending) {
            appendEvent(
                    connection,
                    turn.threadId(),
                    turn.id(),
                    eventType,
                    Map.of(payloadKey, id, "terminalStatus", terminalStatus.name()),
                    id,
                    null);
        }
    }

    public void recordUsage(ThreadId threadId, TurnId turnId, ModelUsage delta) {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(delta, "delta");
        transaction(connection -> {
            AgentTurn turn = requireTurn(connection, turnId, true);
            if (!turn.threadId().equals(threadId)) {
                throw new IllegalArgumentException("turn does not belong to thread");
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE execution_attempts
                    SET input_tokens = input_tokens + ?,
                        output_tokens = output_tokens + ?,
                        reasoning_tokens = reasoning_tokens + ?
                    WHERE attempt_id = ?
                    """)) {
                update.setLong(1, delta.inputTokens());
                update.setLong(2, delta.outputTokens());
                update.setLong(3, delta.reasoningTokens());
                update.setString(4, turn.attemptId().value());
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("execution attempt not found: " + turn.attemptId());
                }
            }
            ExecutionAttemptRow totals = executionAttempt(connection, turn.attemptId());
            appendEvent(
                    connection,
                    threadId,
                    turnId,
                    "usage/updated",
                    Map.of(
                            "inputTokens", Long.toString(totals.inputTokens()),
                            "outputTokens", Long.toString(totals.outputTokens()),
                            "reasoningTokens", Long.toString(totals.reasoningTokens()),
                            "deltaInputTokens", Long.toString(delta.inputTokens()),
                            "deltaOutputTokens", Long.toString(delta.outputTokens()),
                            "deltaReasoningTokens", Long.toString(delta.reasoningTokens())),
                    turnId.value(),
                    null);
            return null;
        });
    }

    public Optional<ConversationWindow> activeConversationWindow(ThreadId threadId) {
        Objects.requireNonNull(threadId, "threadId");
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM conversation_windows
                    WHERE thread_id = ?
                    ORDER BY window_number DESC
                    LIMIT 1
                    """)) {
                statement.setString(1, threadId.value());
                try (ResultSet row = statement.executeQuery()) {
                    return row.next() ? Optional.of(readConversationWindow(row)) : Optional.empty();
                }
            }
        });
    }

    public void saveProviderConversationState(
            ThreadId threadId, String model, long coveredSequence, ProviderConversationState state, ModelUsage usage) {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(usage, "usage");
        String selectedModel = ThreadId.required(model, "model");
        if (coveredSequence < 0) {
            throw new IllegalArgumentException("coveredSequence cannot be negative");
        }
        transaction(connection -> {
            requireThread(connection, threadId, true);
            ConversationWindow active =
                    activeConversationWindow(connection, threadId, true).orElse(null);
            ProviderConversationState persistent = state.persistent();
            if (active != null
                    && active.strategy() == ConversationWindow.Strategy.NATIVE
                    && active.provider().equalsIgnoreCase(persistent.provider())
                    && active.model().equals(selectedModel)) {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE conversation_windows
                        SET covered_sequence = ?, schema_version = ?, payload = ?,
                            input_tokens = input_tokens + ?, output_tokens = output_tokens + ?,
                            reasoning_tokens = reasoning_tokens + ?
                        WHERE thread_id = ? AND window_number = ?
                        """)) {
                    update.setLong(1, coveredSequence);
                    update.setInt(2, persistent.schemaVersion());
                    update.setString(3, persistent.payloadJson());
                    update.setLong(4, usage.inputTokens());
                    update.setLong(5, usage.outputTokens());
                    update.setLong(6, usage.reasoningTokens());
                    update.setString(7, threadId.value());
                    update.setLong(8, active.number());
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException("active conversation window changed concurrently");
                    }
                }
            } else {
                long number = active == null ? 0 : active.number() + 1;
                insertConversationWindow(
                        connection,
                        threadId,
                        number,
                        new ConversationWindow.Replacement(
                                ConversationWindow.Strategy.NATIVE,
                                persistent.provider(),
                                selectedModel,
                                coveredSequence,
                                persistent.schemaVersion(),
                                persistent.payloadJson(),
                                List.of(),
                                usage),
                        null);
            }
            return null;
        });
    }

    public StoredItem startItem(ThreadId threadId, TurnId turnId, String kind) {
        kind = ThreadId.required(kind, "kind");
        String finalKind = kind;
        return transaction(connection -> {
            AgentTurn turn = requireTurn(connection, turnId, true);
            if (!turn.threadId().equals(threadId)) {
                throw new IllegalArgumentException("turn does not belong to thread");
            }
            long ordinal = nextItemOrdinal(connection, turnId);
            ItemId itemId = ItemId.random();
            long now = clock.millis();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO items(
                        item_id, thread_id, turn_id, ordinal, state, kind,
                        payload_json, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'STARTED', ?, NULL, ?, ?)
                    """)) {
                statement.setString(1, itemId.value());
                statement.setString(2, threadId.value());
                statement.setString(3, turnId.value());
                statement.setLong(4, ordinal);
                statement.setString(5, finalKind);
                statement.setLong(6, now);
                statement.setLong(7, now);
                statement.executeUpdate();
            }
            appendEvent(
                    connection,
                    threadId,
                    turnId,
                    "item/started",
                    Map.of(
                            "itemId",
                            itemId.value(),
                            "kind",
                            finalKind,
                            "state",
                            ItemState.STARTED.name(),
                            "ordinal",
                            Long.toString(ordinal)),
                    turnId.value(),
                    null);
            return new StoredItem(
                    itemId,
                    threadId,
                    turnId,
                    ordinal,
                    ItemState.STARTED,
                    finalKind,
                    null,
                    Instant.ofEpochMilli(now),
                    Instant.ofEpochMilli(now));
        });
    }

    public StoredItem completeItem(ItemId itemId, ThreadItem item) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(item, "item");
        return transaction(connection -> transitionItem(connection, itemId, ItemState.COMPLETED, item));
    }

    public StoredItem completeCompaction(
            ItemId itemId, ThreadItem.ContextCompaction item, ConversationWindow.Replacement replacement) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(replacement, "replacement");
        return transaction(connection -> {
            StoredItem completed = transitionItem(connection, itemId, ItemState.COMPLETED, item);
            Optional<ConversationWindow> active = activeConversationWindow(connection, completed.threadId(), true);
            insertConversationWindow(
                    connection,
                    completed.threadId(),
                    active.map(value -> value.number() + 1).orElse(1L),
                    replacement,
                    itemId);
            return completed;
        });
    }

    public StoredItem failItem(ItemId itemId, String code, String message, boolean retryable) {
        Objects.requireNonNull(itemId, "itemId");
        ThreadItem.ErrorItem error = new ThreadItem.ErrorItem(code, message, retryable);
        return transaction(connection -> transitionItem(connection, itemId, ItemState.FAILED, error));
    }

    public StoredItem appendItem(ThreadId threadId, TurnId turnId, ThreadItem item, ItemState state) {
        return transaction(connection -> appendItem(connection, threadId, turnId, item, state));
    }

    public List<StoredItem> items(ThreadId threadId) {
        return query(connection -> items(connection, threadId));
    }

    public List<ThreadEvent> eventsAfter(ThreadId threadId, long afterSequence, int limit) {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must be non-negative");
        }
        int boundedLimit = Math.max(1, Math.min(10_000, limit));
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM thread_events
                    WHERE thread_id = ? AND event_sequence > ?
                    ORDER BY event_sequence LIMIT ?
                    """)) {
                statement.setString(1, threadId.value());
                statement.setLong(2, afterSequence);
                statement.setInt(3, boundedLimit);
                try (ResultSet rows = statement.executeQuery()) {
                    List<ThreadEvent> result = new ArrayList<>();
                    while (rows.next()) {
                        result.add(readEvent(rows));
                    }
                    return List.copyOf(result);
                }
            }
        });
    }

    public ThreadSnapshot snapshot(ThreadId threadId) {
        return query(connection -> new ThreadSnapshot(
                requireThread(connection, threadId, false), turns(connection, threadId), items(connection, threadId)));
    }

    public int recoverInterruptedTurns() {
        List<AgentTurn> active = query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM turns
                    WHERE status IN ('QUEUED', 'IN_PROGRESS', 'WAITING_FOR_APPROVAL', 'WAITING_FOR_INPUT')
                    ORDER BY started_at, turn_id
                    """);
                    ResultSet rows = statement.executeQuery()) {
                List<AgentTurn> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(readTurn(rows));
                }
                return List.copyOf(result);
            }
        });
        int changed = 0;
        for (AgentTurn turn : active) {
            try {
                List<ItemId> startedItems = query(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            SELECT item_id FROM items
                            WHERE turn_id = ? AND state = 'STARTED'
                            ORDER BY ordinal
                            """)) {
                        statement.setString(1, turn.id().value());
                        try (ResultSet rows = statement.executeQuery()) {
                            List<ItemId> result = new ArrayList<>();
                            while (rows.next()) {
                                result.add(new ItemId(rows.getString(1)));
                            }
                            return List.copyOf(result);
                        }
                    }
                });
                for (ItemId itemId : startedItems) {
                    failItem(itemId, "app_server_restarted", "app server restarted before the item completed", true);
                }
                transitionTurn(turn.id(), turn.status(), TurnStatus.INTERRUPTED, "app server restarted");
                changed++;
            } catch (IllegalStateException raced) {
                // Another runtime recovered or completed it first.
            }
        }
        return changed;
    }

    public AttachmentUpload startUpload(
            String expectedSha256, String mediaType, String displayName, long expectedSize, String idempotencyKey)
            throws IOException {
        requireAttachmentRoot();
        if (expectedSize < 0 || expectedSize > AttachmentRepository.MAX_ATTACHMENT_BYTES) {
            throw new IllegalArgumentException("attachment size exceeds the 256 MiB limit");
        }
        String expected = expectedSha256 == null || expectedSha256.isBlank() ? null : normalizedHash(expectedSha256);
        mediaType = Objects.requireNonNull(mediaType, "mediaType").strip();
        if (mediaType.isEmpty() || mediaType.length() > 300) {
            throw new IllegalArgumentException("mediaType must contain 1-300 characters");
        }
        displayName = Objects.requireNonNull(displayName, "displayName").strip();
        if (displayName.isEmpty()
                || displayName.length() > 255
                || displayName.contains("/")
                || displayName.contains("\\")
                || displayName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("displayName is invalid");
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.strip();
        String finalMediaType = mediaType;
        String finalDisplayName = displayName;
        synchronized (attachmentGate) {
            if (key != null) {
                Optional<AttachmentUpload> existing = uploadByIdempotencyKey(key);
                if (existing.isPresent()) {
                    return existing.get();
                }
            }
            Path temporary = Files.createTempFile(requireUploadRoot(), ".upload-", ".part");
            String uploadId = "upl_" + UUID.randomUUID().toString().replace("-", "");
            long now = clock.millis();
            long expires = now + java.time.Duration.ofHours(24).toMillis();
            try {
                return transaction(connection -> {
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO attachment_uploads(
                                upload_id, expected_sha256, media_type, display_name,
                                expected_size, received_bytes, temporary_path,
                                completed_sha256, idempotency_key, created_at, expires_at)
                            VALUES (?, ?, ?, ?, ?, 0, ?, NULL, ?, ?, ?)
                            """)) {
                        insert.setString(1, uploadId);
                        insert.setString(2, expected);
                        insert.setString(3, finalMediaType);
                        insert.setString(4, finalDisplayName);
                        insert.setLong(5, expectedSize);
                        insert.setString(6, temporary.getFileName().toString());
                        insert.setString(7, key);
                        insert.setLong(8, now);
                        insert.setLong(9, expires);
                        insert.executeUpdate();
                    }
                    return new AttachmentUpload(
                            uploadId,
                            expected,
                            finalMediaType,
                            finalDisplayName,
                            expectedSize,
                            0,
                            Instant.ofEpochMilli(expires));
                });
            } catch (RuntimeException failure) {
                Files.deleteIfExists(temporary);
                if (key != null) {
                    Optional<AttachmentUpload> winner = uploadByIdempotencyKey(key);
                    if (winner.isPresent()) {
                        return winner.get();
                    }
                }
                throw failure;
            }
        }
    }

    public AttachmentUpload appendUploadChunk(String uploadId, long offset, byte[] data) throws IOException {
        uploadId = ThreadId.required(uploadId, "uploadId");
        if (offset < 0) {
            throw new IllegalArgumentException("offset is negative");
        }
        byte[] chunk = Objects.requireNonNull(data, "data").clone();
        if (chunk.length < 1 || chunk.length > AttachmentRepository.MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("chunk must contain 1-1048576 bytes");
        }
        String finalUploadId = uploadId;
        synchronized (attachmentGate) {
            try {
                return transaction(connection -> {
                    UploadRow row = requireUpload(connection, finalUploadId, true);
                    if (row.completedSha256() != null) {
                        throw new IllegalStateException("upload is already complete");
                    }
                    if (row.expiresAt() <= clock.millis()) {
                        throw new IllegalStateException("upload has expired");
                    }
                    if (offset > row.receivedBytes() || offset + chunk.length > row.expectedSize()) {
                        throw new IllegalArgumentException("chunk offset or size is invalid");
                    }
                    Path path = uploadPath(row.temporaryPath());
                    if (offset < row.receivedBytes()) {
                        if (offset + chunk.length > row.receivedBytes() || !matches(path, offset, chunk)) {
                            throw new IllegalStateException("retried chunk content differs");
                        }
                        return row.publicView();
                    }
                    try (java.nio.channels.FileChannel channel =
                            java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE)) {
                        channel.position(offset);
                        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(chunk);
                        while (buffer.hasRemaining()) {
                            channel.write(buffer);
                        }
                        channel.force(true);
                    } catch (IOException failure) {
                        throw new UncheckedIo(failure);
                    }
                    long received = offset + chunk.length;
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE attachment_uploads SET received_bytes = ?
                            WHERE upload_id = ? AND received_bytes = ?
                            """)) {
                        update.setLong(1, received);
                        update.setString(2, finalUploadId);
                        update.setLong(3, row.receivedBytes());
                        if (update.executeUpdate() != 1) {
                            throw new IllegalStateException("concurrent upload update");
                        }
                    }
                    return row.withReceived(received).publicView();
                });
            } catch (UncheckedIo failure) {
                throw failure.io();
            }
        }
    }

    public AttachmentMetadata completeUpload(String uploadId) throws IOException {
        uploadId = ThreadId.required(uploadId, "uploadId");
        String finalUploadId = uploadId;
        synchronized (attachmentGate) {
            UploadRow snapshot = query(connection -> requireUpload(connection, finalUploadId, false));
            if (snapshot.completedSha256() != null) {
                return findAttachment(snapshot.completedSha256())
                        .orElseThrow(() -> new IOException("completed upload references a missing attachment"));
            }
            if (snapshot.receivedBytes() != snapshot.expectedSize()) {
                throw new IllegalStateException("upload is incomplete");
            }
            Path temporary = uploadPath(snapshot.temporaryPath());
            String hash = hash(temporary);
            if (snapshot.expectedSha256() != null && !snapshot.expectedSha256().equals(hash)) {
                throw new IOException("attachment SHA-256 does not match the declared value");
            }
            Path target = attachmentPath(hash);
            Files.createDirectories(target.getParent());
            if (Files.notExists(target)) {
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target);
                }
            }
            try {
                AttachmentMetadata metadata = transaction(connection -> {
                    UploadRow locked = requireUpload(connection, finalUploadId, true);
                    if (locked.completedSha256() != null) {
                        return requireAttachment(connection, locked.completedSha256(), false);
                    }
                    try {
                        requireAttachment(connection, hash, true);
                    } catch (NoSuchElementException missing) {
                        long now = clock.millis();
                        String storedPath = Path.of("attachments")
                                .resolve(attachmentRoot.relativize(target))
                                .toString();
                        try (PreparedStatement insert = connection.prepareStatement("""
                                INSERT INTO attachments(
                                    content_sha256, media_type, storage_path, size_bytes,
                                    reference_count, orphaned_at, created_at)
                                VALUES (?, ?, ?, ?, 0, NULL, ?)
                                """)) {
                            insert.setString(1, hash);
                            insert.setString(2, locked.mediaType());
                            insert.setString(3, storedPath);
                            insert.setLong(4, locked.expectedSize());
                            insert.setLong(5, now);
                            insert.executeUpdate();
                        }
                    }
                    retainOwnedReference(connection, "UPLOAD_SESSION", finalUploadId, hash, clock.millis());
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE attachment_uploads SET completed_sha256 = ?
                            WHERE upload_id = ? AND completed_sha256 IS NULL
                            """)) {
                        update.setString(1, hash);
                        update.setString(2, finalUploadId);
                        if (update.executeUpdate() != 1) {
                            throw new IllegalStateException("concurrent upload completion");
                        }
                    }
                    return requireAttachment(connection, hash, false);
                });
                Files.deleteIfExists(temporary);
                return metadata;
            } catch (RuntimeException failure) {
                if (Files.notExists(target) && Files.exists(temporary)) {
                    throw failure;
                }
                throw failure;
            }
        }
    }

    public AttachmentReadChunk readChunk(String sha256, long offset, int maximumBytes) throws IOException {
        String hash = normalizedHash(sha256);
        if (offset < 0) {
            throw new IllegalArgumentException("offset is negative");
        }
        if (maximumBytes < 1 || maximumBytes > AttachmentRepository.MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("maximumBytes must be between 1 and 1048576");
        }
        AttachmentMetadata metadata =
                findAttachment(hash).orElseThrow(() -> new NoSuchElementException("attachment not found: " + hash));
        if (offset > metadata.sizeBytes()) {
            throw new IllegalArgumentException("offset exceeds attachment size");
        }
        int length = Math.toIntExact(Math.min(maximumBytes, metadata.sizeBytes() - offset));
        byte[] value = new byte[length];
        try (java.nio.channels.FileChannel channel =
                java.nio.channels.FileChannel.open(attachmentPath(hash), java.nio.file.StandardOpenOption.READ)) {
            channel.position(offset);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(value);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {}
        }
        return new AttachmentReadChunk(metadata, offset, value, offset + value.length >= metadata.sizeBytes());
    }

    public int collectExpiredUploads() throws IOException {
        List<UploadRow> expired = query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM attachment_uploads WHERE expires_at <= ?
                    ORDER BY expires_at, upload_id
                    """)) {
                statement.setLong(1, clock.millis());
                try (ResultSet rows = statement.executeQuery()) {
                    List<UploadRow> result = new ArrayList<>();
                    while (rows.next()) {
                        result.add(readUpload(rows));
                    }
                    return List.copyOf(result);
                }
            }
        });
        synchronized (attachmentGate) {
            for (UploadRow row : expired) {
                Files.deleteIfExists(uploadPath(row.temporaryPath()));
                transaction(connection -> {
                    if (row.completedSha256() != null) {
                        releaseOwnedReference(
                                connection, "UPLOAD_SESSION", row.uploadId(), row.completedSha256(), clock.millis());
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            DELETE FROM attachment_uploads
                            WHERE upload_id = ? AND expires_at <= ?
                            """)) {
                        statement.setString(1, row.uploadId());
                        statement.setLong(2, clock.millis());
                        statement.executeUpdate();
                    }
                    return null;
                });
            }
        }
        return expired.size();
    }

    public AttachmentReconciliation reconcileAttachments() throws IOException {
        requireAttachmentRoot();
        int repairedUploads = 0;
        int collectedTemporaryFiles = 0;
        long now = clock.millis();
        synchronized (attachmentGate) {
            List<UploadRow> uploads = query(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                                "SELECT * FROM attachment_uploads ORDER BY created_at, upload_id");
                        ResultSet rows = statement.executeQuery()) {
                    List<UploadRow> result = new ArrayList<>();
                    while (rows.next()) {
                        result.add(readUpload(rows));
                    }
                    return List.copyOf(result);
                }
            });
            List<String> recoverCompletion = new ArrayList<>();
            for (UploadRow upload : uploads) {
                Path temporary = uploadPath(upload.temporaryPath());
                if (upload.completedSha256() != null) {
                    if (Files.deleteIfExists(temporary)) {
                        collectedTemporaryFiles++;
                    }
                    continue;
                }
                if (Files.isRegularFile(temporary, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    long actual = Files.size(temporary);
                    if (actual <= upload.expectedSize() && actual != upload.receivedBytes()) {
                        updateUploadProgress(upload.uploadId(), actual, upload.expiresAt());
                        repairedUploads++;
                    } else if (actual > upload.expectedSize()) {
                        Files.delete(temporary);
                        expireUpload(upload.uploadId(), now);
                        repairedUploads++;
                    }
                    continue;
                }
                if (upload.receivedBytes() == 0) {
                    Files.createFile(temporary);
                    repairedUploads++;
                    continue;
                }
                if (upload.expectedSha256() != null) {
                    Path target = attachmentPath(upload.expectedSha256());
                    if (Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            && Files.size(target) == upload.expectedSize()) {
                        Files.copy(target, temporary, StandardCopyOption.REPLACE_EXISTING);
                        updateUploadProgress(upload.uploadId(), upload.expectedSize(), upload.expiresAt());
                        recoverCompletion.add(upload.uploadId());
                        repairedUploads++;
                        continue;
                    }
                }
                expireUpload(upload.uploadId(), now);
                repairedUploads++;
            }
            for (String uploadId : recoverCompletion) {
                completeUpload(uploadId);
            }

            int rebuiltReferences = rebuildAttachmentReferences(now);
            int missingBlobs = recordMissingAttachmentBlobs(now);
            int collectedOrphanBlobs = collectOrphanAttachmentBlobs(now);
            collectedTemporaryFiles += collectUntrackedUploadFiles(now);
            return new AttachmentReconciliation(
                    repairedUploads, rebuiltReferences, missingBlobs, collectedTemporaryFiles, collectedOrphanBlobs);
        }
    }

    private void updateUploadProgress(String uploadId, long received, long expiresAt) {
        transaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE attachment_uploads SET received_bytes = ?, expires_at = ?
                    WHERE upload_id = ? AND completed_sha256 IS NULL
                    """)) {
                update.setLong(1, received);
                update.setLong(2, expiresAt);
                update.setString(3, uploadId);
                update.executeUpdate();
            }
            return null;
        });
    }

    private void expireUpload(String uploadId, long now) {
        transaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE attachment_uploads SET expires_at = ?
                    WHERE upload_id = ? AND completed_sha256 IS NULL
                    """)) {
                update.setLong(1, now);
                update.setString(2, uploadId);
                update.executeUpdate();
            }
            return null;
        });
    }

    private int rebuildAttachmentReferences(long now) {
        return transaction(connection -> {
            int changes = 0;
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT upload_id, completed_sha256 FROM attachment_uploads
                    WHERE completed_sha256 IS NOT NULL
                    """);
                    ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    if (!referenceExists(connection, "UPLOAD_SESSION", rows.getString(1), rows.getString(2))) {
                        retainOwnedReference(connection, "UPLOAD_SESSION", rows.getString(1), rows.getString(2), now);
                        changes++;
                    }
                }
            }
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT source_id, attachment_sha256 FROM knowledge_sources
                    """);
                    ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    if (!referenceExists(connection, "KNOWLEDGE_SOURCE", rows.getString(1), rows.getString(2))) {
                        retainOwnedReference(connection, "KNOWLEDGE_SOURCE", rows.getString(1), rows.getString(2), now);
                        changes++;
                    }
                }
            }
            changes += deleteDanglingReferences(connection, "TURN_INPUT", "turns", "turn_id");
            changes += deleteDanglingReferences(connection, "UPLOAD_SESSION", "attachment_uploads", "upload_id");
            changes += deleteDanglingReferences(connection, "KNOWLEDGE_SOURCE", "knowledge_sources", "source_id");

            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT a.content_sha256, a.reference_count,
                        (SELECT COUNT(*) FROM attachment_references r
                         WHERE r.content_sha256 = a.content_sha256) AS actual_count
                    FROM attachments a FOR UPDATE
                    """);
                    ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    long current = rows.getLong(2);
                    long actual = rows.getLong(3);
                    if (current == actual) {
                        continue;
                    }
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE attachments SET reference_count = ?,
                                orphaned_at = CASE WHEN ? = 0
                                    THEN COALESCE(orphaned_at, ?) ELSE NULL END
                            WHERE content_sha256 = ?
                            """)) {
                        update.setLong(1, actual);
                        update.setLong(2, actual);
                        update.setLong(3, now);
                        update.setString(4, rows.getString(1));
                        update.executeUpdate();
                    }
                    changes++;
                }
            }
            return changes;
        });
    }

    private boolean referenceExists(Connection connection, String ownerType, String ownerId, String hash)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM attachment_references
                WHERE owner_type = ? AND owner_id = ? AND content_sha256 = ?
                """)) {
            query.setString(1, ownerType);
            query.setString(2, ownerId);
            query.setString(3, hash);
            try (ResultSet row = query.executeQuery()) {
                return row.next();
            }
        }
    }

    private int deleteDanglingReferences(Connection connection, String ownerType, String ownerTable, String ownerColumn)
            throws SQLException {
        String sql = "DELETE FROM attachment_references r WHERE owner_type = ? "
                + "AND NOT EXISTS (SELECT 1 FROM " + ownerTable + " o WHERE o."
                + ownerColumn + " = r.owner_id)";
        try (PreparedStatement delete = connection.prepareStatement(sql)) {
            delete.setString(1, ownerType);
            return delete.executeUpdate();
        }
    }

    private int recordMissingAttachmentBlobs(long now) {
        List<String> missing = query(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                            "SELECT content_sha256 FROM attachments ORDER BY content_sha256");
                    ResultSet rows = query.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (rows.next()) {
                    String hash = rows.getString(1);
                    if (!Files.isRegularFile(attachmentPath(hash), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        result.add(hash);
                    }
                }
                return List.copyOf(result);
            }
        });
        if (missing.isEmpty()) {
            return 0;
        }
        transaction(connection -> {
            for (String hash : missing) {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO diagnostic_records(record_id, severity, component,
                            code, message, details_json, created_at)
                        VALUES (?, 'ERROR', 'attachment-reconciler', 'MISSING_BLOB', ?, ?, ?)
                        """)) {
                    insert.setString(1, "diag_" + UUID.randomUUID().toString().replace("-", ""));
                    insert.setString(2, "attachment blob is missing: " + hash);
                    insert.setString(3, "{\"sha256\":\"" + hash + "\"}");
                    insert.setLong(4, now);
                    insert.executeUpdate();
                }
            }
            return null;
        });
        return missing.size();
    }

    private int collectOrphanAttachmentBlobs(long now) throws IOException {
        long cutoff = now - ORPHAN_GRACE_MILLIS;
        List<String> garbage = transaction(connection -> {
            List<String> hashes = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT content_sha256 FROM attachments
                    WHERE reference_count = 0 AND orphaned_at IS NOT NULL
                        AND orphaned_at <= ? FOR UPDATE
                    """)) {
                query.setLong(1, cutoff);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        hashes.add(rows.getString(1));
                    }
                }
            }
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM attachments WHERE content_sha256 = ?
                        AND reference_count = 0
                    """)) {
                for (String hash : hashes) {
                    delete.setString(1, hash);
                    delete.addBatch();
                }
                delete.executeBatch();
            }
            return List.copyOf(hashes);
        });
        int collected = 0;
        for (String hash : garbage) {
            if (Files.deleteIfExists(attachmentPath(hash))) {
                collected++;
            }
        }
        try (var paths = Files.walk(requireAttachmentRoot())) {
            for (Path path : paths.filter(
                            value -> value.getFileName().toString().endsWith(".blob"))
                    .toList()) {
                if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || Files.getLastModifiedTime(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                                        .toMillis()
                                > cutoff) {
                    continue;
                }
                String name = path.getFileName().toString();
                String hash = name.substring(0, name.length() - ".blob".length());
                if (hash.matches("[0-9a-f]{64}") && findAttachment(hash).isEmpty() && Files.deleteIfExists(path)) {
                    collected++;
                }
            }
        }
        return collected;
    }

    private int collectUntrackedUploadFiles(long now) throws IOException {
        long cutoff = now - ORPHAN_GRACE_MILLIS;
        Set<String> tracked = query(connection -> {
            java.util.HashSet<String> names = new java.util.HashSet<>();
            try (PreparedStatement query =
                            connection.prepareStatement("SELECT temporary_path FROM attachment_uploads");
                    ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
            }
            return Set.copyOf(names);
        });
        int collected = 0;
        try (var paths = Files.list(requireUploadRoot())) {
            for (Path path : paths.toList()) {
                if (tracked.contains(path.getFileName().toString())) {
                    continue;
                }
                if (Files.getLastModifiedTime(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                                .toMillis()
                        > cutoff) {
                    continue;
                }
                if (Files.deleteIfExists(path)) {
                    collected++;
                }
            }
        }
        return collected;
    }

    public AttachmentMetadata put(InputStream source, String mediaType) throws IOException {
        Objects.requireNonNull(source, "source");
        mediaType = Objects.requireNonNull(mediaType, "mediaType").strip();
        if (mediaType.isEmpty() || mediaType.length() > 300) {
            throw new IllegalArgumentException("mediaType must contain 1-300 characters");
        }
        Path attachments = requireAttachmentRoot();
        Path temporary = Files.createTempFile(attachments, ".ingest-", ".tmp");
        boolean keep = false;
        try {
            MessageDigest digest = sha256();
            long size = copyAttachment(source, temporary, digest);
            String hash = java.util.HexFormat.of().formatHex(digest.digest());
            Path target = attachmentPath(hash);
            synchronized (attachmentGate) {
                Optional<AttachmentMetadata> existing = findAttachment(hash);
                if (existing.isPresent()) {
                    if (!existing.get().mediaType().equals(mediaType)) {
                        throw new IOException("attachment media type conflicts with existing content");
                    }
                    return retainAttachment(hash);
                }
                Files.createDirectories(target.getParent());
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target);
                }
                keep = true;
                String storedPath = Path.of("attachments")
                        .resolve(attachmentRoot.relativize(target))
                        .toString();
                String finalMediaType = mediaType;
                try {
                    return transaction(connection -> {
                        try (PreparedStatement statement = connection.prepareStatement("""
                                INSERT INTO attachments(
                                    content_sha256, media_type, storage_path, size_bytes,
                                    reference_count, orphaned_at, created_at)
                                VALUES (?, ?, ?, ?, 0, NULL, ?)
                                """)) {
                            statement.setString(1, hash);
                            statement.setString(2, finalMediaType);
                            statement.setString(3, storedPath);
                            statement.setLong(4, size);
                            statement.setLong(5, clock.millis());
                            statement.executeUpdate();
                        }
                        retainOwnedReference(
                                connection,
                                "DIRECT_INGEST",
                                "direct_" + UUID.randomUUID().toString().replace("-", ""),
                                hash,
                                clock.millis());
                        return requireAttachment(connection, hash, false);
                    });
                } catch (RuntimeException failure) {
                    Files.deleteIfExists(target);
                    throw failure;
                }
            }
        } finally {
            if (!keep) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public Optional<AttachmentMetadata> findAttachment(String sha256) {
        String hash = normalizedHash(sha256);
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT content_sha256, media_type, size_bytes, reference_count
                    FROM attachments WHERE content_sha256 = ?
                    """)) {
                statement.setString(1, hash);
                try (ResultSet row = statement.executeQuery()) {
                    return row.next() ? Optional.of(readAttachment(row)) : Optional.empty();
                }
            }
        });
    }

    public InputStream openAttachment(String sha256) throws IOException {
        String hash = normalizedHash(sha256);
        AttachmentMetadata metadata =
                findAttachment(hash).orElseThrow(() -> new NoSuchElementException("attachment not found: " + hash));
        Path path = attachmentPath(metadata.sha256());
        if (!Files.isRegularFile(path)) {
            throw new IOException("attachment blob is missing: " + hash);
        }
        return Files.newInputStream(path);
    }

    public AttachmentMetadata retainAttachment(String sha256) {
        String hash = normalizedHash(sha256);
        return transaction(connection -> {
            requireAttachment(connection, hash, true);
            retainOwnedReference(
                    connection,
                    "DIRECT_RETAIN",
                    "retain_" + UUID.randomUUID().toString().replace("-", ""),
                    hash,
                    clock.millis());
            return requireAttachment(connection, hash, false);
        });
    }

    public boolean releaseAttachment(String sha256) throws IOException {
        String hash = normalizedHash(sha256);
        boolean remove;
        synchronized (attachmentGate) {
            remove = transaction(connection -> {
                requireAttachment(connection, hash, true);
                String ownerType = null;
                String ownerId = null;
                try (PreparedStatement owner = connection.prepareStatement("""
                        SELECT owner_type, owner_id FROM attachment_references
                        WHERE content_sha256 = ? AND owner_type IN (
                            'UPLOAD_SESSION', 'DIRECT_INGEST', 'DIRECT_RETAIN')
                        ORDER BY CASE owner_type WHEN 'UPLOAD_SESSION' THEN 0 ELSE 1 END,
                            created_at, owner_id LIMIT 1 FOR UPDATE
                        """)) {
                    owner.setString(1, hash);
                    try (ResultSet row = owner.executeQuery()) {
                        if (row.next()) {
                            ownerType = row.getString(1);
                            ownerId = row.getString(2);
                        }
                    }
                }
                if (ownerType == null) {
                    return false;
                }
                releaseOwnedReference(connection, ownerType, ownerId, hash, clock.millis());
                if (requireAttachment(connection, hash, false).referenceCount() > 0) {
                    return false;
                }
                try (PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM attachments WHERE content_sha256 = ?")) {
                    statement.setString(1, hash);
                    statement.executeUpdate();
                }
                return true;
            });
            if (remove) {
                Files.deleteIfExists(attachmentPath(hash));
            }
        }
        return remove;
    }

    private void retainOwnedReference(Connection connection, String ownerType, String ownerId, String hash, long now)
            throws SQLException {
        requireAttachment(connection, hash, true);
        try (PreparedStatement existing = connection.prepareStatement("""
                SELECT 1 FROM attachment_references
                WHERE owner_type = ? AND owner_id = ? AND content_sha256 = ?
                """)) {
            existing.setString(1, ownerType);
            existing.setString(2, ownerId);
            existing.setString(3, hash);
            try (ResultSet row = existing.executeQuery()) {
                if (row.next()) {
                    return;
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO attachment_references(
                    owner_type, owner_id, content_sha256, created_at)
                VALUES (?, ?, ?, ?)
                """)) {
            insert.setString(1, ownerType);
            insert.setString(2, ownerId);
            insert.setString(3, hash);
            insert.setLong(4, now);
            insert.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE attachments SET reference_count = reference_count + 1,
                    orphaned_at = NULL WHERE content_sha256 = ?
                """)) {
            update.setString(1, hash);
            if (update.executeUpdate() != 1) {
                throw new NoSuchElementException("attachment not found: " + hash);
            }
        }
    }

    private void releaseOwnedReference(Connection connection, String ownerType, String ownerId, String hash, long now)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM attachment_references
                WHERE owner_type = ? AND owner_id = ? AND content_sha256 = ?
                """)) {
            delete.setString(1, ownerType);
            delete.setString(2, ownerId);
            delete.setString(3, hash);
            if (delete.executeUpdate() == 0) {
                return;
            }
        }
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE attachments SET reference_count = reference_count - 1,
                    orphaned_at = CASE WHEN reference_count <= 1 THEN ? ELSE NULL END
                WHERE content_sha256 = ? AND reference_count > 0
                """)) {
            update.setLong(1, now);
            update.setString(2, hash);
            update.executeUpdate();
        }
    }

    private void releaseOwnedReferences(Connection connection, String ownerType, String ownerId, long now)
            throws SQLException {
        List<String> hashes = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT content_sha256 FROM attachment_references
                WHERE owner_type = ? AND owner_id = ? FOR UPDATE
                """)) {
            query.setString(1, ownerType);
            query.setString(2, ownerId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    hashes.add(rows.getString(1));
                }
            }
        }
        for (String hash : hashes) {
            releaseOwnedReference(connection, ownerType, ownerId, hash, now);
        }
    }

    private AttachmentMetadata requireAttachment(Connection connection, String hash, boolean lock) throws SQLException {
        String sql = """
                SELECT content_sha256, media_type, size_bytes, reference_count
                FROM attachments WHERE content_sha256 = ?
                """ + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, hash);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new NoSuchElementException("attachment not found: " + hash);
                }
                return readAttachment(row);
            }
        }
    }

    private Optional<AttachmentUpload> uploadByIdempotencyKey(String key) {
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM attachment_uploads WHERE idempotency_key = ?
                    """)) {
                statement.setString(1, key);
                try (ResultSet row = statement.executeQuery()) {
                    return row.next() ? Optional.of(readUpload(row).publicView()) : Optional.empty();
                }
            }
        });
    }

    private UploadRow requireUpload(Connection connection, String uploadId, boolean lock) throws SQLException {
        String sql = "SELECT * FROM attachment_uploads WHERE upload_id = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uploadId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new NoSuchElementException("attachment upload not found: " + uploadId);
                }
                return readUpload(row);
            }
        }
    }

    private static UploadRow readUpload(ResultSet row) throws SQLException {
        return new UploadRow(
                row.getString("upload_id"),
                row.getString("expected_sha256"),
                row.getString("media_type"),
                row.getString("display_name"),
                row.getLong("expected_size"),
                row.getLong("received_bytes"),
                row.getString("temporary_path"),
                row.getString("completed_sha256"),
                row.getLong("expires_at"));
    }

    private static boolean matches(Path path, long offset, byte[] expected) {
        try (java.nio.channels.FileChannel channel =
                java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ)) {
            channel.position(offset);
            java.nio.ByteBuffer actual = java.nio.ByteBuffer.allocate(expected.length);
            while (actual.hasRemaining() && channel.read(actual) >= 0) {}
            if (actual.position() != expected.length) {
                return false;
            }
            return java.util.Arrays.equals(actual.array(), expected);
        } catch (IOException failure) {
            throw new UncheckedIo(failure);
        }
    }

    private static String hash(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static AttachmentMetadata readAttachment(ResultSet row) throws SQLException {
        return new AttachmentMetadata(
                row.getString("content_sha256"),
                row.getString("media_type"),
                row.getLong("size_bytes"),
                row.getLong("reference_count"));
    }

    private Path attachmentPath(String hash) {
        Path root = requireAttachmentRoot();
        return root.resolve(hash.substring(0, 2))
                .resolve(hash.substring(2, 4))
                .resolve(hash + ".blob")
                .normalize();
    }

    private Path requireAttachmentRoot() {
        requireOpen();
        if (attachmentRoot == null || dataRoot == null) {
            throw new IllegalStateException("attachment storage requires a v4 file data root");
        }
        return attachmentRoot;
    }

    private Path requireUploadRoot() {
        requireOpen();
        if (uploadRoot == null || dataRoot == null) {
            throw new IllegalStateException("upload storage requires a v4 file data root");
        }
        return uploadRoot;
    }

    private Path uploadPath(String storedName) {
        if (storedName == null
                || storedName.isBlank()
                || !Path.of(storedName).getFileName().toString().equals(storedName)) {
            throw new IllegalStateException("stored upload path is invalid");
        }
        Path result = requireUploadRoot().resolve(storedName).normalize();
        if (!result.getParent().equals(requireUploadRoot())) {
            throw new IllegalStateException("stored upload path escapes upload root");
        }
        return result;
    }

    private static Path initializeAttachmentRoot(Path root) {
        try {
            Path attachments = root.resolve("attachments");
            Files.createDirectories(attachments);
            return attachments.toRealPath();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot initialize attachment storage", failure);
        }
    }

    private static Path initializeUploadRoot(Path root) {
        try {
            Path uploads = root.resolve("uploads");
            Files.createDirectories(uploads);
            return uploads.toRealPath();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot initialize attachment upload storage", failure);
        }
    }

    private static long copyAttachment(InputStream source, Path target, MessageDigest digest) throws IOException {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream output = Files.newOutputStream(target)) {
            int count;
            while ((count = source.read(buffer)) >= 0) {
                total += count;
                if (total > MAX_ATTACHMENT_BYTES) {
                    throw new IOException("attachment exceeds " + MAX_ATTACHMENT_BYTES + " bytes");
                }
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        }
        return total;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String normalizedHash(String value) {
        value = Objects.requireNonNull(value, "sha256").toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be 64 hex characters");
        }
        return value;
    }

    private AgentThread createThread(
            Connection connection,
            String workspaceId,
            Path workingDirectory,
            String title,
            ThreadId parentThreadId,
            TurnId forkedFromTurnId)
            throws SQLException {
        AgentThread thread =
                insertThread(connection, workspaceId, workingDirectory, title, parentThreadId, forkedFromTurnId, 0);
        appendEvent(
                connection,
                thread.id(),
                null,
                "thread/created",
                Map.of("workspaceId", thread.workspaceId()),
                thread.id().value(),
                null);
        return requireThread(connection, thread.id(), false);
    }

    private AgentThread insertThread(
            Connection connection,
            String workspaceId,
            Path workingDirectory,
            String title,
            ThreadId parentThreadId,
            TurnId forkedFromTurnId,
            long baseSequence)
            throws SQLException {
        ThreadId id = ThreadId.random();
        long now = clock.millis();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO threads(
                    thread_id, workspace_id, parent_thread_id, forked_from_turn_id,
                    title, cwd, status, base_sequence, last_sequence, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, 0, ?, ?)
                """)) {
            statement.setString(1, id.value());
            statement.setString(
                    2, Objects.requireNonNull(workspaceId, "workspaceId").strip());
            statement.setString(3, parentThreadId == null ? null : parentThreadId.value());
            statement.setString(4, forkedFromTurnId == null ? null : forkedFromTurnId.value());
            statement.setString(5, title == null ? "" : title.strip());
            statement.setString(6, SandboxPaths.canonicalize(workingDirectory).toString());
            statement.setLong(7, baseSequence);
            statement.setLong(8, now);
            statement.setLong(9, now);
            statement.executeUpdate();
        }
        return requireThread(connection, id, false);
    }

    private static long turnBoundarySequence(Connection connection, ThreadId threadId, TurnId turnId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT MAX(event_sequence) FROM thread_events
                WHERE thread_id = ? AND turn_id = ?
                """)) {
            statement.setString(1, threadId.value());
            statement.setString(2, turnId.value());
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                long sequence = row.getLong(1);
                if (row.wasNull() || sequence < 1) {
                    throw new IllegalStateException("fork boundary has no durable event: " + turnId);
                }
                return sequence;
            }
        }
    }

    private void insertCopiedTurn(Connection connection, ThreadId threadId, TurnId copyId, AgentTurn original)
            throws SQLException {
        AttemptId copiedAttempt = AttemptId.random();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO turns(
                    turn_id, thread_id, attempt_id, status, input_json, config_json,
                    idempotency_key, error_text, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)
                """)) {
            statement.setString(1, copyId.value());
            statement.setString(2, threadId.value());
            statement.setString(3, copiedAttempt.value());
            statement.setString(4, original.status().name());
            statement.setString(5, json.inputs(original.input()));
            statement.setString(6, json.config(original.config()));
            statement.setString(7, original.error());
            statement.setLong(8, original.startedAt().toEpochMilli());
            if (original.completedAt() == null) {
                statement.setNull(9, java.sql.Types.BIGINT);
            } else {
                statement.setLong(9, original.completedAt().toEpochMilli());
            }
            statement.executeUpdate();
        }
        insertAttempt(
                connection,
                copyId,
                copiedAttempt,
                1,
                original.status(),
                original.config().provider(),
                original.config().model(),
                original.startedAt().toEpochMilli(),
                original.completedAt() == null ? null : original.completedAt().toEpochMilli(),
                original.error());
    }

    private void insertAttempt(
            Connection connection,
            TurnId turnId,
            AttemptId attemptId,
            int number,
            TurnStatus status,
            String provider,
            String model,
            long startedAt,
            Long completedAt,
            String error)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO execution_attempts(
                    attempt_id, turn_id, attempt_number, status, provider, model,
                    started_at, completed_at, error_text)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, attemptId.value());
            statement.setString(2, turnId.value());
            statement.setInt(3, number);
            statement.setString(4, status.name());
            statement.setString(5, provider);
            statement.setString(6, model);
            statement.setLong(7, startedAt);
            if (completedAt == null) {
                statement.setNull(8, java.sql.Types.BIGINT);
            } else {
                statement.setLong(8, completedAt);
            }
            statement.setString(9, error);
            statement.executeUpdate();
        }
    }

    private void updateAttempt(Connection connection, AttemptId attemptId, TurnStatus status, long now, String error)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE execution_attempts
                SET status = ?, completed_at = ?, error_text = ?
                WHERE attempt_id = ?
                """)) {
            statement.setString(1, status.name());
            if (status.terminal()) {
                statement.setLong(2, now);
            } else {
                statement.setNull(2, java.sql.Types.BIGINT);
            }
            statement.setString(3, error);
            statement.setString(4, attemptId.value());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("execution attempt not found: " + attemptId);
            }
        }
    }

    ExecutionAttemptRow executionAttempt(AttemptId attemptId) {
        return query(connection -> executionAttempt(connection, attemptId));
    }

    record ExecutionAttemptRow(
            AttemptId id,
            TurnId turnId,
            int number,
            TurnStatus status,
            String provider,
            String model,
            long inputTokens,
            long outputTokens,
            long reasoningTokens,
            Instant startedAt,
            Instant completedAt,
            String error) {}

    private ExecutionAttemptRow executionAttempt(Connection connection, AttemptId attemptId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT * FROM execution_attempts WHERE attempt_id = ?")) {
            statement.setString(1, attemptId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new NoSuchElementException("execution attempt not found: " + attemptId);
                }
                Long completed = row.getObject("completed_at") == null ? null : row.getLong("completed_at");
                return new ExecutionAttemptRow(
                        attemptId,
                        new TurnId(row.getString("turn_id")),
                        row.getInt("attempt_number"),
                        TurnStatus.valueOf(row.getString("status")),
                        row.getString("provider"),
                        row.getString("model"),
                        row.getLong("input_tokens"),
                        row.getLong("output_tokens"),
                        row.getLong("reasoning_tokens"),
                        Instant.ofEpochMilli(row.getLong("started_at")),
                        completed == null ? null : Instant.ofEpochMilli(completed),
                        row.getString("error_text"));
            }
        }
    }

    private void copyItems(Connection connection, ThreadId source, ThreadId target, Map<TurnId, TurnId> copiedTurns)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT i.* FROM items i
                JOIN turns t ON t.turn_id = i.turn_id
                LEFT JOIN (
                    SELECT turn_id, MIN(event_sequence) AS first_sequence
                    FROM thread_events
                    WHERE thread_id = ? AND turn_id IS NOT NULL
                    GROUP BY turn_id
                ) event_order ON event_order.turn_id = t.turn_id
                WHERE i.thread_id = ?
                ORDER BY CASE WHEN event_order.first_sequence IS NULL THEN 1 ELSE 0 END,
                    event_order.first_sequence, t.started_at, t.turn_id, i.ordinal
                """)) {
            query.setString(1, source.value());
            query.setString(2, source.value());
            try (ResultSet rows = query.executeQuery()) {
                Map<TurnId, Long> ordinals = new LinkedHashMap<>();
                while (rows.next()) {
                    TurnId oldTurn = new TurnId(rows.getString("turn_id"));
                    TurnId newTurn = copiedTurns.get(oldTurn);
                    if (newTurn == null) {
                        continue;
                    }
                    long ordinal = ordinals.merge(newTurn, 1L, Long::sum);
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO items(
                                item_id, thread_id, turn_id, ordinal, state, kind,
                                payload_json, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)) {
                        insert.setString(1, ItemId.random().value());
                        insert.setString(2, target.value());
                        insert.setString(3, newTurn.value());
                        insert.setLong(4, ordinal);
                        insert.setString(5, rows.getString("state"));
                        insert.setString(6, rows.getString("kind"));
                        insert.setString(7, rows.getString("payload_json"));
                        insert.setLong(8, rows.getLong("created_at"));
                        insert.setLong(9, rows.getLong("updated_at"));
                        insert.executeUpdate();
                    }
                }
            }
        }
    }

    private StoredItem appendItem(
            Connection connection, ThreadId threadId, TurnId turnId, ThreadItem item, ItemState state)
            throws SQLException {
        AgentTurn turn = requireTurn(connection, turnId, true);
        if (!turn.threadId().equals(threadId)) {
            throw new IllegalArgumentException("turn does not belong to thread");
        }
        long ordinal = nextItemOrdinal(connection, turnId);
        ItemId itemId = ItemId.random();
        long now = clock.millis();
        if (item instanceof ThreadItem.UserMessage message) {
            for (var reference : message.attachments()) {
                AttachmentMetadata metadata = requireAttachment(connection, reference.sha256(), true);
                if (!metadata.mediaType().equalsIgnoreCase(reference.mediaType())) {
                    throw new IllegalArgumentException("attachment media type does not match stored metadata");
                }
                // 初始输入与 steer 的引用都归属于 Turn，同事务持久化，防止客户端 release 提前回收历史图片。
                retainOwnedReference(connection, "TURN_INPUT", turnId.value(), reference.sha256(), now);
            }
        }
        retainGeneratedAttachment(connection, turnId, item, now);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO items(
                    item_id, thread_id, turn_id, ordinal, state, kind,
                    payload_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, itemId.value());
            statement.setString(2, threadId.value());
            statement.setString(3, turnId.value());
            statement.setLong(4, ordinal);
            statement.setString(5, state.name());
            statement.setString(6, item.kind());
            statement.setString(7, json.item(item));
            statement.setLong(8, now);
            statement.setLong(9, now);
            statement.executeUpdate();
        }
        H2ExecutionProjection.append(connection, threadId, turnId, itemId, item, json.item(item), now);
        Map<String, String> eventPayload = new LinkedHashMap<>();
        eventPayload.put("itemId", itemId.value());
        eventPayload.put("kind", item.kind());
        eventPayload.put("state", state.name());
        eventPayload.put("ordinal", Long.toString(ordinal));
        String eventType = "item/" + state.name().toLowerCase(java.util.Locale.ROOT);
        String correlationId = turnId.value();
        if (item instanceof ThreadItem.ApprovalRequest approval) {
            try (PreparedStatement approvalInsert = connection.prepareStatement("""
                    INSERT INTO approvals(
                        approval_id, thread_id, turn_id, state, request_json,
                        response_json, created_at, resolved_at)
                    VALUES (?, ?, ?, 'PENDING', ?, NULL, ?, NULL)
                    """)) {
                approvalInsert.setString(1, approval.approvalId());
                approvalInsert.setString(2, threadId.value());
                approvalInsert.setString(3, turnId.value());
                approvalInsert.setString(4, json.item(item));
                approvalInsert.setLong(5, now);
                approvalInsert.executeUpdate();
            }
            eventType = "approval/requested";
            correlationId = approval.approvalId();
            eventPayload.put("approvalId", approval.approvalId());
            eventPayload.put("risk", approval.risk());
        } else if (item instanceof ThreadItem.UserInputRequest request) {
            try (PreparedStatement inputInsert = connection.prepareStatement("""
                    INSERT INTO user_input_requests(
                        request_id, thread_id, turn_id, state, request_json,
                        response_json, created_at, resolved_at)
                    VALUES (?, ?, ?, 'PENDING', ?, NULL, ?, NULL)
                    """)) {
                inputInsert.setString(1, request.requestId());
                inputInsert.setString(2, threadId.value());
                inputInsert.setString(3, turnId.value());
                inputInsert.setString(4, json.item(item));
                inputInsert.setLong(5, now);
                inputInsert.executeUpdate();
            }
            eventType = "userInput/requested";
            correlationId = request.requestId();
            eventPayload.put("requestId", request.requestId());
        } else if (item instanceof ThreadItem.UserInputResponse response) {
            try (PreparedStatement inputUpdate = connection.prepareStatement("""
                    UPDATE user_input_requests
                    SET state = ?, response_json = ?, resolved_at = ?
                    WHERE request_id = ? AND state = 'PENDING'
                    """)) {
                inputUpdate.setString(1, response.cancelled() ? "CANCELLED" : "RESOLVED");
                inputUpdate.setString(2, json.item(item));
                inputUpdate.setLong(3, now);
                inputUpdate.setString(4, response.requestId());
                if (inputUpdate.executeUpdate() == 1) {
                    eventType = "userInput/resolved";
                    correlationId = response.requestId();
                    eventPayload.put("requestId", response.requestId());
                    eventPayload.put("cancelled", Boolean.toString(response.cancelled()));
                }
            }
        }
        appendEvent(connection, threadId, turnId, eventType, Map.copyOf(eventPayload), correlationId, null);
        return new StoredItem(
                itemId,
                threadId,
                turnId,
                ordinal,
                state,
                item.kind(),
                item,
                Instant.ofEpochMilli(now),
                Instant.ofEpochMilli(now));
    }

    private long nextItemOrdinal(Connection connection, TurnId turnId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(MAX(ordinal), 0) + 1 FROM items WHERE turn_id = ?
                """)) {
            statement.setString(1, turnId.value());
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private StoredItem transitionItem(Connection connection, ItemId itemId, ItemState terminalState, ThreadItem item)
            throws SQLException {
        if (terminalState == ItemState.STARTED) {
            throw new IllegalArgumentException("terminal item state required");
        }
        String threadValue;
        String turnValue;
        String kind;
        long ordinal;
        long createdAt;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM items WHERE item_id = ? FOR UPDATE
                """)) {
            query.setString(1, itemId.value());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    throw new NoSuchElementException("item not found: " + itemId);
                }
                if (!ItemState.STARTED.name().equals(row.getString("state"))) {
                    throw new IllegalStateException("item is already terminal: " + itemId);
                }
                threadValue = row.getString("thread_id");
                turnValue = row.getString("turn_id");
                kind = row.getString("kind");
                ordinal = row.getLong("ordinal");
                createdAt = row.getLong("created_at");
            }
        }
        if (terminalState == ItemState.COMPLETED && !kind.equals(item.kind())) {
            throw new IllegalArgumentException("completed item kind changed from " + kind + " to " + item.kind());
        }
        long now = clock.millis();
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE items SET state = ?, payload_json = ?, updated_at = ?
                WHERE item_id = ? AND state = 'STARTED'
                """)) {
            update.setString(1, terminalState.name());
            update.setString(2, json.item(item));
            update.setLong(3, now);
            update.setString(4, itemId.value());
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("concurrent item transition: " + itemId);
            }
        }
        ThreadId threadId = new ThreadId(threadValue);
        TurnId turnId = new TurnId(turnValue);
        retainGeneratedAttachment(connection, turnId, item, now);
        H2ExecutionProjection.append(connection, threadId, turnId, itemId, item, json.item(item), now);
        appendEvent(
                connection,
                threadId,
                turnId,
                "item/" + terminalState.name().toLowerCase(java.util.Locale.ROOT),
                Map.of(
                        "itemId",
                        itemId.value(),
                        "kind",
                        kind,
                        "state",
                        terminalState.name(),
                        "ordinal",
                        Long.toString(ordinal)),
                turnId.value(),
                null);
        return new StoredItem(
                itemId,
                threadId,
                turnId,
                ordinal,
                terminalState,
                kind,
                item,
                Instant.ofEpochMilli(createdAt),
                Instant.ofEpochMilli(now));
    }

    private void retainGeneratedAttachment(Connection connection, TurnId turnId, ThreadItem item, long now)
            throws SQLException {
        String uri = item instanceof ThreadItem.ImageView image
                ? image.uri()
                : item instanceof ThreadItem.Artifact artifact
                                && artifact.category().equals("browserAttachment")
                        ? artifact.content()
                        : "";
        if (uri.matches("attachment:sha256:[a-f0-9]{64}")) {
            String hash = uri.substring("attachment:sha256:".length());
            requireAttachment(connection, hash, true);
            // 产物与输入共享 Turn 所有权，因此分支、删除和恢复沿用同一引用生命周期。
            retainOwnedReference(connection, "TURN_INPUT", turnId.value(), hash, now);
        }
    }

    private ThreadEvent appendEvent(
            Connection connection,
            ThreadId threadId,
            TurnId turnId,
            String type,
            Map<String, String> payload,
            String correlationId,
            String causationId)
            throws SQLException {
        return events.append(connection, threadId, turnId, type, payload, correlationId, causationId);
    }

    private Optional<AgentTurn> findByIdempotencyKey(Connection connection, ThreadId threadId, String key)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM turns WHERE thread_id = ? AND idempotency_key = ?
                """)) {
            statement.setString(1, threadId.value());
            statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readTurn(rows)) : Optional.empty();
            }
        }
    }

    private boolean hasActiveTurn(Connection connection, ThreadId threadId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM turns
                WHERE thread_id = ?
                  AND status IN ('QUEUED', 'IN_PROGRESS', 'WAITING_FOR_APPROVAL', 'WAITING_FOR_INPUT')
                """)) {
            statement.setString(1, threadId.value());
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1) > 0;
            }
        }
    }

    private Optional<AgentThread> optionalThread(Connection connection, ThreadId id, boolean lock) throws SQLException {
        String sql = "SELECT * FROM threads WHERE thread_id = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readThread(rows)) : Optional.empty();
            }
        }
    }

    private AgentThread requireThread(Connection connection, ThreadId id, boolean lock) throws SQLException {
        return optionalThread(connection, id, lock)
                .orElseThrow(() -> new NoSuchElementException("thread not found: " + id));
    }

    private Optional<AgentTurn> optionalTurn(Connection connection, TurnId id, boolean lock) throws SQLException {
        String sql = "SELECT * FROM turns WHERE turn_id = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readTurn(rows)) : Optional.empty();
            }
        }
    }

    private AgentTurn requireTurn(Connection connection, TurnId id, boolean lock) throws SQLException {
        return optionalTurn(connection, id, lock)
                .orElseThrow(() -> new NoSuchElementException("turn not found: " + id));
    }

    private List<AgentTurn> turns(Connection connection, ThreadId threadId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.* FROM turns t
                LEFT JOIN (
                    SELECT turn_id, MIN(event_sequence) AS first_sequence
                    FROM thread_events
                    WHERE thread_id = ? AND turn_id IS NOT NULL
                    GROUP BY turn_id
                ) event_order ON event_order.turn_id = t.turn_id
                WHERE t.thread_id = ?
                ORDER BY CASE WHEN event_order.first_sequence IS NULL THEN 1 ELSE 0 END,
                    event_order.first_sequence, t.started_at, t.turn_id
                """)) {
            statement.setString(1, threadId.value());
            statement.setString(2, threadId.value());
            try (ResultSet rows = statement.executeQuery()) {
                List<AgentTurn> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(readTurn(rows));
                }
                return List.copyOf(result);
            }
        }
    }

    private List<StoredItem> items(Connection connection, ThreadId threadId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT i.* FROM items i
                JOIN turns t ON t.turn_id = i.turn_id
                LEFT JOIN (
                    SELECT turn_id, MIN(event_sequence) AS first_sequence
                    FROM thread_events
                    WHERE thread_id = ? AND turn_id IS NOT NULL
                    GROUP BY turn_id
                ) event_order ON event_order.turn_id = t.turn_id
                WHERE i.thread_id = ?
                ORDER BY CASE WHEN event_order.first_sequence IS NULL THEN 1 ELSE 0 END,
                    event_order.first_sequence, t.started_at, t.turn_id, i.ordinal
                """)) {
            statement.setString(1, threadId.value());
            statement.setString(2, threadId.value());
            try (ResultSet rows = statement.executeQuery()) {
                List<StoredItem> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(readItem(rows));
                }
                return List.copyOf(result);
            }
        }
    }

    private AgentThread readThread(ResultSet row) throws SQLException {
        String parent = row.getString("parent_thread_id");
        String fork = row.getString("forked_from_turn_id");
        return new AgentThread(
                new ThreadId(row.getString("thread_id")),
                row.getString("workspace_id"),
                parent == null ? null : new ThreadId(parent),
                fork == null ? null : new TurnId(fork),
                row.getString("title"),
                Path.of(row.getString("cwd")),
                ThreadStatus.valueOf(row.getString("status")),
                row.getLong("base_sequence"),
                row.getLong("last_sequence"),
                row.getLong("revision"),
                Instant.ofEpochMilli(row.getLong("created_at")),
                Instant.ofEpochMilli(row.getLong("updated_at")));
    }

    private StoredItem requireItem(Connection connection, ItemId id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM items WHERE item_id = ?")) {
            statement.setString(1, id.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new NoSuchElementException("item not found: " + id);
                }
                return readItem(row);
            }
        }
    }

    private Optional<StoredItem> optionalItem(Connection connection, ItemId id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM items WHERE item_id = ?")) {
            statement.setString(1, id.value());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readItem(row)) : Optional.empty();
            }
        }
    }

    private static void requireThreadRevision(AgentThread current, long expectedRevision, boolean guarded) {
        if (!guarded) {
            return;
        }
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        if (current.revision() != expectedRevision) {
            throw new IllegalStateException("thread revision conflict: " + current.id());
        }
    }

    private Workspace requireWorkspace(Connection connection, WorkspaceId id, boolean lock) throws SQLException {
        String sql = "SELECT * FROM workspaces WHERE workspace_id = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new NoSuchElementException("workspace not found: " + id);
                }
                return readWorkspace(row);
            }
        }
    }

    private static Workspace readWorkspace(ResultSet row) throws SQLException {
        return new Workspace(
                new WorkspaceId(row.getString("workspace_id")),
                row.getString("name"),
                Path.of(row.getString("root_path")),
                row.getLong("revision"),
                row.getBoolean("locked"),
                row.getString("lock_reason"),
                Instant.ofEpochMilli(row.getLong("created_at")),
                Instant.ofEpochMilli(row.getLong("updated_at")));
    }

    private AgentTurn readTurn(ResultSet row) throws SQLException {
        Long completed = row.getObject("completed_at", Long.class);
        return new AgentTurn(
                new TurnId(row.getString("turn_id")),
                new ThreadId(row.getString("thread_id")),
                new AttemptId(row.getString("attempt_id")),
                TurnStatus.valueOf(row.getString("status")),
                json.inputs(row.getString("input_json")),
                json.config(row.getString("config_json")),
                row.getString("error_text"),
                Instant.ofEpochMilli(row.getLong("started_at")),
                completed == null ? null : Instant.ofEpochMilli(completed));
    }

    private StoredItem readItem(ResultSet row) throws SQLException {
        String payload = row.getString("payload_json");
        return new StoredItem(
                new ItemId(row.getString("item_id")),
                new ThreadId(row.getString("thread_id")),
                new TurnId(row.getString("turn_id")),
                row.getLong("ordinal"),
                ItemState.valueOf(row.getString("state")),
                row.getString("kind"),
                payload == null ? null : json.item(payload),
                Instant.ofEpochMilli(row.getLong("created_at")),
                Instant.ofEpochMilli(row.getLong("updated_at")));
    }

    private ThreadEvent readEvent(ResultSet row) throws SQLException {
        String turnId = row.getString("turn_id");
        return new ThreadEvent(
                row.getString("event_id"),
                new ThreadId(row.getString("thread_id")),
                turnId == null ? null : new TurnId(turnId),
                row.getLong("event_sequence"),
                row.getString("event_type"),
                row.getInt("schema_version"),
                row.getString("correlation_id"),
                row.getString("causation_id"),
                json.mapValue(row.getString("payload_json")),
                Instant.ofEpochMilli(row.getLong("timestamp_ms")));
    }

    private Optional<ConversationWindow> activeConversationWindow(
            Connection connection, ThreadId threadId, boolean lock) throws SQLException {
        String sql = """
                SELECT * FROM conversation_windows
                WHERE thread_id = ?
                  AND window_number = (
                      SELECT MAX(window_number) FROM conversation_windows WHERE thread_id = ?)
                """ + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, threadId.value());
            statement.setString(2, threadId.value());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readConversationWindow(row)) : Optional.empty();
            }
        }
    }

    private ConversationWindow readConversationWindow(ResultSet row) throws SQLException {
        String itemId = row.getString("compaction_item_id");
        return new ConversationWindow(
                new ThreadId(row.getString("thread_id")),
                row.getLong("window_number"),
                ConversationWindow.Strategy.valueOf(row.getString("strategy")),
                row.getString("provider"),
                row.getString("model"),
                row.getLong("covered_sequence"),
                row.getInt("schema_version"),
                row.getString("payload"),
                json.stringValues(row.getString("retained_user_messages_json")),
                new ModelUsage(
                        row.getLong("input_tokens"), row.getLong("output_tokens"), row.getLong("reasoning_tokens")),
                itemId == null ? null : new ItemId(itemId),
                Instant.ofEpochMilli(row.getLong("created_at")));
    }

    private void insertConversationWindow(
            Connection connection,
            ThreadId threadId,
            long number,
            ConversationWindow.Replacement replacement,
            ItemId compactionItemId)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO conversation_windows(
                    thread_id, window_number, strategy, provider, model,
                    covered_sequence, schema_version, payload, retained_user_messages_json,
                    input_tokens, output_tokens, reasoning_tokens, compaction_item_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, threadId.value());
            insert.setLong(2, number);
            insert.setString(3, replacement.strategy().name());
            insert.setString(4, replacement.provider());
            insert.setString(5, replacement.model());
            insert.setLong(6, replacement.coveredSequence());
            insert.setInt(7, replacement.schemaVersion());
            insert.setString(8, replacement.payload());
            insert.setString(9, json.stringValues(replacement.retainedUserMessages()));
            insert.setLong(10, replacement.usage().inputTokens());
            insert.setLong(11, replacement.usage().outputTokens());
            insert.setLong(12, replacement.usage().reasoningTokens());
            insert.setString(13, compactionItemId == null ? null : compactionItemId.value());
            insert.setLong(14, clock.millis());
            insert.executeUpdate();
        }
    }

    private void backfillLegacyCompactions() {
        database.transaction(connection -> {
            LinkedHashMap<ThreadId, LegacyCompactionRow> latest = new LinkedHashMap<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT i.item_id, i.thread_id, i.payload_json, i.updated_at, t.config_json
                    FROM items i
                    JOIN turns t ON t.turn_id = i.turn_id
                    WHERE i.kind = 'compaction' AND i.state = 'COMPLETED'
                    ORDER BY i.thread_id, i.updated_at, i.ordinal
                    """);
                    ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    ThreadId threadId = new ThreadId(rows.getString("thread_id"));
                    var legacy = json.legacyCompaction(rows.getString("payload_json"));
                    latest.put(
                            threadId,
                            new LegacyCompactionRow(
                                    new ItemId(rows.getString("item_id")),
                                    legacy.summary(),
                                    legacy.coveredSequence(),
                                    json.config(rows.getString("config_json"))));
                }
            }
            for (Map.Entry<ThreadId, LegacyCompactionRow> entry : latest.entrySet()) {
                if (activeConversationWindow(connection, entry.getKey(), true).isPresent()) {
                    continue;
                }
                LegacyCompactionRow legacy = entry.getValue();
                if (legacy.summary().isBlank()) {
                    throw new IllegalStateException("legacy compaction summary is empty: " + legacy.itemId());
                }
                insertConversationWindow(
                        connection,
                        entry.getKey(),
                        1,
                        new ConversationWindow.Replacement(
                                ConversationWindow.Strategy.SUMMARY,
                                legacy.config().provider(),
                                legacy.config().model(),
                                Math.max(0, legacy.coveredSequence()),
                                1,
                                legacy.summary(),
                                List.of(),
                                ModelUsage.ZERO),
                        legacy.itemId());
            }
            if (!latest.isEmpty()) {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE items
                        SET kind = 'contextCompaction', payload_json = ?, updated_at = ?
                        WHERE kind = 'compaction' AND state = 'COMPLETED'
                        """)) {
                    update.setString(1, json.item(new ThreadItem.ContextCompaction()));
                    update.setLong(2, clock.millis());
                    update.executeUpdate();
                }
            }
            return null;
        });
    }

    private static boolean validTransition(TurnStatus from, TurnStatus to) {
        if (from.terminal()) {
            return false;
        }
        if (to == TurnStatus.INTERRUPTED || to == TurnStatus.FAILED) {
            return true;
        }
        return switch (from) {
            case QUEUED -> to == TurnStatus.IN_PROGRESS;
            case IN_PROGRESS ->
                to == TurnStatus.WAITING_FOR_APPROVAL
                        || to == TurnStatus.WAITING_FOR_INPUT
                        || to == TurnStatus.COMPLETED;
            case WAITING_FOR_APPROVAL, WAITING_FOR_INPUT -> to == TurnStatus.IN_PROGRESS;
            default -> false;
        };
    }

    private static String describe(TurnInput input) {
        if (input instanceof TurnInput.Text text) {
            return text.text();
        }
        if (input instanceof TurnInput.AttachmentRef attachment) {
            return "[attachment " + attachment.mediaType() + "] " + attachment.displayName() + " sha256:"
                    + attachment.sha256();
        }
        return input.toString();
    }

    private <T> T query(SqlWork<T> work) {
        return database.query(work::apply);
    }

    private <T> T transaction(SqlWork<T> work) {
        return database.transaction(work::apply);
    }

    private void requireOpen() {
        database.requireOpen();
    }

    public void close() {
        database.close();
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    private record UploadRow(
            String uploadId,
            String expectedSha256,
            String mediaType,
            String displayName,
            long expectedSize,
            long receivedBytes,
            String temporaryPath,
            String completedSha256,
            long expiresAt) {
        private AttachmentUpload publicView() {
            return new AttachmentUpload(
                    uploadId,
                    expectedSha256,
                    mediaType,
                    displayName,
                    expectedSize,
                    receivedBytes,
                    Instant.ofEpochMilli(expiresAt));
        }

        private UploadRow withReceived(long value) {
            return new UploadRow(
                    uploadId,
                    expectedSha256,
                    mediaType,
                    displayName,
                    expectedSize,
                    value,
                    temporaryPath,
                    completedSha256,
                    expiresAt);
        }
    }

    private record LegacyCompactionRow(
            ItemId itemId, String summary, long coveredSequence, com.javaclaw.core.api.TurnConfig config) {}

    private static final class UncheckedIo extends RuntimeException {
        private final IOException io;

        private UncheckedIo(IOException io) {
            super(io);
            this.io = io;
        }

        private IOException io() {
            return io;
        }
    }
}
