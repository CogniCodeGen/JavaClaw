package com.javaclaw.config;

import java.sql.Connection;
import java.sql.SQLException;

/** 连接 JavaClaw 全局 H2 数据库的生产实现。 */
public final class AppDatabaseAccess implements DatabaseAccess {

    @Override
    public Connection open() throws SQLException {
        return AppDatabase.getConnection();
    }

    @Override
    public String description() {
        return AppDatabase.databaseDisplayPath();
    }
}
