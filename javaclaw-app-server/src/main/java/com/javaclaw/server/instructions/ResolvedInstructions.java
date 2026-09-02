package com.javaclaw.server.instructions;

import java.util.Objects;

import com.javaclaw.api.InstructionResolution;

/**
 * Turn 内部使用的项目约定冻结结果。
 *
 * <p>管理 API 只能返回 {@link #resolution()}；{@link #promptContent()} 不得进入 Diagnostics、日志或管理 RPC。
 *
 * @param resolution 可公开的脱敏来源清单
 * @param promptContent 按层级合并的有效正文
 */
public record ResolvedInstructions(InstructionResolution resolution, String promptContent) {
    /** 校验清单与正文。 */
    public ResolvedInstructions {
        Objects.requireNonNull(resolution, "resolution");
        promptContent = Objects.requireNonNull(promptContent, "promptContent");
    }
}
