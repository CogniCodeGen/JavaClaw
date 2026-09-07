package com.javaclaw.server.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import com.javaclaw.nativehost.ManagedRuntimeDirectory;

/**
 * data-v6 的 H2 连接入口。
 *
 * <p>该类不维护全局 Connection。每次事务独占一个连接，所有权在 try-with-resources 结束时释放。
 */
public final class H2Database {
    /** 当前 data-v6 Core schema 版本。 */
    public static final int CORE_SCHEMA_VERSION = 4;

    private static final List<String> DATABASE_FILES = List.of(
            "javaclaw.mv.db",
            "javaclaw.trace.db",
            "javaclaw.trace.db.old",
            "javaclaw.lock.db",
            "javaclaw.newFile",
            "javaclaw.tempFile",
            "javaclaw.mv.db.newFile",
            "javaclaw.mv.db.tempFile",
            "javaclaw.h2.db");

    private final Path dataRoot;
    private final String jdbcUrl;

    /**
     * 创建 data-v6 数据库。
     *
     * @param dataRoot 必须以 {@code data-v6} 结尾的目录
     */
    public H2Database(Path dataRoot) {
        Path normalized =
                Objects.requireNonNull(dataRoot, "dataRoot").toAbsolutePath().normalize();
        if (normalized.getFileName() == null
                || !"data-v6".equals(normalized.getFileName().toString())) {
            throw new IllegalArgumentException("data root must end with data-v6");
        }
        this.dataRoot = normalized;
        jdbcUrl = "jdbc:h2:file:" + normalized.resolve("javaclaw").toString()
                + ";AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000";
    }

    /** 初始化目录、校验原始 V001 并应用有序前向迁移。 */
    public void initialize() {
        try {
            ManagedRuntimeDirectory.prepare(dataRoot);
            new CoreSchemaInitializer(this).initialize();
        } catch (IOException failure) {
            throw new PersistenceException("无法创建 data-v6 目录", failure);
        }
    }

    Connection open() throws SQLException {
        requireRegularDatabaseFiles();
        return DriverManager.getConnection(jdbcUrl, "sa", "");
    }

    private void requireRegularDatabaseFiles() throws SQLException {
        // 根目录私有并不能证明既有文件没有链接；只读取固定 H2 文件的元数据，绝不跟随目标。
        for (String filename : DATABASE_FILES) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        dataRoot.resolve(filename), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile()) {
                    throw new SQLException("H2 文件必须是普通文件，拒绝符号链接或其他文件类型: " + filename, "08001");
                }
            } catch (NoSuchFileException missing) {
                // 新数据库及未启用的辅助文件不存在是正常状态，由 JDBC 在校验后创建。
            } catch (IOException failure) {
                throw new SQLException("无法验证 H2 文件边界: " + filename, "08001", failure);
            }
        }
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
     * 返回已经规范化的 data-v6 根目录，供同一组合根内的 Blob 与 Bundle 存储使用。
     *
     * @return data-v6 根目录
     */
    public Path dataRoot() {
        return dataRoot;
    }
}
