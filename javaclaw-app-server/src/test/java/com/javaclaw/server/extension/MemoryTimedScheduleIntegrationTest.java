package com.javaclaw.server.extension;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ExecutionState;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryTimedScheduleIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void realQuartzTriggerUsesManagedTimingAndPublishesOnlyExplicitLowRiskUserEvidence() throws Exception {
        var model = new MemoryIntegrationModel();
        try (var fixture = new MemoryIntegrationFixture(temporary.resolve("data-v6"), Clock.systemUTC(), model)) {
            fixture.installProvider();
            var workspace = fixture.workspace("timer");
            fixture.conversation(workspace, "记忆事实：喜欢浅色界面");
            fixture.command(
                    workspace,
                    BuiltinExtensionIds.MEMORY,
                    "settings/update",
                    new MemoryContracts.LearningSettingsUpdate(MemoryContracts.LearningPolicy.AUTO_LOW_RISK),
                    0,
                    MemoryContracts.LearningSettings.class);
            fixture.save(workspace, true, 0);
            var binding = fixture.awaitBinding(workspace);
            Instant firstFire = Instant.now().plusSeconds(3).truncatedTo(ChronoUnit.MILLIS);
            model.blockLearning();
            var definition = fixture.command(
                    workspace,
                    BuiltinExtensionIds.SCHEDULE,
                    "managed/form/update",
                    timing(binding.scheduleId(), firstFire),
                    1,
                    ScheduleContracts.Definition.class);
            fixture.awaitLearning(workspace);
            assertEquals(1, fixture.occurrences(workspace).size());
            var occurrence = fixture.occurrences(workspace).getFirst();
            assertEquals(firstFire, occurrence.scheduledFor());
            assertEquals(definition, occurrence.definition());
            assertEquals(2, occurrence.identity().scheduleRevision());
            var job = fixture.jobs(workspace).stream()
                    .filter(value -> value.jobType().equals("conversation-learning"))
                    .findFirst()
                    .orElseThrow();
            model.releaseLearning();
            fixture.awaitJob(job.id(), ExecutionState.COMPLETED);
            assertEquals(
                    MemoryContracts.ProposalState.AUTO_ACCEPTED,
                    fixture.proposals(workspace).getFirst().state());
            assertEquals(
                    "记忆事实：喜欢浅色界面",
                    fixture.search(workspace).matches().getFirst().memory().content());
            assertTrue(model.learningInvocations.getFirst().tools().isEmpty());
        }
    }

    private static Map<String, Object> timing(String scheduleId, Instant firstFire) {
        return Map.of(
                "id",
                scheduleId,
                "name",
                "真实时间触发",
                "enabled",
                true,
                "timingKind",
                "FIXED_INTERVAL",
                "cronExpression",
                "",
                "zoneId",
                "UTC",
                "intervalMinutes",
                360,
                "firstFireAt",
                firstFire.toString());
    }
}
