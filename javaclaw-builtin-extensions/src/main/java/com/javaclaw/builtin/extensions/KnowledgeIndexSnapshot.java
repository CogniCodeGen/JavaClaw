package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Objects;

import com.javaclaw.builtin.contracts.KnowledgeContracts;

/** 从一个 Managed Store 事务读取的 Knowledge 当前索引快照。 */
record KnowledgeIndexSnapshot(List<Entry> entries) {
    KnowledgeIndexSnapshot {
        entries = List.copyOf(entries);
    }

    /** 当前来源、其激活 Generation 与全部有序文本块。 */
    record Entry(
            KnowledgeContracts.Source source,
            KnowledgeContracts.Generation generation,
            List<KnowledgeContracts.Chunk> chunks) {
        Entry {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(generation, "generation");
            chunks = List.copyOf(chunks);
            if (!source.activeGenerationId().equals(generation.id())
                    || !source.id().equals(generation.sourceId())
                    || source.revision() != generation.sourceRevision()) {
                throw new IllegalArgumentException("Knowledge index entry identity is inconsistent");
            }
        }
    }
}
