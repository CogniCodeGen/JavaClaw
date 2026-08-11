package com.javaclaw.platform.data;

import com.javaclaw.config.DatabaseAccess;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** 让现有持久化端口复用 Spring 管理的 DataSource，迁移期间不再创建静态数据库入口。 */
public final class DataSourceDatabaseAccess implements DatabaseAccess {

    private final DataSource dataSource;
    private final String description;

    public DataSourceDatabaseAccess(DataSource dataSource, String description) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.description = Objects.requireNonNull(description, "description");
    }

    @Override
    public Connection open() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public String description() {
        return description;
    }
}
