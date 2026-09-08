package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Set;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.extension.spi.DocumentRevision;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionTransaction;

/** 冲突参与者的兼容读取；历史墓碑只用于审查和拒绝候选，绝不因读取而恢复记忆。 */
final class MemoryConflictParticipants {
    private static final int HISTORY_PAGE_SIZE = 200;
    private final ExtensionPayloadCodec payloads;
    private final MemoryStoreAccess store;
    private final MemorySemantics semantics;

    MemoryConflictParticipants(ExtensionPayloadCodec payloads, MemoryStoreAccess store) {
        this.payloads = payloads;
        this.store = store;
        semantics = new MemorySemantics(payloads, store);
    }

    List<Snapshot> read(ExtensionTransaction transaction, WorkspaceId workspaceId, Set<String> ids) {
        return ids.stream()
                .sorted()
                .map(id -> read(transaction, workspaceId, id))
                .toList();
    }

    private Snapshot read(ExtensionTransaction transaction, WorkspaceId workspaceId, String id) {
        String collection = MemoryCollectionNames.memories(workspaceId);
        var state = semantics.effectivity(transaction, workspaceId, id).state();
        if (transaction.get(collection, id).isPresent()) {
            var memory = store.requireMemory(transaction, collection, id);
            return new Snapshot(memory, memory.revision(), false, state);
        }
        var latest = latestHistory(transaction, collection, id);
        if (!latest.tombstone()) {
            throw new IllegalStateException("冲突参与者的当前记录缺失，但最新历史不是删除标记：" + id);
        }
        var memory = payloads.decode(latest.payload(), MemoryContracts.Memory.class);
        // tombstone 的 payload 保留删除前正文，其 Memory.revision 不是删除操作后的 CAS 版本。
        return new Snapshot(memory, latest.revision(), true, state);
    }

    private DocumentRevision latestHistory(ExtensionTransaction transaction, String collection, String id) {
        DocumentRevision latest = null;
        long after = 0;
        while (true) {
            var page = transaction.history(collection, id, after, HISTORY_PAGE_SIZE);
            if (!page.isEmpty()) {
                latest = page.getLast();
                after = latest.revision();
            }
            if (page.size() < HISTORY_PAGE_SIZE) {
                if (latest == null) {
                    throw new IllegalStateException("冲突参与者缺少当前记录和历史，不能推断版本：" + id);
                }
                return latest;
            }
        }
    }

    /**
     * 当前存储身份与供人审查的正文分离，防止墓碑回显被误当作有效记忆。
     *
     * @param memory 当前正文或墓碑保留的删除前正文，不可空
     * @param revision 当前托管存储版本，墓碑使用 DocumentRevision 的版本
     * @param tombstone 当前是否已删除
     * @param state 未删除记录的语义状态，不可空
     */
    record Snapshot(
            MemoryContracts.Memory memory, long revision, boolean tombstone, MemoryV3Contracts.SemanticState state) {
        boolean writable() {
            return !tombstone && state == MemoryV3Contracts.SemanticState.ACTIVE;
        }

        String description() {
            String status = tombstone ? "已删除" : state == MemoryV3Contracts.SemanticState.SUPERSEDED ? "已被替代" : "当前记忆";
            return memory.id() + " · 版本 " + revision + " · " + status + "\n\n" + memory.content();
        }
    }
}
