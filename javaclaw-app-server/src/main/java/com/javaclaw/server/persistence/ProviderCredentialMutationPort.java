package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.util.Optional;
import java.util.function.Supplier;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;

/** Provider 配置与 Vault 密文原子变更之间的窄事务端口。 */
public interface ProviderCredentialMutationPort {
    /**
     * 加密首次写入或轮换的候选 Secret。
     *
     * @param reference 已绑定引用；首次绑定时为空
     * @param expectedRevision 凭据期望版本
     * @param secret 临时明文字节；调用方负责清零
     * @return 必须关闭的密文候选
     */
    Prepared prepare(Optional<CredentialRef> reference, long expectedRevision, byte[] secret);

    /**
     * 创建清除候选。
     *
     * @param reference 当前凭据引用
     * @param expectedRevision 当前凭据版本
     * @return 必须关闭的候选
     */
    Prepared prepareClear(CredentialRef reference, long expectedRevision);

    /**
     * 仅在 Adapter 预构造期间公开候选密文的临时解析能力。
     *
     * @param candidate 密文候选
     * @param work 预构造工作
     * @param <T> 结果类型
     * @return 工作结果
     */
    <T> T expose(Prepared candidate, Supplier<T> work);

    /**
     * 在同一 H2 事务中提交 Vault、Provider 版本和幂等结果。
     *
     * @param identity 命令身份
     * @param candidate 密文候选
     * @param resultType 脱敏结果类型
     * @param work Provider 业务写入
     * @param <T> 脱敏结果类型
     * @return 已提交或恢复的结果
     */
    <T> T commit(CommandIdentity identity, Prepared candidate, Class<T> resultType, TransactionWork<T> work);

    /**
     * 在读取 Secret 前恢复已提交复合命令。
     *
     * @param identity 命令身份
     * @param resultType 脱敏结果类型
     * @param <T> 结果类型
     * @return 已提交结果
     */
    <T> Optional<T> recover(CommandIdentity identity, Class<T> resultType);

    /** 仅含密文或清除意图、且必须显式关闭的候选。 */
    interface Prepared extends AutoCloseable {
        /** @return 将被创建、轮换或清除的凭据元数据 */
        CredentialMetadata metadata();

        /** 丢弃候选并禁止再次提交。 */
        @Override
        void close();
    }

    /**
     * Vault 事务内的 Provider 状态写入。
     *
     * @param <T> 脱敏结果类型
     */
    @FunctionalInterface
    interface TransactionWork<T> {
        /**
         * 使用当前事务连接写入 Provider 状态。
         *
         * @param connection 仅在回调期间有效的连接
         * @return 脱敏结果
         * @throws Exception SQL 或业务校验失败
         */
        T commit(Connection connection) throws Exception;
    }
}
