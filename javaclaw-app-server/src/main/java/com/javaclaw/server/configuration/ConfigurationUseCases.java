package com.javaclaw.server.configuration;

import java.util.Map;
import java.util.Set;

/** Non-secret process configuration boundary consumed by protocol handlers. */
public interface ConfigurationUseCases {
    /** 读取有大小限制的非敏感配置快照；凭据必须通过 SecretStore metadata 查询。 */
    ConfigurationState read();

    /** 按 values 合并、removals 删除配置键；先校验单项及总大小，再提交，拒绝敏感键。 */
    ConfigurationState update(Map<String, String> values, Set<String> removals);
}
