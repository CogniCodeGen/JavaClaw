package com.javaclaw.server.configuration;

import java.util.Map;

/**
 * Validated server configuration values encoded as canonical JSON.
 *
 * @param values 配置键到 JSON 文本的快照；null 归一为空 Map，不允许存放敏感值
 * @param revision 当前进程内单调递增的配置修订
 */
public record ConfigurationState(Map<String, String> values, long revision) {
    /** 创建初始修订的兼容配置快照。 */
    public ConfigurationState(Map<String, String> values) {
        this(values, 0);
    }

    /** 复制配置键值，保持协议层之外的只读配置视图。 */
    public ConfigurationState {
        values = Map.copyOf(values);
        if (revision < 0) {
            throw new IllegalArgumentException("configuration revision cannot be negative");
        }
    }
}
