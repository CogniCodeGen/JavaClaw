package com.javaclaw.builtin.extensions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewArgumentBinding;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCommandBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewOption;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

/** 托管定时任务的原生时间编辑器；表单完全不接收执行目标、权限或预算，避免覆盖学习定义。 */
final class ManagedScheduleView {
    private final ManagedDocumentResource<ScheduleContracts.Definition> documents;
    private final ScheduleDefinitionLifecycle lifecycle;
    private final ScheduleDefinitionBindings bindings;

    ManagedScheduleView(
            ManagedDocumentResource<ScheduleContracts.Definition> documents, ScheduleDefinitionLifecycle lifecycle) {
        this.documents = documents;
        this.lifecycle = lifecycle;
        bindings = new ScheduleDefinitionBindings(documents, lifecycle);
    }

    List<ExtensionContribution> contributions() {
        return List.of(
                new ExtensionContributions.Query(
                        "schedule.managed.query", Set.of("managed/view.list", "managed/view.selected"), this::query),
                new ExtensionContributions.Command(
                        "schedule.managed.command", Set.of("managed/form/update"), this::update),
                new ExtensionContributions.View("schedule.managed.view", view()));
    }

    private ExtensionResponse query(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        var query = documents.payloads().decode(request.payload(), ViewQueryRequest.class);
        return context.managedStore().inTransaction(documents.extensionId(), transaction -> {
            if ("managed/view.selected".equals(request.operation())) {
                if (!query.arguments().keySet().equals(Set.of("id", "revision"))) {
                    throw new IllegalArgumentException("managed Schedule editor requires one exact selection");
                }
                String id = query.arguments().get("id");
                var definition = transaction
                        .get(documents.documentCollection(request.workspaceId()), id)
                        .map(value -> documents.payloads().decode(value.payload(), ScheduleContracts.Definition.class))
                        .orElseThrow();
                if (definition.revision() != Long.parseLong(query.arguments().get("revision"))
                        || !bindings.managed(transaction, request.workspaceId(), id)) {
                    throw new IllegalArgumentException("managed Schedule selection changed");
                }
                return response(new ViewQueryResult(
                        query.dataSourceId(),
                        List.of(),
                        documents.payloads().encode(row(definition)),
                        "",
                        false,
                        definition.revision()));
            }
            var rows = transaction.list(
                    documents.documentCollection(request.workspaceId()), query.cursor(), query.limit() + 1);
            boolean more = rows.size() > query.limit();
            var page = more ? rows.subList(0, query.limit()) : rows;
            var values = page.stream()
                    .filter(value -> bindings.managed(transaction, request.workspaceId(), value.key()))
                    .map(value -> documents.payloads().decode(value.payload(), ScheduleContracts.Definition.class))
                    .map(ManagedScheduleView::row)
                    .map(documents.payloads()::encode)
                    .toList();
            return response(new ViewQueryResult(
                    query.dataSourceId(),
                    values,
                    documents.payloads().encode(Map.of()),
                    more ? page.getLast().key() : "",
                    more,
                    0));
        });
    }

    private ExtensionResponse update(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        var input = documents.payloads().decode(request.payload(), TimingForm.class);
        var response = context.managedStore()
                .inCommand(
                        documents.extensionId(),
                        request.operation(),
                        request.idempotencyKey().orElseThrow(),
                        documents
                                .payloads()
                                .encode(Map.of("payload", request.payload(), "revision", request.expectedRevision()))
                                .sha256(),
                        transaction -> {
                            var previous = transaction
                                    .get(documents.documentCollection(request.workspaceId()), input.id())
                                    .map(value -> documents
                                            .payloads()
                                            .decode(value.payload(), ScheduleContracts.Definition.class))
                                    .orElseThrow();
                            if (previous.revision() != request.expectedRevision()
                                    || !bindings.managed(transaction, request.workspaceId(), input.id())) {
                                throw new IllegalArgumentException("managed Schedule revision changed");
                            }
                            var updated = new ScheduleContracts.Definition(
                                    previous.id(),
                                    previous.revision() + 1,
                                    input.name(),
                                    input.enabled(),
                                    input.timing(),
                                    previous.target(),
                                    previous.overlapPolicy(),
                                    previous.misfirePolicy(),
                                    context.clock().instant());
                            lifecycle.validateManaged(updated);
                            bindings.guardUserUpdate(transaction, request.workspaceId(), updated);
                            transaction.put(
                                    documents.documentCollection(request.workspaceId()),
                                    updated.id(),
                                    previous.revision(),
                                    documents.payloads().encode(updated));
                            lifecycle.afterManagedMutation(documents, request.workspaceId(), updated, transaction);
                            return new ExtensionResponse(documents.payloads().encode(updated), updated.revision());
                        });
        lifecycle.afterManagedCommit(documents, context);
        return response;
    }

    private ExtensionResponse response(ViewQueryResult result) {
        return new ExtensionResponse(documents.payloads().encode(result), result.revision());
    }

    private static Map<String, Object> row(ScheduleContracts.Definition definition) {
        return Map.of(
                "id",
                definition.id(),
                "revision",
                definition.revision(),
                "name",
                definition.name(),
                "enabled",
                definition.enabled(),
                "timingKind",
                definition.timing().kind(),
                "cronExpression",
                definition.timing().cronExpression().orElse(""),
                "zoneId",
                definition.timing().zoneId(),
                "intervalMinutes",
                definition.timing().interval().map(Duration::toMinutes).orElse(360L),
                "firstFireAt",
                definition.timing().firstFireAt().map(Instant::toString).orElse(""));
    }

    private static ViewSchema view() {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "javaclaw.schedule.managed",
                "托管定时任务",
                sources(),
                List.of(
                        new ViewSchema.Card(
                                "managed-help", "托管目标", "此处修改名称、时间和启停。执行目标与预算由学习配置维护。删除后，旧绑定意图不会重新创建任务。", List.of()),
                        new ViewSchema.Table(
                                "managedSchedules",
                                "定时任务",
                                "managedSchedules",
                                "id",
                                List.of(
                                        new ViewSchema.Column("name", "名称", Optional.of(240)),
                                        new ViewSchema.Column("enabled", "启用", Optional.of(80))),
                                ViewSelectionMode.SINGLE,
                                List.of(new ViewAction(
                                        "删除",
                                        "delete",
                                        Map.of(),
                                        Map.of("id", "id"),
                                        new ExpectedRevisionBinding.RowField("revision"),
                                        true))),
                        new ViewSchema.Form(
                                "managed-timing",
                                "时间与启停",
                                List.of(
                                        field("name", "名称", ViewFieldType.TEXT),
                                        field("enabled", "启用", ViewFieldType.BOOLEAN),
                                        new ViewField(
                                                "timingKind",
                                                "时间类型",
                                                ViewFieldType.CHOICE,
                                                new ViewBinding("managedEditor", "timingKind"),
                                                Optional.empty(),
                                                ViewFieldValidation.required(true),
                                                List.of(
                                                        new ViewOption("CRON", "Cron"),
                                                        new ViewOption("FIXED_INTERVAL", "固定间隔")),
                                                Optional.empty(),
                                                Optional.empty()),
                                        field("cronExpression", "Cron（仅 Cron 类型）", ViewFieldType.TEXT),
                                        field("zoneId", "IANA 时区", ViewFieldType.TEXT),
                                        field("intervalMinutes", "间隔分钟（仅固定间隔）", ViewFieldType.NUMBER),
                                        field("firstFireAt", "首次时间 ISO-8601（仅固定间隔）", ViewFieldType.TEXT)),
                                new ViewAction(
                                        "保存时间配置",
                                        "managed/form/update",
                                        Map.of(),
                                        Map.of(),
                                        new ExpectedRevisionBinding.SourceRevision("managedEditor"),
                                        false,
                                        new ViewCommandBinding("id", new ViewBinding("managedEditor", "id"))))));
    }

    private static List<ViewDataSource> sources() {
        return List.of(
                new ViewDataSource("managedSchedules", "managed/view.list", Map.of(), List.of(), 100),
                new ViewDataSource(
                        "managedEditor",
                        "managed/view.selected",
                        Map.of(),
                        List.of(
                                new ViewArgumentBinding("id", "managedSchedules", "id"),
                                new ViewArgumentBinding("revision", "managedSchedules", "revision")),
                        1));
    }

    private static ViewField field(String name, String label, ViewFieldType type) {
        return new ViewField(
                name,
                label,
                type,
                new ViewBinding("managedEditor", name),
                Optional.empty(),
                ViewFieldValidation.required(false),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private record TimingForm(
            String id,
            String name,
            boolean enabled,
            ScheduleContracts.TimingKind timingKind,
            String cronExpression,
            String zoneId,
            long intervalMinutes,
            String firstFireAt) {
        private ScheduleContracts.Timing timing() {
            return timingKind == ScheduleContracts.TimingKind.CRON
                    ? ScheduleContracts.Timing.cron(cronExpression, zoneId)
                    : ScheduleContracts.Timing.fixed(Duration.ofMinutes(intervalMinutes), Instant.parse(firstFireAt));
        }
    }
}
