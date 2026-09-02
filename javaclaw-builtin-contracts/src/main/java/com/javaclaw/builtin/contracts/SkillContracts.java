package com.javaclaw.builtin.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.TurnId;

/** Skill Draft、Published、Proposal 与冻结发现契约。 */
public final class SkillContracts {
    /** 单个 Skill 资源允许的最大原始字节数。 */
    public static final long MAXIMUM_RESOURCE_BYTES = 256L * 1024;

    /** 单个 Skill 最多包含的资源数。 */
    public static final int MAXIMUM_RESOURCES = 64;

    /** App Server 组合根注册的 Skill 资源隔离执行服务。 */
    public static final String RESOURCE_EXECUTION_SERVICE = "skill.resource.execution";

    /** 只允许 Java source-file mode 的 MIME 类型。 */
    public static final String JAVA_SOURCE_MEDIA_TYPE = "text/x-java-source";

    /** 只允许 JShell 脚本的 MIME 类型。 */
    public static final String JSHELL_MEDIA_TYPE = "text/x-jshell";

    private SkillContracts() {}

    /** Skill 提案状态。 */
    public enum ProposalState {
        /** 等待人工处理。 */
        PENDING,
        /** 已采纳为 Draft，尚未发布。 */
        ADOPTED_AS_DRAFT,
        /** 已拒绝。 */
        REJECTED
    }

    /**
     * Skill 资源引用；资源正文由受管 Attachment/Blob 边界保存。
     *
     * @param id 资源标识
     * @param mediaType MIME 类型
     * @param digest 内容 SHA-256
     * @param executable 是否声明为可执行资源；仅已发布的 Java/JShell 资源可进入受治理执行入口
     */
    public record Resource(String id, String mediaType, String digest, boolean executable) {
        /** 校验资源元数据。 */
        public Resource {
            id = ContractValidation.text(id, "id");
            mediaType = ContractValidation.text(mediaType, "mediaType");
            digest = ContractDigests.requireSha256(digest, "digest");
            if (executable) {
                requireExecutableMediaType(mediaType);
            }
        }
    }

    /**
     * 可编辑但不可被 Turn 发现的 Skill Draft。
     *
     * @param id Skill 标识
     * @param revision Draft 版本
     * @param name 名称
     * @param description 检索摘要
     * @param instructions 待审阅指令
     * @param resources 资源引用
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record Draft(
            String id,
            long revision,
            String name,
            String description,
            String instructions,
            List<Resource> resources,
            Instant createdAt,
            Instant updatedAt)
            implements VersionedExtensionDocument {
        /** 校验 Draft。 */
        public Draft {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            name = ContractValidation.text(name, "name");
            description = ContractValidation.text(description, "description");
            instructions = ContractValidation.text(instructions, "instructions");
            resources = validatedResources(resources);
            createdAt = ContractValidation.instant(createdAt, "createdAt");
            updatedAt = ContractValidation.instant(updatedAt, "updatedAt");
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("updatedAt must not be before createdAt");
            }
        }
    }

    /**
     * 管理中心保存 Draft 文本字段的受限输入。
     *
     * <p>已有 Draft 的资源引用由服务端保留；创建 Draft 时资源列表为空，资源只能通过独立的 Attachment 管理流程加入。
     *
     * @param id Skill 标识
     * @param name 名称
     * @param description 检索摘要
     * @param instructions 待审阅指令
     */
    public record SaveContentRequest(String id, String name, String description, String instructions) {
        /** 校验文本输入。 */
        public SaveContentRequest {
            id = ContractValidation.text(id, "id");
            name = ContractValidation.text(name, "name");
            description = ContractValidation.text(description, "description");
            instructions = ContractValidation.text(instructions, "instructions");
        }
    }

    /**
     * 把已经上传并取得 Workspace claim 的 Attachment 加入 Draft。
     *
     * @param id Draft 标识；管理页面必须从所选 Draft 权威绑定
     * @param resourceId Skill 内稳定且唯一的资源标识
     * @param attachment Core 上传返回的完整引用
     * @param executable 是否声明为可执行资源；仅 Java source 或 JShell 可执行
     */
    public record AddResourceRequest(String id, String resourceId, AttachmentRef attachment, boolean executable) {
        /** 校验资源标识、引用和静态大小边界；所有权及元数据由服务端端口核验。 */
        public AddResourceRequest {
            id = ContractValidation.text(id, "id");
            resourceId = ContractValidation.text(resourceId, "resourceId");
            Objects.requireNonNull(attachment, "attachment");
            if (attachment.sizeBytes() < 1 || attachment.sizeBytes() > MAXIMUM_RESOURCE_BYTES) {
                throw new IllegalArgumentException("Skill resource size exceeds the supported range");
            }
            if (executable) {
                requireExecutableMediaType(attachment.mediaType());
            }
        }
    }

    /**
     * 从 Draft 删除一个资源引用的管理请求。
     *
     * @param id Draft 标识；管理页面必须从所选资源行权威绑定
     * @param resourceId 要删除的资源标识
     */
    public record RemoveResourceRequest(String id, String resourceId) {
        /** 校验 Draft 和资源标识。 */
        public RemoveResourceRequest {
            id = ContractValidation.text(id, "id");
            resourceId = ContractValidation.text(resourceId, "resourceId");
        }
    }

    /**
     * 已人工发布的不可变内容快照。
     *
     * @param id Skill 标识
     * @param revision Published 版本
     * @param draftRevision 来源 Draft 版本
     * @param digest 发布内容摘要
     * @param name 名称
     * @param description 检索摘要
     * @param instructions 已审阅指令
     * @param resources 冻结资源引用
     * @param enabled 是否允许新发现和精确读取
     * @param publishedAt 首次发布时间
     * @param updatedAt 最近发布或启停时间
     */
    public record PublishedSkill(
            String id,
            long revision,
            long draftRevision,
            String digest,
            String name,
            String description,
            String instructions,
            List<Resource> resources,
            boolean enabled,
            Instant publishedAt,
            Instant updatedAt)
            implements VersionedExtensionDocument {
        /** 校验发布快照。 */
        public PublishedSkill {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            draftRevision = ContractValidation.revision(draftRevision);
            digest = ContractDigests.requireSha256(digest, "digest");
            name = ContractValidation.text(name, "name");
            description = ContractValidation.text(description, "description");
            instructions = ContractValidation.text(instructions, "instructions");
            resources = validatedResources(resources);
            publishedAt = ContractValidation.instant(publishedAt, "publishedAt");
            updatedAt = ContractValidation.instant(updatedAt, "updatedAt");
            if (updatedAt.isBefore(publishedAt)) {
                throw new IllegalArgumentException("updatedAt must not be before publishedAt");
            }
        }
    }

    /**
     * Skill 标识请求。
     *
     * @param id Skill 标识
     */
    public record Key(String id) {
        /** 校验标识。 */
        public Key {
            id = ContractValidation.text(id, "id");
        }
    }

    /**
     * 发布指定 Draft 版本的请求。
     *
     * @param id Skill 标识
     * @param draftRevision 要发布的精确 Draft 版本
     */
    public record PublishRequest(String id, long draftRevision) {
        /** 校验发布输入。 */
        public PublishRequest {
            id = ContractValidation.text(id, "id");
            draftRevision = ContractValidation.revision(draftRevision);
        }
    }

    /**
     * 修改发布 Skill 启用状态的请求。
     *
     * @param id Skill 标识
     * @param enabled 新启用状态
     */
    public record EnableRequest(String id, boolean enabled) {
        /** 校验标识。 */
        public EnableRequest {
            id = ContractValidation.text(id, "id");
        }
    }

    /**
     * Turn 目录只公开的稳定摘要。
     *
     * @param id Skill 标识
     * @param revision Published 版本
     * @param digest 发布摘要
     * @param name 名称
     * @param description 描述
     */
    public record Summary(String id, long revision, String digest, String name, String description) {
        /** 校验摘要。 */
        public Summary {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            digest = ContractDigests.requireSha256(digest, "digest");
            name = ContractValidation.text(name, "name");
            description = ContractValidation.text(description, "description");
        }
    }

    /**
     * Skill 摘要检索请求。
     *
     * @param query 名称或说明关键词
     * @param limit 最大结果数，1 到 100
     */
    public record SearchRequest(String query, int limit) {
        /** 校验条件。 */
        public SearchRequest {
            query = ContractValidation.text(query, "query");
            limit = ContractValidation.searchLimit(limit);
        }
    }

    /**
     * 冻结目录中的搜索结果。
     *
     * @param matches 匹配摘要
     * @param catalogDigest 本 Turn Skill 目录摘要；管理查询为空字符串
     */
    public record SearchResult(List<Summary> matches, String catalogDigest) {
        /** 复制结果。 */
        public SearchResult {
            matches = List.copyOf(matches);
            catalogDigest = Objects.requireNonNull(catalogDigest, "catalogDigest");
        }
    }

    /**
     * 精确读取已发布 Skill 的请求。
     *
     * @param id Skill 标识
     * @param revision 冻结的 Published 版本
     * @param digest 冻结的发布摘要
     */
    public record PublishedReadRequest(String id, long revision, String digest) {
        /** 校验精确身份。 */
        public PublishedReadRequest {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            digest = ContractDigests.requireSha256(digest, "digest");
        }
    }

    /**
     * Skill 提案输入；采纳后也只生成 Draft。
     *
     * @param id 提案标识
     * @param draftId 采纳后创建的 Draft 标识
     * @param name 名称
     * @param description 检索摘要
     * @param instructions 待审阅指令
     */
    public record ProposeRequest(String id, String draftId, String name, String description, String instructions) {
        /** 校验提案输入。 */
        public ProposeRequest {
            id = ContractValidation.text(id, "id");
            draftId = ContractValidation.text(draftId, "draftId");
            name = ContractValidation.text(name, "name");
            description = ContractValidation.text(description, "description");
            instructions = ContractValidation.text(instructions, "instructions");
        }
    }

    /**
     * Skill 生成提案。
     *
     * @param id 提案标识
     * @param revision 提案版本
     * @param candidate 原始候选
     * @param state 当前状态
     * @param draftId 采纳后生成的 Draft 标识
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record Proposal(
            String id,
            long revision,
            ProposeRequest candidate,
            ProposalState state,
            Optional<String> draftId,
            Instant createdAt,
            Instant updatedAt)
            implements VersionedExtensionDocument {
        /** 校验提案状态。 */
        public Proposal {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(state, "state");
            draftId = Objects.requireNonNull(draftId, "draftId");
            createdAt = ContractValidation.instant(createdAt, "createdAt");
            updatedAt = ContractValidation.instant(updatedAt, "updatedAt");
            if ((state == ProposalState.ADOPTED_AS_DRAFT) != draftId.isPresent()) {
                throw new IllegalArgumentException("adopted proposal must reference a Draft");
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
     * Draft 历史分页请求。
     *
     * @param id Draft 标识
     * @param afterRevision 排他性起始版本，零表示从头读取
     * @param limit 最大结果数，范围为 1 到 100
     */
    public record HistoryRequest(String id, long afterRevision, int limit) {
        /** 校验分页。 */
        public HistoryRequest {
            id = ContractValidation.text(id, "id");
            if (afterRevision < 0 || limit < 1 || limit > 100) {
                throw new IllegalArgumentException("history cursor or limit is invalid");
            }
        }
    }

    /**
     * Draft 历史恢复请求。
     *
     * @param id Skill 标识
     * @param sourceRevision 要恢复的历史版本
     */
    public record DraftRestoreRequest(String id, long sourceRevision) {
        /** 校验恢复请求。 */
        public DraftRestoreRequest {
            id = ContractValidation.text(id, "id");
            sourceRevision = ContractValidation.revision(sourceRevision);
        }
    }

    /**
     * Draft 历史版本。
     *
     * @param revision 历史版本
     * @param draft 该版本保存的 Draft 快照
     * @param tombstone 该版本是否为删除标记
     * @param updatedAt 写入时间
     */
    public record DraftHistoryEntry(long revision, Draft draft, boolean tombstone, Instant updatedAt) {
        /** 校验历史。 */
        public DraftHistoryEntry {
            revision = ContractValidation.revision(revision);
            Objects.requireNonNull(draft, "draft");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    /**
     * Draft 历史分页结果。
     *
     * @param entries Draft 历史页
     * @param hasMore 是否有下一页
     */
    public record DraftHistoryPage(List<DraftHistoryEntry> entries, boolean hasMore) {
        /** 复制历史。 */
        public DraftHistoryPage {
            entries = List.copyOf(entries);
        }
    }

    /**
     * Turn 内部持久化的 Skill 目录快照。
     *
     * @param turnId 所属 Turn
     * @param skills 冻结摘要
     * @param digest 目录摘要
     * @param capturedAt 捕获时间
     */
    public record CatalogSnapshot(TurnId turnId, List<Summary> skills, String digest, Instant capturedAt) {
        /** 校验目录快照。 */
        public CatalogSnapshot {
            Objects.requireNonNull(turnId, "turnId");
            skills = List.copyOf(skills);
            digest = ContractDigests.requireSha256(digest, "digest");
            Objects.requireNonNull(capturedAt, "capturedAt");
        }
    }

    /**
     * 可执行资源能力状态。
     *
     * @param executable 当前运行环境是否能使用 Native Sandbox 执行资源
     * @param reason 不可用原因；可用时为空字符串
     */
    public record ResourceExecutionAvailability(boolean executable, String reason) {
        /** 校验可用状态。 */
        public ResourceExecutionAvailability {
            reason = Objects.requireNonNull(reason, "reason").strip();
            if ((executable && !reason.isEmpty()) || (!executable && reason.isEmpty())) {
                throw new IllegalArgumentException("resource execution availability and reason disagree");
            }
        }
    }

    /** Skill 隔离服务操作。 */
    public enum ResourceExecutionOperation {
        /** 只读检查当前发行镜像与 Native Sandbox。 */
        STATUS,
        /** 执行已发布并绑定摘要的资源。 */
        EXECUTE
    }

    /**
     * Turn 工具发起的精确资源执行请求。
     *
     * @param skill 冻结 Published Skill 身份
     * @param resourceId Skill 内的资源标识
     * @param arguments 不经过 shell 的有界参数
     */
    public record ResourceExecutionRequest(PublishedReadRequest skill, String resourceId, List<String> arguments) {
        /** 校验冻结身份与参数上限。 */
        public ResourceExecutionRequest {
            Objects.requireNonNull(skill, "skill");
            resourceId = ContractValidation.text(resourceId, "resourceId");
            arguments = validatedArguments(arguments);
        }
    }

    /**
     * 内置扩展交给 App Server 隔离服务的请求。
     *
     * @param operation 操作
     * @param resource 执行时必填的已发布资源元数据
     * @param arguments 不经过 shell 的有界参数
     */
    public record ResourceExecutionInvocation(
            ResourceExecutionOperation operation, Optional<Resource> resource, List<String> arguments) {
        /** 校验操作与资源组合。 */
        public ResourceExecutionInvocation {
            Objects.requireNonNull(operation, "operation");
            resource = Objects.requireNonNull(resource, "resource");
            arguments = validatedArguments(arguments);
            boolean status = operation == ResourceExecutionOperation.STATUS;
            if (status != resource.isEmpty() || (status && !arguments.isEmpty())) {
                throw new IllegalArgumentException("resource execution invocation shape is invalid");
            }
            resource.ifPresent(value -> {
                if (!value.executable()) {
                    throw new IllegalArgumentException("resource is not declared executable");
                }
                requireExecutableMediaType(value.mediaType());
            });
        }
    }

    /**
     * Native Sandbox 资源执行结果。
     *
     * @param exitCode 目标进程退出码
     * @param output 有界标准输出
     * @param truncated 是否因输出上限截断
     * @param elapsedMillis 宿主观测的毫秒耗时
     */
    public record ResourceExecutionResult(int exitCode, String output, boolean truncated, long elapsedMillis) {
        /** 校验执行结果不包含无界或负值数据。 */
        public ResourceExecutionResult {
            if (exitCode < 0 || elapsedMillis < 0) {
                throw new IllegalArgumentException("resource execution result contains a negative value");
            }
            output = Objects.requireNonNull(output, "output");
        }
    }

    private static List<String> validatedArguments(List<String> values) {
        List<String> copied = List.copyOf(Objects.requireNonNull(values, "arguments"));
        if (copied.size() > 16) {
            throw new IllegalArgumentException("resource execution accepts at most 16 arguments");
        }
        for (String value : copied) {
            if (value == null || value.length() > 1_024 || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("resource execution argument is invalid");
            }
        }
        return copied;
    }

    private static List<Resource> validatedResources(List<Resource> values) {
        List<Resource> copied = List.copyOf(Objects.requireNonNull(values, "resources"));
        if (copied.size() > MAXIMUM_RESOURCES) {
            throw new IllegalArgumentException("Skill resource count exceeds the supported range");
        }
        if (copied.stream().map(Resource::id).distinct().count() != copied.size()) {
            throw new IllegalArgumentException("Skill resource id is duplicated");
        }
        return copied;
    }

    private static void requireExecutableMediaType(String mediaType) {
        if (!JAVA_SOURCE_MEDIA_TYPE.equals(mediaType) && !JSHELL_MEDIA_TYPE.equals(mediaType)) {
            throw new IllegalArgumentException("only Java source and JShell resources are executable");
        }
    }
}
