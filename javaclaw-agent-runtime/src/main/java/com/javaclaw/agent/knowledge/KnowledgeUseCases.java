package com.javaclaw.agent.knowledge;

import java.util.List;

/** Memory, knowledge-source and Skill administration boundary. */
public interface KnowledgeUseCases extends MemoryUseCases {
    /** 读取工作区学习模式。 */
    LearningRepository.LearningSettings learningSettings(String workspaceId);

    /** 保存用户确认的学习偏好；版本变化不会追溯改变已完成的提案。 */
    LearningRepository.LearningSettings saveLearningSettings(
            String workspaceId,
            String skillMode,
            boolean memoryAutomatic,
            long expectedRevision,
            String idempotencyKey);

    /** 显式提交候选；使用此管理入口不会自动批准脚本或更新。 */
    LearningRepository.SkillProposal proposeSkill(
            LearningRepository.SkillDraft draft, String reason, String idempotencyKey);

    /** 查询可审阅提案。 */
    List<LearningRepository.SkillProposal> skillProposals(String workspaceId);

    /** 用户确认接受或拒绝一个候选；目标过期则返回冲突。 */
    LearningRepository.SkillProposal reviewSkillProposal(
            String id, boolean accept, long expectedRevision, String idempotencyKey);

    /** 查询已完成的知识 generation，不会触发模型或重建。 */
    java.util.List<KnowledgeRepository.KnowledgeGeneration> sourceHistory(String sourceId);

    /** 显式查看一个知识版本的正文；不得当作高优先级提示词。 */
    String sourceContent(String sourceId, long revision);

    /** 列出指定 Workspace 的记忆记录及版本，不跨工作区混合上下文。 */
    List<KnowledgeRepository.MemoryEntry> listMemories(String workspaceId);

    /** 创建或按预期修订号保存记忆；幂等重放不重复写入，content 不得包含明文凭据。 */
    KnowledgeRepository.MemoryEntry putMemory(
            String id, String workspaceId, String kind, String content, long expectedRevision, String idempotencyKey);

    /** 按版本删除记忆记录并返回是否删除成功；已经固化的历史 ContextUsage 不变。 */
    boolean deleteMemory(String id, long revision, String idempotencyKey);

    /** 列出工作区知识源及索引/降级状态，不加载全部文档正文。 */
    List<KnowledgeRepository.KnowledgeSource> listSources(String workspaceId);

    /** 批量读取工作区知识源统计，避免客户端逐项查询。 */
    List<KnowledgeRepository.KnowledgeSourceStats> sourceStats(String workspaceId);

    /** 读取知识源元数据；不存在时抛出 NoSuchElementException。 */
    KnowledgeRepository.KnowledgeSource readSource(String id);

    /**
     * 从已上传的 SHA-256 附件登记知识源并建立索引；重试复用已有记录，不接受客户端路径。
     *
     * @throws Exception 附件不可读、格式不支持或索引写入失败
     */
    KnowledgeRepository.KnowledgeSource importAttachment(
            String workspaceId, String sha256, String displayName, String mediaType, String idempotencyKey)
            throws Exception;

    /**
     * 按指定版本重建正文与索引；Embedding 不可用时保留关键词降级状态，fingerprint 防止混用不同向量空间。
     *
     * @throws Exception 抽取、版本冲突或持久化失败
     */
    KnowledgeRepository.KnowledgeSource reindex(String sourceId, long expectedRevision, String idempotencyKey)
            throws Exception;

    /** 按版本删除知识源及派生索引，释放关联内容引用；返回删除结果，不修改原始用户文件。 */
    boolean deleteSource(String id, long revision, String idempotencyKey);

    /** 最多返回 100 条知识命中；Embedding 不可用时使用关键词检索，不自动切换模型或扩大数据范围。 */
    List<KnowledgeRepository.SearchHit> search(String workspaceId, String query, int limit) throws Exception;

    /** 列出已安装 Skill 的声明、版本和启用状态，不加载第三方 JVM 代码。 */
    List<KnowledgeRepository.SkillEntry> listSkills();

    /** 读取 Skill 声明；不存在时抛出 NoSuchElementException。 */
    KnowledgeRepository.SkillEntry readSkill(String id);

    /** 查看 Skill 指令及资源的历史版本，不自动切换活动版本。 */
    List<KnowledgeRepository.SkillEntry> skillHistory(String id);

    /** 显式恢复选定版本；保留当前启停状态，并使旧 Turn 快照的执行权失效。 */
    KnowledgeRepository.SkillEntry restoreSkill(String id, long sourceRevision, long expectedRevision, String key);

    /** 读取某个固定修订中的资源；仅已启用且修订未变化的 Skill 可用于当前执行。 */
    SkillResource readSkillResource(String id, long revision, String path);

    /** 按版本保存 Skill 声明与启用状态；只保存内容，不加载或执行插件代码。 */
    KnowledgeRepository.SkillEntry installSkill(
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
    boolean uninstallSkill(String id, long expectedRevision, String idempotencyKey);
}
