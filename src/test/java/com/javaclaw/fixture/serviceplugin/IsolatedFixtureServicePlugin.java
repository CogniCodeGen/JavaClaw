package com.javaclaw.fixture.serviceplugin;

import com.javaclaw.service.api.Registration;
import com.javaclaw.service.api.ServiceDescriptor;
import com.javaclaw.service.api.ServiceInvocation;
import com.javaclaw.service.api.ServicePlugin;
import com.javaclaw.service.api.ServicePluginContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Test fixture packaged into a JAR so it is visible only to the child JVM plugin loader. */
public final class IsolatedFixtureServicePlugin implements ServicePlugin {
    private final List<Registration> registrations = new ArrayList<>();

    @Override
    public void start(ServicePluginContext context) {
        registrations.add(context.internalServices().register(
                new ServiceDescriptor("fixture/echo", Set.of("echo"), false), this::echo));
        registrations.add(context.internalServices().register(
                new ServiceDescriptor("deliverance/unload", Set.of("unload"), false), invocation -> {
                    throw new IllegalStateException("model still has active requests");
                }));
    }

    private void echo(ServiceInvocation invocation) {
        invocation.responses().complete("text/plain",
                new String(invocation.payload(), StandardCharsets.UTF_8)
                        .getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void stop() {
        for (int index = registrations.size() - 1; index >= 0; index--) {
            registrations.get(index).close();
        }
        registrations.clear();
    }
}
