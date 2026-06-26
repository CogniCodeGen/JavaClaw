package com.javaclaw.plugin.api;

/**
 * 插件自有配置只读视图 —— 插件经 {@code PluginContext.config()} 读取自己在 {@code plugin.json}
 * 声明、并由用户在插件中心填写的配置项（如飞书 appId/appSecret）。
 *
 * <p>secret 字段由宿主加密存储，读取时已解密。插件不可写配置（写由宿主 UI 负责）。</p>
 *
 * @author JavaClaw
 */
public interface PluginConfig {

    /**
     * 读取配置项。
     *
     * @param key 配置键（须在 plugin.json 的 config 中声明）
     * @return 配置值；未配置时返回空串
     */
    String get(String key);

    /**
     * 读取配置项，缺省回退。
     *
     * @param key          配置键
     * @param defaultValue 缺省值
     * @return 配置值；为空时返回缺省值
     */
    String get(String key, String defaultValue);
}
