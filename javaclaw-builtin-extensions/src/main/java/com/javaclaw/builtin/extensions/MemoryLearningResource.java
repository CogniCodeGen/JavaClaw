package com.javaclaw.builtin.extensions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionJobMutation;
import com.javaclaw.extension.spi.ExtensionJobSubmission;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ScheduleDefinitionBindingPort;
import com.javaclaw.extension.spi.ScheduleTargetCatalogPort;
import com.javaclaw.extension.spi.VersionedDocument;

/** 显式学习配置与现有 Definition/start/Receipt 编排入口；不新增异步 Action 协议。 */
final class MemoryLearningResource {
    static final OrchestrationContracts.ExecutionBudget BUDGET =
            new OrchestrationContracts.ExecutionBudget(1, 16000, 2000, 0);
    private final ExtensionPayloadCodec payloads;
    private final MemoryStoreAccess store;
    private final MemorySemantics semantics;

    MemoryLearningResource(ExtensionPayloadCodec payloads, MemoryStoreAccess store) {
        this.payloads = payloads;
        this.store = store;
        semantics = new MemorySemantics(payloads, store);
    }

    List<ExtensionContribution> contributions() {
        return List.of(
                new ExtensionContributions.Query(
                        "memory.learning.query", Set.of("learning/read", "learning/batches"), this::query),
                new ExtensionContributions.Command(
                        "memory.learning.command",
                        Set.of(
                                "learning/save",
                                "learning/repair",
                                "learning/run",
                                "learning/batch/skip",
                                "learning/batch/retry"),
                        this::command),
                new ExtensionContributions.Orchestrator(
                        "memory.learning.start", Set.of("execution/start"), this::start),
                new ExtensionContributions.SchedulableAction(
                        "memory.learning.start.schedulable", "execution/start", "对话记忆学习", false, List.of(), 0),
                new ExtensionContributions.SchedulableDefinition(
                        "memory.learning.definition",
                        "对话记忆学习",
                        context -> context.managedStore()
                                .inTransaction(
                                        MemoryStoreAccess.ID,
                                        transaction ->
                                                transaction
                                                        .list(
                                                                MemoryLearningState.definitions(context.workspaceId()),
                                                                "",
                                                                1)
                                                        .stream()
                                                        .map(value -> payloads.decode(
                                                                value.payload(),
                                                                MemoryV3Contracts.LearningDefinition.class))
                                                        .filter(MemoryV3Contracts.LearningDefinition::enabled)
                                                        .map(value -> new ScheduleTargetCatalogPort.DefinitionEntry(
                                                                value.id(), value.revision(), value.name()))
                                                        .toList())));
    }

    private ExtensionResponse query(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        if ("learning/batches".equals(request.operation())) {
            var page = payloads.decode(
                    request.payload(), com.javaclaw.builtin.contracts.DocumentContracts.PageRequest.class);
            var records = context.managedStore()
                    .inTransaction(
                            MemoryStoreAccess.ID,
                            transaction -> transaction.list(
                                    MemoryLearningState.batches(request.workspaceId()),
                                    page.afterKey(),
                                    Math.min(page.limit(), 200)));
            return new ExtensionResponse(
                    payloads.encode(new com.javaclaw.builtin.contracts.DocumentContracts.Page(
                            records.stream().map(VersionedDocument::payload).toList(),
                            records.isEmpty()
                                    ? page.afterKey()
                                    : records.getLast().key())),
                    0);
        }
        var definition = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction -> transaction.get(
                                MemoryLearningState.definitions(request.workspaceId()),
                                MemoryLearningState.DEFINITION_ID));
        var binding = context.scheduleBindings()
                .read(MemoryStoreAccess.ID, request.workspaceId(), MemoryLearningState.DEFINITION_ID);
        return new ExtensionResponse(
                payloads.encode(new LearningView(
                        definition.map(
                                value -> payloads.decode(value.payload(), MemoryV3Contracts.LearningDefinition.class)),
                        binding)),
                definition.map(VersionedDocument::revision).orElse(0L));
    }

    ExtensionResponse command(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        if ("learning/run".equals(request.operation())) {
            return start(request, context);
        }
        if ("learning/repair".equals(request.operation())) {
            restore(context, request.idempotencyKey());
            return new ExtensionResponse(payloads.encode(Map.of("submitted", true)), 0);
        }
        if ("learning/batch/skip".equals(request.operation()) || "learning/batch/retry".equals(request.operation())) {
            return new MemoryBatchRecovery(payloads, store).decide(request, context);
        }
        var input = payloads.decode(request.payload(), MemoryV3Contracts.LearningSave.class);
        var binding = context.scheduleBindings()
                .read(MemoryStoreAccess.ID, request.workspaceId(), MemoryLearningState.DEFINITION_ID);
        String key = request.idempotencyKey().orElseThrow();
        var response = context.managedStore()
                .inCommand(
                        MemoryStoreAccess.ID,
                        request.operation(),
                        key,
                        payloads.encode(Map.of("payload", request.payload(), "revision", request.expectedRevision()))
                                .sha256(),
                        transaction -> saveDefinition(request, context, transaction, input, binding));
        restore(context);
        return response;
    }

    private ExtensionResponse saveDefinition(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            com.javaclaw.extension.spi.ExtensionTransaction transaction,
            MemoryV3Contracts.LearningSave input,
            ScheduleDefinitionBindingPort.Binding binding) {
        semantics.lock(transaction, request.workspaceId());
        var existing = transaction.get(
                MemoryLearningState.definitions(request.workspaceId()), MemoryLearningState.DEFINITION_ID);
        long revision = existing.map(VersionedDocument::revision).orElse(0L);
        if (revision != request.expectedRevision()) {
            throw new IllegalArgumentException("learning definition revision changed");
        }
        var now = context.clock().instant();
        var definition = new MemoryV3Contracts.LearningDefinition(
                MemoryLearningState.DEFINITION_ID,
                revision + 1,
                "对话记忆学习",
                input.enabled(),
                restricted(input.execution()),
                initialSince(transaction, request.workspaceId(), input.enabled(), existing, now),
                now);
        transaction.put(
                MemoryLearningState.definitions(request.workspaceId()),
                definition.id(),
                revision,
                payloads.encode(definition));
        if (input.enabled()) {
            String intentId = "binding-" + definition.revision();
            var target = new ScheduleContracts.DefinitionTarget(
                    MemoryStoreAccess.ID.value(),
                    definition.id(),
                    definition.revision(),
                    definition.execution(),
                    BUDGET);
            var change = new ScheduleDefinitionBindingPort.Change(
                    definition.id(),
                    definition.revision(),
                    binding.revision(),
                    binding.generation(),
                    input.reschedule(),
                    payloads.encode(target),
                    "memory-" + request.workspaceId() + "-" + intentId);
            transaction.put(
                    MemoryLearningState.intents(request.workspaceId()),
                    intentId,
                    0,
                    payloads.encode(new MemoryLearningState.BindingIntent(intentId, 1, change, false)));
        }
        return new ExtensionResponse(payloads.encode(definition), definition.revision());
    }

    private java.time.Instant initialSince(
            com.javaclaw.extension.spi.ExtensionTransaction transaction,
            com.javaclaw.api.WorkspaceId workspaceId,
            boolean enabled,
            Optional<VersionedDocument> existing,
            java.time.Instant now) {
        // 尚未启用的草稿不固定回扫窗口；持久绑定意图证明已经发生过首次启用。
        if (enabled
                && transaction
                        .list(MemoryLearningState.intents(workspaceId), "", 1)
                        .isEmpty()) {
            return now.minus(Duration.ofDays(30));
        }
        return existing.map(value -> payloads.decode(value.payload(), MemoryV3Contracts.LearningDefinition.class)
                        .initialSince())
                .orElse(now.minus(Duration.ofDays(30)));
    }

    void restore(ExtensionExecutionContext context) throws Exception {
        restore(context, Optional.empty());
    }

    private void restore(ExtensionExecutionContext context, Optional<String> repairKey) throws Exception {
        var intents = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction ->
                                MemoryStoreAccess.all(transaction, MemoryLearningState.intents(context.workspaceId()))
                                        .stream()
                                        .map(value -> payloads.decode(
                                                value.payload(), MemoryLearningState.BindingIntent.class))
                                        .filter(value -> !value.acknowledged())
                                        .toList());
        for (var intent : intents) {
            var original = submitBinding(context, intent, intent.change().idempotencyKey());
            if (repairKey.isPresent()
                    && (original.state() == ExecutionState.FAILED || original.state() == ExecutionState.CANCELLED)) {
                submitBinding(context, intent, "repair-" + repairKey.orElseThrow() + "-" + intent.id());
            }
        }
        new MemoryBatchRecovery(payloads, store).restore(context);
    }

    private com.javaclaw.extension.spi.ExtensionJob submitBinding(
            ExtensionExecutionContext context, MemoryLearningState.BindingIntent intent, String key) throws Exception {
        return context.jobs()
                .submit(
                        payloads.encode(intent.change()),
                        new ExtensionJobMutation(key, 0),
                        () -> new ExtensionJobSubmission(
                                MemoryStoreAccess.ID,
                                context.workspaceId(),
                                MemoryLearningState.BINDING_JOB_TYPE,
                                MemoryLearningState.DEFINITION_ID,
                                intent.change().definitionRevision(),
                                payloads.encode(intent),
                                payloads.encode(Map.of())));
    }

    private ExtensionResponse start(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        var start = "learning/run".equals(request.operation())
                ? new OrchestrationContracts.StartRequest(
                        MemoryLearningState.DEFINITION_ID, ExecutionOverrides.empty(), BUDGET)
                : payloads.decode(request.payload(), OrchestrationContracts.StartRequest.class);
        String key = request.idempotencyKey().orElseThrow();
        var job = context.jobs()
                .submit(
                        payloads.encode(JobSubmissionIdentity.from(MemoryStoreAccess.ID, request)),
                        new ExtensionJobMutation(key, 0),
                        () -> {
                            var definition = context.managedStore()
                                    .inTransaction(
                                            MemoryStoreAccess.ID,
                                            transaction -> transaction
                                                    .get(
                                                            MemoryLearningState.definitions(request.workspaceId()),
                                                            start.definitionId())
                                                    .map(value -> payloads.decode(
                                                            value.payload(),
                                                            MemoryV3Contracts.LearningDefinition.class))
                                                    .orElseThrow());
                            if (!definition.enabled() || definition.revision() != request.expectedRevision()) {
                                throw new IllegalArgumentException("learning definition is disabled or stale");
                            }
                            MemoryLearningStartPolicy.requireCompatible(start, definition.execution());
                            var policy = context.managedStore()
                                    .inTransaction(
                                            MemoryStoreAccess.ID,
                                            transaction -> store.readSettings(
                                                            transaction, request.workspaceId(), context)
                                                    .policy());
                            if (policy == MemoryContracts.LearningPolicy.OFF) {
                                throw new IllegalArgumentException("Memory learning policy is OFF");
                            }
                            var platform = context.executionPolicies()
                                    .freeze(
                                            request.workspaceId(),
                                            restricted(definition.execution()),
                                            context.cancellation());
                            if (!platform.toolCatalog().tools().isEmpty()) {
                                throw new IllegalArgumentException("learning requires an empty frozen tool catalog");
                            }
                            if (request.unattendedExecutionScope().isPresent()) {
                                platform = platform.withUnattendedExecutionScope(
                                        request.unattendedExecutionScope().orElseThrow());
                            }
                            return new ExtensionJobSubmission(
                                    MemoryStoreAccess.ID,
                                    request.workspaceId(),
                                    MemoryLearningState.JOB_TYPE,
                                    definition.id(),
                                    definition.revision(),
                                    payloads.encode(new MemoryLearningState.Frozen(definition, platform, policy)),
                                    payloads.encode(new MemoryLearningState.Checkpoint("prepare", "")));
                        });
        return new ExtensionResponse(payloads.encode(ExtensionExecutionReceipt.from(job)), job.revision());
    }

    static ExecutionOverrides restricted(ExecutionOverrides input) {
        return new ExecutionOverrides(
                input.role(),
                input.provider(),
                input.permissionProfile(),
                input.approvalPolicy(),
                Optional.of(new TurnBudget(16000, 2000, 0, 0, Duration.ofSeconds(120))),
                Optional.of(Set.of()),
                input.reasoning());
    }

    private record LearningView(
            Optional<MemoryV3Contracts.LearningDefinition> definition, ScheduleDefinitionBindingPort.Binding binding) {}
}
