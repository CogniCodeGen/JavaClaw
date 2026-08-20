package com.javaclaw.infrastructure.serviceplugin;

import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.EndpointConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.StartupPolicy;
import com.javaclaw.plugin.api.PluginDescriptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fully verified launch description. It contains paths only in the Desktop process. */
public record ServicePluginDefinition(
        String id,
        String name,
        String version,
        String apiVersion,
        String mainClass,
        String publisher,
        boolean signatureVerified,
        String artifactSha256,
        Path pluginJar,
        Path dataDirectory,
        StartupPolicy startupPolicy,
        ResourceConfiguration resources,
        List<EndpointConfiguration> endpoints,
        boolean endpointConfigurationManaged,
        Set<String> permissions,
        Map<String, String> config,
        boolean builtIn,
        String description,
        String configurationSchema,
        PluginDescriptor.Inference inference,
        PluginDescriptor.ConfigurationUi configurationUi,
        Map<String, Set<String>> endpointCapabilities,
        boolean developmentUnsigned) {

    public ServicePluginDefinition {
        id = required(id, "插件 id");
        name = name == null || name.isBlank() ? id : name.strip();
        version = required(version, "插件版本");
        apiVersion = required(apiVersion, "服务插件 API 版本");
        mainClass = required(mainClass, "服务插件入口类");
        publisher = publisher == null ? "" : publisher.strip();
        artifactSha256 = required(artifactSha256, "插件哈希").toLowerCase();
        if (!artifactSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("插件哈希无效");
        pluginJar = pluginJar.toAbsolutePath().normalize();
        dataDirectory = dataDirectory.toAbsolutePath().normalize();
        startupPolicy = startupPolicy == null ? StartupPolicy.MANUAL : startupPolicy;
        resources = resources == null ? new ResourceConfiguration(256, 0, 1, 64, 64) : resources;
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        config = config == null ? Map.of() : Map.copyOf(config);
        description = description == null ? "" : description.strip();
        configurationSchema = configurationSchema == null ? "" : configurationSchema.strip();
        endpointCapabilities = immutableNestedSetMap(endpointCapabilities);
        if (signatureVerified && developmentUnsigned) {
            throw new IllegalArgumentException("服务插件不能同时标记为已签名和未签名开发工件");
        }
        if (developmentUnsigned) startupPolicy = StartupPolicy.MANUAL;
    }

    /** Backward-compatible constructor for host adapters that do not publish static UI metadata. */
    public ServicePluginDefinition(
            String id, String name, String version, String apiVersion, String mainClass,
            String publisher, boolean signatureVerified, String artifactSha256, Path pluginJar,
            Path dataDirectory, StartupPolicy startupPolicy,
            ResourceConfiguration resources, List<EndpointConfiguration> endpoints,
            boolean endpointConfigurationManaged, Set<String> permissions,
            Map<String, String> config, boolean builtIn) {
        this(id, name, version, apiVersion, mainClass, publisher, signatureVerified,
                artifactSha256, pluginJar, dataDirectory, startupPolicy,
                resources, endpoints, endpointConfigurationManaged, permissions, config, builtIn,
                "", "", null, null, Map.of(), false);
    }

    /** Compatibility constructor for verified definitions created before development mode existed. */
    public ServicePluginDefinition(
            String id, String name, String version, String apiVersion, String mainClass,
            String publisher, boolean signatureVerified, String artifactSha256, Path pluginJar,
            Path dataDirectory, StartupPolicy startupPolicy,
            ResourceConfiguration resources, List<EndpointConfiguration> endpoints,
            boolean endpointConfigurationManaged, Set<String> permissions,
            Map<String, String> config, boolean builtIn, String description,
            String configurationSchema, PluginDescriptor.Inference inference) {
        this(id, name, version, apiVersion, mainClass, publisher, signatureVerified,
                artifactSha256, pluginJar, dataDirectory, startupPolicy,
                resources, endpoints, endpointConfigurationManaged, permissions, config, builtIn,
                description, configurationSchema, inference, null, Map.of(), false);
    }

    /** Compatibility constructor for definitions created before declarative configuration UI. */
    public ServicePluginDefinition(
            String id, String name, String version, String apiVersion, String mainClass,
            String publisher, boolean signatureVerified, String artifactSha256, Path pluginJar,
            Path dataDirectory, StartupPolicy startupPolicy,
            ResourceConfiguration resources, List<EndpointConfiguration> endpoints,
            boolean endpointConfigurationManaged, Set<String> permissions,
            Map<String, String> config, boolean builtIn, String description,
            String configurationSchema, PluginDescriptor.Inference inference,
            boolean developmentUnsigned) {
        this(id, name, version, apiVersion, mainClass, publisher, signatureVerified,
                artifactSha256, pluginJar, dataDirectory, startupPolicy, resources, endpoints,
                endpointConfigurationManaged, permissions, config, builtIn, description,
                configurationSchema, inference, null, Map.of(), developmentUnsigned);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.strip();
    }

    private static Map<String, Set<String>> immutableNestedSetMap(
            Map<String, Set<String>> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, Set<String>> result = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, value == null ? Set.of() : Set.copyOf(value)));
        return Map.copyOf(result);
    }
}
