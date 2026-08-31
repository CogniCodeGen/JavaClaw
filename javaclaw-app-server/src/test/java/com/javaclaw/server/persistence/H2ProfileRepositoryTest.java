package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.conversation.ProfileRepository;
import com.javaclaw.agent.conversation.ProfileService;
import com.javaclaw.core.api.ProfileKind;
import com.javaclaw.sandbox.api.SandboxMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2ProfileRepositoryTest {
    @TempDir
    Path temporary;

    @Test
    void zeroLoopBudgetsInheritFiniteLimitsWithoutChangingSavedCustomValues() {
        try (var persistence = new H2Persistence(temporary.resolve("zero-budget"))) {
            var profiles = new ProfileService(new H2ProfileRepository(persistence.database()), Set.of());
            var workspace = persistence.workspaces().create("workspace", temporary, "workspace");
            var profile = profiles.put(
                    new ProfileRepository.ProfileDraft(
                            "loop",
                            "Loop",
                            ProfileKind.LOOP,
                            "openai",
                            "fake",
                            "custom",
                            Set.of(),
                            SandboxMode.READ_ONLY,
                            0,
                            0,
                            Map.of("maxTokens", "5000")),
                    0,
                    "zero");
            var config = profiles.resolve(profile.id(), workspace, null, null).turnConfig();
            assertEquals("25", config.attributes().get("maxIterations"));
            assertEquals("100", config.attributes().get("maxModelCalls"));
            assertEquals("5000", config.attributes().get("maxTokens"));
            assertEquals(0, profiles.read(profile.id()).maxIterations());
        }
    }

    @Test
    void versionsProfilesAndResolvesPlanAsReadOnly() {
        try (H2Persistence store = new H2Persistence(temporary.resolve("data-v4"))) {
            H2ProfileRepository repository = new H2ProfileRepository(store.database());
            ProfileService profiles = new ProfileService(repository, Set.of());
            var created = profiles.put(
                    new ProfileRepository.ProfileDraft(
                            "profile_plan",
                            "Plan",
                            ProfileKind.PLAN,
                            "openai",
                            "gpt-5",
                            "Plan only",
                            Set.of("read_file"),
                            SandboxMode.READ_ONLY,
                            8,
                            8,
                            Map.of()),
                    0,
                    "create-plan");
            assertEquals(1, created.revision());
            assertEquals(
                    created,
                    profiles.put(
                            new ProfileRepository.ProfileDraft(
                                    "profile_plan",
                                    "Plan",
                                    ProfileKind.PLAN,
                                    "openai",
                                    "gpt-5",
                                    "Plan only",
                                    Set.of("read_file"),
                                    SandboxMode.READ_ONLY,
                                    8,
                                    8,
                                    Map.of()),
                            0,
                            "create-plan"));
            assertThrows(
                    IllegalStateException.class,
                    () -> profiles.put(
                            new ProfileRepository.ProfileDraft(
                                    "profile_plan",
                                    "Changed",
                                    ProfileKind.PLAN,
                                    "openai",
                                    "gpt-5",
                                    "",
                                    Set.of(),
                                    SandboxMode.READ_ONLY,
                                    8,
                                    8,
                                    Map.of()),
                            99,
                            "bad-revision"));

            var workspace = store.workspaces().create("workspace", temporary, "profile-workspace");
            var resolved = profiles.resolve(created.id(), workspace, null, "high");
            assertEquals(
                    SandboxMode.READ_ONLY, resolved.turnConfig().sandboxPolicy().mode());
            assertEquals("PLAN", resolved.turnConfig().attributes().get("profileKind"));
            assertEquals("1", resolved.turnConfig().attributes().get("profileRevision"));
            assertTrue(profiles.delete(created.id(), 1, "delete-plan"));
            assertTrue(profiles.delete(created.id(), 1, "delete-plan"));
        }
    }
}
