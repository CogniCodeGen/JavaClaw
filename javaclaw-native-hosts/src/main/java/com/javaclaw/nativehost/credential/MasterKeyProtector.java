package com.javaclaw.nativehost.credential;

import java.util.Optional;

/**
 * JavaClaw Vault 主密钥的持久化端口；系统设施及本地数据库实现共享相同所有权契约。
 *
 * <p>实现不得把明文密钥放入命令行、日志或异常消息。调用方拥有返回数组，使用结束后必须清零。
 */
public interface MasterKeyProtector {
    /**
     * 读取已经保存的主密钥。
     *
     * @param keyId 安装内稳定且不含路径的密钥标识
     * @return 主密钥副本；尚未创建时为空
     */
    Optional<byte[]> load(String keyId);

    /**
     * 创建或替换主密钥。
     *
     * @param keyId 安装内稳定且不含路径的密钥标识
     * @param key 主密钥；实现必须复制且不得取得数组所有权
     */
    void store(String keyId, byte[] key);

    /**
     * 删除当前实现保存的主密钥；不得扩散到其他存储后端。
     *
     * @param keyId 安装内稳定且不含路径的密钥标识
     */
    void delete(String keyId);
}
