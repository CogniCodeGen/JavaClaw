package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** data-v5 的显式事务协调器；跨 App Server 子系统共享同一事务边界实现。 */
public final class H2Transactions {
    private final H2Database database;

    /**
     * 创建事务协调器。
     *
     * @param database data-v5 数据库
     */
    public H2Transactions(H2Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /**
     * 以 SERIALIZABLE 隔离级别执行一个工作单元。
     *
     * @param work 持有独占连接所有权的事务工作
     * @param <T> 返回类型
     * @return 已提交结果
     * @throws Exception 工作或提交失败；失败时先尝试回滚
     */
    public <T> T execute(SqlWork<T> work) throws Exception {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (Exception failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
    }

    private void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    /**
     * 数据库事务内执行的受检工作。
     *
     * @param <T> 事务结果类型
     */
    @FunctionalInterface
    public interface SqlWork<T> {
        /**
         * 使用当前事务连接执行。
         *
         * @param connection 仅在回调期间有效的连接
         * @return 事务结果
         * @throws Exception SQL 或领域校验失败
         */
        T execute(Connection connection) throws Exception;
    }
}
