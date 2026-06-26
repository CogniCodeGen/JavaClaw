package com.javaclaw.plugin.api;

import java.util.List;
import java.util.Set;

/**
 * 插件描述符 —— 对应插件 jar 根目录下的 {@code plugin.json}，是宿主认识一个插件的全部元信息。
 *
 * <p>由 {@code PluginDescriptorLoader} 从 jar 内解析得到。{@link #capabilities()} 即插件声明
 * 需要的能力清单（= 权限申请），宿主据此在运行期装配 {@code PluginContext}。</p>
 *
 * @param id           插件唯一标识（小写短横线，如 {@code feishu-listener}），同时作日志/线程命名前缀
 * @param name         显示名称（中文友好）
 * @param version      插件版本（语义化，如 {@code 1.0.0}）
 * @param apiVersion   插件编译所依赖的 plugin-api 主版本，加载时与宿主比对兼容性
 * @param mainClass    插件入口类全限定名，须实现 {@link JavaClawPlugin}
 * @param description  插件用途简述
 * @param capabilities 声明所需能力集合（空集表示不申请任何宿主能力）
 * @param config       插件自有配置项声明（宿主据此渲染配置表单、加密 secret 项）
 * @author JavaClaw
 */
public record PluginDescriptor(
        String id,
        String name,
        String version,
        String apiVersion,
        String mainClass,
        String description,
        Set<Capability> capabilities,
        List<ConfigField> config) {

    /** 当前宿主支持的 plugin-api 主版本号；插件 {@link #apiVersion()} 不匹配则拒载 */
    public static final String HOST_API_VERSION = "1.0";

    /** 防御性拷贝 + 空值兜底，保证不可变与非空集合 */
    public PluginDescriptor {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        config = config == null ? List.of() : List.copyOf(config);
    }

    /**
     * 插件自有配置项声明。
     *
     * @param key    配置键
     * @param label  中文标签（UI 显示）
     * @param secret 是否敏感（true 则宿主加密存储、UI 以密码框呈现）
     */
    public record ConfigField(String key, String label, boolean secret) {
    }
}
