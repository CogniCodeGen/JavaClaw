package com.javaclaw.builtin.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;

/** Loop 扩展的 Definition、验证规则与人工确认契约。 */
public final class LoopContracts {
    private LoopContracts() {}

    /** 允许作为完成证据的来源；模型文本不在此枚举中。 */
    public enum VerificationKind {
        /** 每轮由用户显式确认。 */
        USER_CONFIRMATION,
        /** 匹配指定工具输出的退出码。 */
        TOOL_EXIT_CODE,
        /** 匹配指定工具输出的 JSON Pointer 字段。 */
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
     * Loop 的真实证据规则。
     *
     * @param kind 证据类别
     * @param toolName 工具证据来源；用户确认时为空
     * @param expectedExitCode 期望退出码；仅退出码规则存在
     * @param fieldPointer RFC 6901 JSON Pointer；仅字段断言存在
     * @param expectedValue 期望规范值；仅字段断言存在
     */
    public record VerificationRule(
            VerificationKind kind,
            Optional<String> toolName,
            Optional<Integer> expectedExitCode,
            Optional<String> fieldPointer,
            Optional<CanonicalPayload> expectedValue) {
        /** 校验互斥字段，拒绝模型自述型规则。 */
        public VerificationRule {
            Objects.requireNonNull(kind, "kind");
            toolName = text(toolName, "toolName");
            expectedExitCode = Objects.requireNonNull(expectedExitCode, "expectedExitCode");
            fieldPointer = text(fieldPointer, "fieldPointer");
            expectedValue = Objects.requireNonNull(expectedValue, "expectedValue");
            validateFields(kind, toolName, expectedExitCode, fieldPointer, expectedValue);
        }

        private static Optional<String> text(Optional<String> value, String name) {
            return Objects.requireNonNull(value, name).map(entry -> ContractValidation.text(entry, name));
        }
    }

    /**
     * 用户可编辑的有界 Loop Definition。
     *
     * @param id 定义标识
     * @param revision 乐观锁版本
     * @param name 名称
     * @param objective 目标
     * @param instruction 每轮指令
     * @param maximumIterations 最大迭代数
     * @param noProgressThreshold 连续相同输出停止阈值
     * @param verificationRule 完成证据规则
     * @param updatedAt 更新时间
     */
    public record Definition(
            String id,
            long revision,
            String name,
            String objective,
            String instruction,
            int maximumIterations,
            int noProgressThreshold,
            VerificationRule verificationRule,
            Instant updatedAt)
            implements VersionedExtensionDocument {
        /** 校验上限和验证规则。 */
        public Definition {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            name = ContractValidation.text(name, "name");
            objective = ContractValidation.text(objective, "objective");
            instruction = ContractValidation.text(instruction, "instruction");
            if (maximumIterations < 1 || maximumIterations > 100) {
                throw new IllegalArgumentException("maximumIterations must be between 1 and 100");
            }
            if (noProgressThreshold < 1 || noProgressThreshold > maximumIterations) {
                throw new IllegalArgumentException("noProgressThreshold must be within the iteration limit");
            }
            Objects.requireNonNull(verificationRule, "verificationRule");
            updatedAt = ContractValidation.instant(updatedAt, "updatedAt");
        }
    }

    /**
     * 管理中心保存 Loop Definition 的强类型输入。
     *
     * <p>服务端负责 revision 与时间；字段断言只接受用户明确填写的字符串规范值，管理界面不开放 JSON 文本。
     *
     * @param id 定义标识
     * @param name 名称
     * @param objective 目标
     * @param instruction 每轮指令
     * @param maximumIterations 最大迭代数
     * @param noProgressThreshold 无进展阈值
     * @param verificationKind 真实证据类别
     * @param toolName 工具证据来源
     * @param expectedExitCode 期望退出码
     * @param fieldPointer JSON Pointer 字段
     * @param expectedValueKind 期望标量类型
     * @param expectedValue 期望标量文本；服务端按 {@code expectedValueKind} 严格转换
     */
    public record ManagementSaveRequest(
            String id,
            String name,
            String objective,
            String instruction,
            int maximumIterations,
            int noProgressThreshold,
            VerificationKind verificationKind,
            Optional<String> toolName,
            Optional<Integer> expectedExitCode,
            Optional<String> fieldPointer,
            Optional<ManagementValueKind> expectedValueKind,
            Optional<String> expectedValue) {
        /** 校验管理输入，具体互斥关系由 {@link VerificationRule} 统一判定。 */
        public ManagementSaveRequest {
            id = ContractValidation.text(id, "id");
            name = ContractValidation.text(name, "name");
            objective = ContractValidation.text(objective, "objective");
            instruction = ContractValidation.text(instruction, "instruction");
            Objects.requireNonNull(verificationKind, "verificationKind");
            toolName = optionalText(toolName, "toolName");
            expectedExitCode = Objects.requireNonNull(expectedExitCode, "expectedExitCode");
            fieldPointer = optionalText(fieldPointer, "fieldPointer");
            expectedValueKind = Objects.requireNonNull(expectedValueKind, "expectedValueKind");
            expectedValue = optionalText(expectedValue, "expectedValue");
        }

        private static Optional<String> optionalText(Optional<String> value, String name) {
            return Objects.requireNonNull(value, name).map(entry -> ContractValidation.text(entry, name));
        }
    }

    /**
     * 对等待中的某轮提交人工确认。
     *
     * @param jobId Execution Job 标识
     * @param iteration 等待确认的轮次
     * @param confirmed 是否确认该轮证据
     */
    public record Confirmation(String jobId, int iteration, boolean confirmed) {
        /** 校验 Job 与轮次。 */
        public Confirmation {
            jobId = ContractValidation.text(jobId, "jobId");
            if (iteration < 1) {
                throw new IllegalArgumentException("iteration must be positive");
            }
        }
    }

    private static void validateFields(
            VerificationKind kind,
            Optional<String> tool,
            Optional<Integer> exitCode,
            Optional<String> pointer,
            Optional<CanonicalPayload> value) {
        boolean valid =
                switch (kind) {
                    case USER_CONFIRMATION -> isUserConfirmation(tool, exitCode, pointer, value);
                    case TOOL_EXIT_CODE -> isExitCodeRule(tool, exitCode, pointer, value);
                    case TOOL_FIELD_ASSERTION -> isFieldAssertionRule(tool, exitCode, pointer, value);
                };
        if (!valid) {
            throw new IllegalArgumentException("verification rule fields do not match its kind");
        }
    }

    private static boolean isUserConfirmation(
            Optional<String> tool,
            Optional<Integer> exitCode,
            Optional<String> pointer,
            Optional<CanonicalPayload> value) {
        return tool.isEmpty() && exitCode.isEmpty() && pointer.isEmpty() && value.isEmpty();
    }

    private static boolean isExitCodeRule(
            Optional<String> tool,
            Optional<Integer> exitCode,
            Optional<String> pointer,
            Optional<CanonicalPayload> value) {
        return tool.isPresent() && exitCode.isPresent() && pointer.isEmpty() && value.isEmpty();
    }

    private static boolean isFieldAssertionRule(
            Optional<String> tool,
            Optional<Integer> exitCode,
            Optional<String> pointer,
            Optional<CanonicalPayload> value) {
        return tool.isPresent()
                && exitCode.isEmpty()
                && pointer.filter(entry -> entry.startsWith("/")).isPresent()
                && value.isPresent();
    }
}
