package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.ModelTokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelTokenUsageExtractorTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void readsSpringCacheFieldsAndProviderReasoningWithoutDoubleCounting() {
        DefaultUsage source = new DefaultUsage(1_000, 400, 1_400,
                Map.of("completion_tokens_details", Map.of("reasoning_tokens", 125)),
                320L, 80L);

        ModelTokenUsage usage = ModelTokenUsageExtractor.extract(source, json);

        assertEquals(1_000, usage.inputTokens());
        assertEquals(320, usage.cacheReadInputTokens());
        assertEquals(80, usage.cacheWriteInputTokens());
        assertEquals(400, usage.outputTokens());
        assertEquals(125, usage.reasoningTokens());
        assertEquals(1, usage.modelCalls());
        assertEquals(1_400, usage.totalTokens());
    }

    @Test
    void anthropicAddsCacheReadAndCreationForAccountingButKeepsRawPricingInput() {
        DefaultUsage source = new DefaultUsage(100, 40, 190,
                Map.of("cache_read_input_tokens", 30,
                        "cache_creation_input_tokens", 20),
                30L, 20L);

        ExtractedModelUsage extracted = ModelTokenUsageExtractor.extractDetailed(source, json);

        assertEquals(150, extracted.usage().inputTokens());
        assertEquals(30, extracted.usage().cacheReadInputTokens());
        assertEquals(20, extracted.usage().cacheWriteInputTokens());
        assertEquals(190, extracted.usage().totalTokens());
        assertEquals(100, extracted.pricingInputTokens());
    }

    @Test
    void anthropicCacheReadOnlyPayloadStillUsesAdditiveInputSemantics() {
        DefaultUsage source = new DefaultUsage(100, 10, 140,
                Map.of("cache_read_input_tokens", 30), 30L, null);

        ExtractedModelUsage extracted = ModelTokenUsageExtractor.extractDetailed(source, json);

        assertEquals(130, extracted.usage().inputTokens());
        assertEquals(100, extracted.pricingInputTokens());
    }

    @Test
    void fallsBackToNativeOpenAiCompatibleCacheDetails() {
        DefaultUsage source = new DefaultUsage(500, 100, 600,
                Map.of("prompt_tokens_details", Map.of("cached_tokens", 200)),
                null, null);

        ExtractedModelUsage extracted = ModelTokenUsageExtractor.extractDetailed(source, json);
        ModelTokenUsage usage = extracted.usage();

        assertEquals(200, usage.cacheReadInputTokens());
        assertEquals(0, usage.cacheWriteInputTokens());
        assertEquals(0, usage.reasoningTokens());
        assertEquals(500, usage.inputTokens());
        assertEquals(500, extracted.pricingInputTokens());
    }

    @Test
    void normalizedSubsetsCannotOverlapOrExceedTheirParentTotals() {
        ModelTokenUsage usage = new ModelTokenUsage(100, 80, 80, 50, 70, 1);

        assertEquals(80, usage.cacheReadInputTokens());
        assertEquals(20, usage.cacheWriteInputTokens());
        assertEquals(50, usage.reasoningTokens());
        assertEquals(150, usage.totalTokens());
    }

    @Test
    void missingUsageStillCountsTheCompletedProviderCall() {
        ModelTokenUsage usage = ModelTokenUsageExtractor.extract(null, json);

        assertEquals(0, usage.totalTokens());
        assertEquals(1, usage.modelCalls());
    }
}
