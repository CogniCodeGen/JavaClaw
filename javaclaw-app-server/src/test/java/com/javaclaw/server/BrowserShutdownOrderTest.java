package com.javaclaw.server;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.javaclaw.server.extension.contract.ExtensionHost;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserShutdownOrderTest {
    @Test
    void 浏览器保存登录态时Vault仍开放且Worker关闭失败不跳过Vault关闭() {
        AtomicBoolean vaultClosed = new AtomicBoolean();
        AtomicBoolean browserClosed = new AtomicBoolean();
        AutoCloseable noOperation = () -> {};
        ExtensionHost extensions = (ExtensionHost) Proxy.newProxyInstance(
                ExtensionHost.class.getClassLoader(),
                new Class<?>[] {ExtensionHost.class},
                (proxy, method, arguments) -> null);
        AutoCloseable vault = () -> vaultClosed.set(true);
        AutoCloseable browser = () -> {
            assertFalse(vaultClosed.get());
            browserClosed.set(true);
            throw new IllegalStateException("模拟 Worker 关闭失败");
        };
        var resources = new AppServerResources(
                noOperation,
                noOperation,
                noOperation,
                noOperation,
                noOperation,
                noOperation,
                noOperation,
                extensions,
                noOperation,
                noOperation,
                noOperation,
                vault,
                browser);
        assertThrows(IllegalStateException.class, resources::close);
        assertTrue(browserClosed.get());
        assertTrue(vaultClosed.get());
    }
}
