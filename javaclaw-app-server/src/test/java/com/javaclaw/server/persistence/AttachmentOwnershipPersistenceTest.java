package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.AttachmentRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.extension.CoreAttachmentEvidencePort;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttachmentOwnershipPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private Clock clock;
    private AttachmentService attachments;
    private CoreCommandService core;

    @BeforeEach
    void initialize() {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        attachments = new AttachmentService(database, json, clock);
        core = new CoreCommandService(database, json, clock);
    }

    @Test
    void sameDigestCanBeClaimedConcurrentlyByDifferentScopesAndMediaTypes() throws Exception {
        Workspace first = createWorkspace("first");
        Workspace second = createWorkspace("second");
        byte[] content = "same bytes".getBytes(StandardCharsets.UTF_8);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AttachmentMetadata> firstResult = executor.submit(() -> storeAfterSignal(
                    ready, start, AttachmentScope.workspace(first.id()), "text/plain", "first", content));
            Future<AttachmentMetadata> secondResult = executor.submit(() -> storeAfterSignal(
                    ready, start, AttachmentScope.workspace(second.id()), "application/json", "second", content));
            ready.await();
            start.countDown();

            assertEquals("text/plain", firstResult.get().mediaType());
            assertEquals("application/json", secondResult.get().mediaType());
        }

        String digest = ManagedWorktreePolicy.sha256(content);
        assertEquals(
                "text/plain",
                attachments
                        .read(AttachmentScope.workspace(first.id()), digest)
                        .metadata()
                        .mediaType());
        assertEquals(
                "application/json",
                attachments
                        .read(AttachmentScope.workspace(second.id()), digest)
                        .metadata()
                        .mediaType());
        assertThrows(PersistenceException.class, () -> attachments.read(AttachmentScope.global(), digest));
    }

    @Test
    void storeRecoveryRejectsChangedScopeMediaTypeAndContent() {
        Workspace first = createWorkspace("first");
        Workspace second = createWorkspace("second");
        byte[] content = "owned content".getBytes(StandardCharsets.UTF_8);
        AttachmentScope firstScope = AttachmentScope.workspace(first.id());
        AttachmentScope secondScope = AttachmentScope.workspace(second.id());
        CommandIdentity identity = storeIdentity("stable", firstScope, "text/plain", content);

        AttachmentMetadata stored = attachments.store(firstScope, identity, "text/plain", content);
        AttachmentRef reference =
                new AttachmentRef(stored.digest(), stored.mediaType(), "evidence.txt", stored.sizeBytes());
        AttachmentRef wrongSize =
                new AttachmentRef(stored.digest(), stored.mediaType(), "evidence.txt", stored.sizeBytes() + 1);

        assertEquals(stored, new CoreAttachmentEvidencePort(attachments, first.id()).requireOwned(reference));
        assertThrows(
                PersistenceException.class,
                () -> new CoreAttachmentEvidencePort(attachments, first.id()).requireOwned(wrongSize));
        assertThrows(
                PersistenceException.class,
                () -> new CoreAttachmentEvidencePort(attachments, second.id()).requireOwned(reference));
        assertThrows(PersistenceException.class, () -> attachments.store(secondScope, identity, "text/plain", content));
        assertThrows(
                PersistenceException.class, () -> attachments.store(firstScope, identity, "application/json", content));
        assertThrows(
                PersistenceException.class,
                () -> attachments.store(
                        firstScope, identity, "text/plain", "changed content".getBytes(StandardCharsets.UTF_8)));
        CommandIdentity changedMethod = new CommandIdentity(
                "attachment/internal/other",
                identity.idempotencyKey(),
                identity.expectedRevision(),
                identity.requestDigest());
        CommandIdentity changedDigest = new CommandIdentity(
                identity.method(), identity.idempotencyKey(), identity.expectedRevision(), "f".repeat(64));
        assertThrows(
                PersistenceException.class, () -> attachments.store(firstScope, changedMethod, "text/plain", content));
        assertThrows(
                PersistenceException.class, () -> attachments.store(firstScope, changedDigest, "text/plain", content));
    }

    @Test
    void oneScopeCannotAssignTwoMediaTypesToSameDigest() {
        Workspace workspace = createWorkspace("workspace");
        AttachmentScope scope = AttachmentScope.workspace(workspace.id());
        byte[] content = "same bytes".getBytes(StandardCharsets.UTF_8);

        attachments.store(scope, storeIdentity("plain", scope, "text/plain", content), "text/plain", content);

        assertThrows(
                PersistenceException.class,
                () -> attachments.store(
                        scope, storeIdentity("json", scope, "application/json", content), "application/json", content));
    }

    @Test
    void recoveryRejectsTamperedCommandResult() throws Exception {
        Workspace workspace = createWorkspace("tampered-command");
        AttachmentScope scope = AttachmentScope.workspace(workspace.id());
        byte[] content = "tampered command".getBytes(StandardCharsets.UTF_8);
        CommandIdentity identity = storeIdentity("tampered-command", scope, "text/plain", content);
        AttachmentMetadata stored = attachments.store(scope, identity, "text/plain", content);
        AttachmentMetadata tampered =
                new AttachmentMetadata(stored.digest(), "application/json", stored.sizeBytes(), stored.createdAt());
        updateCommandResponse(identity.idempotencyKey(), json.encode(tampered).json());

        assertThrows(PersistenceException.class, () -> attachments.store(scope, identity, "text/plain", content));
    }

    @Test
    void recoveryRejectsAuthoritySizeDriftEvenWhenStoredResponseWasAlsoChanged() throws Exception {
        Workspace workspace = createWorkspace("tampered-size");
        AttachmentScope scope = AttachmentScope.workspace(workspace.id());
        byte[] content = "tampered size".getBytes(StandardCharsets.UTF_8);
        CommandIdentity identity = storeIdentity("tampered-size", scope, "text/plain", content);
        AttachmentMetadata stored = attachments.store(scope, identity, "text/plain", content);
        AttachmentMetadata tampered =
                new AttachmentMetadata(stored.digest(), stored.mediaType(), stored.sizeBytes() + 1, stored.createdAt());
        updateAttachmentSize(stored.digest(), tampered.sizeBytes());
        updateCommandResponse(identity.idempotencyKey(), json.encode(tampered).json());

        assertThrows(PersistenceException.class, () -> attachments.store(scope, identity, "text/plain", content));
    }

    @Test
    void builtinExtensionPortStoresAndReadsGeneratedWorkspaceAttachmentIdempotently() {
        Workspace workspace = createWorkspace("generated");
        CoreAttachmentEvidencePort port = new CoreAttachmentEvidencePort(attachments, workspace.id());
        byte[] content = "generated skill".getBytes(StandardCharsets.UTF_8);

        AttachmentRef first =
                port.storeGenerated("skill/export", "stable-export", "text/markdown", "review.skill.md", content);
        AttachmentRef recovered =
                port.storeGenerated("skill/export", "stable-export", "text/markdown", "review.skill.md", content);

        assertEquals(first, recovered);
        assertArrayEquals(
                content, port.readOwned(first.digest(), content.length).content());
        assertThrows(IllegalArgumentException.class, () -> port.readOwned(first.digest(), content.length - 1));
        assertThrows(
                PersistenceException.class,
                () -> port.storeGenerated(
                        "skill/export", "stable-export", "text/markdown", "changed.skill.md", content));
    }

    private AttachmentMetadata storeAfterSignal(
            CountDownLatch ready,
            CountDownLatch start,
            AttachmentScope scope,
            String mediaType,
            String key,
            byte[] content)
            throws InterruptedException {
        ready.countDown();
        start.await();
        return attachments.store(scope, storeIdentity(key, scope, mediaType, content), mediaType, content);
    }

    private Workspace createWorkspace(String name) {
        CoreRpcContracts.WorkspaceCreatePayload payload =
                new CoreRpcContracts.WorkspaceCreatePayload(name, temporaryDirectory.resolve(name));
        return core.createWorkspace(
                identity("workspace/create", "workspace-" + name, 0, payload), payload.name(), payload.root());
    }

    private CommandIdentity storeIdentity(String key, AttachmentScope scope, String mediaType, byte[] content) {
        AttachmentRpcContracts.BeginPayload payload = new AttachmentRpcContracts.BeginPayload(
                scope, mediaType, ManagedWorktreePolicy.sha256(content), content.length);
        return identity("attachment/internal/store", key, 0, payload);
    }

    private void updateCommandResponse(String key, String response) throws Exception {
        try (var connection = database.open();
                var statement = connection.prepareStatement(
                        "UPDATE CORE.COMMAND_RESULT SET RESPONSE_PAYLOAD = ? WHERE IDEMPOTENCY_KEY = ?")) {
            statement.setString(1, response);
            statement.setString(2, key);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void updateAttachmentSize(String digest, long size) throws Exception {
        try (var connection = database.open();
                var statement =
                        connection.prepareStatement("UPDATE CORE.ATTACHMENT SET BYTE_LENGTH = ? WHERE DIGEST = ?")) {
            statement.setLong(1, size);
            statement.setString(2, digest);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private CommandIdentity identity(String method, String key, long expectedRevision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, expectedRevision, json.encode(payload)), json);
    }
}
