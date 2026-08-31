package com.javaclaw.server.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.javaclaw.server.diagnostics.DiagnosticsRepository;

/** Bounded H2 diagnostic log. Callers must pass already-redacted text. */
public final class H2DiagnosticsRepository implements DiagnosticsRepository {
    private final H2Database database;

    /** 绑定 App Server 持有的共享 H2Database；不另建连接工厂，数据库生命周期由装配层统一管理。 */
    public H2DiagnosticsRepository(H2Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public List<Record> list(int limit) {
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("diagnostic limit must be 1-10000");
        }
        return database.query(connection -> {
            ArrayList<Record> result = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT * FROM diagnostic_records ORDER BY created_at DESC, record_id
                    LIMIT ?
                    """)) {
                query.setInt(1, limit);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        result.add(read(rows));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public Record append(String severity, String component, String code, String message, String detailsJson) {
        String id = "diag_" + UUID.randomUUID().toString().replace("-", "");
        String safeSeverity = required(severity, "severity", 40);
        String safeComponent = required(component, "component", 200);
        String safeCode = required(code, "code", 200);
        String safeMessage = required(message, "message", 100_000);
        String safeDetails = detailsJson == null || detailsJson.isBlank() ? "{}" : detailsJson;
        if (safeDetails.length() > 1_000_000) {
            throw new IllegalArgumentException("diagnostic details exceed 1 MiB");
        }
        long now = System.currentTimeMillis();
        return database.transaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO diagnostic_records(record_id, severity, component, code,
                        message, details_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, id);
                insert.setString(2, safeSeverity);
                insert.setString(3, safeComponent);
                insert.setString(4, safeCode);
                insert.setString(5, safeMessage);
                insert.setString(6, safeDetails);
                insert.setLong(7, now);
                insert.executeUpdate();
            }
            return new Record(
                    id, safeSeverity, safeComponent, safeCode, safeMessage, safeDetails, Instant.ofEpochMilli(now));
        });
    }

    private static Record read(ResultSet row) throws java.sql.SQLException {
        return new Record(
                row.getString("record_id"),
                row.getString("severity"),
                row.getString("component"),
                row.getString("code"),
                row.getString("message"),
                row.getString("details_json"),
                Instant.ofEpochMilli(row.getLong("created_at")));
    }

    private static String required(String value, String name, int maximum) {
        String result = Objects.requireNonNull(value, name).strip();
        if (result.isEmpty() || result.length() > maximum) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return result;
    }
}
