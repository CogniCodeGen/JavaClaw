package com.javaclaw.server.persistence;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import org.h2.tools.RunScript;

import com.javaclaw.extension.spi.ExtensionId;

/** 为内置扩展分配隔离 H2 schema，并验证独立 baseline history。 */
final class ExtensionSchemaOwner {
    private static final String BASELINE_RESOURCE = "/db/extension/V001__managed_store.sql";
    private static final int BASELINE_VERSION = 1;

    private final H2Database database;
    private final Clock clock;
    private final Object initializationLock = new Object();
    private final Set<ExtensionId> initialized = new HashSet<>();

    ExtensionSchemaOwner(H2Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    String ensureInitialized(ExtensionId extensionId) {
        synchronized (initializationLock) {
            String schema = schemaName(extensionId);
            if (!initialized.contains(extensionId)) {
                initialize(extensionId, schema);
                initialized.add(extensionId);
            }
            return schema;
        }
    }

    private void initialize(ExtensionId extensionId, String schema) {
        byte[] template = readBaseline();
        String checksum = sha256(template);
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            reserveSchema(connection, extensionId, schema);
            applyBaseline(connection, schema, template);
            verifyOrRecordHistory(connection, schema, checksum);
            connection.commit();
        } catch (SQLException failure) {
            throw new PersistenceException("扩展 schema 初始化失败: " + extensionId.value(), failure);
        }
    }

    private void reserveSchema(Connection connection, ExtensionId extensionId, String schema) throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement("SELECT SCHEMA_NAME FROM CORE.EXTENSION_SCHEMA WHERE EXTENSION_ID = ?")) {
            query.setString(1, extensionId.value());
            try (ResultSet result = query.executeQuery()) {
                if (result.next()) {
                    if (!schema.equals(result.getString(1))) {
                        throw new PersistenceException("扩展 schema 映射不一致");
                    }
                    return;
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO CORE.EXTENSION_SCHEMA (EXTENSION_ID, SCHEMA_NAME) VALUES (?, ?)")) {
            insert.setString(1, extensionId.value());
            insert.setString(2, schema);
            insert.executeUpdate();
        }
    }

    private void applyBaseline(Connection connection, String schema, byte[] template) throws SQLException {
        String sql = new String(template, StandardCharsets.UTF_8).replace("${schema}", schema);
        RunScript.execute(
                connection,
                new InputStreamReader(
                        new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
    }

    private void verifyOrRecordHistory(Connection connection, String schema, String checksum) throws SQLException {
        String installed = installedChecksum(connection, schema);
        if (installed == null) {
            recordHistory(connection, schema, checksum);
        } else if (!installed.equals(checksum)) {
            throw new PersistenceException("扩展 managed store baseline checksum 不一致");
        }
    }

    private String installedChecksum(Connection connection, String schema) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT CHECKSUM FROM " + schema + ".SCHEMA_HISTORY WHERE VERSION = ?")) {
            statement.setInt(1, BASELINE_VERSION);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private void recordHistory(Connection connection, String schema, String checksum) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + schema
                + ".SCHEMA_HISTORY (VERSION, DESCRIPTION, CHECKSUM, INSTALLED_AT) VALUES (?, ?, ?, ?)")) {
            statement.setInt(1, BASELINE_VERSION);
            statement.setString(2, "managed extension store baseline");
            statement.setString(3, checksum);
            statement.setObject(4, Instant.now(clock).atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private byte[] readBaseline() {
        try (InputStream input = ExtensionSchemaOwner.class.getResourceAsStream(BASELINE_RESOURCE)) {
            if (input == null) {
                throw new PersistenceException("缺少扩展 managed store baseline resource");
            }
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new PersistenceException("读取扩展 managed store baseline 失败", failure);
        }
    }

    private static String schemaName(ExtensionId extensionId) {
        byte[] id = extensionId.value().getBytes(StandardCharsets.UTF_8);
        return "EXT_" + sha256(id).substring(0, 24).toUpperCase(java.util.Locale.ROOT);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
