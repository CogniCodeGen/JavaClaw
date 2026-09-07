package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleFileFormat;
import com.javaclaw.api.AgentRoleFilePreview;
import com.javaclaw.api.ModelPreference;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.ProviderEndpointTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRoleFileBoundaryTest {
    @TempDir
    Path temporaryDirectory;

    private final CanonicalJson json = new CanonicalJson();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private H2Database database;
    private ProviderService providers;
    private AgentRoleService roles;
    private AgentRoleFileService files;

    @BeforeEach
    void initialize() {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        providers = new ProviderService(database, reference -> true, json, clock);
        roles = new AgentRoleService(database, providers, json, clock);
        files = new AgentRoleFileService(database, roles, providers, json, clock);
    }

    @Test
    void uniqueMappingUsesOnlyActiveChatModelsAndRevocationPreservesCommittedReplay() {
        ProviderEndpointSpec selected = chat("Selected", "model");
        provider("selected", selected, ProviderLifecycle.ACTIVE);
        provider("disabled", chat("Disabled", "model"), ProviderLifecycle.DISABLED);
        provider("different", chat("Different", "other"), ProviderLifecycle.ACTIVE);
        provider(
                "embedding",
                ProviderEndpointTestFixtures.embedding("Embedding", ProviderAdapter.OPENAI_COMPATIBLE, "model"),
                ProviderLifecycle.ACTIVE);
        ProviderRef mapping = new ProviderRef("selected", 1, "model");
        AgentRoleFilePreview preview = preview("mapped");
        assertTrue(preview.unresolvedModel().isEmpty());
        assertEquals(Optional.of(new ModelPreference(mapping)), preview.spec().model());
        assertEquals(4, roles.listLatest().size());
        assertEquals(
                PersistenceException.Kind.INVALID_REQUEST,
                assertThrows(
                                PersistenceException.class,
                                () -> files.commit(
                                        identity("replace-mapping", 0, preview),
                                        preview.previewId(),
                                        Optional.of(mapping)))
                        .kind());
        CommandIdentity command = identity("commit-mapped", 0, preview);
        AgentRole committed = files.commit(command, preview.previewId(), Optional.empty());
        AgentRoleFilePreview pending = preview("pending");
        providers.update(identity("disable-selected", 1, selected), "selected", selected, ProviderLifecycle.DISABLED);

        // 已确认的写入可恢复原结果；尚未确认的预览必须重新检查撤销状态。
        assertEquals(committed, files.commit(command, preview.previewId(), Optional.empty()));
        assertEquals(
                PersistenceException.Kind.INVALID_REQUEST,
                assertThrows(
                                PersistenceException.class,
                                () -> files.commit(
                                        identity("revoked", 0, pending), pending.previewId(), Optional.empty()))
                        .kind());
        assertThrows(PersistenceException.class, () -> roles.requireLatest("pending"));
        assertEquals(5, roles.listLatest().size());
    }

    @Test
    void ambiguousPreviewRequiresSameModelAndKeepsItsExplicitMappingAcrossProviderChanges() {
        ProviderEndpointSpec selected = chat("First", "model");
        provider("first", selected, ProviderLifecycle.ACTIVE);
        provider("second", chat("Second", "model"), ProviderLifecycle.ACTIVE);
        provider("other", chat("Other", "different"), ProviderLifecycle.ACTIVE);
        AgentRoleFilePreview preview = preview("ambiguous");
        assertEquals(Optional.of("model"), preview.unresolvedModel());
        assertTrue(preview.spec().model().isEmpty());
        assertEquals(
                PersistenceException.Kind.INVALID_REQUEST,
                assertThrows(
                                PersistenceException.class,
                                () -> files.commit(
                                        identity("wrong-model", 0, preview),
                                        preview.previewId(),
                                        Optional.of(new ProviderRef("other", 1, "different"))))
                        .kind());
        providers.update(identity("disable-first", 1, selected), "first", selected, ProviderLifecycle.DISABLED);
        assertThrows(
                PersistenceException.class,
                () -> files.commit(
                        identity("disabled-mapping", 0, preview),
                        preview.previewId(),
                        Optional.of(new ProviderRef("first", 1, "model"))));
        assertThrows(
                PersistenceException.class,
                () -> files.commit(identity("still-needs-choice", 0, preview), preview.previewId(), Optional.empty()));

        // 候选数改变不能静默改写已展示的预览；用户仍需明确确认精确 Provider。
        ProviderRef mapping = new ProviderRef("second", 1, "model");
        AgentRole committed =
                files.commit(identity("choose-second", 0, preview), preview.previewId(), Optional.of(mapping));
        assertEquals(Optional.of(new ModelPreference(mapping)), committed.spec().model());
        assertEquals(committed, roles.require("ambiguous", 1));
    }

    @Test
    void committedPreviewRejectsChangedRequestIdentityAndSurvivesServiceRestart() {
        AgentRoleFilePreview preview = files.preview("replay", "name = 'Replay'\n", AgentRoleFileFormat.CODEX_PORTABLE);
        CommandIdentity command = identity("confirmed-preview", 0, preview);
        AgentRole committed = files.commit(command, preview.previewId(), Optional.empty());
        AgentRoleFileService reopened = new AgentRoleFileService(database, roles, providers, json, clock);
        CommandIdentity differentMethod = new CommandIdentity(
                "agent/role/update", command.idempotencyKey(), command.expectedRevision(), command.requestDigest());
        CommandIdentity differentPayload = new CommandIdentity(
                command.method(), command.idempotencyKey(), command.expectedRevision(), "b".repeat(64));
        for (CommandIdentity changed : java.util.List.of(differentMethod, differentPayload)) {
            assertEquals(
                    PersistenceException.Kind.IDEMPOTENCY_CONFLICT,
                    assertThrows(
                                    PersistenceException.class,
                                    () -> reopened.commit(changed, preview.previewId(), Optional.empty()))
                            .kind());
        }
        assertEquals(committed, reopened.commit(command, preview.previewId(), Optional.empty()));
        assertEquals(committed, roles.requireLatest("replay"));
        assertEquals(5, roles.listLatest().size());
    }

    private AgentRoleFilePreview preview(String roleId) {
        return files.preview(roleId, "name = 'Model role'\nmodel = 'model'\n", AgentRoleFileFormat.CODEX_PORTABLE);
    }

    private void provider(String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle) {
        providers.create(identity("provider-" + id, 0, spec), id, spec, lifecycle);
    }

    private static ProviderEndpointSpec chat(String name, String model) {
        return ProviderEndpointTestFixtures.chat(name, ProviderAdapter.OPENAI_COMPATIBLE, model);
    }

    private CommandIdentity identity(String key, long revision, Object payload) {
        return new CommandIdentity(
                "agent/role/import/commit",
                key,
                revision,
                json.encode(Map.of("payload", payload, "expectedRevision", revision))
                        .sha256());
    }
}
