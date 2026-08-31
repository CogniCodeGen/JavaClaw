package com.javaclaw.server.configuration;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.server.persistence.H2ServerConfigurationRepository;

/** Bounded, process-shared configuration view. JSON {@code null} removes a key. */
public final class ServerConfiguration implements ConfigurationUseCases {
    static final int MAX_ENTRIES = 256;
    static final int MAX_KEY_CHARACTERS = 128;
    static final int MAX_VALUE_CHARACTERS = 1_048_576;
    static final int MAX_TOTAL_CHARACTERS = 4 * 1_048_576;
    private static final java.util.regex.Pattern SENSITIVE_KEY = java.util.regex.Pattern.compile(
            "(?i)(^|[._-])(api[_-]?key|access[_-]?key|access[_-]?token|refresh[_-]?token|"
                    + "password|passwd|secret|credential|private[_-]?key|client[_-]?secret)"
                    + "($|[._-])");

    interface Backend {
        Map<String, String> read();

        Map<String, String> update(Map<String, String> values, Set<String> removals);
    }

    private final ObjectMapper json;
    private final Backend backend;
    private final java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();

    private ServerConfiguration(ObjectMapper json, Backend backend) {
        this.json = Objects.requireNonNull(json, "json");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    /** 创建持久配置服务，共享给定 H2 配置仓库；不另开工作区数据库。 */
    public static ServerConfiguration persistent(ObjectMapper json, H2ServerConfigurationRepository repository) {
        Objects.requireNonNull(repository, "repository");
        return new ServerConfiguration(json, new Backend() {
            @Override
            public Map<String, String> read() {
                return repository.read();
            }

            @Override
            public Map<String, String> update(Map<String, String> values, Set<String> removals) {
                return repository.update(values, removals);
            }
        });
    }

    /** 创建进程内配置服务，适合最小端点和测试；重启后不保留配置。 */
    public static ServerConfiguration inMemory(ObjectMapper json) {
        return new ServerConfiguration(json, new Backend() {
            private final Map<String, String> values = new LinkedHashMap<>();

            @Override
            public synchronized Map<String, String> read() {
                return Map.copyOf(values);
            }

            @Override
            public synchronized Map<String, String> update(Map<String, String> additions, Set<String> removals) {
                removals.forEach(values::remove);
                values.putAll(additions);
                return Map.copyOf(values);
            }
        });
    }

    @Override
    public synchronized ConfigurationState read() {
        return state(backend.read(), revision.get());
    }

    @Override
    public synchronized ConfigurationState update(Map<String, String> values, Set<String> removals) {
        Map<String, String> current = backend.read();
        Map<String, String> additions = new LinkedHashMap<>();
        Objects.requireNonNull(values, "values").forEach((rawKey, rawValue) -> {
            String key = requireKey(rawKey);
            additions.put(key, validateJson(rawValue, key));
        });
        LinkedHashSet<String> validatedRemovals = new LinkedHashSet<>();
        Objects.requireNonNull(removals, "removals").forEach(key -> validatedRemovals.add(requireKey(key)));
        Map<String, String> predicted = new LinkedHashMap<>(current);
        validatedRemovals.forEach(predicted::remove);
        predicted.putAll(additions);
        validateAggregate(predicted);
        Map<String, String> updated = backend.update(Map.copyOf(additions), Set.copyOf(validatedRemovals));
        return state(updated, revision.incrementAndGet());
    }

    private ConfigurationState state(Map<String, String> values, long currentRevision) {
        validateAggregate(values);
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        values.forEach((key, encoded) -> result.put(requireKey(key), validateJson(encoded, key)));
        return new ConfigurationState(result, currentRevision);
    }

    private String validateJson(String encoded, String key) {
        Objects.requireNonNull(encoded, "configuration value");
        if (encoded.length() > MAX_VALUE_CHARACTERS) {
            throw new IllegalArgumentException(
                    "configuration value exceeds " + MAX_VALUE_CHARACTERS + " characters: " + key);
        }
        try {
            JsonNode parsed = json.readTree(encoded);
            if (parsed == null) {
                throw new IllegalArgumentException("configuration value is empty: " + key);
            }
            return json.writeValueAsString(parsed);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("configuration value is not valid JSON: " + key, failure);
        }
    }

    private static void validateAggregate(Map<String, String> values) {
        if (values.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("configuration exceeds " + MAX_ENTRIES + " entries");
        }
        long total = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            requireKey(entry.getKey());
            String value = Objects.requireNonNull(entry.getValue(), "configuration value");
            if (value.length() > MAX_VALUE_CHARACTERS) {
                throw new IllegalArgumentException("stored configuration value is too large: " + entry.getKey());
            }
            total += entry.getKey().length() + value.length();
            if (total > MAX_TOTAL_CHARACTERS) {
                throw new IllegalArgumentException("configuration exceeds the total size limit");
            }
        }
    }

    private static String requireKey(String key) {
        key = Objects.requireNonNull(key, "configuration key").strip();
        if (key.isEmpty() || key.length() > MAX_KEY_CHARACTERS) {
            throw new IllegalArgumentException(
                    "configuration keys must contain 1-" + MAX_KEY_CHARACTERS + " characters");
        }
        if (SENSITIVE_KEY.matcher(key).find()) {
            throw new IllegalArgumentException("sensitive values must be written through SecretStore: " + key);
        }
        return key;
    }
}
