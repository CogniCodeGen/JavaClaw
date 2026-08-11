package com.javaclaw.platform.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JavaClaw 的 H2 连接源。
 *
 * <p>优先使用 H2 {@code AUTO_SERVER} mixed mode；运行环境不允许本地套接字时，
 * 当前 DataSource 会永久回退到 embedded 模式，避免每次连接重复触发已知失败。
 * 连接按调用创建，不在本类缓存，因此关闭责任仍属于调用方或 Spring JDBC。</p>
 *
 * <p>实例线程安全。回退只针对明确的 AUTO_SERVER/套接字能力错误，认证、磁盘和
 * SQL 配置错误会原样抛出。</p>
 */
public final class H2DataSource extends AbstractDataSource {

    private static final Logger log = LoggerFactory.getLogger(H2DataSource.class);
    private final Path databaseBase;
    private final AtomicBoolean embeddedOnly = new AtomicBoolean(false);

    public H2DataSource(DataRoot dataRoot) {
        this.databaseBase = dataRoot.path().resolve("javaclaw").toAbsolutePath().normalize();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connect("sa", "");
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return connect(username, password);
    }

    public Path databaseFile() {
        return databaseBase.resolveSibling(databaseBase.getFileName() + ".mv.db");
    }

    public boolean isEmbeddedFallback() {
        return embeddedOnly.get();
    }

    private Connection connect(String username, String password) throws SQLException {
        if (!embeddedOnly.get()) {
            try {
                return DriverManager.getConnection(jdbcUrl(true), username, password);
            } catch (SQLException failure) {
                if (!canFallback(failure)) {
                    throw failure;
                }
                if (embeddedOnly.compareAndSet(false, true)) {
                    log.warn("H2 mixed mode 不可用，根 DataSource 回退 embedded 模式: {}",
                            failure.getMessage());
                }
            }
        }
        return DriverManager.getConnection(jdbcUrl(false), username, password);
    }

    private String jdbcUrl(boolean autoServer) {
        String path = databaseBase.toString().replace('\\', '/');
        String base = "jdbc:h2:file:" + path
                + ";DATABASE_TO_UPPER=false"
                + ";LOCK_TIMEOUT=10000"
                + ";TRACE_LEVEL_FILE=0";
        return autoServer ? base + ";AUTO_SERVER=TRUE" : base;
    }

    private boolean canFallback(SQLException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        return failure.getErrorCode() == 50100
                || failure.getErrorCode() == 90031
                || message.contains("AUTO_SERVER")
                || message.contains("SocketException")
                || message.contains("Operation not permitted");
    }
}
