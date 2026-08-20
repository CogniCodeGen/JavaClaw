package com.javaclaw.infrastructure.serviceplugin;

import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.EndpointConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.StartupPolicy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

interface ServicePluginConfigurationStore {
    Optional<SavedConfiguration> load(String pluginId);
    void save(SavedConfiguration configuration);
    void delete(String pluginId);

    record SavedConfiguration(
            String pluginId,
            String pluginVersion,
            String artifactSha256,
            StartupPolicy startupPolicy,
            ResourceConfiguration resources,
            List<EndpointConfiguration> endpoints,
            Map<String, String> pluginConfiguration,
            java.util.Set<String> sensitiveConfigurationKeys,
            boolean quarantined,
            List<Instant> crashes) {
        public SavedConfiguration {
            endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
            pluginConfiguration = pluginConfiguration == null
                    ? Map.of() : Map.copyOf(pluginConfiguration);
            sensitiveConfigurationKeys = sensitiveConfigurationKeys == null
                    ? java.util.Set.of() : java.util.Set.copyOf(sensitiveConfigurationKeys);
            crashes = crashes == null ? List.of() : List.copyOf(crashes);
        }

        SavedConfiguration(
                String pluginId, String pluginVersion, String artifactSha256,
                StartupPolicy startupPolicy, ResourceConfiguration resources,
                List<EndpointConfiguration> endpoints, Map<String, String> pluginConfiguration,
                boolean quarantined, List<Instant> crashes) {
            this(pluginId, pluginVersion, artifactSha256, startupPolicy, resources, endpoints,
                    pluginConfiguration, java.util.Set.of(), quarantined, crashes);
        }
    }

    static ServicePluginConfigurationStore transientStore() {
        return new ServicePluginConfigurationStore() {
            @Override public Optional<SavedConfiguration> load(String pluginId) { return Optional.empty(); }
            @Override public void save(SavedConfiguration configuration) { }
            @Override public void delete(String pluginId) { }
        };
    }
}
