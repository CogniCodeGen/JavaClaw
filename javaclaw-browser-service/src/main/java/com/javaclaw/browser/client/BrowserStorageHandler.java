package com.javaclaw.browser.client;

/**
 * 在 Browser Worker 私有结果帧仍受宿主管控时消费 storage state。
 *
 * <p>实现不得缓存、记录、编码或返回传入数组；调用结束后宿主会立即覆写该数组。该回调用于把状态直接密封进 Vault，而不是把 Cookie 暴露给 RPC 或扩展代码。
 *
 * @param <T> 非敏感处理结果
 */
@FunctionalInterface
public interface BrowserStorageHandler<T> {
    /**
     * 消费短生命周期 storage state。
     *
     * @param storageState Worker 返回的原始 JSON 字节，仅在回调期间有效
     * @return 不含 Secret 的结果
     * @throws Exception Vault 或事务写入失败
     */
    T handle(byte[] storageState) throws Exception;
}
