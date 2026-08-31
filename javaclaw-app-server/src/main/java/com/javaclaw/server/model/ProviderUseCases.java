package com.javaclaw.server.model;

import java.util.List;
import java.util.Map;

import com.javaclaw.server.security.SecretStore;

/** Provider administration boundary consumed by protocol handlers. */
public interface ProviderUseCases {
    /** 列出 Provider 非敏感配置和凭据 metadata，不返回明文值。 */
    List<ProviderState> list();

    /** 按版本更新非敏感 Provider 配置并重载模型网关；API Key 必须单独通过凭据接口提交。 */
    ProviderState configure(
            String provider, Map<String, String> configuration, long expectedRevision, String idempotencyKey);

    /** 加密保存 Provider 凭据并刷新网关，返回 metadata；调用方仍负责清空原 value 数组。 */
    SecretStore.SecretMetadata setCredential(String provider, char[] value, String idempotencyKey);

    /** 按凭据版本清除配置并重载 Provider 状态；返回是否完成删除。 */
    boolean clearCredential(String provider, long expectedRevision, String idempotencyKey);
}
