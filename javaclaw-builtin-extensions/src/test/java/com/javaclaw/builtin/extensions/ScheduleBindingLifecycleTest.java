package com.javaclaw.builtin.extensions;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.ConversationEvidencePort;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ScheduleDefinitionBindingPort;
import com.javaclaw.extension.spi.ScheduledCommandPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScheduleBindingLifecycleTest {
    @Test
    void retargetPreservesUserTimingAndDisableAndDeletedGenerationCannotBeResurrected() throws Exception {
        var support = new BuiltinExtensionTestSupport();
        var bundle = new ScheduleExtension();
        var schedule = support.start(bundle);
        bundle.jobExecutors(runtime(support));
        try {
            var first = bind(support, schedule, change(support, 1, 0, 0, false, "create"));
            var updated = support.decode(
                    schedule.command(support.request(
                            "managed/form/update",
                            Map.of(
                                    "id",
                                    first.scheduleId(),
                                    "name",
                                    "个人学习计划",
                                    "enabled",
                                    false,
                                    "timingKind",
                                    "CRON",
                                    "cronExpression",
                                    "0 0 8 * * ?",
                                    "zoneId",
                                    "Asia/Shanghai",
                                    "intervalMinutes",
                                    360,
                                    "firstFireAt",
                                    ""),
                            Optional.of("edit"),
                            1)),
                    ScheduleContracts.Definition.class);
            assertFalse(updated.enabled());
            var afterEdit = read(support, schedule);
            var retarget = bind(
                    support,
                    schedule,
                    change(support, 2, afterEdit.revision(), afterEdit.generation(), false, "retarget"));
            var definition = support.decode(
                    schedule.query(support.request(
                            "read", new DocumentContracts.Key(first.scheduleId()), Optional.empty(), 0)),
                    ScheduleContracts.Definition.class);
            assertEquals("个人学习计划", definition.name());
            assertFalse(definition.enabled());
            assertEquals("Asia/Shanghai", definition.timing().zoneId());
            assertEquals(2, definition.target().definition().orElseThrow().definitionRevision());
            assertDeleteGeneration(support, schedule, first, retarget, definition);
        } finally {
            bundle.close();
        }
    }

    private static void assertDeleteGeneration(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started schedule,
            ScheduleDefinitionBindingPort.Binding first,
            ScheduleDefinitionBindingPort.Binding retarget,
            ScheduleContracts.Definition definition)
            throws Exception {
        schedule.command(support.request(
                "delete", new DocumentContracts.Key(first.scheduleId()), Optional.of("delete"), definition.revision()));
        var detached = read(support, schedule);
        assertEquals(ScheduleDefinitionBindingPort.State.DETACHED, detached.state());
        assertThrows(
                IllegalArgumentException.class,
                () -> bind(
                        support,
                        schedule,
                        change(support, 3, retarget.revision(), retarget.generation(), false, "old-intent")));
        assertThrows(
                IllegalArgumentException.class,
                () -> bind(
                        support,
                        schedule,
                        change(support, 3, detached.revision(), detached.generation(), false, "not-explicit")));
        var restored = bind(
                support, schedule, change(support, 3, detached.revision(), detached.generation(), true, "explicit"));
        assertEquals(ScheduleDefinitionBindingPort.State.BOUND, restored.state());
        assertFalse(first.scheduleId().equals(restored.scheduleId()));
    }

    private static ScheduleDefinitionBindingPort.Change change(
            BuiltinExtensionTestSupport support,
            long revision,
            long bindingRevision,
            long generation,
            boolean reschedule,
            String key) {
        var target = new ScheduleContracts.DefinitionTarget(
                MemoryStoreAccess.ID.value(),
                MemoryLearningState.DEFINITION_ID,
                revision,
                MemoryLearningResource.restricted(com.javaclaw.api.ExecutionOverrides.empty()),
                MemoryLearningResource.BUDGET);
        return new ScheduleDefinitionBindingPort.Change(
                MemoryLearningState.DEFINITION_ID,
                revision,
                bindingRevision,
                generation,
                reschedule,
                support.payloads.encode(target),
                key);
    }

    private static ScheduleDefinitionBindingPort.Binding bind(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started schedule,
            ScheduleDefinitionBindingPort.Change change)
            throws Exception {
        return support.decode(
                schedule.command(support.request(
                        "binding/apply",
                        new ScheduleDefinitionBindingPort.Apply(MemoryStoreAccess.ID, change),
                        Optional.of(change.idempotencyKey()),
                        0)),
                ScheduleDefinitionBindingPort.Binding.class);
    }

    private static ScheduleDefinitionBindingPort.Binding read(
            BuiltinExtensionTestSupport support, BuiltinExtensionTestSupport.Started schedule) throws Exception {
        return support.decode(
                schedule.query(support.request(
                        "binding/read",
                        new ScheduleDefinitionBindingPort.Lookup(
                                MemoryStoreAccess.ID, MemoryLearningState.DEFINITION_ID),
                        Optional.empty(),
                        0)),
                ScheduleDefinitionBindingPort.Binding.class);
    }

    private static ExtensionJobRuntimeContext runtime(BuiltinExtensionTestSupport support) {
        return new ExtensionJobRuntimeContext(
                support.clock,
                support.payloads,
                support.turns,
                support.store,
                invocation -> {
                    throw new IllegalStateException("no services");
                },
                support.embeddings,
                AutomationStepPort.unavailable(),
                ScheduledCommandPort.unavailable(),
                (workspace, required) -> {},
                ConversationEvidencePort.unavailable(),
                ScheduleDefinitionBindingPort.unavailable());
    }
}
