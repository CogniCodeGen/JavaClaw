package com.javaclaw.server;

import java.util.Optional;

import com.javaclaw.nativehost.credential.MasterKeyProtectionException;
import com.javaclaw.nativehost.credential.MasterKeyProtector;

/** 健康检查与显式 ModelGateway 路径使用的 Vault 锁定实现。 */
final class LockedMasterKeyProtector implements MasterKeyProtector {
    @Override
    public Optional<byte[]> load(String keyId) {
        throw unavailable();
    }

    @Override
    public void store(String keyId, byte[] key) {
        throw unavailable();
    }

    @Override
    public void delete(String keyId) {
        throw unavailable();
    }

    private static MasterKeyProtectionException unavailable() {
        return new MasterKeyProtectionException("当前启动配置禁止访问系统凭据设施");
    }
}
