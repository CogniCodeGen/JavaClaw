package com.javaclaw.builtin.contracts;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;

/** SDD 的可编辑 Definition、固定阶段与两次摘要审批契约。 */
public final class SddContracts {
    private static final int MAX_MANAGEMENT_TASKS = 100;

    private SddContracts() {}

    /** 服务端维护的固定阶段。 */
    public enum Phase {
        /** 已冻结 Proposal，准备规格审批。 */
        PROPOSAL,
        /** 等待 requirements 摘要审批。 */
        SPECIFICATION_APPROVAL,
        /** 执行 Design 工作单元。 */
        DESIGN,
        /** 等待 design/tasks 摘要审批。 */
        TASK_APPROVAL,
        /** 按冻结任务逐项实施。 */
        IMPLEMENT,
        /** 使用真实 Tool 证据验证。 */
        VERIFY,
        /** 验收未通过后的受限修复。 */
        REMEDIATE,
        /** 验收通过后的终态归档。 */
        ARCHIVE
    }

    /** 人工审批类别。 */
    public enum ApprovalKind {
        /** 规格审批。 */
        SPECIFICATION,
        /** 设计与任务审批。 */
        TASKS
    }

    /** 只允许来自 ToolResult 的验证类别。 */
    public enum VerificationKind {
        /** 工具输出的 {@code /exitCode} 等于期望值。 */
        TOOL_EXIT_CODE,
        /** 工具输出的 JSON Pointer 字段等于期望规范值。 */
        TOOL_FIELD_ASSERTION
    }

    /** 管理中心可安全编辑的字段断言标量类型；对象和数组不通过文本 JSON 暴露。 */
    public enum ManagementValueKind {
        /** 字符串。 */
        STRING,
        /** 十进制数值。 */
        NUMBER,
        /** 布尔值。 */
        BOOLEAN
    }

    /**
     * 管理中心中的单条实施任务。
     *
     * @param itemKey 编辑会话内的稳定行键，不进入持久化 Definition
     * @param instruction 实施任务正文
     */
    public record ManagementTask(String itemKey, String instruction) {
        /** 校验稳定行键和任务正文。 */
        public ManagementTask {
            itemKey = ContractValidation.text(itemKey, "itemKey");
            instruction = ContractValidation.text(instruction, "instruction");
        }
    }

    /**
     * 管理中心保存 SDD Definition 的强类型输入。
     *
     * <p>服务端负责生成 revision、更新时间以及规格和任务审批摘要。字段断言只接受明确的标量类型，不接受 JSON 文本。
     *
     * @param id Definition 标识；新建时由用户指定，编辑时由平台权威绑定
     * @param title 标题
     * @param requirements 需求与验收条件
     * @param design 设计说明
     * @param tasks 结构化实施任务行
     * @param verificationKind 真实工具验证类别
     * @param toolName 精确工具名
     * @param expectedExitCode 期望退出码
     * @param fieldPointer RFC 6901 JSON Pointer
     * @param expectedValueKind 期望标量类型
     * @param expectedValue 期望标量文本；服务端按类型严格转换
     * @param maximumRemediations 最大修复轮数
     */
    public record ManagementSaveRequest(
            String id,
            String title,
            String requirements,
            String design,
            List<ManagementTask> tasks,
            VerificationKind verificationKind,
            String toolName,
            Optional<Integer> expectedExitCode,
            Optional<String> fieldPointer,
            Optional<ManagementValueKind> expectedValueKind,
            Optional<String> expectedValue,
            int maximumRemediations) {
        /** 校验管理输入；验证规则的互斥关系由服务端构造 {@link VerificationRule} 时统一检查。 */
        public ManagementSaveRequest {
            id = ContractValidation.text(id, "id");
            title = ContractValidation.text(title, "title");
            requirements = ContractValidation.text(requirements, "requirements");
            design = ContractValidation.text(design, "design");
            tasks = managementTasks(tasks);
            Objects.requireNonNull(verificationKind, "verificationKind");
            toolName = ContractValidation.text(toolName, "toolName");
            expectedExitCode = Objects.requireNonNull(expectedExitCode, "expectedExitCode");
            fieldPointer = optionalText(fieldPointer, "fieldPointer");
            expectedValueKind = Objects.requireNonNull(expectedValueKind, "expectedValueKind");
            expectedValue = optionalText(expectedValue, "expectedValue");
            if (maximumRemediations < 0 || maximumRemediations > 10) {
                throw new IllegalArgumentException("maximumRemediations must be between 0 and 10");
            }
        }
    }

    /**
     * 规格正文。
     *
     * @param requirements 需求与验收条件
     * @param design 设计说明
     * @param tasks 实施任务
     */
    public record Content(String requirements, String design, List<String> tasks) {
        /** 校验规格正文。 */
        public Content {
            requirements = ContractValidation.text(requirements, "requirements");
            design = ContractValidation.text(design, "design");
            tasks = ContractValidation.textList(tasks, "tasks");
            if (tasks.isEmpty()) {
                throw new IllegalArgumentException("tasks must not be empty");
            }
        }
    }

    /**
     * SDD 的真实工具验证规则。
     *
     * @param kind 验证种类
     * @param toolName 精确工具名
     * @param expectedExitCode 退出码规则的期望值
     * @param fieldPointer 字段规则的 RFC 6901 JSON Pointer
     * @param expectedValue 字段规则的期望规范值
     */
    public record VerificationRule(
            VerificationKind kind,
            String toolName,
            Optional<Integer> expectedExitCode,
            Optional<String> fieldPointer,
            Optional<CanonicalPayload> expectedValue) {
        /** 校验验证规则的互斥字段。 */
        public VerificationRule {
            Objects.requireNonNull(kind, "kind");
            toolName = ContractValidation.text(toolName, "toolName");
            expectedExitCode = Objects.requireNonNull(expectedExitCode, "expectedExitCode");
            fieldPointer = Objects.requireNonNull(fieldPointer, "fieldPointer")
                    .map(value -> ContractValidation.text(value, "fieldPointer"));
            expectedValue = Objects.requireNonNull(expectedValue, "expectedValue");
            requireRuleFields(kind, expectedExitCode, fieldPointer, expectedValue);
        }
    }

    /**
     * 用户可编辑 SDD Definition。
     *
     * @param id 定义标识
     * @param revision 乐观锁版本
     * @param title 标题
     * @param content 需求、设计与任务
     * @param verificationRule 必须由真实 ToolResult 满足的验收规则
     * @param maximumRemediations 最大修复轮数
     * @param updatedAt 更新时间
     */
    public record Definition(
            String id,
            long revision,
            String title,
            Content content,
            VerificationRule verificationRule,
            int maximumRemediations,
            Instant updatedAt)
            implements VersionedExtensionDocument {
        /** 校验 Definition。 */
        public Definition {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            title = ContractValidation.text(title, "title");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(verificationRule, "verificationRule");
            if (maximumRemediations < 0 || maximumRemediations > 10) {
                throw new IllegalArgumentException("maximumRemediations must be between 0 and 10");
            }
            updatedAt = ContractValidation.instant(updatedAt, "updatedAt");
        }

        /**
         * 规格审批绑定摘要。
         *
         * @return requirements 的 SHA-256
         */
        public String specificationDigest() {
            return ContractDigests.sha256(content.requirements());
        }

        /**
         * 任务审批绑定摘要。
         *
         * @return design 与任务的 SHA-256
         */
        public String taskDigest() {
            StringBuilder framed = new StringBuilder();
            appendDigestPart(framed, "design", content.design());
            content.tasks().forEach(task -> appendDigestPart(framed, "task", task));
            return ContractDigests.sha256(framed.toString());
        }
    }

    /**
     * 内容摘要绑定的审批命令。
     *
     * @param jobId Execution Job
     * @param kind 审批类别
     * @param contentDigest 客户端确认的 SHA-256
     */
    public record Approval(String jobId, ApprovalKind kind, String contentDigest) {
        /** 校验审批身份与摘要格式。 */
        public Approval {
            jobId = ContractValidation.text(jobId, "jobId");
            Objects.requireNonNull(kind, "kind");
            contentDigest = ContractDigests.requireSha256(contentDigest, "contentDigest");
        }
    }

    /**
     * 服务端持久化的 SDD 恢复指针。
     *
     * @param phase 当前阶段
     * @param nextTaskIndex 下一项冻结任务下标
     * @param specificationApproval 已批准的规格摘要
     * @param taskApproval 已批准的任务摘要
     * @param remediationAttempts 已执行修复次数
     * @param verificationPassed 最近一次真实验证是否通过
     */
    public record Checkpoint(
            Phase phase,
            int nextTaskIndex,
            Optional<String> specificationApproval,
            Optional<String> taskApproval,
            int remediationAttempts,
            boolean verificationPassed) {
        /** 校验阶段计数与摘要。 */
        public Checkpoint {
            Objects.requireNonNull(phase, "phase");
            if (nextTaskIndex < 0 || remediationAttempts < 0) {
                throw new IllegalArgumentException("SDD checkpoint counters must not be negative");
            }
            specificationApproval = digest(specificationApproval, "specificationApproval");
            taskApproval = digest(taskApproval, "taskApproval");
        }
    }

    private static Optional<String> digest(Optional<String> value, String name) {
        return Objects.requireNonNull(value, name).map(entry -> ContractDigests.requireSha256(entry, name));
    }

    private static List<ManagementTask> managementTasks(List<ManagementTask> tasks) {
        List<ManagementTask> copy = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        if (copy.isEmpty() || copy.size() > MAX_MANAGEMENT_TASKS) {
            throw new IllegalArgumentException("tasks row count is invalid");
        }
        List<String> keys = copy.stream().map(ManagementTask::itemKey).toList();
        if (new HashSet<>(keys).size() != keys.size()) {
            throw new IllegalArgumentException("tasks contains duplicate itemKey");
        }
        return copy;
    }

    private static Optional<String> optionalText(Optional<String> value, String name) {
        return Objects.requireNonNull(value, name)
                .filter(entry -> !entry.isBlank())
                .map(entry -> ContractValidation.text(entry, name));
    }

    private static void appendDigestPart(StringBuilder target, String kind, String value) {
        target.append(kind).append(':').append(value.length()).append(':').append(value);
    }

    private static void requireRuleFields(
            VerificationKind kind,
            Optional<Integer> exitCode,
            Optional<String> pointer,
            Optional<CanonicalPayload> value) {
        boolean valid =
                switch (kind) {
                    case TOOL_EXIT_CODE -> exitCode.isPresent() && pointer.isEmpty() && value.isEmpty();
                    case TOOL_FIELD_ASSERTION ->
                        exitCode.isEmpty()
                                && pointer.filter(entry -> entry.startsWith("/"))
                                        .isPresent()
                                && value.isPresent();
                };
        if (!valid) {
            throw new IllegalArgumentException("SDD verification fields do not match kind");
        }
    }
}
