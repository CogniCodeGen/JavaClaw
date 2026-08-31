package com.javaclaw.server.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;

/** 全量预检历史版本并备份文件库，再按严格升序应用不可修改的 SQL 资源。 */
final class H2MigrationRunner {
    private static final String ROOT = "/db/migration/";
    private static final String SEPARATOR = "(?m)^--;;\\s*$";

    private H2MigrationRunner() {}

    static void migrate(Connection connection) throws SQLException {
        List<String> resources = preflight(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migrations(
                        version INT PRIMARY KEY,
                        description VARCHAR(500) NOT NULL,
                        checksum CHAR(64) NOT NULL,
                        applied_at BIGINT NOT NULL)
                    """);
        }
        for (String resource : resources) {
            apply(connection, resource);
        }
    }

    private static List<String> preflight(Connection connection) throws SQLException {
        List<String> resources = migrationIndex();
        var known = new LinkedHashMap<Integer, String>();
        for (String name : resources) {
            known.put(ParsedName.parse(name).version(), sha256(resource(name)));
        }
        boolean initialized;
        try (ResultSet tables = connection.getMetaData().getTables(null, "PUBLIC", "SCHEMA_MIGRATIONS", null)) {
            initialized = tables.next();
        }
        if (!initialized) {
            return resources;
        }
        var applied = new LinkedHashMap<Integer, String>();
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("SELECT version, checksum FROM schema_migrations")) {
            while (rows.next()) {
                int version = rows.getInt(1);
                String checksum = rows.getString(2);
                if (!known.containsKey(version) || !known.get(version).equals(checksum)) {
                    throw new SQLException("unknown or changed migration version: " + version);
                }
                applied.put(version, checksum);
            }
        }
        boolean missing = false;
        for (int version : known.keySet()) {
            if (!applied.containsKey(version)) {
                missing = true;
            } else if (missing) {
                throw new SQLException("migration history is not a complete prefix: " + version);
            }
        }
        // 先验证所有旧版本，再做任何 DDL；不能应用几个新脚本后才发现后面的历史文件被改写。
        if (!applied.isEmpty() && applied.size() < known.size()) {
            H2MigrationBackup.create(
                    connection,
                    applied.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow(),
                    known.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow());
        }
        return resources;
    }

    private static void apply(Connection connection, String resource) throws SQLException {
        ParsedName name = ParsedName.parse(resource);
        byte[] bytes = resource(resource);
        String checksum = sha256(bytes);
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT checksum FROM schema_migrations WHERE version = ?
                """)) {
            query.setInt(1, name.version());
            try (ResultSet row = query.executeQuery()) {
                if (row.next()) {
                    if (!checksum.equals(row.getString(1))) {
                        throw new SQLException("migration checksum changed: " + resource);
                    }
                    return;
                }
            }
        }
        String sql = new String(bytes, StandardCharsets.UTF_8);
        for (String block : sql.split(SEPARATOR)) {
            String statementSql = block.strip();
            if (statementSql.isEmpty() || statementSql.startsWith("-- only comments")) {
                continue;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementSql);
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO schema_migrations(version, description, checksum, applied_at)
                VALUES (?, ?, ?, ?)
                """)) {
            insert.setInt(1, name.version());
            insert.setString(2, name.description());
            insert.setString(3, checksum);
            insert.setLong(4, Instant.now().toEpochMilli());
            insert.executeUpdate();
        }
    }

    private static List<String> migrationIndex() {
        String value = new String(resource("index.txt"), StandardCharsets.UTF_8);
        List<String> resources = value.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        int previous = 0;
        for (String resource : resources) {
            int version = ParsedName.parse(resource).version();
            if (version <= previous) {
                throw new IllegalStateException("migration index is not strictly ordered");
            }
            previous = version;
        }
        return resources;
    }

    private static byte[] resource(String name) {
        try (InputStream input = H2MigrationRunner.class.getResourceAsStream(ROOT + name)) {
            if (input == null) {
                throw new IllegalStateException("missing migration resource: " + name);
            }
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read migration resource: " + name, failure);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record ParsedName(int version, String description) {
        private static ParsedName parse(String resource) {
            if (!resource.matches("V[0-9]{3}__[a-z0-9_]+\\.sql")) {
                throw new IllegalStateException("invalid migration filename: " + resource);
            }
            return new ParsedName(
                    Integer.parseInt(resource.substring(1, 4)),
                    resource.substring(6, resource.length() - 4).replace('_', ' '));
        }
    }
}
