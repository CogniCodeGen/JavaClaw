package com.javaclaw.memory.store;

import com.javaclaw.memory.model.KnowledgeChunk;
import com.javaclaw.memory.model.MemoryContextIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 知识分块写入与同文档替换补偿，全部复用所属存储的写锁和审计。 */
final class MemoryStoreKnowledge {
    private final MemoryStore store;
    private final int dimension;

    MemoryStoreKnowledge(MemoryStore store, int dimension) {
        this.store = store;
        this.dimension = dimension;
    }

    void addKnowledgeChunk(KnowledgeChunk c, String actor) {
        store.withProjectionLock(() -> {
            if (c.id == null) c.id = UUID.randomUUID().toString();
            MemoryContextIndex.refresh(c);
            c.entityId = store.root().knowledge.add(c);
            store.root().knowledge.store();
            store.logInternal("ADD", "KnowledgeChunk", c.id, actor, MemoryStore.trunc(c.docName));
        });
    }

    /**
     * 在当前存储中整批替换同名文档；调用方须先完成全部嵌入。
     * 成功路径只持久化一次知识映射。失败补偿不构成跨存储或崩溃事务。
     */
    int replaceKnowledgeByDoc(String docName, List<KnowledgeChunk> chunks, String actor) {
        java.util.Objects.requireNonNull(docName, "docName");
        List<KnowledgeChunk> staged = List.copyOf(chunks);
        return store.writeCall(() -> {
            if (staged.isEmpty()) return 0;
            List<KnowledgeChunk> previous = new ArrayList<>();
            List<Long> previousIds = new ArrayList<>();
            java.util.Set<String> ids = new java.util.HashSet<>();
            java.util.Set<String> stagedIds = new java.util.HashSet<>();
            store.root().knowledge.iterateIndexed((entityId, c) -> {
                if (docName.equals(c.docName)) {
                    previous.add(c);
                    previousIds.add(entityId);
                }
                if (c.id != null) ids.add(c.id);
            });
            for (KnowledgeChunk c : staged) {
                if (!docName.equals(c.docName)) {
                    throw new IllegalArgumentException("替换分块的文档名不一致");
                }
                if (c.embedding == null || c.embedding.length != dimension) {
                    throw new IllegalArgumentException("替换分块的嵌入必须符合当前索引维度: " + dimension);
                }
                for (float value : c.embedding) {
                    if (!Float.isFinite(value)) {
                        throw new IllegalArgumentException("替换分块的嵌入包含非有限数值");
                    }
                }
                if (c.id == null) c.id = UUID.randomUUID().toString();
                if (!ids.add(c.id)) {
                    throw new IllegalArgumentException("替换分块必须使用新的唯一标识");
                }
                stagedIds.add(c.id);
                MemoryContextIndex.refresh(c);
            }

            java.util.Set<Long> inserted = new java.util.LinkedHashSet<>();
            try {
                // 新批次全部加入后才移除旧版本，避免追加失败先丢失旧内容。
                // GigaMap 4.1 的 addAll 在索引更新前推进计数，并补偿普通异常。
                store.root().knowledge.addAll(staged);
                store.root().knowledge.iterateIndexed((entityId, c) -> {
                    if (stagedIds.contains(c.id)) {
                        c.entityId = entityId;
                        inserted.add(entityId);
                    }
                });
                if (inserted.size() != staged.size()) {
                    throw new IllegalStateException("替换分块未全部加入当前知识映射");
                }
                for (long entityId : previousIds) store.root().knowledge.removeById(entityId);
                store.root().knowledge.store();
            } catch (RuntimeException | Error failure) {
                // 扫描实际映射，不依赖失败 addAll 的返回值或不完整的 bitmap 索引。
                try {
                    store.root().knowledge.iterateIndexed((entityId, c) -> {
                        if (stagedIds.contains(c.id)) inserted.add(entityId);
                    });
                } catch (RuntimeException | Error rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                for (long id : inserted) {
                    try {
                        store.root().knowledge.removeById(id);
                    } catch (RuntimeException | Error rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
                List<KnowledgeChunk> remaining = new ArrayList<>();
                try {
                    store.root().knowledge.iterate(remaining::add);
                    List<KnowledgeChunk> missing = new ArrayList<>();
                    for (KnowledgeChunk c : previous) {
                        if (remaining.stream().noneMatch(current -> current == c)) missing.add(c);
                    }
                    for (KnowledgeChunk c : missing) {
                        try {
                            store.root().knowledge.addAll(List.of(c));
                        } catch (RuntimeException | Error rollbackFailure) {
                            failure.addSuppressed(rollbackFailure);
                        }
                    }
                    store.root().knowledge.iterateIndexed((entityId, c) -> {
                        if (c.entityId != entityId
                                && previous.stream().anyMatch(old -> old == c)) {
                            // 旧对象已持久化过，update 登记字段变更供这次 store 重存。
                            try {
                                store.root().knowledge.update(c, restored -> restored.entityId = entityId);
                            } catch (RuntimeException | Error rollbackFailure) {
                                failure.addSuppressed(rollbackFailure);
                            }
                        }
                    });
                    store.root().knowledge.store();
                } catch (RuntimeException | Error rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
            // 知识已提交；后置审计/observer 失败不能再撤销已持久化的新版本。
            try {
                store.logInternal("REPLACE", "KnowledgeChunk", docName, actor,
                        previous.size() + " -> " + staged.size() + " 块");
            } catch (RuntimeException auditFailure) {
                throw new IllegalStateException("文档分块已替换，但审计或变更通知失败", auditFailure);
            }
            return staged.size();
        });
    }

    /** 删除某文档的全部分块,返回删除数量。 */
    int removeKnowledgeByDoc(String docName, String actor) {
        int[] removed = {0};
        store.withProjectionLock(() -> {
            List<Long> ids = new ArrayList<>();
            store.root().knowledge.iterate(c -> {
                if (docName.equals(c.docName)) ids.add(c.entityId);
            });
            for (long id : ids) store.root().knowledge.removeById(id);
            if (!ids.isEmpty()) {
                store.root().knowledge.store();
                store.logInternal("REMOVE", "KnowledgeChunk", docName, actor, ids.size() + " 块");
            }
            removed[0] = ids.size();
        });
        return removed[0];
    }

    /** 清空全部知识分块。 */
    void clearKnowledge(String actor) {
        store.withProjectionLock(() -> {
            store.root().knowledge.removeAll();
            store.root().knowledge.store();
            store.logInternal("CLEAR", "KnowledgeChunk", null, actor, "");
        });
    }

    /** 原地更新知识分块(通过 GigaMap.update 通知索引重建),供重建索引时回填新向量。 */
    void updateKnowledgeChunk(KnowledgeChunk c, java.util.function.Consumer<KnowledgeChunk> mutator, String actor) {
        store.withProjectionLock(() -> {
            store.root().knowledge.update(c, value -> {
                mutator.accept(value);
                MemoryContextIndex.refresh(value);
            });
            store.root().knowledge.store();
            store.logInternal("UPDATE", "KnowledgeChunk", c.id, actor, MemoryStore.trunc(c.docName));
        });
    }

}
