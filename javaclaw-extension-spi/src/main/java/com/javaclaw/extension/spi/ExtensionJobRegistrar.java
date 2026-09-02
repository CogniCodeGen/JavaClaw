package com.javaclaw.extension.spi;

/** 平台接收内置扩展可恢复 Job executor 的注册端口。 */
public interface ExtensionJobRegistrar {
    /**
     * 注册一类执行器；相同扩展和 Job 类型只能注册一次。
     *
     * @param extensionId 所有者扩展
     * @param registration Job 类型和执行器
     */
    void register(ExtensionId extensionId, ExtensionJobRegistration registration);
}
