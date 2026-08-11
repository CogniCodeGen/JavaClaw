package com.javaclaw.task.sdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.config.FileDatabaseAccess;
import com.javaclaw.platform.json.JsonCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** SDD 持久化测试的非全局 H2/Spring JDBC 组合。 */
public final class SddTestDatabase {

    private final DatabaseAccess access;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final JsonCodec json;

    public SddTestDatabase(Path dataDir) {
        access = new FileDatabaseAccess(dataDir);
        dataSource = new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return access.open();
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return getConnection();
            }
        };
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        json = new JsonCodec(new ObjectMapper().findAndRegisterModules());
    }

    public DatabaseAccess access() { return access; }
    public JdbcTemplate jdbc() { return jdbc; }
    public PlatformTransactionManager transactions() { return transactions; }
    public JsonCodec json() { return json; }
}
