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
 * <p>实例线程安全。连接按调用创建，不在本类缓存，因此关闭责任仍属于调用方或
 * Spring JDBC。诊断工具必须在 JavaClaw 停止后访问数据库文件。</p>
 */
public final class H2DataSource extends AbstractDataSource {

    private final Path databaseBase;

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

    private Connection connect(String username, String password) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), username, password);
    }

    private String jdbcUrl() {
        String path = databaseBase.toString().replace('\\', '/');
        return "jdbc:h2:file:" + path
                + ";DATABASE_TO_UPPER=false"
                + ";LOCK_TIMEOUT=10000"
                + ";TRACE_LEVEL_FILE=0";
    }
}
