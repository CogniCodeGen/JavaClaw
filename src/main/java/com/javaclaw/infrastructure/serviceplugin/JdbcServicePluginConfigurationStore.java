package com.javaclaw.infrastructure.serviceplugin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.EndpointConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.Protocol;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.StartupPolicy;
import com.javaclaw.config.CredentialCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Desktop-only persistence adapter. Secrets are encrypted before endpoint JSON is stored. */
final class JdbcServicePluginConfigurationStore implements ServicePluginConfigurationStore {
    private static final String ENCRYPTED_PREFIX = "{credential}";
    private static final TypeReference<List<StoredEndpoint>> ENDPOINTS = new TypeReference<>() { };
    private static final TypeReference<List<Long>> INSTANTS = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> PLUGIN_CONFIG = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final CredentialCipher credentials;

    JdbcServicePluginConfigurationStore(JdbcTemplate jdbc,
                                        PlatformTransactionManager transactionManager,
                                        ObjectMapper json, CredentialCipher credentials) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.json = json;
        this.credentials = credentials;
    }

    @Override
    public Optional<SavedConfiguration> load(String pluginId) {
        return jdbc.query("""
                SELECT plugin_version, artifact_sha256, startup_policy, resources_json,
                       endpoints_json, plugin_config_json, quarantined, crash_history_json
                FROM service_plugin_config WHERE plugin_id = ?
                """, result -> {
            if (!result.next()) return Optional.empty();
            try {
                ResourceConfiguration resources = json.readValue(
                        result.getString("resources_json"), ResourceConfiguration.class);
                List<EndpointConfiguration> endpoints = json.readValue(
                                result.getString("endpoints_json"), ENDPOINTS).stream()
                        .map(this::decrypt).toList();
                List<Instant> crashes = json.readValue(
                                result.getString("crash_history_json"), INSTANTS).stream()
                        .map(Instant::ofEpochMilli).toList();
                Map<String, String> pluginConfig = decryptPluginConfiguration(json.readValue(
                        result.getString("plugin_config_json"), PLUGIN_CONFIG));
                return Optional.of(new SavedConfiguration(pluginId,
                        result.getString("plugin_version"), result.getString("artifact_sha256"),
                        StartupPolicy.valueOf(result.getString("startup_policy")), resources,
                        endpoints, pluginConfig, result.getBoolean("quarantined"), crashes));
            } catch (Exception failure) {
                throw new IllegalStateException("服务插件配置损坏: " + pluginId, failure);
            }
        }, pluginId);
    }

    @Override
    public void save(SavedConfiguration value) {
        try {
            String resources = json.writeValueAsString(value.resources());
            String endpoints = json.writeValueAsString(value.endpoints().stream()
                    .map(this::encrypt).toList());
            String pluginConfig = json.writeValueAsString(encryptPluginConfiguration(
                    value.pluginConfiguration(), value.sensitiveConfigurationKeys()));
            String crashes = json.writeValueAsString(value.crashes().stream()
                    .map(Instant::toEpochMilli).toList());
            transactions.executeWithoutResult(status -> jdbc.update("""
                    MERGE INTO service_plugin_config(
                        plugin_id, plugin_version, artifact_sha256, startup_policy,
                        resources_json, endpoints_json, plugin_config_json, quarantined,
                        crash_history_json, updated_at)
                    KEY(plugin_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, value.pluginId(), value.pluginVersion(), value.artifactSha256(),
                    value.startupPolicy().name(), resources, endpoints, pluginConfig,
                    value.quarantined(), crashes, System.currentTimeMillis()));
        } catch (Exception failure) {
            throw new IllegalStateException("无法持久化服务插件配置", failure);
        }
    }

    @Override
    public void delete(String pluginId) {
        transactions.executeWithoutResult(status ->
                jdbc.update("DELETE FROM service_plugin_config WHERE plugin_id = ?", pluginId));
    }

    private StoredEndpoint encrypt(EndpointConfiguration value) {
        return new StoredEndpoint(value.id(), value.protocol(), value.bindAddress(), value.port(),
                value.tlsEnabled(), value.allowInsecureLan(), value.keyStorePath() == null ? ""
                        : value.keyStorePath().toAbsolutePath().normalize().toString(),
                encryptText(value.keyStorePassword()), encryptText(value.apiKey()),
                value.requestsPerMinute(), value.tokensPerMinute(), value.maxConcurrent(),
                value.maxConnections(), value.maxRequestBytes(), value.requestTimeoutSeconds());
    }

    private EndpointConfiguration decrypt(StoredEndpoint value) {
        return new EndpointConfiguration(value.id(), value.protocol(), value.bindAddress(), value.port(),
                value.tlsEnabled(), value.allowInsecureLan(), value.keyStorePath().isBlank() ? null
                        : Path.of(value.keyStorePath()).toAbsolutePath().normalize(),
                decryptText(value.keyStorePasswordEnc()), decryptText(value.apiKeyEnc()),
                value.requestsPerMinute(), value.tokensPerMinute(), value.maxConcurrent(),
                value.maxConnections(), value.maxRequestBytes(), value.requestTimeoutSeconds());
    }

    private String encryptText(String value) {
        return value == null || value.isBlank() ? "" : credentials.encrypt(value);
    }

    private String decryptText(String value) {
        return value == null || value.isBlank() ? "" : credentials.decrypt(value);
    }

    private Map<String, String> encryptPluginConfiguration(
            Map<String, String> values, java.util.Set<String> sensitiveKeys) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key,
                sensitiveKeys.contains(key) && value != null && !value.isBlank()
                        ? ENCRYPTED_PREFIX + credentials.encrypt(value) : value));
        return Map.copyOf(result);
    }

    private Map<String, String> decryptPluginConfiguration(Map<String, String> values) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key,
                value != null && value.startsWith(ENCRYPTED_PREFIX)
                        ? credentials.decrypt(value.substring(ENCRYPTED_PREFIX.length())) : value));
        return Map.copyOf(result);
    }

    private record StoredEndpoint(
            String id, Protocol protocol, String bindAddress, int port, boolean tlsEnabled,
            boolean allowInsecureLan, String keyStorePath, String keyStorePasswordEnc,
            String apiKeyEnc, int requestsPerMinute, long tokensPerMinute,
            int maxConcurrent, int maxConnections, long maxRequestBytes,
            int requestTimeoutSeconds) { }
}
