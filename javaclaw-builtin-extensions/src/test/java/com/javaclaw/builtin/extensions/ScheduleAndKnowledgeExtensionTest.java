package com.javaclaw.builtin.extensions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.builtin.contracts.ScheduleManagementContracts;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ScheduledCommandPort;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleAndKnowledgeExtensionTest {
    private static final AgentProfileRef PROFILE = new AgentProfileRef("profile", 1);
    private static final OrchestrationContracts.ExecutionBudget BUDGET =
            new OrchestrationContracts.ExecutionBudget(10, 10_000, 10_000, 100);

    @Test
    void schedulePersistsDefinitionPreviewsFiveTimesAndQueuesIndependentOccurrence() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        ScheduleExtension bundle = new ScheduleExtension();
        var started = support.start(bundle);
        bundle.jobExecutors(runtime(support));
        try {
            ScheduleContracts.Definition enabled = schedule("enabled", 1, true, Duration.ofMinutes(2));
            started.command(support.request(
                    "definition/create",
                    scheduleInput("enabled", true, Duration.ofMinutes(2)),
                    Optional.of("schedule-enabled"),
                    0));
            assertDefinitionAndPreview(started, support, enabled);
            assertOccurrences(started, support);
            assertEquals(
                    1,
                    support.jobs
                            .list(Optional.empty(), Optional.empty(), Set.of(), 10)
                            .size());
            assertTrue(started.contributions().stream().anyMatch(value -> value.kind() == ContributionKind.TIMER));
        } finally {
            bundle.close();
        }
    }

    private static void assertDefinitionAndPreview(
            BuiltinExtensionTestSupport.Started started,
            BuiltinExtensionTestSupport support,
            ScheduleContracts.Definition enabled)
            throws Exception {
        ScheduleContracts.Definition read = support.decode(
                started.query(support.request("read", new DocumentContracts.Key("enabled"), Optional.empty(), 0)),
                ScheduleContracts.Definition.class);
        ScheduleContracts.Preview preview = support.decode(
                started.query(support.request(
                        "preview", new ScheduleContracts.PreviewRequest("enabled", 1, NOW), Optional.empty(), 0)),
                ScheduleContracts.Preview.class);
        ViewQueryResult viewPreview = support.decode(
                started.query(support.request(
                        "preview/view.list",
                        new ViewQueryRequest(
                                "preview",
                                Map.of("scheduleId", "enabled", "scheduleRevision", "1"),
                                "",
                                5,
                                Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
        assertEquals(enabled, read);
        assertEquals(5, preview.instants().size());
        assertEquals(5, viewPreview.rows().size());
        assertEquals(1, viewPreview.revision());
    }

    private static void assertOccurrences(
            BuiltinExtensionTestSupport.Started started, BuiltinExtensionTestSupport support) throws Exception {
        ScheduleContracts.Occurrence occurrence = runOccurrence(started, support, "run-enabled");
        ScheduleContracts.Occurrence overlapping = runOccurrence(started, support, "run-enabled-again");
        ViewQueryResult rows = support.decode(
                started.query(support.request(
                        "occurrence/view.list",
                        new ViewQueryRequest("occurrences", Map.of("scheduleId", "enabled"), "", 10, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
        assertEquals(
                ScheduleContracts.OccurrenceState.DISPATCHED,
                occurrence.status().state());
        assertEquals(
                ScheduleContracts.OccurrenceState.SKIPPED, overlapping.status().state());
        assertNotEquals(occurrence.identity().id(), overlapping.identity().id());
        assertEquals(2, rows.rows().size());
        assertTrue(rows.rows().stream().allMatch(row -> row.json().contains("\"scheduleRevision\":1")));
    }

    private static ScheduleContracts.Occurrence runOccurrence(
            BuiltinExtensionTestSupport.Started started, BuiltinExtensionTestSupport support, String idempotencyKey)
            throws Exception {
        return support.decode(
                started.command(support.request(
                        "occurrence/run", new ScheduleContracts.ManualRun("enabled"), Optional.of(idempotencyKey), 1)),
                ScheduleContracts.Occurrence.class);
    }

    private static ExtensionJobRuntimeContext runtime(BuiltinExtensionTestSupport support) {
        return new ExtensionJobRuntimeContext(
                support.clock,
                support.payloads,
                support.turns,
                support.store,
                invocation -> {
                    throw new IllegalStateException("isolated service is not configured");
                },
                support.embeddings,
                AutomationStepPort.unavailable(),
                ScheduledCommandPort.unavailable(),
                (workspaceId, required) -> {});
    }

    private static ScheduleContracts.Definition schedule(String id, long revision, boolean enabled, Duration interval) {
        ScheduleContracts.TurnTemplate target =
                new ScheduleContracts.TurnTemplate(PROFILE, id + " Thread", "执行定时任务", BUDGET);
        return new ScheduleContracts.Definition(
                id,
                revision,
                id,
                enabled,
                ScheduleContracts.Timing.fixed(interval, NOW.plusSeconds(600)),
                ScheduleContracts.Target.turn(target),
                ScheduleContracts.OverlapPolicy.SKIP_IF_RUNNING,
                ScheduleContracts.MisfirePolicy.DO_NOT_CATCH_UP,
                NOW);
    }

    private static ScheduleManagementContracts.SaveRequest scheduleInput(
            String id, boolean enabled, Duration interval) {
        return new ScheduleManagementContracts.SaveRequest(
                id,
                id,
                enabled,
                ScheduleContracts.TimingKind.FIXED_INTERVAL,
                ScheduleContracts.TargetKind.TURN_TEMPLATE,
                ScheduleManagementContracts.TURN_TEMPLATE_EXTENSION,
                ScheduleManagementContracts.TURN_TEMPLATE_ID,
                0,
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.of(interval.toMinutes()),
                Optional.of(NOW.plusSeconds(600)),
                PROFILE.id(),
                PROFILE.revision(),
                id + " Thread",
                "执行定时任务",
                BUDGET.maximumTurns(),
                BUDGET.inputTokens(),
                BUDGET.outputTokens(),
                BUDGET.toolCalls(),
                List.of());
    }
}
