package com.javaclaw.infrastructure.serviceplugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.EndpointConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.StartupPolicy;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.State;
import com.javaclaw.framework.spi.JsonSchemaValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Restores, validates and persists the user-editable part of a static plugin definition. */
final class ServicePluginDefinitionSettings {
    private static final Logger log = LoggerFactory.getLogger(ServicePluginDefinitionSettings.class);

    private final ObjectMapper json;
    private final ServicePluginConfigurationStore store;
    private final JsonSchemaValidator schemas = new JsonSchemaValidator();

    ServicePluginDefinitionSettings(ObjectMapper json, ServicePluginConfigurationStore store) {
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.store = java.util.Objects.requireNonNull(store, "store");
    }

    ServicePluginDefinition restore(ServicePluginDefinition definition) {
        return store.load(definition.id()).map(saved -> copy(definition,
                saved.startupPolicy(), saved.resources(), definition.endpointConfigurationManaged()
                        ? mergeEndpoints(definition.endpoints(), saved.endpoints())
                        : definition.endpoints(), restorePluginConfiguration(
                                definition, saved.pluginConfiguration()))).orElseGet(() -> copy(
                definition, definition.startupPolicy(), definition.resources(),
                definition.endpoints(), validatePluginConfiguration(
                        definition, definition.config())));
    }

    Optional<ServicePluginConfigurationStore.SavedConfiguration> load(String pluginId) {
        return store.load(pluginId);
    }

    void delete(String pluginId) {
        store.delete(pluginId);
    }

    void persist(ServicePluginDefinition definition, State state, List<Instant> crashes) {
        store.save(new ServicePluginConfigurationStore.SavedConfiguration(
                definition.id(), definition.version(), definition.artifactSha256(),
                definition.startupPolicy(), definition.resources(), definition.endpoints(),
                definition.config(), sensitiveConfigurationKeys(definition),
                state == State.QUARANTINED, crashes));
    }

    Map<String, String> validatedConfiguration(
            ServicePluginDefinition definition, Map<String, String> values) {
        return validatePluginConfiguration(definition, values);
    }

    Map<String, String> submittedConfiguration(
            ServicePluginDefinition definition, Map<String, String> values) {
        Map<String, String> submitted = new LinkedHashMap<>(values == null ? Map.of() : values);
        preserveManagedValues(definition, submitted);
        preserveRedactedSecrets(definition, submitted);
        return validatePluginConfiguration(definition, submitted);
    }

    Map<String, String> patchedConfiguration(
            ServicePluginDefinition definition, Map<String, String> patch) {
        Map<String, String> merged = new LinkedHashMap<>(definition.config());
        if (patch != null) {
            for (Map.Entry<String, String> entry : patch.entrySet()) {
                if (protectedConfigurationKeys(definition).contains(entry.getKey())) {
                    String current = definition.config().get(entry.getKey());
                    if (!java.util.Objects.equals(current, entry.getValue())
                            && !"********".equals(entry.getValue())) {
                        throw new IllegalArgumentException(
                                "配置项由宿主保护，不能修改: " + entry.getKey());
                    }
                    continue;
                }
                if (entry.getValue() == null) merged.remove(entry.getKey());
                else merged.put(entry.getKey(), entry.getValue());
            }
        }
        preserveRedactedSecrets(definition, merged);
        return validatePluginConfiguration(definition, merged);
    }

    Map<String, String> redactedConfiguration(ServicePluginDefinition definition) {
        Map<String, String> result = new LinkedHashMap<>(definition.config());
        sensitiveConfigurationKeys(definition).forEach(key -> {
            if (result.containsKey(key)) result.put(key, "********");
        });
        return Map.copyOf(result);
    }

    Set<String> sensitiveConfigurationKeys(ServicePluginDefinition definition) {
        if (definition.configurationSchema().isBlank()) return Set.of();
        try {
            Set<String> result = new java.util.LinkedHashSet<>();
            json.readTree(definition.configurationSchema()).path("properties")
                    .fields().forEachRemaining(entry -> {
                        if (entry.getValue().path("x-javaclaw-secret").asBoolean(false)
                                || entry.getValue().path("writeOnly").asBoolean(false)) {
                            result.add(entry.getKey());
                        }
                    });
            return Set.copyOf(result);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("插件配置 Schema 无法解析", invalid);
        }
    }

    Set<String> hostManagedConfigurationKeys(ServicePluginDefinition definition) {
        return schemaFlaggedKeys(definition, "x-javaclaw-host-managed");
    }

    Set<String> protectedConfigurationKeys(ServicePluginDefinition definition) {
        Set<String> result = new java.util.LinkedHashSet<>(
                hostManagedConfigurationKeys(definition));
        result.addAll(schemaFlaggedKeys(definition, "x-javaclaw-hidden"));
        return Set.copyOf(result);
    }

    int endpointIndex(List<EndpointConfiguration> endpoints, String endpointId) {
        for (int index = 0; index < endpoints.size(); index++) {
            if (endpoints.get(index).id().equals(endpointId)) return index;
        }
        throw new IllegalArgumentException("服务插件未声明端点: " + endpointId);
    }

    EndpointConfiguration mergeEndpointSecrets(
            EndpointConfiguration current, EndpointConfiguration incoming) {
        if (current.protocol() != incoming.protocol()) {
            throw new IllegalArgumentException("服务插件端点协议由插件声明，不能修改");
        }
        String keyStorePassword = retainedSecret(
                incoming.keyStorePassword(), current.keyStorePassword());
        String apiKey = retainedSecret(incoming.apiKey(), current.apiKey());
        return new EndpointConfiguration(incoming.id(), incoming.protocol(), incoming.bindAddress(),
                incoming.port(), incoming.tlsEnabled(), incoming.allowInsecureLan(),
                incoming.keyStorePath(), keyStorePassword, apiKey,
                incoming.requestsPerMinute(), incoming.tokensPerMinute(), incoming.maxConcurrent(),
                incoming.maxConnections(), incoming.maxRequestBytes(),
                incoming.requestTimeoutSeconds());
    }

    ServicePluginDefinition copy(
            ServicePluginDefinition value, StartupPolicy policy,
            ResourceConfiguration resources, List<EndpointConfiguration> endpoints) {
        return copy(value, policy, resources, endpoints, value.config());
    }

    ServicePluginDefinition copy(
            ServicePluginDefinition value, StartupPolicy policy,
            ResourceConfiguration resources, List<EndpointConfiguration> endpoints,
            Map<String, String> configuration) {
        return new ServicePluginDefinition(value.id(), value.name(), value.version(), value.apiVersion(),
                value.mainClass(), value.publisher(), value.signatureVerified(), value.artifactSha256(),
                value.pluginJar(), value.dataDirectory(), policy,
                resources, endpoints, value.endpointConfigurationManaged(), value.permissions(),
                configuration, value.builtIn(), value.description(), value.configurationSchema(),
                value.inference(), value.configurationUi(), value.endpointCapabilities(),
                value.developmentUnsigned());
    }

    private Map<String, String> restorePluginConfiguration(
            ServicePluginDefinition definition, Map<String, String> saved) {
        Map<String, String> merged = new LinkedHashMap<>(definition.config());
        if (saved != null) merged.putAll(saved);
        try {
            var properties = json.readTree(definition.configurationSchema()).path("properties");
            properties.fields().forEachRemaining(entry -> {
                if (entry.getValue().path("x-javaclaw-host-managed").asBoolean(false)
                        && definition.config().containsKey(entry.getKey())) {
                    merged.put(entry.getKey(), definition.config().get(entry.getKey()));
                }
            });
            return validatePluginConfiguration(definition, merged);
        } catch (Exception invalid) {
            log.warn("服务插件[{}]的已保存配置与当前 Schema 不兼容，已恢复默认值: {}",
                    definition.id(), safeMessage(invalid));
            return validatePluginConfiguration(definition, definition.config());
        }
    }

    private void preserveManagedValues(
            ServicePluginDefinition definition, Map<String, String> submitted) {
        for (String key : protectedConfigurationKeys(definition)) {
            rejectManagedChange(definition, key, submitted.get(key));
            if (definition.config().containsKey(key)) submitted.put(key, definition.config().get(key));
            else submitted.remove(key);
        }
    }

    private void rejectManagedChange(
            ServicePluginDefinition definition, String key, String submittedValue) {
        if (!protectedConfigurationKeys(definition).contains(key)) return;
        String current = definition.config().get(key);
        if (submittedValue != null && !"********".equals(submittedValue)
                && !java.util.Objects.equals(current, submittedValue)) {
            throw new IllegalArgumentException("配置项由宿主保护，不能修改: " + key);
        }
    }

    private void preserveRedactedSecrets(
            ServicePluginDefinition definition, Map<String, String> submitted) {
        for (String key : sensitiveConfigurationKeys(definition)) {
            String value = submitted.get(key);
            if (value == null || value.isBlank() || "********".equals(value)) {
                if (definition.config().containsKey(key)) {
                    submitted.put(key, definition.config().get(key));
                }
            }
        }
    }

    private Set<String> schemaFlaggedKeys(ServicePluginDefinition definition, String flag) {
        if (definition.configurationSchema().isBlank()) return Set.of();
        try {
            Set<String> result = new java.util.LinkedHashSet<>();
            json.readTree(definition.configurationSchema()).path("properties")
                    .fields().forEachRemaining(entry -> {
                        if (entry.getValue().path(flag).asBoolean(false)) result.add(entry.getKey());
                    });
            return Set.copyOf(result);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("插件配置 Schema 无法解析", invalid);
        }
    }

    private Map<String, String> validatePluginConfiguration(
            ServicePluginDefinition definition, Map<String, String> values) {
        if (definition.configurationSchema().isBlank()) {
            if (!values.isEmpty()) throw new IllegalArgumentException("插件未声明自有配置 Schema");
            return Map.of();
        }
        try {
            var schema = json.readTree(definition.configurationSchema());
            schemas.requireValidSchema(schema,
                    "service plugin " + definition.id() + " configuration");
            var properties = schema.path("properties");
            for (String key : values.keySet()) {
                if (!properties.has(key)) throw new IllegalArgumentException("未知插件配置项: " + key);
            }
            ObjectNode typed = json.createObjectNode();
            properties.fields().forEachRemaining(entry -> putTypedValue(
                    typed, entry.getKey(), entry.getValue().path("type").asText("string"),
                    values.get(entry.getKey())));
            var issues = schemas.validate(schema, typed, "/configuration");
            if (!issues.isEmpty()) {
                throw new IllegalArgumentException(issues.getFirst().path() + ": "
                        + issues.getFirst().message());
            }
            return Map.copyOf(values);
        } catch (IOException invalidSchema) {
            throw new IllegalArgumentException("插件配置 Schema 无法解析", invalidSchema);
        }
    }

    private void putTypedValue(ObjectNode target, String key, String type, String raw) {
        if (raw == null) return;
        try {
            switch (type) {
                case "boolean" -> {
                    if (!"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw)) {
                        throw new IllegalArgumentException("必须是 true 或 false");
                    }
                    target.put(key, Boolean.parseBoolean(raw));
                }
                case "integer" -> target.put(key, Long.parseLong(raw));
                case "number" -> target.put(key, new java.math.BigDecimal(raw));
                case "object", "array" -> target.set(key, json.readTree(raw));
                default -> target.put(key, raw);
            }
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "插件配置项 " + key + " 无效: " + safeMessage(invalid), invalid);
        }
    }

    private static List<EndpointConfiguration> mergeEndpoints(
            List<EndpointConfiguration> declared, List<EndpointConfiguration> saved) {
        Map<String, EndpointConfiguration> configured = saved.stream().collect(
                java.util.stream.Collectors.toMap(EndpointConfiguration::id, value -> value,
                        (first, ignored) -> first, LinkedHashMap::new));
        return declared.stream().map(value -> mergeEndpoint(value, configured.get(value.id()))).toList();
    }

    private static EndpointConfiguration mergeEndpoint(
            EndpointConfiguration declared, EndpointConfiguration saved) {
        if (saved == null) return declared;
        return new EndpointConfiguration(declared.id(), declared.protocol(), saved.bindAddress(),
                saved.port(), saved.tlsEnabled(), saved.allowInsecureLan(), saved.keyStorePath(),
                saved.keyStorePassword(), saved.apiKey(), saved.requestsPerMinute(),
                saved.tokensPerMinute(), saved.maxConcurrent(), saved.maxConnections(),
                saved.maxRequestBytes(), saved.requestTimeoutSeconds());
    }

    private static String retainedSecret(String incoming, String current) {
        return incoming == null || incoming.isBlank() || "********".equals(incoming)
                ? current : incoming;
    }

    private static String safeMessage(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
