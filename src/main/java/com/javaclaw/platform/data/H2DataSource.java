package com.javaclaw.platform.data;

import org.springframework.jdbc.datasource.AbstractDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * JavaClaw 的 H2 连接源。
 *
 * <p>JavaClaw 已通过数据目录级单实例协调器保证同一数据根只有一个 Desktop 进程，
 * 因此数据库固定使用 embedded 文件模式。不能在这里启用 {@code AUTO_SERVER}：混合模式
 * 会把每次短连接变成临时 TCP server 的启停边界；进程交错或套接字能力变化时，H2 可能
 * 让后续连接看到不一致的数据库生命周期。</p>
 *
 * <p>实例线程安全。每次调用仍返回独立连接，由调用方或 Spring JDBC 关闭。本类另持有
 * 一个不参与业务操作的连接，避免短连接之间触发 H2 关库和压缩；Spring 根上下文关闭时
 * 才释放它。诊断工具必须在 JavaClaw 停止后访问数据库文件。</p>
 *
 * <p>任务暂停、取消和超时会中断业务线程。H2 的默认 {@code file:} 通道可能因此关闭
 * 整个数据库共享的文件句柄，所以使用 H2 自带的 {@code async:} 文件系统：文件读写由
 * 异步通道完成，等待它的线程被中断时不会关闭共享句柄。它仍访问同一个本地
 * {@code javaclaw.mv.db}，不改变事务、凭据或单实例边界。</p>
 */
public final class H2DataSource extends AbstractDataSource implements AutoCloseable {

    private final Path databaseBase;
    private Connection keepAlive;
    private boolean closed;

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

    private synchronized Connection connect(String username, String password) throws SQLException {
        if (closed) {
            throw new SQLException("H2 data source is closed");
        }
        if (keepAlive == null || keepAlive.isClosed()) {
            keepAlive = DriverManager.getConnection(jdbcUrl(), username, password);
        }
        return DriverManager.getConnection(jdbcUrl(), username, password);
    }

    @Override
    public synchronized void close() throws SQLException {
        closed = true;
        if (keepAlive != null) {
            keepAlive.close();
            keepAlive = null;
        }
    }

    private String jdbcUrl() {
        String path = databaseBase.toString().replace('\\', '/');
        return "jdbc:h2:async:" + path
                + ";DATABASE_TO_UPPER=false"
                + ";LOCK_TIMEOUT=10000"
                + ";TRACE_LEVEL_FILE=0";
    }
}
