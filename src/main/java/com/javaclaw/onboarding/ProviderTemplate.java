package com.javaclaw.onboarding;

import java.util.List;

/**
 * Provider 推荐模板 — 用于首次使用向导的 Step1
 *
 * <p>为每个模型提供商预置推荐的 baseUrl、默认模型、是否本地、说明等，
 * 用户选中后进入 Step2 时自动回填表单。</p>
 */
public record ProviderTemplate(
        String id,            // AgentConfig 中 provider.type 的值
        String displayName,   // 卡片显示名
        String description,   // 简短描述
        String baseUrl,
        String defaultModel,
        boolean local,        // 本地部署（无需 API Key）
        boolean recommended   // 是否推荐
) {

    public static final ProviderTemplate OPENAI = new ProviderTemplate(
            "OpenAI", "OpenAI / 兼容 API",
            "GPT、DeepSeek、GLM、LMStudio 等 OpenAI 兼容接口",
            "https://api.openai.com/v1", "gpt-4o-mini", false, false);

    public static final ProviderTemplate DASHSCOPE = new ProviderTemplate(
            "DashScope", "阿里通义千问",
            "阿里云 DashScope，中文场景推荐",
            "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", false, true);

    public static final ProviderTemplate ANTHROPIC = new ProviderTemplate(
            "Anthropic", "Anthropic Claude",
            "Claude 4 系列，推理与长上下文强",
            "https://api.anthropic.com", "claude-sonnet-4-6", false, false);

    public static final ProviderTemplate GEMINI = new ProviderTemplate(
            "Gemini", "Google Gemini",
            "Gemini 2.x 系列，多模态",
            "https://generativelanguage.googleapis.com", "gemini-2.0-flash", false, false);

    public static final ProviderTemplate OLLAMA = new ProviderTemplate(
            "Ollama", "Ollama（本地）",
            "本地模型，隐私敏感场景推荐，无需 API Key",
            "http://localhost:11434/v1", "qwen2.5:7b", true, true);

    public static final List<ProviderTemplate> ALL = List.of(
            DASHSCOPE, OPENAI, ANTHROPIC, GEMINI, OLLAMA);
}
