package com.javaclaw.builtin.extensions;

import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobMutation;
import com.javaclaw.extension.spi.ExtensionJobSubmission;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;

/** 未决批次只允许用户明确跳过或创建新尝试 Job；恢复意图不重新选择证据和执行策略。 */
final class MemoryBatchRecovery {
    private final ExtensionPayloadCodec payloads;
    private final MemorySemantics semantics;

    MemoryBatchRecovery(ExtensionPayloadCodec payloads, MemoryStoreAccess store) {
        this.payloads = payloads;
        semantics = new MemorySemantics(payloads, store);
    }

    ExtensionResponse decide(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        String idempotencyKey = request.idempotencyKey().orElseThrow();
        String digest = payloads.encode(Map.of("payload", request.payload(), "revision", request.expectedRevision()))
                .sha256();
        var recovered = context.managedStore()
                .recoverCommand(MemoryStoreAccess.ID, request.operation(), idempotencyKey, digest);
        if (recovered.isPresent()) {
            // 成功回执先于当前批次状态；替代 Job 已开始执行也不能使原决议的幂等重放失败。
            restore(context);
            return recovered.orElseThrow();
        }
        var key = payloads.decode(request.payload(), MemoryContracts.Key.class);
        var selected = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction -> transaction
                                .get(MemoryLearningState.batches(request.workspaceId()), key.id())
                                .map(value -> payloads.decode(value.payload(), MemoryLearningState.Batch.class))
                                .orElseThrow());
        ExtensionJob original = context.jobs().find(selected.ownerJobId()).orElseThrow();
        boolean retry = "learning/batch/retry".equals(request.operation());
        var response = context.managedStore()
                .inCommand(
                        MemoryStoreAccess.ID,
                        request.operation(),
                        idempotencyKey,
                        digest,
                        transaction -> persistDecision(request, context, transaction, key, original, retry));
        restore(context);
        return response;
    }

    private ExtensionResponse persistDecision(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            com.javaclaw.extension.spi.ExtensionTransaction transaction,
            MemoryContracts.Key key,
            ExtensionJob original,
            boolean retry) {
        // 并发首次调用仍由 inCommand 先兑现已提交回执，安全边界只约束新决议。
        if (original.activeUnitSequence().isPresent()) {
            throw new IllegalArgumentException("请等待当前工作单元到达安全边界后处理未决批次");
        }
        semantics.lock(transaction, request.workspaceId());
        var current = transaction
                .get(MemoryLearningState.batches(request.workspaceId()), key.id())
                .map(value -> payloads.decode(value.payload(), MemoryLearningState.Batch.class))
                .orElseThrow();
        var progress = transaction
                .get(MemoryLearningState.progress(request.workspaceId()), "cursor")
                .map(value -> payloads.decode(value.payload(), MemoryLearningState.Progress.class))
                .orElseThrow();
        if (current.revision() != request.expectedRevision()
                || !recoverable(current, original)
                || !progress.pendingBatch().equals(Optional.of(current.id()))) {
            throw new IllegalArgumentException("only the exact unresolved batch at a safe boundary can be decided");
        }
        int attempt = retry ? current.attempt() + 1 : current.attempt();
        var updated = new MemoryLearningState.Batch(
                current.id(),
                current.revision() + 1,
                current.ownerJobId(),
                attempt,
                current.from(),
                current.next(),
                current.upperSequence(),
                current.evidence(),
                current.deferred(),
                retry ? MemoryLearningState.BatchState.FROZEN : MemoryLearningState.BatchState.SKIPPED,
                retry ? "用户明确重试；保留原冻结证据与策略" : "用户明确跳过",
                context.clock().instant());
        transaction.put(
                MemoryLearningState.batches(request.workspaceId()),
                current.id(),
                current.revision(),
                payloads.encode(updated));
        if (!retry) {
            transaction.put(
                    MemoryLearningState.progress(request.workspaceId()),
                    "cursor",
                    progress.revision(),
                    payloads.encode(new MemoryLearningState.Progress(
                            progress.revision() + 1, current.next(), Optional.empty())));
        }
        String intentId = current.id() + "-" + attempt + (retry ? "-retry" : "-skip");
        var intent = new RecoveryIntent(
                intentId,
                1,
                current.id(),
                attempt,
                original.id(),
                original.definitionRevision(),
                original.frozenInput(),
                retry,
                false);
        transaction.put(intents(request.workspaceId()), intentId, 0, payloads.encode(intent));
        return new ExtensionResponse(payloads.encode(updated), updated.revision());
    }

    void restore(ExtensionExecutionContext context) throws Exception {
        var pending = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction -> MemoryStoreAccess.all(transaction, intents(context.workspaceId())).stream()
                                .map(value -> payloads.decode(value.payload(), RecoveryIntent.class))
                                .filter(value -> !value.acknowledged())
                                .toList());
        for (var intent : pending) {
            restoreIntent(context, intent);
        }
    }

    private void restoreIntent(ExtensionExecutionContext context, RecoveryIntent intent) throws Exception {
        var original = context.jobs().find(intent.originalJobId()).orElseThrow();
        if (!terminal(original.state())) {
            context.jobs()
                    .cancel(original.id(), new ExtensionJobMutation(intent.id() + "-cancel", original.revision()));
        }
        Optional<ExtensionJob> replacement = Optional.empty();
        if (intent.retry()) {
            replacement = Optional.of(context.jobs()
                    .submit(
                            payloads.encode(intent),
                            new ExtensionJobMutation(intent.id() + "-submit", 0),
                            () -> new ExtensionJobSubmission(
                                    MemoryStoreAccess.ID,
                                    context.workspaceId(),
                                    MemoryLearningState.JOB_TYPE,
                                    MemoryLearningState.DEFINITION_ID,
                                    intent.definitionRevision(),
                                    intent.frozenInput(),
                                    payloads.encode(new MemoryLearningState.Checkpoint("learn", intent.batchId())))));
        }
        acknowledge(context, intent, replacement);
    }

    private void acknowledge(
            ExtensionExecutionContext context, RecoveryIntent intent, Optional<ExtensionJob> replacement)
            throws Exception {
        context.managedStore().inTransaction(MemoryStoreAccess.ID, transaction -> {
            semantics.lock(transaction, context.workspaceId());
            var latest = transaction
                    .get(intents(context.workspaceId()), intent.id())
                    .map(value -> payloads.decode(value.payload(), RecoveryIntent.class))
                    .orElseThrow();
            if (!latest.acknowledged()) {
                transaction.put(
                        intents(context.workspaceId()),
                        intent.id(),
                        latest.revision(),
                        payloads.encode(new RecoveryIntent(
                                latest.id(),
                                latest.revision() + 1,
                                latest.batchId(),
                                latest.attempt(),
                                latest.originalJobId(),
                                latest.definitionRevision(),
                                latest.frozenInput(),
                                latest.retry(),
                                true)));
                if (replacement.isPresent()) {
                    var batch = transaction
                            .get(MemoryLearningState.batches(context.workspaceId()), intent.batchId())
                            .map(value -> payloads.decode(value.payload(), MemoryLearningState.Batch.class))
                            .orElseThrow();
                    if (batch.attempt() == intent.attempt()) {
                        transaction.put(
                                MemoryLearningState.batches(context.workspaceId()),
                                batch.id(),
                                batch.revision(),
                                payloads.encode(new MemoryLearningState.Batch(
                                        batch.id(),
                                        batch.revision() + 1,
                                        replacement.orElseThrow().id(),
                                        batch.attempt(),
                                        batch.from(),
                                        batch.next(),
                                        batch.upperSequence(),
                                        batch.evidence(),
                                        batch.deferred(),
                                        batch.state(),
                                        batch.reason(),
                                        batch.updatedAt())));
                    }
                }
            }
            return null;
        });
    }

    private static boolean recoverable(MemoryLearningState.Batch batch, ExtensionJob original) {
        if (!batch.ownerJobId().equals(original.id())) {
            return false;
        }
        return batch.state() == MemoryLearningState.BatchState.UNKNOWN
                || (batch.state() == MemoryLearningState.BatchState.FROZEN && terminal(original.state()));
    }

    private static boolean terminal(ExecutionState state) {
        return state == ExecutionState.COMPLETED || state == ExecutionState.CANCELLED || state == ExecutionState.FAILED;
    }

    private static String intents(WorkspaceId workspaceId) {
        return "learning-recovery-intents." + workspaceId;
    }

    private record RecoveryIntent(
            String id,
            long revision,
            String batchId,
            int attempt,
            String originalJobId,
            long definitionRevision,
            CanonicalPayload frozenInput,
            boolean retry,
            boolean acknowledged) {}
}
