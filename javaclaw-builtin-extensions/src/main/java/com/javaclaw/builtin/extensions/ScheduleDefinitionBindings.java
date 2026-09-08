package com.javaclaw.builtin.extensions;

import java.time.Duration;
import java.util.Optional;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.ScheduleDefinitionBindingPort;

/** Schedule 拥有的受限 Definition 绑定；绑定版本与主文档在同一事务写入，删除保留代次墓碑。 */
final class ScheduleDefinitionBindings {
    private final ManagedDocumentResource<ScheduleContracts.Definition> documents;
    private final ScheduleDefinitionLifecycle lifecycle;

    ScheduleDefinitionBindings(
            ManagedDocumentResource<ScheduleContracts.Definition> documents, ScheduleDefinitionLifecycle lifecycle) {
        this.documents = documents;
        this.lifecycle = lifecycle;
    }

    ExtensionResponse read(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        var input = documents.payloads().decode(request.payload(), ScheduleDefinitionBindingPort.Lookup.class);
        var binding = context.managedStore()
                .inTransaction(
                        documents.extensionId(),
                        transaction ->
                                current(transaction, request.workspaceId(), input.owner(), input.definitionId()));
        return new ExtensionResponse(documents.payloads().encode(binding), binding.revision());
    }

    ExtensionResponse apply(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        var input = documents.payloads().decode(request.payload(), ScheduleDefinitionBindingPort.Apply.class);
        var change = input.change();
        var target = documents.payloads().decode(change.target(), ScheduleContracts.DefinitionTarget.class);
        if (!input.owner().value().equals(target.extensionId())
                || !change.definitionId().equals(target.definitionId())
                || change.definitionRevision() != target.definitionRevision()) {
            throw new IllegalArgumentException("binding may only target its owner's exact Definition");
        }
        ExtensionResponse response = context.managedStore()
                .inCommand(
                        documents.extensionId(),
                        "binding/apply",
                        change.idempotencyKey(),
                        request.payload().sha256(),
                        transaction -> persistBinding(request, context, transaction, input, target));
        lifecycle.afterManagedCommit(documents, context);
        return response;
    }

    private ExtensionResponse persistBinding(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            ScheduleDefinitionBindingPort.Apply input,
            ScheduleContracts.DefinitionTarget target) {
        var change = input.change();
        var current = current(transaction, request.workspaceId(), input.owner(), change.definitionId());
        if (current.revision() != change.expectedBindingRevision()
                || current.generation() != change.expectedGeneration()) {
            throw new IllegalArgumentException("Schedule binding revision or generation changed");
        }
        if (current.state() == ScheduleDefinitionBindingPort.State.DETACHED && !change.reschedule()) {
            throw new IllegalArgumentException("Schedule was deleted; explicit reschedule is required");
        }
        boolean creating = current.state() != ScheduleDefinitionBindingPort.State.BOUND;
        long generation = creating ? current.generation() + 1 : current.generation();
        String scheduleId = creating
                ? "managed-" + key(input.owner(), change.definitionId()) + "-" + generation
                : current.scheduleId();
        var binding = new ScheduleDefinitionBindingPort.Binding(
                change.definitionId(),
                current.revision() + 1,
                generation,
                scheduleId,
                change.definitionRevision(),
                ScheduleDefinitionBindingPort.State.BOUND);
        transaction.put(
                bindings(request.workspaceId()),
                key(input.owner(), change.definitionId()),
                current.revision(),
                documents.payloads().encode(binding));
        persistSchedule(request, context, transaction, input, target, binding, creating);
        return new ExtensionResponse(documents.payloads().encode(binding), binding.revision());
    }

    private void persistSchedule(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            ScheduleDefinitionBindingPort.Apply input,
            ScheduleContracts.DefinitionTarget target,
            ScheduleDefinitionBindingPort.Binding binding,
            boolean creating) {
        String scheduleId = binding.scheduleId();
        var change = input.change();
        ScheduleContracts.Definition previous = creating
                ? null
                : transaction
                        .get(documents.documentCollection(request.workspaceId()), scheduleId)
                        .map(value -> documents.payloads().decode(value.payload(), ScheduleContracts.Definition.class))
                        .orElseThrow(() -> new IllegalStateException("bound Schedule does not exist"));
        var now = context.clock().instant();
        var definition = new ScheduleContracts.Definition(
                scheduleId,
                creating ? 1 : previous.revision() + 1,
                creating ? "记忆学习" : previous.name(),
                creating || previous.enabled(),
                creating
                        ? ScheduleContracts.Timing.fixed(Duration.ofHours(6), now.plus(Duration.ofHours(6)))
                        : previous.timing(),
                ScheduleContracts.Target.definition(target),
                ScheduleContracts.OverlapPolicy.SKIP_IF_RUNNING,
                ScheduleContracts.MisfirePolicy.DO_NOT_CATCH_UP,
                now);
        transaction.put(
                documents.documentCollection(request.workspaceId()),
                scheduleId,
                creating ? 0 : previous.revision(),
                documents.payloads().encode(definition));
        if (creating) {
            transaction.put(
                    scheduleOwners(request.workspaceId()),
                    scheduleId,
                    0,
                    documents.payloads().encode(new Owner(input.owner(), change.definitionId())));
        }
        lifecycle.afterManagedMutation(documents, request.workspaceId(), definition, transaction);
    }

    void guardUserUpdate(
            ExtensionTransaction transaction, WorkspaceId workspaceId, ScheduleContracts.Definition definition) {
        Optional<Owner> owner = owner(transaction, workspaceId, definition.id());
        if (owner.isEmpty()) {
            return;
        }
        var binding = current(
                transaction,
                workspaceId,
                owner.orElseThrow().extensionId(),
                owner.orElseThrow().definitionId());
        if (binding.state() != ScheduleDefinitionBindingPort.State.BOUND
                || !binding.scheduleId().equals(definition.id())) {
            throw new IllegalArgumentException("managed Schedule binding is detached");
        }
        var existing = transaction
                .get(documents.documentCollection(workspaceId), definition.id())
                .map(value -> documents.payloads().decode(value.payload(), ScheduleContracts.Definition.class))
                .orElseThrow(() -> new IllegalArgumentException("managed Schedule does not exist"));
        if (!existing.target().equals(definition.target())) {
            throw new IllegalArgumentException("托管学习任务的目标和执行预算由学习配置管理；这里只能修改名称、时间和启停");
        }
        // 绑定先于 Schedule 文档 CAS，确保删除/重定向与用户时间编辑采用相同锁顺序。
        transaction.put(
                bindings(workspaceId),
                key(owner.orElseThrow().extensionId(), owner.orElseThrow().definitionId()),
                binding.revision(),
                documents
                        .payloads()
                        .encode(new ScheduleDefinitionBindingPort.Binding(
                                binding.definitionId(),
                                binding.revision() + 1,
                                binding.generation(),
                                binding.scheduleId(),
                                binding.definitionRevision(),
                                binding.state())));
    }

    void detach(ExtensionTransaction transaction, WorkspaceId workspaceId, String scheduleId) {
        owner(transaction, workspaceId, scheduleId).ifPresent(owner -> {
            var binding = current(transaction, workspaceId, owner.extensionId(), owner.definitionId());
            if (binding.state() == ScheduleDefinitionBindingPort.State.BOUND
                    && binding.scheduleId().equals(scheduleId)) {
                transaction.put(
                        bindings(workspaceId),
                        key(owner.extensionId(), owner.definitionId()),
                        binding.revision(),
                        documents
                                .payloads()
                                .encode(new ScheduleDefinitionBindingPort.Binding(
                                        binding.definitionId(),
                                        binding.revision() + 1,
                                        binding.generation() + 1,
                                        binding.scheduleId(),
                                        binding.definitionRevision(),
                                        ScheduleDefinitionBindingPort.State.DETACHED)));
            }
        });
    }

    boolean managed(ExtensionTransaction transaction, WorkspaceId workspaceId, String scheduleId) {
        return owner(transaction, workspaceId, scheduleId).isPresent();
    }

    private Optional<Owner> owner(ExtensionTransaction transaction, WorkspaceId workspaceId, String scheduleId) {
        return transaction
                .get(scheduleOwners(workspaceId), scheduleId)
                .map(value -> documents.payloads().decode(value.payload(), Owner.class));
    }

    private ScheduleDefinitionBindingPort.Binding current(
            ExtensionTransaction transaction, WorkspaceId workspaceId, ExtensionId owner, String definitionId) {
        return transaction
                .get(bindings(workspaceId), key(owner, definitionId))
                .map(value -> documents.payloads().decode(value.payload(), ScheduleDefinitionBindingPort.Binding.class))
                .orElseGet(() -> new ScheduleDefinitionBindingPort.Binding(
                        definitionId, 0, 0, "", 0, ScheduleDefinitionBindingPort.State.UNBOUND));
    }

    private String key(ExtensionId owner, String definitionId) {
        return documents.payloads().encode(new Owner(owner, definitionId)).sha256();
    }

    private static String bindings(WorkspaceId workspaceId) {
        return "definition-bindings." + workspaceId;
    }

    private static String scheduleOwners(WorkspaceId workspaceId) {
        return "managed-schedules." + workspaceId;
    }

    private record Owner(ExtensionId extensionId, String definitionId) {}
}
