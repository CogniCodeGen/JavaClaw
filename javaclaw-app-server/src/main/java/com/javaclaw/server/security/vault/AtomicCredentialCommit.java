package com.javaclaw.server.security.vault;

import java.sql.Connection;

/**
 * Vault 变更已在当前事务中暂存后执行的同库提交工作。
 *
 * <p>连接只在回调期间有效；实现不得提交、回滚、关闭或跨线程保存连接。回调失败会让 Vault 变更与业务写入一起回滚。
 *
 * @param <T> 不含 Secret 的命令结果类型
 */
@FunctionalInterface
public interface AtomicCredentialCommit<T> {
    /**
     * 在当前 Vault H2 事务内提交关联业务状态。
     *
     * @param connection 当前事务独占连接
     * @return 不含 Secret 的幂等结果
     * @throws Exception SQL 或业务校验失败
     */
    T commit(Connection connection) throws Exception;
}
