package com.javaclaw.server.persistence;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ApprovalResolution;
import com.javaclaw.core.api.AttachmentMetadata;
import com.javaclaw.core.api.ItemState;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ThreadSnapshot;
import com.javaclaw.core.api.ThreadStatus;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.core.api.TurnStatus;
import com.javaclaw.core.api.UserInputResolution;
import com.javaclaw.core.api.Workspace;
import com.javaclaw.sandbox.api.SandboxPolicy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2PersistenceTest {
    @TempDir
    Path temporary;

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC);
    private final List<H2Persistence> openStores = new ArrayList<>();

    @AfterEach
    void closeStores() {
        for (int index = openStores.size() - 1; index >= 0; index--) {
            openStores.get(index).close();
        }
    }

    @Test
    void commitsThreadTurnItemsAndEventsAsOneTranscript() {
        H2Persistence store = store(temporary.resolve("data-v4"));
        AgentThread thread = thread(store, "test");
        AgentTurn queued = store.journal().startTurn(command(thread.id(), "same-key"));
        AgentTurn idempotent = store.journal().startTurn(command(thread.id(), "same-key"));
        assertEquals(queued.id(), idempotent.id());
        TurnStartCommand conflicting = new TurnStartCommand(
                thread.id(), List.of(new TurnInput.Text("different")), queued.config(), "same-key");
        assertThrows(IllegalStateException.class, () -> store.journal().startTurn(conflicting));
        assertEquals(
                TurnStatus.QUEUED,
                store.journal().executionAttempt(queued.attemptId()).status());

        AgentTurn running =
                store.journal().transitionTurn(queued.id(), TurnStatus.QUEUED, TurnStatus.IN_PROGRESS, null);
        assertEquals(TurnStatus.IN_PROGRESS, running.status());
        assertEquals(
                TurnStatus.IN_PROGRESS,
                store.journal().findTurn(running.id()).orElseThrow().status());
        store.journal()
                .appendItem(thread.id(), running.id(), new ThreadItem.AgentMessage("answer"), ItemState.COMPLETED);
        store.journal().recordUsage(thread.id(), running.id(), new ModelUsage(12, 5, 2));
        store.journal().recordUsage(thread.id(), running.id(), new ModelUsage(8, 3, 0));
        assertEquals(
                TurnStatus.IN_PROGRESS,
                store.journal().findTurn(running.id()).orElseThrow().status());
        AgentTurn completed =
                store.journal().transitionTurn(running.id(), TurnStatus.IN_PROGRESS, TurnStatus.COMPLETED, null);

        ThreadSnapshot snapshot = store.journal().snapshot(thread.id());
        assertEquals(TurnStatus.COMPLETED, completed.status());
        H2ThreadJournal.ExecutionAttemptRow attempt = store.journal().executionAttempt(completed.attemptId());
        assertEquals(TurnStatus.COMPLETED, attempt.status());
        assertEquals("test-provider", attempt.provider());
        assertEquals("test-model", attempt.model());
        assertEquals(20, attempt.inputTokens());
        assertEquals(8, attempt.outputTokens());
        assertEquals(2, attempt.reasoningTokens());
        assertNotNull(attempt.completedAt());
        assertEquals(1, snapshot.turns().size());
        assertEquals(
                List.of("userMessage", "agentMessage"),
                snapshot.items().stream().map(item -> item.item().kind()).toList());
        List<ThreadEvent> events = store.journal().eventsAfter(thread.id(), 0, 100);
        assertTrue(events.size() >= 6);
        assertEquals(
                2,
                events.stream()
                        .filter(event -> event.type().equals("usage/updated"))
                        .count());
        assertEquals(events.size(), events.getLast().sequence());
        for (int index = 0; index < events.size(); index++) {
            assertEquals(index + 1L, events.get(index).sequence());
        }
        List<ThreadEvent> pending = store.outbox().unpublishedEvents(100);
        assertEquals(events.size(), pending.size());
        assertTrue(store.outbox()
                .markEventPublished(
                        pending.getFirst().threadId(), pending.getFirst().sequence()));
        assertFalse(store.outbox()
                .markEventPublished(
                        pending.getFirst().threadId(), pending.getFirst().sequence()));
        assertEquals(pending.size() - 1, store.outbox().unpublishedEvents(100).size());
    }

    @Test
    void recoversNonTerminalTurnsAsInterrupted() {
        Path root = temporary.resolve("recovery");
        H2Persistence first = store(root);
        AgentThread thread = thread(first, "test");
        AgentTurn queued = first.journal().startTurn(command(thread.id(), null));

        H2Persistence recovered = store(root);
        assertEquals(1, recovered.journal().recoverInterruptedTurns());
        assertEquals(
                TurnStatus.INTERRUPTED,
                recovered.journal().findTurn(queued.id()).orElseThrow().status());
        assertEquals(
                TurnStatus.INTERRUPTED,
                recovered.journal().executionAttempt(queued.attemptId()).status());
    }

    @Test
    void executionProjectionAndEffectUniquenessShareTheItemEventOutboxTransaction() {
        H2Persistence store = store(temporary.resolve("execution-records"));
        AgentThread thread = thread(store, "execution");
        AgentTurn turn = store.journal().startTurn(command(thread.id(), "execution-turn"));
        String hash = "b".repeat(64);
        var checkpoint = new ThreadItem.Checkpoint(
                "execution-test",
                hash,
                "step-one",
                "RUNNING",
                1,
                Map.of("result", "已确认进展"),
                List.of("previous"),
                1,
                20,
                30,
                "继续验收");
        var stored = store.journal().appendItem(thread.id(), turn.id(), checkpoint, ItemState.COMPLETED);
        assertEquals(
                checkpoint, store.journal().findItem(stored.id()).orElseThrow().item());
        assertEquals(Long.valueOf(1L), store.database().<Long>query(connection -> {
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*) FROM execution_checkpoints")) {
                rows.next();
                return rows.getLong(1);
            }
        }));
        var pending = new ThreadItem.EffectReceipt(hash, "send", "PENDING", null, "等待真实结果");
        store.journal().appendItem(thread.id(), turn.id(), pending, ItemState.COMPLETED);
        var before = store.journal().snapshot(thread.id());
        int outboxBefore = store.outbox().unpublishedEvents(100).size();
        assertThrows(
                IllegalStateException.class,
                () -> store.journal().appendItem(thread.id(), turn.id(), pending, ItemState.COMPLETED));
        assertEquals(before.items(), store.journal().snapshot(thread.id()).items());
        assertEquals(
                before.thread().lastSequence(),
                store.journal().snapshot(thread.id()).thread().lastSequence());
        assertEquals(outboxBefore, store.outbox().unpublishedEvents(100).size());
        var result = new com.javaclaw.core.api.ToolExecutionResult(
                new ThreadItem.DynamicToolCall("send", Map.of("status", "submitted")), "submitted");
        var confirmed = new ThreadItem.EffectReceipt(hash, "send", "CONFIRMED", result, "已提交，但未验证送达");
        var finalItem = store.journal().appendItem(thread.id(), turn.id(), confirmed, ItemState.COMPLETED);
        assertEquals(
                confirmed,
                store.journal().findItem(finalItem.id()).orElseThrow().item());
    }

    @Test
    void forksACompletedTranscriptIntoIndependentIds() {
        H2Persistence store = store(temporary.resolve("fork"));
        AgentThread source = thread(store, "source");
        AgentTurn turn = store.journal().startTurn(command(source.id(), null));
        store.journal().transitionTurn(turn.id(), TurnStatus.QUEUED, TurnStatus.IN_PROGRESS, null);
        store.journal().appendItem(source.id(), turn.id(), new ThreadItem.AgentMessage("answer"), ItemState.COMPLETED);
        store.journal().transitionTurn(turn.id(), TurnStatus.IN_PROGRESS, TurnStatus.COMPLETED, null);

        AgentThread fork = store.journal().forkThread(source.id(), turn.id(), "fork");
        ThreadSnapshot snapshot = store.journal().snapshot(fork.id());

        assertEquals(source.id(), fork.parentThreadId());
        assertEquals(turn.id(), fork.forkedFromTurnId());
        long expectedBoundary = store.journal().eventsAfter(source.id(), 0, 100).stream()
                .filter(event -> turn.id().equals(event.turnId()))
                .mapToLong(ThreadEvent::sequence)
                .max()
                .orElseThrow();
        assertEquals(expectedBoundary, fork.baseSequence());
        assertEquals(1, snapshot.turns().size());
        assertNotEquals(turn.id(), snapshot.turns().getFirst().id());
        assertEquals(
                List.of("userMessage", "agentMessage"),
                snapshot.items().stream().map(item -> item.item().kind()).toList());
    }

    @Test
    void forksBeforeTargetTurnWithoutChangingSourceHistory() {
        H2Persistence store = store(temporary.resolve("retry-fork"));
        AgentThread source = thread(store, "source");
        AgentTurn first = completedTurn(store, source, "first", "one");
        AgentTurn second = completedTurn(store, source, "second", "two");
        completedTurn(store, source, "third", "three");
        ThreadSnapshot sourceBefore = store.journal().snapshot(source.id());

        AgentThread fork = store.journal().forkBeforeTurn(source.id(), second.id(), "retry", null, "retry-fork-key");
        ThreadSnapshot forked = store.journal().snapshot(fork.id());

        assertEquals(source.id(), fork.parentThreadId());
        assertEquals(second.id(), fork.forkedFromTurnId());
        assertEquals(1, forked.turns().size());
        assertNotEquals(first.id(), forked.turns().getFirst().id());
        assertEquals(
                List.of("one", "answer-one"),
                forked.items().stream()
                        .map(item -> item.item() instanceof ThreadItem.UserMessage message
                                ? message.text()
                                : ((ThreadItem.AgentMessage) item.item()).text())
                        .toList());
        assertEquals(sourceBefore, store.journal().snapshot(source.id()));
        assertEquals(
                fork.id(),
                store.journal()
                        .forkBeforeTurn(source.id(), second.id(), "retry", null, "retry-fork-key")
                        .id());

        AgentThread firstRetry =
                store.journal().forkBeforeTurn(source.id(), first.id(), "retry-first", null, "retry-first-key");
        assertTrue(store.journal().snapshot(firstRetry.id()).turns().isEmpty());
        assertTrue(store.journal().snapshot(firstRetry.id()).items().isEmpty());
    }

    @Test
    void retryBranchRollbackClearsInternalIdempotencyForTheSameRequestRetry() {
        H2Persistence store = store(temporary.resolve("retry-rollback"));
        AgentThread source = thread(store, "source");
        AgentTurn target = completedTurn(store, source, "target", "target");
        String branchKey = "retry-request:branch";
        String turnKey = "retry-request:turn";

        AgentThread first = store.journal().forkBeforeTurn(source.id(), target.id(), "retry", null, branchKey);
        AgentTurn queued = store.journal().startTurn(command(first.id(), turnKey));
        store.journal().transitionTurn(queued.id(), TurnStatus.QUEUED, TurnStatus.FAILED, "scheduler rejected");
        store.journal().rollbackRetryBranch(first.id(), branchKey, turnKey);

        assertTrue(store.journal().findThread(first.id()).isEmpty());
        AgentThread retried = store.journal().forkBeforeTurn(source.id(), target.id(), "retry", null, branchKey);
        AgentTurn retriedTurn = store.journal().startTurn(command(retried.id(), turnKey));
        assertNotEquals(first.id(), retried.id());
        assertEquals(retried.id(), retriedTurn.threadId());
    }

    @Test
    void guardsThreadMutationsWithRevisionAndTransactionalIdempotency() {
        H2Persistence store = store(temporary.resolve("thread-mutations"));
        Workspace workspace = store.workspaces().create("workspace", temporary, "workspace-create-key");
        AgentThread created = store.journal()
                .createThread(workspace.id().value(), workspace.root(), "original", null, null, "thread-start-key");
        AgentThread replayedCreate = store.journal()
                .createThread(workspace.id().value(), workspace.root(), "original", null, null, "thread-start-key");

        assertEquals(created.id(), replayedCreate.id());
        assertEquals(1, created.revision());
        assertThrows(
                IllegalStateException.class,
                () -> store.journal()
                        .createThread(
                                workspace.id().value(), workspace.root(), "different", null, null, "thread-start-key"));

        AgentThread updated =
                store.journal().updateThreadTitle(created.id(), "renamed", created.revision(), "thread-update-key");
        AgentThread replayedUpdate =
                store.journal().updateThreadTitle(created.id(), "renamed", created.revision(), "thread-update-key");
        assertEquals(updated.id(), replayedUpdate.id());
        assertEquals(2, updated.revision());
        assertThrows(
                IllegalStateException.class,
                () -> store.journal()
                        .updateThreadTitle(created.id(), "stale", created.revision(), "another-update-key"));

        AgentThread archived = store.journal()
                .setThreadStatus(created.id(), ThreadStatus.ARCHIVED, updated.revision(), "thread-archive-key");
        assertEquals(3, archived.revision());
        assertEquals(ThreadStatus.ARCHIVED, archived.status());
        assertEquals(
                archived.id(),
                store.journal()
                        .setThreadStatus(created.id(), ThreadStatus.ARCHIVED, updated.revision(), "thread-archive-key")
                        .id());
    }

    @Test
    void rejectsWorkspaceIdempotencyKeyReuseWithDifferentInput() {
        H2Persistence store = store(temporary.resolve("workspace-idempotency"));
        Workspace workspace = store.workspaces().create("workspace", temporary, "workspace-key");

        assertEquals(
                workspace.id(),
                store.workspaces()
                        .create("workspace", temporary, "workspace-key")
                        .id());
        assertThrows(
                IllegalStateException.class, () -> store.workspaces().create("different", temporary, "workspace-key"));
        Workspace updated =
                store.workspaces().update(workspace.id(), "renamed", workspace.revision(), "workspace-update-key");
        assertEquals(
                updated.id(),
                store.workspaces()
                        .update(workspace.id(), "renamed", workspace.revision(), "workspace-update-key")
                        .id());
        assertThrows(
                IllegalStateException.class,
                () -> store.workspaces()
                        .update(workspace.id(), "stale", workspace.revision(), "another-workspace-key"));
    }

    @Test
    void rejectsOldOrUnmarkedDataRoots() throws Exception {
        Path old = temporary.resolve("old");
        Files.createDirectories(old);
        Files.writeString(old.resolve(".javaclaw-format"), "3\n");
        var oldView = Files.getFileAttributeView(old, java.nio.file.attribute.PosixFileAttributeView.class);
        var originalPermissions =
                oldView == null ? null : java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-x---");
        if (oldView != null) {
            oldView.setPermissions(originalPermissions);
        }
        var oldTimestamp = Files.getLastModifiedTime(old);
        assertThrows(IllegalStateException.class, () -> new H2Persistence(old, clock));
        assertEquals("3\n", Files.readString(old.resolve(".javaclaw-format")));
        assertEquals(oldTimestamp, Files.getLastModifiedTime(old));
        if (oldView != null) {
            assertEquals(originalPermissions, oldView.readAttributes().permissions());
        }

        Path unmarked = temporary.resolve("unmarked");
        Files.createDirectories(unmarked);
        Files.writeString(unmarked.resolve("javaclaw.mv.db"), "old");
        var unmarkedView = Files.getFileAttributeView(unmarked, java.nio.file.attribute.PosixFileAttributeView.class);
        if (unmarkedView != null) {
            unmarkedView.setPermissions(originalPermissions);
        }
        var unmarkedTimestamp = Files.getLastModifiedTime(unmarked);
        assertThrows(IllegalStateException.class, () -> new H2Persistence(unmarked, clock));
        assertEquals("old", Files.readString(unmarked.resolve("javaclaw.mv.db")));
        assertFalse(Files.exists(unmarked.resolve(".javaclaw-format")));
        assertEquals(unmarkedTimestamp, Files.getLastModifiedTime(unmarked));
        if (unmarkedView != null) {
            assertEquals(originalPermissions, unmarkedView.readAttributes().permissions());
        }
    }

    @Test
    void canonicalizesASymbolicLinkDataRootBeforeCreatingTheFormatMarker() throws Exception {
        Path target = Files.createDirectories(temporary.resolve("real-data-root"));
        Path link = temporary.resolve("linked-data-root");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("host does not permit symbolic links: " + unsupported.getMessage());
        }

        H2Persistence linkedStore = store(link);
        assertEquals(target.toRealPath(), linkedStore.dataRoot());
        assertEquals("4", Files.readString(target.resolve(".javaclaw-format")).strip());
    }

    @Test
    void posixDataRootAndFormatMarkerAreOwnerOnly() throws Exception {
        Path root = temporary.resolve("owner-only-data-v4");
        store(root);
        var view = Files.getFileAttributeView(root, java.nio.file.attribute.PosixFileAttributeView.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(view != null, "filesystem does not expose POSIX permissions");

        assertEquals(
                Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(root));
        assertEquals(
                Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(root.resolve(".javaclaw-format")));
    }

    @Test
    void persistsApprovalRequestResolutionAndEventsAtomically() {
        H2Persistence store = store(temporary.resolve("approvals"));
        AgentThread thread = thread(store, "test");
        AgentTurn turn = store.journal().startTurn(command(thread.id(), null));
        store.journal().transitionTurn(turn.id(), TurnStatus.QUEUED, TurnStatus.IN_PROGRESS, null);
        store.journal()
                .appendItem(
                        thread.id(),
                        turn.id(),
                        new ThreadItem.ApprovalRequest("approval_test", "run command", "HIGH"),
                        ItemState.COMPLETED);

        ApprovalResolution resolution =
                store.interactions().resolveApproval("approval_test", true).orElseThrow();

        assertTrue(resolution.approved());
        assertTrue(store.interactions().resolveApproval("approval_test", true).isEmpty());
        assertEquals(
                List.of("approval/requested", "approval/resolved"),
                store.journal().eventsAfter(thread.id(), 0, 100).stream()
                        .map(ThreadEvent::type)
                        .filter(type -> type.startsWith("approval/"))
                        .toList());
    }

    @Test
    void persistsUserInputPauseAndResolutionAtomically() {
        H2Persistence store = store(temporary.resolve("user-input"));
        AgentThread thread = thread(store, "test");
        AgentTurn turn = store.journal().startTurn(command(thread.id(), null));
        store.journal().transitionTurn(turn.id(), TurnStatus.QUEUED, TurnStatus.IN_PROGRESS, null);
        store.journal()
                .appendItem(
                        thread.id(),
                        turn.id(),
                        new ThreadItem.UserInputRequest("input_test", "Choose", List.of("a", "b")),
                        ItemState.COMPLETED);

        UserInputResolution resolution =
                store.interactions().resolveUserInput("input_test", "a", false).orElseThrow();

        assertEquals("a", resolution.value());
        assertFalse(resolution.cancelled());
        assertTrue(
                store.interactions().resolveUserInput("input_test", "b", false).isEmpty());
        assertEquals(
                List.of("userInput/requested", "userInput/resolved"),
                store.journal().eventsAfter(thread.id(), 0, 100).stream()
                        .map(ThreadEvent::type)
                        .filter(type -> type.startsWith("userInput/"))
                        .toList());
    }

    @Test
    void contentAddressesDeduplicatesAndReferenceCountsAttachments() throws Exception {
        H2Persistence store = store(temporary.resolve("attachment-data-v4"));
        byte[] content = "same attachment".getBytes(StandardCharsets.UTF_8);

        AttachmentMetadata first = store.attachments().put(new ByteArrayInputStream(content), "text/plain");
        AttachmentMetadata second = store.attachments().put(new ByteArrayInputStream(content), "text/plain");

        assertEquals(first.sha256(), second.sha256());
        assertEquals(2, second.referenceCount());
        try (var input = store.attachments().openAttachment(first.sha256())) {
            assertArrayEquals(content, input.readAllBytes());
        }
        assertFalse(store.attachments().releaseAttachment(first.sha256()));
        assertEquals(
                1,
                store.attachments().findAttachment(first.sha256()).orElseThrow().referenceCount());
        assertTrue(store.attachments().releaseAttachment(first.sha256()));
        assertTrue(store.attachments().findAttachment(first.sha256()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.attachments().findAttachment("../../credentials"));
    }

    @Test
    void persistsProcessConfigurationAndAppliesRemovalPatchAtomically() {
        Path root = temporary.resolve("configuration-data-v4");
        H2Persistence first = store(root);
        assertEquals(
                Map.of("model", "\"gpt-test\"", "limits", "{\"turns\":8}"),
                first.configuration().update(Map.of("model", "\"gpt-test\"", "limits", "{\"turns\":8}"), Set.of()));
        assertEquals(Map.of("model", "\"gpt-test\""), first.configuration().update(Map.of(), Set.of("limits")));
        first.close();

        H2Persistence reopened = store(root);
        assertEquals(Map.of("model", "\"gpt-test\""), reopened.configuration().read());
        assertThrows(
                IllegalArgumentException.class,
                () -> reopened.configuration().update(Map.of("x".repeat(129), "true"), Set.of()));
    }

    private TurnStartCommand command(ThreadId threadId, String idempotencyKey) {
        Path cwd = temporary.toAbsolutePath().normalize();
        TurnConfig config = new TurnConfig(
                "test-model",
                "test-provider",
                "medium",
                cwd,
                SandboxPolicy.readOnly(Set.of(cwd), Set.of(cwd.resolve(".git"))),
                ApprovalPolicy.ON_RISK,
                Set.of(),
                Map.of());
        return new TurnStartCommand(threadId, List.of(new TurnInput.Text("hello")), config, idempotencyKey);
    }

    private AgentTurn completedTurn(H2Persistence store, AgentThread thread, String key, String input) {
        TurnStartCommand base = command(thread.id(), key);
        AgentTurn queued = store.journal()
                .startTurn(new TurnStartCommand(thread.id(), List.of(new TurnInput.Text(input)), base.config(), key));
        store.journal().transitionTurn(queued.id(), TurnStatus.QUEUED, TurnStatus.IN_PROGRESS, null);
        store.journal()
                .appendItem(
                        thread.id(), queued.id(), new ThreadItem.AgentMessage("answer-" + input), ItemState.COMPLETED);
        return store.journal().transitionTurn(queued.id(), TurnStatus.IN_PROGRESS, TurnStatus.COMPLETED, null);
    }

    private AgentThread thread(H2Persistence store, String title) {
        Workspace workspace = store.workspaces().list().stream()
                .findFirst()
                .orElseGet(() -> store.workspaces().create("workspace", temporary, "test-workspace"));
        return store.journal().createThread(workspace.id().value(), workspace.root(), title, null, null);
    }

    private H2Persistence store(Path root) {
        H2Persistence store = new H2Persistence(root, clock);
        openStores.add(store);
        return store;
    }
}
