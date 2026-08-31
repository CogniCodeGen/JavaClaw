package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Transaction-local replay protection shared by v1 mutating resource repositories. */
final class H2IdempotencyStore {
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    <T> Optional<T> replay(Connection connection, String method, String key, String requestHash, Class<T> type)
            throws SQLException {
        String normalized = normalize(key);
        if (normalized == null) {
            return Optional.empty();
        }
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT request_hash, response_json FROM idempotency_records
                WHERE method = ? AND idempotency_key = ?
                """)) {
            query.setString(1, required(method, "method"));
            query.setString(2, normalized);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                if (!Objects.equals(row.getString(1), requestHash)) {
                    throw new IllegalStateException("idempotency key was already used with another request");
                }
                try {
                    return Optional.of(json.readValue(row.getString(2), type));
                } catch (JsonProcessingException failure) {
                    throw new IllegalStateException("invalid idempotency replay record", failure);
                }
            }
        }
    }

    void record(Connection connection, String method, String key, String requestHash, Object response, long now)
            throws SQLException {
        String normalized = normalize(key);
        if (normalized == null) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO idempotency_records(
                    method, idempotency_key, request_hash, response_json, created_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, required(method, "method"));
            insert.setString(2, normalized);
            insert.setString(3, requestHash);
            try {
                insert.setString(4, json.writeValueAsString(response));
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException("cannot serialize idempotency response", failure);
            }
            insert.setLong(5, now);
            insert.executeUpdate();
        }
    }

    void forget(Connection connection, String method, String key) throws SQLException {
        String normalized = normalize(key);
        if (normalized == null) {
            return;
        }
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM idempotency_records
                WHERE method = ? AND idempotency_key = ?
                """)) {
            delete.setString(1, required(method, "method"));
            delete.setString(2, normalized);
            delete.executeUpdate();
        }
    }

    static String requestHash(Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                byte[] bytes = String.valueOf(part).getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("idempotencyKey exceeds 500 characters");
        }
        return normalized;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
