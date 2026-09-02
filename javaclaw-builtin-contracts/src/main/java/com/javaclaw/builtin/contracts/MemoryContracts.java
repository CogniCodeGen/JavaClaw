package com.javaclaw.builtin.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.ItemId;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

/** Memory 的当前值、历史、学习提案与安全策略契约。 */
public final class MemoryContracts {
    private MemoryContracts() {}

    /** 记忆语义类别。 */
    public enum MemoryKind {
        /** 可由来源逐字核验的事实。 */
        FACT,
        /** 用户偏好、身份或长期行为描述。 */
        PERSONA
    }

    /** Workspace 学习策略。 */
    public enum LearningPolicy {
        /** 不记录学习提案。 */
        OFF,
        /** 只创建待人工处理的提案。 */
        SUGGEST,
        /** 仅自动接受可逐字核验的低风险新增事实。 */
        AUTO_LOW_RISK
    }

    /** 学习提案状态。 */
    public enum ProposalState {
        /** 等待人工决定。 */
        PENDING,
        /** 通过低风险规则自动接受。 */
        AUTO_ACCEPTED,
        /** 经用户显式接受。 */
        ACCEPTED,
        /** 经用户显式拒绝。 */
        REJECTED
    }

    /** 阻止自动写入的保守风险分类。 */
    public enum ProposalConcern {
        /** Persona 内容。 */
        PERSONA,
        /** 与当前记忆存在冲突。 */
        CONFLICT,
        /** 内容可能包含敏感信息。 */
        SENSITIVE,
        /** 内容带推测语义。 */
        SPECULATIVE,
        /** 来源不能逐字核验。 */
        UNCERTAIN_EVIDENCE,
        /** 来源工具结果为 UNKNOWN_OUTCOME。 */
        UNKNOWN_OUTCOME
    }

    /** 学习提交结果。 */
    public enum LearningAction {
        /** 策略关闭，未持久化。 */
        IGNORED,
        /** 已创建待处理提案。 */
        PROPOSED,
        /** 已同时记录提案和新记忆。 */
        AUTO_ACCEPTED
    }

    /**
     * 可由平台回查的逐字来源。
     *
     * @param workspaceId 来源 Workspace
     * @param threadId 来源 Thread
     * @param itemId 来源 Item
     * @param verbatim 必须能在 Item 文本字段中逐字找到的内容
     */
    public record Source(WorkspaceId workspaceId, ThreadId threadId, ItemId itemId, String verbatim) {
        /** 校验来源身份和逐字内容。 */
        public Source {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(itemId, "itemId");
            verbatim = ContractValidation.text(verbatim, "verbatim");
        }
    }

    /**
     * 当前可检索记忆。
     *
     * @param id 稳定标识
     * @param revision 单调版本
     * @param kind 语义类别
     * @param scope 用户可见作用域标签
     * @param content 记忆正文
     * @param tags 检索标签
     * @param pinned 是否固定
     * @param source 可选来源 Item
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record Memory(
            String id,
            long revision,
            MemoryKind kind,
            String scope,
            String content,
            Set<String> tags,
            boolean pinned,
            Optional<Source> source,
            Instant createdAt,
            Instant updatedAt)
            implements VersionedExtensionDocument {
        /** 校验并复制记忆。 */
        public Memory {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            Objects.requireNonNull(kind, "kind");
            scope = ContractValidation.text(scope, "scope");
            content = ContractValidation.text(content, "content");
            tags = ContractValidation.textSet(tags, "tags");
            source = Objects.requireNonNull(source, "source");
            createdAt = ContractValidation.instant(createdAt, "createdAt");
            updatedAt = ContractValidation.instant(updatedAt, "updatedAt");
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("updatedAt must not be before createdAt");
            }
        }
    }

    /**
     * 显式创建记忆的用户输入。
     *
     * @param id 稳定标识
     * @param kind 语义类别
     * @param scope 用户可见作用域标签
     * @param content 记忆正文
     * @param tags 检索标签
     * @param pinned 是否固定
     * @param source 可选来源 Item
     */
    public record CreateRequest(
            String id,
            MemoryKind kind,
            String scope,
            String content,
            Set<String> tags,
            boolean pinned,
            Optional<Source> source) {
        /** 校验创建输入。 */
        public CreateRequest {
            id = ContractValidation.text(id, "id");
            Objects.requireNonNull(kind, "kind");
            scope = ContractValidation.text(scope, "scope");
            content = ContractValidation.text(content, "content");
            tags = ContractValidation.textSet(tags, "tags");
            source = Objects.requireNonNull(source, "source");
        }
    }

    /**
     * 管理中心结构化标签行。
     *
     * @param itemKey 本次编辑会话内稳定且不可编辑的行键
     * @param value 标签正文
     */
    public record ManagementTag(String itemKey, String value) {
        /** 校验行键和标签。 */
        public ManagementTag {
            itemKey = ContractValidation.text(itemKey, "itemKey");
            value = ContractValidation.text(value, "value");
        }
    }

    /**
     * 管理中心结构化来源行；Workspace 始终由服务端请求上下文绑定。
     *
     * @param itemKey 本次编辑会话内稳定且不可编辑的行键
     * @param threadId 来源 Thread UUID
     * @param itemId 来源 Item UUID
     * @param verbatim 必须能从来源 Item 逐字核验的内容
     */
    public record ManagementSource(String itemKey, String threadId, String itemId, String verbatim) {
        /** 校验来源文本；UUID 和归属由命令处理器核验。 */
        public ManagementSource {
            itemKey = ContractValidation.text(itemKey, "itemKey");
            threadId = ContractValidation.text(threadId, "threadId");
            itemId = ContractValidation.text(itemId, "itemId");
            verbatim = ContractValidation.text(verbatim, "verbatim");
        }
    }

    /**
     * 管理中心手工创建记忆的强类型输入。
     *
     * <p>标签和来源来自平台 StructuredList，拒绝重复标签、重复行键和多个来源。来源 Workspace 不接受客户端输入。
     *
     * @param id 稳定标识
     * @param kind 语义类别
     * @param scope 用户可见作用域标签
     * @param content 记忆正文
     * @param tags 最多 32 个结构化标签
     * @param pinned 是否固定
     * @param sources 零个或一个可核验来源
     */
    public record ManagementCreateRequest(
            String id,
            MemoryKind kind,
            String scope,
            String content,
            List<ManagementTag> tags,
            boolean pinned,
            List<ManagementSource> sources) {
        /** 校验并复制管理中心输入。 */
        public ManagementCreateRequest {
            id = ContractValidation.text(id, "id");
            Objects.requireNonNull(kind, "kind");
            scope = ContractValidation.text(scope, "scope");
            content = ContractValidation.text(content, "content");
            tags = List.copyOf(tags);
            sources = List.copyOf(sources);
            if (tags.size() > 32
                    || tags.stream().map(ManagementTag::itemKey).distinct().count() != tags.size()
                    || tags.stream().map(ManagementTag::value).distinct().count() != tags.size()) {
                throw new IllegalArgumentException("management tags are invalid");
            }
            if (sources.size() > 1
                    || sources.stream()
                                    .map(ManagementSource::itemKey)
                                    .distinct()
                                    .count()
                            != sources.size()) {
                throw new IllegalArgumentException("management source is invalid");
            }
        }
    }

    /**
     * 显式纠错或编辑记忆的用户输入。
     *
     * @param id 稳定标识
     * @param kind 语义类别
     * @param scope 用户可见作用域标签
     * @param content 记忆正文
     * @param tags 检索标签
     * @param pinned 是否固定
     * @param source 可选来源 Item
     */
    public record UpdateRequest(
            String id,
            MemoryKind kind,
            String scope,
            String content,
            Set<String> tags,
            boolean pinned,
            Optional<Source> source) {
        /** 校验更新输入。 */
        public UpdateRequest {
            id = ContractValidation.text(id, "id");
            Objects.requireNonNull(kind, "kind");
            scope = ContractValidation.text(scope, "scope");
            content = ContractValidation.text(content, "content");
            tags = ContractValidation.textSet(tags, "tags");
            source = Objects.requireNonNull(source, "source");
        }
    }

    /**
     * 管理中心对记忆正文执行的受限纠错。
     *
     * <p>标签、来源、固定状态与创建时间由服务端从当前版本保留，避免扁平表单覆盖不可见字段。
     *
     * @param id 记忆标识
     * @param kind 纠错后的语义类别
     * @param scope 纠错后的作用域
     * @param content 纠错后的正文
     */
    public record ContentUpdateRequest(String id, MemoryKind kind, String scope, String content) {
        /** 校验纠错输入。 */
        public ContentUpdateRequest {
            id = ContractValidation.text(id, "id");
            Objects.requireNonNull(kind, "kind");
            scope = ContractValidation.text(scope, "scope");
            content = ContractValidation.text(content, "content");
        }
    }

    /**
     * 修改固定状态的请求。
     *
     * @param id 记忆标识
     * @param pinned 新固定状态
     */
    public record PinRequest(String id, boolean pinned) {
        /** 校验标识。 */
        public PinRequest {
            id = ContractValidation.text(id, "id");
        }
    }

    /**
     * 记忆标识请求。
     *
     * @param id 记忆标识
     */
    public record Key(String id) {
        /** 校验标识。 */
        public Key {
            id = ContractValidation.text(id, "id");
        }
    }

    /**
     * 从不可变历史恢复记忆的请求。
     *
     * @param id 记忆标识
     * @param sourceRevision 要恢复的历史版本
     */
    public record RestoreRequest(String id, long sourceRevision) {
        /** 校验来源版本。 */
        public RestoreRequest {
            id = ContractValidation.text(id, "id");
            sourceRevision = ContractValidation.revision(sourceRevision);
        }
    }

    /**
     * 历史分页请求。
     *
     * @param id 记忆标识
     * @param afterRevision 排他性起始版本，零表示从头读取
     * @param limit 最大结果数，范围为 1 到 100
     */
    public record HistoryRequest(String id, long afterRevision, int limit) {
        /** 校验分页参数。 */
        public HistoryRequest {
            id = ContractValidation.text(id, "id");
            if (afterRevision < 0 || limit < 1 || limit > 100) {
                throw new IllegalArgumentException("history cursor or limit is invalid");
            }
        }
    }

    /**
     * 历史版本。
     *
     * @param revision 历史版本
     * @param memory 该版本保存的记忆快照
     * @param tombstone 该版本是否为删除标记
     * @param updatedAt 写入时间
     */
    public record HistoryEntry(long revision, Memory memory, boolean tombstone, Instant updatedAt) {
        /** 校验历史身份。 */
        public HistoryEntry {
            revision = ContractValidation.revision(revision);
            Objects.requireNonNull(memory, "memory");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    /**
     * 历史分页结果。
     *
     * @param entries 历史页
     * @param hasMore 是否有下一页
     */
    public record HistoryPage(List<HistoryEntry> entries, boolean hasMore) {
        /** 复制历史。 */
        public HistoryPage {
            entries = List.copyOf(entries);
        }
    }

    /**
     * 记忆检索条件。
     *
     * @param query 正文关键词
     * @param scopes 允许的作用域；空集合表示不限制
     * @param tags 必须匹配的标签；空集合表示不限制
     * @param limit 最大结果数，范围为 1 到 100
     */
    public record SearchRequest(String query, Set<String> scopes, Set<String> tags, int limit) {
        /** 校验并复制条件。 */
        public SearchRequest {
            query = ContractValidation.text(query, "query");
            scopes = ContractValidation.textSet(scopes, "scopes");
            tags = ContractValidation.textSet(tags, "tags");
            limit = ContractValidation.searchLimit(limit);
        }
    }

    /** @param matches 按固定、更新时间和 ID 稳定排序的记忆 */
    public record SearchResult(List<Memory> matches) {
        /** 复制结果。 */
        public SearchResult {
            matches = List.copyOf(matches);
        }
    }

    /**
     * Workspace 学习设置。
     *
     * @param revision 设置版本；初始值可以为零
     * @param policy 当前学习策略
     * @param updatedAt 最近更新时间
     */
    public record LearningSettings(long revision, LearningPolicy policy, Instant updatedAt) {
        /** 校验设置。 */
        public LearningSettings {
            if (revision < 0) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    /**
     * 学习策略更新请求。
     *
     * @param policy 新学习策略
     */
    public record LearningSettingsUpdate(LearningPolicy policy) {
        /** 校验策略。 */
        public LearningSettingsUpdate {
            Objects.requireNonNull(policy, "policy");
        }
    }

    /**
     * 模型或工具提交的学习候选。
     *
     * @param id 提案标识
     * @param kind 候选语义类别
     * @param scope 用户可见作用域标签
     * @param content 候选正文
     * @param tags 检索标签
     * @param source 平台可回查的逐字来源
     */
    public record LearningProposalRequest(
            String id, MemoryKind kind, String scope, String content, Set<String> tags, Source source) {
        /** 校验候选。 */
        public LearningProposalRequest {
            id = ContractValidation.text(id, "id");
            Objects.requireNonNull(kind, "kind");
            scope = ContractValidation.text(scope, "scope");
            content = ContractValidation.text(content, "content");
            tags = ContractValidation.textSet(tags, "tags");
            Objects.requireNonNull(source, "source");
        }
    }

    /**
     * 不可静默发布的学习提案。
     *
     * @param id 提案标识
     * @param revision 提案版本
     * @param candidate 原始候选
     * @param concerns 阻止自动接受的风险原因
     * @param state 当前状态
     * @param memoryId 接受后生成的记忆标识
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record Proposal(
            String id,
            long revision,
            LearningProposalRequest candidate,
            Set<ProposalConcern> concerns,
            ProposalState state,
            Optional<String> memoryId,
            Instant createdAt,
            Instant updatedAt)
            implements VersionedExtensionDocument {
        /** 校验提案。 */
        public Proposal {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            Objects.requireNonNull(candidate, "candidate");
            concerns = Set.copyOf(concerns);
            Objects.requireNonNull(state, "state");
            memoryId = Objects.requireNonNull(memoryId, "memoryId");
            createdAt = ContractValidation.instant(createdAt, "createdAt");
            updatedAt = ContractValidation.instant(updatedAt, "updatedAt");
            boolean accepted = state == ProposalState.ACCEPTED || state == ProposalState.AUTO_ACCEPTED;
            if (accepted != memoryId.isPresent()) {
                throw new IllegalArgumentException("accepted proposal must reference a memory");
            }
        }
    }

    /**
     * 提案决定请求。
     *
     * @param id 提案标识
     */
    public record ProposalDecision(String id) {
        /** 校验标识。 */
        public ProposalDecision {
            id = ContractValidation.text(id, "id");
        }
    }

    /**
     * 学习提交结果。
     *
     * @param action 已执行动作
     * @param proposal 可选的提案
     * @param memory 可选的新记忆
     */
    public record LearningResult(LearningAction action, Optional<Proposal> proposal, Optional<Memory> memory) {
        /** 校验动作和结果一致。 */
        public LearningResult {
            Objects.requireNonNull(action, "action");
            proposal = Objects.requireNonNull(proposal, "proposal");
            memory = Objects.requireNonNull(memory, "memory");
            if (action == LearningAction.IGNORED && (proposal.isPresent() || memory.isPresent())) {
                throw new IllegalArgumentException("ignored learning must not persist results");
            }
            if (action == LearningAction.PROPOSED && (proposal.isEmpty() || memory.isPresent())) {
                throw new IllegalArgumentException("proposed learning result is invalid");
            }
            if (action == LearningAction.AUTO_ACCEPTED && (proposal.isEmpty() || memory.isEmpty())) {
                throw new IllegalArgumentException("auto accepted learning must include proposal and memory");
            }
        }
    }

    /**
     * Workspace 记忆统计。
     *
     * @param active 活动记忆数
     * @param pinned 已固定记忆数
     * @param pendingProposals 待处理提案数
     * @param tombstones 删除标记数
     */
    public record Stats(long active, long pinned, long pendingProposals, long tombstones) {
        /** 校验计数非负。 */
        public Stats {
            if (active < 0 || pinned < 0 || pendingProposals < 0 || tombstones < 0) {
                throw new IllegalArgumentException("memory statistics must not be negative");
            }
        }
    }
}
