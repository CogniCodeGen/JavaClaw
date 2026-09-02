package com.javaclaw.builtin.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AttachmentRef;

/** Knowledge 扩展公开契约；原始文件只通过 Core Attachment 引用传递。 */
public final class KnowledgeContracts {
    /** App Server 为 Knowledge Bundle 注册的解析服务标识。 */
    public static final String EXTRACTION_SERVICE = "knowledge.extract";

    /** Knowledge 可恢复索引 Job 类型。 */
    public static final String GENERATION_JOB_TYPE = "generation-build";

    private KnowledgeContracts() {}

    /** 用户对索引方式的选择。 */
    public enum RetrievalPreference {
        /** 优先生成 Embedding；不可用或失败时明确降级关键词索引。 */
        EMBEDDING_PREFERRED,
        /** 不调用 Embedding Provider，只建立关键词索引。 */
        KEYWORD_ONLY
    }

    /** 一个已完成 Generation 的实际检索方式。 */
    public enum RetrievalMode {
        /** 关键词与同指纹向量共同评分。 */
        HYBRID,
        /** 只使用标题、媒体类型和正文关键词。 */
        KEYWORD
    }

    /** Embedding 降级的稳定原因。 */
    public enum FallbackReason {
        /** 当前没有配置可用 Embedding endpoint。 */
        EMBEDDING_UNAVAILABLE,
        /** Provider 调用或响应校验失败。 */
        EMBEDDING_FAILED
    }

    /**
     * 当前可查询知识源。
     *
     * @param id 来源标识
     * @param revision 来源乐观锁版本
     * @param title 用户可见标题
     * @param attachment 当前原始文件 Attachment 引用
     * @param activeGenerationId 当前原子激活的 Generation
     * @param updatedAt 激活时间
     */
    public record Source(
            String id,
            long revision,
            String title,
            AttachmentRef attachment,
            String activeGenerationId,
            Instant updatedAt)
            implements VersionedExtensionDocument {
        /** 校验来源身份与引用。 */
        public Source {
            id = identifier(id, "id");
            revision = ContractValidation.revision(revision);
            title = ContractValidation.text(title, "title");
            Objects.requireNonNull(attachment, "attachment");
            activeGenerationId = identifier(activeGenerationId, "activeGenerationId");
            updatedAt = ContractValidation.instant(updatedAt, "updatedAt");
        }
    }

    /**
     * 已完成且不可变的索引 Generation。
     *
     * @param id Generation 标识
     * @param revision 固定为 1
     * @param sourceId 来源标识
     * @param sourceRevision 激活后来源版本
     * @param attachmentDigest 原文件摘要
     * @param parserFingerprint 解析器与版本指纹
     * @param retrievalMode 实际检索方式
     * @param embeddingFingerprint 精确 Embedding 模型指纹；关键词模式为空
     * @param embeddingDimensions 向量维度；关键词模式为 0
     * @param chunkCount 分块数
     * @param characters 规范纯文本字符数
     * @param fallbackReason 降级原因；用户主动选择关键词时为空
     * @param createdAt 完成时间
     */
    public record Generation(
            String id,
            long revision,
            String sourceId,
            long sourceRevision,
            String attachmentDigest,
            String parserFingerprint,
            RetrievalMode retrievalMode,
            Optional<String> embeddingFingerprint,
            int embeddingDimensions,
            int chunkCount,
            int characters,
            Optional<FallbackReason> fallbackReason,
            Instant createdAt)
            implements VersionedExtensionDocument {
        /** 校验 Generation 完成态不变量。 */
        public Generation {
            id = identifier(id, "id");
            if (revision != 1) {
                throw new IllegalArgumentException("Generation revision must be 1");
            }
            sourceId = identifier(sourceId, "sourceId");
            sourceRevision = ContractValidation.revision(sourceRevision);
            attachmentDigest = digest(attachmentDigest, "attachmentDigest");
            parserFingerprint = ContractValidation.text(parserFingerprint, "parserFingerprint");
            Objects.requireNonNull(retrievalMode, "retrievalMode");
            embeddingFingerprint = Objects.requireNonNull(embeddingFingerprint, "embeddingFingerprint")
                    .map(value -> digest(value, "embeddingFingerprint"));
            fallbackReason = Objects.requireNonNull(fallbackReason, "fallbackReason");
            requireRetrievalState(retrievalMode, embeddingFingerprint, embeddingDimensions, fallbackReason);
            if (chunkCount < 1 || characters < 0) {
                throw new IllegalArgumentException("Generation counters are invalid");
            }
            createdAt = ContractValidation.instant(createdAt, "createdAt");
        }
    }

    /**
     * Generation 中可重建的文本分块。
     *
     * @param generationId Generation 标识
     * @param sourceId 来源标识
     * @param index 从 0 开始的稳定序号
     * @param text 规范纯文本
     * @param vector 同指纹 Embedding；关键词模式为空
     */
    public record Chunk(String generationId, String sourceId, int index, String text, Optional<List<Double>> vector) {
        /** 校验分块内容并深复制向量。 */
        public Chunk {
            generationId = identifier(generationId, "generationId");
            sourceId = identifier(sourceId, "sourceId");
            if (index < 0) {
                throw new IllegalArgumentException("index must not be negative");
            }
            text = ContractValidation.text(text, "text");
            if (text.length() > 8_000) {
                throw new IllegalArgumentException("chunk text exceeds 8000 characters");
            }
            vector = Objects.requireNonNull(vector, "vector").map(KnowledgeContracts::vector);
        }
    }

    /**
     * 基于 Core Attachment 创建或重建来源的异步命令。
     *
     * @param id 来源标识
     * @param title 标题
     * @param attachment 已上传的 Core Attachment
     * @param maxCharacters 最大提取字符数，1 至 2000000
     * @param chunkCharacters 每块最大字符数，200 至 8000
     * @param overlapCharacters 相邻块重叠字符数，小于 chunkCharacters
     * @param retrievalPreference 索引偏好
     */
    public record ImportRequest(
            String id,
            String title,
            AttachmentRef attachment,
            int maxCharacters,
            int chunkCharacters,
            int overlapCharacters,
            RetrievalPreference retrievalPreference) {
        /** 校验导入参数；不接受正文或 Base64。 */
        public ImportRequest {
            id = identifier(id, "id");
            title = ContractValidation.text(title, "title");
            Objects.requireNonNull(attachment, "attachment");
            requireAttachmentSize(attachment);
            if (maxCharacters < 1 || maxCharacters > 2_000_000) {
                throw new IllegalArgumentException("maxCharacters must be between 1 and 2000000");
            }
            if (chunkCharacters < 200 || chunkCharacters > 8_000) {
                throw new IllegalArgumentException("chunkCharacters must be between 200 and 8000");
            }
            if (overlapCharacters < 0 || overlapCharacters >= chunkCharacters) {
                throw new IllegalArgumentException("overlapCharacters must be smaller than chunkCharacters");
            }
            Objects.requireNonNull(retrievalPreference, "retrievalPreference");
        }
    }

    /**
     * 已持久化的异步导入受理结果。
     *
     * @param jobId 可通过 Core Extension Job API 查询的 Job
     * @param sourceId 目标来源
     * @param targetSourceRevision 成功后将激活的来源版本
     */
    public record ImportAccepted(String jobId, String sourceId, long targetSourceRevision) {
        /** 校验 Job 与来源身份。 */
        public ImportAccepted {
            jobId = identifier(jobId, "jobId");
            sourceId = identifier(sourceId, "sourceId");
            targetSourceRevision = ContractValidation.revision(targetSourceRevision);
        }
    }

    /**
     * 单来源或 Generation 查询参数。
     *
     * @param id 资源标识
     */
    public record Key(String id) {
        /** 校验标识。 */
        public Key {
            id = identifier(id, "id");
        }
    }

    /**
     * 稳定键分页请求。
     *
     * @param afterKey 排他游标；从头读取为空字符串
     * @param limit 页大小，1 至 200
     */
    public record PageRequest(String afterKey, int limit) {
        /** 校验分页参数。 */
        public PageRequest {
            afterKey = afterKey == null ? "" : afterKey;
            if (limit < 1 || limit > 200) {
                throw new IllegalArgumentException("limit must be between 1 and 200");
            }
        }
    }

    /**
     * 来源分页结果。
     *
     * @param values 当前来源
     * @param nextKey 下一页游标
     */
    public record SourcePage(List<Source> values, String nextKey) {
        /** 复制分页结果。 */
        public SourcePage {
            values = List.copyOf(values);
            nextKey = nextKey == null ? "" : nextKey;
        }
    }

    /**
     * Generation 分页结果。
     *
     * @param values 已完成 Generation
     * @param nextKey 下一页游标
     */
    public record GenerationPage(List<Generation> values, String nextKey) {
        /** 复制分页结果。 */
        public GenerationPage {
            values = List.copyOf(values);
            nextKey = nextKey == null ? "" : nextKey;
        }
    }

    /**
     * 知识检索条件。
     *
     * @param query 查询文本
     * @param mediaTypes 可选 MIME 类型过滤；空集合表示全部
     * @param limit 最大返回数，1 至 100
     */
    public record SearchRequest(String query, Set<String> mediaTypes, int limit) {
        /** 校验并复制检索条件。 */
        public SearchRequest {
            query = ContractValidation.text(query, "query");
            mediaTypes = ContractValidation.textSet(mediaTypes, "mediaTypes");
            limit = ContractValidation.searchLimit(limit);
        }
    }

    /**
     * 知识检索结果。
     *
     * @param matches 按综合分数和更新时间排序的命中
     * @param embeddingFallback 本次查询无法使用同指纹向量时是否已降级
     */
    public record SearchResult(List<SearchMatch> matches, boolean embeddingFallback) {
        /** 复制结果列表。 */
        public SearchResult {
            matches = List.copyOf(matches);
        }
    }

    /**
     * 单个知识搜索命中。
     *
     * @param source 当前来源
     * @param generation 当前激活 Generation
     * @param excerpts 最多三个短摘要
     * @param score 0 至 1 的稳定综合分数
     * @param retrievalMode 本次实际命中方式
     */
    public record SearchMatch(
            Source source, Generation generation, List<String> excerpts, double score, RetrievalMode retrievalMode) {
        /** 校验命中与分数。 */
        public SearchMatch {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(generation, "generation");
            excerpts = ContractValidation.textList(excerpts, "excerpts");
            if (excerpts.size() > 3 || excerpts.stream().anyMatch(value -> value.length() > 400)) {
                throw new IllegalArgumentException("excerpts exceed count or length limit");
            }
            if (!Double.isFinite(score) || score < 0 || score > 1) {
                throw new IllegalArgumentException("score must be between 0 and 1");
            }
            Objects.requireNonNull(retrievalMode, "retrievalMode");
        }
    }

    /**
     * 发给 App Server 隔离解析服务的 Attachment 任务。
     *
     * @param attachment Core Attachment 引用
     * @param maxCharacters 最大提取字符数
     */
    public record ExtractionRequest(AttachmentRef attachment, int maxCharacters) {
        /** 校验解析任务。 */
        public ExtractionRequest {
            Objects.requireNonNull(attachment, "attachment");
            requireAttachmentSize(attachment);
            if (maxCharacters < 1 || maxCharacters > 2_000_000) {
                throw new IllegalArgumentException("maxCharacters must be between 1 and 2000000");
            }
        }
    }

    /**
     * 隔离 Worker 提取结果。
     *
     * @param digest 原始字节摘要
     * @param parserFingerprint 解析器指纹
     * @param text 规范化纯文本
     */
    public record ExtractionResult(String digest, String parserFingerprint, String text) {
        /** 校验摘要、解析器与正文上限。 */
        public ExtractionResult {
            digest = KnowledgeContracts.digest(digest, "digest");
            parserFingerprint = ContractValidation.text(parserFingerprint, "parserFingerprint");
            text = Objects.requireNonNull(text, "text");
            if (text.length() > 2_000_000) {
                throw new IllegalArgumentException("extracted text exceeds 2000000 characters");
            }
        }
    }

    private static void requireRetrievalState(
            RetrievalMode mode, Optional<String> fingerprint, int dimensions, Optional<FallbackReason> fallbackReason) {
        if (mode == RetrievalMode.HYBRID && (fingerprint.isEmpty() || dimensions < 1 || fallbackReason.isPresent())) {
            throw new IllegalArgumentException("hybrid Generation requires one embedding fingerprint");
        }
        if (mode == RetrievalMode.KEYWORD && (fingerprint.isPresent() || dimensions != 0)) {
            throw new IllegalArgumentException("keyword Generation must not contain embedding metadata");
        }
    }

    private static List<Double> vector(List<Double> source) {
        List<Double> copied = List.copyOf(source);
        if (copied.isEmpty() || copied.size() > 65_536) {
            throw new IllegalArgumentException("vector size is invalid");
        }
        if (copied.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("vector must contain finite values");
        }
        return copied;
    }

    private static void requireAttachmentSize(AttachmentRef attachment) {
        if (attachment.sizeBytes() < 1 || attachment.sizeBytes() > KnowledgeWorkerProtocol.MAXIMUM_CONTENT_BYTES) {
            throw new IllegalArgumentException("attachment size exceeds Knowledge Worker limit");
        }
    }

    private static String digest(String value, String name) {
        String normalized = ContractValidation.text(value, name).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256 hex");
        }
        return normalized;
    }

    private static String identifier(String value, String name) {
        String normalized = ContractValidation.text(value, name);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }
}
