package com.javaclaw.server.persistence;

import com.javaclaw.api.CredentialRef;

/** Provider 调用前实时检查 Secret 引用是否可用的窄安全端口。 */
@FunctionalInterface
public interface CredentialAvailabilityPort {
    /**
     * 检查 Vault 已解锁且引用仍存在。
     *
     * @param reference 不含明文的 Secret 引用
     * @return 当前调用可以使用时为 true
     */
    boolean available(CredentialRef reference);
}
