package com.javaclaw.browser.client;

import com.javaclaw.builtin.contracts.SiteRegistrationContracts;

/**
 * 登记私有结果的宿主密封回调；秘密只在回调期间有效，不得写入普通 DTO、日志或 Artifact。
 *
 * @param <T> 不包含秘密的持久提交回执
 */
@FunctionalInterface
public interface BrowserRegistrationHandler<T> {
    /**
     * @param status 与用户确认代次一致的脱敏页面状态
     * @param storageState 含 IndexedDB 的状态字节，调用方在返回后清零
     * @param credentials UTF-8 用户名、单个 NUL 与密码；未选择候选时为空，返回后清零
     * @return 已持久提交的非敏感回执
     * @throws Exception 密封或事务提交失败
     */
    T handle(SiteRegistrationContracts.WorkerStatus status, byte[] storageState, byte[] credentials) throws Exception;
}
