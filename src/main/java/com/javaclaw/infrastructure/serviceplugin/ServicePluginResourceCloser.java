package com.javaclaw.infrastructure.serviceplugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Best-effort cleanup that records failures instead of silently discarding them. */
final class ServicePluginResourceCloser {
    private static final Logger log = LoggerFactory.getLogger(ServicePluginResourceCloser.class);

    private ServicePluginResourceCloser() { }

    static void close(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception failure) {
            log.debug("关闭服务插件资源失败: {}", resource.getClass().getSimpleName(), failure);
        }
    }

    static void destroy(ProcessHandle process) {
        try {
            process.destroyForcibly();
        } catch (RuntimeException failure) {
            log.debug("终止陈旧服务插件子进程失败: pid={}", process.pid(), failure);
        }
    }
}
