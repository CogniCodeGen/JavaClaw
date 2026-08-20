package com.javaclaw.infrastructure.inference;

import com.javaclaw.application.inference.InferenceModelMetadataPort;
import com.javaclaw.application.inference.InferenceSystemProfilePort;
import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.platform.system.SystemMemoryProbe;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Balanced desktop defaults. Existing persisted settings are never rewritten by this adapter. */
public final class JvmInferenceSystemProfileAdapter implements InferenceSystemProfilePort {
    static final long GIB = 1024L * 1024 * 1024;
    private static final long GLOBAL_HARD_LIMIT = 21L * GIB;
    private static final int DELIVERANCE_THREAD_LIMIT = 6;
    private final HardwareProbe hardware;

    public JvmInferenceSystemProfileAdapter() {
        this(JvmInferenceSystemProfileAdapter::probeSystem);
    }

    JvmInferenceSystemProfileAdapter(HardwareProbe hardware) {
        this.hardware = Objects.requireNonNull(hardware, "hardware");
    }

    @Override
    public Recommendation recommend(InferenceModelMetadataPort.ModelMetadata model,
                                    InferenceModelAsset.ArtifactMetadata artifact) {
        Objects.requireNonNull(model, "model");
        InferenceModelAsset.ArtifactMetadata checked = artifact == null
                ? InferenceModelAsset.ArtifactMetadata.unknown(0) : artifact;
        Hardware value = hardware.read();
        int processors = Math.max(1, value.logicalProcessors());
        int threads = Math.max(1, Math.min(Math.min(8, DELIVERANCE_THREAD_LIMIT),
                processors > 1 ? processors - 1 : 1));
        long total = Math.max(0, value.totalPhysicalBytes());
        long available = Math.max(0, Math.min(total, value.availablePhysicalBytes()));
        boolean memoryAvailable = total > 0;
        long reserved = memoryAvailable
                ? Math.max(GIB, Math.min(GLOBAL_HARD_LIMIT, fraction(total, 3, 5))) : 0;
        reserved = memoryAvailable ? Math.min(reserved, total) : 0;
        long heap = reserved == 0 ? 0
                : Math.min(8L * GIB, Math.max(GIB / 2, reserved * 35 / 100));
        heap = Math.min(heap, reserved);
        long nativeMemory = Math.max(0, reserved - heap);
        long headroom = memoryAvailable ? Math.min(total,
                Math.max(2L * GIB, total / 5)) : 0;
        long staticSafe = memoryAvailable
                ? Math.min(nativeMemory, Math.max(0, total - headroom)) : 0;
        long safe = value.availableMemoryReliable()
                ? Math.min(staticSafe, fraction(available, 4, 5)) : staticSafe;

        int declared = model.declaredContextLength();
        int context = declared <= 0 ? 0 : Math.min(8192, declared);
        String quantization = checked.quantizationType().toUpperCase(Locale.ROOT);
        if (!quantization.equals("Q4") && !quantization.equals("I8")) quantization = "I8";
        Map<String, Object> load = new LinkedHashMap<>();
        load.put("workerThreads", threads);
        load.put("tensorBackend", "auto");
        load.put("workingMemoryType", "F32");
        load.put("workingQuantType", quantization);
        load.put("maxBatchSize", 1);
        load.put("pooling", "AUTO");
        load.put("kvCacheMaxEntries", 10_000);
        int maxTokens = context > 0 ? Math.max(1, Math.min(1024, context)) : 1024;
        Map<String, Object> generation = Map.of(
                "temperature", 0,
                "maxTokens", maxTokens,
                "seed", 42,
                "enableThinking", true);

        long modelBytes = checked.quantizedSizeBytes();
        long estimate = modelBytes > Long.MAX_VALUE / 115
                ? Long.MAX_VALUE : modelBytes * 115 / 100;
        boolean exceedsPhysical = memoryAvailable && modelBytes > total;
        boolean exceedsSafe = memoryAvailable && estimate > safe;
        String message = exceedsPhysical
                ? "模型文件已超过本机物理内存，无法完整驻留"
                : exceedsSafe ? "模型预计超过本机均衡安全内存预算，加载可能引发内存压力" : "";
        return new Recommendation(context, load, generation,
                new SystemCapacity(processors, total, available, reserved, heap, nativeMemory,
                        safe, threads, memoryAvailable, value.availableMemoryReliable()),
                new MemoryAssessment(modelBytes, estimate, exceedsPhysical, exceedsSafe, message));
    }

    private static Hardware probeSystem() {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        SystemMemoryProbe.Snapshot memory = SystemMemoryProbe.read();
        return new Hardware(processors, memory.totalBytes(), memory.availableBytes(),
                memory.availableReliable());
    }

    private static long fraction(long value, long numerator, long denominator) {
        if (value <= 0) return 0;
        return value / denominator * numerator + value % denominator * numerator / denominator;
    }

    record Hardware(int logicalProcessors, long totalPhysicalBytes,
                    long availablePhysicalBytes, boolean availableMemoryReliable) { }
    @FunctionalInterface interface HardwareProbe { Hardware read(); }
}
