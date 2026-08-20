package com.javaclaw.fixture;

import com.javaclaw.service.api.ServiceDescriptor;
import com.javaclaw.service.api.ServicePlugin;
import com.javaclaw.service.api.ServicePluginContext;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Test plugin that must be instantiated through the Runner's isolated class loader. */
public final class FixtureServicePlugin implements ServicePlugin {
    private boolean startCanLoadPlugin;

    @Override
    public void start(ServicePluginContext context) throws Exception {
        startCanLoadPlugin = contextLoaderCanLoadPlugin();
        context.internalServices().register(new ServiceDescriptor(
                "fixture", Set.of("echo", "large", "stream", "loader", "context-loader",
                        "host", "failure"), true), invocation -> {
            switch (invocation.operation()) {
                case "echo" -> invocation.responses().complete(
                        invocation.contentType(), invocation.payload());
                case "large" -> {
                    byte[] value = new byte[9 * 1024 * 1024];
                    Arrays.fill(value, (byte) 0x5a);
                    invocation.responses().complete("application/octet-stream", value);
                }
                case "stream" -> {
                    invocation.responses().event("text/plain", "event".getBytes(StandardCharsets.UTF_8));
                    invocation.responses().complete("text/plain", "done".getBytes(StandardCharsets.UTF_8));
                    invocation.responses().complete("text/plain", "duplicate".getBytes(StandardCharsets.UTF_8));
                }
                case "loader" -> invocation.responses().complete("text/plain",
                        getClass().getClassLoader().getClass().getName()
                                .getBytes(StandardCharsets.UTF_8));
                case "context-loader" -> {
                    boolean requestCanLoadPlugin = contextLoaderCanLoadPlugin();
                    boolean managedCanLoadPlugin = context.executor()
                            .call("context-loader", this::contextLoaderCanLoadPlugin)
                            .toCompletableFuture().get(5, TimeUnit.SECONDS);
                    invocation.responses().complete("text/plain",
                            (startCanLoadPlugin + ":" + requestCanLoadPlugin + ":" + managedCanLoadPlugin)
                                    .getBytes(StandardCharsets.UTF_8));
                }
                case "host" -> {
                    var response = context.desktopServices().invoke("host.fixture", "echo",
                                    invocation.contentType(), invocation.payload(),
                                    Duration.ofSeconds(5), ignored -> { })
                            .completion().get(5, TimeUnit.SECONDS);
                    invocation.responses().complete(response.contentType(), response.payload());
                }
                case "failure" -> throw new IllegalStateException("wrapper",
                        new IllegalArgumentException("specific model failure"));
                default -> invocation.responses().fail(
                        "unknown", "unknown operation", "application/json", new byte[0]);
            }
        });
    }

    @Override
    public void stop() { }

    private boolean contextLoaderCanLoadPlugin() throws ClassNotFoundException {
        return Thread.currentThread().getContextClassLoader().loadClass(getClass().getName()) == getClass();
    }
}
