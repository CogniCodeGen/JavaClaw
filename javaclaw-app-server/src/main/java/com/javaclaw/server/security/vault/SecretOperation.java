package com.javaclaw.server.security.vault;

/**
 * 只在同步调用期间使用 Secret 明文字节的受信操作。
 *
 * @param <T> 操作结果类型
 */
@FunctionalInterface
public interface SecretOperation<T> {
    /**
     * 使用临时明文字节；实现不得保留数组引用。
     *
     * @param secret 只在本次调用期间有效的 Secret 字节
     * @return 操作结果
     * @throws Exception 下游操作失败
     */
    T use(byte[] secret) throws Exception;
}
