package com.javaclaw.builtin.extensions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.VersionedDocument;

/** Memory 统一语义版本、有效性与人工冲突事务；所有写入先 CAS head，事务内不得执行模型或外域调用。 */
final class MemorySemantics {
    private final ExtensionPayloadCodec payloads;
    private final MemoryStoreAccess store;

    MemorySemantics(ExtensionPayloadCodec payloads, MemoryStoreAccess store) {
        this.payloads = payloads;
        this.store = store;
    }

    long head(ExtensionTransaction transaction, WorkspaceId workspaceId) {
        return transaction
                .get(headCollection(workspaceId), "head")
                .map(VersionedDocument::revision)
                .orElse(0L);
    }

    long lock(ExtensionTransaction transaction, WorkspaceId workspaceId) {
        long revision = head(transaction, workspaceId);
        return lock(transaction, workspaceId, revision);
    }

    long lock(ExtensionTransaction transaction, WorkspaceId workspaceId, long expectedRevision) {
        long next = Math.addExact(expectedRevision, 1);
        transaction.put(headCollection(workspaceId), "head", expectedRevision, payloads.encode(new Head(next)));
        return next;
    }

    MemoryV3Contracts.Effectivity effectivity(ExtensionTransaction transaction, WorkspaceId workspaceId, String id) {
        return transaction
                .get(effects(workspaceId), id)
                .map(value -> payloads.decode(value.payload(), MemoryV3Contracts.Effectivity.class))
                .orElseGet(() -> MemoryV3Contracts.Effectivity.legacy(id));
    }

    boolean active(ExtensionTransaction transaction, WorkspaceId workspaceId, String id) {
        return effectivity(transaction, workspaceId, id).state() == MemoryV3Contracts.SemanticState.ACTIVE;
    }

    List<MemoryContracts.Memory> activeMemories(ExtensionTransaction transaction, WorkspaceId workspaceId) {
        // 已替代事实不能成为新冲突参与者；时间条件另由有效性规则判断，不等同于语义替代。
        return store.allMemories(transaction, MemoryCollectionNames.memories(workspaceId)).stream()
                .filter(memory -> active(transaction, workspaceId, memory.id()))
                .toList();
    }

    void requireLegacyWritable(ExtensionTransaction transaction, WorkspaceId workspaceId, String id) {
        MemoryV3Contracts.Effectivity current = effectivity(transaction, workspaceId, id);
        if (current.state() != MemoryV3Contracts.SemanticState.ACTIVE || !current.unconditional()) {
            throw new IllegalArgumentException("此记忆含有效性或替代关系，请通过新版有效性及冲突管理修改");
        }
    }

    List<MemoryV3Contracts.SearchMatch> search(
            ExtensionTransaction transaction,
            WorkspaceId workspaceId,
            MemoryContracts.SearchRequest search,
            Instant now,
            boolean legacy) {
        return store.allMemories(transaction, MemoryCollectionNames.memories(workspaceId)).stream()
                .map(memory ->
                        new MemoryV3Contracts.SearchMatch(memory, effectivity(transaction, workspaceId, memory.id())))
                .filter(match -> match.effectivity().effectiveAt(now))
                .filter(match -> !legacy || match.effectivity().unconditional())
                .filter(match -> ExtensionSearch.matchesValue(
                        search.scopes(), match.memory().scope()))
                .filter(match -> ExtensionSearch.containsAll(match.memory().tags(), search.tags()))
                .filter(match -> ExtensionSearch.contains(
                                search.query(),
                                match.memory().scope(),
                                match.memory().content())
                        || ExtensionSearch.contains(
                                search.query(), match.memory().tags()))
                .sorted(Comparator.comparing((MemoryV3Contracts.SearchMatch value) ->
                                value.memory().pinned())
                        .reversed()
                        .thenComparing(value -> value.memory().updatedAt(), Comparator.reverseOrder())
                        .thenComparing(value -> value.memory().id()))
                .limit(search.limit())
                .toList();
    }

    ExtensionResponse updateEffectivity(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        MemoryV3Contracts.EffectivityUpdate input =
                payloads.decode(request.payload(), MemoryV3Contracts.EffectivityUpdate.class);
        MemoryContracts.Memory memory =
                store.requireMemory(transaction, MemoryCollectionNames.memories(request), input.id());
        requireRevision(request.expectedRevision(), memory.revision());
        MemoryV3Contracts.Effectivity old = effectivity(transaction, request.workspaceId(), input.id());
        if (old.state() != MemoryV3Contracts.SemanticState.ACTIVE) {
            throw new IllegalArgumentException("被替代记忆不能通过编辑条件重新激活");
        }
        MemoryV3Contracts.Effectivity updated = new MemoryV3Contracts.Effectivity(
                input.id(),
                old.revision() + 1,
                old.state(),
                input.validFrom(),
                input.validUntil(),
                input.condition(),
                old.replacements());
        transaction.put(effects(request.workspaceId()), input.id(), old.revision(), payloads.encode(updated));
        MemoryContracts.Memory touched = MemoryStoreAccess.copy(
                memory, memory.revision() + 1, memory.pinned(), context.clock().instant());
        store.putMemory(transaction, request.workspaceId(), touched, memory.revision());
        return new ExtensionResponse(
                payloads.encode(new MemoryV3Contracts.SearchMatch(touched, updated)), touched.revision());
    }

    MemoryV3Contracts.Graph graph(
            ExtensionTransaction transaction,
            WorkspaceId workspaceId,
            MemoryV3Contracts.GraphRequest query,
            Instant now) {
        List<MemoryContracts.Memory> all =
                store.allMemories(transaction, MemoryCollectionNames.memories(workspaceId)).stream()
                        .filter(memory -> ExtensionSearch.contains(query.query(), memory.content(), memory.scope()))
                        .sorted(Comparator.comparing(MemoryContracts.Memory::id))
                        .limit(query.limit() + 1L)
                        .toList();
        List<MemoryContracts.Memory> page = all.stream().limit(query.limit()).toList();
        Set<String> ids = page.stream().map(MemoryContracts.Memory::id).collect(java.util.stream.Collectors.toSet());
        List<MemoryV3Contracts.GraphNode> nodes = new ArrayList<>();
        List<MemoryV3Contracts.GraphEdge> edges = new ArrayList<>();
        for (MemoryContracts.Memory memory : page) {
            MemoryV3Contracts.Effectivity effect = effectivity(transaction, workspaceId, memory.id());
            String condition = effect.condition()
                    + effect.validFrom().map(value -> " 起始 " + value).orElse("")
                    + effect.validUntil().map(value -> " 截止（不含） " + value).orElse("");
            String label = MemoryGraphLabels.label(memory.content());
            nodes.add(new MemoryV3Contracts.GraphNode(
                    memory.id(), label, memory.revision(), effect.state(), effect.effectiveAt(now), condition));
            effect.replacements().stream()
                    .sorted()
                    .filter(ids::contains)
                    .forEach(target -> edges.add(new MemoryV3Contracts.GraphEdge(memory.id(), target, "REPLACED_BY")));
        }
        return new MemoryV3Contracts.Graph(nodes, edges, head(transaction, workspaceId), all.size() > query.limit());
    }

    ExtensionResponse resolve(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        MemoryV3Contracts.ConflictDecision decision =
                payloads.decode(request.payload(), MemoryV3Contracts.ConflictDecision.class);
        MemoryV3Contracts.Conflict conflict = transaction
                .get(conflicts(request.workspaceId()), decision.id())
                .map(value -> payloads.decode(value.payload(), MemoryV3Contracts.Conflict.class))
                .orElseThrow(() -> new IllegalArgumentException("Memory conflict does not exist"));
        requireRevision(request.expectedRevision(), conflict.revision());
        requireRevision(decision.expectedMemoryRevision(), head(transaction, request.workspaceId()) - 1);
        if (conflict.state() != MemoryV3Contracts.ConflictState.PENDING
                || !decision.expectedMemoryRevisions().keySet().equals(conflict.memoryIds())) {
            throw new IllegalArgumentException("冲突已决议或缺少完整的记忆版本");
        }
        var participants = new MemoryConflictParticipants(payloads, store)
                .read(transaction, request.workspaceId(), conflict.memoryIds());
        validateParticipants(participants, decision);
        List<MemoryContracts.Memory> memories = participants.stream()
                .map(MemoryConflictParticipants.Snapshot::memory)
                .toList();
        MemoryContracts.Proposal proposal =
                store.requireProposal(transaction, MemoryCollectionNames.proposals(request), conflict.proposalId());
        if (proposal.state() != MemoryContracts.ProposalState.PENDING) {
            throw new IllegalArgumentException("冲突候选已经处理");
        }
        Instant now = context.clock().instant();
        boolean keep = decision.resolution() == MemoryV3Contracts.Resolution.KEEP_EXISTING;
        validateDecision(decision);
        validateCoexistence(transaction, request.workspaceId(), memories, decision);
        String newId = "resolved-" + conflict.id();
        if (!keep) {
            createResolved(transaction, request.workspaceId(), proposal, decision, newId, now);
            if (decision.resolution() != MemoryV3Contracts.Resolution.COEXIST) {
                for (MemoryContracts.Memory memory : memories) {
                    supersede(transaction, request.workspaceId(), memory, newId, now);
                }
            }
        }
        return commitDecision(transaction, request.workspaceId(), proposal, conflict, keep, newId, now);
    }

    private void validateParticipants(
            List<MemoryConflictParticipants.Snapshot> participants, MemoryV3Contracts.ConflictDecision decision) {
        // head 已锁定；包含墓碑的完整版本集合必须全部匹配，拒绝候选也不能绕过并发修改。
        for (var participant : participants) {
            requireRevision(
                    decision.expectedMemoryRevisions().get(participant.memory().id()), participant.revision());
            if (decision.resolution() != MemoryV3Contracts.Resolution.KEEP_EXISTING && !participant.writable()) {
                throw new IllegalArgumentException("冲突参与者已删除或被替代；请保留当前记忆并关闭过时候选，再基于当前记录审查");
            }
        }
    }

    private ExtensionResponse commitDecision(
            ExtensionTransaction transaction,
            WorkspaceId workspaceId,
            MemoryContracts.Proposal proposal,
            MemoryV3Contracts.Conflict conflict,
            boolean keep,
            String newId,
            Instant now) {
        MemoryContracts.Proposal updatedProposal = new MemoryContracts.Proposal(
                proposal.id(),
                proposal.revision() + 1,
                proposal.candidate(),
                proposal.concerns(),
                keep ? MemoryContracts.ProposalState.REJECTED : MemoryContracts.ProposalState.ACCEPTED,
                keep ? Optional.empty() : Optional.of(newId),
                proposal.createdAt(),
                now);
        transaction.put(
                MemoryCollectionNames.proposals(workspaceId),
                proposal.id(),
                proposal.revision(),
                payloads.encode(updatedProposal));
        MemoryV3Contracts.Conflict resolved = new MemoryV3Contracts.Conflict(
                conflict.id(),
                conflict.revision() + 1,
                conflict.proposalId(),
                conflict.memoryIds(),
                conflict.fingerprint(),
                keep ? MemoryV3Contracts.ConflictState.REJECTED : MemoryV3Contracts.ConflictState.RESOLVED,
                now);
        transaction.put(conflicts(workspaceId), conflict.id(), conflict.revision(), payloads.encode(resolved));
        return new ExtensionResponse(payloads.encode(resolved), resolved.revision());
    }

    private static void validateDecision(MemoryV3Contracts.ConflictDecision decision) {
        if (decision.resolution() != MemoryV3Contracts.Resolution.KEEP_EXISTING
                && decision.content().isBlank()) {
            throw new IllegalArgumentException("请提供确认后的正文");
        }
        if (decision.resolution() == MemoryV3Contracts.Resolution.COEXIST
                && decision.validFrom().isEmpty()
                && decision.validUntil().isEmpty()
                && decision.condition().isBlank()) {
            throw new IllegalArgumentException("并存冲突必须填写明确有效时间或人工条件");
        }
    }

    private void validateCoexistence(
            ExtensionTransaction transaction,
            WorkspaceId workspaceId,
            List<MemoryContracts.Memory> memories,
            MemoryV3Contracts.ConflictDecision decision) {
        if (decision.resolution() != MemoryV3Contracts.Resolution.COEXIST
                || !decision.condition().isBlank()) {
            return;
        }
        for (var memory : memories) {
            var old = effectivity(transaction, workspaceId, memory.id());
            if (!old.condition().isBlank() || old.state() != MemoryV3Contracts.SemanticState.ACTIVE) {
                continue;
            }
            boolean before = endsBefore(old.validUntil(), decision.validFrom());
            boolean after = endsBefore(decision.validUntil(), old.validFrom());
            if (!before && !after) {
                throw new IllegalArgumentException("时间并存必须与旧记忆有效区间不重叠；请先明确旧记忆有效期，或使用仅供人工判断的条件");
            }
        }
    }

    private static boolean endsBefore(Optional<Instant> end, Optional<Instant> start) {
        return end.isPresent() && start.isPresent() && !end.orElseThrow().isAfter(start.orElseThrow());
    }

    private void createResolved(
            ExtensionTransaction transaction,
            WorkspaceId workspaceId,
            MemoryContracts.Proposal proposal,
            MemoryV3Contracts.ConflictDecision decision,
            String newId,
            Instant now) {
        var candidate = proposal.candidate();
        var created = new MemoryContracts.Memory(
                newId,
                1,
                candidate.kind(),
                candidate.scope(),
                decision.content(),
                candidate.tags(),
                false,
                Optional.of(candidate.source()),
                now,
                now);
        store.putMemory(transaction, workspaceId, created, 0);
        var effect = new MemoryV3Contracts.Effectivity(
                newId,
                1,
                MemoryV3Contracts.SemanticState.ACTIVE,
                decision.validFrom(),
                decision.validUntil(),
                decision.condition(),
                Set.of());
        transaction.put(effects(workspaceId), newId, 0, payloads.encode(effect));
    }

    private void supersede(
            ExtensionTransaction transaction,
            WorkspaceId workspaceId,
            MemoryContracts.Memory memory,
            String replacementId,
            Instant now) {
        var old = effectivity(transaction, workspaceId, memory.id());
        var superseded = new MemoryV3Contracts.Effectivity(
                memory.id(),
                old.revision() + 1,
                MemoryV3Contracts.SemanticState.SUPERSEDED,
                old.validFrom(),
                old.validUntil(),
                old.condition(),
                Set.of(replacementId));
        transaction.put(effects(workspaceId), memory.id(), old.revision(), payloads.encode(superseded));
        store.putMemory(
                transaction,
                workspaceId,
                MemoryStoreAccess.copy(memory, memory.revision() + 1, memory.pinned(), now),
                memory.revision());
    }

    void registerConflict(
            ExtensionTransaction transaction,
            WorkspaceId workspaceId,
            MemoryContracts.Proposal proposal,
            List<MemoryContracts.Memory> memories,
            Instant now) {
        if (proposal.state() != MemoryContracts.ProposalState.PENDING
                || !proposal.concerns().contains(MemoryContracts.ProposalConcern.CONFLICT)) {
            return;
        }
        var candidate = proposal.candidate();
        Set<String> participants = memories.stream()
                .filter(memory -> active(transaction, workspaceId, memory.id()))
                .filter(memory -> memory.scope().equals(candidate.scope()))
                .filter(memory -> !java.util.Collections.disjoint(memory.tags(), candidate.tags()))
                .map(MemoryContracts.Memory::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!participants.isEmpty()) {
            String id = "conflict-" + proposal.id();
            var conflict = new MemoryV3Contracts.Conflict(
                    id,
                    1,
                    proposal.id(),
                    participants,
                    payloads.encode(Map.of("candidate", candidate, "participants", participants))
                            .sha256(),
                    MemoryV3Contracts.ConflictState.PENDING,
                    now);
            transaction.put(conflicts(workspaceId), id, 0, payloads.encode(conflict));
        }
    }

    void requireNoPendingConflict(ExtensionTransaction transaction, WorkspaceId workspaceId, String proposalId) {
        boolean pending = MemoryStoreAccess.all(transaction, conflicts(workspaceId)).stream()
                .map(value -> payloads.decode(value.payload(), MemoryV3Contracts.Conflict.class))
                .anyMatch(value -> value.proposalId().equals(proposalId)
                        && value.state() == MemoryV3Contracts.ConflictState.PENDING);
        if (pending) {
            throw new IllegalArgumentException("该提案有未决冲突，请通过冲突决议处理");
        }
    }

    void requireNoPendingReference(ExtensionTransaction transaction, WorkspaceId workspaceId, String memoryId) {
        boolean referenced = MemoryStoreAccess.all(transaction, conflicts(workspaceId)).stream()
                .map(value -> payloads.decode(value.payload(), MemoryV3Contracts.Conflict.class))
                .anyMatch(value -> value.state() == MemoryV3Contracts.ConflictState.PENDING
                        && value.memoryIds().contains(memoryId));
        if (referenced) {
            throw new IllegalArgumentException("该记忆仍有待处理冲突，请先在记忆冲突模块处理后再删除");
        }
    }

    static String effects(WorkspaceId workspaceId) {
        return "effectivity." + workspaceId;
    }

    static String conflicts(WorkspaceId workspaceId) {
        return "conflicts." + workspaceId;
    }

    private static String headCollection(WorkspaceId workspaceId) {
        return "semantic-head." + workspaceId;
    }

    private static void requireRevision(long expected, long actual) {
        if (expected != actual) {
            throw new IllegalArgumentException("Memory revision changed; refresh before applying the decision");
        }
    }

    private record Head(long revision) {}
}
