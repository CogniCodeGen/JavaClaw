package com.javaclaw.application.inference;

import com.javaclaw.inference.api.InferenceModelAsset;

import java.util.Map;

/** Supplies a safe, deterministic initial profile from current host capacity. */
public interface InferenceSystemProfilePort {

    Recommendation recommend(InferenceModelMetadataPort.ModelMetadata model,
                             InferenceModelAsset.ArtifactMetadata artifact);

    InferenceSystemProfilePort CONSERVATIVE = (model, artifact) -> {
        int context = model == null || model.declaredContextLength() <= 0
                ? 0 : Math.min(8192, model.declaredContextLength());
        long bytes = artifact == null ? 0 : artifact.quantizedSizeBytes();
        return new Recommendation(context,
                Map.of("workerThreads", 1, "tensorBackend", "auto",
                        "workingMemoryType", "F32", "workingQuantType", "I8",
                        "maxBatchSize", 1, "pooling", "AUTO", "kvCacheMaxEntries", 10_000),
                Map.of("temperature", 0, "maxTokens", context > 0
                                ? Math.min(1024, context) : 1024,
                        "seed", 42, "enableThinking", true),
                new SystemCapacity(1, 0, 0, 0, 0, 0, 0, 1, false, false),
                new MemoryAssessment(bytes, bytes, false, false, "无法读取物理内存，已使用保守默认值"));
    };

    record SystemCapacity(
            int logicalProcessors,
            long totalPhysicalBytes,
            long availablePhysicalBytes,
            long recommendedReservedBytes,
            long recommendedHeapBytes,
            long recommendedNativeBytes,
            long safeModelBudgetBytes,
            int recommendedThreads,
            boolean physicalMemoryAvailable,
            boolean availableMemoryReliable) {
        public SystemCapacity {
            if (logicalProcessors < 1 || recommendedThreads < 1) {
                throw new IllegalArgumentException("系统 CPU 容量无效");
            }
            if (totalPhysicalBytes < 0 || availablePhysicalBytes < 0
                    || recommendedReservedBytes < 0 || recommendedHeapBytes < 0
                    || recommendedNativeBytes < 0 || safeModelBudgetBytes < 0) {
                throw new IllegalArgumentException("系统内存容量无效");
            }
            if (physicalMemoryAvailable && (totalPhysicalBytes == 0
                    || availablePhysicalBytes > totalPhysicalBytes)) {
                throw new IllegalArgumentException("系统可用内存超过物理内存");
            }
            if (safeModelBudgetBytes > recommendedNativeBytes) {
                throw new IllegalArgumentException("安全模型预算超过 Native 预算");
            }
            if (!physicalMemoryAvailable) availableMemoryReliable = false;
        }
    }

    record MemoryAssessment(
            long modelBytes,
            long estimatedRequiredBytes,
            boolean exceedsPhysicalMemory,
            boolean exceedsSafeBudget,
            String message) {
        public MemoryAssessment {
            if (modelBytes < 0 || estimatedRequiredBytes < 0) {
                throw new IllegalArgumentException("模型内存估算无效");
            }
            message = message == null ? "" : message.strip();
        }
    }

    record Recommendation(
            int contextLength,
            Map<String, Object> loadParameters,
            Map<String, Object> generationParameters,
            SystemCapacity capacity,
            MemoryAssessment memory) {
        public Recommendation {
            if (contextLength < 0) throw new IllegalArgumentException("推荐上下文不能为负数");
            loadParameters = loadParameters == null ? Map.of() : Map.copyOf(loadParameters);
            generationParameters = generationParameters == null
                    ? Map.of() : Map.copyOf(generationParameters);
            if (capacity == null || memory == null) {
                throw new IllegalArgumentException("推荐配置缺少容量信息");
            }
        }
    }
}
