package com.javaclaw.builtin.extensions;

import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobExecution;
import com.javaclaw.extension.spi.ExtensionJobExecutor;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;
import com.javaclaw.extension.spi.ScheduleDefinitionBindingPort;

/** 现有 Supervisor 推进的短绑定工作单元；跨扩展调用前释放 Memory 事务，不等待任何子 Job。 */
final class MemoryBindingJobExecutor implements ExtensionJobExecutor {
    private final ExtensionJobRuntimeContext context;

    MemoryBindingJobExecutor(ExtensionJobRuntimeContext context) {
        this.context = context;
    }

    @Override
    public Optional<ExtensionJobWorkUnit> plan(ExtensionJob job) {
        return Optional.of(new ExtensionJobWorkUnit("bind", job.frozenInput()));
    }

    @Override
    public ExtensionJobStepResult execute(ExtensionJobExecution execution, CancellationToken cancellation)
            throws Exception {
        var intent = context.payloads().decode(execution.job().frozenInput(), MemoryLearningState.BindingIntent.class);
        var definition = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction -> transaction
                                .get(
                                        MemoryLearningState.definitions(
                                                execution.job().workspaceId()),
                                        MemoryLearningState.DEFINITION_ID)
                                .map(value -> context.payloads()
                                        .decode(value.payload(), MemoryV3Contracts.LearningDefinition.class))
                                .orElseThrow());
        if (definition.enabled() && definition.revision() == intent.change().definitionRevision()) {
            var binding = context.scheduleBindings()
                    .read(MemoryStoreAccess.ID, execution.job().workspaceId(), definition.id());
            var change = intent.change();
            boolean alreadyApplied = binding.state() == ScheduleDefinitionBindingPort.State.BOUND
                    && binding.definitionRevision() == change.definitionRevision();
            if (!alreadyApplied && binding.generation() != change.expectedGeneration()) {
                // 已删除计划不能被恢复或旧意图重建；界面必须明确重新安排。
                throw new IllegalArgumentException(
                        "Schedule binding generation changed; explicit reschedule is required");
            }
            var rebased = new ScheduleDefinitionBindingPort.Change(
                    change.definitionId(),
                    change.definitionRevision(),
                    binding.revision(),
                    binding.generation(),
                    change.reschedule(),
                    change.target(),
                    change.idempotencyKey());
            if (!alreadyApplied) {
                context.scheduleBindings()
                        .bind(MemoryStoreAccess.ID, execution.job().workspaceId(), rebased, cancellation);
            }
        }
        context.managedStore().inTransaction(MemoryStoreAccess.ID, transaction -> {
            var current = transaction
                    .get(MemoryLearningState.intents(execution.job().workspaceId()), intent.id())
                    .orElseThrow();
            var value = context.payloads().decode(current.payload(), MemoryLearningState.BindingIntent.class);
            if (!value.acknowledged()) {
                transaction.put(
                        MemoryLearningState.intents(execution.job().workspaceId()),
                        intent.id(),
                        current.revision(),
                        context.payloads()
                                .encode(new MemoryLearningState.BindingIntent(
                                        intent.id(), current.revision() + 1, value.change(), true)));
            }
            return null;
        });
        return new ExtensionJobStepResult(
                context.payloads().encode(Map.of("bindingIntent", intent.id(), "acknowledged", true)),
                execution.job().checkpoint(),
                ExecutionState.COMPLETED,
                Optional.empty(),
                Optional.empty());
    }
}
