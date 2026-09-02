package com.javaclaw.extension.spi;

import java.util.Optional;

/** 内置扩展使用的平台托管事务入口。 */
public interface ManagedExtensionStore {
    /**
     * 在 Core Item、Event 与 Outbox 可共同提交的事务中执行工作。
     *
     * <p>事务对象只在回调期间有效，不得跨线程或缓存。异常会回滚全部修改。
     *
     * @param extensionId schema 所有者
     * @param work 事务工作
     * @param <T> 返回类型
     * @return 回调结果
     * @throws Exception 回调或提交失败
     */
    <T> T inTransaction(ExtensionId extensionId, TransactionWork<T> work) throws Exception;

    /**
     * 在扩展状态同一事务中执行可恢复的幂等命令。
     *
     * <p>相同键、操作和摘要返回首次提交结果；相同键绑定不同请求时失败。平台不会在崩溃恢复后重复业务副作用。
     *
     * @param extensionId schema 所有者
     * @param operation 扩展操作
     * @param idempotencyKey 全局幂等键
     * @param requestDigest 请求 SHA-256
     * @param work 首次执行的事务工作
     * @return 首次提交或恢复的响应
     * @throws Exception 回调或提交失败
     */
    ExtensionResponse inCommand(
            ExtensionId extensionId,
            String operation,
            String idempotencyKey,
            String requestDigest,
            TransactionWork<ExtensionResponse> work)
            throws Exception;

    /**
     * 查询并校验已提交的命令结果，不执行新工作。
     *
     * <p>同一幂等键绑定其他操作或摘要时必须失败，不能伪装成未命中。
     *
     * @param extensionId schema 所有者
     * @param operation 扩展操作
     * @param idempotencyKey 全局幂等键
     * @param requestDigest 请求 SHA-256
     * @return 已提交响应；首次调用为空
     * @throws Exception 查询失败或幂等身份冲突
     */
    Optional<ExtensionResponse> recoverCommand(
            ExtensionId extensionId, String operation, String idempotencyKey, String requestDigest) throws Exception;

    /**
     * 事务回调。
     *
     * @param <T> 回调结果类型
     */
    @FunctionalInterface
    interface TransactionWork<T> {
        /**
         * 在当前事务内执行。
         *
         * @param transaction 受限键值与事件事务端口
         * @return 工作结果
         * @throws Exception 业务失败
         */
        T execute(ExtensionTransaction transaction) throws Exception;
    }
}
