package com.javaclaw.builtin.extensions;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ThreadId;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.extension.spi.DocumentRevision;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.VersionedDocument;

import static com.javaclaw.builtin.extensions.MemoryCollectionNames.memories;
import static com.javaclaw.builtin.extensions.MemoryCollectionNames.proposals;
import static com.javaclaw.builtin.extensions.MemoryCollectionNames.settings;

/** Memory 编辑、学习提案和人工决议的幂等命令处理器。 */
final class MemoryCommandHandler {
    private static final Set<String> MANAGEMENT_OPERATIONS =
            Set.of("management/create", "update/content", "pin/set", "pin/clear");

    private final ExtensionPayloadCodec payloads;
    private final MemoryStoreAccess store;
    private final MemorySemantics semantics;

    MemoryCommandHandler(ExtensionPayloadCodec payloads, MemoryStoreAccess store) {
        this.payloads = java.util.Objects.requireNonNull(payloads, "payloads");
        this.store = java.util.Objects.requireNonNull(store, "store");
        semantics = new MemorySemantics(payloads, store);
    }

    ExtensionResponse command(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return idempotent(request, context, transaction -> {
            if (MANAGEMENT_OPERATIONS.contains(request.operation())) {
                return managementCommand(request, context, transaction);
            }
            return domainCommand(request, context, transaction);
        });
    }

    private ExtensionResponse domainCommand(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        return switch (request.operation()) {
            case "effectivity/update" -> semantics.updateEffectivity(request, context, transaction);
            case "conflict/resolve" -> semantics.resolve(request, context, transaction);
            case "create" -> create(request, context, transaction);
            case "update" -> update(request, context, transaction);
            case "pin" -> pin(request, context, transaction);
            case "tombstone" -> tombstone(request, transaction);
            case "restore" -> restore(request, context, transaction);
            case "settings/update" -> updateSettings(request, context, transaction);
            case "proposal/submit" -> submitProposal(request, context, transaction);
            case "proposal/accept" -> decideProposal(request, context, transaction, true);
            case "proposal/reject" -> decideProposal(request, context, transaction, false);
            default -> throw new IllegalArgumentException("unknown Memory command: " + request.operation());
        };
    }

    private ExtensionResponse managementCommand(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        return switch (request.operation()) {
            case "management/create" -> managementCreate(request, context, transaction);
            case "update/content" -> updateContent(request, context, transaction);
            case "pin/set" -> setPinned(request, context, transaction, true);
            case "pin/clear" -> setPinned(request, context, transaction, false);
            default -> throw new IllegalArgumentException("unknown Memory management command: " + request.operation());
        };
    }

    ExtensionResponse submitProposal(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return idempotent(request, context, transaction -> submitProposal(request, context, transaction));
    }

    private ExtensionResponse create(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        MemoryContracts.CreateRequest input = payloads.decode(request.payload(), MemoryContracts.CreateRequest.class);
        return create(request, context, transaction, input);
    }

    private ExtensionResponse managementCreate(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        MemoryContracts.ManagementCreateRequest input =
                payloads.decode(request.payload(), MemoryContracts.ManagementCreateRequest.class);
        Optional<MemoryContracts.Source> source =
                input.sources().stream().findFirst().map(value -> source(value, context));
        MemoryContracts.CreateRequest create = new MemoryContracts.CreateRequest(
                input.id(),
                input.kind(),
                input.scope(),
                input.content(),
                Set.copyOf(input.tags().stream()
                        .map(MemoryContracts.ManagementTag::value)
                        .toList()),
                input.pinned(),
                source);
        return create(request, context, transaction, create);
    }

    private ExtensionResponse create(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            MemoryContracts.CreateRequest input) {
        requireExpected(request, 0);
        MemoryStoreAccess.validateSource(input.source(), context);
        Instant now = context.clock().instant();
        MemoryContracts.Memory memory = new MemoryContracts.Memory(
                input.id(),
                1,
                input.kind(),
                input.scope(),
                input.content(),
                input.tags(),
                input.pinned(),
                input.source(),
                now,
                now);
        store.putMemory(transaction, request.workspaceId(), memory, 0);
        return store.response(memory);
    }

    private static MemoryContracts.Source source(
            MemoryContracts.ManagementSource input, ExtensionExecutionContext context) {
        try {
            return new MemoryContracts.Source(
                    context.workspaceId(),
                    ThreadId.parse(input.threadId()),
                    ItemId.parse(input.itemId()),
                    input.verbatim());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Memory 来源 Thread 或 Item 标识不是有效 UUID", failure);
        }
    }

    private ExtensionResponse update(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        MemoryContracts.UpdateRequest input = payloads.decode(request.payload(), MemoryContracts.UpdateRequest.class);
        semantics.requireLegacyWritable(transaction, request.workspaceId(), input.id());
        MemoryContracts.Memory current = store.requireMemory(transaction, memories(request), input.id());
        requireExpected(request, current.revision());
        MemoryStoreAccess.validateSource(input.source(), context);
        MemoryContracts.Memory updated = new MemoryContracts.Memory(
                input.id(),
                next(request),
                input.kind(),
                input.scope(),
                input.content(),
                input.tags(),
                input.pinned(),
                input.source(),
                current.createdAt(),
                context.clock().instant());
        store.putMemory(transaction, request.workspaceId(), updated, request.expectedRevision());
        return store.response(updated);
    }

    private ExtensionResponse pin(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        MemoryContracts.PinRequest input = payloads.decode(request.payload(), MemoryContracts.PinRequest.class);
        return setPinned(request, context, transaction, input.id(), input.pinned());
    }

    private ExtensionResponse setPinned(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            boolean pinned) {
        MemoryContracts.Key input = payloads.decode(request.payload(), MemoryContracts.Key.class);
        return setPinned(request, context, transaction, input.id(), pinned);
    }

    private ExtensionResponse setPinned(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            String id,
            boolean pinned) {
        MemoryContracts.Memory current = store.requireMemory(transaction, memories(request), id);
        requireExpected(request, current.revision());
        MemoryContracts.Memory updated = MemoryStoreAccess.copy(
                current, next(request), pinned, context.clock().instant());
        store.putMemory(transaction, request.workspaceId(), updated, request.expectedRevision());
        return store.response(updated);
    }

    private ExtensionResponse updateContent(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        MemoryContracts.ContentUpdateRequest input =
                payloads.decode(request.payload(), MemoryContracts.ContentUpdateRequest.class);
        semantics.requireLegacyWritable(transaction, request.workspaceId(), input.id());
        MemoryContracts.Memory current = store.requireMemory(transaction, memories(request), input.id());
        requireExpected(request, current.revision());
        MemoryContracts.Memory updated = new MemoryContracts.Memory(
                current.id(),
                next(request),
                input.kind(),
                input.scope(),
                input.content(),
                current.tags(),
                current.pinned(),
                current.source(),
                current.createdAt(),
                context.clock().instant());
        store.putMemory(transaction, request.workspaceId(), updated, request.expectedRevision());
        return store.response(updated);
    }

    private ExtensionResponse tombstone(ExtensionRequest request, ExtensionTransaction transaction) {
        MemoryContracts.Key key = payloads.decode(request.payload(), MemoryContracts.Key.class);
        store.requireMemory(transaction, memories(request), key.id());
        // 删除不隐含拒绝其他候选；在同一 head 事务内阻止制造新的悬空冲突。
        semantics.requireNoPendingReference(transaction, request.workspaceId(), key.id());
        transaction.delete(memories(request), key.id(), request.expectedRevision());
        return new ExtensionResponse(
                payloads.encode(new DocumentContracts.Deleted(key.id())), Math.addExact(request.expectedRevision(), 1));
    }

    private ExtensionResponse restore(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        MemoryContracts.RestoreRequest input = payloads.decode(request.payload(), MemoryContracts.RestoreRequest.class);
        semantics.requireLegacyWritable(transaction, request.workspaceId(), input.id());
        DocumentRevision source =
                transaction.history(memories(request), input.id(), input.sourceRevision() - 1, 1).stream()
                        .filter(value -> value.revision() == input.sourceRevision() && !value.tombstone())
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("restorable Memory revision does not exist"));
        MemoryContracts.Memory historical = payloads.decode(source.payload(), MemoryContracts.Memory.class);
        MemoryContracts.Memory restored = MemoryStoreAccess.copy(
                historical, next(request), historical.pinned(), context.clock().instant());
        store.putMemory(transaction, request.workspaceId(), restored, request.expectedRevision());
        return store.response(restored);
    }

    private ExtensionResponse updateSettings(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        MemoryContracts.LearningSettingsUpdate input =
                payloads.decode(request.payload(), MemoryContracts.LearningSettingsUpdate.class);
        long revision = transaction
                .get(settings(request), MemoryStoreAccess.SETTINGS_KEY)
                .map(VersionedDocument::revision)
                .orElse(0L);
        requireExpected(request, revision);
        MemoryContracts.LearningSettings updated = new MemoryContracts.LearningSettings(
                next(request), input.policy(), context.clock().instant());
        transaction.put(settings(request), MemoryStoreAccess.SETTINGS_KEY, revision, payloads.encode(updated));
        return new ExtensionResponse(payloads.encode(updated), updated.revision());
    }

    private ExtensionResponse submitProposal(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        requireExpected(request, 0);
        MemoryContracts.LearningProposalRequest input =
                payloads.decode(request.payload(), MemoryContracts.LearningProposalRequest.class);
        MemoryContracts.LearningSettings learning = store.readSettings(transaction, request.workspaceId(), context);
        if (learning.policy() == MemoryContracts.LearningPolicy.OFF) {
            return ignoredLearningResult();
        }
        List<MemoryContracts.Memory> current = semantics.activeMemories(transaction, request.workspaceId());
        Set<MemoryContracts.ProposalConcern> concerns = MemoryLearningRules.classify(input, current, context);
        boolean automatic = learning.policy() == MemoryContracts.LearningPolicy.AUTO_LOW_RISK && concerns.isEmpty();
        Instant now = context.clock().instant();
        Optional<MemoryContracts.Memory> memory = automatic
                ? Optional.of(createLearnedMemory(input, now, transaction, memories(request)))
                : Optional.empty();
        MemoryContracts.Proposal proposal = new MemoryContracts.Proposal(
                input.id(),
                1,
                input,
                concerns,
                automatic ? MemoryContracts.ProposalState.AUTO_ACCEPTED : MemoryContracts.ProposalState.PENDING,
                memory.map(MemoryContracts.Memory::id),
                now,
                now);
        transaction.put(proposals(request), proposal.id(), 0, payloads.encode(proposal));
        semantics.registerConflict(transaction, request.workspaceId(), proposal, current, now);
        MemoryContracts.LearningAction action =
                automatic ? MemoryContracts.LearningAction.AUTO_ACCEPTED : MemoryContracts.LearningAction.PROPOSED;
        MemoryContracts.LearningResult result =
                new MemoryContracts.LearningResult(action, Optional.of(proposal), memory);
        return new ExtensionResponse(payloads.encode(result), proposal.revision());
    }

    private ExtensionResponse decideProposal(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            boolean accept) {
        MemoryContracts.ProposalDecision decision =
                payloads.decode(request.payload(), MemoryContracts.ProposalDecision.class);
        MemoryContracts.Proposal current = store.requireProposal(transaction, proposals(request), decision.id());
        requireExpected(request, current.revision());
        semantics.requireNoPendingConflict(transaction, request.workspaceId(), current.id());
        if (current.state() != MemoryContracts.ProposalState.PENDING) {
            throw new IllegalArgumentException("only pending Memory Proposal can be decided");
        }
        Instant now = context.clock().instant();
        Optional<MemoryContracts.Memory> memory = accept
                ? Optional.of(createLearnedMemory(current.candidate(), now, transaction, memories(request)))
                : Optional.empty();
        MemoryContracts.Proposal updated = new MemoryContracts.Proposal(
                current.id(),
                next(request),
                current.candidate(),
                current.concerns(),
                accept ? MemoryContracts.ProposalState.ACCEPTED : MemoryContracts.ProposalState.REJECTED,
                memory.map(MemoryContracts.Memory::id),
                current.createdAt(),
                now);
        transaction.put(proposals(request), updated.id(), request.expectedRevision(), payloads.encode(updated));
        return new ExtensionResponse(payloads.encode(updated), updated.revision());
    }

    private ExtensionResponse ignoredLearningResult() {
        MemoryContracts.LearningResult result = new MemoryContracts.LearningResult(
                MemoryContracts.LearningAction.IGNORED, Optional.empty(), Optional.empty());
        return new ExtensionResponse(payloads.encode(result), 0);
    }

    private MemoryContracts.Memory createLearnedMemory(
            MemoryContracts.LearningProposalRequest input,
            Instant now,
            ExtensionTransaction transaction,
            String collection) {
        String id = "learned-" + input.id();
        MemoryContracts.Memory memory = new MemoryContracts.Memory(
                id,
                1,
                input.kind(),
                input.scope(),
                input.content(),
                input.tags(),
                false,
                Optional.of(input.source()),
                now,
                now);
        store.putMemory(transaction, input.source().workspaceId(), memory, 0);
        return memory;
    }

    private ExtensionResponse idempotent(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            com.javaclaw.extension.spi.ManagedExtensionStore.TransactionWork<ExtensionResponse> work)
            throws Exception {
        String key = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("Memory command requires idempotency key"));
        CanonicalPayload digest = payloads.encode(new CommandDigest(
                request.workspaceId().toString(), request.operation(), request.expectedRevision(), request.payload()));
        return context.managedStore()
                .inCommand(MemoryStoreAccess.ID, request.operation(), key, digest.sha256(), transaction -> {
                    semantics.lock(transaction, request.workspaceId());
                    return work.execute(transaction);
                });
    }

    private static void requireExpected(ExtensionRequest request, long actual) {
        if (request.expectedRevision() != actual) {
            throw new IllegalArgumentException("Memory expected revision differs from current revision");
        }
    }

    private static long next(ExtensionRequest request) {
        return Math.addExact(request.expectedRevision(), 1);
    }

    private record CommandDigest(
            String workspaceId, String operation, long expectedRevision, CanonicalPayload payload) {}
}
