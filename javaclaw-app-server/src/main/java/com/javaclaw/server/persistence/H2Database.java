package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/** Shared connection, transaction and lifecycle boundary for every H2 v4 repository. */
public final class H2Database implements AutoCloseable {
    /**
     * 一次受管理 JDBC 连接内的工作单元；不得让连接或依赖其生命周期的 ResultSet 逃逸。
     *
     * @param <T> 在连接关闭前提取完成的返回值类型
     */
    @FunctionalInterface
    public interface SqlWork<T> {
        /**
         * 在借用 connection 上执行 SQL 并提取结果；不能关闭连接或自行 commit/rollback。
         *
         * @throws java.sql.SQLException SQL 执行或结果读取失败
         */
        T apply(Connection connection) throws SQLException;
    }

    private final DataSource dataSource;
    private final Connection anchor;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 持有锚点连接，预检全部 migration 校验和，升级已有 v4 文件库前生成一致性备份。 H2 DDL 可能隐式提交，不能假定 transaction 能回滚整个升级；失败关闭锚点且保留备份。 */
    public H2Database(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.anchor = openAnchor(dataSource);
        try {
            transaction(connection -> {
                H2MigrationRunner.migrate(connection);
                return null;
            });
        } catch (RuntimeException | Error failure) {
            try {
                anchor.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** 借用连接执行工作并在返回后关闭连接；SQL 异常包装为 IllegalStateException，不向外返回活跃游标。 */
    public <T> T query(SqlWork<T> work) {
        requireOpen();
        try (Connection connection = dataSource.getConnection()) {
            return work.apply(connection);
        } catch (SQLException failure) {
            throw new IllegalStateException("H2 query failed", failure);
        }
    }

    /**
     * 新建独立事务执行 work，成功提交、任意失败回滚并保留回滚异常；嵌套调用不隐式共享连接。
     *
     * @param <T> 提交前已提取的结果类型
     * @param work 不可自行提交或关闭连接的 SQL 工作
     * @return 成功提交后的结果
     */
    public <T> T transaction(SqlWork<T> work) {
        requireOpen();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (Throwable failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("H2 transaction failed", failure);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot open H2 transaction", failure);
        }
    }

    /** 返回连接工厂边界是否已关闭；关闭后不能开启新的查询或事务。 */
    public boolean isClosed() {
        return closed.get();
    }

    void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("H2 database is closed");
        }
    }

    private static Connection openAnchor(DataSource dataSource) {
        try {
            return dataSource.getConnection();
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot keep H2 database open", failure);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            anchor.close();
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot close H2 database", failure);
        }
    }
}
