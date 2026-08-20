package com.javaclaw.application.inference;

import com.javaclaw.inference.api.InferenceModelAsset;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 本地推理基础页的一键导入、默认配置、预热与 API 发布入口。 */
public interface LocalInferenceQuickSetupApplicationService {

    String DEFAULT_ALIAS = "local-chat";

    QuickSnapshot quickSnapshot();

    InferenceModelAsset importLocalModel(
            Path source,
            Consumer<InferenceAssetPreparationPort.Progress> progress,
            BooleanSupplier cancelled) throws Exception;

    InferenceManagementApplicationService.ProfileDraft defaultDraft(UUID assetId);

    LoadResult loadAndPublish(
            LoadCommand command,
            Consumer<Progress> progress,
            BooleanSupplier cancelled) throws Exception;

    record LoadCommand(
            InferenceManagementApplicationService.ProfileDraft draft,
            boolean insecureLanConfirmed) {
        public LoadCommand {
            if (draft == null) throw new IllegalArgumentException("模型档案草稿不能为空");
        }
    }

    record QuickSnapshot(
            List<LocalModel> models,
            UUID publishedProfileId,
            String alias,
            String endpoint,
            boolean gatewayEnabled,
            boolean apiKeyConfigured,
            boolean insecureLanConfirmed,
            InferenceApiServerControlPort.State apiState,
            List<SupportedModelType> supportedModelTypes) {
        public QuickSnapshot {
            models = models == null ? List.of() : List.copyOf(models);
            alias = alias == null || alias.isBlank() ? DEFAULT_ALIAS : alias;
            endpoint = InferenceApiServerControlPort.normalizeOpenAiBaseUrl(endpoint);
            apiState = apiState == null
                    ? InferenceApiServerControlPort.State.DISABLED : apiState;
            supportedModelTypes = supportedModelTypes == null
                    ? List.of() : List.copyOf(supportedModelTypes);
        }

        public QuickSnapshot(
                List<LocalModel> models, UUID publishedProfileId, String alias, String endpoint,
                boolean gatewayEnabled, boolean apiKeyConfigured, boolean insecureLanConfirmed) {
            this(models, publishedProfileId, alias, endpoint, gatewayEnabled,
                    apiKeyConfigured, insecureLanConfirmed,
                    gatewayEnabled ? InferenceApiServerControlPort.State.CONFIGURED_STOPPED
                            : InferenceApiServerControlPort.State.DISABLED,
                    List.of());
        }

        public QuickSnapshot(
                List<LocalModel> models, UUID publishedProfileId, String alias, String endpoint,
                boolean gatewayEnabled, boolean apiKeyConfigured, boolean insecureLanConfirmed,
                List<SupportedModelType> supportedModelTypes) {
            this(models, publishedProfileId, alias, endpoint, gatewayEnabled,
                    apiKeyConfigured, insecureLanConfirmed,
                    gatewayEnabled ? InferenceApiServerControlPort.State.CONFIGURED_STOPPED
                            : InferenceApiServerControlPort.State.DISABLED,
                    supportedModelTypes);
        }
    }

    record SupportedModelType(String id, String displayName) {
        public SupportedModelType {
            if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
                throw new IllegalArgumentException("支持的模型类型 ID 无效");
            }
            displayName = displayName == null || displayName.isBlank() ? id : displayName.strip();
        }

        public static SupportedModelType fromId(String id) {
            return new SupportedModelType(id, switch (id) {
                case "llama" -> "Llama";
                case "qwen2" -> "Qwen 2 / 2.5";
                case "qwen3" -> "Qwen 3";
                case "qwen3_moe" -> "Qwen 3 MoE";
                case "gemma2" -> "Gemma 2";
                case "gemma3_text" -> "Gemma 3 Text";
                case "gemma4" -> "Gemma 4";
                case "mistral" -> "Mistral";
                case "mixtral" -> "Mixtral";
                case "gpt2" -> "GPT-2";
                case "granitemoehybrid" -> "Granite MoE Hybrid";
                case "bert" -> "BERT";
                default -> id;
            });
        }
    }

    record LocalModel(
            UUID assetId,
            String displayName,
            String modelType,
            long sizeBytes,
            InferenceModelAsset.State assetState,
            UUID profileId,
            Status status,
            String failure) {
        public LocalModel {
            if (assetId == null) throw new IllegalArgumentException("资产 ID 不能为空");
            displayName = displayName == null || displayName.isBlank() ? "本地模型" : displayName.strip();
            modelType = modelType == null || modelType.isBlank()
                    ? "unknown" : modelType.strip().toLowerCase(java.util.Locale.ROOT);
            if (!modelType.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
                throw new IllegalArgumentException("模型类型格式无效");
            }
            if (sizeBytes < 0) throw new IllegalArgumentException("模型大小不能为负数");
            if (assetState == null) throw new IllegalArgumentException("资产状态不能为空");
            if (status == null) throw new IllegalArgumentException("模型状态不能为空");
            failure = failure == null ? "" : failure.strip();
        }

        public LocalModel(
                UUID assetId, String displayName, long sizeBytes,
                InferenceModelAsset.State assetState, UUID profileId,
                Status status, String failure) {
            this(assetId, displayName, "unknown", sizeBytes, assetState, profileId, status, failure);
        }
    }

    enum Status { IMPORTED, READY, API_AVAILABLE, RUNNING, FAILED }

    record Progress(String phase, double fraction) {
        public Progress {
            phase = phase == null ? "" : phase.strip();
            fraction = Double.isFinite(fraction) ? Math.max(0, Math.min(1, fraction)) : -1;
        }
    }

    /** apiKey 只在本次操作新建密钥时返回一次。 */
    record LoadResult(
            UUID profileId,
            String alias,
            String endpoint,
            int actualContextLength,
            String selectedBackend,
            String apiKey) {
        public LoadResult {
            if (profileId == null) throw new IllegalArgumentException("档案 ID 不能为空");
            alias = alias == null ? DEFAULT_ALIAS : alias;
            endpoint = InferenceApiServerControlPort.normalizeOpenAiBaseUrl(endpoint);
            selectedBackend = selectedBackend == null ? "" : selectedBackend;
            apiKey = apiKey == null ? "" : apiKey;
        }

        public boolean apiKeyCreated() { return !apiKey.isBlank(); }
    }
}
