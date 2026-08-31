package com.javaclaw.agent.knowledge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import com.javaclaw.agent.context.ContextContributor;
import com.javaclaw.agent.model.EmbeddingGateway;
import com.javaclaw.agent.runtime.persistence.AttachmentRepository;
import com.javaclaw.core.api.AttachmentReadChunk;
import com.javaclaw.core.api.ThreadItem;

/** Memory/RAG/Skill application service and bounded ContextContributor. */
public final class KnowledgeService implements ContextContributor, KnowledgeUseCases {
    private static final int CHUNK_CHARS = 1_500;
    private static final int CHUNK_OVERLAP = 200;

    private final KnowledgeRepository repository;
    private final AttachmentRepository attachments;
    private final EmbeddingGateway embeddings;
    private final DocumentExtractionGateway extractor;
    private final String embeddingProvider;
    private final Supplier<String> embeddingModel;

    /** 绑定权威仓库、附件与受监督的文档 Worker；Embedding 为空时保留关键词检索。 */
    public KnowledgeService(
            KnowledgeRepository repository,
            AttachmentRepository attachments,
            EmbeddingGateway embeddings,
            DocumentExtractionGateway extractor,
            String embeddingProvider,
            String embeddingModel) {
        this(repository, attachments, embeddings, extractor, embeddingProvider, () -> embeddingModel);
    }

    /**
     * 创建绑定可热重载 Embedding 模型选择器的服务；每次索引或检索固定读取一次，避免运行中 Provider 配置变更失效。
     *
     * @return 使用动态模型配置的 Knowledge 服务
     */
    public static KnowledgeService withReloadableEmbeddingModel(
            KnowledgeRepository repository,
            AttachmentRepository attachments,
            EmbeddingGateway embeddings,
            DocumentExtractionGateway extractor,
            String embeddingProvider,
            Supplier<String> embeddingModel) {
        return new KnowledgeService(repository, attachments, embeddings, extractor, embeddingProvider, embeddingModel);
    }

    private KnowledgeService(
            KnowledgeRepository repository,
            AttachmentRepository attachments,
            EmbeddingGateway embeddings,
            DocumentExtractionGateway extractor,
            String embeddingProvider,
            Supplier<String> embeddingModel) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.embeddings = embeddings;
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.embeddingProvider = embeddingProvider;
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
    }

    @Override
    public List<KnowledgeRepository.MemoryEntry> listMemories(String workspaceId) {
        return repository.listMemories(workspaceId);
    }

    @Override
    public MemoryRepository.MemoryDocument readMemory(String id) {
        return repository.readMemory(id);
    }

    @Override
    public List<MemoryRepository.MemoryDocument> memoryHistory(String id) {
        return repository.memoryHistory(id);
    }

    @Override
    public MemoryRepository.MemoryDocument saveMemory(MemoryRepository.MemoryDraft draft, long revision, String key) {
        MemorySafety.requireNoSecrets(draft.content());
        return repository.saveMemory(draft, revision, key);
    }

    @Override
    public MemoryRepository.MemoryDocument restoreMemory(String id, long sourceRevision, long revision, String key) {
        return repository.restoreMemory(id, sourceRevision, revision, key);
    }

    @Override
    public MemoryRepository.MemoryProposal proposeMemory(
            MemoryRepository.MemoryDraft draft, long revision, String reason, String key) {
        return repository.proposeMemory(draft, revision, false, reason, key);
    }

    @Override
    public List<MemoryRepository.MemoryProposal> memoryProposals(String workspaceId) {
        return repository.memoryProposals(workspaceId);
    }

    @Override
    public MemoryRepository.MemoryProposal reviewMemoryProposal(String id, boolean accept, long revision, String key) {
        return repository.reviewMemoryProposal(id, accept, revision, key);
    }

    @Override
    public KnowledgeRepository.MemoryEntry putMemory(
            String id, String workspaceId, String kind, String content, long expectedRevision, String idempotencyKey) {
        return repository.putMemory(id, workspaceId, kind, content, expectedRevision, idempotencyKey);
    }

    @Override
    public boolean deleteMemory(String id, long revision, String key) {
        return repository.deleteMemory(id, revision, key);
    }

    @Override
    public List<KnowledgeRepository.KnowledgeSource> listSources(String workspaceId) {
        return repository.listSources(workspaceId);
    }

    @Override
    public List<KnowledgeRepository.KnowledgeSourceStats> sourceStats(String workspaceId) {
        return repository.sourceStats(workspaceId);
    }

    @Override
    public List<KnowledgeRepository.KnowledgeGeneration> sourceHistory(String sourceId) {
        return repository.sourceHistory(sourceId);
    }

    @Override
    public String sourceContent(String sourceId, long revision) {
        return repository.sourceContent(sourceId, revision);
    }

    @Override
    public KnowledgeRepository.KnowledgeSource readSource(String id) {
        return repository
                .findSource(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("knowledge source not found: " + id));
    }

    @Override
    public KnowledgeRepository.KnowledgeSource importAttachment(
            String workspaceId, String sha256, String displayName, String mediaType, String idempotencyKey)
            throws Exception {
        var source = repository.createSource(workspaceId, sha256, displayName, mediaType, idempotencyKey);
        var current = readSource(source.id());
        if (!"PENDING".equals(current.status())) {
            return current;
        }
        String reindexKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? null
                : sha256("knowledge/source/reindex\n" + idempotencyKey.strip());
        return reindex(current.id(), current.revision(), reindexKey);
    }

    /**
     * 不携带幂等键的重建入口；仍校验 expectedRevision，并保留 Embedding 不可用的降级语义。
     *
     * @throws Exception 文档抽取或索引持久化失败
     */
    public KnowledgeRepository.KnowledgeSource reindex(String sourceId, long expectedRevision) throws Exception {
        return reindex(sourceId, expectedRevision, null);
    }

    @Override
    public KnowledgeRepository.KnowledgeSource reindex(String sourceId, long expectedRevision, String idempotencyKey)
            throws Exception {
        var source = readSource(sourceId);
        byte[] bytes = readAttachment(source.attachmentSha256());
        String content = extractor.extract(bytes, source.mediaType(), source.displayName());
        List<String> chunks = chunks(content);
        List<float[]> vectors = null;
        String fingerprint = null;
        String status = "READY_KEYWORD_ONLY";
        String selectedEmbeddingModel = embeddingModel();
        if (embeddings != null && embeddingProvider != null && selectedEmbeddingModel != null) {
            try {
                var result = embeddings.embed(embeddingProvider, selectedEmbeddingModel, chunks);
                vectors = result.vectors();
                int dimensions = vectors.getFirst().length;
                fingerprint = sha256(embeddingProvider + "\n" + selectedEmbeddingModel + "\n" + dimensions
                        + "\nknowledge-schema-v1");
                status = "READY";
            } catch (Exception unavailable) {
                status = "DEGRADED_EMBEDDING_UNAVAILABLE";
            }
        }
        ArrayList<KnowledgeRepository.IndexedChunk> indexed = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            indexed.add(new KnowledgeRepository.IndexedChunk(
                    index,
                    chunks.get(index),
                    vectors == null ? null : vectors.get(index),
                    embeddingProvider,
                    selectedEmbeddingModel,
                    fingerprint));
        }
        return repository.replaceIndex(
                sourceId,
                content,
                sha256(content),
                extractorFingerprint(source.mediaType()),
                indexed,
                status,
                expectedRevision,
                idempotencyKey);
    }

    @Override
    public boolean deleteSource(String id, long revision, String key) {
        return repository.deleteSource(id, revision, key);
    }

    @Override
    public List<KnowledgeRepository.SearchHit> search(String workspaceId, String query, int limit) throws Exception {
        float[] vector = null;
        String fingerprint = null;
        String selectedEmbeddingModel = embeddingModel();
        if (embeddings != null && embeddingProvider != null && selectedEmbeddingModel != null) {
            try {
                vector = embeddings
                        .embed(embeddingProvider, selectedEmbeddingModel, List.of(query))
                        .vectors()
                        .getFirst();
                fingerprint = sha256(embeddingProvider + "\n" + selectedEmbeddingModel + "\n" + vector.length
                        + "\nknowledge-schema-v1");
            } catch (Exception ignored) {
            }
        }
        return repository.search(workspaceId, query, vector, fingerprint, Math.max(1, Math.min(100, limit)));
    }

    private String embeddingModel() {
        String value = embeddingModel.get();
        return value == null || value.isBlank() ? null : value.strip();
    }

    @Override
    public List<KnowledgeRepository.SkillEntry> listSkills() {
        return repository.listSkills();
    }

    @Override
    public LearningRepository.LearningSettings learningSettings(String workspaceId) {
        return repository.learningSettings(workspaceId);
    }

    @Override
    public LearningRepository.LearningSettings saveLearningSettings(
            String workspaceId, String mode, boolean memoryAutomatic, long revision, String key) {
        return repository.saveLearningSettings(workspaceId, mode, memoryAutomatic, revision, key);
    }

    @Override
    public LearningRepository.SkillProposal proposeSkill(
            LearningRepository.SkillDraft draft, String reason, String key) {
        return repository.proposeSkill(draft, false, reason, key);
    }

    @Override
    public List<LearningRepository.SkillProposal> skillProposals(String workspaceId) {
        return repository.skillProposals(workspaceId);
    }

    @Override
    public LearningRepository.SkillProposal reviewSkillProposal(String id, boolean accept, long revision, String key) {
        return repository.reviewSkillProposal(id, accept, revision, key);
    }

    @Override
    public KnowledgeRepository.SkillEntry readSkill(String id) {
        return repository
                .findSkill(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("skill not found: " + id));
    }

    @Override
    public List<KnowledgeRepository.SkillEntry> skillHistory(String id) {
        return repository.skillHistory(id);
    }

    @Override
    public KnowledgeRepository.SkillEntry restoreSkill(String id, long sourceRevision, long revision, String key) {
        return repository.restoreSkill(id, sourceRevision, revision, key);
    }

    @Override
    public SkillResource readSkillResource(String id, long revision, String path) {
        var skill = readSkill(id);
        if (!skill.enabled() || skill.revision() != revision) {
            throw new IllegalStateException("Skill was disabled or its revision changed");
        }
        return com.javaclaw.agent.tool.SkillManifests.resources(skill.manifest()).stream()
                .filter(value -> value.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("Skill resource not found"));
    }

    @Override
    public KnowledgeRepository.SkillEntry installSkill(
            String id,
            String name,
            String version,
            String manifest,
            boolean enabled,
            long expectedRevision,
            String idempotencyKey) {
        com.javaclaw.agent.tool.SkillManifests.validate(manifest);
        return repository.putSkill(id, name, version, manifest, enabled, expectedRevision, idempotencyKey);
    }

    @Override
    public boolean setSkillEnabled(String id, boolean enabled, long expectedRevision, String idempotencyKey) {
        return repository.setSkillEnabled(id, enabled, expectedRevision, idempotencyKey);
    }

    @Override
    public boolean uninstallSkill(String id, long expectedRevision, String idempotencyKey) {
        return repository.deleteSkill(id, expectedRevision, idempotencyKey);
    }

    @Override
    public List<ContextContribution> contribute(ContextRequest request) throws Exception {
        String workspaceId = request.thread().workspaceId();
        ArrayList<ContextContribution> result = new ArrayList<>();
        for (var memory :
                repository.listMemories(workspaceId).stream().limit(20).toList()) {
            result.add(new ContextContribution("memory", memory.id(), memory.revision(), memory.content()));
        }
        String query = lastUserText(request);
        if (!query.isBlank()) {
            for (var hit : search(workspaceId, query, 5)) {
                result.add(new ContextContribution("knowledge", hit.sourceId(), hit.sourceRevision(), hit.content()));
            }
        }
        for (var skill : repository.listSkills().stream()
                .filter(KnowledgeRepository.SkillEntry::enabled)
                .limit(20)
                .toList()) {
            // 目录只用于选择；正文必须通过受治理的 skill_read 按选定版本完整读取。
            result.add(new ContextContribution(
                    "skill-catalog",
                    skill.id(),
                    skill.revision(),
                    "Skill id=" + skill.id() + ", name=" + skill.name() + ", version=" + skill.version()));
        }
        return List.copyOf(result);
    }

    private byte[] readAttachment(String sha256) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long offset = 0;
        while (true) {
            AttachmentReadChunk chunk = attachments.readChunk(sha256, offset, AttachmentRepository.MAX_CHUNK_BYTES);
            output.write(chunk.data());
            offset += chunk.data().length;
            if (offset > DocumentExtractor.MAX_SOURCE_BYTES) {
                throw new IllegalArgumentException("knowledge attachment exceeds 256 MiB");
            }
            if (chunk.eof()) {
                return output.toByteArray();
            }
            if (chunk.data().length == 0) {
                throw new IllegalStateException("attachment reader made no progress");
            }
        }
    }

    private static List<String> chunks(String content) {
        ArrayList<String> result = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + CHUNK_CHARS);
            if (end < content.length()) {
                int boundary = content.lastIndexOf('\n', end);
                if (boundary > start + CHUNK_CHARS / 2) {
                    end = boundary;
                }
            }
            String chunk = content.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                result.add(chunk);
            }
            if (end == content.length()) {
                break;
            }
            start = Math.max(start + 1, end - CHUNK_OVERLAP);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("document produced no chunks");
        }
        return List.copyOf(result);
    }

    private static String lastUserText(ContextRequest request) {
        String current = request.turn().input().stream()
                .filter(com.javaclaw.core.api.TurnInput.Text.class::isInstance)
                .map(com.javaclaw.core.api.TurnInput.Text.class::cast)
                .map(com.javaclaw.core.api.TurnInput.Text::text)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
        if (!current.isBlank()) {
            return current;
        }
        for (int index = request.transcript().size() - 1; index >= 0; index--) {
            if (request.transcript().get(index).item() instanceof ThreadItem.UserMessage value) {
                return value.text();
            }
        }
        return "";
    }

    private static String extractorFingerprint(String mediaType) {
        return sha256("javaclaw-extractor-v1\n" + String.valueOf(mediaType));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
