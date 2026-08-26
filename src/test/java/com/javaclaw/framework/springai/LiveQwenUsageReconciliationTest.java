package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.api.ModelTokenUsage;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit, non-CI provider reconciliation; opt in with -Djavaclaw.live.qwen=true. */
@EnabledIfSystemProperty(named = "javaclaw.live.qwen", matches = "true")
class LiveQwenUsageReconciliationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void currentQwenProviderUsageReconcilesMainAndSummaryCallsWithoutMutatingProductionData()
            throws Exception {
        Path source = Path.of(System.getProperty("javaclaw.live.data.dir", "data"))
                .toAbsolutePath().normalize();
        Path clone = Files.createDirectories(temporaryDirectory.resolve("data-copy"));
        Files.copy(source.resolve(DataRoot.FORMAT_FILE), clone.resolve(DataRoot.FORMAT_FILE),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(source.resolve("javaclaw.mv.db"), clone.resolve("javaclaw.mv.db"),
                StandardCopyOption.REPLACE_EXISTING);

        try (var context = ApplicationContexts.createRoot(new DataRoot(clone))) {
            AgentConfig config = context.getBean(AgentConfig.class);
            assertTrue(config.getApiKey() != null && !config.getApiKey().isBlank(),
                    "current provider key is not configured");
            // Keep this opt-in reconciliation request small and deterministic.
            config.setThinkingEnabled(false);

            Prompt mainPrompt = new Prompt(List.of(
                    new SystemMessage("Return exactly the requested short text."),
                    new UserMessage("Return exactly: OK")));
            Prompt summaryPrompt = new Prompt(List.of(
                    new SystemMessage("Summarize the supplied conversation in one short sentence."),
                    new UserMessage("User requested a short confirmation. Assistant replied OK.")));
            long estimatedInput = Math.addExact(
                    PromptTokenEstimator.estimate(mainPrompt).totalInputTokens(),
                    PromptTokenEstimator.estimate(summaryPrompt).totalInputTokens());
            SpringAiModelRegistry registry = new SpringAiModelRegistry();
            try (SpringAiModelFactory factory = new SpringAiModelFactory(
                    config, ObservationRegistry.NOOP)) {
                factory.install("live-usage-reconciliation", registry);
                var mainResponse = registry.require("live-usage-reconciliation", ModelTier.HIGH)
                        .call(mainPrompt);
                var summaryResponse = registry.require("live-usage-reconciliation", ModelTier.LIGHT)
                        .call(summaryPrompt);
                assertNotNull(mainResponse);
                assertNotNull(mainResponse.getMetadata());
                assertNotNull(mainResponse.getMetadata().getUsage());
                assertNotNull(summaryResponse);
                assertNotNull(summaryResponse.getMetadata());
                assertNotNull(summaryResponse.getMetadata().getUsage());

                ObjectMapper json = context.getBean(ObjectMapper.class);
                ModelTokenUsage mainUsage = ModelTokenUsageExtractor.extract(
                        mainResponse.getMetadata().getUsage(), json);
                ModelTokenUsage summaryUsage = ModelTokenUsageExtractor.extract(
                        summaryResponse.getMetadata().getUsage(), json);
                ModelTokenUsage actual = mainUsage.plus(summaryUsage);
                assertTrue(actual.inputTokens() > 0);
                assertTrue(actual.outputTokens() > 0);
                assertEquals(1, mainUsage.modelCalls());
                assertEquals(1, summaryUsage.modelCalls());
                assertEquals(2, actual.modelCalls());
                assertEquals(actual.inputTokens() + actual.outputTokens(), actual.totalTokens());
                double estimateRatio = (double) estimatedInput / actual.inputTokens();
                assertTrue(estimateRatio >= 0.20 && estimateRatio <= 4.0,
                        () -> "prompt estimate/provider ratio=" + estimateRatio);

                String mainModel = mainResponse.getMetadata().getModel();
                String summaryModel = summaryResponse.getMetadata().getModel();
                System.out.printf(
                        "LIVE_QWEN_USAGE mainModel=%s summaryModel=%s "
                                + "estimatedInput=%d providerInput=%d "
                                + "cacheRead=%d cacheWrite=%d output=%d reasoning=%d calls=%d%n",
                        mainModel == null ? config.getModelName() : mainModel,
                        summaryModel == null ? config.getModelName() : summaryModel,
                        estimatedInput, actual.inputTokens(),
                        actual.cacheReadInputTokens(), actual.cacheWriteInputTokens(),
                        actual.outputTokens(), actual.reasoningTokens(), actual.modelCalls());
            }
        }
    }
}
