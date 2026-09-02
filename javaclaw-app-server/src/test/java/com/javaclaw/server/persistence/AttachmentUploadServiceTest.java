package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.AttachmentUploadSession;
import com.javaclaw.api.AttachmentUploadState;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.AttachmentRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentUploadServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final AttachmentScope SCOPE = AttachmentScope.global();

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private AttachmentService attachments;

    @BeforeEach
    void initialize() {
        database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        json = new CanonicalJson();
        attachments = new AttachmentService(database, json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void chunksAreOrderedIdempotentAndRecoverAcrossServiceRestart() {
        byte[] first = "chunk-one".getBytes(StandardCharsets.UTF_8);
        byte[] second = "-chunk-two".getBytes(StandardCharsets.UTF_8);
        byte[] content = "chunk-one-chunk-two".getBytes(StandardCharsets.UTF_8);
        AttachmentUploadSession begun = begin("complete", content, digest(content));
        AttachmentUploadSession appended = append("complete-0", begun, 0, first);

        assertEquals(appended, append("complete-0", begun, 0, first));
        assertThrows(PersistenceException.class, () -> append("out-of-order", appended, 2, second));
        AttachmentUploadSession ready = append("complete-1", appended, 1, second);
        AttachmentMetadata metadata = complete("complete-finish", ready);

        assertArrayEquals(content, attachments.read(SCOPE, metadata.digest()).content());
        assertEquals(
                AttachmentUploadState.COMPLETED,
                attachments.readUpload(SCOPE, begun.id()).state());
        assertFalse(Files.exists(staging(begun.id())));

        AttachmentService restarted = new AttachmentService(database, json, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(
                metadata, restarted.readUpload(SCOPE, begun.id()).attachment().orElseThrow());
    }

    @Test
    void digestMismatchFailsClosedAndCleansStaging() {
        byte[] content = "actual".getBytes(StandardCharsets.UTF_8);
        AttachmentUploadSession begun =
                begin("wrong-digest", content, digest("other!".getBytes(StandardCharsets.UTF_8)));
        AttachmentUploadSession ready = append("wrong-digest-chunk", begun, 0, content);

        assertThrows(PersistenceException.class, () -> complete("wrong-digest-complete", ready));

        AttachmentUploadSession failed = attachments.readUpload(SCOPE, begun.id());
        assertEquals(AttachmentUploadState.FAILED, failed.state());
        assertTrue(failed.failureReason().isPresent());
        assertFalse(Files.exists(staging(begun.id())));
    }

    @Test
    void abortIsIdempotentAndRejectsFurtherChunks() {
        byte[] content = "cancel".getBytes(StandardCharsets.UTF_8);
        AttachmentUploadSession begun = begin("abort", content, digest(content));
        AttachmentRpcContracts.AbortPayload payload =
                new AttachmentRpcContracts.AbortPayload(SCOPE, begun.id(), "用户取消");
        CommandIdentity identity = identity("attachment/upload/abort", "abort-command", begun.revision(), payload);

        AttachmentUploadSession aborted = attachments.abortUpload(SCOPE, identity, begun.id(), payload.reason());

        assertEquals(aborted, attachments.abortUpload(SCOPE, identity, begun.id(), payload.reason()));
        assertEquals(AttachmentUploadState.ABORTED, aborted.state());
        assertFalse(Files.exists(staging(begun.id())));
        assertThrows(PersistenceException.class, () -> append("after-abort", aborted, 0, content));
    }

    @Test
    void beginEnforcesGlobalActiveSessionQuota() {
        byte[] content = {1};
        for (int index = 0; index < AttachmentUploadService.MAX_ACTIVE_UPLOADS; index++) {
            begin("quota-" + index, content, digest(content));
        }

        assertThrows(PersistenceException.class, () -> begin("quota-overflow", content, digest(content)));
    }

    @Test
    void beginEnforcesGlobalReservedByteQuotaWithoutAllocatingPayload() {
        for (int index = 0; index < 4; index++) {
            beginDeclared("reserved-" + index, "a".repeat(64), AttachmentRpcContracts.MAX_ATTACHMENT_BYTES);
        }

        assertThrows(PersistenceException.class, () -> beginDeclared("reserved-overflow", "b".repeat(64), 1));
    }

    @Test
    void restartExpiresAbandonedUploadAndRemovesItsStaging() {
        byte[] content = "abandoned".getBytes(StandardCharsets.UTF_8);
        AttachmentUploadSession begun = begin("expire", content, digest(content));

        AttachmentService restarted = new AttachmentService(
                database,
                json,
                Clock.fixed(NOW.plus(AttachmentUploadService.SESSION_TTL).plusSeconds(1), ZoneOffset.UTC));

        assertEquals(
                AttachmentUploadState.EXPIRED,
                restarted.readUpload(SCOPE, begun.id()).state());
        assertFalse(Files.exists(staging(begun.id())));
    }

    @Test
    void uploadScopeIsCheckedForNormalCallsAndIdempotentRecovery() {
        Workspace workspace = createWorkspace("uploads");
        AttachmentScope workspaceScope = AttachmentScope.workspace(workspace.id());
        byte[] content = "scope evidence".getBytes(StandardCharsets.UTF_8);
        AttachmentRpcContracts.BeginPayload beginPayload =
                new AttachmentRpcContracts.BeginPayload(workspaceScope, "text/plain", digest(content), content.length);
        CommandIdentity beginIdentity = identity("attachment/upload/begin", "scoped-begin", 0, beginPayload);
        AttachmentUploadSession begun = attachments.beginUpload(
                workspaceScope,
                beginIdentity,
                beginPayload.mediaType(),
                beginPayload.expectedDigest(),
                beginPayload.expectedSizeBytes());

        assertThrows(
                PersistenceException.class,
                () -> attachments.beginUpload(
                        SCOPE,
                        beginIdentity,
                        beginPayload.mediaType(),
                        beginPayload.expectedDigest(),
                        beginPayload.expectedSizeBytes()));
        assertThrows(PersistenceException.class, () -> attachments.readUpload(SCOPE, begun.id()));

        AttachmentRpcContracts.ChunkPayload chunkPayload =
                new AttachmentRpcContracts.ChunkPayload(workspaceScope, begun.id(), 0, content);
        CommandIdentity chunkIdentity =
                identity("attachment/upload/chunk", "scoped-chunk", begun.revision(), chunkPayload);
        AttachmentUploadSession ready = attachments.appendUploadChunk(
                workspaceScope, chunkIdentity, begun.id(), chunkPayload.chunkIndex(), chunkPayload.content());

        assertThrows(
                PersistenceException.class,
                () -> attachments.appendUploadChunk(SCOPE, chunkIdentity, begun.id(), 0, content));
        assertEquals(ready, attachments.readUpload(workspaceScope, begun.id()));
    }

    @Test
    void completeRollsBackClaimSessionAndIdempotencyWhenMediaClaimConflicts() {
        Workspace workspace = createWorkspace("atomic-complete");
        AttachmentScope scope = AttachmentScope.workspace(workspace.id());
        byte[] content = "same content".getBytes(StandardCharsets.UTF_8);
        AttachmentRpcContracts.BeginPayload existingPayload =
                new AttachmentRpcContracts.BeginPayload(scope, "text/plain", digest(content), content.length);
        attachments.store(
                scope,
                identity("attachment/internal/store", "existing", 0, existingPayload),
                existingPayload.mediaType(),
                content);

        AttachmentUploadSession begun = begin(scope, "conflicting", "application/json", content);
        AttachmentUploadSession ready = append(scope, "conflicting-chunk", begun, 0, content);
        AttachmentRpcContracts.CompletePayload completePayload =
                new AttachmentRpcContracts.CompletePayload(scope, ready.id());
        CommandIdentity completeIdentity =
                identity("attachment/upload/complete", "conflicting-complete", ready.revision(), completePayload);

        assertThrows(PersistenceException.class, () -> attachments.completeUpload(scope, completeIdentity, ready.id()));
        assertEquals(ready, attachments.readUpload(scope, ready.id()));
        assertEquals(
                "text/plain",
                attachments.read(scope, digest(content)).metadata().mediaType());
        assertThrows(PersistenceException.class, () -> attachments.completeUpload(scope, completeIdentity, ready.id()));
    }

    @Test
    void completedAndAbortedCommandsRecoverOnlyForTheExactSessionAndRevision() {
        byte[] content = "recover".getBytes(StandardCharsets.UTF_8);
        AttachmentUploadSession begun = begin("recover-complete", content, digest(content));
        AttachmentUploadSession ready = append("recover-complete-chunk", begun, 0, content);
        AttachmentRpcContracts.CompletePayload completePayload =
                new AttachmentRpcContracts.CompletePayload(SCOPE, ready.id());
        CommandIdentity completeIdentity =
                identity("attachment/upload/complete", "recover-complete", ready.revision(), completePayload);
        AttachmentMetadata completed = attachments.completeUpload(SCOPE, completeIdentity, ready.id());

        assertEquals(completed, attachments.completeUpload(SCOPE, completeIdentity, ready.id()));
        CommandIdentity wrongRevision = new CommandIdentity(
                completeIdentity.method(),
                completeIdentity.idempotencyKey(),
                completeIdentity.expectedRevision() + 1,
                completeIdentity.requestDigest());
        assertThrows(PersistenceException.class, () -> attachments.completeUpload(SCOPE, wrongRevision, ready.id()));

        AttachmentUploadSession abortable = begin("recover-abort", content, digest(content));
        AttachmentRpcContracts.AbortPayload abortPayload =
                new AttachmentRpcContracts.AbortPayload(SCOPE, abortable.id(), "取消");
        CommandIdentity abortIdentity =
                identity("attachment/upload/abort", "recover-abort", abortable.revision(), abortPayload);
        attachments.abortUpload(SCOPE, abortIdentity, abortable.id(), abortPayload.reason());
        CommandIdentity abortWrongRevision = new CommandIdentity(
                abortIdentity.method(),
                abortIdentity.idempotencyKey(),
                abortIdentity.expectedRevision() + 1,
                abortIdentity.requestDigest());
        assertThrows(
                PersistenceException.class,
                () -> attachments.abortUpload(SCOPE, abortWrongRevision, abortable.id(), abortPayload.reason()));
    }

    @Test
    void beginValidationRejectsInvalidRevisionAndConflictingIdempotency() {
        byte[] content = "limits".getBytes(StandardCharsets.UTF_8);
        AttachmentRpcContracts.BeginPayload payload =
                new AttachmentRpcContracts.BeginPayload(SCOPE, "text/plain", digest(content), content.length);
        CommandIdentity beginIdentity = identity("attachment/upload/begin", "validation", 0, payload);
        AttachmentUploadSession begun = attachments.beginUpload(
                SCOPE, beginIdentity, payload.mediaType(), payload.expectedDigest(), payload.expectedSizeBytes());

        assertEquals(
                begun,
                attachments.beginUpload(
                        SCOPE,
                        beginIdentity,
                        payload.mediaType(),
                        payload.expectedDigest(),
                        payload.expectedSizeBytes()));
        assertThrows(
                PersistenceException.class,
                () -> attachments.beginUpload(
                        SCOPE,
                        beginIdentity,
                        "application/json",
                        payload.expectedDigest(),
                        payload.expectedSizeBytes()));
        CommandIdentity conflictingDigest = new CommandIdentity(
                beginIdentity.method(),
                beginIdentity.idempotencyKey(),
                0,
                json.encode(Map.of("payload", "different")).sha256());
        assertThrows(
                PersistenceException.class,
                () -> attachments.beginUpload(
                        SCOPE,
                        conflictingDigest,
                        payload.mediaType(),
                        payload.expectedDigest(),
                        payload.expectedSizeBytes()));
        assertThrows(
                PersistenceException.class,
                () -> attachments.beginUpload(
                        SCOPE,
                        identity("attachment/upload/begin", "wrong-revision", 1, payload),
                        payload.mediaType(),
                        payload.expectedDigest(),
                        payload.expectedSizeBytes()));
    }

    @Test
    void beginRecovery逐字段拒绝DigestSize与Method漂移() {
        byte[] content = "begin-recovery".getBytes(StandardCharsets.UTF_8);
        AttachmentRpcContracts.BeginPayload payload =
                new AttachmentRpcContracts.BeginPayload(SCOPE, "text/plain", digest(content), content.length);
        CommandIdentity identity = identity("attachment/upload/begin", "begin-recovery", 0, payload);
        attachments.beginUpload(
                SCOPE, identity, payload.mediaType(), payload.expectedDigest(), payload.expectedSizeBytes());

        assertThrows(
                PersistenceException.class,
                () -> attachments.beginUpload(
                        SCOPE, identity, payload.mediaType(), "f".repeat(64), payload.expectedSizeBytes()));
        assertThrows(
                PersistenceException.class,
                () -> attachments.beginUpload(
                        SCOPE,
                        identity,
                        payload.mediaType(),
                        payload.expectedDigest(),
                        payload.expectedSizeBytes() + 1));
        CommandIdentity changedMethod = new CommandIdentity(
                "attachment/upload/other",
                identity.idempotencyKey(),
                identity.expectedRevision(),
                identity.requestDigest());
        assertThrows(
                PersistenceException.class,
                () -> attachments.beginUpload(
                        SCOPE,
                        changedMethod,
                        payload.mediaType(),
                        payload.expectedDigest(),
                        payload.expectedSizeBytes()));
    }

    @Test
    void chunkValidationRejectsStaleRevisionInvalidSizeAndConflictingReplay() {
        byte[] content = "limits".getBytes(StandardCharsets.UTF_8);
        AttachmentUploadSession begun = begin("validation", content, digest(content));

        AttachmentRpcContracts.ChunkPayload chunkPayload =
                new AttachmentRpcContracts.ChunkPayload(SCOPE, begun.id(), 0, content);
        assertThrows(
                PersistenceException.class,
                () -> attachments.appendUploadChunk(
                        SCOPE,
                        identity("attachment/upload/chunk", "stale", begun.revision() + 1, chunkPayload),
                        begun.id(),
                        0,
                        content));
        CommandIdentity chunkIdentity =
                identity("attachment/upload/chunk", "validation-chunk", begun.revision(), chunkPayload);
        attachments.appendUploadChunk(SCOPE, chunkIdentity, begun.id(), 0, content);
        assertThrows(
                PersistenceException.class,
                () -> attachments.appendUploadChunk(SCOPE, chunkIdentity, begun.id(), 1, content));
        CommandIdentity recoveredWrongRevision = new CommandIdentity(
                chunkIdentity.method(),
                chunkIdentity.idempotencyKey(),
                chunkIdentity.expectedRevision() + 1,
                chunkIdentity.requestDigest());
        assertThrows(
                PersistenceException.class,
                () -> attachments.appendUploadChunk(SCOPE, recoveredWrongRevision, begun.id(), 0, content));

        AttachmentUploadSession incomplete = begin("incomplete", content, digest(content));
        assertThrows(PersistenceException.class, () -> complete("incomplete", incomplete));
        AttachmentUploadSession empty = begin("empty", content, digest(content));
        assertThrows(IllegalArgumentException.class, () -> append("empty", empty, 0, new byte[0]));

        byte[] oneByte = {1};
        AttachmentUploadSession tooSmall = begin("too-small", oneByte, digest(oneByte));
        assertThrows(
                PersistenceException.class, () -> append("too-large-for-declaration", tooSmall, 0, new byte[] {1, 2}));
        byte[] oversized = new byte[AttachmentRpcContracts.MAX_ATTACHMENT_CHUNK_BYTES + 1];
        AttachmentUploadSession oversizedChunk = begin("oversized-chunk", oversized, digest(oversized));
        assertThrows(IllegalArgumentException.class, () -> append("oversized-chunk", oversizedChunk, 0, oversized));
    }

    @Test
    void chunkRecovery拒绝其他Session零Revision与平台Chunk上限() throws Exception {
        byte[] content = {1};
        AttachmentUploadSession first = begin("chunk-recovery-first", content, digest(content));
        AttachmentRpcContracts.ChunkPayload payload =
                new AttachmentRpcContracts.ChunkPayload(SCOPE, first.id(), 0, content);
        CommandIdentity identity = identity("attachment/upload/chunk", "chunk-recovery", first.revision(), payload);
        attachments.appendUploadChunk(SCOPE, identity, first.id(), 0, content);
        AttachmentUploadSession second = begin("chunk-recovery-second", content, digest(content));

        assertThrows(
                PersistenceException.class,
                () -> attachments.appendUploadChunk(SCOPE, identity, second.id(), 0, content));
        assertThrows(
                PersistenceException.class,
                () -> attachments.appendUploadChunk(
                        SCOPE,
                        new CommandIdentity("attachment/upload/chunk", "zero-revision", 0, "a".repeat(64)),
                        second.id(),
                        0,
                        content));

        try (var connection = database.open();
                var statement = connection.prepareStatement(
                        "UPDATE CORE.ATTACHMENT_UPLOAD SET NEXT_CHUNK_INDEX = ? WHERE ID = ?")) {
            statement.setInt(1, AttachmentRpcContracts.MAX_ATTACHMENT_CHUNKS);
            statement.setString(2, second.id());
            assertEquals(1, statement.executeUpdate());
        }
        AttachmentUploadSession atLimit = attachments.readUpload(SCOPE, second.id());
        AttachmentUploadService uploadStateMachine =
                new AttachmentUploadService(database, attachments, json, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(
                PersistenceException.class,
                () -> uploadStateMachine.append(
                        new CommandIdentity(
                                "attachment/upload/chunk", "chunk-limit", atLimit.revision(), "b".repeat(64)),
                        SCOPE,
                        atLimit.id(),
                        AttachmentRpcContracts.MAX_ATTACHMENT_CHUNKS,
                        content));
    }

    @Test
    void alteredStagingChunkFailsSessionAndAbortReasonsAreBounded() throws Exception {
        byte[] expected = "ab".getBytes(StandardCharsets.UTF_8);
        AttachmentUploadSession begun = begin("tamper", expected, digest(expected));
        AttachmentUploadSession first = append("tamper-first", begun, 0, new byte[] {'a'});
        Files.write(staging(first.id()).resolve("0001.chunk"), new byte[] {'x'});

        assertThrows(
                AttachmentUploadStore.StagingCorruptionException.class,
                () -> append("tamper-second", first, 1, new byte[] {'b'}));
        assertEquals(
                AttachmentUploadState.FAILED,
                attachments.readUpload(SCOPE, first.id()).state());
        assertFalse(Files.exists(staging(first.id())));

        AttachmentUploadSession abortable = begin("invalid-abort", expected, digest(expected));
        AttachmentRpcContracts.AbortPayload payload =
                new AttachmentRpcContracts.AbortPayload(SCOPE, abortable.id(), "valid");
        CommandIdentity identity = identity("attachment/upload/abort", "invalid-abort", abortable.revision(), payload);
        assertThrows(
                IllegalArgumentException.class, () -> attachments.abortUpload(SCOPE, identity, abortable.id(), "  "));
        assertThrows(
                IllegalArgumentException.class,
                () -> attachments.abortUpload(SCOPE, identity, abortable.id(), "x".repeat(501)));
    }

    private AttachmentUploadSession begin(String key, byte[] content, String digest) {
        return beginDeclared(key, digest, content.length);
    }

    private AttachmentUploadSession begin(AttachmentScope scope, String key, String mediaType, byte[] content) {
        AttachmentRpcContracts.BeginPayload payload =
                new AttachmentRpcContracts.BeginPayload(scope, mediaType, digest(content), content.length);
        return attachments.beginUpload(
                scope,
                identity("attachment/upload/begin", key + "-begin", 0, payload),
                payload.mediaType(),
                payload.expectedDigest(),
                payload.expectedSizeBytes());
    }

    private AttachmentUploadSession beginDeclared(String key, String digest, long sizeBytes) {
        AttachmentRpcContracts.BeginPayload payload =
                new AttachmentRpcContracts.BeginPayload(SCOPE, "application/octet-stream", digest, sizeBytes);
        return attachments.beginUpload(
                SCOPE,
                identity("attachment/upload/begin", key + "-begin", 0, payload),
                payload.mediaType(),
                payload.expectedDigest(),
                payload.expectedSizeBytes());
    }

    private AttachmentUploadSession append(String key, AttachmentUploadSession current, int index, byte[] content) {
        return append(SCOPE, key, current, index, content);
    }

    private AttachmentUploadSession append(
            AttachmentScope scope, String key, AttachmentUploadSession current, int index, byte[] content) {
        AttachmentRpcContracts.ChunkPayload payload =
                new AttachmentRpcContracts.ChunkPayload(scope, current.id(), index, content);
        return attachments.appendUploadChunk(
                scope,
                identity("attachment/upload/chunk", key, current.revision(), payload),
                payload.uploadId(),
                payload.chunkIndex(),
                payload.content());
    }

    private AttachmentMetadata complete(String key, AttachmentUploadSession current) {
        AttachmentRpcContracts.CompletePayload payload =
                new AttachmentRpcContracts.CompletePayload(SCOPE, current.id());
        return attachments.completeUpload(
                SCOPE, identity("attachment/upload/complete", key, current.revision(), payload), payload.uploadId());
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        String requestDigest =
                json.encode(new IdentityInput(revision, json.encode(payload))).sha256();
        return new CommandIdentity(method, key, revision, requestDigest);
    }

    private Path staging(String uploadId) {
        return database.dataRoot().resolve("staging/attachment-uploads").resolve(uploadId);
    }

    private Workspace createWorkspace(String name) {
        CoreCommandService core = new CoreCommandService(database, json, Clock.fixed(NOW, ZoneOffset.UTC));
        CoreRpcContracts.WorkspaceCreatePayload payload =
                new CoreRpcContracts.WorkspaceCreatePayload(name, temporaryDirectory.resolve(name));
        return core.createWorkspace(
                identity("workspace/create", "workspace-" + name, 0, payload), payload.name(), payload.root());
    }

    private static String digest(byte[] content) {
        return ManagedWorktreePolicy.sha256(content);
    }

    private record IdentityInput(long expectedRevision, com.javaclaw.api.CanonicalPayload payload) {}
}
