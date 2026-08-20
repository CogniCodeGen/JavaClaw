package com.javaclaw.application.inference;

import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.inference.api.InferenceRuntimeManifest;
import com.javaclaw.inference.api.InferenceUsage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 全局推理目录的持久化端口。实现必须保证单个方法的事务原子性。 */
public interface InferenceCatalogPort {

    List<RuntimeInstallation> runtimes();
    Optional<RuntimeInstallation> runtime(String runtimeId);
    void saveRuntime(RuntimeInstallation runtime);
    void setActiveRuntime(String engine, String runtimeId);
    Optional<String> previousActiveRuntime(String engine);
    void deleteRuntime(String runtimeId);

    List<InferenceModelAsset> assets();
    default List<ModelAssetSummary> assetSummaries() {
        return assets().stream().map(asset -> new ModelAssetSummary(
                asset.id(), asset.displayName(), asset.modelType(), asset.sizeBytes(),
                asset.state(), asset.failure()))
                .toList();
    }
    Optional<InferenceModelAsset> asset(UUID id);
    Optional<InferenceModelAsset> assetByHash(String sha256);
    void saveAsset(InferenceModelAsset asset);
    boolean assetReferenced(UUID id);
    void deleteAsset(UUID id);

    List<InferenceModelProfile> profiles();
    default List<ModelProfileSummary> profileSummaries(InferenceModelProfile.Kind kind) {
        return profiles().stream().filter(profile -> profile.kind() == kind)
                .map(profile -> new ModelProfileSummary(profile.id(), profile.assetId(),
                        profile.kind(), profile.state(), profile.failure(), profile.updatedAt()))
                .toList();
    }
    Optional<InferenceModelProfile> profile(UUID id);
    void saveProfile(InferenceModelProfile profile);
    boolean profileReferenced(UUID id);
    void deleteProfile(UUID id);

    Map<ModelTier, UUID> bindings(String workspaceId);
    void replaceBindings(String workspaceId, Map<ModelTier, UUID> bindings);
    void bind(String workspaceId, ModelTier tier, UUID profileId);
    void clearBinding(String workspaceId, ModelTier tier);

    List<PublishedModel> publishedModels();
    Optional<PublishedModel> publishedModel(String alias);
    void publish(PublishedModel model);
    void unpublish(String alias);

    GatewayConfiguration gatewayConfiguration();
    void saveGatewayConfiguration(GatewayConfiguration configuration);

    List<ApiKeyRecord> apiKeys();
    default boolean hasActiveApiKey(Set<ApiScope> requiredScopes, String alias) {
        Set<ApiScope> required = requiredScopes == null ? Set.of() : Set.copyOf(requiredScopes);
        return apiKeys().stream().anyMatch(key -> !key.revoked()
                && key.scopes().containsAll(required)
                && (alias == null || key.modelAliases().isEmpty()
                || key.modelAliases().contains(alias)));
    }
    Optional<ApiKeyRecord> apiKeyByPrefix(String prefix);
    void saveApiKey(ApiKeyRecord key);
    void revokeApiKey(UUID keyId);
    void recordApiUsage(UUID keyId, long minuteBucket, InferenceUsage usage, boolean failed);
    Optional<ApiUsage> apiUsage(UUID keyId, long minuteBucket);

    enum ModelTier { HIGH, NORMAL, LIGHT, EMBEDDING }
    enum RuntimeState { STAGED, INSTALLED, ACTIVE, FAILED }
    enum ApiScope { MODELS_READ, CHAT_INVOKE, EMBEDDINGS_INVOKE }

    record ModelAssetSummary(
            UUID id,
            String displayName,
            String modelType,
            long sizeBytes,
            InferenceModelAsset.State state,
            String failure) {
        public ModelAssetSummary {
            if (id == null) throw new IllegalArgumentException("资产 ID 不能为空");
            displayName = displayName == null || displayName.isBlank()
                    ? "本地模型" : displayName.strip();
            modelType = modelType == null || modelType.isBlank()
                    ? "unknown" : modelType.strip().toLowerCase(java.util.Locale.ROOT);
            if (!modelType.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
                throw new IllegalArgumentException("模型类型格式无效");
            }
            if (sizeBytes < 0) throw new IllegalArgumentException("资产大小不能为负数");
            if (state == null) throw new IllegalArgumentException("资产状态不能为空");
            failure = failure == null ? "" : failure.strip();
        }

        public ModelAssetSummary(
                UUID id, String displayName, long sizeBytes,
                InferenceModelAsset.State state, String failure) {
            this(id, displayName, "unknown", sizeBytes, state, failure);
        }
    }

    record ModelProfileSummary(
            UUID id,
            UUID assetId,
            InferenceModelProfile.Kind kind,
            InferenceModelProfile.State state,
            String failure,
            Instant updatedAt) {
        public ModelProfileSummary {
            if (id == null || assetId == null) {
                throw new IllegalArgumentException("档案及资产 ID 不能为空");
            }
            if (kind == null || state == null) {
                throw new IllegalArgumentException("档案类型及状态不能为空");
            }
            failure = failure == null ? "" : failure.strip();
            updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
        }
    }

    record RuntimeInstallation(
            InferenceRuntimeManifest manifest,
            String installPath,
            RuntimeState state,
            boolean active,
            Instant installedAt) {
        public RuntimeInstallation {
            if (manifest == null) throw new IllegalArgumentException("运行时清单不能为空");
            if (installPath == null || installPath.isBlank()) {
                throw new IllegalArgumentException("运行时安装路径不能为空");
            }
            if (state == null) throw new IllegalArgumentException("运行时状态不能为空");
            installedAt = installedAt == null ? Instant.now() : installedAt;
        }
    }

    record PublishedModel(String alias, UUID profileId, boolean enabled, Instant createdAt, Instant updatedAt) {
        public PublishedModel {
            alias = normalizeAlias(alias);
            if (profileId == null) throw new IllegalArgumentException("公开模型档案不能为空");
            createdAt = createdAt == null ? Instant.now() : createdAt;
            updatedAt = updatedAt == null ? createdAt : updatedAt;
        }

        private static String normalizeAlias(String value) {
            if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                throw new IllegalArgumentException("公开模型别名格式无效");
            }
            return value;
        }
    }

    record GatewayConfiguration(
            boolean enabled,
            String bindAddress,
            int port,
            boolean tlsEnabled,
            boolean allowInsecureLanWithoutTls,
            String keyStorePath,
            String encryptedKeyStorePassword,
            long maxRequestBytes,
            int requestTimeoutSeconds,
            boolean invocationLoggingEnabled,
            Instant updatedAt) {
        public GatewayConfiguration {
            bindAddress = bindAddress == null || bindAddress.isBlank() ? "127.0.0.1" : bindAddress.strip();
            if ("0.0.0.0".equals(bindAddress) || "::".equals(bindAddress)) {
                throw new IllegalArgumentException("API 服务禁止绑定通配地址，请选择具体网卡地址");
            }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("API 服务端口无效");
            keyStorePath = keyStorePath == null ? "" : keyStorePath.strip();
            encryptedKeyStorePassword = encryptedKeyStorePassword == null ? "" : encryptedKeyStorePassword;
            if (maxRequestBytes < 1024 || maxRequestBytes > 64L * 1024 * 1024) {
                throw new IllegalArgumentException("请求体上限必须在 1 KiB 到 64 MiB 之间");
            }
            if (requestTimeoutSeconds < 1 || requestTimeoutSeconds > 3600) {
                throw new IllegalArgumentException("请求超时必须在 1 到 3600 秒之间");
            }
            updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        }

        /** 兼容已有调用方；未显式选择时绝不允许未加密局域网监听。 */
        public GatewayConfiguration(
                boolean enabled,
                String bindAddress,
                int port,
                boolean tlsEnabled,
                boolean allowInsecureLanWithoutTls,
                String keyStorePath,
                String encryptedKeyStorePassword,
                long maxRequestBytes,
                int requestTimeoutSeconds,
                Instant updatedAt) {
            this(enabled, bindAddress, port, tlsEnabled, allowInsecureLanWithoutTls,
                    keyStorePath, encryptedKeyStorePassword, maxRequestBytes,
                    requestTimeoutSeconds, false, updatedAt);
        }

        public GatewayConfiguration(
                boolean enabled,
                String bindAddress,
                int port,
                boolean tlsEnabled,
                String keyStorePath,
                String encryptedKeyStorePassword,
                long maxRequestBytes,
                int requestTimeoutSeconds,
                Instant updatedAt) {
            this(enabled, bindAddress, port, tlsEnabled, false, keyStorePath,
                    encryptedKeyStorePassword, maxRequestBytes, requestTimeoutSeconds,
                    false, updatedAt);
        }

        public static GatewayConfiguration defaults() {
            return new GatewayConfiguration(false, "127.0.0.1", 18080, false, false,
                    "", "", 4L * 1024 * 1024, 120, false, Instant.now());
        }

        public boolean loopbackOnly() {
            return "127.0.0.1".equals(bindAddress) || "::1".equals(bindAddress)
                    || "localhost".equalsIgnoreCase(bindAddress);
        }
    }

    record ApiKeyRecord(
            UUID id,
            String name,
            String prefix,
            String salt,
            String digest,
            Set<ApiScope> scopes,
            Set<String> modelAliases,
            int requestsPerMinute,
            long tokensPerMinute,
            int maxConcurrent,
            boolean revoked,
            Instant createdAt,
            Instant lastUsedAt) {
        public ApiKeyRecord {
            if (id == null) throw new IllegalArgumentException("密钥 ID 不能为空");
            name = name == null || name.isBlank() ? "未命名密钥" : name.strip();
            prefix = requireText(prefix, "密钥前缀");
            salt = requireText(salt, "密钥盐");
            digest = requireText(digest, "密钥摘要");
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
            modelAliases = modelAliases == null ? Set.of() : Set.copyOf(modelAliases);
            if (requestsPerMinute < 1 || requestsPerMinute > 100_000) {
                throw new IllegalArgumentException("RPM 限额无效");
            }
            if (tokensPerMinute < 1) throw new IllegalArgumentException("TPM 限额无效");
            if (maxConcurrent < 1 || maxConcurrent > 1024) {
                throw new IllegalArgumentException("并发限额无效");
            }
            createdAt = createdAt == null ? Instant.now() : createdAt;
        }

        public boolean permits(ApiScope scope, String alias) {
            return !revoked && scopes.contains(scope)
                    && (alias == null || modelAliases.isEmpty() || modelAliases.contains(alias));
        }

        private static String requireText(String value, String label) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
            return value;
        }
    }

    record ApiUsage(long requests, long promptTokens, long completionTokens, long failures) { }
}
