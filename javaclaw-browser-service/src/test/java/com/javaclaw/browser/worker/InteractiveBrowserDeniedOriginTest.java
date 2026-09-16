package com.javaclaw.browser.worker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.Test;

import com.javaclaw.browser.protocol.BrowserFrameIo;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class InteractiveBrowserDeniedOriginTest {
    @Test
    void 未授权子资源只发无敏感内容的通知并阻断且同代只提示一次() throws Exception {
        var json = new CanonicalJson();
        var output = new ByteArrayOutputStream();
        var input = new PipedInputStream();
        var lease = new AtomicReference<>(new BrowserContracts.AccessLease(
                BrowserContracts.ControlMode.ASSISTANT,
                "lease",
                1,
                Instant.now().plusSeconds(60),
                Set.of(URI.create("https://docs.example.com"))));
        AtomicInteger aborts = new AtomicInteger();
        AtomicReference<Consumer<Route>> route = new AtomicReference<>();
        try (var writer = new PipedOutputStream(input);
                var connection = new InteractiveWorkerConnection(input, output, json)) {
            var network = new InteractiveBrowserNetwork(connection, lease::get);
            network.configure(context(route));
            route.get().accept(resource(aborts));
            route.get().accept(resource(aborts));
            int bytes = output.size();
            var packet = BrowserFrameIo.readJson(
                    new ByteArrayInputStream(output.toByteArray()), json, InteractiveBrowserProtocol.Frame.class);
            assertEquals(InteractiveBrowserProtocol.Kind.DENIED_ORIGIN, packet.kind());
            assertEquals(0, packet.binaryBytes());
            assertEquals(
                    URI.create("https://asset.example.com"),
                    json.decode(packet.payload(), InteractiveBrowserProtocol.OriginNotice.class)
                            .origin());
            assertFalse(packet.payload().json().contains("secret"));
            lease.set(new BrowserContracts.AccessLease(
                    BrowserContracts.ControlMode.NONE, "none", 2, Instant.now(), Set.of()));
            route.get().accept(resource(aborts));
            assertEquals(bytes, output.size(), "无租约不能从后台继续生成授权通知或网络请求");
            assertEquals(3, aborts.get());
        }
    }

    @SuppressWarnings("unchecked")
    private static BrowserContext context(AtomicReference<Consumer<Route>> route) {
        return proxy(BrowserContext.class, (target, method, args) -> {
            if (method.getName().equals("route")) {
                route.set((Consumer<Route>) args[1]);
            }
            return null;
        });
    }

    private static Route resource(AtomicInteger aborts) {
        Request request = proxy(Request.class, (target, method, args) -> switch (method.getName()) {
            case "url" -> "https://asset.example.com/path?token=secret";
            case "postDataBuffer" -> new byte[] {1, 2, 3};
            default -> null;
        });
        return proxy(Route.class, (target, method, args) -> switch (method.getName()) {
            case "request" -> request;
            case "abort" -> {
                aborts.incrementAndGet();
                yield null;
            }
            default -> throw new AssertionError("未授权请求不能执行 " + method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
