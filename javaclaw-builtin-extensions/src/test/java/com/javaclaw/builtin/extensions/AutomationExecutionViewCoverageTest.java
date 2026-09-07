package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.PlanContracts;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ScheduledCommandPort;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationExecutionViewCoverageTest {
    private static final AgentRoleRef PROFILE = new AgentRoleRef("profile", 1);
    private static final OrchestrationContracts.ExecutionBudget BUDGET =
            new OrchestrationContracts.ExecutionBudget(5, 1_000, 500, 10);

    @Test
    void executionViewPaginatesPublicRowsAndRejectsInvalidSourceOrCursor() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = startPlan(support);
        startExecution(support, started, "first");
        startExecution(support, started, "second");

        ViewQueryResult first = view(support, started, "execution/view.list", "executions", Map.of(), "", 1);
        ViewQueryResult second =
                view(support, started, "execution/view.list", "executions", Map.of(), first.nextCursor(), 10);

        assertTrue(first.hasMore());
        assertEquals(1, first.rows().size());
        assertFalse(second.hasMore());
        assertEquals(1, second.rows().size());
        Map<?, ?> row = support.payloads.decode(first.rows().getFirst(), Map.class);
        assertEquals(
                List.of("definitionRevision", "id", "revision", "state", "updatedAt"),
                row.keySet().stream().map(Object::toString).sorted().toList());
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, "execution/view.list", "wrong", Map.of(), "", 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, "execution/view.list", "executions", Map.of("state", "RUNNING"), "", 10));
        assertThrows(
                RuntimeException.class,
                () -> view(support, started, "execution/view.list", "executions", Map.of(), "not-a-cursor", 10));
    }

    @Test
    void profileViewUsesStableCursorAndRejectsArgumentsOrStaleSelection() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = startPlan(support);

        ViewQueryResult first = view(support, started, "execution/role/view.list", "roles", Map.of(), "", 1);
        ViewQueryResult exhausted =
                view(support, started, "execution/role/view.list", "roles", Map.of(), "profile", 10);

        assertEquals(1, first.rows().size());
        assertFalse(first.hasMore());
        assertTrue(exhausted.rows().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, "execution/role/view.list", "wrong", Map.of(), "", 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, "execution/role/view.list", "roles", Map.of("id", "profile"), "", 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, "execution/role/view.list", "roles", Map.of(), "missing", 10));
    }

    @Test
    void definitionViewRequiresExactPositiveCurrentRevision() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = startPlan(support);
        Map<String, String> exact = Map.of("id", "plan", "revision", "1");

        ViewQueryResult selected =
                view(support, started, "execution/definition/view.selected", "executionDefinition", exact, "", 1);

        assertEquals(1, selected.revision());
        assertTrue(selected.values().json().contains("\"id\":\"plan\""));
        assertDefinitionRejected(support, started, "wrong", exact, "");
        assertDefinitionRejected(support, started, "executionDefinition", Map.of("id", "plan"), "");
        assertDefinitionRejected(support, started, "executionDefinition", exact, "cursor");
        assertDefinitionRejected(support, started, "executionDefinition", Map.of("id", "plan", "revision", "bad"), "");
        assertDefinitionRejected(support, started, "executionDefinition", Map.of("id", "plan", "revision", "0"), "");
        assertDefinitionRejected(support, started, "executionDefinition", Map.of("id", "plan", "revision", "2"), "");
        assertDefinitionRejected(support, started, "executionDefinition", Map.of("id", "missing", "revision", "1"), "");
    }

    @Test
    void executionResourcePublishesSchemasExecutorAndSchedulableDefinitionCatalog() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        PlanExtension extension = new PlanExtension();
        var started = support.start(extension);
        started.command(support.request("definition/create", plan(), Optional.of("create"), 0));

        String extensionId = extension.descriptor().id().value();
        assertEquals(
                Set.of(extensionId + "/document/v1", extensionId + "/proposal/v5", extensionId + "/execution-start/v2"),
                extension.schemas().stream()
                        .map(com.javaclaw.extension.spi.ExtensionSchema::schemaId)
                        .collect(Collectors.toUnmodifiableSet()));
        assertEquals(1, extension.jobExecutors(runtime(support)).size());
        var schedulable = started.contributions().stream()
                .filter(com.javaclaw.extension.spi.ExtensionContributions.SchedulableDefinition.class::isInstance)
                .map(com.javaclaw.extension.spi.ExtensionContributions.SchedulableDefinition.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(
                "plan",
                schedulable.provider().list(started.context()).getFirst().definitionId());
    }

    private static void assertDefinitionRejected(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            String source,
            Map<String, String> arguments,
            String cursor) {
        assertThrows(
                IllegalArgumentException.class,
                () -> view(support, started, "execution/definition/view.selected", source, arguments, cursor, 1));
    }

    private static BuiltinExtensionTestSupport.Started startPlan(BuiltinExtensionTestSupport support) throws Exception {
        var started = support.start(new PlanExtension());
        started.command(support.request("definition/create", plan(), Optional.of("create-plan"), 0));
        return started;
    }

    private static void startExecution(
            BuiltinExtensionTestSupport support, BuiltinExtensionTestSupport.Started started, String key)
            throws Exception {
        started.orchestrate(support.request(
                "execution/start",
                new OrchestrationContracts.StartRequest("plan", AutomationV6Fixtures.selection(PROFILE), BUDGET),
                Optional.of(key),
                1));
    }

    private static ViewQueryResult view(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            String operation,
            String source,
            Map<String, String> arguments,
            String cursor,
            int limit)
            throws Exception {
        ViewQueryRequest query = new ViewQueryRequest(source, arguments, cursor, limit, Optional.empty());
        return support.decode(
                started.query(support.request(operation, query, Optional.empty(), 0)), ViewQueryResult.class);
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
                com.javaclaw.extension.spi.ScheduleLifecyclePort.unavailable());
    }

    private static PlanContracts.ManagementSaveRequest plan() {
        return new PlanContracts.ManagementSaveRequest(
                "plan",
                "计划",
                "完成",
                "Workspace",
                List.of(),
                List.of(),
                List.of(new PlanContracts.ManagementStep("step", "build", "构建", "执行", "成功", List.of())));
    }
}
