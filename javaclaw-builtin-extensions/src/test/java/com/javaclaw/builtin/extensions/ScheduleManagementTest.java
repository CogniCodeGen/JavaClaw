package com.javaclaw.builtin.extensions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.ScheduleActionContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.builtin.contracts.ScheduleManagementContracts;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ScheduleTargetCatalogPort;
import com.javaclaw.extension.spi.ScheduledCommandPort;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleManagementTest {
    private static final AgentProfileRef PROFILE = new AgentProfileRef("profile", 1);
    private static final OrchestrationContracts.ExecutionBudget BUDGET =
            new OrchestrationContracts.ExecutionBudget(10, 10_000, 5_000, 100);

    @Test
    void definitionAndActionFreezeCurrentCatalogSchemaRevisionAndArguments() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        MutableCatalog catalog = new MutableCatalog();
        support.scheduleTargets = catalog;
        ScheduleExtension bundle = new ScheduleExtension();
        var started = support.start(bundle);
        bundle.jobExecutors(runtime(support));
        try {
            assertDefinitionAndActionCreate(started, support);
            assertTargetCatalogIncludesParameterizedAction(started, support);
            assertRejectedCatalogReferences(started, support);
        } finally {
            bundle.close();
        }
    }

    private static void assertDefinitionAndActionCreate(
            BuiltinExtensionTestSupport.Started started, BuiltinExtensionTestSupport support) throws Exception {
        ScheduleContracts.Definition definition = support.decode(
                started.command(support.request(
                        "definition/create",
                        input(
                                "definition-schedule",
                                ScheduleContracts.TargetKind.DEFINITION,
                                "javaclaw.plan",
                                "plan-1",
                                4),
                        Optional.of("create-definition-schedule"),
                        0)),
                ScheduleContracts.Definition.class);
        assertEquals(1, definition.revision());
        assertEquals(NOW, definition.updatedAt());
        assertEquals("plan-1", definition.target().definition().orElseThrow().definitionId());

        ScheduleContracts.Definition action = support.decode(
                started.command(support.request(
                        "definition/create",
                        input("action-schedule", ScheduleContracts.TargetKind.ACTION, "javaclaw.safe", "refresh", 2),
                        Optional.of("create-action-schedule"),
                        0)),
                ScheduleContracts.Definition.class);
        ScheduleActionContracts.Target actionTarget = action.target().action().orElseThrow();
        assertEquals(
                "{}",
                ScheduleActionParameters.payload(actionTarget, support.payloads).json());
        assertEquals(2, actionTarget.expectedRevision());
        assertEquals(64, actionTarget.schemaHash().length());

        ScheduleContracts.Definition parameterized = support.decode(
                started.command(support.request(
                        "definition/create",
                        input(
                                "parameterized-action",
                                ScheduleContracts.TargetKind.ACTION,
                                "javaclaw.safe",
                                "parameterized",
                                0,
                                List.of(new ScheduleActionContracts.Argument(
                                        "scope", ScheduleActionContracts.ValueType.STRING, "workspace"))),
                        Optional.of("parameterized-action"),
                        0)),
                ScheduleContracts.Definition.class);
        ScheduleActionContracts.Target frozen = parameterized.target().action().orElseThrow();
        assertEquals(
                "{\"scope\":\"workspace\"}",
                ScheduleActionParameters.payload(frozen, support.payloads).json());
        assertEquals("scope", frozen.fields().getFirst().name());
    }

    private static void assertRejectedCatalogReferences(
            BuiltinExtensionTestSupport.Started started, BuiltinExtensionTestSupport support) {
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "definition/create",
                        input("stale-target", ScheduleContracts.TargetKind.DEFINITION, "javaclaw.plan", "plan-1", 3),
                        Optional.of("stale-target"),
                        0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "definition/create",
                        input("stale-action", ScheduleContracts.TargetKind.ACTION, "javaclaw.safe", "refresh", 1),
                        Optional.of("stale-action"),
                        0)));
        Map<?, ?> validAction = support.payloads.decode(
                support.payloads.encode(
                        input("stale-schema", ScheduleContracts.TargetKind.ACTION, "javaclaw.safe", "refresh", 2)),
                Map.class);
        Map<Object, Object> staleSchema = new LinkedHashMap<>(validAction);
        staleSchema.put("targetSchemaHash", "f".repeat(64));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(
                        support.request("definition/create", staleSchema, Optional.of("stale-schema"), 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "definition/create",
                        input(
                                "missing-action-argument",
                                ScheduleContracts.TargetKind.ACTION,
                                "javaclaw.safe",
                                "parameterized",
                                0),
                        Optional.of("missing-action-argument"),
                        0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "definition/create",
                        input(
                                "wrong-action-type",
                                ScheduleContracts.TargetKind.ACTION,
                                "javaclaw.safe",
                                "parameterized",
                                0,
                                List.of(new ScheduleActionContracts.Argument(
                                        "scope", ScheduleActionContracts.ValueType.BOOLEAN, "true"))),
                        Optional.of("wrong-action-type"),
                        0)));
    }

    @Test
    void editUsesSourceRevisionAndRejectsClientOwnedServerFields() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        support.scheduleTargets = new MutableCatalog();
        ScheduleExtension bundle = new ScheduleExtension();
        var started = support.start(bundle);
        bundle.jobExecutors(runtime(support));
        try {
            ScheduleManagementContracts.SaveRequest created =
                    turnInput("turn-schedule", "0 0 9 ? * MON-FRI", "Asia/Shanghai");
            started.command(support.request("definition/create", created, Optional.of("create-turn-schedule"), 0));
            ScheduleManagementContracts.SaveRequest updated =
                    turnInput("turn-schedule", "0 30 9 ? * MON-FRI", "Asia/Shanghai");
            ScheduleContracts.Definition saved = support.decode(
                    started.command(
                            support.request("definition/update", updated, Optional.of("update-turn-schedule"), 1)),
                    ScheduleContracts.Definition.class);
            assertEquals(2, saved.revision());
            assertEquals("0 30 9 ? * MON-FRI", saved.timing().cronExpression().orElseThrow());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> started.command(
                            support.request("definition/update", updated, Optional.of("stale-turn-schedule"), 1)));

            Map<?, ?> decoded = support.payloads.decode(support.payloads.encode(updated), Map.class);
            Map<Object, Object> malicious = new LinkedHashMap<>(decoded);
            malicious.put("revision", 999);
            assertThrows(
                    RuntimeException.class,
                    () -> started.command(
                            support.request("definition/update", malicious, Optional.of("forged-revision"), 2)));
            assertManagementViewUsesAuthorityBindings(started);
            assertFalse(started.contributions().stream()
                    .filter(ExtensionContributions.Command.class::isInstance)
                    .map(ExtensionContributions.Command.class::cast)
                    .anyMatch(command -> command.operations().contains("put")));
        } finally {
            bundle.close();
        }
    }

    private static void assertTargetCatalogIncludesParameterizedAction(
            BuiltinExtensionTestSupport.Started started, BuiltinExtensionTestSupport support) throws Exception {
        ViewQueryResult result = support.decode(
                started.query(support.request(
                        "definition/view.targets",
                        new ViewQueryRequest("scheduleTargets", Map.of(), "", 100, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
        String json = result.rows().toString();
        assertTrue(json.contains("plan-1"));
        assertTrue(json.contains("refresh"));
        assertTrue(json.contains("parameterized"));
        assertTrue(json.contains("actionArguments"));
    }

    private static void assertManagementViewUsesAuthorityBindings(BuiltinExtensionTestSupport.Started started) {
        ViewSchema schema = started.contributions().stream()
                .filter(ExtensionContributions.View.class::isInstance)
                .map(ExtensionContributions.View.class::cast)
                .filter(view -> view.contributionId().equals("schedule.management.view"))
                .map(ExtensionContributions.View::view)
                .findFirst()
                .orElseThrow();
        ViewSchema.Form edit = schema.nodes().stream()
                .filter(ViewSchema.Form.class::isInstance)
                .map(ViewSchema.Form.class::cast)
                .filter(form -> form.id().equals("schedule-edit"))
                .findFirst()
                .orElseThrow();
        assertFalse(edit.fields().stream().anyMatch(field -> field.name().equals("id")));
        assertTrue(
                edit.submit().expectedRevision()
                        instanceof com.javaclaw.extension.spi.ExpectedRevisionBinding.SourceRevision);
        assertEquals(
                List.of(
                        "id",
                        "profileId",
                        "profileRevision",
                        "targetKind",
                        "targetExtensionId",
                        "targetId",
                        "targetRevision",
                        "targetSchemaHash"),
                edit.submit().commandBindings().stream()
                        .map(com.javaclaw.extension.spi.ViewCommandBinding::argumentName)
                        .toList());
    }

    private static ScheduleManagementContracts.SaveRequest input(
            String id, ScheduleContracts.TargetKind kind, String extensionId, String targetId, long revision) {
        return input(id, kind, extensionId, targetId, revision, List.of());
    }

    private static ScheduleManagementContracts.SaveRequest input(
            String id,
            ScheduleContracts.TargetKind kind,
            String extensionId,
            String targetId,
            long revision,
            List<ScheduleActionContracts.Argument> arguments) {
        return new ScheduleManagementContracts.SaveRequest(
                id,
                id,
                true,
                ScheduleContracts.TimingKind.FIXED_INTERVAL,
                kind,
                extensionId,
                targetId,
                revision,
                kind == ScheduleContracts.TargetKind.ACTION ? actionSchemaHash(targetId) : "",
                Optional.empty(),
                Optional.empty(),
                Optional.of(60L),
                Optional.of(NOW.plusSeconds(600)),
                PROFILE.id(),
                PROFILE.revision(),
                id + " Thread",
                "执行目标",
                BUDGET.maximumTurns(),
                BUDGET.inputTokens(),
                BUDGET.outputTokens(),
                BUDGET.toolCalls(),
                arguments);
    }

    private static ScheduleManagementContracts.SaveRequest turnInput(String id, String cron, String zone) {
        return new ScheduleManagementContracts.SaveRequest(
                id,
                id,
                true,
                ScheduleContracts.TimingKind.CRON,
                ScheduleContracts.TargetKind.TURN_TEMPLATE,
                ScheduleManagementContracts.TURN_TEMPLATE_EXTENSION,
                ScheduleManagementContracts.TURN_TEMPLATE_ID,
                0,
                "",
                Optional.of(cron),
                Optional.of(zone),
                Optional.empty(),
                Optional.empty(),
                PROFILE.id(),
                PROFILE.revision(),
                id + " Thread",
                "执行目标",
                BUDGET.maximumTurns(),
                BUDGET.inputTokens(),
                BUDGET.outputTokens(),
                BUDGET.toolCalls(),
                List.of());
    }

    private static String actionSchemaHash(String operation) {
        List<ScheduleTargetCatalogPort.ActionField> fields = "parameterized".equals(operation)
                ? List.of(new ScheduleTargetCatalogPort.ActionField(
                        "scope", "范围", ScheduleTargetCatalogPort.ScalarType.STRING, true))
                : List.of();
        return new ScheduleTargetCatalogPort.ActionOption("javaclaw.safe", operation, operation, fields, 0)
                .schemaHash();
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

    private static final class MutableCatalog implements ScheduleTargetCatalogPort {
        private final List<DefinitionOption> definitions =
                List.of(new DefinitionOption("javaclaw.plan", "plan-1", 4, "发布计划"));
        private final List<ActionOption> actions = List.of(
                new ActionOption("javaclaw.safe", "refresh", "刷新索引", List.of(), 2),
                new ActionOption(
                        "javaclaw.safe",
                        "parameterized",
                        "带参数动作",
                        List.of(new ActionField("scope", "范围", ScalarType.STRING, true)),
                        0));

        @Override
        public List<DefinitionOption> definitions(com.javaclaw.api.WorkspaceId workspaceId) {
            return definitions;
        }

        @Override
        public List<ActionOption> actions(com.javaclaw.api.WorkspaceId workspaceId) {
            return actions;
        }
    }
}
