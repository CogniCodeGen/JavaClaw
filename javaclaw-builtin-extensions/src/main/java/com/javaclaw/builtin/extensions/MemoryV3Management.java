package com.javaclaw.builtin.extensions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
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

/** Memory 原生管理表单与纯数据图谱；版本和冲突裁决始终由领域命令验证。 */
final class MemoryV3Management {
    private final ExtensionPayloadCodec payloads;
    private final MemoryStoreAccess store;
    private final MemorySemantics semantics;
    private final MemoryCommandHandler commands;
    private final MemoryLearningResource learning;

    MemoryV3Management(
            ExtensionPayloadCodec payloads,
            MemoryStoreAccess store,
            MemoryCommandHandler commands,
            MemoryLearningResource learning) {
        this.payloads = payloads;
        this.store = store;
        this.commands = commands;
        this.learning = learning;
        semantics = new MemorySemantics(payloads, store);
    }

    List<ExtensionContribution> contributions() {
        return List.of(
                new ExtensionContributions.Query(
                        "memory.v3.view.query",
                        Set.of(
                                "view.conflicts",
                                "view.conflict",
                                "view.effectivity",
                                "view.graph.nodes",
                                "view.graph.edges",
                                "view.graph.memory",
                                "view.learning.definition",
                                "view.learning.batches",
                                "view.learning.roles",
                                AutomationSelectionView.PROVIDERS,
                                AutomationSelectionView.PERMISSIONS),
                        this::query),
                new ExtensionContributions.Command(
                        "memory.v3.form.command",
                        Set.of("conflict/form/resolve", "effectivity/form/update", "learning/form/save"),
                        this::command),
                new ExtensionContributions.View("memory.conflicts", conflictView()),
                new ExtensionContributions.View("memory.graph", MemoryGraphView.create()),
                new ExtensionContributions.View("memory.background-learning", learningView()));
    }

    private ExtensionResponse query(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        if (Set.of(AutomationSelectionView.PROVIDERS, AutomationSelectionView.PERMISSIONS)
                .contains(request.operation())) {
            return new MemoryLearningSelectionView(payloads)
                    .decorate(AutomationSelectionView.query(request, context, payloads), context);
        }
        var query = payloads.decode(request.payload(), ViewQueryRequest.class);
        if ("view.learning.roles".equals(request.operation())) {
            var rows = context.executionPolicies().roles(request.workspaceId()).stream()
                    .map(role -> payloads.encode(Map.of(
                            "id",
                            role.role().id(),
                            "name",
                            role.displayName(),
                            "revision",
                            role.role().revision(),
                            "role",
                            role.role())))
                    .toList();
            return new MemoryLearningSelectionView(payloads).decorate(response(query, rows, Map.of(), 0), context);
        }
        return context.managedStore().inTransaction(MemoryStoreAccess.ID, transaction -> {
            if ("view.learning.definition".equals(request.operation())) {
                return learningDefinition(request, context, transaction, query);
            }
            if ("view.learning.batches".equals(request.operation()) || "view.conflicts".equals(request.operation())) {
                return listRecords(request, context, transaction, query);
            }
            if ("view.conflict".equals(request.operation())) {
                return conflictEditor(request, context, transaction, query);
            }
            if ("view.effectivity".equals(request.operation()) || "view.graph.memory".equals(request.operation())) {
                return effectivityEditor(request, context, transaction, query);
            }
            return new MemoryGraphResource(payloads, store).view(request, context, query, transaction);
        });
    }

    private ExtensionResponse learningDefinition(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            com.javaclaw.extension.spi.ExtensionTransaction transaction,
            ViewQueryRequest query) {

        var definition = transaction.get(
                MemoryLearningState.definitions(request.workspaceId()), MemoryLearningState.DEFINITION_ID);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(
                "enabled",
                definition
                        .map(value -> payloads.decode(value.payload(), MemoryV3Contracts.LearningDefinition.class)
                                .enabled())
                        .orElse(false));
        values.put("reschedule", false);
        values.put("savedExecution", "尚未保存学习配置，请从当前目录选择角色、模型和权限。");
        definition
                .map(value -> payloads.decode(value.payload(), MemoryV3Contracts.LearningDefinition.class))
                .ifPresent(value -> MemoryLearningSelectionView.savedValues(values, value.execution()));
        return response(
                query,
                List.of(),
                values,
                definition.map(value -> value.revision()).orElse(0L));
    }

    private ExtensionResponse listRecords(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            com.javaclaw.extension.spi.ExtensionTransaction transaction,
            ViewQueryRequest query) {

        String collection = "view.conflicts".equals(request.operation())
                ? MemorySemantics.conflicts(request.workspaceId())
                : MemoryLearningState.batches(request.workspaceId());
        boolean conflicts = "view.conflicts".equals(request.operation());
        var allPending = conflicts
                ? MemoryStoreAccess.all(transaction, collection).stream()
                        .filter(value -> payloads.decode(value.payload(), MemoryV3Contracts.Conflict.class)
                                        .state()
                                == MemoryV3Contracts.ConflictState.PENDING)
                        .toList()
                : List.<com.javaclaw.extension.spi.VersionedDocument>of();
        var fetched = conflicts
                ? allPending.stream()
                        .filter(value -> value.key().compareTo(query.cursor()) > 0)
                        .limit(query.limit() + 1L)
                        .toList()
                : transaction.list(collection, query.cursor(), Math.min(query.limit(), 200) + 1);
        boolean more = fetched.size() > Math.min(query.limit(), 200);
        var page = more ? fetched.subList(0, fetched.size() - 1) : fetched;
        var result = new ViewQueryResult(
                query.dataSourceId(),
                page.stream().map(value -> value.payload()).toList(),
                payloads.encode(Map.of(
                        "notice",
                        allPending.isEmpty()
                                ? "当前没有待处理的记忆冲突。"
                                : "有 " + allPending.size() + " 项待处理冲突，请在下方原生表单审查；候选不会作为有效记忆注入。")),
                more ? page.getLast().key() : "",
                more,
                0);
        return new ExtensionResponse(payloads.encode(result), 0);
    }

    private ExtensionResponse conflictEditor(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            com.javaclaw.extension.spi.ExtensionTransaction transaction,
            ViewQueryRequest query) {

        requireSelection(query);
        var conflict = transaction
                .get(
                        MemorySemantics.conflicts(request.workspaceId()),
                        query.arguments().get("id"))
                .map(value -> payloads.decode(value.payload(), MemoryV3Contracts.Conflict.class))
                .orElseThrow();
        requireSelectedRevision(query, conflict.revision());
        var proposal =
                store.requireProposal(transaction, MemoryCollectionNames.proposals(request), conflict.proposalId());
        Map<String, Long> revisions = new LinkedHashMap<>();
        var participants = new MemoryConflictParticipants(payloads, store)
                .read(transaction, request.workspaceId(), conflict.memoryIds());
        for (var participant : participants) {
            revisions.put(participant.memory().id(), participant.revision());
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", conflict.id());
        values.put("expectedMemoryRevisions", revisions);
        values.put("expectedMemoryRevision", semantics.head(transaction, request.workspaceId()));
        values.put("content", proposal.candidate().content());
        values.put("resolution", "KEEP_EXISTING");
        values.put("validFrom", "");
        values.put("validUntil", "");
        values.put("condition", "");
        values.put(
                "participants",
                participants.stream()
                        .map(MemoryConflictParticipants.Snapshot::description)
                        .collect(java.util.stream.Collectors.joining("\n\n---\n\n")));
        return response(query, List.of(), values, conflict.revision());
    }

    private ExtensionResponse effectivityEditor(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            com.javaclaw.extension.spi.ExtensionTransaction transaction,
            ViewQueryRequest query) {

        requireSelection(query);
        var memory = store.requireMemory(
                transaction,
                MemoryCollectionNames.memories(request),
                query.arguments().get("id"));
        requireSelectedRevision(query, memory.revision());
        var effect = semantics.effectivity(transaction, request.workspaceId(), memory.id());
        return response(
                query,
                List.of(),
                Map.of(
                        "id",
                        memory.id(),
                        "content",
                        memory.content(),
                        "state",
                        effect.state(),
                        "validFrom",
                        effect.validFrom().map(Instant::toString).orElse(""),
                        "validUntil",
                        effect.validUntil().map(Instant::toString).orElse(""),
                        "condition",
                        effect.condition()),
                memory.revision());
    }

    private ExtensionResponse command(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        Object payload;
        String operation;
        if ("learning/form/save".equals(request.operation())) {
            var form = payloads.decode(request.payload(), LearningForm.class);
            payload = new MemoryV3Contracts.LearningSave(
                    form.enabled(),
                    AutomationFormContracts.execution(
                            form.role(),
                            form.provider(),
                            form.permissionProfile(),
                            form.approvalPolicy(),
                            form.reasoning()),
                    form.reschedule());
            operation = "learning/save";
        } else if ("conflict/form/resolve".equals(request.operation())) {
            var form = payloads.decode(request.payload(), ConflictForm.class);
            payload = new MemoryV3Contracts.ConflictDecision(
                    form.id(),
                    form.resolution(),
                    form.expectedMemoryRevisions(),
                    form.expectedMemoryRevision(),
                    form.content(),
                    instant(form.validFrom()),
                    instant(form.validUntil()),
                    form.condition());
            operation = "conflict/resolve";
        } else {
            var form = payloads.decode(request.payload(), EffectivityForm.class);
            payload = new MemoryV3Contracts.EffectivityUpdate(
                    form.id(), instant(form.validFrom()), instant(form.validUntil()), form.condition());
            operation = "effectivity/update";
        }
        var mapped = new ExtensionRequest(
                request.workspaceId(),
                request.threadId(),
                request.turnId(),
                operation,
                payloads.encode(payload),
                request.idempotencyKey(),
                request.expectedRevision(),
                request.unattendedExecutionScope());
        return "learning/save".equals(operation)
                ? learning.command(mapped, context)
                : commands.command(mapped, context);
    }

    private ExtensionResponse response(
            ViewQueryRequest query,
            List<com.javaclaw.api.CanonicalPayload> rows,
            Map<String, Object> values,
            long revision) {
        return new ExtensionResponse(
                payloads.encode(
                        new ViewQueryResult(query.dataSourceId(), rows, payloads.encode(values), "", false, revision)),
                revision);
    }

    static ViewSchema conflictView() {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "javaclaw.memory.conflicts",
                "记忆冲突",
                List.of(
                        new ViewDataSource("conflicts", "view.conflicts", Map.of(), List.of(), 100),
                        selected("conflict", "view.conflict", "conflicts")),
                List.of(
                        new ViewSchema.Table(
                                "conflicts",
                                "待审查冲突",
                                "conflicts",
                                "id",
                                List.of(
                                        column("proposalId", "候选"),
                                        column("memoryIds", "冲突记忆"),
                                        column("state", "状态"),
                                        column("revision", "版本")),
                                ViewSelectionMode.SINGLE,
                                List.of()),
                        new ViewSchema.Markdown(
                                "conflict-participants", "冲突记忆及状态", new ViewBinding("conflict", "participants")),
                        new ViewSchema.Form(
                                "conflict-resolution",
                                "人工决议（条件为 ISO-8601 时间；自然语言条件不自动注入）",
                                List.of(
                                        choice(
                                                "resolution",
                                                "决议",
                                                "conflict",
                                                List.of(
                                                        new ViewOption("KEEP_EXISTING", "保留旧记忆"),
                                                        new ViewOption("REPLACE", "替换"),
                                                        new ViewOption("MERGE", "合并"),
                                                        new ViewOption("COEXIST", "按条件并存"))),
                                        field("content", "确认正文", "conflict", ViewFieldType.MULTILINE, false),
                                        field("validFrom", "起始时间（含，可空）", "conflict", ViewFieldType.TEXT, false),
                                        field("validUntil", "结束时间（不含，可空）", "conflict", ViewFieldType.TEXT, false),
                                        field("condition", "人工条件（可空）", "conflict", ViewFieldType.MULTILINE, false)),
                                new ViewAction(
                                        "提交决议",
                                        "conflict/form/resolve",
                                        Map.of(),
                                        Map.of(),
                                        new ExpectedRevisionBinding.SourceRevision("conflict"),
                                        true,
                                        new ViewCommandBinding("id", new ViewBinding("conflict", "id")),
                                        new ViewCommandBinding(
                                                "expectedMemoryRevisions",
                                                new ViewBinding("conflict", "expectedMemoryRevisions")),
                                        new ViewCommandBinding(
                                                "expectedMemoryRevision",
                                                new ViewBinding("conflict", "expectedMemoryRevision"))))));
    }

    private static ViewSchema learningView() {
        List<ViewSchema.Node> nodes = new ArrayList<>();
        nodes.add(learningExplanation());
        nodes.add(new ViewSchema.Markdown(
                "learning-saved-execution", "已保存的执行配置", new ViewBinding("learningDefinition", "savedExecution")));
        nodes.add(new ViewSchema.Table(
                "learning-roles",
                "选择 Agent Role",
                "roles",
                "id",
                List.of(column("name", "角色"), column("revision", "版本")),
                ViewSelectionMode.SINGLE,
                List.of()));
        nodes.add(new ViewSchema.Form(
                "learning-config",
                "学习配置",
                AutomationSelectionView.withFields(
                        "learningDefinition",
                        List.of(
                                field("enabled", "明确启用后台学习", "learningDefinition", ViewFieldType.BOOLEAN, true),
                                field(
                                        "reschedule",
                                        "明确重新安排已删除的定时任务",
                                        "learningDefinition",
                                        ViewFieldType.BOOLEAN,
                                        true))),
                new ViewAction(
                        "保存学习配置",
                        "learning/form/save",
                        Map.of(),
                        Map.of(),
                        new ExpectedRevisionBinding.SourceRevision("learningDefinition"),
                        false,
                        new ViewCommandBinding("role", new ViewBinding("roles", "role")),
                        new ViewCommandBinding("provider", new ViewBinding("providers", "provider")),
                        new ViewCommandBinding(
                                "permissionProfile", new ViewBinding("permissions", "permissionProfile")))));
        nodes.add(batchTable());
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "javaclaw.memory.background-learning",
                "后台记忆学习",
                AutomationSelectionView.withSources(List.of(
                        new ViewDataSource("learningDefinition", "view.learning.definition", Map.of(), List.of(), 1),
                        new ViewDataSource("roles", "view.learning.roles", Map.of(), List.of(), 100),
                        new ViewDataSource("batches", "view.learning.batches", Map.of(), List.of(), 100))),
                AutomationSelectionView.withTables(nodes));
    }

    private static ViewSchema.Table batchTable() {
        return new ViewSchema.Table(
                "learning-batches",
                "批次审计",
                "batches",
                "id",
                List.of(column("id", "批次"), column("state", "状态"), column("reason", "说明"), column("deferred", "超预算条目")),
                ViewSelectionMode.SINGLE,
                List.of(
                        new ViewAction(
                                "明确重试未决批次",
                                "learning/batch/retry",
                                Map.of(),
                                Map.of("id", "id"),
                                new ExpectedRevisionBinding.RowField("revision"),
                                true),
                        new ViewAction(
                                "明确跳过未决批次",
                                "learning/batch/skip",
                                Map.of(),
                                Map.of("id", "id"),
                                new ExpectedRevisionBinding.RowField("revision"),
                                true)));
    }

    private static ViewSchema.Card learningExplanation() {
        return new ViewSchema.Card(
                "learning-bound",
                "后台学习",
                "工作空间需明确启用。首次仅回扫最近 30 天；默认每 6 小时一个批次，最多 200 条、1 次模型 Turn、16000 输入 / 2000 输出 token、120 秒，工具目录为空。时间与启停在定时任务管理中维护。",
                List.of(
                        new ViewAction(
                                "立即执行一个批次",
                                "learning/run",
                                Map.of(),
                                Map.of(),
                                new ExpectedRevisionBinding.SourceRevision("learningDefinition"),
                                false),
                        new ViewAction(
                                "修复待绑定意图",
                                "learning/repair",
                                Map.of(),
                                Map.of(),
                                new ExpectedRevisionBinding.None(),
                                false)));
    }

    private static ViewDataSource selected(String id, String operation, String source) {
        return new ViewDataSource(
                id,
                operation,
                Map.of(),
                List.of(
                        new ViewArgumentBinding("id", source, "id"),
                        new ViewArgumentBinding("revision", source, "revision")),
                1);
    }

    private static ViewSchema.Column column(String field, String label) {
        return new ViewSchema.Column(field, label, Optional.of(180));
    }

    private static ViewField field(String name, String label, String source, ViewFieldType type, boolean required) {
        return new ViewField(
                name,
                label,
                type,
                new ViewBinding(source, name),
                Optional.empty(),
                ViewFieldValidation.required(required),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private static ViewField choice(String name, String label, String source, List<ViewOption> options) {
        return new ViewField(
                name,
                label,
                ViewFieldType.CHOICE,
                new ViewBinding(source, name),
                Optional.empty(),
                ViewFieldValidation.required(true),
                options,
                Optional.empty(),
                Optional.empty());
    }

    private static Optional<Instant> instant(String input) {
        return input == null || input.isBlank() ? Optional.empty() : Optional.of(Instant.parse(input.strip()));
    }

    private static void requireSelection(ViewQueryRequest query) {
        if (!query.arguments().keySet().equals(Set.of("id", "revision"))
                || !query.cursor().isEmpty()) {
            throw new IllegalArgumentException("Memory editor requires one exact selection");
        }
    }

    private static void requireSelectedRevision(ViewQueryRequest query, long revision) {
        if (Long.parseLong(query.arguments().get("revision")) != revision) {
            throw new IllegalArgumentException("Memory selection changed; refresh before editing");
        }
    }

    private record LearningForm(
            boolean enabled,
            boolean reschedule,
            AgentRoleRef role,
            ProviderRef provider,
            PermissionProfileRef permissionProfile,
            ApprovalPolicy approvalPolicy,
            ReasoningPreference reasoning) {}

    private record ConflictForm(
            String id,
            MemoryV3Contracts.Resolution resolution,
            Map<String, Long> expectedMemoryRevisions,
            long expectedMemoryRevision,
            String content,
            String validFrom,
            String validUntil,
            String condition) {}

    private record EffectivityForm(String id, String validFrom, String validUntil, String condition) {}
}
