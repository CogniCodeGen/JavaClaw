package com.javaclaw.platform.data;

import org.springframework.dao.DataAccessResourceFailureException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 根 Context 的一次性 schema 初始化器。
 *
 * <p>{@link #initialize()} 幂等且线程安全；只有完整执行全部幂等 DDL 后才标记成功，
 * 因此失败后可以由下一次显式调用重试。这里只创建当前 schema，
 * 不承担旧版本业务迁移。</p>
 */
public final class SchemaInitializer {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SchemaInitializer.class);
    private static final List<String> THREAD_TABLES = List.of(
            "agent_threads", "agent_thread_events", "agent_thread_outbox",
            "agent_thread_mutations", "workflow_thread_lifecycle");

    private final DataSource dataSource;
    private final JavaClawSchema schema = new JavaClawSchema();
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public SchemaInitializer(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public synchronized void initialize() {
        if (initialized.get()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            schema.initialize(connection);
            initialized.set(true);
        } catch (SQLException failure) {
            throw new DataAccessResourceFailureException("初始化 JavaClaw 4 schema 失败", failure);
        }
    }

    /**
     * 启动会话运行时前检查旧数据库所需的表。即使初始化曾被标记成功，也从数据库
     * 重新检查；缺表时重复执行幂等 DDL，并通过新连接确认修复已持久化。
     */
    public synchronized void ensureThreadSchema() {
        initialize();
        try {
            List<String> missing = missingThreadTables();
            if (!missing.isEmpty()) {
                log.warn("检测到缺失的会话表 {}，正在补全数据库结构", missing);
                try (Connection connection = dataSource.getConnection();
                     Statement statement = connection.createStatement()) {
                    ThreadSchema.initialize(statement);
                }
                missing = missingThreadTables();
                if (!missing.isEmpty()) {
                    throw new SQLException("会话表仍然缺失: " + missing);
                }
            }
        } catch (SQLException failure) {
            throw new DataAccessResourceFailureException("检查或修复 JavaClaw 会话 schema 失败", failure);
        }
    }

    private List<String> missingThreadTables() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            List<String> missing = new ArrayList<>();
            for (String table : THREAD_TABLES) {
                try (ResultSet tables = metadata.getTables(
                        connection.getCatalog(), connection.getSchema(), table, new String[] {"TABLE"})) {
                    boolean found = false;
                    while (tables.next()) {
                        if (table.equals(tables.getString("TABLE_NAME"))) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        missing.add(table);
                    }
                }
            }
            return missing;
        }
    }

    public boolean isInitialized() {
        return initialized.get();
    }
}
