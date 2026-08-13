package com.javaclaw.framework.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.spi.ExecutionPlanStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** H2 execution-plan cache used for exact pause/restart recovery. */
public final class JdbcExecutionPlanStore implements ExecutionPlanStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public JdbcExecutionPlanStore(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void save(String planId, long extensionGeneration, JsonNode plan, String checksum) {
        String serialized = write(plan);
        jdbc.update("""
                MERGE INTO compiled_execution_plans(
                    plan_id, extension_generation, plan_json, checksum, created_at)
                KEY(plan_id) VALUES (?, ?, ?, ?, ?)
                """, planId, extensionGeneration, serialized, checksum, clock.millis());
    }

    @Override
    public Optional<JsonNode> find(String planId) {
        return jdbc.query("SELECT plan_json FROM compiled_execution_plans WHERE plan_id = ?",
                (row, index) -> read(row.getString(1)), planId).stream().findFirst();
    }

    private String write(JsonNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot serialize execution plan", failure);
        }
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot deserialize execution plan", failure);
        }
    }
}
