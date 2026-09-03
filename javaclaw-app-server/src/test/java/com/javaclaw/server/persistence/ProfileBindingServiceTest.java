package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.ProviderEndpointTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileBindingServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private CoreCommandService core;
    private AgentProfileService profiles;
    private ProfileBindingService bindings;
    private Workspace firstWorkspace;
    private Workspace secondWorkspace;
    private ConversationThread firstThread;
    private ConversationThread secondThread;
    private AgentProfile firstProfile;
    private AgentProfile secondProfile;

    @BeforeEach
    void initializeBindings() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        json = new CanonicalJson();
        core = new CoreCommandService(database, json, clock);
        PermissionProfileService permissions = new PermissionProfileService(database, json, clock);
        permissions.installStandardProfile();
        ProviderService providers = new ProviderService(database, reference -> true, json, clock);
        ProviderEndpointSpec provider = providerSpec();
        providers.create(
                identity("provider/create", "provider", 0, provider), "provider", provider, ProviderLifecycle.ACTIVE);
        profiles = new AgentProfileService(database, providers, permissions, json, clock);
        firstProfile = profiles.create(
                identity("profile/create", "first", 0, Map.of("profileId", "first")), "first", profileSpec("First"));
        secondProfile = profiles.create(
                identity("profile/create", "second", 0, Map.of("profileId", "second")),
                "second",
                profileSpec("Second"));
        bindings = new ProfileBindingService(database, core, profiles, json, clock);
        firstWorkspace = workspace("first-workspace");
        secondWorkspace = workspace("second-workspace");
        firstThread = thread(firstWorkspace, "first-thread");
        secondThread = thread(secondWorkspace, "second-thread");
    }

    @Test
    void workspaceFallbackThreadOverrideAndExplicitReferenceResolveExactProfiles() {
        CommandIdentity workspaceCommand = identity("profileBinding/update", "workspace-binding", 0, firstProfile);
        ProfileBinding workspaceBinding =
                bindings.update(workspaceCommand, firstWorkspace.id(), Optional.empty(), reference(firstProfile));

        assertEquals(
                workspaceBinding,
                bindings.update(workspaceCommand, firstWorkspace.id(), Optional.empty(), reference(firstProfile)));
        assertEquals(firstProfile, bindings.resolve(firstThread.id(), Optional.empty()));
        ProfileBinding threadBinding = bindings.update(
                identity("profileBinding/update", "thread-binding", 0, secondProfile),
                firstWorkspace.id(),
                Optional.of(firstThread.id()),
                reference(secondProfile));
        assertEquals(Optional.of(threadBinding), bindings.find(firstWorkspace.id(), Optional.of(firstThread.id())));
        assertEquals(secondProfile, bindings.resolve(firstThread.id(), Optional.empty()));
        assertEquals(firstProfile, bindings.resolve(firstThread.id(), Optional.of(reference(firstProfile))));

        ProfileBinding updated = bindings.update(
                identity("profileBinding/update", "thread-binding-update", 1, firstProfile),
                firstWorkspace.id(),
                Optional.of(firstThread.id()),
                reference(firstProfile));
        assertEquals(2, updated.revision());
        assertEquals(firstProfile, bindings.resolve(firstThread.id(), Optional.empty()));
    }

    @Test
    void bindingsRejectMissingDefaultsCrossWorkspaceThreadsStaleWritesAndArchivedProfiles() {
        assertThrows(PersistenceException.class, () -> bindings.resolve(firstThread.id(), Optional.empty()));
        assertThrows(
                PersistenceException.class, () -> bindings.find(firstWorkspace.id(), Optional.of(secondThread.id())));
        assertThrows(
                PersistenceException.class,
                () -> bindings.update(
                        identity("profileBinding/update", "stale", 1, firstProfile),
                        firstWorkspace.id(),
                        Optional.empty(),
                        reference(firstProfile)));

        AgentProfile archived = profiles.archive(
                identity("profile/archive", "archive", firstProfile.revision(), firstProfile), firstProfile.id());
        assertThrows(
                PersistenceException.class,
                () -> bindings.update(
                        identity("profileBinding/update", "archived", 0, archived),
                        firstWorkspace.id(),
                        Optional.empty(),
                        reference(archived)));
        assertThrows(
                PersistenceException.class, () -> bindings.resolve(firstThread.id(), Optional.of(reference(archived))));
    }

    @Test
    void idempotencyKeyCannotBeReusedForAnotherBindingPayload() {
        CommandIdentity first = identity("profileBinding/update", "shared-key", 0, firstProfile);
        bindings.update(first, firstWorkspace.id(), Optional.empty(), reference(firstProfile));
        CommandIdentity conflict = new CommandIdentity(
                first.method(),
                first.idempotencyKey(),
                first.expectedRevision(),
                json.encode(secondProfile).sha256());

        assertThrows(
                PersistenceException.class,
                () -> bindings.update(conflict, firstWorkspace.id(), Optional.empty(), reference(secondProfile)));
        assertTrue(bindings.find(firstWorkspace.id(), Optional.empty()).isPresent());
    }

    private Workspace workspace(String name) {
        Path root = temporaryDirectory.resolve(name);
        return core.createWorkspace(
                identity("workspace/create", "workspace-" + name, 0, Map.of("name", name)), name, root);
    }

    private ConversationThread thread(Workspace workspace, String title) {
        return core.createThread(
                identity("thread/create", title, 0, Map.of("title", title)),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                title);
    }

    private static ProviderEndpointSpec providerSpec() {
        return ProviderEndpointTestFixtures.chat("Provider", ProviderAdapter.OPENAI_COMPATIBLE, "test-model");
    }

    private static AgentProfileSpec profileSpec(String name) {
        return new AgentProfileSpec(
                name,
                "",
                new ProviderRef("provider", 1, "test-model"),
                new PermissionProfileRef(PermissionProfileService.STANDARD_PROFILE_ID, 1),
                Set.of(),
                new TurnBudget(1_000, 1_000, 5, 1, Duration.ofMinutes(1)));
    }

    private static AgentProfileRef reference(AgentProfile profile) {
        return new AgentProfileRef(profile.id(), profile.revision());
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        return new CommandIdentity(method, key, revision, json.encode(payload).sha256());
    }
}
