package com.javaclaw.core.api;

import java.util.Objects;

/**
 * Provider 原生对话载荷的不可变封装；Core 只校验边界，不理解或重写其中的 SDK Schema。
 *
 * @param provider 生成载荷的 Provider 标识
 * @param schemaVersion JavaClaw 对该不透明封装采用的 Schema 版本
 * @param payloadJson Provider canonical items 的 JSON；仅交还同一 Provider
 * @param consumedMessages 当前模型循环中已被载荷覆盖的动态消息数，不跨 Turn 持久化
 * @param compacted 本次响应是否产生了新的原生压缩检查点
 */
public record ProviderConversationState(
        String provider, int schemaVersion, String payloadJson, int consumedMessages, boolean compacted) {
    private static final int MAXIMUM_PAYLOAD_CHARACTERS = 16 * 1024 * 1024;

    /** 固定不透明载荷并限制最大尺寸；正文不得写入普通日志或协议 Item。 */
    public ProviderConversationState {
        provider = ThreadId.required(provider, "provider");
        payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
        if (schemaVersion < 1 || consumedMessages < 0 || payloadJson.length() > MAXIMUM_PAYLOAD_CHARACTERS) {
            throw new IllegalArgumentException("invalid provider conversation state");
        }
    }

    /** 返回可跨 Turn 持久化的状态；下一 Turn 的动态消息计数从零开始。 */
    public ProviderConversationState persistent() {
        return new ProviderConversationState(provider, schemaVersion, payloadJson, 0, false);
    }
}
