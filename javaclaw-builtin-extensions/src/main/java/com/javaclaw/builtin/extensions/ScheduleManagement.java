package com.javaclaw.builtin.extensions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.builtin.contracts.ScheduleActionContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.builtin.contracts.ScheduleManagementContracts;
import com.javaclaw.extension.spi.AutomationRoleOption;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.ScheduleTargetCatalogPort;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewArgumentBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

/** Schedule 的强类型新建、权威 Role 选择、乐观锁编辑与触发预览管理纵切。 */
final class ScheduleManagement {
    static final String PREVIEW_VIEW = "preview/view.list";
    static final String OCCURRENCE_RUN = "occurrence/run";

    private static final String CREATE = "definition/create";
    private static final String UPDATE = "definition/update";
    private static final String VIEW_NEW = "definition/view.new";
    private static final String VIEW_SELECTED = "definition/view.selected";
    private static final String VIEW_ROLES = "definition/view.roles";
    private static final String VIEW_TARGETS = "definition/view.targets";
    private static final String NEW_SOURCE = "newDefinition";
    private static final String EDIT_SOURCE = "definitionEditor";
    private static final String ROLE_SOURCE = "roles";
    private static final String TARGET_SOURCE = "scheduleTargets";

    private final ManagedDocumentResource<ScheduleContracts.Definition> documents;
    private final ScheduleDefinitionLifecycle lifecycle;
    private final DefinitionManagementSupport<ScheduleContracts.Definition> support;

    ScheduleManagement(
            ManagedDocumentResource<ScheduleContracts.Definition> documents, ScheduleDefinitionLifecycle lifecycle) {
        this.documents = java.util.Objects.requireNonNull(documents, "documents");
        this.lifecycle = java.util.Objects.requireNonNull(lifecycle, "lifecycle");
        support = new DefinitionManagementSupport<>(documents);
    }

    List<ExtensionContribution> contributions() {
        return List.of(
                new ExtensionContributions.Query(
                        "schedule.management.query",
                        Set.of(
                                VIEW_NEW,
                                VIEW_SELECTED,
                                VIEW_ROLES,
                                VIEW_TARGETS,
                                AutomationSelectionView.PROVIDERS,
                                AutomationSelectionView.PERMISSIONS),
                        this::query),
                new ExtensionContributions.Command(
                        "schedule.management.command",
                        Set.of(CREATE, UPDATE, "definition/form/create", "definition/form/update"),
                        this::save),
                new ExtensionContributions.View("schedule.management.view", view()));
    }

    private ExtensionResponse query(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return switch (request.operation()) {
            case AutomationSelectionView.PROVIDERS, AutomationSelectionView.PERMISSIONS ->
                AutomationSelectionView.query(request, context, documents.payloads());
            case VIEW_NEW -> support.newEditor(request, NEW_SOURCE);
            case VIEW_SELECTED -> support.selected(request, context, EDIT_SOURCE, this::editor);
            case VIEW_ROLES -> roles(request, context);
            case VIEW_TARGETS -> targets(request, context);
            default -> throw new IllegalArgumentException("unknown Schedule management query");
        };
    }

    private ExtensionResponse save(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        SaveMode mode =
                switch (request.operation()) {
                    case CREATE, "definition/form/create" -> SaveMode.CREATE;
                    case UPDATE, "definition/form/update" -> SaveMode.UPDATE;
                    default -> throw new IllegalArgumentException("unknown Schedule management command");
                };
        String key = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("Schedule save requires idempotency key"));
        CanonicalPayload digest = documents
                .payloads()
                .encode(new CommandDigest(request.operation(), request.expectedRevision(), request.payload()));
        ExtensionResponse response = context.managedStore()
                .inCommand(
                        documents.extensionId(),
                        request.operation(),
                        key,
                        digest.sha256(),
                        transaction -> persist(request, context, transaction, mode));
        lifecycle.afterManagedCommit(documents, context);
        return response;
    }

    private ExtensionResponse persist(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            SaveMode mode)
            throws Exception {
        ScheduleManagementContracts.SaveRequest input = request.operation().contains("/form/")
                ? documents
                        .payloads()
                        .decode(request.payload(), ScheduleFormSaveRequest.class)
                        .toRequest()
                : documents.payloads().decode(request.payload(), ScheduleManagementContracts.SaveRequest.class);
        requireExecution(input.execution(), request, context);
        ScheduleContracts.Target target = target(input, request, context);
        ScheduleContracts.Definition definition = input.definition(
                target,
                Math.addExact(request.expectedRevision(), 1),
                context.clock().instant());
        lifecycle.validateManaged(definition);
        new ScheduleDefinitionBindings(documents, lifecycle)
                .guardUserUpdate(transaction, request.workspaceId(), definition);
        requireRevision(transaction, request, definition.id(), mode);
        transaction.put(
                documents.documentCollection(request.workspaceId()),
                definition.id(),
                request.expectedRevision(),
                documents.payloads().encode(definition));
        ExtensionResponse response =
                new ExtensionResponse(documents.payloads().encode(definition), definition.revision());
        lifecycle.afterManagedMutation(documents, request.workspaceId(), definition, transaction);
        return response;
    }

    private ScheduleContracts.Target target(
            ScheduleManagementContracts.SaveRequest input, ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        return switch (input.targetKind()) {
            case TURN_TEMPLATE -> turnTarget(input);
            case DEFINITION -> definitionTarget(input, request, context);
            case ACTION -> actionTarget(input, request, context);
        };
    }

    private ScheduleContracts.Target turnTarget(ScheduleManagementContracts.SaveRequest input) {
        requireNoActionArguments(input);
        if (!ScheduleManagementContracts.TURN_TEMPLATE_EXTENSION.equals(input.targetExtensionId())
                || !ScheduleManagementContracts.TURN_TEMPLATE_ID.equals(input.targetId())
                || input.targetRevision() != 0) {
            throw new IllegalArgumentException("TurnTemplate target does not match the platform catalog");
        }
        ScheduleContracts.TurnTemplate template = new ScheduleContracts.TurnTemplate(
                input.execution(), input.title(), input.instruction(), input.budget());
        return ScheduleContracts.Target.turn(template);
    }

    private ScheduleContracts.Target definitionTarget(
            ScheduleManagementContracts.SaveRequest input, ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        requireNoActionArguments(input);
        ScheduleTargetCatalogPort.DefinitionOption selected = context.scheduleTargets()
                .requireDefinition(
                        request.workspaceId(), input.targetExtensionId(), input.targetId(), input.targetRevision());
        ScheduleContracts.DefinitionTarget target = new ScheduleContracts.DefinitionTarget(
                selected.extensionId(),
                selected.definitionId(),
                selected.revision(),
                input.execution(),
                input.budget());
        return ScheduleContracts.Target.definition(target);
    }

    private ScheduleContracts.Target actionTarget(
            ScheduleManagementContracts.SaveRequest input, ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        ScheduleTargetCatalogPort.ActionOption selected = context.scheduleTargets()
                .requireAction(
                        request.workspaceId(), input.targetExtensionId(), input.targetId(), input.targetRevision());
        if (!selected.schemaHash().equals(input.targetSchemaHash())) {
            throw new IllegalArgumentException("SchedulableAction Schema 已改变，请刷新后重新确认固定参数");
        }
        return ScheduleContracts.Target.action(ScheduleActionParameters.target(selected, input.actionArguments()));
    }

    private static void requireNoActionArguments(ScheduleManagementContracts.SaveRequest input) {
        if (!input.actionArguments().isEmpty()) {
            throw new IllegalArgumentException("非 Action Schedule 不能保存 Action 参数");
        }
    }

    private void requireExecution(
            ExecutionOverrides selected, ExtensionRequest request, ExtensionExecutionContext context) {
        boolean present = context.executionPolicies().roles(request.workspaceId()).stream()
                .map(AutomationRoleOption::role)
                .anyMatch(role -> selected.role().isEmpty()
                        || selected.role().orElseThrow().equals(role));
        if (!present) {
            throw new IllegalArgumentException("selected Agent Role revision is not available");
        }
    }

    private void requireRevision(ExtensionTransaction transaction, ExtensionRequest request, String id, SaveMode mode) {
        Optional<VersionedDocument> current = transaction.get(documents.documentCollection(request.workspaceId()), id);
        boolean valid =
                switch (mode) {
                    case CREATE -> request.expectedRevision() == 0 && current.isEmpty();
                    case UPDATE ->
                        request.expectedRevision() > 0
                                && current.map(VersionedDocument::revision)
                                        .filter(revision -> revision == request.expectedRevision())
                                        .isPresent();
                };
        if (!valid) {
            throw new IllegalArgumentException("Schedule Definition revision changed");
        }
    }

    private ExtensionResponse roles(ExtensionRequest request, ExtensionExecutionContext context) {
        ViewQueryRequest query = documents.payloads().decode(request.payload(), ViewQueryRequest.class);
        if (!ROLE_SOURCE.equals(query.dataSourceId()) || !query.arguments().isEmpty()) {
            throw new IllegalArgumentException("Schedule Role view does not accept arguments");
        }
        List<RoleRow> available = context.executionPolicies().roles(request.workspaceId()).stream()
                .map(RoleRow::from)
                .toList();
        int start = pageStart(available, query.cursor());
        int end = Math.min(available.size(), Math.addExact(start, query.limit()));
        List<RoleRow> page = available.subList(start, end);
        boolean hasMore = end < available.size();
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                page.stream().map(documents.payloads()::encode).toList(),
                documents.payloads().encode(Map.of()),
                hasMore && !page.isEmpty() ? page.getLast().id() : "",
                hasMore,
                page.stream().mapToLong(RoleRow::revision).max().orElse(0));
        return new ExtensionResponse(documents.payloads().encode(result), result.revision());
    }

    private ExtensionResponse targets(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ViewQueryRequest query = documents.payloads().decode(request.payload(), ViewQueryRequest.class);
        if (!TARGET_SOURCE.equals(query.dataSourceId()) || !query.arguments().isEmpty()) {
            throw new IllegalArgumentException("Schedule target view does not accept arguments");
        }
        List<TargetRow> available = new ArrayList<>();
        available.add(TargetRow.turnTemplate());
        context.scheduleTargets().definitions(request.workspaceId()).stream()
                .map(TargetRow::definition)
                .forEach(available::add);
        context.scheduleTargets().actions(request.workspaceId()).stream()
                .map(TargetRow::action)
                .forEach(available::add);
        int start = targetPageStart(available, query.cursor());
        int end = Math.min(available.size(), Math.addExact(start, query.limit()));
        List<TargetRow> page = available.subList(start, end);
        boolean hasMore = end < available.size();
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                page.stream().map(documents.payloads()::encode).toList(),
                documents.payloads().encode(Map.of()),
                hasMore && !page.isEmpty() ? page.getLast().key() : "",
                hasMore,
                page.stream().mapToLong(TargetRow::targetRevision).max().orElse(0));
        return new ExtensionResponse(documents.payloads().encode(result), result.revision());
    }

    private static int pageStart(List<RoleRow> roles, String cursor) {
        if (cursor.isEmpty()) {
            return 0;
        }
        for (int index = 0; index < roles.size(); index++) {
            if (roles.get(index).id().equals(cursor)) {
                return index + 1;
            }
        }
        throw new IllegalArgumentException("Schedule Role view cursor is stale");
    }

    private static int targetPageStart(List<TargetRow> targets, String cursor) {
        if (cursor.isEmpty()) {
            return 0;
        }
        for (int index = 0; index < targets.size(); index++) {
            if (targets.get(index).key().equals(cursor)) {
                return index + 1;
            }
        }
        throw new IllegalArgumentException("Schedule target view cursor is stale");
    }

    private ScheduleEditor editor(ScheduleContracts.Definition definition) {
        ScheduleContracts.Timing timing = definition.timing();
        EditableTarget target = editableTarget(definition);
        return new ScheduleEditor(
                definition.id(),
                definition.name(),
                definition.enabled(),
                timing.kind(),
                timing.cronExpression(),
                timing.kind() == ScheduleContracts.TimingKind.CRON ? Optional.of(timing.zoneId()) : Optional.empty(),
                timing.interval().map(Duration::toMinutes),
                timing.firstFireAt(),
                target.title(),
                target.instruction(),
                target.budget().maximumTurns(),
                target.budget().inputTokens(),
                target.budget().outputTokens(),
                target.budget().toolCalls(),
                target.actionArguments());
    }

    private static EditableTarget editableTarget(ScheduleContracts.Definition definition) {
        if (definition.target().turnTemplate().isPresent()) {
            ScheduleContracts.TurnTemplate template =
                    definition.target().turnTemplate().orElseThrow();
            return new EditableTarget(template.title(), template.instruction(), template.budget(), List.of());
        }
        if (definition.target().definition().isPresent()) {
            ScheduleContracts.DefinitionTarget target =
                    definition.target().definition().orElseThrow();
            return new EditableTarget(definition.name(), "按所选 Definition 精确版本执行", target.budget(), List.of());
        }
        ScheduleActionContracts.Target action = definition.target().action().orElseThrow();
        return new EditableTarget(
                definition.name(),
                "调用固定参数 SchedulableAction",
                new com.javaclaw.builtin.contracts.OrchestrationContracts.ExecutionBudget(1, 1, 1, 1),
                action.arguments());
    }

    private ViewSchema view() {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                documents.extensionId().value() + ".management",
                "定时任务管理",
                sources(),
                AutomationSelectionView.withTables(List.of(
                        definitionsTable(),
                        targetTable(),
                        roleTable(),
                        ScheduleManagementForm.create(
                                "schedule-create", "新建 Schedule", NEW_SOURCE, TARGET_SOURCE, true),
                        ScheduleManagementForm.create("schedule-edit", "编辑 Schedule", EDIT_SOURCE, EDIT_SOURCE, false),
                        previewTable())));
    }

    private List<ViewDataSource> sources() {
        return AutomationSelectionView.withSources(List.of(
                new ViewDataSource("documents", "view.list", Map.of(), List.of(), 100),
                new ViewDataSource(NEW_SOURCE, VIEW_NEW, Map.of(), List.of(), 1),
                new ViewDataSource(
                        EDIT_SOURCE,
                        VIEW_SELECTED,
                        Map.of(),
                        List.of(
                                new ViewArgumentBinding("id", "documents", "id"),
                                new ViewArgumentBinding("revision", "documents", "revision")),
                        1),
                new ViewDataSource(ROLE_SOURCE, VIEW_ROLES, Map.of(), List.of(), 100),
                new ViewDataSource(TARGET_SOURCE, VIEW_TARGETS, Map.of(), List.of(), 100),
                new ViewDataSource(
                        "preview",
                        PREVIEW_VIEW,
                        Map.of(),
                        List.of(
                                new ViewArgumentBinding("scheduleId", "documents", "id"),
                                new ViewArgumentBinding("scheduleRevision", "documents", "revision")),
                        5)));
    }

    private ViewSchema.Table definitionsTable() {
        ViewAction run = new ViewAction(
                "立即运行",
                OCCURRENCE_RUN,
                Map.of(),
                Map.of("scheduleId", "id"),
                new ExpectedRevisionBinding.RowField("revision"),
                false);
        ViewAction delete = new ViewAction(
                "删除", "delete", Map.of(), Map.of("id", "id"), new ExpectedRevisionBinding.RowField("revision"), true);
        return new ViewSchema.Table(
                "definitions",
                "Schedule",
                "documents",
                "id",
                List.of(
                        new ViewSchema.Column("name", "名称", Optional.of(220)),
                        new ViewSchema.Column("enabled", "启用", Optional.of(80)),
                        new ViewSchema.Column("revision", "版本", Optional.of(80)),
                        new ViewSchema.Column("updatedAt", "更新时间", Optional.of(180))),
                ViewSelectionMode.SINGLE,
                List.of(run, delete));
    }

    private ViewSchema.Table roleTable() {
        return new ViewSchema.Table(
                "roles",
                "选择权威 Agent Role",
                ROLE_SOURCE,
                "id",
                List.of(
                        new ViewSchema.Column("name", "Agent", Optional.of(220)),
                        new ViewSchema.Column("id", "标识", Optional.of(200)),
                        new ViewSchema.Column("revision", "版本", Optional.of(80))),
                ViewSelectionMode.SINGLE,
                List.of());
    }

    private ViewSchema.Table targetTable() {
        return new ViewSchema.Table(
                "scheduleTargets",
                "选择权威目标",
                TARGET_SOURCE,
                "key",
                List.of(
                        new ViewSchema.Column("name", "目标", Optional.of(260)),
                        new ViewSchema.Column("targetKind", "类别", Optional.of(120)),
                        new ViewSchema.Column("targetRevision", "版本", Optional.of(80)),
                        new ViewSchema.Column("parameterCount", "固定参数", Optional.of(90))),
                ViewSelectionMode.SINGLE,
                List.of());
    }

    private ViewSchema.Table previewTable() {
        return new ViewSchema.Table(
                "preview",
                "未来五次触发",
                "preview",
                "sequence",
                List.of(
                        new ViewSchema.Column("sequence", "序号", Optional.of(70)),
                        new ViewSchema.Column("scheduledFor", "触发时间", Optional.of(220))),
                ViewSelectionMode.NONE,
                List.of());
    }

    private enum SaveMode {
        CREATE,
        UPDATE
    }

    private record RoleRow(String id, long revision, String name, com.javaclaw.api.AgentRoleRef role) {
        private static RoleRow from(AutomationRoleOption option) {
            return new RoleRow(option.role().id(), option.role().revision(), option.displayName(), option.role());
        }
    }

    private record TargetRow(
            String key,
            ScheduleContracts.TargetKind targetKind,
            String targetExtensionId,
            String targetId,
            long targetRevision,
            String name,
            String actionSchemaHash,
            int parameterCount,
            List<ScheduleActionContracts.Argument> actionArguments) {
        private static TargetRow turnTemplate() {
            return new TargetRow(
                    "turn-template",
                    ScheduleContracts.TargetKind.TURN_TEMPLATE,
                    ScheduleManagementContracts.TURN_TEMPLATE_EXTENSION,
                    ScheduleManagementContracts.TURN_TEMPLATE_ID,
                    0,
                    "新建 Agent Turn",
                    "",
                    0,
                    List.of());
        }

        private static TargetRow definition(ScheduleTargetCatalogPort.DefinitionOption option) {
            return new TargetRow(
                    "definition:" + option.extensionId() + ":" + option.definitionId() + ":" + option.revision(),
                    ScheduleContracts.TargetKind.DEFINITION,
                    option.extensionId(),
                    option.definitionId(),
                    option.revision(),
                    option.displayName(),
                    "",
                    0,
                    List.of());
        }

        private static TargetRow action(ScheduleTargetCatalogPort.ActionOption option) {
            return new TargetRow(
                    "action:" + option.extensionId() + ":" + option.operation() + ":" + option.expectedRevision(),
                    ScheduleContracts.TargetKind.ACTION,
                    option.extensionId(),
                    option.operation(),
                    option.expectedRevision(),
                    option.displayName(),
                    option.schemaHash(),
                    option.fields().size(),
                    ScheduleActionParameters.emptyArguments(option));
        }
    }

    private record EditableTarget(
            String title,
            String instruction,
            com.javaclaw.builtin.contracts.OrchestrationContracts.ExecutionBudget budget,
            List<ScheduleActionContracts.Argument> actionArguments) {}

    private record ScheduleEditor(
            String id,
            String name,
            boolean enabled,
            ScheduleContracts.TimingKind timingKind,
            Optional<String> cronExpression,
            Optional<String> zoneId,
            Optional<Long> intervalMinutes,
            Optional<java.time.Instant> firstFireAt,
            String title,
            String instruction,
            int maximumTurns,
            long inputTokens,
            long outputTokens,
            int toolCalls,
            List<ScheduleActionContracts.Argument> actionArguments) {}

    private record CommandDigest(String operation, long expectedRevision, CanonicalPayload payload) {}
}
