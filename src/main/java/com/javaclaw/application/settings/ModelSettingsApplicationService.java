package com.javaclaw.application.settings;

import java.util.Objects;

/**
 * 模型、分级模型与嵌入配置的应用入口。
 *
 * <p>实现不保存页面状态。保存操作同步且具备“先完整校验、后一次持久化”的失败语义；
 * 探测操作会阻塞调用线程，调用方必须选择托管 I/O 执行器，并通过中断取消。</p>
 */
public interface ModelSettingsApplicationService {

    Snapshot snapshot();

    SaveResult saveModel(ModelSettings settings);

    SaveResult resetModel();

    SaveResult saveTiers(TierSettings settings);

    SaveResult clearTiers();

    SaveResult saveEmbedding(EmbeddingSettings settings);

    ProbeResult probeModel(ModelSettings settings) throws Exception;

    ProbeResult probeEmbedding(EmbeddingSettings settings) throws Exception;

    record Snapshot(
            ModelSettings model,
            TierSettings tiers,
            EmbeddingSettings embedding,
            String storageDescription) {
        public Snapshot {
            model = Objects.requireNonNull(model, "model");
            tiers = Objects.requireNonNull(tiers, "tiers");
            embedding = Objects.requireNonNull(embedding, "embedding");
            storageDescription = normalize(storageDescription);
        }
    }

    record ModelSettings(
            String provider,
            String baseUrl,
            String modelName,
            String apiKey,
            boolean thinkingEnabled,
            int thinkingBudget,
            String httpVersion,
            int connectTimeoutSeconds,
            int readTimeoutSeconds,
            int writeTimeoutSeconds,
            int orchestratorMaxIterations,
            int webAgentMaxIterations,
            int emailAgentMaxIterations,
            int maxRepeatedToolCalls,
            double loopSimilarityThreshold,
            double evaluatorPassThreshold,
            int evaluatorMaxRetries) {
        public ModelSettings {
            provider = normalize(provider);
            baseUrl = normalize(baseUrl);
            modelName = normalize(modelName);
            apiKey = normalize(apiKey);
            httpVersion = normalize(httpVersion);
        }
    }

    record TierSettings(Tier normal, Tier light) {
        public TierSettings {
            normal = Objects.requireNonNull(normal, "normal");
            light = Objects.requireNonNull(light, "light");
        }
    }

    record Tier(
            boolean enabled,
            String provider,
            String baseUrl,
            String modelName,
            String apiKey,
            boolean thinkingEnabled) {
        public Tier {
            provider = normalize(provider);
            baseUrl = normalize(baseUrl);
            modelName = normalize(modelName);
            apiKey = normalize(apiKey);
        }
    }

    record EmbeddingSettings(
            boolean enabled,
            String provider,
            String baseUrl,
            String apiKey,
            String modelName,
            int dimensions,
            int retrieveLimit,
            double scoreThreshold) {
        public EmbeddingSettings {
            provider = normalize(provider);
            baseUrl = normalize(baseUrl);
            apiKey = normalize(apiKey);
            modelName = normalize(modelName);
        }
    }

    record SaveResult(Snapshot snapshot, String message, boolean runtimeRefreshRequired) {
        public SaveResult {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            message = normalize(message);
        }
    }

    record ProbeResult(boolean succeeded, String message) {
        public ProbeResult {
            message = normalize(message);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
