package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.automation.AutomationKind;
import com.javaclaw.agent.automation.AutomationRepository;
import com.javaclaw.agent.conversation.ProfileRepository;
import com.javaclaw.agent.conversation.ProfileService;
import com.javaclaw.core.api.ProfileKind;
import com.javaclaw.sandbox.api.SandboxMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2AutomationRepositoryTest {
    @TempDir
    Path temporary;

    @Test
    void versionsDefinitionsSchedulesAndReplaysMutations() {
        try (H2Persistence store = new H2Persistence(temporary.resolve("data-v4"))) {
            var workspace = store.workspaces().create("Automation", temporary.resolve("workspace"), "wsp");
            ProfileService profiles = new ProfileService(new H2ProfileRepository(store.database()), Set.of());
            putProfile(profiles, "profile_loop", ProfileKind.LOOP);
            putProfile(profiles, "profile_schedule", ProfileKind.SCHEDULE);
            H2AutomationRepository repository = new H2AutomationRepository(store.database());

            var draft = new AutomationRepository.AutomationDraft(
                    "automation_loop",
                    AutomationKind.LOOP,
                    "Loop",
                    workspace.id().value(),
                    "profile_loop",
                    "iterate until complete",
                    "{\"maxIterations\":8}");
            var created = repository.putAutomation(draft, 0, "automation-put");
            assertEquals(created, repository.putAutomation(draft, 0, "automation-put"));
            var running =
                    repository.bindAutomationRun(created.id(), created.revision(), "thr_test", "turn_test", "RUNNING");
            assertEquals("turn_test", running.activeTurnId());
            assertEquals(2, running.revision());

            var scheduleDraft = new AutomationRepository.ScheduleDraft(
                    "schedule_daily",
                    "Daily",
                    workspace.id().value(),
                    "profile_schedule",
                    "summarize",
                    "0 0 9 * * ?",
                    "Asia/Shanghai",
                    false);
            var schedule = repository.putSchedule(scheduleDraft, 0, "schedule-put");
            assertEquals(schedule, repository.putSchedule(scheduleDraft, 0, "schedule-put"));
            var enabled = repository.setScheduleEnabled(schedule.id(), true, schedule.revision(), "schedule-enable");
            assertTrue(enabled.enabled());
            Instant next = Instant.parse("2099-01-01T01:00:00Z");
            repository.recordScheduleFire(schedule.id(), Instant.now(), next, "STARTED");
            var recorded = repository.findSchedule(schedule.id()).orElseThrow();
            assertEquals(next, recorded.nextFireAt());
            assertEquals("STARTED", recorded.lastResult());
            assertNotNull(recorded.lastFireAt());
            assertTrue(repository.deleteSchedule(schedule.id(), enabled.revision(), "schedule-delete"));
            assertFalse(repository.findSchedule(schedule.id()).isPresent());
        }
    }

    private static void putProfile(ProfileService profiles, String id, ProfileKind kind) {
        profiles.put(
                new ProfileRepository.ProfileDraft(
                        id, id, kind, "openai", "gpt-5", "", Set.of(), SandboxMode.READ_ONLY, 8, 8, Map.of()),
                0,
                "put-" + id);
    }
}
