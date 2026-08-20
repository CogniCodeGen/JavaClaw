package com.javaclaw.infrastructure.serviceplugin;

import com.javaclaw.application.serviceplugin.ServicePluginHostServiceRouter;
import com.javaclaw.application.serviceplugin.ServicePluginHostServiceRouter.HostServiceException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServicePluginHostServiceRegistryTest {

    @Test
    void routesOnlyRegisteredOperationsWithDeclaredPermission() throws Exception {
        ServicePluginHostServiceRegistry registry = new ServicePluginHostServiceRegistry();
        registry.register("desktop.echo", Set.of("echo"), "desktop.echo", (request, cancelled, events) -> {
            events.accept(new ServicePluginHostServiceRouter.Event("text/plain", new byte[]{1}));
            return new ServicePluginHostServiceRouter.Response(request.contentType(), request.payload());
        });
        List<ServicePluginHostServiceRouter.Event> events = new ArrayList<>();
        byte[] payload = new byte[]{4, 2};

        ServicePluginHostServiceRouter.Response response = registry.invoke(request(
                Set.of("desktop.echo"), "desktop.echo", "echo", payload), () -> false, events::add);

        assertArrayEquals(payload, response.payload());
        assertEquals(1, events.size());
        assertEquals("host_permission_denied", assertThrows(HostServiceException.class,
                () -> registry.invoke(request(Set.of(), "desktop.echo", "echo", payload),
                        () -> false, ignored -> { })).code());
        assertEquals("host_service_not_found", assertThrows(HostServiceException.class,
                () -> registry.invoke(request(Set.of("desktop.echo"), "desktop.echo", "delete", payload),
                        () -> false, ignored -> { })).code());
    }

    @Test
    void rejectsExpiredOrCancelledCallsBeforeHandlerRuns() throws Exception {
        ServicePluginHostServiceRegistry registry = new ServicePluginHostServiceRegistry();
        registry.register("desktop.echo", Set.of(), "", (request, cancelled, events) ->
                new ServicePluginHostServiceRouter.Response("text/plain", request.payload()));

        var expired = new ServicePluginHostServiceRouter.Request("plugin", Set.of(), "request",
                "desktop.echo", "echo", "text/plain", new byte[0], Instant.now().minusSeconds(1));
        assertEquals("host_deadline_exceeded", assertThrows(HostServiceException.class,
                () -> registry.invoke(expired, () -> false, ignored -> { })).code());
        assertEquals("cancelled", assertThrows(HostServiceException.class,
                () -> registry.invoke(request(Set.of(), "desktop.echo", "echo", new byte[0]),
                        () -> true, ignored -> { })).code());
    }

    private static ServicePluginHostServiceRouter.Request request(
            Set<String> permissions, String service, String operation, byte[] payload) {
        return new ServicePluginHostServiceRouter.Request("plugin", permissions, "request",
                service, operation, "application/octet-stream", payload,
                Instant.now().plusSeconds(10));
    }
}
