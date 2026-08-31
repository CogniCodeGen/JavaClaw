package com.javaclaw.sdk.model;

/**
 * 副作用执行凭据；UNKNOWN 不能自动重发。
 *
 * @param key 执行幂等摘要
 * @param tool 工具名
 * @param state PENDING、CONFIRMED 或 UNKNOWN
 * @param summary 执行证据摘要
 * @param document 完整原始 JSON，保留未知扩展
 */
public record EffectReceiptItemContent(String key, String tool, String state, String summary, JsonDocument document)
        implements ItemContent {
    @Override
    public String kind() {
        return "effectReceipt";
    }
}
