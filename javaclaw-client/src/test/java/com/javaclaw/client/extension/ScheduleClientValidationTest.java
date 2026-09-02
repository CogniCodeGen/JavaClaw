package com.javaclaw.client.extension;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.builtin.contracts.ScheduleManagementContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ScheduleClientValidationTest {
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("921e2a14-52fe-44db-99e2-050f81fcddc0");
    private static final AgentProfileRef PROFILE = new AgentProfileRef("profile", 3);
    private static final OrchestrationContracts.ExecutionBudget BUDGET =
            new OrchestrationContracts.ExecutionBudget(5, 2_000, 1_000, 20);
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
    private static final CommandOptions CREATE = new CommandOptions("create", 0);
    private static final CommandOptions REVISION_ONE = new CommandOptions("revision-one", 1);

    @Test
    void writeMethodsRejectInvalidExpectedRevisionBeforeRpc() throws IOException {
        ScriptedExtensionConnection script = new ScriptedExtensionConnection(WORKSPACE);
        try (ClientFixture fixture = fixture(script)) {
            ScheduleClient client = fixture.client();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.create(WORKSPACE, request(), new CommandOptions("invalid-create", 1)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.update(WORKSPACE, request(), new CommandOptions("invalid-update", 0)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.run(
                            WORKSPACE,
                            new ScheduleContracts.ManualRun("schedule"),
                            new CommandOptions("invalid-run", 0)));
        }
        script.assertExhausted();
    }

    @Test
    void facadeRejectsResponsesForAnotherScheduleOrRevision() throws IOException {
        ScheduleContracts.Definition another = definition("another", 1);
        ScheduleContracts.Definition scheduleAtTwo = definition("schedule", 2);
        ScheduleContracts.Occurrence anotherOccurrence = occurrence(another);
        ScheduleContracts.Occurrence wrongRevisionOccurrence = occurrence(scheduleAtTwo);
        ScheduleContracts.OccurrenceQuery query =
                new ScheduleContracts.OccurrenceQuery(Optional.of("schedule"), "", 20);
        ScheduleContracts.ManualRun run = new ScheduleContracts.ManualRun("schedule");
        ScriptedExtensionConnection script = new ScriptedExtensionConnection(WORKSPACE);
        script.expectCommand(BuiltinExtensionIds.SCHEDULE, "definition/create", request(), CREATE, another, 1);
        script.expectQuery(
                BuiltinExtensionIds.SCHEDULE,
                "occurrence/list",
                query,
                new ScheduleContracts.OccurrencePage(List.of(anotherOccurrence), ""),
                0);
        script.expectCommand(BuiltinExtensionIds.SCHEDULE, "occurrence/run", run, REVISION_ONE, anotherOccurrence, 1);
        script.expectCommand(
                BuiltinExtensionIds.SCHEDULE, "occurrence/run", run, REVISION_ONE, wrongRevisionOccurrence, 1);
        try (ClientFixture fixture = fixture(script)) {
            ScheduleClient client = fixture.client();
            assertThrows(IllegalStateException.class, () -> client.create(WORKSPACE, request(), CREATE));
            assertThrows(IllegalStateException.class, () -> client.occurrences(WORKSPACE, query));
            assertThrows(IllegalStateException.class, () -> client.run(WORKSPACE, run, REVISION_ONE));
            assertThrows(IllegalStateException.class, () -> client.run(WORKSPACE, run, REVISION_ONE));
        }
        script.assertExhausted();
    }

    private static ScheduleManagementContracts.SaveRequest request() {
        return new ScheduleManagementContracts.SaveRequest(
                "schedule",
                "Daily",
                true,
                ScheduleContracts.TimingKind.FIXED_INTERVAL,
                ScheduleContracts.TargetKind.TURN_TEMPLATE,
                ScheduleManagementContracts.TURN_TEMPLATE_EXTENSION,
                ScheduleManagementContracts.TURN_TEMPLATE_ID,
                0,
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.of(60L),
                Optional.of(NOW.plusSeconds(60)),
                PROFILE.id(),
                PROFILE.revision(),
                "Scheduled task",
                "run checks",
                BUDGET.maximumTurns(),
                BUDGET.inputTokens(),
                BUDGET.outputTokens(),
                BUDGET.toolCalls(),
                java.util.List.of());
    }

    private static ScheduleContracts.Definition definition(String id, long revision) {
        ScheduleContracts.Target target = ScheduleContracts.Target.turn(
                new ScheduleContracts.TurnTemplate(PROFILE, "Scheduled task", "run checks", BUDGET));
        return new ScheduleContracts.Definition(
                id,
                revision,
                "Daily",
                true,
                ScheduleContracts.Timing.fixed(Duration.ofMinutes(60), NOW.plusSeconds(60)),
                target,
                ScheduleContracts.OverlapPolicy.SKIP_IF_RUNNING,
                ScheduleContracts.MisfirePolicy.DO_NOT_CATCH_UP,
                NOW.plusSeconds(revision));
    }

    private static ScheduleContracts.Occurrence occurrence(ScheduleContracts.Definition definition) {
        return new ScheduleContracts.Occurrence(
                new ScheduleContracts.OccurrenceIdentity(
                        "occurrence-" + definition.id(), definition.id(), definition.revision()),
                definition,
                NOW,
                new ScheduleContracts.OccurrenceStatus(
                        ScheduleContracts.OccurrenceState.COMPLETED, Optional.empty(), Optional.empty()),
                NOW,
                NOW);
    }

    private static ClientFixture fixture(ScriptedExtensionConnection script) {
        RpcClientConnection connection = new RpcClientConnection(script, new CanonicalJson(), ignored -> {});
        ScheduleClient client = new BuiltinExtensionClients(new ExtensionClient(connection)).schedules();
        return new ClientFixture(client, connection);
    }

    private record ClientFixture(ScheduleClient client, RpcClientConnection connection) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            connection.close();
        }
    }
}
