package com.javaclaw.infrastructure.knowledge;

import com.javaclaw.application.knowledge.KnowledgeApplicationService.Settings;
import com.javaclaw.application.knowledge.KnowledgeSettingsPort;
import com.javaclaw.config.AgentConfig;

import java.util.Objects;

/** Maps mutable legacy configuration to immutable knowledge settings snapshots. */
public final class AgentConfigKnowledgeSettingsAdapter implements KnowledgeSettingsPort {

    private final AgentConfig config;

    public AgentConfigKnowledgeSettingsAdapter(AgentConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public synchronized Settings load() {
        return new Settings(config.getRagEmbeddingProvider(), config.getRagEmbeddingBaseUrl(),
                config.getRagEmbeddingModelName(), config.getRagEmbeddingDimensions(),
                config.getRagRetrieveLimit(), config.getRagChunkSize(),
                config.getRagChunkOverlap());
    }

    @Override
    public synchronized Settings saveChunkSettings(int chunkSize, int chunkOverlap) {
        config.setRagChunkSize(chunkSize);
        config.setRagChunkOverlap(chunkOverlap);
        config.save();
        return load();
    }
}
