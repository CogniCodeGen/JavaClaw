package com.javaclaw.builtin.contracts;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Plan 扩展的可编辑 Definition 契约；运行状态只存在于平台托管 Execution。 */
public final class PlanContracts {
    private static final int MAX_MANAGEMENT_ROWS = 100;

    private PlanContracts() {}

    /** Plan 生成提案的人工决策状态。 */
    public enum ProposalState {
        /** 等待人工审阅。 */
        PENDING,
        /** 已原子采纳为 Definition。 */
        ADOPTED,
        /** 已明确拒绝。 */
        REJECTED
    }

    /**
     * 管理中心中的单条风险输入。
     *
     * @param itemKey 编辑会话内的稳定行键，不进入持久化 Definition
     * @param description 风险说明
     */
    public record ManagementRisk(String itemKey, String description) {
        /** 校验稳定行键和风险正文。 */
        public ManagementRisk {
            itemKey = ContractValidation.text(itemKey, "itemKey");
            description = ContractValidation.text(description, "description");
        }
    }

    /**
     * 管理中心中的开放问题输入。
     *
     * <p>客户端不提交内容摘要。服务端以本次 {@code prompt} 重新计算摘要，并把可选决策绑定到该摘要。
     *
     * @param itemKey 编辑会话内的稳定行键，不进入持久化 Definition
     * @param id 稳定问题标识
     * @param prompt 问题正文
     * @param decision 用户显式决策；未决时为空
     */
    public record ManagementOpenQuestion(String itemKey, String id, String prompt, Optional<String> decision) {
        /** 校验强类型问题输入，不接受客户端摘要。 */
        public ManagementOpenQuestion {
            itemKey = ContractValidation.text(itemKey, "itemKey");
            id = ContractValidation.text(id, "id");
            prompt = ContractValidation.text(prompt, "prompt");
            decision = optionalText(decision, "decision");
        }
    }

    /**
     * 管理中心中的计划步骤输入。
     *
     * @param itemKey 编辑会话内的稳定行键，不进入持久化 Definition
     * @param id 稳定步骤标识
     * @param title 简短标题
     * @param instruction 单 Turn 指令
     * @param acceptanceCriteria 可验证验收条件
     * @param dependencies 前置步骤 ID
     */
    public record ManagementStep(
            String itemKey,
            String id,
            String title,
            String instruction,
            String acceptanceCriteria,
            List<String> dependencies) {
        /** 校验强类型步骤输入并复制依赖。 */
        public ManagementStep {
            itemKey = ContractValidation.text(itemKey, "itemKey");
            id = ContractValidation.text(id, "id");
            title = ContractValidation.text(title, "title");
            instruction = ContractValidation.text(instruction, "instruction");
            acceptanceCriteria = ContractValidation.text(acceptanceCriteria, "acceptanceCriteria");
            dependencies = ContractValidation.textList(dependencies, "dependencies");
        }
    }

    /**
     * 管理中心保存 Plan Definition 的强类型输入。
     *
     * <p>服务端负责生成 revision、更新时间、问题摘要和决策摘要。编辑动作中的 {@code id} 必须由平台从权威详情数据源绑定，不能来自可编辑字段。
     *
     * @param id Definition 标识；新建时由用户指定，编辑时由平台权威绑定
     * @param title 标题
     * @param objective 验收目标
     * @param scope 明确范围
     * @param risks 结构化风险行
     * @param openQuestions 结构化开放问题行
     * @param steps 结构化依赖步骤行
     */
    public record ManagementSaveRequest(
            String id,
            String title,
            String objective,
            String scope,
            List<ManagementRisk> risks,
            List<ManagementOpenQuestion> openQuestions,
            List<ManagementStep> steps) {
        /** 校验管理输入的规模和编辑会话行键。 */
        public ManagementSaveRequest {
            id = ContractValidation.text(id, "id");
            title = ContractValidation.text(title, "title");
            objective = ContractValidation.text(objective, "objective");
            scope = ContractValidation.text(scope, "scope");
            risks = managementRows(risks, "risks", false);
            openQuestions = managementRows(openQuestions, "openQuestions", false);
            steps = managementRows(steps, "steps", true);
            requireUniqueItemKeys(risks.stream().map(ManagementRisk::itemKey).toList(), "risks");
            requireUniqueItemKeys(
                    openQuestions.stream().map(ManagementOpenQuestion::itemKey).toList(), "openQuestions");
            requireUniqueItemKeys(steps.stream().map(ManagementStep::itemKey).toList(), "steps");
        }
    }

    /**
     * 可独立验收的计划步骤。
     *
     * @param id 稳定步骤标识
     * @param title 简短标题
     * @param instruction 单 Turn 指令
     * @param acceptanceCriteria 可验证验收条件
     * @param dependencies 前置步骤 ID
     */
    public record Step(
            String id, String title, String instruction, String acceptanceCriteria, List<String> dependencies) {
        /** 校验配置并复制依赖。 */
        public Step {
            id = ContractValidation.text(id, "id");
            title = ContractValidation.text(title, "title");
            instruction = ContractValidation.text(instruction, "instruction");
            acceptanceCriteria = ContractValidation.text(acceptanceCriteria, "acceptanceCriteria");
            dependencies = ContractValidation.textList(dependencies, "dependencies");
        }
    }

    /**
     * 开放问题及其显式决策。
     *
     * @param id 问题标识
     * @param prompt 问题正文
     * @param contentHash 问题正文 SHA-256
     * @param decision 用户显式决策；未决时为空
     */
    public record OpenQuestion(String id, String prompt, String contentHash, Optional<Decision> decision) {
        /** 校验决策绑定的内容摘要，避免正文变化后沿用旧决策。 */
        public OpenQuestion {
            id = ContractValidation.text(id, "id");
            prompt = ContractValidation.text(prompt, "prompt");
            contentHash = ContractDigests.requireMatch(contentHash, prompt, "contentHash");
            decision = Objects.requireNonNull(decision, "decision");
            if (decision.isPresent() && !decision.orElseThrow().questionHash().equals(contentHash)) {
                throw new IllegalArgumentException("decision questionHash does not match current question");
            }
        }
    }

    /**
     * 与问题正文摘要绑定的用户决策。
     *
     * @param questionHash 决策时的问题 SHA-256
     * @param answer 用户答案
     */
    public record Decision(String questionHash, String answer) {
        /** 校验摘要格式和答案。 */
        public Decision {
            questionHash =
                    Objects.requireNonNull(questionHash, "questionHash").strip().toLowerCase(java.util.Locale.ROOT);
            if (!questionHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("questionHash must be a SHA-256 digest");
            }
            answer = ContractValidation.text(answer, "answer");
        }
    }

    /**
     * 不含持久化 revision 和编辑器行键的 Plan 候选内容。
     *
     * @param id 目标 Definition 标识
     * @param title 标题
     * @param objective 验收目标
     * @param scope 明确范围
     * @param risks 已识别风险
     * @param openQuestions 开放问题及可选决策
     * @param steps 有稳定 ID、验收条件和依赖关系的步骤
     */
    public record Candidate(
            String id,
            String title,
            String objective,
            String scope,
            List<String> risks,
            List<OpenQuestion> openQuestions,
            List<Step> steps) {
        /** 校验候选内容和步骤图。 */
        public Candidate {
            id = ContractValidation.text(id, "id");
            title = ContractValidation.text(title, "title");
            objective = ContractValidation.text(objective, "objective");
            scope = ContractValidation.text(scope, "scope");
            risks = ContractValidation.textList(risks, "risks");
            openQuestions = List.copyOf(openQuestions);
            steps = List.copyOf(steps);
            PlanDefinitionGraph.validate(openQuestions, steps);
        }

        /**
         * 判断候选中的全部开放问题是否已有与当前正文绑定的显式决策。
         *
         * @return 没有未决问题时为 {@code true}
         */
        public boolean decisionsComplete() {
            return openQuestions.stream()
                    .allMatch(question -> question.decision().isPresent());
        }
    }

    /**
     * 提交模型生成 Plan 候选的请求。
     *
     * <p>服务端会删除编辑器行键、重算问题摘要，并把候选规范化后保存为 Proposal；本请求本身不进入持久化。
     *
     * @param id Proposal 标识
     * @param baseDefinitionRevision 要更新的精确 Definition revision；创建新 Definition 时为空
     * @param sourceItemId 生成 Proposal 的可选模型 Item 标识
     * @param candidate 未受信任的候选输入
     */
    public record ProposeRequest(
            String id,
            Optional<Long> baseDefinitionRevision,
            Optional<String> sourceItemId,
            ManagementSaveRequest candidate) {
        /** 校验提案身份与目标 revision。 */
        public ProposeRequest {
            id = ContractValidation.text(id, "id");
            baseDefinitionRevision = Objects.requireNonNull(baseDefinitionRevision, "baseDefinitionRevision");
            baseDefinitionRevision.ifPresent(value -> ContractValidation.revision(value));
            sourceItemId = optionalText(sourceItemId, "sourceItemId");
            Objects.requireNonNull(candidate, "candidate");
        }
    }

    /**
     * 等待人工审阅的 Plan Proposal。
     *
     * @param id Proposal 标识
     * @param revision Proposal 乐观锁版本
     * @param candidate 已规范化候选内容
     * @param baseDefinitionRevision 采纳时必须仍匹配的目标 Definition revision
     * @param sourceItemId 可选模型 Item 来源
     * @param contentHash 规范化候选 JSON 的 SHA-256
     * @param state 人工决策状态
     * @param adoptedDefinitionRevision 采纳后生成的 Definition revision
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record Proposal(
            String id,
            long revision,
            Candidate candidate,
            Optional<Long> baseDefinitionRevision,
            Optional<String> sourceItemId,
            String contentHash,
            ProposalState state,
            Optional<Long> adoptedDefinitionRevision,
            Instant createdAt,
            Instant updatedAt)
            implements VersionedExtensionDocument {
        /** 校验 Proposal 状态与采纳结果的一致性。 */
        public Proposal {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            Objects.requireNonNull(candidate, "candidate");
            baseDefinitionRevision = Objects.requireNonNull(baseDefinitionRevision, "baseDefinitionRevision");
            baseDefinitionRevision.ifPresent(value -> ContractValidation.revision(value));
            sourceItemId = optionalText(sourceItemId, "sourceItemId");
            contentHash = ContractDigests.requireSha256(contentHash, "contentHash");
            Objects.requireNonNull(state, "state");
            adoptedDefinitionRevision = Objects.requireNonNull(adoptedDefinitionRevision, "adoptedDefinitionRevision");
            adoptedDefinitionRevision.ifPresent(value -> ContractValidation.revision(value));
            createdAt = ContractValidation.instant(createdAt, "createdAt");
            updatedAt = ContractValidation.instant(updatedAt, "updatedAt");
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("updatedAt must not be before createdAt");
            }
            if ((state == ProposalState.ADOPTED) != adoptedDefinitionRevision.isPresent()) {
                throw new IllegalArgumentException("adopted Proposal must reference a Definition revision");
            }
        }
    }

    /**
     * 对精确 Proposal 版本作人工决定。
     *
     * @param id Proposal 标识
     */
    public record ProposalDecision(String id) {
        /** 校验 Proposal 标识。 */
        public ProposalDecision {
            id = ContractValidation.text(id, "id");
        }
    }

    /**
     * 用户可编辑的计划定义。
     *
     * @param id 定义标识
     * @param revision 乐观锁版本
     * @param title 标题
     * @param objective 验收目标
     * @param scope 明确范围
     * @param risks 已识别风险
     * @param openQuestions 开放问题及决策
     * @param steps 有依赖关系的步骤
     * @param updatedAt 更新时间
     */
    public record Definition(
            String id,
            long revision,
            String title,
            String objective,
            String scope,
            List<String> risks,
            List<OpenQuestion> openQuestions,
            List<Step> steps,
            Instant updatedAt)
            implements VersionedExtensionDocument {
        /** 校验定义和步骤依赖图。 */
        public Definition {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            title = ContractValidation.text(title, "title");
            objective = ContractValidation.text(objective, "objective");
            scope = ContractValidation.text(scope, "scope");
            risks = ContractValidation.textList(risks, "risks");
            openQuestions = List.copyOf(openQuestions);
            steps = List.copyOf(steps);
            PlanDefinitionGraph.validate(openQuestions, steps);
            updatedAt = ContractValidation.instant(updatedAt, "updatedAt");
        }

        /**
         * 判断全部开放问题是否已有与当前正文绑定的决策。
         *
         * @return 没有未决问题时为 {@code true}
         */
        public boolean decisionsComplete() {
            return openQuestions.stream()
                    .allMatch(question -> question.decision().isPresent());
        }
    }

    private static <T> List<T> managementRows(List<T> rows, String name, boolean required) {
        List<T> copy = List.copyOf(Objects.requireNonNull(rows, name));
        if (copy.size() > MAX_MANAGEMENT_ROWS || required && copy.isEmpty()) {
            throw new IllegalArgumentException(name + " row count is invalid");
        }
        return copy;
    }

    private static void requireUniqueItemKeys(List<String> keys, String name) {
        if (new HashSet<>(keys).size() != keys.size()) {
            throw new IllegalArgumentException(name + " contains duplicate itemKey");
        }
    }

    private static Optional<String> optionalText(Optional<String> value, String name) {
        return Objects.requireNonNull(value, name)
                .filter(entry -> !entry.isBlank())
                .map(entry -> ContractValidation.text(entry, name));
    }
}
