package com.javaclaw.server.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

/**
 * data-v5 的 H2 连接入口。
 *
 * <p>该类不维护全局 Connection。每次事务独占一个连接，所有权在 try-with-resources 结束时释放。
 */
public final class H2Database {
    /** 当前 data-v5 Core schema 版本。 */
    public static final int CORE_SCHEMA_VERSION = 1;

    private final Path dataRoot;
    private final String jdbcUrl;

    /**
     * 创建 data-v5 数据库。
     *
     * @param dataRoot 必须以 {@code data-v5} 结尾的目录
     */
    public H2Database(Path dataRoot) {
        Path normalized =
                Objects.requireNonNull(dataRoot, "dataRoot").toAbsolutePath().normalize();
        if (normalized.getFileName() == null
                || !"data-v5".equals(normalized.getFileName().toString())) {
            throw new IllegalArgumentException("data root must end with data-v5");
        }
        this.dataRoot = normalized;
        jdbcUrl = "jdbc:h2:file:" + normalized.resolve("javaclaw").toString()
                + ";AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000";
    }

    /** 初始化目录与新的 V001 baseline。 */
    public void initialize() {
        try {
            Files.createDirectories(dataRoot);
            new CoreSchemaInitializer(this).initialize();
        } catch (IOException failure) {
            throw new PersistenceException("无法创建 data-v5 目录", failure);
        }
    }

    Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, "sa", "");
    }

    /**
     * 执行不读取用户数据的连接探针。
     *
     * @return 数据库可连接且能执行常量查询时为 true
     */
    public boolean healthy() {
        try (Connection connection = open();
                var statement = connection.prepareStatement("SELECT 1");
                var result = statement.executeQuery()) {
            return result.next() && result.getInt(1) == 1;
        } catch (SQLException failure) {
            return false;
        }
    }

    /**
     * 返回已经规范化的 data-v5 根目录，供同一组合根内的 Blob 与 Bundle 存储使用。
     *
     * @return data-v5 根目录
     */
    public Path dataRoot() {
        return dataRoot;
    }
}
