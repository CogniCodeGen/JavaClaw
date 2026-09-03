package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.AttachmentRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ProviderProfileRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.ProviderEndpointTestFixtures;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CorePlatformServicesTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private Clock clock;

    @BeforeEach
    void initializeDataV5() {
        database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        json = new CanonicalJson();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Test
    void attachmentIsContentAddressedIdempotentAndDetectsBlobTampering() throws Exception {
        AttachmentService attachments = new AttachmentService(database, json, clock);
        byte[] content = "v5 attachment".getBytes(StandardCharsets.UTF_8);
        AttachmentRpcContracts.BeginPayload payload = new AttachmentRpcContracts.BeginPayload(
                AttachmentScope.global(), "text/plain", ManagedWorktreePolicy.sha256(content), content.length);
        CommandIdentity identity = identity("attachment/internal/store", "attachment-key", 0, payload);

        AttachmentMetadata created = attachments.store(payload.scope(), identity, payload.mediaType(), content);
        AttachmentMetadata retried = attachments.store(payload.scope(), identity, payload.mediaType(), content);
        AttachmentContent read = attachments.read(payload.scope(), created.digest());

        assertEquals(created, retried);
        assertArrayEquals(content, read.content());
        Path blob = database.dataRoot()
                .resolve("blobs/core")
                .resolve(created.digest().substring(0, 2))
                .resolve(created.digest() + ".blob");
        Files.writeString(blob, "tampered", StandardCharsets.UTF_8);
        assertThrows(PersistenceException.class, () -> attachments.read(payload.scope(), created.digest()));
    }

    @Test
    void attachmentDigestDoesNotGrantGlobalOrAnotherWorkspaceOwnership() {
        CoreCommandService core = new CoreCommandService(database, json, clock);
        Workspace first = createWorkspace(core, "first");
        Workspace second = createWorkspace(core, "second");
        AttachmentService attachments = new AttachmentService(database, json, clock);
        byte[] content = "workspace attachment".getBytes(StandardCharsets.UTF_8);
        String digest = ManagedWorktreePolicy.sha256(content);
        AttachmentScope global = AttachmentScope.global();
        AttachmentScope firstScope = AttachmentScope.workspace(first.id());
        AttachmentRpcContracts.BeginPayload globalPayload =
                new AttachmentRpcContracts.BeginPayload(global, "text/plain", digest, content.length);
        AttachmentMetadata globalMetadata = attachments.store(
                global, identity("attachment/internal/store", "global", 0, globalPayload), "text/plain", content);
        AttachmentRef reference = new AttachmentRef(digest, "text/plain", "guide.txt", content.length);

        assertThrows(PersistenceException.class, () -> attachments.requireOwned(first.id(), reference));
        AttachmentRpcContracts.BeginPayload workspacePayload =
                new AttachmentRpcContracts.BeginPayload(firstScope, "text/plain", digest, content.length);
        AttachmentMetadata workspaceMetadata = attachments.store(
                firstScope,
                identity("attachment/internal/store", "workspace", 0, workspacePayload),
                "text/plain",
                content);

        assertEquals(globalMetadata, workspaceMetadata);
        assertEquals(workspaceMetadata, attachments.requireOwned(first.id(), reference));
        assertThrows(PersistenceException.class, () -> attachments.requireOwned(second.id(), reference));
        assertThrows(
                PersistenceException.class,
                () -> attachments.requireOwned(
                        first.id(), new AttachmentRef(digest, "application/json", "guide.json", content.length)));
    }

    @Test
    void typedProviderServiceEnforcesRevisionAndRejectsMismatchedAdapterOptions() {
        ProviderService service = new ProviderService(database, reference -> true, json, clock);
        ProviderEndpointSpec firstSpec = providerSpec("Provider", "gpt-test");
        ProviderProfileRpcContracts.ProviderCreatePayload firstPayload =
                new ProviderProfileRpcContracts.ProviderCreatePayload("openai", firstSpec, ProviderLifecycle.ACTIVE);
        ProviderEndpoint first = service.create(
                identity("provider/create", "provider-create", 0, firstPayload),
                "openai",
                firstSpec,
                ProviderLifecycle.ACTIVE);
        ProviderEndpointSpec secondSpec = providerSpec("Provider 2", "gpt-test");
        ProviderProfileRpcContracts.ProviderUpdatePayload secondPayload =
                new ProviderProfileRpcContracts.ProviderUpdatePayload("openai", secondSpec, ProviderLifecycle.ACTIVE);
        ProviderEndpoint second = service.update(
                identity("provider/update", "provider-update", 1, secondPayload),
                "openai",
                secondSpec,
                ProviderLifecycle.ACTIVE);

        assertEquals(1, first.revision());
        assertEquals(2, second.revision());
        assertEquals(List.of(second), service.listLatest());
        assertThrows(
                PersistenceException.class,
                () -> service.update(
                        identity("provider/update", "stale-provider", 1, secondPayload),
                        "openai",
                        secondSpec,
                        ProviderLifecycle.ACTIVE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderEndpointSpec(
                        "Unsafe",
                        ProviderAdapter.OPENAI_COMPATIBLE,
                        Optional.empty(),
                        com.javaclaw.api.ProviderAuthentication.API_KEY,
                        ProviderEndpointTestFixtures.chat("Unsafe", ProviderAdapter.OPENAI_COMPATIBLE, "gpt-test")
                                .models(),
                        Optional.empty(),
                        Duration.ofSeconds(30),
                        0,
                        ProviderAdapterOptions.defaults(ProviderAdapter.ANTHROPIC)));
    }

    private static ProviderEndpointSpec providerSpec(String displayName, String model) {
        return ProviderEndpointTestFixtures.chat(displayName, ProviderAdapter.OPENAI_COMPATIBLE, model);
    }

    private Workspace createWorkspace(CoreCommandService core, String name) {
        CoreRpcContracts.WorkspaceCreatePayload payload =
                new CoreRpcContracts.WorkspaceCreatePayload(name, temporaryDirectory.resolve(name));
        return core.createWorkspace(
                identity("workspace/create", "workspace-" + name, 0, payload), payload.name(), payload.root());
    }

    private CommandIdentity identity(String method, String key, long expectedRevision, Object payload) {
        WriteCommand command = new WriteCommand(key, expectedRevision, json.encode(payload));
        return CommandIdentity.from(method, command, json);
    }
}
