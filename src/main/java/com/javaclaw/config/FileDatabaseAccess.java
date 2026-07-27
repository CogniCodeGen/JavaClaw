package com.javaclaw.config;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 指向显式数据目录的数据库访问器。适用于集成测试和独立工具，避免修改全局
 * {@code user.dir} 或 {@code javaclaw.data.dir}。
 */
public final class FileDatabaseAccess implements DatabaseAccess {

    private final Path dataDir;

    public FileDatabaseAccess(Path dataDir) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
    }

    @Override
    public Connection open() throws SQLException {
        return AppDatabase.open(dataDir);
    }

    @Override
    public String description() {
        return dataDir.resolve("javaclaw.mv.db").toString();
    }
}
