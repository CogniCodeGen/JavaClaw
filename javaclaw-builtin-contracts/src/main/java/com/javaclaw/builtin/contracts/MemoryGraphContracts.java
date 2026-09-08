package com.javaclaw.builtin.contracts;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.ItemId;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

/** 已确认记忆的结构化图谱投影；实体和关系均不能由模型推断后直接发布。 */
public final class MemoryGraphContracts {
    private MemoryGraphContracts() {}

    /**
     * 已确认记忆对应的实体；首期不自动合并同名人、地点或组织。
     *
     * @param id 实体标识，与所属 Memory 标识一致
     * @param revision 投影 CAS 版本
     * @param memoryRevision 来源 Memory 精确版本
     * @param label 可读名称
     */
    public record Entity(String id, long revision, long memoryRevision, String label) {
        /** 校验标识与来源版本。 */
        public Entity {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            memoryRevision = ContractValidation.revision(memoryRevision);
            label = ContractValidation.text(label, "label");
        }
    }

    /**
     * 已确认断言；普通记忆投影为 REMEMBERS 文本，人工关系关联两个精确版本的实体。
     *
     * @param id 断言标识
     * @param revision 断言 CAS 版本
     * @param subject 主语实体和来源版本
     * @param predicate 明确关系名称
     * @param object 宾语实体和版本；文本断言时与主语相同
     * @param text 已确认正文；关系断言时为空
     */
    public record Assertion(
            String id, long revision, EntityVersion subject, String predicate, EntityVersion object, String text) {
        /** 校验关系内容；仅 REMEMBERS 可以带正文。 */
        public Assertion {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(object, "object");
            predicate = ContractValidation.text(predicate, "predicate");
            text = Objects.requireNonNull(text, "text");
            if (predicate.length() > 64 || (!"REMEMBERS".equals(predicate) && !text.isEmpty())) {
                throw new IllegalArgumentException("invalid confirmed assertion");
            }
        }
    }

    /**
     * 断言所依据的实体版本；Memory 编辑使旧关系失效，必须重新确认。
     *
     * @param id 实体标识
     * @param memoryRevision 精确来源 Memory 版本
     */
    public record EntityVersion(String id, long memoryRevision) {
        /** 校验实体版本。 */
        public EntityVersion {
            id = ContractValidation.text(id, "id");
            memoryRevision = ContractValidation.revision(memoryRevision);
        }
    }

    /**
     * 原文证据引用，不复制任意模型推断为事实。
     *
     * @param id 证据投影标识
     * @param memoryId 已确认记忆
     * @param memoryRevision 来源 Memory 版本
     * @param workspaceId 证据工作空间
     * @param threadId 原始会话
     * @param itemId 原始消息
     * @param sha256 所引用原文 UTF-8 SHA-256，定位后需复核
     */
    public record EvidenceReference(
            String id,
            String memoryId,
            long memoryRevision,
            WorkspaceId workspaceId,
            ThreadId threadId,
            ItemId itemId,
            String sha256) {
        /** 校验证据身份和摘要。 */
        public EvidenceReference {
            id = ContractValidation.text(id, "id");
            memoryId = ContractValidation.text(memoryId, "memoryId");
            memoryRevision = ContractValidation.revision(memoryRevision);
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(itemId, "itemId");
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid evidence digest");
            }
        }
    }

    /**
     * 人工关系确认；请求 expectedRevision 为当前 Workspace Memory head。
     *
     * @param source 主语 Memory
     * @param target 宾语 Memory
     * @param predicate 人工明确的关系名称，不能使用保留的 REMEMBERS / REPLACED_BY
     */
    public record ConfirmRelation(String source, String target, String predicate) {
        /** 校验关系和保留名称。 */
        public ConfirmRelation {
            source = ContractValidation.text(source, "source");
            target = ContractValidation.text(target, "target");
            predicate = ContractValidation.text(predicate, "predicate");
            if (source.equals(target)
                    || predicate.length() > 64
                    || "REMEMBERS".equals(predicate)
                    || "REPLACED_BY".equals(predicate)) {
                throw new IllegalArgumentException("invalid manual relation");
            }
        }
    }

    /**
     * 当前实体的可审计结构化投影。
     *
     * @param entity 实体
     * @param assertions 正文与仍满足精确版本条件的人工关系
     * @param evidence 已确认原文引用
     * @param memoryRevision Workspace Memory head
     */
    public record Projection(
            Entity entity, List<Assertion> assertions, List<EvidenceReference> evidence, long memoryRevision) {
        /** 冻结查询集合。 */
        public Projection {
            Objects.requireNonNull(entity, "entity");
            assertions = List.copyOf(assertions);
            evidence = List.copyOf(evidence);
        }
    }
}
