package com.javaclaw.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.FileDatabaseAccess;
import com.javaclaw.platform.json.JsonCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** 包内测试数据库适配；生产代码只接收 Spring 管理的 JdbcTemplate 与事务管理器。 */
final class ScheduleTestStoreFactory {

    private ScheduleTestStoreFactory() { }

    static ScheduledTaskStore create(Path dataDir) {
        FileDatabaseAccess database = new FileDatabaseAccess(dataDir);
        AbstractDataSource dataSource = new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return database.open();
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return getConnection();
            }
        };
        return new ScheduledTaskStore(
                new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource),
                new JsonCodec(new ObjectMapper().findAndRegisterModules()));
    }
}
