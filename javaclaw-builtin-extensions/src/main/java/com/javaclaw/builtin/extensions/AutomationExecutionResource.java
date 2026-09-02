package com.javaclaw.builtin.extensions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.VersionedExtensionDocument;
import com.javaclaw.extension.spi.AutomationProfileOption;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobExecutor;
import com.javaclaw.extension.spi.ExtensionJobMutation;
import com.javaclaw.extension.spi.ExtensionJobPage;
import com.javaclaw.extension.spi.ExtensionJobRegistration;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionJobSubmission;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.extension.spi.ScheduleTargetCatalogPort;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewArgumentBinding;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCommandBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

/** 可恢复 Definition Execution 的组合资源。 */
final class AutomationExecutionResource<T extends VersionedExtensionDocument> {
    static final String JOB_TYPE = "definition-execution";
    private static final String MANAGEMENT_START = "execution/management/start";
    private static final String DEFINITION_VIEW = "execution/definition/view.selected";
    private static final String PROFILE_VIEW = "execution/profile/view.list";

    private final ManagedDocumentResource<T> documents;
    private final String displayName;
    private final Function<ExtensionJobRuntimeContext, ExtensionJobExecutor> executorFactory;
    private final Function<T, CanonicalPayload> initialCheckpointFactory;
    private final Consumer<T> startValidator;
    private final Function<ExtensionJob, Map<String, Object>> executionFields;

    AutomationExecutionResource(
            ManagedDocumentResource<T> documents,
            String displayName,
            Function<ExtensionJobRuntimeContext, ExtensionJobExecutor> executorFactory,
            Function<T, CanonicalPayload> initialCheckpointFactory) {
        this(documents, displayName, executorFactory, initialCheckpointFactory, ignored -> {});
    }

    AutomationExecutionResource(
            ManagedDocumentResource<T> documents,
            String displayName,
            Function<ExtensionJobRuntimeContext, ExtensionJobExecutor> executorFactory,
            Function<T, CanonicalPayload> initialCheckpointFactory,
            Consumer<T> startValidator) {
        this(documents, displayName, executorFactory, initialCheckpointFactory, startValidator, ignored -> Map.of());
    }

    AutomationExecutionResource(
            ManagedDocumentResource<T> documents,
            String displayName,
            Function<ExtensionJobRuntimeContext, ExtensionJobExecutor> executorFactory,
            Function<T, CanonicalPayload> initialCheckpointFactory,
            Consumer<T> startValidator,
            Function<ExtensionJob, Map<String, Object>> executionFields) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory");
        this.initialCheckpointFactory = Objects.requireNonNull(initialCheckpointFactory, "initialCheckpointFactory");
        this.startValidator = Objects.requireNonNull(startValidator, "startValidator");
        this.executionFields = Objects.requireNonNull(executionFields, "executionFields");
    }

    List<ExtensionContribution> contributions(List<ExtensionContribution> additionalContributions) {
        return contributions(additionalContributions, List.of());
    }

    List<ExtensionContribution> contributions(
            List<ExtensionContribution> additionalContributions, List<ViewAction> executionActions) {
        List<ExtensionContribution> contributions = new ArrayList<>();
        contributions.add(new ExtensionContributions.Orchestrator(
                "execution.start", Set.of("execution/start", MANAGEMENT_START), this::startExecution));
        contributions.add(new ExtensionContributions.SchedulableAction(
                "execution.start.schedulable", "execution/start", displayName + "执行", false, List.of(), 0));
        contributions.add(new ExtensionContributions.SchedulableDefinition(
                "execution.definition.schedulable", displayName, this::schedulableDefinitions));
        contributions.add(new ExtensionContributions.Query(
                "execution.query", Set.of("execution/view.list", DEFINITION_VIEW, PROFILE_VIEW), this::viewQuery));
        contributions.add(new ExtensionContributions.View("execution.view", executionView(executionActions)));
        contributions.addAll(List.copyOf(additionalContributions));
        return List.copyOf(contributions);
    }

    private List<ScheduleTargetCatalogPort.DefinitionEntry> schedulableDefinitions(ExtensionExecutionContext context)
            throws Exception {
        return documents.documents(context).stream()
                .map(definition -> new ScheduleTargetCatalogPort.DefinitionEntry(
                        definition.id(), definition.revision(), definition.id()))
                .toList();
    }

    List<ExtensionJobRegistration> jobExecutors(ExtensionJobRuntimeContext context) {
        return List.of(new ExtensionJobRegistration(JOB_TYPE, executorFactory.apply(context)));
    }

    List<ExtensionSchema> schemas() {
        Map<String, Object> profile = ContractSchemaFactory.object(
                Map.of(
                        "id", ContractSchemaFactory.string(),
                        "revision", ContractSchemaFactory.integer(1)),
                List.of("id", "revision"));
        Map<String, Object> budget = ContractSchemaFactory.object(
                Map.of(
                        "maximumTurns", ContractSchemaFactory.boundedInteger(1, 10_000),
                        "inputTokens", ContractSchemaFactory.integer(1),
                        "outputTokens", ContractSchemaFactory.integer(1),
                        "toolCalls", ContractSchemaFactory.integer(1)),
                List.of("maximumTurns", "inputTokens", "outputTokens", "toolCalls"));
        CanonicalPayload startSchema = payloads()
                .encode(Map.of(
                        "$schema",
                        "https://json-schema.org/draft/2020-12/schema",
                        "$id",
                        extensionId().value() + "/execution-start/v1",
                        "additionalProperties",
                        false,
                        "properties",
                        Map.of(
                                "definitionId", Map.of("minLength", 1, "type", "string"),
                                "profile", profile,
                                "budget", budget),
                        "required",
                        List.of("definitionId", "profile", "budget"),
                        "type",
                        "object"));
        return List.of(new ExtensionSchema(extensionId().value() + "/execution-start/v1", startSchema));
    }

    private ExtensionResponse startExecution(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        OrchestrationContracts.StartRequest start = decodeStart(request);
        String key = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("execution/start requires idempotency key"));
        CanonicalPayload identity = payloads().encode(JobSubmissionIdentity.from(extensionId(), request));
        ExtensionJob job = context.jobs()
                .submit(identity, new ExtensionJobMutation(key, 0), () -> submission(request, context, start));
        return new ExtensionResponse(payloads().encode(ExtensionExecutionReceipt.from(job)), job.revision());
    }

    private OrchestrationContracts.StartRequest decodeStart(ExtensionRequest request) {
        if (MANAGEMENT_START.equals(request.operation())) {
            return payloads()
                    .decode(request.payload(), OrchestrationContracts.ManagementStartRequest.class)
                    .toStartRequest();
        }
        return payloads().decode(request.payload(), OrchestrationContracts.StartRequest.class);
    }

    private ExtensionJobSubmission submission(
            ExtensionRequest request, ExtensionExecutionContext context, OrchestrationContracts.StartRequest start)
            throws Exception {
        T definition = documents.requireDocument(request, start.definitionId(), context);
        startValidator.accept(definition);
        var platform =
                context.executionPolicies().freeze(request.workspaceId(), start.profile(), context.cancellation());
        if (request.unattendedExecutionScope().isPresent()) {
            platform = platform.withUnattendedExecutionScope(
                    request.unattendedExecutionScope().orElseThrow());
        }
        OrchestrationContracts.FrozenExecution frozen = new OrchestrationContracts.FrozenExecution(
                platform, request.threadId(), payloads().encode(definition), start.budget());
        OrchestrationContracts.ExecutionCheckpoint checkpoint = new OrchestrationContracts.ExecutionCheckpoint(
                initialCheckpointFactory.apply(definition), OrchestrationContracts.ExecutionConsumption.zero());
        return new ExtensionJobSubmission(
                extensionId(),
                request.workspaceId(),
                JOB_TYPE,
                definition.id(),
                definition.revision(),
                payloads().encode(frozen),
                payloads().encode(checkpoint));
    }

    private ExtensionResponse viewQuery(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return switch (request.operation()) {
            case "execution/view.list" -> viewExecutions(request, context);
            case DEFINITION_VIEW -> viewDefinition(request, context);
            case PROFILE_VIEW -> viewProfiles(request, context);
            default -> throw new IllegalArgumentException("unknown execution view operation");
        };
    }

    private ExtensionResponse viewExecutions(ExtensionRequest request, ExtensionExecutionContext context) {
        ViewQueryRequest query = payloads().decode(request.payload(), ViewQueryRequest.class);
        if (!"executions".equals(query.dataSourceId()) || !query.arguments().isEmpty()) {
            throw new IllegalArgumentException("unknown execution view data source");
        }
        ExtensionJobPage page = context.jobs()
                .page(
                        Optional.of(request.workspaceId()),
                        Optional.of(extensionId()),
                        Set.of(),
                        ExtensionJobCursors.decode(query.cursor()),
                        query.limit());
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                page.jobs().stream()
                        .map(this::executionRow)
                        .map(payloads()::encode)
                        .toList(),
                payloads().encode(Map.of()),
                page.nextCursor().map(ExtensionJobCursors::encode).orElse(""),
                page.nextCursor().isPresent(),
                page.jobs().stream().mapToLong(ExtensionJob::revision).max().orElse(0));
        return new ExtensionResponse(payloads().encode(result), result.revision());
    }

    private Map<String, Object> executionRow(ExtensionJob job) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("id", job.id());
        row.put("state", job.state().name());
        row.put("definitionRevision", job.definitionRevision());
        row.put("revision", job.revision());
        row.put("updatedAt", job.updatedAt().toString());
        executionFields.apply(job).forEach((name, value) -> addExecutionField(row, name, value));
        return Map.copyOf(row);
    }

    private static void addExecutionField(Map<String, Object> row, String name, Object value) {
        String field = Objects.requireNonNull(name, "execution field").strip();
        if (field.isEmpty() || row.containsKey(field)) {
            throw new IllegalArgumentException("execution view field is blank or overlaps a core field");
        }
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
            throw new IllegalArgumentException("execution view fields must be scalar values");
        }
        row.put(field, value);
    }

    private ExtensionResponse viewProfiles(ExtensionRequest request, ExtensionExecutionContext context) {
        ViewQueryRequest query = payloads().decode(request.payload(), ViewQueryRequest.class);
        if (!"profiles".equals(query.dataSourceId()) || !query.arguments().isEmpty()) {
            throw new IllegalArgumentException("profile view does not accept arguments");
        }
        List<ProfileRow> available = context.executionPolicies().profiles(request.workspaceId()).stream()
                .map(ProfileRow::from)
                .toList();
        int start = profilePageStart(available, query.cursor());
        int end = Math.min(available.size(), Math.addExact(start, query.limit()));
        List<ProfileRow> rows = available.subList(start, end);
        boolean hasMore = end < available.size();
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                rows.stream().map(payloads()::encode).toList(),
                payloads().encode(Map.of()),
                hasMore && !rows.isEmpty() ? rows.getLast().id() : "",
                hasMore,
                rows.stream().mapToLong(ProfileRow::revision).max().orElse(0));
        return new ExtensionResponse(payloads().encode(result), result.revision());
    }

    private ExtensionResponse viewDefinition(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        ViewQueryRequest query = payloads().decode(request.payload(), ViewQueryRequest.class);
        if (!"executionDefinition".equals(query.dataSourceId())
                || !query.arguments().keySet().equals(Set.of("id", "revision"))
                || !query.cursor().isEmpty()) {
            throw new IllegalArgumentException("execution Definition view requires one exact selection");
        }
        long revision = parseRevision(query.arguments().get("revision"));
        T definition = documents.requireDocument(query.arguments().get("id"), revision, context);
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                List.of(),
                payloads().encode(Map.of("id", definition.id(), "revision", definition.revision())),
                "",
                false,
                definition.revision());
        return new ExtensionResponse(payloads().encode(result), result.revision());
    }

    private static long parseRevision(String value) {
        try {
            long revision = Long.parseLong(value);
            if (revision < 1) {
                throw new IllegalArgumentException("Definition revision must be positive");
            }
            return revision;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Definition revision is invalid", failure);
        }
    }

    private static int profilePageStart(List<ProfileRow> profiles, String cursor) {
        if (cursor.isEmpty()) {
            return 0;
        }
        for (int index = 0; index < profiles.size(); index++) {
            if (profiles.get(index).id().equals(cursor)) {
                return index + 1;
            }
        }
        throw new IllegalArgumentException("profile view cursor is stale");
    }

    private ViewSchema executionView(List<ViewAction> actions) {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                extensionId().value() + ".executions",
                displayName + "执行",
                executionSources(),
                List.of(definitionTable(), profileTable(), startForm(), executionTable(actions)));
    }

    private List<ViewDataSource> executionSources() {
        List<ViewArgumentBinding> selection = List.of(
                new ViewArgumentBinding("id", "documents", "id"),
                new ViewArgumentBinding("revision", "documents", "revision"));
        return List.of(
                new ViewDataSource("documents", "view.list", Map.of(), List.of(), 100),
                new ViewDataSource("executionDefinition", DEFINITION_VIEW, Map.of(), selection, 1),
                new ViewDataSource("profiles", PROFILE_VIEW, Map.of(), List.of(), 100),
                new ViewDataSource("executions", "execution/view.list", Map.of(), List.of(), 100));
    }

    private ViewSchema.Table definitionTable() {
        return new ViewSchema.Table(
                "executionDefinitions",
                "选择 Definition",
                "documents",
                "id",
                List.of(
                        new ViewSchema.Column("id", "Definition", Optional.of(260)),
                        new ViewSchema.Column("revision", "版本", Optional.of(90))),
                ViewSelectionMode.SINGLE,
                List.of());
    }

    private ViewSchema.Table profileTable() {
        return new ViewSchema.Table(
                "executionProfiles",
                "选择 Agent Profile",
                "profiles",
                "id",
                List.of(
                        new ViewSchema.Column("name", "Profile", Optional.of(240)),
                        new ViewSchema.Column("id", "标识", Optional.of(220)),
                        new ViewSchema.Column("revision", "版本", Optional.of(90))),
                ViewSelectionMode.SINGLE,
                List.of());
    }

    private ViewSchema.Form startForm() {
        ViewAction start = new ViewAction(
                "启动 Execution",
                MANAGEMENT_START,
                Map.of(),
                Map.of(),
                new ExpectedRevisionBinding.SourceRevision("executionDefinition"),
                false,
                new ViewCommandBinding("definitionId", new ViewBinding("executionDefinition", "id")),
                new ViewCommandBinding("profileId", new ViewBinding("profiles", "id")),
                new ViewCommandBinding("profileRevision", new ViewBinding("profiles", "revision")));
        return new ViewSchema.Form("executionStart", "执行预算", budgetFields(), start);
    }

    private List<ViewField> budgetFields() {
        return List.of(
                budgetField("maximumTurns", "最大 Turn 数", "10", 1, 10_000),
                budgetField("inputTokens", "输入 token 总上限", "100000", 1, 1_000_000_000),
                budgetField("outputTokens", "输出 token 总上限", "50000", 1, 1_000_000_000),
                budgetField("toolCalls", "Tool 调用总上限", "100", 1, 1_000_000));
    }

    private static ViewField budgetField(String name, String label, String initial, long minimum, long maximum) {
        ViewFieldValidation validation = new ViewFieldValidation(
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.of(BigDecimal.valueOf(minimum)),
                Optional.of(BigDecimal.valueOf(maximum)),
                Optional.empty());
        return new ViewField(
                name,
                label,
                ViewFieldType.NUMBER,
                new ViewBinding("executionDefinition", name),
                Optional.of(initial),
                validation,
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private ViewSchema.Table executionTable(List<ViewAction> actions) {
        return new ViewSchema.Table(
                "executions",
                "执行记录",
                "executions",
                "id",
                List.of(
                        new ViewSchema.Column("id", "执行 ID", Optional.of(260)),
                        new ViewSchema.Column("state", "状态", Optional.of(120)),
                        new ViewSchema.Column("definitionRevision", "定义版本", Optional.of(100))),
                ViewSelectionMode.SINGLE,
                List.copyOf(actions));
    }

    private ExtensionPayloadCodec payloads() {
        return documents.payloads();
    }

    private ExtensionId extensionId() {
        return documents.extensionId();
    }

    private record ProfileRow(String id, long revision, String name) {
        private static ProfileRow from(AutomationProfileOption option) {
            return new ProfileRow(option.profile().id(), option.profile().revision(), option.displayName());
        }
    }
}
