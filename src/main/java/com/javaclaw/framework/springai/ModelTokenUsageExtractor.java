package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.ModelTokenUsage;
import org.springframework.ai.chat.metadata.Usage;

import java.util.List;

/** Best-effort normalization of Spring AI and provider-native usage payloads. */
final class ModelTokenUsageExtractor {
    private ModelTokenUsageExtractor() { }

    static ModelTokenUsage extract(Usage usage, ObjectMapper json) {
        return extractDetailed(usage, json).usage();
    }

    static ExtractedModelUsage extractDetailed(Usage usage, ObjectMapper json) {
        // Reaching the extractor represents one completed provider response even when
        // that provider omitted all token details.
        if (usage == null) {
            return new ExtractedModelUsage(new ModelTokenUsage(0, 0, 0, 0, 0, 1), 0);
        }
        long providerInput = number(usage.getPromptTokens());
        long output = number(usage.getCompletionTokens());
        long cacheRead = number(usage.getCacheReadInputTokens());
        long cacheWrite = number(usage.getCacheWriteInputTokens());
        long reasoning = 0;
        boolean additiveCacheInput = false;
        Object nativeUsage = usage.getNativeUsage();
        if (nativeUsage != null) {
            try {
                JsonNode root = json.valueToTree(nativeUsage);
                if (root.isTextual()) {
                    String encoded = root.asText().strip();
                    if (encoded.startsWith("{") || encoded.startsWith("[")) {
                        root = json.readTree(encoded);
                    }
                }
                String nativeType = nativeUsage.getClass().getName().toLowerCase(
                        java.util.Locale.ROOT);
                additiveCacheInput = nativeType.contains("anthropic") || hasAny(root, List.of(
                        "/cache_creation_input_tokens", "/cacheCreationInputTokens",
                        "/usage/cache_creation_input_tokens", "/usage/cacheCreationInputTokens",
                        "/cache_read_input_tokens", "/cacheReadInputTokens",
                        "/usage/cache_read_input_tokens", "/usage/cacheReadInputTokens"));
                reasoning = firstLong(root, List.of(
                        "/completion_tokens_details/reasoning_tokens",
                        "/output_tokens_details/reasoning_tokens",
                        "/usage/completion_tokens_details/reasoning_tokens",
                        "/usage/output_tokens_details/reasoning_tokens",
                        "/usageMetadata/thoughtsTokenCount",
                        "/thoughts_token_count",
                        "/reasoning_tokens"));
                if (cacheRead == 0) cacheRead = firstLong(root, List.of(
                        "/prompt_tokens_details/cached_tokens",
                        "/input_tokens_details/cached_tokens",
                        "/usage/prompt_tokens_details/cached_tokens",
                        "/cache_read_input_tokens",
                        "/cacheReadInputTokens",
                        "/usage/cache_read_input_tokens",
                        "/usage/cacheReadInputTokens",
                        "/usageMetadata/cachedContentTokenCount"));
                if (cacheWrite == 0) cacheWrite = firstLong(root, List.of(
                        "/cache_creation_input_tokens", "/cacheCreationInputTokens",
                        "/usage/cache_creation_input_tokens", "/usage/cacheCreationInputTokens"));
            } catch (Exception ignored) {
                // Provider-native usage is intentionally best-effort.
            }
        }
        long totalInput = additiveCacheInput
                ? Math.addExact(providerInput, Math.addExact(cacheRead, cacheWrite))
                : providerInput;
        return new ExtractedModelUsage(
                new ModelTokenUsage(totalInput, cacheRead, cacheWrite, output, reasoning, 1),
                providerInput);
    }

    private static boolean hasAny(JsonNode root, List<String> pointers) {
        for (String pointer : pointers) {
            if (!root.at(pointer).isMissingNode()) return true;
        }
        return false;
    }

    private static long firstLong(JsonNode root, List<String> pointers) {
        for (String pointer : pointers) {
            JsonNode value = root.at(pointer);
            if (value.isNumber()) return Math.max(0, value.asLong());
        }
        return 0;
    }

    private static long number(Number value) {
        return value == null ? 0 : Math.max(0, value.longValue());
    }
}
