package com.javaclaw.core.api;

import java.util.Objects;

/**
 * 工具执行的双重输出：可审计 Item 与回传模型的文本。
 *
 * @param item 非空最终工具 Item
 * @param modelContent 非空模型可见结果文本，可为空字符串
 */
public record ToolExecutionResult(ThreadItem item, String modelContent) {
    /** 要求审计结果与模型结果同时存在，避免 transcript 与模型上下文脱节。 */
    public ToolExecutionResult {
        item = Objects.requireNonNull(item, "item");
        modelContent = Objects.requireNonNull(modelContent, "modelContent");
    }
}
