package com.javaclaw.plugin;

import com.javaclaw.plugin.api.Capability;

import java.util.Set;

/**
 * 插件身份的作用域传播 —— 基于 JDK 25 正式版 {@link ScopedValue}。
 *
 * <p>宿主在派发插件的每一段执行（任务/后台循环/同步调用）前，把当前插件身份绑入
 * {@link #CURRENT} 作用域；能力实现经 {@code CapabilityGuard} 零参数即可取回"谁在调用"，
 * 用于鉴权与审计，无须将 pluginId 一路透传。</p>
 *
 * <p>相较 {@code ThreadLocal}：{@link ScopedValue} 不可变、作用域结束即失效、自动随结构化
 * 执行传播，契合"一任务一虚拟线程"，且插件无法篡改自身身份（拿不到写接口）。</p>
 *
 * @author JavaClaw
 */
public final class PluginScope {

    /** 当前正在执行的插件身份；仅在宿主派发的执行作用域内有界 */
    public static final ScopedValue<PluginIdentity> CURRENT = ScopedValue.newInstance();

    private PluginScope() {
    }

    /**
     * 插件身份：标识 + 已授权能力集合。供鉴权判定使用。
     *
     * @param id      插件 id
     * @param granted 已授权能力集合
     */
    public record PluginIdentity(String id, Set<Capability> granted) {
        public PluginIdentity {
            granted = granted == null ? Set.of() : Set.copyOf(granted);
        }
    }
}
