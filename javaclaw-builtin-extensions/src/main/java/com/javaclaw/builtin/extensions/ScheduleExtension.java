package com.javaclaw.builtin.extensions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContext;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionJobRegistration;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.extension.spi.ViewArgumentBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

/** Schedule Definition、Occurrence、Outbox 投影与可恢复执行入口。 */
final class ScheduleExtension implements ExtensionBundle {
    private static final String OCCURRENCE_RUN = "occurrence/run";
    private static final String OCCURRENCE_LIST = "occurrence/list";
    private static final String OCCURRENCE_VIEW = "occurrence/view.list";
    private static final String PREVIEW = "preview";
    private static final String PREVIEW_VIEW = "preview/view.list";

    private final ScheduleDefinitionLifecycle lifecycle = new ScheduleDefinitionLifecycle();
    private final ManagedDocumentResource<ScheduleContracts.Definition> documents = new ManagedDocumentResource<>(
            BuiltinExtensionIds.SCHEDULE,
            "定时任务",
            ScheduleContracts.Definition.class,
            Set.of(ContributionKind.TIMER, ContributionKind.SCHEDULABLE_ACTION),
            BuiltinStoragePermission.create(BuiltinExtensionIds.SCHEDULE),
            lifecycle);
    private final ScheduleDefinitionBindings bindings = new ScheduleDefinitionBindings(documents, lifecycle);
    private final ScheduleManagement management = new ScheduleManagement(documents, lifecycle);

    @Override
    public ExtensionDescriptor descriptor() {
        return documents.descriptor();
    }

    @Override
    public List<ExtensionContribution> start(ExtensionContext context) {
        List<ExtensionContribution> contributions = new ArrayList<>(documents.startWithManagedWrites(context));
        contributions.addAll(management.contributions());
        contributions.addAll(new ManagedScheduleView(documents, lifecycle).contributions());
        contributions.addAll(List.of(
                new ExtensionContributions.Query(
                        "schedule.query",
                        Set.of(PREVIEW, PREVIEW_VIEW, OCCURRENCE_LIST, OCCURRENCE_VIEW, "binding/read"),
                        this::querySchedule),
                new ExtensionContributions.Command(
                        "schedule.command",
                        Set.of(OCCURRENCE_RUN, ScheduleEngine.DELIVERY_OPERATION, "binding/apply"),
                        this::commandSchedule),
                new ExtensionContributions.SchedulableAction(
                        "schedule.delivery.schedulable",
                        ScheduleEngine.DELIVERY_OPERATION,
                        "Schedule 内部投递",
                        false,
                        List.of(),
                        0),
                new ExtensionContributions.Timer(
                        "schedule.delivery.timer", Duration.ofMinutes(1), ScheduleEngine.DELIVERY_OPERATION),
                new ExtensionContributions.View("schedule.occurrences", occurrenceView())));
        return List.copyOf(contributions);
    }

    @Override
    public List<ExtensionSchema> schemas() {
        List<ExtensionSchema> schemas = new ArrayList<>(documents.schemas());
        schemas.addAll(List.of(
                new ExtensionSchema(
                        documents.extensionId().value() + "/preview/v1",
                        ContractSchemaFactory.document(
                                documents.payloads(),
                                documents.extensionId().value() + "/preview/v1",
                                "Schedule preview request",
                                Map.of(
                                        "scheduleId", ContractSchemaFactory.string(),
                                        "scheduleRevision", ContractSchemaFactory.integer(1),
                                        "after", ContractSchemaFactory.instant()),
                                List.of("scheduleId", "scheduleRevision", "after"))),
                new ExtensionSchema(
                        documents.extensionId().value() + "/occurrence-query/v1",
                        ContractSchemaFactory.document(
                                documents.payloads(),
                                documents.extensionId().value() + "/occurrence-query/v1",
                                "Schedule occurrence query",
                                Map.of(
                                        "scheduleId", Map.of("type", List.of("string", "null")),
                                        "afterKey", Map.of("type", "string"),
                                        "limit", ContractSchemaFactory.boundedInteger(1, 500)),
                                List.of("scheduleId", "afterKey", "limit")))));
        return List.copyOf(schemas);
    }

    @Override
    public List<ExtensionJobRegistration> jobExecutors(ExtensionJobRuntimeContext context) {
        return lifecycle.jobExecutors(context);
    }

    @Override
    public void restore(ExtensionExecutionContext context) throws Exception {
        lifecycle.restore(documents, context);
    }

    @Override
    public void close() {
        lifecycle.close();
        documents.close();
    }

    private ExtensionResponse querySchedule(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        return switch (request.operation()) {
            case "binding/read" -> bindings.read(request, context);
            case PREVIEW -> preview(request, context);
            case PREVIEW_VIEW -> previewViewRows(request, context);
            case OCCURRENCE_LIST -> occurrences(request, context);
            case OCCURRENCE_VIEW -> occurrenceViewRows(request, context);
            default -> throw new IllegalArgumentException("unknown Schedule query operation");
        };
    }

    private ExtensionResponse preview(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ScheduleContracts.PreviewRequest query =
                documents.payloads().decode(request.payload(), ScheduleContracts.PreviewRequest.class);
        ScheduleContracts.Definition definition =
                documents.requireDocument(query.scheduleId(), query.scheduleRevision(), context);
        return new ExtensionResponse(
                documents.payloads().encode(ScheduleTimes.preview(definition.timing(), query.after())), 0);
    }

    private ExtensionResponse previewViewRows(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        ViewQueryRequest query = documents.payloads().decode(request.payload(), ViewQueryRequest.class);
        if (!"preview".equals(query.dataSourceId())
                || !query.arguments().keySet().equals(Set.of("scheduleId", "scheduleRevision"))
                || !query.cursor().isEmpty()) {
            throw new IllegalArgumentException("Schedule preview view requires one selected Definition");
        }
        long revision = parseRevision(query.arguments().get("scheduleRevision"));
        ScheduleContracts.Definition definition =
                documents.requireDocument(query.arguments().get("scheduleId"), revision, context);
        ScheduleContracts.Preview preview =
                ScheduleTimes.preview(definition.timing(), context.clock().instant());
        List<CanonicalPayload> rows = java.util.stream.IntStream.range(
                        0, preview.instants().size())
                .mapToObj(index -> documents
                        .payloads()
                        .encode(new PreviewViewRow(index + 1, preview.instants().get(index))))
                .toList();
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(), rows, documents.payloads().encode(Map.of()), "", false, revision);
        return new ExtensionResponse(documents.payloads().encode(result), revision);
    }

    private ExtensionResponse occurrences(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        ScheduleContracts.OccurrenceQuery query =
                documents.payloads().decode(request.payload(), ScheduleContracts.OccurrenceQuery.class);
        ScheduleContracts.OccurrencePage page = ScheduleOccurrenceStore.list(
                context.managedStore(), documents.payloads(), request.workspaceId(), query);
        return new ExtensionResponse(documents.payloads().encode(page), 0);
    }

    private ExtensionResponse occurrenceViewRows(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        ViewQueryRequest query = documents.payloads().decode(request.payload(), ViewQueryRequest.class);
        if (!"occurrences".equals(query.dataSourceId())) {
            throw new IllegalArgumentException("unknown Schedule view data source");
        }
        Optional<String> scheduleId =
                Optional.ofNullable(query.arguments().get("scheduleId")).filter(value -> !value.isBlank());
        ScheduleContracts.OccurrencePage page = ScheduleOccurrenceStore.list(
                context.managedStore(),
                documents.payloads(),
                request.workspaceId(),
                new ScheduleContracts.OccurrenceQuery(scheduleId, query.cursor(), query.limit()));
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                page.occurrences().stream().map(this::occurrenceRow).toList(),
                documents.payloads().encode(Map.of()),
                page.nextKey(),
                !page.nextKey().isEmpty(),
                0);
        return new ExtensionResponse(documents.payloads().encode(result), 0);
    }

    private ExtensionResponse commandSchedule(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        if ("binding/apply".equals(request.operation())) {
            return bindings.apply(request, context);
        }
        String key = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("Schedule command requires idempotency key"));
        CanonicalPayload digest = documents
                .payloads()
                .encode(new ScheduleCommandDigest(request.operation(), request.expectedRevision(), request.payload()));
        ExtensionResponse response = context.managedStore()
                .inCommand(
                        documents.extensionId(),
                        request.operation(),
                        key,
                        digest.sha256(),
                        transaction -> recordOccurrence(request, context, transaction));
        ScheduleContracts.Occurrence occurrence =
                documents.payloads().decode(response.payload(), ScheduleContracts.Occurrence.class);
        ScheduleContracts.Occurrence dispatched = lifecycle.dispatchRecorded(context, occurrence);
        return new ExtensionResponse(documents.payloads().encode(dispatched), response.revision());
    }

    private ExtensionResponse recordOccurrence(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        OccurrenceIntent intent = occurrenceIntent(request, context, transaction);
        ScheduleContracts.Occurrence occurrence = ScheduleOccurrenceStore.create(
                transaction,
                documents.payloads(),
                request.workspaceId(),
                intent.definition(),
                intent.scheduledFor(),
                request.idempotencyKey().orElseThrow(),
                context.clock().instant());
        return new ExtensionResponse(documents.payloads().encode(occurrence), 1);
    }

    private OccurrenceIntent occurrenceIntent(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        return switch (request.operation()) {
            case OCCURRENCE_RUN -> manualIntent(request, context, transaction);
            case ScheduleEngine.DELIVERY_OPERATION -> deliveryIntent(request, transaction);
            default -> throw new IllegalArgumentException("unknown Schedule command operation");
        };
    }

    private OccurrenceIntent manualIntent(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        ScheduleContracts.ManualRun run =
                documents.payloads().decode(request.payload(), ScheduleContracts.ManualRun.class);
        ScheduleContracts.Definition definition =
                requireDefinition(transaction, request, run.scheduleId(), request.expectedRevision());
        requireEnabled(definition);
        return new OccurrenceIntent(definition, context.clock().instant());
    }

    private OccurrenceIntent deliveryIntent(ExtensionRequest request, ExtensionTransaction transaction) {
        ScheduleContracts.DeliveryRequest delivery =
                documents.payloads().decode(request.payload(), ScheduleContracts.DeliveryRequest.class);
        if (request.expectedRevision() != delivery.scheduleRevision()) {
            throw new IllegalArgumentException("Schedule delivery revision differs from command");
        }
        ScheduleContracts.Definition definition =
                requireDefinition(transaction, request, delivery.scheduleId(), delivery.scheduleRevision());
        requireEnabled(definition);
        return new OccurrenceIntent(definition, delivery.scheduledFor());
    }

    private ScheduleContracts.Definition requireDefinition(
            ExtensionTransaction transaction, ExtensionRequest request, String scheduleId, long expectedRevision) {
        VersionedDocument document = transaction
                .get(documents.documentCollection(request.workspaceId()), scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("Schedule Definition does not exist"));
        ScheduleContracts.Definition definition =
                documents.payloads().decode(document.payload(), ScheduleContracts.Definition.class);
        if (document.revision() != expectedRevision || definition.revision() != expectedRevision) {
            throw new IllegalArgumentException("Schedule Definition revision changed");
        }
        return definition;
    }

    private static void requireEnabled(ScheduleContracts.Definition definition) {
        if (!definition.enabled()) {
            throw new IllegalStateException("Schedule is disabled");
        }
    }

    private ViewSchema occurrenceView() {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                documents.extensionId().value() + ".occurrences",
                "定时执行记录",
                List.of(
                        new ViewDataSource("documents", "view.list", Map.of(), List.of(), 100),
                        new ViewDataSource(
                                "occurrences",
                                OCCURRENCE_VIEW,
                                Map.of(),
                                List.of(new ViewArgumentBinding("scheduleId", "documents", "id")),
                                100)),
                List.of(
                        new ViewSchema.Table(
                                "occurrenceDefinitions",
                                "Schedule",
                                "documents",
                                "id",
                                List.of(
                                        new ViewSchema.Column("name", "名称", Optional.of(220)),
                                        new ViewSchema.Column("revision", "版本", Optional.of(80))),
                                ViewSelectionMode.SINGLE,
                                List.of()),
                        new ViewSchema.Table(
                                "occurrenceTable",
                                "Occurrence",
                                "occurrences",
                                "id",
                                List.of(
                                        new ViewSchema.Column("scheduleRevision", "定义版本", Optional.of(100)),
                                        new ViewSchema.Column("scheduledFor", "计划时间", Optional.of(180)),
                                        new ViewSchema.Column("status", "状态", Optional.of(140)),
                                        new ViewSchema.Column("reason", "原因", Optional.of(180)),
                                        new ViewSchema.Column("updatedAt", "更新时间", Optional.of(180))),
                                ViewSelectionMode.SINGLE,
                                List.of()),
                        new ViewSchema.Timeline("occurrenceTimeline", "执行时间线", "occurrences", "updatedAt", "status")));
    }

    private CanonicalPayload occurrenceRow(ScheduleContracts.Occurrence occurrence) {
        return documents
                .payloads()
                .encode(new OccurrenceViewRow(
                        occurrence.identity().id(),
                        occurrence.identity().scheduleId(),
                        occurrence.identity().scheduleRevision(),
                        occurrence.scheduledFor(),
                        occurrence.status().state(),
                        occurrence.status().reason().orElse(""),
                        occurrence.updatedAt()));
    }

    private static long parseRevision(String value) {
        try {
            long revision = Long.parseLong(value);
            if (revision < 1) {
                throw new IllegalArgumentException("Schedule preview revision must be positive");
            }
            return revision;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Schedule preview revision is invalid", failure);
        }
    }

    private record ScheduleCommandDigest(String operation, long expectedRevision, CanonicalPayload payload) {}

    private record OccurrenceIntent(ScheduleContracts.Definition definition, Instant scheduledFor) {}

    private record PreviewViewRow(int sequence, Instant scheduledFor) {}

    private record OccurrenceViewRow(
            String id,
            String scheduleId,
            long scheduleRevision,
            Instant scheduledFor,
            ScheduleContracts.OccurrenceState status,
            String reason,
            Instant updatedAt) {}
}
