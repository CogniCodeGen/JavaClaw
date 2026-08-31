package com.javaclaw.server.model;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.agent.conversation.ProfileRepository;
import com.javaclaw.agent.conversation.ProfileUseCases;
import com.javaclaw.core.api.ExecutionProfile;
import com.javaclaw.server.persistence.H2ProviderConfigStore;
import com.javaclaw.server.security.SecretStore;

/** Non-secret Provider configuration plus write-only credential protocol boundary. */
public final class ProviderService implements ProviderUseCases {
    private static final List<String> PROVIDERS = List.of("openai", "anthropic", "google");
    private static final Set<String> CONFIG_KEYS = Set.of("model", "embeddingModel", "baseUrl", "nativeCompaction");
    private static final Set<String> BUILTIN_PROFILE_IDS = Set.of(
            "profile_chat",
            "profile_plan",
            "profile_loop",
            "profile_workflow",
            "profile_sdd",
            "profile_schedule",
            "profile_subagent");
    private final H2ProviderConfigStore configs;
    private final SecretStore secrets;
    private final ReloadableCloudModelGateway models;
    private final ProfileUseCases profiles;

    /** 绑定非敏感配置仓库、SecretStore 和可重载模型网关；所有模型配置更新由此边界协调。 */
    public ProviderService(H2ProviderConfigStore configs, SecretStore secrets, ReloadableCloudModelGateway models) {
        this(configs, secrets, models, null);
    }

    /**
     * 绑定 Provider 与 Profile 权威边界；启动时修复旧版本遗留的内置 Profile 模型偏差，后续配置保存同步更新。
     *
     * <p>只同步仍属于同一 Provider 的内置 Profile，不覆盖用户显式创建或切换 Provider 的自定义 Profile。
     */
    public ProviderService(
            H2ProviderConfigStore configs,
            SecretStore secrets,
            ReloadableCloudModelGateway models,
            ProfileUseCases profiles) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.models = Objects.requireNonNull(models, "models");
        this.profiles = profiles;
        reload();
        synchronizePersistedProfileModels();
    }

    @Override
    public List<ProviderState> list() {
        Map<String, H2ProviderConfigStore.ProviderConfig> configured = new LinkedHashMap<>();
        configs.list().forEach(value -> configured.put(value.provider(), value));
        Map<String, com.javaclaw.server.model.CloudModelDescriptor> descriptors = new LinkedHashMap<>();
        models.descriptors().forEach(value -> descriptors.put(value.provider(), value));
        ArrayList<ProviderState> result = new ArrayList<>();
        for (String provider : PROVIDERS) {
            var config = configured.get(provider);
            var credential = secrets.metadata(namespace(provider), "apiKey").orElse(null);
            var descriptor = descriptors.get(provider);
            Map<String, String> values = config == null ? Map.of() : config.config();
            Instant updated = config == null ? null : config.updatedAt();
            if (credential != null && (updated == null || credential.updatedAt().isAfter(updated))) {
                updated = credential.updatedAt();
            }
            result.add(new ProviderState(
                    provider,
                    credential != null || descriptor != null && descriptor.configured(),
                    credential == null ? 0 : credential.revision(),
                    config == null ? 0 : config.revision(),
                    values.getOrDefault("model", descriptor == null ? "" : descriptor.defaultModel()),
                    values.getOrDefault("embeddingModel", ""),
                    values.getOrDefault("baseUrl", ""),
                    updated));
        }
        return List.copyOf(result);
    }

    @Override
    public ProviderState configure(
            String provider, Map<String, String> configuration, long expectedRevision, String idempotencyKey) {
        String id = provider(provider);
        Map<String, String> validated = validateConfig(configuration);
        H2ProviderConfigStore.ProviderConfig saved = configs.put(id, validated, expectedRevision, idempotencyKey);
        reload();
        synchronizeProfileModels(saved);
        return list().stream()
                .filter(value -> value.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @Override
    public SecretStore.SecretMetadata setCredential(String provider, char[] value, String idempotencyKey) {
        String id = provider(provider);
        SecretStore.SecretMetadata metadata = secrets.put(namespace(id), "apiKey", value, idempotencyKey);
        reload();
        return metadata;
    }

    @Override
    public boolean clearCredential(String provider, long expectedRevision, String idempotencyKey) {
        String id = provider(provider);
        boolean removed = secrets.remove(namespace(id), "apiKey", expectedRevision, idempotencyKey);
        reload();
        return removed;
    }

    private void reload() {
        LinkedHashMap<String, Map<String, String>> values = new LinkedHashMap<>();
        configs.list().forEach(value -> values.put(value.provider(), value.config()));
        models.reload(values);
    }

    private void synchronizePersistedProfileModels() {
        if (profiles == null) {
            return;
        }
        configs.list().forEach(this::synchronizeProfileModels);
    }

    private void synchronizeProfileModels(H2ProviderConfigStore.ProviderConfig configuration) {
        if (profiles == null) {
            return;
        }
        String model = configuration.config().get("model");
        if (model == null || model.isBlank()) {
            return;
        }
        profiles.list().stream()
                .filter(profile -> BUILTIN_PROFILE_IDS.contains(profile.id()))
                .filter(profile -> configuration.provider().equalsIgnoreCase(profile.provider()))
                .filter(profile -> !model.equals(profile.model()))
                .forEach(profile -> profiles.put(
                        withModel(profile, model),
                        profile.revision(),
                        "provider-profile-default-"
                                + configuration.provider()
                                + "-"
                                + configuration.revision()
                                + "-"
                                + profile.id()
                                + "-"
                                + profile.revision()));
    }

    private static ProfileRepository.ProfileDraft withModel(ExecutionProfile profile, String model) {
        return new ProfileRepository.ProfileDraft(
                profile.id(),
                profile.name(),
                profile.kind(),
                profile.provider(),
                model,
                profile.systemPrompt(),
                profile.enabledTools(),
                profile.requestedSandboxMode(),
                profile.maxIterations(),
                profile.maxModelCalls(),
                profile.attributes());
    }

    private static Map<String, String> validateConfig(Map<String, String> configuration) {
        Objects.requireNonNull(configuration, "configuration");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        configuration.forEach((key, value) -> {
            if (!CONFIG_KEYS.contains(key)) {
                throw new IllegalArgumentException("unsupported provider config key: " + key);
            }
            String normalized =
                    Objects.requireNonNull(value, "provider config value").strip();
            if (normalized.isBlank() || normalized.length() > 4_000) {
                throw new IllegalArgumentException("provider config value is invalid: " + key);
            }
            if ("baseUrl".equals(key)) {
                URI uri;
                try {
                    uri = URI.create(normalized);
                } catch (IllegalArgumentException failure) {
                    throw new IllegalArgumentException("baseUrl is invalid", failure);
                }
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null
                        || uri.getUserInfo() != null) {
                    throw new IllegalArgumentException("baseUrl must be HTTP(S) and cannot contain user information");
                }
            }
            if ("nativeCompaction".equals(key)
                    && !("true".equalsIgnoreCase(normalized) || "false".equalsIgnoreCase(normalized))) {
                throw new IllegalArgumentException("nativeCompaction must be true or false");
            }
            result.put(key, normalized);
        });
        return Map.copyOf(result);
    }

    private static String provider(String value) {
        String normalized = Objects.requireNonNull(value, "provider").strip().toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(normalized)) {
            throw new IllegalArgumentException("unsupported cloud provider: " + value);
        }
        return normalized;
    }

    private static String namespace(String provider) {
        return "provider:" + provider;
    }
}
