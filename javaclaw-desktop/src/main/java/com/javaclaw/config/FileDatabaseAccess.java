package com.javaclaw.config;

import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.data.H2DataSource;
import com.javaclaw.platform.data.SchemaInitializer;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 指向显式数据目录的数据库访问器。适用于集成测试和独立工具，避免修改全局
 * {@code user.dir} 或 {@code javaclaw.data.dir}。
 */
public final class FileDatabaseAccess implements DatabaseAccess {

    private final H2DataSource dataSource;

    public FileDatabaseAccess(Path dataDir) {
        Path normalized = Objects.requireNonNull(dataDir, "dataDir")
                .toAbsolutePath().normalize();
        DataRoot root = new DataRoot(normalized);
        try {
            root.prepare();
        } catch (java.io.IOException failure) {
            throw new UncheckedIOException("准备显式 JavaClaw 3 数据目录失败", failure);
        }
        this.dataSource = new H2DataSource(root);
        new SchemaInitializer(dataSource).initialize();
    }

    @Override
    public Connection open() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public String description() {
        return dataSource.databaseFile().toString();
    }
}
