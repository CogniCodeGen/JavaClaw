package com.javaclaw.sdk.model;

/** Forward-compatible SDK Item union. Unknown kinds remain losslessly representable. */
public sealed interface ItemContent
        permits TextItemContent,
                UserMessageItemContent,
                StructuredItemContent,
                ErrorItemContent,
                ApprovalItemContent,
                UserInputItemContent,
                PromptDraftItemContent,
                PlanItemContent,
                ArtifactItemContent,
                EvaluationItemContent,
                CheckpointItemContent,
                EffectReceiptItemContent,
                CommandItemContent,
                FileChangeItemContent,
                McpItemContent,
                ImageItemContent,
                SubagentItemContent,
                UnknownItemContent {
    /** 返回稳定 Item 类型标记；未知种类仍可通过 UnknownItemContent 保留原文。 */
    String kind();

    /** 返回完整 JSON 文档，用于前向兼容；不要求 UI 依赖协议或 Jackson 类型。 */
    JsonDocument document();
}
