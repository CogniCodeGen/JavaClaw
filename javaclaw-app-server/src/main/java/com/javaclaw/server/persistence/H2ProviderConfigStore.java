package com.javaclaw.server.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Versioned non-secret cloud provider configuration. */
public final class H2ProviderConfigStore {
    private static final Set<String> PROVIDERS = Set.of("openai", "anthropic", "google");
    private static final Set<String> KEYS = Set.of("model", "baseUrl", "embeddingModel", "nativeCompaction");
    private final H2Database database;
    private final H2IdempotencyStore idempotency = new H2IdempotencyStore();
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /** 绑定 App Server 持有的共享 H2Database；不另建连接工厂，数据库生命周期由装配层统一管理。 */
    public H2ProviderConfigStore(H2Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /** 列出持久 Provider 非敏感配置；凭据不在此表中。 */
    public List<ProviderConfig> list() {
        return database.query(connection -> {
            ArrayList<ProviderConfig> result = new ArrayList<>();
            try (PreparedStatement query =
                            connection.prepareStatement("SELECT * FROM provider_configs ORDER BY provider");
                    ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(read(rows));
                }
            }
            return List.copyOf(result);
        });
    }

    /** 按 Provider 标识查找配置；未配置返回 Optional.empty。 */
    public Optional<ProviderConfig> find(String provider) {
        String id = provider(provider);
        return database.query(connection -> {
            try (PreparedStatement query =
                    connection.prepareStatement("SELECT * FROM provider_configs WHERE provider = ?")) {
                query.setString(1, id);
                try (ResultSet row = query.executeQuery()) {
                    return row.next() ? Optional.of(read(row)) : Optional.empty();
                }
            }
        });
    }

    /** 按版本保存 Provider 配置并记录幂等请求；不接受在 values 中存放明文凭据。 */
    public ProviderConfig put(
            String provider, Map<String, String> values, long expectedRevision, String idempotencyKey) {
        String id = provider(provider);
        Map<String, String> config = validate(values);
        String encoded = encode(config);
        String requestHash = H2IdempotencyStore.requestHash(id, encoded, expectedRevision);
        return database.transaction(connection -> {
            Optional<ProviderConfig> replay = idempotency.replay(
                    connection, "provider/configure", idempotencyKey, requestHash, ProviderConfig.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            ProviderConfig current = null;
            try (PreparedStatement query =
                    connection.prepareStatement("SELECT * FROM provider_configs WHERE provider = ? FOR UPDATE")) {
                query.setString(1, id);
                try (ResultSet row = query.executeQuery()) {
                    if (row.next()) {
                        current = read(row);
                    }
                }
            }
            long now = System.currentTimeMillis();
            ProviderConfig result;
            if (current == null) {
                if (expectedRevision != 0) {
                    throw new NoSuchElementException("provider config not found: " + id);
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO provider_configs(provider, config_json, revision,
                            created_at, updated_at) VALUES (?, ?, 1, ?, ?)
                        """)) {
                    insert.setString(1, id);
                    insert.setString(2, encoded);
                    insert.setLong(3, now);
                    insert.setLong(4, now);
                    insert.executeUpdate();
                }
                result = new ProviderConfig(id, config, 1, Instant.ofEpochMilli(now));
            } else {
                if (expectedRevision < 1 || current.revision() != expectedRevision) {
                    throw new IllegalStateException("provider config revision conflict: " + id);
                }
                long revision = current.revision() + 1;
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE provider_configs SET config_json = ?, revision = ?, updated_at = ?
                        WHERE provider = ? AND revision = ?
                        """)) {
                    update.setString(1, encoded);
                    update.setLong(2, revision);
                    update.setLong(3, now);
                    update.setString(4, id);
                    update.setLong(5, current.revision());
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException("provider config revision conflict: " + id);
                    }
                }
                result = new ProviderConfig(id, config, revision, Instant.ofEpochMilli(now));
            }
            idempotency.record(connection, "provider/configure", idempotencyKey, requestHash, result, now);
            return result;
        });
    }

    private ProviderConfig read(ResultSet row) throws java.sql.SQLException {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> config = json.readValue(row.getString("config_json"), Map.class);
            return new ProviderConfig(
                    row.getString("provider"),
                    validate(config),
                    row.getLong("revision"),
                    Instant.ofEpochMilli(row.getLong("updated_at")));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("invalid persisted provider config", failure);
        }
    }

    private String encode(Map<String, String> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("cannot encode provider config", failure);
        }
    }

    private static Map<String, String> validate(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("unsupported provider config key: " + key);
            }
            String normalized =
                    Objects.requireNonNull(value, "provider config value").strip();
            if (normalized.isEmpty() || normalized.length() > 4_000) {
                throw new IllegalArgumentException("provider config value is invalid: " + key);
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

    /**
     * 非敏感 Provider 配置的持久快照。
     *
     * @param provider 云 Provider 标识
     * @param config 非空配置 Map，构造时复制；不得包含凭据
     * @param revision 持久修订号，用于乐观锁和缓存失效
     * @param updatedAt 最近更新时间；尚未配置的资源可为 null
     */
    public record ProviderConfig(String provider, Map<String, String> config, long revision, Instant updatedAt) {
        /** 复制配置 Map，隔离模型重载与调用方修改。 */
        public ProviderConfig {
            config = Map.copyOf(config);
        }
    }
}
