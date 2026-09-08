package com.javaclaw.builtin.extensions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.extension.spi.DocumentRevision;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.VersionedDocument;

import static com.javaclaw.builtin.extensions.MemoryCollectionNames.settings;

/** Memory 当前值、历史、tombstone 与分页扫描的共享托管存取规则。 */
final class MemoryStoreAccess {
    static final ExtensionId ID = new ExtensionId(BuiltinExtensionIds.MEMORY);
    static final String SETTINGS_KEY = "learning";

    private final ExtensionPayloadCodec payloads;

    MemoryStoreAccess(ExtensionPayloadCodec payloads) {
        this.payloads = java.util.Objects.requireNonNull(payloads, "payloads");
    }

    MemoryContracts.LearningSettings readSettings(
            ExtensionTransaction transaction, WorkspaceId workspaceId, ExtensionExecutionContext context) {
        return transaction
                .get(settings(workspaceId), SETTINGS_KEY)
                .map(document -> payloads.decode(document.payload(), MemoryContracts.LearningSettings.class))
                .orElseGet(() -> new MemoryContracts.LearningSettings(
                        0,
                        MemoryContracts.LearningPolicy.SUGGEST,
                        context.clock().instant()));
    }

    List<MemoryContracts.Memory> allMemories(ExtensionTransaction transaction, String collection) {
        return all(transaction, collection).stream()
                .map(document -> payloads.decode(document.payload(), MemoryContracts.Memory.class))
                .toList();
    }

    List<MemoryContracts.Proposal> allProposals(ExtensionTransaction transaction, String collection) {
        return all(transaction, collection).stream()
                .map(document -> payloads.decode(document.payload(), MemoryContracts.Proposal.class))
                .toList();
    }

    MemoryContracts.Memory requireMemory(ExtensionTransaction transaction, String collection, String id) {
        VersionedDocument document = transaction
                .get(collection, id)
                .orElseThrow(() -> new IllegalArgumentException("Memory does not exist"));
        MemoryContracts.Memory memory = payloads.decode(document.payload(), MemoryContracts.Memory.class);
        if (memory.revision() != document.revision()) {
            throw new IllegalStateException("Memory payload revision differs from managed store");
        }
        return memory;
    }

    MemoryContracts.Proposal requireProposal(ExtensionTransaction transaction, String collection, String id) {
        VersionedDocument document = transaction
                .get(collection, id)
                .orElseThrow(() -> new IllegalArgumentException("Memory Proposal does not exist"));
        MemoryContracts.Proposal proposal = payloads.decode(document.payload(), MemoryContracts.Proposal.class);
        if (proposal.revision() != document.revision()) {
            throw new IllegalStateException("Memory Proposal revision differs from managed store");
        }
        return proposal;
    }

    MemoryContracts.HistoryEntry historyEntry(DocumentRevision revision) {
        MemoryContracts.Memory decoded = payloads.decode(revision.payload(), MemoryContracts.Memory.class);
        MemoryContracts.Memory normalized = copy(decoded, revision.revision(), decoded.pinned(), revision.updatedAt());
        return new MemoryContracts.HistoryEntry(
                revision.revision(), normalized, revision.tombstone(), revision.updatedAt());
    }

    void putMemory(
            ExtensionTransaction transaction,
            WorkspaceId workspaceId,
            MemoryContracts.Memory memory,
            long expectedRevision) {
        transaction.put(
                MemoryCollectionNames.memories(workspaceId), memory.id(), expectedRevision, payloads.encode(memory));
        new MemoryGraphProjection(payloads, this).project(transaction, workspaceId, memory);
    }

    ExtensionResponse response(MemoryContracts.Memory memory) {
        return new ExtensionResponse(payloads.encode(memory), memory.revision());
    }

    static MemoryContracts.Memory copy(
            MemoryContracts.Memory source, long revision, boolean pinned, Instant updatedAt) {
        return new MemoryContracts.Memory(
                source.id(),
                revision,
                source.kind(),
                source.scope(),
                source.content(),
                source.tags(),
                pinned,
                source.source(),
                source.createdAt(),
                updatedAt);
    }

    static void validateSource(Optional<MemoryContracts.Source> source, ExtensionExecutionContext context) {
        source.ifPresent(value -> {
            if (!value.workspaceId().equals(context.workspaceId())
                    || !context.evidence()
                            .containsVerbatim(
                                    context.workspaceId(), value.threadId(), value.itemId(), value.verbatim())) {
                throw new IllegalArgumentException("Memory source cannot be verified in this Workspace");
            }
        });
    }

    static List<VersionedDocument> all(ExtensionTransaction transaction, String collection) {
        List<VersionedDocument> result = new ArrayList<>();
        String cursor = "";
        while (true) {
            List<VersionedDocument> page = transaction.list(collection, cursor, 500);
            result.addAll(page);
            if (page.size() < 500) {
                return List.copyOf(result);
            }
            cursor = page.getLast().key();
        }
    }

    static long countTombstones(ExtensionTransaction transaction, String collection) {
        long count = 0;
        String cursor = "";
        while (true) {
            List<DocumentRevision> page = transaction.listTombstones(collection, cursor, 500);
            count = Math.addExact(count, page.size());
            if (page.size() < 500) {
                return count;
            }
            cursor = page.getLast().key();
        }
    }
}
