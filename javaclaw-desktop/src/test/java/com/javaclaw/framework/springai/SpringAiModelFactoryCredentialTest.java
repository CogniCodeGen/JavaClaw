package com.javaclaw.framework.springai;

import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiModelFactoryCredentialTest {

    private static final String UNREADABLE = "ENC(YmFk)";

    @TempDir
    Path temporaryDirectory;

    private AnnotationConfigApplicationContext context;
    private AgentConfig config;
    private SpringAiModelFactory factory;

    @BeforeEach
    void createConfig() {
        context = ApplicationContexts.createRoot(new DataRoot(temporaryDirectory.resolve("data")));
        config = context.getBean(AgentConfig.class);
        config.setProviderType("OpenAI");
        config.setBaseUrl("https://example.test/v1");
        config.setModelName("chat-model");
        config.setApiKey("high-key");
        config.setNormalProviderType("OpenAI");
        config.setNormalModelName("normal-model");
        config.setNormalApiKey("normal-key");
        config.setLightProviderType("OpenAI");
        config.setLightModelName("light-model");
        config.setLightApiKey("light-key");
    }

    @AfterEach
    void close() {
        if (factory != null) factory.close();
        if (context != null) context.close();
    }

    @ParameterizedTest
    @EnumSource(ModelTier.class)
    void unreadableTierKeyKeepsWorkspaceAvailableButFailsAtModelCall(ModelTier tier) {
        switch (tier) {
            case HIGH -> config.setApiKey(UNREADABLE);
            case NORMAL -> config.setNormalApiKey(UNREADABLE);
            case LIGHT -> config.setLightApiKey(UNREADABLE);
        }
        config.save();
        config.reload();
        String saved = switch (tier) {
            case HIGH -> config.getApiKey();
            case NORMAL -> config.getNormalApiKey();
            case LIGHT -> config.getLightApiKey();
        };
        assertEquals(UNREADABLE, saved);

        factory = createFactory();
        SpringAiModelRegistry registry = new SpringAiModelRegistry();
        factory.install("fixture-workspace", registry);
        for (ModelTier installed : ModelTier.values()) {
            assertTrue(registry.require("fixture-workspace", installed) != null);
        }
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> registry.require("fixture-workspace", tier).call(new Prompt("sample")));
        assertTrue(failure.getMessage().contains("API 密钥无法解密"));
        assertFalse(failure.getMessage().contains(UNREADABLE));

        IllegalStateException streamFailure = assertThrows(IllegalStateException.class,
                () -> registry.require("fixture-workspace", tier)
                        .stream(new Prompt("sample")).blockFirst());
        assertTrue(streamFailure.getMessage().contains("API 密钥无法解密"));
    }

    @Test
    void unreadableEmbeddingKeyReportsInitializationFailure() {
        config.setRagEnabled(true);
        config.setRagEmbeddingProvider("OpenAI");
        config.setRagEmbeddingModelName("embedding-model");
        config.setRagEmbeddingApiKey(UNREADABLE);
        factory = createFactory();

        var provider = factory.createEmbeddingProvider();

        assertTrue(provider.configured());
        assertTrue(provider.initializationError().contains("API 密钥无法解密"));
        assertFalse(provider.initializationError().contains(UNREADABLE));
        assertThrows(IllegalStateException.class,
                () -> provider.embed("sample", Duration.ofSeconds(1)));
    }

    private SpringAiModelFactory createFactory() {
        return new SpringAiModelFactory(config, ObservationRegistry.NOOP,
                null, null, null, null);
    }
}
