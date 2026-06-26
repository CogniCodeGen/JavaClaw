package com.javaclaw.plugin;

import com.javaclaw.plugin.api.PluginConfig;

import java.util.Map;

/**
 * 插件配置只读视图实现 —— P1 以内存键值表承载（用户在插件中心填写的值，由 PluginManager 注入）。
 *
 * <p>P2 将接通工作区维度持久化（{@code data/plugins.json}）与 secret 字段加解密。</p>
 *
 * @author JavaClaw
 */
final class PluginConfigImpl implements PluginConfig {

    private final Map<String, String> values;

    PluginConfigImpl(Map<String, String> values) {
        this.values = values == null ? Map.of() : Map.copyOf(values);
    }

    @Override
    public String get(String key) {
        return get(key, "");
    }

    @Override
    public String get(String key, String defaultValue) {
        String v = values.get(key);
        return (v == null || v.isEmpty()) ? defaultValue : v;
    }
}
