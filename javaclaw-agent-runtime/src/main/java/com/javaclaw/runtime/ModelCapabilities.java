package com.javaclaw.runtime;

/**
 * 模型端点的能力快照。
 *
 * @param streaming 支持增量事件
 * @param toolCalls 支持结构化工具调用
 * @param structuredOutput 支持结构化输出约束
 * @param images 支持图片输入
 * @param reasoningSummary 支持 reasoning summary
 * @param opaqueState 支持 Provider opaque conversation state
 * @param nativeCompaction 支持 Provider 原生压缩
 */
public record ModelCapabilities(
        boolean streaming,
        boolean toolCalls,
        boolean structuredOutput,
        boolean images,
        boolean reasoningSummary,
        boolean opaqueState,
        boolean nativeCompaction) {}
