package com.javaclaw.api;

import java.util.Set;

/**
 * Provider 端点声明的稳定能力快照。
 *
 * @param roles 支持的模型角色
 * @param streaming 支持流式事件
 * @param toolCalls 支持结构化工具调用
 * @param structuredOutput 支持结构化输出
 * @param images 支持图片输入
 * @param reasoningSummary 支持 reasoning summary
 * @param opaqueState 支持 Provider opaque conversation state
 * @param nativeCompaction 支持 Provider 原生压缩
 */
public record ProviderCapabilities(
        Set<ProviderRole> roles,
        boolean streaming,
        boolean toolCalls,
        boolean structuredOutput,
        boolean images,
        boolean reasoningSummary,
        boolean opaqueState,
        boolean nativeCompaction) {
    /** 固定角色集合。 */
    public ProviderCapabilities {
        roles = Set.copyOf(roles);
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("provider roles must not be empty");
        }
    }
}
