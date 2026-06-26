package com.javaclaw.plugin.api;

/**
 * 插件相关异常基类。能力未授权、执行失败等具体场景由子类细分。
 *
 * @author JavaClaw
 */
public class PluginException extends RuntimeException {

    public PluginException(String message) {
        super(message);
    }

    public PluginException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 能力未授权 —— 插件调用了一个未在 {@code plugin.json} 声明、或宿主未授予的能力。
     */
    public static final class CapabilityNotGrantedException extends PluginException {
        public CapabilityNotGrantedException(String pluginId, Capability capability) {
            super("插件[" + pluginId + "]未被授予能力：" + capability.displayName()
                    + "（" + capability + "），请在 plugin.json 的 capabilities 中声明");
        }
    }

    /**
     * 执行失败 —— 同步调用（{@code PluginExecutor.call}）中任务抛出异常时的包装。
     */
    public static final class PluginExecException extends PluginException {
        public PluginExecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
