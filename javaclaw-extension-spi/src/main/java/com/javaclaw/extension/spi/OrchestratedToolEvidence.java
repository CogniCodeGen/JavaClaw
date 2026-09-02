package com.javaclaw.extension.spi;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;

/**
 * 平台从已提交 Core ToolCall/ToolResult Item 提取的真实验证证据。
 *
 * @param toolName 冻结目录中的工具名称
 * @param successful 工具是否成功
 * @param output 工具的规范输出
 */
public record OrchestratedToolEvidence(String toolName, boolean successful, CanonicalPayload output) {
    /** 校验证据。 */
    public OrchestratedToolEvidence {
        toolName = Objects.requireNonNull(toolName, "toolName").strip();
        if (toolName.isEmpty()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        Objects.requireNonNull(output, "output");
    }
}
