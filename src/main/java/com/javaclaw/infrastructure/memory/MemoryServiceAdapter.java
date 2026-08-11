package com.javaclaw.infrastructure.memory;

import com.javaclaw.agent.expert.KnowledgeExpert;
import com.javaclaw.application.error.NotFoundException;
import com.javaclaw.application.memory.MemoryApplicationService.ChangeItem;
import com.javaclaw.application.memory.MemoryApplicationService.CorrectionItem;
import com.javaclaw.application.memory.MemoryApplicationService.EmbeddingState;
import com.javaclaw.application.memory.MemoryApplicationService.EntityItem;
import com.javaclaw.application.memory.MemoryApplicationService.EpisodeItem;
import com.javaclaw.application.memory.MemoryApplicationService.FactItem;
import com.javaclaw.application.memory.MemoryApplicationService.KnowledgeDocument;
import com.javaclaw.application.memory.MemoryApplicationService.PersonaDraft;
import com.javaclaw.application.memory.MemoryApplicationService.Snapshot;
import com.javaclaw.application.memory.MemoryApplicationService.Statistics;
import com.javaclaw.application.memory.MemoryPort;
import com.javaclaw.memory.MemoryService;
import com.javaclaw.memory.graph.MemoryGraph;
import com.javaclaw.memory.model.ChangeLogEntry;
import com.javaclaw.memory.model.CorrectionRecord;
import com.javaclaw.memory.model.EntityNode;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.model.KnowledgeChunk;
import com.javaclaw.memory.model.MemoryStats;
import com.javaclaw.memory.model.Persona;
import com.javaclaw.platform.storage.AtomicContentStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 将现有记忆引擎和知识库实现适配为不可变 Application 快照。 */
public final class MemoryServiceAdapter implements MemoryPort {

    private static final int CHANGE_LIMIT = 500;
    private final MemoryService memory;
    private final KnowledgeExpert knowledge;
    private final AtomicContentStore files;

    public MemoryServiceAdapter(
            MemoryService memory,
            KnowledgeExpert knowledge,
            AtomicContentStore files) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
        this.files = Objects.requireNonNull(files, "files");
    }

    @Override
    public Snapshot load() {
        List<Fact> facts = memory.facts();
        List<Episode> episodes = memory.episodes();
        Map<String, Integer> factsByEpisode = new HashMap<>();
        Map<String, Integer> factsByEntity = new HashMap<>();
        for (Fact fact : facts) {
            if (fact.source != null && fact.source.id != null) {
                factsByEpisode.merge(fact.source.id, 1, Integer::sum);
            }
            if (fact.about == null) continue;
            for (EntityNode entity : fact.about) {
                if (entity != null && entity.name != null) {
                    factsByEntity.merge(entity.name, 1, Integer::sum);
                }
            }
        }
        return new Snapshot(statistics(memory.stats()), facts.stream().map(this::fact).toList(),
                episodes.stream().map(item -> episode(item,
                        factsByEpisode.getOrDefault(item.id, 0))).toList(),
                memory.entities().stream().map(item -> entity(item,
                        factsByEntity.getOrDefault(item.name, 0))).toList(),
                documents(knowledge.allKnowledgeChunks()), persona(memory.getPersona()),
                memory.corrections().stream().filter(Objects::nonNull).map(this::correction).toList(),
                memory.recentChangeLog(CHANGE_LIMIT).stream().map(this::change).toList(),
                new EmbeddingState(memory.embeddingError(), memory.pendingCount()));
    }

    @Override public String probeEmbedding() { return memory.probeEmbedding(); }
    @Override public int promoteAllPending() { return memory.promoteAllPending(); }
    @Override public MemoryGraph graph() { return memory.graph(); }
    @Override public void addFact(String section, String text) { memory.addFact(section, text); }

    @Override
    public void editFact(String id, String text) {
        memory.editFact(requireFact(id), text);
    }

    @Override
    public void toggleFactPin(String id) {
        memory.togglePin(requireFact(id));
    }

    @Override
    public void restoreFact(String id) {
        memory.restoreFact(requireFact(id));
    }

    @Override
    public int deleteFacts(List<String> ids) {
        int removed = 0;
        for (String id : ids) {
            memory.deleteFact(requireFact(id));
            removed++;
        }
        return removed;
    }

    @Override public int reindexDocument(String name) { return knowledge.reindexDocument(name); }
    @Override public int deleteDocument(String name) { return knowledge.deleteDocument(name); }

    @Override
    public void savePersona(PersonaDraft persona) {
        memory.setPersonaStructured(persona.identity(), persona.tone(),
                persona.preferences(), persona.taboos());
    }

    @Override
    public String personaMarkdown(PersonaDraft persona) {
        return MemoryService.assemblePersona(persona.identity(), persona.tone(),
                persona.preferences(), persona.taboos());
    }

    @Override
    public void exportPersona(Path target, String markdown) {
        try {
            files.writeString(target, markdown);
        } catch (IOException failure) {
            throw new UncheckedIOException("导出人格失败: " + target, failure);
        }
    }

    @Override
    public void revokeCorrection(String id) {
        memory.revokeCorrection(requireCorrection(id));
    }

    @Override
    public void deleteCorrection(String id) {
        memory.deleteCorrection(requireCorrection(id));
    }

    private Fact requireFact(String id) {
        return memory.facts().stream().filter(item -> Objects.equals(item.id, id)).findFirst()
                .orElseThrow(() -> new NotFoundException("未找到事实：" + id));
    }

    private CorrectionRecord requireCorrection(String id) {
        return memory.corrections().stream().filter(item -> item != null
                        && Objects.equals(item.id, id)).findFirst()
                .orElseThrow(() -> new NotFoundException("未找到纠错记录：" + id));
    }

    private FactItem fact(Fact item) {
        List<String> names = item.about == null ? List.of() : item.about.stream()
                .filter(Objects::nonNull).map(entity -> entity.name)
                .filter(Objects::nonNull).toList();
        return new FactItem(item.id, item.section, item.text, item.updatedAt, item.hitCount,
                item.mergeCount, item.userEdited, item.userAsserted, item.pinned,
                item.superseded, item.contested, item.pending,
                item.source == null ? "" : item.source.id, names);
    }

    private static EpisodeItem episode(Episode item, int derivedFacts) {
        return new EpisodeItem(item.id, item.userInput, item.assistantReply,
                item.toolTraceJson, item.timestamp, item.pending, derivedFacts);
    }

    private static EntityItem entity(EntityNode item, int factCount) {
        return new EntityItem(item.id, item.name, item.type, factCount);
    }

    private static List<KnowledgeDocument> documents(List<KnowledgeChunk> chunks) {
        Map<String, DocumentAccumulator> documents = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : chunks) {
            String name = chunk.docName == null ? "" : chunk.docName;
            DocumentAccumulator document = documents.computeIfAbsent(
                    name, ignored -> new DocumentAccumulator());
            document.chunkCount++;
            document.characterCount += chunk.content == null ? 0 : chunk.content.length();
            if (document.importedAt.isBlank() && chunk.importTime != null) {
                document.importedAt = chunk.importTime;
            }
        }
        List<KnowledgeDocument> result = new ArrayList<>(documents.size());
        documents.forEach((name, value) -> result.add(new KnowledgeDocument(
                name, value.chunkCount, value.characterCount, value.importedAt)));
        return List.copyOf(result);
    }

    private static PersonaDraft persona(Persona source) {
        if (source == null) return PersonaDraft.empty();
        if (!source.structured) {
            return new PersonaDraft(source.content, "简洁直接", List.of(), List.of());
        }
        return new PersonaDraft(source.identity, source.tone,
                source.preferences, source.taboos);
    }

    private static Statistics statistics(MemoryStats source) {
        return source == null ? Statistics.empty() : new Statistics(source.totalRecalls,
                source.totalFactHits, source.totalFactsDistilled, source.totalFactsMerged);
    }

    private CorrectionItem correction(CorrectionRecord item) {
        long timestamp = item.createdAt > 0 ? item.createdAt : item.updatedAt;
        return new CorrectionItem(item.id, name(item.type), name(item.scope), name(item.status),
                item.wrongClaim, item.correctClaim, item.sourceInput, timestamp, item.isEffective());
    }

    private ChangeItem change(ChangeLogEntry item) {
        return new ChangeItem(item.timestamp, item.op, item.type, item.targetId, item.detail);
    }

    private static String name(Enum<?> value) { return value == null ? "" : value.name(); }

    private static final class DocumentAccumulator {
        private long chunkCount;
        private long characterCount;
        private String importedAt = "";
    }
}
