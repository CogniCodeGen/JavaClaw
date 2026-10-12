package com.javaclaw.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * 模型单价表（元 / 1K tokens）
 *
 * <p>用于从 token 用量估算人民币成本。价格来自公开渠道的参考价位，
 * 精确数字随时可能变动；真正的计费以各提供商实际账单为准。</p>
 *
 * <p>查表规则：先按完整模型名精确匹配，再按前缀匹配（如 "qwen-plus-1206" → "qwen-plus"），
 * 都未命中时返回 0，视为本地或免费模型。</p>
 */
public final class PricingTable {

    /** 单价记录：每 1K tokens 的人民币价格 */
    public record Price(double inputPer1k, double outputPer1k) {
        public static final Price FREE = new Price(0, 0);
    }

    /** 美元兑人民币汇率（粗略，用于将美元计价的模型折算为 CNY） */
    private static final double USD_TO_CNY = 7.2;

    private static final Map<String, Price> DEFAULTS = new HashMap<>();

    static {
        // === 阿里通义（DashScope） — 元/1K tokens ===
        DEFAULTS.put("qwen-plus", new Price(0.0008, 0.002));
        DEFAULTS.put("qwen-turbo", new Price(0.0003, 0.0006));
        DEFAULTS.put("qwen-max", new Price(0.02, 0.06));
        DEFAULTS.put("qwen-long", new Price(0.0005, 0.002));

        // === OpenAI — 美元/1K tokens × 汇率 ===
        DEFAULTS.put("gpt-4o-mini", new Price(0.00015 * USD_TO_CNY, 0.0006 * USD_TO_CNY));
        DEFAULTS.put("gpt-4o", new Price(0.0025 * USD_TO_CNY, 0.01 * USD_TO_CNY));
        DEFAULTS.put("gpt-4-turbo", new Price(0.01 * USD_TO_CNY, 0.03 * USD_TO_CNY));

        // === Anthropic Claude ===
        DEFAULTS.put("claude-opus-4", new Price(0.015 * USD_TO_CNY, 0.075 * USD_TO_CNY));
        DEFAULTS.put("claude-sonnet-4", new Price(0.003 * USD_TO_CNY, 0.015 * USD_TO_CNY));
        DEFAULTS.put("claude-haiku-4", new Price(0.0008 * USD_TO_CNY, 0.004 * USD_TO_CNY));

        // === Google Gemini ===
        DEFAULTS.put("gemini-2.0-flash", Price.FREE);
        DEFAULTS.put("gemini-1.5-pro", new Price(0.00125 * USD_TO_CNY, 0.005 * USD_TO_CNY));
        DEFAULTS.put("gemini-1.5-flash", new Price(0.000075 * USD_TO_CNY, 0.0003 * USD_TO_CNY));

        // === Ollama 本地模型（0 成本） ===
        DEFAULTS.put("qwen2.5", Price.FREE);
        DEFAULTS.put("llama3", Price.FREE);
        DEFAULTS.put("deepseek", Price.FREE);
    }

    private PricingTable() {}

    /**
     * 查询单价：先精确匹配，再前缀匹配，都未命中返回 {@link Price#FREE}。
     */
    public static Price lookup(String modelName) {
        if (modelName == null || modelName.isBlank()) return Price.FREE;
        String key = modelName.toLowerCase();

        Price exact = DEFAULTS.get(key);
        if (exact != null) return exact;

        // 前缀匹配（处理版本号/日期后缀）
        Price best = null;
        int bestLen = 0;
        for (var e : DEFAULTS.entrySet()) {
            if (key.startsWith(e.getKey()) && e.getKey().length() > bestLen) {
                best = e.getValue();
                bestLen = e.getKey().length();
            }
        }
        return best != null ? best : Price.FREE;
    }

    /**
     * 估算成本（CNY）
     *
     * @param modelName     模型名
     * @param inputTokens   输入 token 数
     * @param outputTokens  输出 token 数
     */
    public static double estimateCostCny(String modelName, long inputTokens, long outputTokens) {
        Price p = lookup(modelName);
        return (inputTokens * p.inputPer1k() + outputTokens * p.outputPer1k()) / 1000.0;
    }
}
