package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;

/** Provider 与 Agent Profile 不可变版本行的共享 SQL。 */
final class VersionedSettingsRepository {
    List<StoredVersion> listAll(Connection connection, Table table) throws SQLException {
        String sql = "SELECT ID, REVISION, LIFECYCLE, PAYLOAD, CREATED_AT, UPDATED_AT FROM "
                + table.sqlName()
                + " ORDER BY ID, REVISION";
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            List<StoredVersion> values = new ArrayList<>();
            while (result.next()) {
                values.add(map(result));
            }
            return List.copyOf(values);
        }
    }

    List<StoredVersion> listLatest(Connection connection, Table table) throws SQLException {
        String sql = "SELECT S.ID, S.REVISION, S.LIFECYCLE, S.PAYLOAD, S.CREATED_AT, S.UPDATED_AT FROM "
                + table.sqlName()
                + " S JOIN (SELECT ID, MAX(REVISION) REVISION FROM "
                + table.sqlName()
                + " GROUP BY ID) L ON L.ID = S.ID AND L.REVISION = S.REVISION ORDER BY S.ID";
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            List<StoredVersion> values = new ArrayList<>();
            while (result.next()) {
                values.add(map(result));
            }
            return List.copyOf(values);
        }
    }

    Optional<StoredVersion> find(Connection connection, Table table, String id, long revision) throws SQLException {
        String sql = "SELECT ID, REVISION, LIFECYCLE, PAYLOAD, CREATED_AT, UPDATED_AT FROM "
                + table.sqlName()
                + " WHERE ID = ? AND REVISION = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setLong(2, revision);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    Optional<StoredVersion> latest(Connection connection, Table table, String id, boolean lock) throws SQLException {
        String suffix = lock ? " FOR UPDATE" : "";
        String sql = "SELECT ID, REVISION, LIFECYCLE, PAYLOAD, CREATED_AT, UPDATED_AT FROM "
                + table.sqlName()
                + " WHERE ID = ? ORDER BY REVISION DESC LIMIT 1"
                + suffix;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    void insert(Connection connection, Table table, StoredVersion version) throws SQLException {
        String sql = "INSERT INTO "
                + table.sqlName()
                + " (ID, REVISION, LIFECYCLE, PAYLOAD, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, version.id());
            statement.setLong(2, version.revision());
            statement.setString(3, version.lifecycle());
            statement.setString(4, version.payload().json());
            statement.setObject(5, version.createdAt().atOffset(ZoneOffset.UTC));
            statement.setObject(6, version.updatedAt().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private static StoredVersion map(ResultSet result) throws SQLException {
        return new StoredVersion(
                result.getString("ID"),
                result.getLong("REVISION"),
                result.getString("LIFECYCLE"),
                new CanonicalPayload(result.getString("PAYLOAD")),
                instant(result, "CREATED_AT"),
                instant(result, "UPDATED_AT"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toInstant();
    }

    /** 固定表名，禁止把 wire 字符串拼接进 SQL。 */
    enum Table {
        /** Provider 历史。 */
        PROVIDER("CORE.PROVIDER"),
        /** Agent Profile 历史。 */
        PROFILE("CORE.PROFILE");

        private final String sqlName;

        Table(String sqlName) {
            this.sqlName = sqlName;
        }

        String sqlName() {
            return sqlName;
        }
    }

    record StoredVersion(
            String id,
            long revision,
            String lifecycle,
            CanonicalPayload payload,
            Instant createdAt,
            Instant updatedAt) {}
}
