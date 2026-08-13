package com.javaclaw.framework.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.ExtensionStateStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** Run-scoped, extension-namespaced state store. */
public final class JdbcExtensionStateStore implements ExtensionStateStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public JdbcExtensionStateStore(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public com.javaclaw.framework.spi.ExtensionStateView view(RunId runId) {
        Objects.requireNonNull(runId, "runId");
        return new com.javaclaw.framework.spi.ExtensionStateView() {
            @Override public RunId runId() { return runId; }

            @Override
            public Optional<JsonNode> get(String extensionId, String key) {
                extensionId = required(extensionId, "extensionId");
                key = required(key, "key");
                return jdbc.query("""
                        SELECT state_json FROM agent_extension_state
                        WHERE run_id = ? AND extension_id = ? AND state_key = ?
                        """, (row, index) -> read(row.getString(1)),
                        runId.value(), extensionId, key).stream().findFirst();
            }
        };
    }

    @Override
    public void put(RunId runId, String extensionId, String key, int schemaVersion, JsonNode value) {
        Objects.requireNonNull(runId, "runId");
        extensionId = required(extensionId, "extensionId");
        key = required(key, "key");
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        Objects.requireNonNull(value, "value");
        jdbc.update("""
                MERGE INTO agent_extension_state(
                    run_id, extension_id, state_key, schema_version, state_json, updated_at)
                KEY(run_id, extension_id, state_key) VALUES (?, ?, ?, ?, ?, ?)
                """, runId.value(), extensionId, key, schemaVersion, write(value), clock.millis());
    }

    @Override
    public void remove(RunId runId, String extensionId, String key) {
        Objects.requireNonNull(runId, "runId");
        extensionId = required(extensionId, "extensionId");
        key = required(key, "key");
        jdbc.update("""
                DELETE FROM agent_extension_state
                WHERE run_id = ? AND extension_id = ? AND state_key = ?
                """, runId.value(), extensionId, key);
    }

    private String write(JsonNode value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("cannot serialize extension state", e); }
    }

    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (Exception e) { throw new IllegalStateException("cannot deserialize extension state", e); }
    }

    private static String required(String value, String name) {
        String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return checked;
    }
}
