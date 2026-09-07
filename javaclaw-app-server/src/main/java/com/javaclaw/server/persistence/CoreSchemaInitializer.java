package com.javaclaw.server.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;

import org.h2.tools.RunScript;

/**
 * 只识别 JavaClaw 6 新 baseline 的 schema 初始化器。
 *
 * <p>领域资源按固定顺序拼接为一个版本；全部读取成功后才打开数据库，沿用单次脚本执行和 history 提交。
 */
final class CoreSchemaInitializer {
    private static final int BASELINE_VERSION = 1;
    private static final List<String> BASELINE_RESOURCES = List.of(
            "/db/core/V001__v6_baseline.sql",
            "/db/core/V001__v6_configuration.sql",
            "/db/core/V001__v6_extensions.sql",
            "/db/core/V001__v6_mcp.sql",
            "/db/core/V001__v6_collaboration.sql");

    private final H2Database database;

    CoreSchemaInitializer(H2Database database) {
        this.database = database;
    }

    void initialize() {
        byte[] baseline = readBaseline();
        String checksum = sha256(baseline);
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            ensureHistory(connection);
            String installedChecksum = installedChecksum(connection);
            if (installedChecksum == null) {
                apply(connection, baseline, checksum);
            } else if (!installedChecksum.equals(checksum)) {
                throw new PersistenceException("data-v6 baseline checksum 不一致");
            }
            connection.commit();
            new CoreMigrationRunner().migrate(connection);
        } catch (SQLException failure) {
            throw new PersistenceException("data-v6 baseline 初始化失败", failure);
        }
    }

    private void ensureHistory(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS CORE");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS CORE.SCHEMA_HISTORY (
                        VERSION INT PRIMARY KEY,
                        DESCRIPTION VARCHAR(240) NOT NULL,
                        CHECKSUM CHAR(64) NOT NULL,
                        INSTALLED_AT TIMESTAMP WITH TIME ZONE NOT NULL
                    )
                    """);
        }
    }

    private String installedChecksum(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT CHECKSUM FROM CORE.SCHEMA_HISTORY WHERE VERSION = ?")) {
            statement.setInt(1, BASELINE_VERSION);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private void apply(Connection connection, byte[] baseline, String checksum) throws SQLException {
        RunScript.execute(
                connection, new InputStreamReader(new ByteArrayInputStream(baseline), StandardCharsets.UTF_8));
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.SCHEMA_HISTORY (VERSION, DESCRIPTION, CHECKSUM, INSTALLED_AT)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setInt(1, BASELINE_VERSION);
            statement.setString(2, "v6 baseline");
            statement.setString(3, checksum);
            statement.setObject(4, OffsetDateTime.now());
            statement.executeUpdate();
        }
    }

    private byte[] readBaseline() {
        ByteArrayOutputStream baseline = new ByteArrayOutputStream();
        // 不插入分隔符或重排 SQL，使领域拆分前后的字节、执行顺序和已安装 checksum 保持一致。
        for (String resource : BASELINE_RESOURCES) {
            try (InputStream input = CoreSchemaInitializer.class.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new PersistenceException("缺少 data-v6 baseline resource: " + resource);
                }
                input.transferTo(baseline);
            } catch (IOException failure) {
                throw new PersistenceException("读取 data-v6 baseline 失败", failure);
            }
        }
        return baseline.toByteArray();
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
