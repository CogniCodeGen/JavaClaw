package com.javaclaw.builtin.extensions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.ScheduleActionContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.builtin.contracts.ScheduleManagementContracts;
import com.javaclaw.extension.spi.AutomationExecutionPolicyPort;
import com.javaclaw.extension.spi.AutomationRoleOption;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ScheduleTargetCatalogPort;
import com.javaclaw.extension.spi.ScheduledCommandPort;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleManagementBranchCoverageTest {
    private static final AgentRoleRef PROFILE = new AgentRoleRef("profile", 1);
    private static final OrchestrationContracts.ExecutionBudget BUDGET =
            new OrchestrationContracts.ExecutionBudget(4, 4_000, 2_000, 20);

    @Test
    void profileAndTargetViewsCoverPaginationValidationAndStaleCursors() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        ScheduleExtension bundle = new ScheduleExtension();
        BuiltinExtensionTestSupport.Started started = support.start(bundle);
        try {
            ExtensionExecutionContext context = copyContext(started.context(), profiles(support), catalog());
            ViewQueryResult firstProfiles = query(
                    started,
                    support,
                    context,
                    "definition/view.roles",
                    new ViewQueryRequest("roles", Map.of(), "", 1, Optional.empty()));
            ViewQueryResult secondProfiles = query(
                    started,
                    support,
                    context,
                    "definition/view.roles",
                    new ViewQueryRequest("roles", Map.of(), "profile-a", 2, Optional.empty()));
            ViewQueryResult firstTargets = query(
                    started,
                    support,
                    context,
                    "definition/view.targets",
                    new ViewQueryRequest("scheduleTargets", Map.of(), "", 2, Optional.empty()));
            ViewQueryResult secondTargets = query(
                    started,
                    support,
                    context,
                    "definition/view.targets",
                    new ViewQueryRequest(
                            "scheduleTargets",
                            Map.of(),
                            targetKey(firstTargets.rows().getLast(), support),
                            10,
                            Optional.empty()));

            assertTrue(firstProfiles.hasMore());
            assertEquals("profile-a", firstProfiles.nextCursor());
            assertFalse(secondProfiles.hasMore());
            assertEquals(2, secondProfiles.rows().size());
            assertTrue(firstTargets.hasMore());
            assertFalse(secondTargets.hasMore());
            assertTrue(firstTargets.revision() >= 1);

            assertViewFailures(started, support, context);
        } finally {
            bundle.close();
        }
    }

    @Test
    void selectedEditorCoversEveryTargetAndTimingShape() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        ScheduleExtension bundle = new ScheduleExtension();
        BuiltinExtensionTestSupport.Started started = support.start(bundle);
        try {
            put(support, definition("turn-fixed", ScheduleContracts.Timing.fixed(Duration.ofMinutes(5), NOW), turn()));
            put(
                    support,
                    definition(
                            "turn-cron", ScheduleContracts.Timing.cron("0 0 8 ? * MON-FRI", "Asia/Shanghai"), turn()));
            put(
                    support,
                    definition(
                            "definition",
                            ScheduleContracts.Timing.fixed(Duration.ofMinutes(5), NOW),
                            definitionTarget()));
            put(
                    support,
                    definition("action", ScheduleContracts.Timing.fixed(Duration.ofMinutes(5), NOW), action(false)));
            put(
                    support,
                    definition(
                            "action-parameters",
                            ScheduleContracts.Timing.fixed(Duration.ofMinutes(5), NOW),
                            action(true)));

            Map<?, ?> fixed = editor(started, support, "turn-fixed");
            Map<?, ?> cron = editor(started, support, "turn-cron");
            Map<?, ?> definition = editor(started, support, "definition");
            Map<?, ?> action = editor(started, support, "action");
            Map<?, ?> parameterized = editor(started, support, "action-parameters");

            assertEquals(5, ((Number) fixed.get("intervalMinutes")).intValue());
            assertEquals(null, fixed.get("zoneId"));
            assertEquals("Asia/Shanghai", cron.get("zoneId"));
            assertEquals(null, cron.get("intervalMinutes"));
            assertEquals("按所选 Definition 精确版本执行", definition.get("instruction"));
            assertEquals("调用固定参数 SchedulableAction", action.get("instruction"));
            assertEquals(1, ((Number) action.get("maximumTurns")).intValue());
            assertEquals(List.of(), action.get("actionArguments"));
            List<?> actionArguments = (List<?>) parameterized.get("actionArguments");
            assertEquals("all", ((Map<?, ?>) actionArguments.getFirst()).get("value"));
        } finally {
            bundle.close();
        }
    }

    @Test
    void saveRejectsUnknownOperationsMissingAuthorityAndEveryRevisionMismatch() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        support.scheduleTargets = catalog();
        ScheduleExtension bundle = new ScheduleExtension();
        BuiltinExtensionTestSupport.Started started = support.start(bundle);
        bundle.jobExecutors(runtime(support));
        try {
            ExtensionContributions.Command command = contribution(
                    started.contributions(), ExtensionContributions.Command.class, "schedule.management.command");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> command.handler()
                            .handle(
                                    support.request("unknown", Map.of(), Optional.of("unknown"), 0),
                                    started.context()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> started.command(
                            support.request("definition/create", turnInput("missing-key"), Optional.empty(), 0)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> started.command(support.request(
                            "definition/create",
                            input(
                                    "missing-profile",
                                    "missing",
                                    1,
                                    ScheduleManagementContracts.TURN_TEMPLATE_EXTENSION,
                                    ScheduleManagementContracts.TURN_TEMPLATE_ID,
                                    0),
                            Optional.of("missing-profile"),
                            0)));

            assertTurnCatalogMismatches(started, support);
            assertRevisionMismatches(started, support);
        } finally {
            bundle.close();
        }
    }

    @Test
    void managementHandlersRejectUnknownQueriesAndMalformedEditorRequests() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        ScheduleExtension bundle = new ScheduleExtension();
        BuiltinExtensionTestSupport.Started started = support.start(bundle);
        try {
            ExtensionContributions.Query query = contribution(
                    started.contributions(), ExtensionContributions.Query.class, "schedule.management.query");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> query.handler()
                            .handle(support.request("unknown", Map.of(), Optional.empty(), 0), started.context()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> query.handler()
                            .handle(
                                    support.request(
                                            "definition/view.new",
                                            new ViewQueryRequest("wrong", Map.of(), "", 1, Optional.empty()),
                                            Optional.empty(),
                                            0),
                                    started.context()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> query.handler()
                            .handle(
                                    support.request(
                                            "definition/view.new",
                                            new ViewQueryRequest(
                                                    "newDefinition", Map.of("id", "x"), "", 1, Optional.empty()),
                                            Optional.empty(),
                                            0),
                                    started.context()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> query.handler()
                            .handle(
                                    support.request(
                                            "definition/view.new",
                                            new ViewQueryRequest(
                                                    "newDefinition", Map.of(), "cursor", 1, Optional.empty()),
                                            Optional.empty(),
                                            0),
                                    started.context()));
            assertSelectedFailures(query, support, started.context());
        } finally {
            bundle.close();
        }
    }

    private static void assertViewFailures(
            BuiltinExtensionTestSupport.Started started,
            BuiltinExtensionTestSupport support,
            ExtensionExecutionContext context) {
        assertThrows(
                IllegalArgumentException.class,
                () -> query(started, support, context, "definition/view.roles", view("wrong", Map.of(), "")));
        assertThrows(
                IllegalArgumentException.class,
                () -> query(started, support, context, "definition/view.roles", view("roles", Map.of("x", "y"), "")));
        assertThrows(
                IllegalArgumentException.class,
                () -> query(started, support, context, "definition/view.roles", view("roles", Map.of(), "stale")));
        assertThrows(
                IllegalArgumentException.class,
                () -> query(started, support, context, "definition/view.targets", view("wrong", Map.of(), "")));
        assertThrows(
                IllegalArgumentException.class,
                () -> query(
                        started,
                        support,
                        context,
                        "definition/view.targets",
                        view("scheduleTargets", Map.of("x", "y"), "")));
        assertThrows(
                IllegalArgumentException.class,
                () -> query(
                        started,
                        support,
                        context,
                        "definition/view.targets",
                        view("scheduleTargets", Map.of(), "stale")));
    }

    private static void assertTurnCatalogMismatches(
            BuiltinExtensionTestSupport.Started started, BuiltinExtensionTestSupport support) {
        List<ScheduleManagementContracts.SaveRequest> invalid = List.of(
                input(
                        "bad-extension",
                        PROFILE.id(),
                        PROFILE.revision(),
                        "other",
                        ScheduleManagementContracts.TURN_TEMPLATE_ID,
                        0),
                input(
                        "bad-id",
                        PROFILE.id(),
                        PROFILE.revision(),
                        ScheduleManagementContracts.TURN_TEMPLATE_EXTENSION,
                        "other",
                        0),
                input(
                        "bad-revision",
                        PROFILE.id(),
                        PROFILE.revision(),
                        ScheduleManagementContracts.TURN_TEMPLATE_EXTENSION,
                        ScheduleManagementContracts.TURN_TEMPLATE_ID,
                        2));
        invalid.forEach(input -> assertThrows(
                IllegalArgumentException.class,
                () -> started.command(
                        support.request("definition/create", input, Optional.of("invalid-" + input.id()), 0))));
    }

    private static void assertRevisionMismatches(
            BuiltinExtensionTestSupport.Started started, BuiltinExtensionTestSupport support) throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "definition/create", turnInput("nonzero-create"), Optional.of("nonzero-create"), 1)));
        started.command(support.request("definition/create", turnInput("existing"), Optional.of("create-existing"), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "definition/create", turnInput("existing"), Optional.of("duplicate-existing"), 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(
                        support.request("definition/update", turnInput("existing"), Optional.of("zero-update"), 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(
                        support.request("definition/update", turnInput("missing"), Optional.of("missing-update"), 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(
                        support.request("definition/update", turnInput("existing"), Optional.of("wrong-update"), 2)));
        ExtensionResponse updated = started.command(
                support.request("definition/update", turnInput("existing"), Optional.of("valid-update"), 1));
        assertEquals(2, updated.revision());
    }

    private static void assertSelectedFailures(
            ExtensionContributions.Query query,
            BuiltinExtensionTestSupport support,
            ExtensionExecutionContext context) {
        List<ViewQueryRequest> invalid = List.of(
                view("wrong", Map.of("id", "x", "revision", "1"), ""),
                view("definitionEditor", Map.of("id", "x"), ""),
                view("definitionEditor", Map.of("id", "x", "revision", "1"), "cursor"),
                view("definitionEditor", Map.of("id", "", "revision", "1"), ""),
                view("definitionEditor", Map.of("id", "x", "revision", "0"), ""),
                view("definitionEditor", Map.of("id", "x", "revision", "bad"), ""));
        invalid.forEach(value -> assertThrows(
                IllegalArgumentException.class,
                () -> query.handler()
                        .handle(support.request("definition/view.selected", value, Optional.empty(), 0), context)));
    }

    private static ViewQueryResult query(
            BuiltinExtensionTestSupport.Started started,
            BuiltinExtensionTestSupport support,
            ExtensionExecutionContext context,
            String operation,
            ViewQueryRequest request)
            throws Exception {
        ExtensionContributions.Query query =
                contribution(started.contributions(), ExtensionContributions.Query.class, "schedule.management.query");
        ExtensionRequest extensionRequest = support.request(operation, request, Optional.empty(), 0);
        return support.decode(query.handler().handle(extensionRequest, context), ViewQueryResult.class);
    }

    private static ViewQueryRequest view(String source, Map<String, String> arguments, String cursor) {
        return new ViewQueryRequest(source, arguments, cursor, 10, Optional.empty());
    }

    private static Map<?, ?> editor(
            BuiltinExtensionTestSupport.Started started, BuiltinExtensionTestSupport support, String id)
            throws Exception {
        ViewQueryResult result = query(
                started,
                support,
                started.context(),
                "definition/view.selected",
                view("definitionEditor", Map.of("id", id, "revision", "1"), ""));
        return support.payloads.decode(result.values(), Map.class);
    }

    private static void put(BuiltinExtensionTestSupport support, ScheduleContracts.Definition definition) {
        support.store.put("documents." + support.workspaceId, definition.id(), 0, support.payloads.encode(definition));
    }

    private static ScheduleContracts.Definition definition(
            String id, ScheduleContracts.Timing timing, ScheduleContracts.Target target) {
        return new ScheduleContracts.Definition(
                id,
                1,
                id,
                true,
                timing,
                target,
                ScheduleContracts.OverlapPolicy.SKIP_IF_RUNNING,
                ScheduleContracts.MisfirePolicy.DO_NOT_CATCH_UP,
                NOW);
    }

    private static ScheduleContracts.Target turn() {
        return ScheduleContracts.Target.turn(
                new ScheduleContracts.TurnTemplate(AutomationV6Fixtures.selection(PROFILE), "标题", "执行任务", BUDGET));
    }

    private static ScheduleContracts.Target definitionTarget() {
        return ScheduleContracts.Target.definition(new ScheduleContracts.DefinitionTarget(
                "javaclaw.plan", "plan", 3, AutomationV6Fixtures.selection(PROFILE), BUDGET));
    }

    private static ScheduleContracts.Target action(boolean parameterized) {
        List<ScheduleTargetCatalogPort.ActionField> fields = parameterized
                ? List.of(new ScheduleTargetCatalogPort.ActionField(
                        "scope", "范围", ScheduleTargetCatalogPort.ScalarType.STRING, true))
                : List.of();
        ScheduleTargetCatalogPort.ActionOption option =
                new ScheduleTargetCatalogPort.ActionOption("javaclaw.safe", "refresh", "刷新", fields, 0);
        List<ScheduleActionContracts.Argument> arguments = parameterized
                ? List.of(
                        new ScheduleActionContracts.Argument("scope", ScheduleActionContracts.ValueType.STRING, "all"))
                : List.of();
        return ScheduleContracts.Target.action(ScheduleActionParameters.target(option, arguments));
    }

    private static ScheduleManagementContracts.SaveRequest turnInput(String id) {
        return input(
                id,
                PROFILE.id(),
                PROFILE.revision(),
                ScheduleManagementContracts.TURN_TEMPLATE_EXTENSION,
                ScheduleManagementContracts.TURN_TEMPLATE_ID,
                0);
    }

    private static ScheduleManagementContracts.SaveRequest input(
            String id,
            String profileId,
            long profileRevision,
            String targetExtension,
            String targetId,
            long targetRevision) {
        return new ScheduleManagementContracts.SaveRequest(
                id,
                id,
                true,
                ScheduleContracts.TimingKind.FIXED_INTERVAL,
                ScheduleContracts.TargetKind.TURN_TEMPLATE,
                targetExtension,
                targetId,
                targetRevision,
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.of(5L),
                Optional.of(NOW.plusSeconds(60)),
                AutomationV6Fixtures.selection(new AgentRoleRef(profileId, profileRevision)),
                "标题",
                "执行任务",
                BUDGET.maximumTurns(),
                BUDGET.inputTokens(),
                BUDGET.outputTokens(),
                BUDGET.toolCalls(),
                List.of());
    }

    private static AutomationExecutionPolicyPort profiles(BuiltinExtensionTestSupport support) {
        List<AutomationRoleOption> values = List.of(
                new AutomationRoleOption(new AgentRoleRef("profile-a", 1), "A"),
                new AutomationRoleOption(new AgentRoleRef("profile-b", 2), "B"),
                new AutomationRoleOption(new AgentRoleRef("profile-c", 3), "C"));
        return new AutomationExecutionPolicyPort() {
            @Override
            public List<AutomationRoleOption> roles(WorkspaceId workspaceId) {
                return values;
            }

            @Override
            public AutomationExecutionSnapshot freeze(
                    WorkspaceId workspaceId, ExecutionOverrides execution, CancellationToken cancellation) {
                return support.executionSnapshot(execution.role().orElseThrow());
            }
        };
    }

    private static ScheduleTargetCatalogPort catalog() {
        return new ScheduleTargetCatalogPort() {
            @Override
            public List<DefinitionOption> definitions(WorkspaceId workspaceId) {
                return List.of(
                        new DefinitionOption("javaclaw.plan", "plan-a", 1, "计划 A"),
                        new DefinitionOption("javaclaw.plan", "plan-b", 2, "计划 B"));
            }

            @Override
            public List<ActionOption> actions(WorkspaceId workspaceId) {
                return List.of(
                        new ActionOption("javaclaw.safe", "refresh", "刷新", List.of(), 3),
                        new ActionOption(
                                "javaclaw.safe",
                                "parameterized",
                                "带参数",
                                List.of(new ActionField("scope", "范围", ScalarType.STRING, true)),
                                0));
            }
        };
    }

    private static String targetKey(com.javaclaw.api.CanonicalPayload row, BuiltinExtensionTestSupport support) {
        return String.valueOf(support.payloads.decode(row, Map.class).get("key"));
    }

    private static ExtensionExecutionContext copyContext(
            ExtensionExecutionContext source,
            AutomationExecutionPolicyPort profiles,
            ScheduleTargetCatalogPort targets) {
        return new ExtensionExecutionContext(
                source.extension(),
                source.workspaceId(),
                source.effectivePermissions(),
                source.cancellation(),
                source.clock(),
                source.managedStore(),
                source.turns(),
                profiles,
                targets,
                source.inputs(),
                source.jobs(),
                source.evidence(),
                source.attachments(),
                source.credentials(),
                source.privateNetworkGrants(),
                source.services(),
                source.embeddings(),
                com.javaclaw.extension.spi.WorkspaceExecutionPort.denied(),
                com.javaclaw.extension.spi.ScheduleDefinitionBindingPort.unavailable());
    }

    private static ExtensionJobRuntimeContext runtime(BuiltinExtensionTestSupport support) {
        return new ExtensionJobRuntimeContext(
                support.clock,
                support.payloads,
                support.turns,
                support.store,
                invocation -> {
                    throw new IllegalStateException("测试未配置隔离服务");
                },
                support.embeddings,
                AutomationStepPort.unavailable(),
                ScheduledCommandPort.unavailable(),
                (workspaceId, required) -> {},
                com.javaclaw.extension.spi.ConversationEvidencePort.unavailable(),
                com.javaclaw.extension.spi.ScheduleDefinitionBindingPort.unavailable());
    }

    private static <T extends ExtensionContribution> T contribution(
            List<ExtensionContribution> contributions, Class<T> type, String id) {
        return contributions.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(value -> value.contributionId().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
