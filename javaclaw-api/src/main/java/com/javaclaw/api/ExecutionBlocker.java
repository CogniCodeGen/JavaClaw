package com.javaclaw.api;

import java.util.Objects;

/**
 * 执行预览中的可修复阻塞项，不含凭据与内部异常。
 *
 * @param code 稳定阻塞原因，不可空
 * @param message 可直接显示的中文说明，不可空或空白
 */
public record ExecutionBlocker(Code code, String message) {
    /** 校验原因与说明。 */
    public ExecutionBlocker {
        Objects.requireNonNull(code, "code");
        message = Preconditions.boundedText(message, "message", 2000);
    }

    /** 客户端用于选择配置修复入口的稳定原因。 */
    public enum Code {
        /** Workspace 不存在或已归档。 */
        WORKSPACE_UNAVAILABLE,
        /** Thread 不存在、归属不符或执行根不可用。 */
        THREAD_UNAVAILABLE,
        /** 尚未选择模型。 */
        MODEL_REQUIRED,
        /** 精确模型引用不存在或不支持聊天。 */
        MODEL_UNAVAILABLE,
        /** Agent 的精确版本当前不可用。 */
        ROLE_UNAVAILABLE,
        /** 权限或其他执行配置当前无效。 */
        CONFIGURATION_INVALID,
        /** 凭据仍未通过本地 Vault 校验。 */
        CREDENTIAL_UNVERIFIED,
        /** 尚未配置凭据。 */
        CREDENTIAL_REQUIRED,
        /** 凭据或 Vault 当前不可用。 */
        CREDENTIAL_UNAVAILABLE,
        /** 连接已停用。 */
        DISABLED,
        /** 连接已归档。 */
        ARCHIVED,
        /** 模型不在精确版本的连接目录中。 */
        INVALID_CONFIGURATION
    }
}
