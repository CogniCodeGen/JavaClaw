package com.javaclaw.server.persistence;

import java.sql.ResultSet;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Process configuration projection; credentials are intentionally stored elsewhere. */
public final class H2ServerConfigurationRepository {
    private final H2Database database;
    private final Clock clock;

    H2ServerConfigurationRepository(H2Database database, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 读取服务配置 JSON 文本快照；上层负责校验字段白名单和敏感键。 */
    public Map<String, String> read() {
        return database.query(this::read);
    }

    /** 在同一事务中删除 removals、合并 values，并返回更新快照；不重排配置的业务执行顺序。 */
    public Map<String, String> update(Map<String, String> values, Set<String> removals) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(removals, "removals");
        return database.transaction(connection -> {
            try (var delete = connection.prepareStatement("DELETE FROM server_configuration WHERE config_key = ?")) {
                for (String key : removals) {
                    delete.setString(1, requireKey(key));
                    delete.addBatch();
                }
                delete.executeBatch();
            }
            try (var merge = connection.prepareStatement("""
                    MERGE INTO server_configuration(config_key, value_json, updated_at)
                    KEY(config_key) VALUES (?, ?, ?)
                    """)) {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    merge.setString(1, requireKey(entry.getKey()));
                    merge.setString(2, Objects.requireNonNull(entry.getValue(), "configuration value"));
                    merge.setLong(3, clock.millis());
                    merge.addBatch();
                }
                merge.executeBatch();
            }
            return read(connection);
        });
    }

    private Map<String, String> read(java.sql.Connection connection) throws java.sql.SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT config_key, value_json FROM server_configuration ORDER BY config_key
                """);
                ResultSet rows = statement.executeQuery()) {
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            while (rows.next()) {
                result.put(rows.getString(1), rows.getString(2));
            }
            return Map.copyOf(result);
        }
    }

    private static String requireKey(String key) {
        String result = Objects.requireNonNull(key, "configuration key").strip();
        if (result.isEmpty() || result.length() > 128) {
            throw new IllegalArgumentException("configuration keys must contain 1-128 characters");
        }
        return result;
    }
}
