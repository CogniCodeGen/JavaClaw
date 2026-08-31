package com.javaclaw.agent.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Feature-owned Memory, Knowledge and Skill persistence contract. */
public interface KnowledgeRepository extends MemoryRepository, LearningRepository {
    /** 列出指定 Workspace 的记忆记录及版本，不跨工作区混合上下文。 */
    List<MemoryEntry> listMemories(String workspaceId);

    /** 创建或按预期修订号保存记忆；幂等重放不重复写入，content 不得包含明文凭据。 */
    MemoryEntry putMemory(
            String id, String workspaceId, String kind, String content, long expectedRevision, String idempotencyKey);

    /** 按版本删除记忆记录并返回是否删除成功；已经固化的历史 ContextUsage 不变。 */
    boolean deleteMemory(String id, long expectedRevision, String idempotencyKey);

    /** 列出工作区知识源及索引/降级状态，不加载全部文档正文。 */
    List<KnowledgeSource> listSources(String workspaceId);

    /** 批量读取工作区当前 generation、Chunk 数和检索模式；实现应使用单次有界查询。 */
    default List<KnowledgeSourceStats> sourceStats(String workspaceId) {
        return listSources(workspaceId).stream()
                .map(source -> new KnowledgeSourceStats(source.id(), 0, 0, "UNAVAILABLE", null, "统计能力不可用；知识源状态仍可读取。"))
                .toList();
    }

    /** 按 sourceId 读取知识源元数据；不存在返回 Optional.empty。 */
    Optional<KnowledgeSource> findSource(String sourceId);

    /** 返回已完成的索引 generation；重建失败不删除当前可查询 generation。 */
    List<KnowledgeGeneration> sourceHistory(String sourceId);

    /** 查询指定 generation 的有界正文，便于来源核对；不自动注入上下文。 */
    String sourceContent(String sourceId, long revision);

    /** 登记已上传附件为知识源并建立内容引用；幂等键防止同一导入请求重复登记。 */
    KnowledgeSource createSource(
            String workspaceId, String attachmentSha256, String displayName, String mediaType, String idempotencyKey);

    /** 按版本原子替换抽取正文、Chunk、Embedding 及 fingerprint；H2 保存权威内容，外部向量索引仅为投影。 */
    KnowledgeSource replaceIndex(
            String sourceId,
            String content,
            String contentSha256,
            String extractorFingerprint,
            List<IndexedChunk> chunks,
            String status,
            long expectedRevision,
            String idempotencyKey);

    /** 按版本删除知识源及派生索引，释放关联内容引用；返回删除结果，不修改原始用户文件。 */
    boolean deleteSource(String sourceId, long expectedRevision, String idempotencyKey);

    /** 在指定工作区进行关键词/向量检索；queryVector 可为空，向量仅匹配相同 embeddingFingerprint，结果受 limit 限制。 */
    List<SearchHit> search(
            String workspaceId, String query, float[] queryVector, String embeddingFingerprint, int limit);

    /** 列出已安装 Skill 的声明、版本和启用状态，不加载第三方 JVM 代码。 */
    List<SkillEntry> listSkills();

    /** 按标识查找 Skill；不存在返回 Optional.empty。 */
    Optional<SkillEntry> findSkill(String id);

    /** 返回 Skill 的不可变历史，包括启停造成的修订；恢复必须创建新修订。 */
    List<SkillEntry> skillHistory(String id);

    /** 恢复旧 Skill 内容为新修订，保留当前启用状态；幂等重放不能因后续变化再次恢复。 */
    SkillEntry restoreSkill(String id, long sourceRevision, long expectedRevision, String idempotencyKey);

    /** 按版本保存 Skill 声明与启用状态；只保存内容，不加载或执行插件代码。 */
    SkillEntry putSkill(
            String id,
            String name,
            String version,
            String manifest,
            boolean enabled,
            long expectedRevision,
            String idempotencyKey);

    /** 按版本切换 Skill 是否参与后续上下文构建；返回更新结果，历史使用记录不变。 */
    boolean setSkillEnabled(String id, boolean enabled, long expectedRevision, String idempotencyKey);

    /** 按版本删除 Skill 登记；返回删除结果，不删除历史 Turn 中的来源审计。 */
    boolean deleteSkill(String id, long expectedRevision, String idempotencyKey);

    /**
     * 带来源类别与修订号的工作区记忆条目。
     *
     * @param id 资源标识；创建草稿允许为空由 Repository 分配，已保存记录非空
     * @param workspaceId 所属 Workspace 标识，保存时必须对应有效工作区
     * @param kind 记忆类别，保存时校验的非空白文本
     * @param content 记忆正文，保存时要求非空白；不得包含凭据
     * @param revision 从 1 开始的持久修订号，用于乐观锁
     * @param createdAt 持久记录创建时间，已保存记录非空
     * @param updatedAt 最近持久更新时间，已保存记录非空
     */
    record MemoryEntry(
            String id,
            String workspaceId,
            String kind,
            String content,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    /**
     * 知识源元数据；正文、Chunk 与向量保存在独立权威记录中。
     *
     * @param id 资源标识；创建草稿允许为空由 Repository 分配，已保存记录非空
     * @param workspaceId 所属 Workspace 标识，保存时必须对应有效工作区
     * @param attachmentSha256 原始附件的内容摘要，非空
     * @param displayName 文档展示名，不作为文件系统路径
     * @param mediaType 文档 MIME 类型
     * @param status 索引状态，包括 READY、关键词模式及 Embedding 降级状态
     * @param revision 从 1 开始的持久修订号，用于乐观锁
     * @param createdAt 持久记录创建时间，已保存记录非空
     * @param updatedAt 最近持久更新时间，已保存记录非空
     */
    record KnowledgeSource(
            String id,
            String workspaceId,
            String attachmentSha256,
            String displayName,
            String mediaType,
            String status,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    /**
     * 已原子切换的知识索引版本。
     *
     * @param sourceId 知识源标识
     * @param revision 索引切换对应的源修订
     * @param contentSha256 抽取正文的摘要
     * @param extractorFingerprint 解析器版本摘要
     * @param status 可用或降级状态
     * @param createdAt 切换时间
     */
    record KnowledgeGeneration(
            String sourceId,
            long revision,
            String contentSha256,
            String extractorFingerprint,
            String status,
            Instant createdAt) {}

    /**
     * 知识源当前索引统计。
     *
     * @param sourceId 知识源标识
     * @param generation 当前可查询 generation；尚未建立时为 0
     * @param chunkCount 当前片段数
     * @param retrievalMode VECTOR、KEYWORD 或 UNAVAILABLE
     * @param indexedAt 当前 generation 建立时间；尚未建立时为空
     * @param failureSummary 脱敏失败摘要；无失败时为空
     */
    record KnowledgeSourceStats(
            String sourceId,
            long generation,
            long chunkCount,
            String retrievalMode,
            Instant indexedAt,
            String failureSummary) {}

    /**
     * 一段抽取文本及可选向量；向量元数据用于重建和模型隔离。
     *
     * @param ordinal 源文档内从 0 开始的 Chunk 序号
     * @param content Chunk 文本，建立索引时要求非空
     * @param embedding 可选向量；未配置或不可用时为 null，输入和 accessor 均复制
     * @param embeddingProvider Embedding Provider；无配置时可为 null
     * @param embeddingModel Embedding 模型；无配置时可为 null
     * @param embeddingFingerprint 模型、维度与 Schema 的指纹；无向量时可为 null
     */
    record IndexedChunk(
            int ordinal,
            String content,
            float[] embedding,
            String embeddingProvider,
            String embeddingModel,
            String embeddingFingerprint) {
        /** 防御性复制可选向量；null 向量用于显式关键词降级，不伪造零向量。 */
        public IndexedChunk {
            embedding = embedding == null ? null : embedding.clone();
        }

        @Override
        public float[] embedding() {
            return embedding == null ? null : embedding.clone();
        }
    }

    /**
     * 一次知识检索命中及来源版本，供答案上下文与审计共同使用。
     *
     * @param sourceId 来源知识源标识
     * @param chunkId 命中 Chunk 标识
     * @param displayName 源文档展示名
     * @param content 命中的文本内容
     * @param score 排序相关性分数，不是概率
     * @param sourceRevision 检索时的知识源版本
     */
    record SearchHit(
            String sourceId, String chunkId, String displayName, String content, double score, long sourceRevision) {}

    /**
     * Skill 声明的持久版本及可见性状态。
     *
     * @param id 资源标识；创建草稿允许为空由 Repository 分配，已保存记录非空
     * @param name 展示名称；保存时要求非空白
     * @param version Skill 发布版本字符串
     * @param manifest Skill 声明内容，不是可加载的 JVM 扩展
     * @param enabled 是否启用该资源；禁用状态不应产生新执行
     * @param revision 从 1 开始的持久修订号，用于乐观锁
     * @param createdAt 持久记录创建时间，已保存记录非空
     * @param updatedAt 最近持久更新时间，已保存记录非空
     */
    record SkillEntry(
            String id,
            String name,
            String version,
            String manifest,
            boolean enabled,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}
}
