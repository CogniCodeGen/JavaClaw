package com.javaclaw.api;

import java.util.Set;

/**
 * Provider 端点声明的稳定能力快照。
 *
 * @param purposes 支持的模型用途
 * @param streaming 支持流式事件
 * @param toolCalls 支持结构化工具调用
 * @param structuredOutput 支持结构化输出
 * @param images 支持图片输入
 * @param reasoningSummary 支持 reasoning summary
 * @param opaqueState 支持 Provider opaque conversation state
 * @param nativeCompaction 支持 Provider 原生压缩
 */
public record ProviderCapabilities(
        Set<ProviderModelPurpose> purposes,
        boolean streaming,
        boolean toolCalls,
        boolean structuredOutput,
        boolean images,
        boolean reasoningSummary,
        boolean opaqueState,
        boolean nativeCompaction) {
    /** 固定用途集合；禁用的空模型连接壳允许没有用途。 */
    public ProviderCapabilities {
        purposes = Set.copyOf(purposes);
    }
}
