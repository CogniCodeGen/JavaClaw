package com.javaclaw.application.settings;

import com.javaclaw.application.settings.ModelDiscoveryApplicationService.DiscoveryRequest;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService.DiscoveryResult;

/** 提供商模型目录的阻塞式读取端口。实现必须响应中断且不缓存密钥或列表。 */
public interface ModelDiscoveryPort {
    DiscoveryResult discover(DiscoveryRequest request) throws InterruptedException;
}
