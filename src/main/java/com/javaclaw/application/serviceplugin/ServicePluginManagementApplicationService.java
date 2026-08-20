package com.javaclaw.application.serviceplugin;

import com.javaclaw.plugin.api.PluginDescriptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Root-context lifecycle and configuration boundary for isolated service plugins. */
public interface ServicePluginManagementApplicationService {
    List<ServicePluginInfo> list();
    void start(String pluginId);
    void stop(String pluginId);
    void restart(String pluginId);
    void setStartupPolicy(String pluginId, StartupPolicy policy);
    void updateResources(String pluginId, ResourceConfiguration resources);
    void updateEndpoint(String pluginId, EndpointConfiguration endpoint);
    default void updatePluginConfiguration(String pluginId, Map<String, String> values) {
        throw new UnsupportedOperationException("当前实现不支持服务插件自有配置");
    }
    default void updateResourcesAndRestart(String pluginId, ResourceConfiguration resources) {
        ServicePluginInfo current = requirePlugin(list(), pluginId);
        updateConfigurationAndRestart(pluginId, resources, current.endpoints());
    }
    default void updateEndpointsAndRestart(
            String pluginId, List<EndpointConfiguration> endpoints) {
        ServicePluginInfo current = requirePlugin(list(), pluginId);
        updateConfigurationAndRestart(pluginId, current.resources(), endpoints);
    }
    default void patchPluginConfigurationAndRestart(
            String pluginId, Map<String, String> patch) {
        ServicePluginInfo current = requirePlugin(list(), pluginId);
        Map<String, String> merged = new java.util.LinkedHashMap<>(current.configuration());
        if (patch != null) patch.forEach((key, value) -> {
            if (value == null) merged.remove(key); else merged.put(key, value);
        });
        updateConfigurationAndRestart(
                pluginId, current.resources(), current.endpoints(), merged);
    }
    default void updateConfiguration(
            String pluginId,
            ResourceConfiguration resources,
            List<EndpointConfiguration> endpoints) {
        updateResources(pluginId, resources);
        if (endpoints != null) endpoints.forEach(endpoint -> updateEndpoint(pluginId, endpoint));
    }
    default void updateConfigurationAndRestart(
            String pluginId,
            ResourceConfiguration resources,
            List<EndpointConfiguration> endpoints) {
        boolean running = list().stream().filter(plugin -> plugin.id().equals(pluginId))
                .findFirst().map(plugin -> plugin.state() == State.HEALTHY
                        || plugin.state() == State.DEGRADED).orElse(false);
        if (running) stop(pluginId);
        updateConfiguration(pluginId, resources, endpoints);
        if (running) start(pluginId);
    }
    default void updateConfigurationAndRestart(
            String pluginId,
            ResourceConfiguration resources,
            List<EndpointConfiguration> endpoints,
            Map<String, String> pluginConfiguration) {
        boolean running = list().stream().filter(plugin -> plugin.id().equals(pluginId))
                .findFirst().map(plugin -> plugin.state() == State.HEALTHY
                        || plugin.state() == State.DEGRADED).orElse(false);
        if (running) stop(pluginId);
        updateConfiguration(pluginId, resources, endpoints);
        updatePluginConfiguration(pluginId, pluginConfiguration);
        if (running) start(pluginId);
    }
    default ApiKeyRotation rotateEndpointApiKey(String pluginId, String endpointId) {
        throw new UnsupportedOperationException("当前实现不支持重建服务插件 API Key");
    }
    void unquarantine(String pluginId);

    private static ServicePluginInfo requirePlugin(
            List<ServicePluginInfo> plugins, String pluginId) {
        return plugins.stream().filter(plugin -> plugin.id().equals(pluginId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到服务插件: " + pluginId));
    }

    enum StartupPolicy { MANUAL, AUTO_START }
    enum State { INSTALLED, STOPPED, STARTING, HEALTHY, DEGRADED, STOPPING, FAILED, QUARANTINED }
    enum Protocol { HTTP, HTTPS, TCP, SSE, WEBSOCKET }

    /** One-time secret returned only by an explicit key rotation. */
    record ApiKeyRotation(String endpointId, String apiKey) {
        public ApiKeyRotation {
            if (endpointId == null || endpointId.isBlank()) {
                throw new IllegalArgumentException("端点 id 不能为空");
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("API Key 不能为空");
            }
        }
    }

    record ResourceConfiguration(
            int heapMiB,
            int nativeMemoryMiB,
            int computeThreads,
            int ioConcurrency,
            int fileDescriptors) {
        public ResourceConfiguration {
            heapMiB = Math.max(256, heapMiB);
            nativeMemoryMiB = Math.max(0, nativeMemoryMiB);
            computeThreads = Math.max(1, computeThreads);
            ioConcurrency = Math.max(1, ioConcurrency);
            fileDescriptors = Math.max(64, fileDescriptors);
        }

        public long reservedMemoryMiB() { return Math.addExact((long) heapMiB, nativeMemoryMiB); }
    }

    record EndpointConfiguration(
            String id,
            Protocol protocol,
            String bindAddress,
            int port,
            boolean tlsEnabled,
            boolean allowInsecureLan,
            Path keyStorePath,
            String keyStorePassword,
            String apiKey,
            int requestsPerMinute,
            long tokensPerMinute,
            int maxConcurrent,
            int maxConnections,
            long maxRequestBytes,
            int requestTimeoutSeconds) {
        public EndpointConfiguration {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("端点 id 不能为空");
            protocol = protocol == null ? Protocol.HTTP : protocol;
            bindAddress = bindAddress == null || bindAddress.isBlank() ? "127.0.0.1" : bindAddress.strip();
            if (port < 0 || port > 65_535) throw new IllegalArgumentException("端口无效");
            requestsPerMinute = Math.max(1, requestsPerMinute);
            tokensPerMinute = Math.max(1, tokensPerMinute);
            maxConcurrent = Math.max(1, maxConcurrent);
            maxConnections = Math.max(1, maxConnections);
            maxRequestBytes = Math.max(1, maxRequestBytes);
            requestTimeoutSeconds = requestTimeoutSeconds <= 0 ? 120
                    : Math.min(3_600, requestTimeoutSeconds);
        }

        /** Source-compatible default for descriptors created before endpoint timeouts. */
        public EndpointConfiguration(
                String id, Protocol protocol, String bindAddress, int port,
                boolean tlsEnabled, boolean allowInsecureLan, Path keyStorePath,
                String keyStorePassword, String apiKey, int requestsPerMinute,
                long tokensPerMinute, int maxConcurrent, int maxConnections,
                long maxRequestBytes) {
            this(id, protocol, bindAddress, port, tlsEnabled, allowInsecureLan,
                    keyStorePath, keyStorePassword, apiKey, requestsPerMinute,
                    tokensPerMinute, maxConcurrent, maxConnections, maxRequestBytes, 120);
        }

        /** Redacted copy safe for UI snapshots and logs. */
        public EndpointConfiguration redacted() {
            return new EndpointConfiguration(id, protocol, bindAddress, port, tlsEnabled,
                    allowInsecureLan, keyStorePath, "", apiKey == null || apiKey.isBlank() ? "" : "********",
                    requestsPerMinute, tokensPerMinute, maxConcurrent, maxConnections,
                    maxRequestBytes, requestTimeoutSeconds);
        }
    }

    record ServicePluginInfo(
            String id,
            String name,
            String version,
            String publisher,
            boolean signatureVerified,
            Path artifactPath,
            boolean builtIn,
            StartupPolicy startupPolicy,
            State state,
            long pid,
            Instant processStartedAt,
            ResourceConfiguration resources,
            List<EndpointConfiguration> endpoints,
            boolean endpointConfigurationManaged,
            Set<String> services,
            int activeRequests,
            int queuedRequests,
            long reservedMemoryMiB,
            int reservedComputeThreads,
            int restartCount,
            List<Instant> recentCrashes,
            String lastError,
            List<String> recentLogs,
            Map<String, Object> health,
            String description,
            String configurationSchema,
            Map<String, String> configuration,
            PluginDescriptor.ConfigurationUi configurationUi,
            PluginDescriptor.Inference inference,
            Map<String, Set<String>> endpointCapabilities) {
        public ServicePluginInfo {
            endpoints = endpoints == null ? List.of() : endpoints.stream()
                    .map(EndpointConfiguration::redacted).toList();
            services = services == null ? Set.of() : Set.copyOf(services);
            recentCrashes = recentCrashes == null ? List.of() : List.copyOf(recentCrashes);
            recentLogs = recentLogs == null ? List.of() : List.copyOf(recentLogs);
            health = health == null ? Map.of() : Map.copyOf(health);
            lastError = lastError == null ? "" : lastError;
            publisher = publisher == null ? "" : publisher;
            artifactPath = artifactPath == null ? null : artifactPath.toAbsolutePath().normalize();
            description = description == null ? "" : description;
            configurationSchema = configurationSchema == null ? "" : configurationSchema;
            configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
            endpointCapabilities = immutableNestedSetMap(endpointCapabilities);
        }

        /** Compatibility constructor for callers created before declarative plugin UI metadata. */
        public ServicePluginInfo(
                String id, String name, String version, String publisher,
                boolean signatureVerified, Path artifactPath, boolean builtIn,
                StartupPolicy startupPolicy, State state, long pid, Instant processStartedAt,
                ResourceConfiguration resources, List<EndpointConfiguration> endpoints,
                boolean endpointConfigurationManaged, Set<String> services,
                int activeRequests, int queuedRequests, long reservedMemoryMiB,
                int reservedComputeThreads, int restartCount, List<Instant> recentCrashes,
                String lastError, List<String> recentLogs, Map<String, Object> health,
                String description, String configurationSchema,
                Map<String, String> configuration) {
            this(id, name, version, publisher, signatureVerified, artifactPath, builtIn,
                    startupPolicy, state, pid, processStartedAt, resources, endpoints,
                    endpointConfigurationManaged, services, activeRequests, queuedRequests,
                    reservedMemoryMiB, reservedComputeThreads, restartCount, recentCrashes,
                    lastError, recentLogs, health, description, configurationSchema,
                    configuration, null, null, Map.of());
        }

        private static Map<String, Set<String>> immutableNestedSetMap(
                Map<String, Set<String>> values) {
            if (values == null || values.isEmpty()) return Map.of();
            Map<String, Set<String>> result = new java.util.LinkedHashMap<>();
            values.forEach((key, value) -> result.put(
                    key, value == null ? Set.of() : Set.copyOf(value)));
            return Map.copyOf(result);
        }
    }
}
