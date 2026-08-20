package com.javaclaw.application.inference;

import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.inference.api.InferenceRuntimeManifest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** 推理运行时安装与服务插件内模型生命周期端口。 */
public interface InferenceRuntimePort {

    void validateProfile(InferenceModelProfile profile);
    void start(InferenceModelProfile profile) throws Exception;
    void stop(java.util.UUID profileId);
    default Optional<RuntimeProfileStatus> status(UUID profileId) { return Optional.empty(); }
    ProfileProbeResult probeDraft(ProfileProbeCommand command, BooleanSupplier cancelled) throws Exception;
    List<String> recentLogs(java.util.UUID profileId, int maxLines);

    /** 默认从签名 manifest 读取；具体运行时可为旧版 manifest 提供受控兼容映射。 */
    default Set<String> supportedModelTypes(
            InferenceRuntimeManifest manifest, InferenceModelProfile.Kind kind) {
        return manifest == null ? Set.of() : manifest.supportedModelTypes(kind);
    }

    /** Host-side probe command. It deliberately contains no Deliverance implementation type. */
    record ProfileProbeCommand(
            UUID temporaryProfileId,
            String name,
            InferenceModelProfile.Kind kind,
            UUID assetId,
            String runtimeId,
            Map<String, Object> loadParameters,
            Map<String, Object> defaultParameters,
            int requestedContextLength) {
        public ProfileProbeCommand {
            if (temporaryProfileId == null) throw new IllegalArgumentException("临时探测 ID 不能为空");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("档案名称不能为空");
            if (kind == null) throw new IllegalArgumentException("模型类型不能为空");
            if (assetId == null) throw new IllegalArgumentException("资产 ID 不能为空");
            if (runtimeId == null || runtimeId.isBlank()) throw new IllegalArgumentException("运行时 ID 不能为空");
            loadParameters = loadParameters == null ? Map.of() : Map.copyOf(loadParameters);
            defaultParameters = defaultParameters == null ? Map.of() : Map.copyOf(defaultParameters);
            if (requestedContextLength < 0) throw new IllegalArgumentException("上下文限制不能为负数");
        }
    }

    record ProfileProbeResult(
            int actualContextLength,
            int actualEmbeddingDimensions,
            String selectedBackend,
            Set<String> capabilities) {
        public ProfileProbeResult {
            if (actualContextLength <= 0) throw new IllegalArgumentException("模型实际上下文无效");
            if (actualEmbeddingDimensions < 0) throw new IllegalArgumentException("模型实际嵌入维度无效");
            selectedBackend = selectedBackend == null ? "" : selectedBackend.strip();
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }
    }

    /** 已驻留进程的非敏感状态，不暴露临时端口或 bearer。 */
    record RuntimeProfileStatus(
            UUID profileId,
            boolean running,
            int actualContextLength,
            int actualEmbeddingDimensions,
            String selectedBackend,
            Set<String> capabilities) {
        public RuntimeProfileStatus {
            if (profileId == null) throw new IllegalArgumentException("档案 ID 不能为空");
            selectedBackend = selectedBackend == null ? "" : selectedBackend.strip();
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }
    }
}
