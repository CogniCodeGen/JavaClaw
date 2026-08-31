package com.javaclaw.sdk;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.javaclaw.sdk.model.AttachmentInfo;
import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.KnowledgeHitInfo;
import com.javaclaw.sdk.model.KnowledgeSourceInfo;
import com.javaclaw.sdk.model.MemoryInfo;
import com.javaclaw.sdk.model.SkillInfo;

/** Memory、RAG 和 Skill 的领域客户端；所有持久状态通过 App Server 管理，远程失败通过 Future 异常返回。 */
public final class KnowledgeClient {
    private final ProtocolClient protocol;
    private final SdkProtocolMapper mapper;

    /** 查询记忆的来源、固定状态与实体属性，不返回存储实现。 */
    public CompletableFuture<com.javaclaw.sdk.model.MemoryDetailInfo> readMemory(String id) {
        return protocol.readMemory(id).thenApply(mapper::memoryDetail);
    }

    /** 查询全部持久修订以供对比；不会更改当前事实。 */
    public CompletableFuture<List<com.javaclaw.sdk.model.MemoryDetailInfo>> memoryHistory(String id) {
        return protocol.memoryHistory(id)
                .thenApply(values -> values.stream().map(mapper::memoryDetail).toList());
    }

    /** 显式保存用户内容，使用 revision 保护并发编辑和用户固定内容。 */
    public CompletableFuture<com.javaclaw.sdk.model.MemoryDetailInfo> saveMemory(
            com.javaclaw.sdk.model.MemoryDetailInfo value, long revision, String key) {
        return protocol.saveMemory(mapper.memoryDetail(value), revision, key).thenApply(mapper::memoryDetail);
    }

    /** 将历史修订复制为新版本，不修改历史或回拨版本号。 */
    public CompletableFuture<com.javaclaw.sdk.model.MemoryDetailInfo> restoreMemory(
            String id, long sourceRevision, long revision, String key) {
        return protocol.restoreMemory(id, sourceRevision, revision, key).thenApply(mapper::memoryDetail);
    }

    /** 提交带来源的建议，不能通过客户端接口自动接受。 */
    public CompletableFuture<com.javaclaw.sdk.model.MemoryProposalInfo> proposeMemory(
            com.javaclaw.sdk.model.MemoryDetailInfo value, long revision, String reason, String key) {
        return protocol.proposeMemory(mapper.memoryDetail(value), revision, reason, key)
                .thenApply(mapper::memoryProposal);
    }

    /** 查询工作区的待审阅及已处理记忆提案。 */
    public CompletableFuture<List<com.javaclaw.sdk.model.MemoryProposalInfo>> memoryProposals(String workspace) {
        return protocol.memoryProposals(workspace)
                .thenApply(values -> values.stream().map(mapper::memoryProposal).toList());
    }

    /** 显式接受或拒绝提案；服务端同时校验目标内容和提案修订。 */
    public CompletableFuture<com.javaclaw.sdk.model.MemoryProposalInfo> reviewMemoryProposal(
            String id, boolean accept, long revision, String key) {
        return protocol.reviewMemoryProposal(id, accept, revision, key).thenApply(mapper::memoryProposal);
    }

    KnowledgeClient(ProtocolClient protocol, SdkProtocolMapper mapper) {
        this.protocol = protocol;
        this.mapper = mapper;
    }

    /** 读取指定工作区记忆及版本，不跨 Workspace 混合数据。 */
    public CompletableFuture<List<MemoryInfo>> listMemories(String workspaceId) {
        return protocol.listMemories(workspaceId)
                .thenApply(values -> values.stream().map(mapper::memory).toList());
    }

    /** 创建或按版本保存记忆正文；key 支持幂等，content 不应含凭据。 */
    public CompletableFuture<MemoryInfo> putMemory(
            String id, String workspaceId, String kind, String content, long expectedRevision, String key) {
        return protocol.putMemory(id, workspaceId, kind, content, expectedRevision, key)
                .thenApply(mapper::memory);
    }

    /** 按版本删除记忆，返回删除结果；已完成 Turn 的来源审计保留。 */
    public CompletableFuture<Boolean> deleteMemory(String id, long expectedRevision, String key) {
        return protocol.deleteMemory(id, expectedRevision, key);
    }

    /** 列出工作区知识源和索引/降级状态，不下载完整正文。 */
    public CompletableFuture<List<KnowledgeSourceInfo>> listSources(String workspaceId) {
        return protocol.listKnowledgeSources(workspaceId)
                .thenApply(values -> values.stream().map(mapper::source).toList());
    }

    /** 批量读取工作区知识源的 generation、片段数和检索模式，避免 Desktop 按源发起 N+1 查询。 */
    public CompletableFuture<List<com.javaclaw.sdk.model.KnowledgeSourceStatsInfo>> sourceStats(String workspaceId) {
        return protocol.knowledgeSourceStats(workspaceId)
                .thenApply(values -> values.stream().map(mapper::knowledgeStats).toList());
    }

    /** 读取单个知识源元数据；不存在时 Future 以 RPC 错误完成。 */
    public CompletableFuture<KnowledgeSourceInfo> readSource(String id) {
        return protocol.readKnowledgeSource(id).thenApply(mapper::source);
    }

    /** 将已上传附件登记为知识源并建立索引；仅发送 SHA-256 等元数据，不透传本地路径。 */
    public CompletableFuture<KnowledgeSourceInfo> importSource(
            String workspaceId, AttachmentInfo attachment, String displayName, String key) {
        return protocol.importKnowledgeSource(
                        workspaceId, attachment.sha256(), attachment.mediaType(), displayName, key)
                .thenApply(mapper::source);
    }

    /** 按 expectedRevision 重建知识源索引；Embedding 失败的降级状态由服务端明确返回。 */
    public CompletableFuture<KnowledgeSourceInfo> reindexSource(String id, long expectedRevision, String key) {
        return protocol.reindexKnowledgeSource(id, expectedRevision, key).thenApply(mapper::source);
    }

    /** 按版本删除知识源及派生索引并释放引用；不删除用户原始文件。 */
    public CompletableFuture<Boolean> deleteSource(String id, long expectedRevision, String key) {
        return protocol.deleteKnowledgeSource(id, expectedRevision, key);
    }

    /** 在指定工作区执行有界知识检索；返回命中分数和来源版本，未配置 Embedding 时可降级关键词。 */
    public CompletableFuture<List<KnowledgeHitInfo>> search(String workspaceId, String query, int limit) {
        return protocol.searchKnowledge(workspaceId, query, limit)
                .thenApply(values -> values.stream().map(mapper::hit).toList());
    }

    /** 列出 Skill 声明与启用状态，不执行第三方代码。 */
    public CompletableFuture<List<SkillInfo>> listSkills() {
        return protocol.listSkills()
                .thenApply(values -> values.stream().map(mapper::skill).toList());
    }

    /** 读取完整 Skill 声明，用于编辑和显式 Bundle 导出；不会执行资源。 */
    public CompletableFuture<SkillInfo> readSkill(String id) {
        return protocol.readSkill(id).thenApply(mapper::skill);
    }

    /** 返回编辑器所需的完整指令和资源；未知声明字段仍保存在 skill.manifest 中。 */
    public CompletableFuture<com.javaclaw.sdk.model.SkillContentInfo> readSkillContent(String id) {
        return readSkill(id).thenApply(SkillDocuments::read);
    }

    /** 显式读取用户选择的 Markdown 或 v4 ZIP 为未启用草稿；不安装、不执行、不解压到宿主目录。 */
    public CompletableFuture<com.javaclaw.sdk.model.SkillContentInfo> importSkillDraft(java.nio.file.Path file) {
        var result = new CompletableFuture<com.javaclaw.sdk.model.SkillContentInfo>();
        Thread.startVirtualThread(() -> {
            try {
                result.complete(SkillBundleCodec.read(file));
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /** 导出用户选定的内容快照为 v4 ZIP；replaceExisting 必须来自明确覆盖决定，不变更服务器内容。 */
    public CompletableFuture<Void> exportSkillBundle(
            com.javaclaw.sdk.model.SkillContentInfo value, java.nio.file.Path file, boolean replaceExisting) {
        var result = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            try {
                SkillBundleCodec.write(value, file, replaceExisting);
                result.complete(null);
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /** 保存显式编辑的正文与资源，保留未知声明字段，并以原 revision 拒绝覆盖并发修改。 */
    public CompletableFuture<SkillInfo> saveSkillContent(com.javaclaw.sdk.model.SkillContentInfo value, String key) {
        var skill = value.skill();
        return installSkill(
                skill.id(),
                skill.name(),
                skill.version(),
                SkillDocuments.write(value),
                skill.enabled(),
                skill.revision(),
                key);
    }

    /** 查询不可变版本，恢复必须通过 restoreSkill 形成新 revision。 */
    public CompletableFuture<List<SkillInfo>> skillHistory(String id) {
        return protocol.skillHistory(id)
                .thenApply(values -> values.stream().map(mapper::skill).toList());
    }

    /** 按目标当前修订恢复所选历史版本；保留当前启用状态，重放不重复恢复。 */
    public CompletableFuture<SkillInfo> restoreSkill(String id, long sourceRevision, long revision, String key) {
        return protocol.restoreSkill(id, sourceRevision, revision, key).thenApply(mapper::skill);
    }

    /** 显式读取 Skill 内资源，禁用或 revision 不匹配会拒绝；path 不是宿主路径。 */
    public CompletableFuture<com.javaclaw.sdk.model.SkillResourceInfo> readSkillResource(
            String id, long revision, String path) {
        return protocol.skillResource(id, revision, path).thenApply(mapper::skillResource);
    }

    /** 查看已完成的知识索引版本，失败重建不会产生成功版本。 */
    public CompletableFuture<List<com.javaclaw.sdk.model.KnowledgeGenerationInfo>> sourceHistory(String id) {
        return protocol.sourceHistory(id)
                .thenApply(values ->
                        values.stream().map(mapper::knowledgeGeneration).toList());
    }

    /** 显式读取来源正文用于核验；返回值仍是不可信资料，不是系统指令。 */
    public CompletableFuture<String> sourceContent(String id, long revision) {
        return protocol.sourceContent(id, revision);
    }

    /** 查询 OFF/SUGGEST/AUTO 学习偏好，不调用模型。 */
    public CompletableFuture<com.javaclaw.sdk.model.LearningSettingsInfo> learningSettings(String workspace) {
        return protocol.learningSettings(workspace).thenApply(mapper::learningSettings);
    }

    /** 用户确认后保存学习偏好；自动模式不授权权限提升或覆盖用户内容。 */
    public CompletableFuture<com.javaclaw.sdk.model.LearningSettingsInfo> saveLearningSettings(
            String workspace, String mode, boolean memoryAutomatic, long revision, String key) {
        return protocol.saveLearningSettings(workspace, mode, memoryAutomatic, revision, key)
                .thenApply(mapper::learningSettings);
    }

    /** 查询工作区的 Skill 学习提案和处理状态。 */
    public CompletableFuture<List<com.javaclaw.sdk.model.SkillProposalInfo>> skillProposals(String workspace) {
        return protocol.skillProposals(workspace)
                .thenApply(values -> values.stream().map(mapper::skillProposal).toList());
    }

    /** 提交已验证流程的候选，是否接受仍需显式用户确认。 */
    public CompletableFuture<com.javaclaw.sdk.model.SkillProposalInfo> proposeSkill(
            com.javaclaw.sdk.model.SkillLearningDraft draft, String reason, String key) {
        return protocol.proposeSkill(mapper.skillDraft(draft), reason, key).thenApply(mapper::skillProposal);
    }

    /** 接受或拒绝候选；过期目标、证据缺失和版本冲突均失败，不覆盖新内容。 */
    public CompletableFuture<com.javaclaw.sdk.model.SkillProposalInfo> reviewSkillProposal(
            String id, boolean accept, long revision, String key) {
        return protocol.reviewSkillProposal(id, accept, revision, key).thenApply(mapper::skillProposal);
    }

    /** 按版本保存 Skill 声明内容；manifest 不是可加载主 JVM 扩展。 */
    public CompletableFuture<SkillInfo> installSkill(
            String id,
            String name,
            String version,
            JsonDocument manifest,
            boolean enabled,
            long expectedRevision,
            String key) {
        return protocol.installSkill(id, name, version, mapper.parse(manifest), enabled, expectedRevision, key)
                .thenApply(mapper::skill);
    }

    /** 按版本切换 Skill 可见性，只影响后续上下文构建；返回更新结果。 */
    public CompletableFuture<Boolean> setSkillEnabled(String id, boolean enabled, long expectedRevision, String key) {
        return protocol.setSkillEnabled(id, enabled, expectedRevision, key);
    }

    /** 按版本删除 Skill 登记；历史 ContextUsage 不随之删除。 */
    public CompletableFuture<Boolean> uninstallSkill(String id, long expectedRevision, String key) {
        return protocol.uninstallSkill(id, expectedRevision, key);
    }
}
