package com.javaclaw.plugin;

import com.javaclaw.plugin.api.Capability;
import com.javaclaw.plugin.api.PluginException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 能力鉴权卫士 —— 每个能力实现的入口统一调用 {@link #require(Capability)} 做授权校验 + 审计。
 *
 * <p>调用者身份经 {@link PluginScope#CURRENT}（ScopedValue）自动传播，无须显式传 pluginId。
 * 鉴权与审计因此天然跟随调用链：插件后台虚拟线程里发起 CHAT、CHAT 内部再访问其他能力，
 * 身份在整条链上始终可见。</p>
 *
 * @author JavaClaw
 */
public final class CapabilityGuard {

    private static final Logger log = LoggerFactory.getLogger(CapabilityGuard.class);

    private CapabilityGuard() {
    }

    /**
     * 校验当前插件是否被授予指定能力；通过则记审计日志，否则抛未授权异常。
     *
     * @param capability 待校验能力
     * @throws PluginException.CapabilityNotGrantedException 未在作用域内（非插件上下文调用）或未授权
     */
    public static void require(Capability capability) {
        if (!PluginScope.CURRENT.isBound()) {
            // 非插件执行上下文却调到了能力实现——属编程错误，拒绝并告警
            log.warn("能力 {} 在非插件作用域内被调用，已拒绝", capability);
            throw new PluginException.CapabilityNotGrantedException("<unknown>", capability);
        }
        PluginScope.PluginIdentity who = PluginScope.CURRENT.get();
        if (!who.granted().contains(capability)) {
            log.warn("插件[{}]尝试调用未授权能力：{}", who.id(), capability);
            throw new PluginException.CapabilityNotGrantedException(who.id(), capability);
        }
        log.debug("插件[{}]调用能力：{}", who.id(), capability);
    }
}
