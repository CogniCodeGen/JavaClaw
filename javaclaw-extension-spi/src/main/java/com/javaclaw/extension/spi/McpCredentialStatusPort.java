package com.javaclaw.extension.spi;

import com.javaclaw.api.CredentialRef;

/** MCP 执行前检查 Vault 引用实时有效性的边界。 */
@FunctionalInterface
public interface McpCredentialStatusPort {
    /**
     * 检查 Secret 引用当前是否可以使用。
     *
     * @param reference 不含明文的 Vault 引用
     * @return Vault 已解锁且引用存在时为 true
     */
    boolean available(CredentialRef reference);
}
