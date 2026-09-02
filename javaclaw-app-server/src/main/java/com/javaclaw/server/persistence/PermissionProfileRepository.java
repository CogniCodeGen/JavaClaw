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

/** PermissionProfile 的不可变版本行与查询 SQL。 */
final class PermissionProfileRepository {
    void insert(Connection connection, String id, long version, CanonicalPayload payload, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.PERMISSION_PROFILE (ID, VERSION, PAYLOAD, CREATED_AT)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, id);
            statement.setLong(2, version);
            statement.setString(3, payload.json());
            statement.setObject(4, now.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    Optional<StoredProfile> find(Connection connection, String id, long version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, VERSION, PAYLOAD, CREATED_AT
                FROM CORE.PERMISSION_PROFILE WHERE ID = ? AND VERSION = ?
                """)) {
            statement.setString(1, id);
            statement.setLong(2, version);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    Optional<StoredProfile> latest(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, VERSION, PAYLOAD, CREATED_AT
                FROM CORE.PERMISSION_PROFILE WHERE ID = ? ORDER BY VERSION DESC LIMIT 1
                """)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    List<StoredProfile> listLatest(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT P.ID, P.VERSION, P.PAYLOAD, P.CREATED_AT
                FROM CORE.PERMISSION_PROFILE P
                JOIN (
                    SELECT ID, MAX(VERSION) AS VERSION
                    FROM CORE.PERMISSION_PROFILE GROUP BY ID
                ) LATEST ON LATEST.ID = P.ID AND LATEST.VERSION = P.VERSION
                ORDER BY P.ID
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                List<StoredProfile> profiles = new ArrayList<>();
                while (result.next()) {
                    profiles.add(map(result));
                }
                return List.copyOf(profiles);
            }
        }
    }

    List<StoredProfile> history(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, VERSION, PAYLOAD, CREATED_AT
                FROM CORE.PERMISSION_PROFILE WHERE ID = ? ORDER BY VERSION
                """)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                List<StoredProfile> profiles = new ArrayList<>();
                while (result.next()) {
                    profiles.add(map(result));
                }
                return List.copyOf(profiles);
            }
        }
    }

    private static StoredProfile map(ResultSet result) throws SQLException {
        return new StoredProfile(
                result.getString("ID"),
                result.getLong("VERSION"),
                new CanonicalPayload(result.getString("PAYLOAD")),
                result.getObject("CREATED_AT", OffsetDateTime.class).toInstant());
    }

    record StoredProfile(String id, long version, CanonicalPayload payload, Instant createdAt) {}
}
