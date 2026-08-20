package com.javaclaw.infrastructure.serviceplugin;

import com.javaclaw.application.serviceplugin.ServicePluginHostServiceRouter;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Root-owned allowlist for byte-only service-plugin calls into Desktop. */
public final class ServicePluginHostServiceRegistry implements ServicePluginHostServiceRouter {
    private final Map<String, Registration> services = new ConcurrentHashMap<>();

    public AutoCloseable register(String serviceId, Set<String> operations,
                                  String requiredPermission, Handler handler) {
        if (serviceId == null || serviceId.isBlank()) throw new IllegalArgumentException("serviceId 不能为空");
        Registration registration = new Registration(serviceId, operations, requiredPermission, handler);
        if (services.putIfAbsent(serviceId, registration) != null) {
            throw new IllegalStateException("Desktop 服务已注册: " + serviceId);
        }
        return () -> services.remove(serviceId, registration);
    }

    @Override
    public Response invoke(Request request, BooleanSupplier cancellation, Consumer<Event> events)
            throws Exception {
        Registration registration = services.get(request.serviceId());
        if (registration == null || (!registration.operations().isEmpty()
                && !registration.operations().contains(request.operation()))) {
            throw new HostServiceException("host_service_not_found", "Desktop 未注册该服务操作");
        }
        if (!registration.requiredPermission().isBlank()
                && !request.grantedPermissions().contains(registration.requiredPermission())) {
            throw new HostServiceException("host_permission_denied", "服务插件没有调用该 Desktop 能力的权限");
        }
        if (!Instant.MAX.equals(request.deadline()) && !Instant.now().isBefore(request.deadline())) {
            throw new HostServiceException("host_deadline_exceeded", "Desktop 服务请求已超时");
        }
        if (cancellation.getAsBoolean()) {
            throw new HostServiceException("cancelled", "Desktop 服务请求已取消");
        }
        return registration.handler().handle(request, cancellation, events);
    }

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request, BooleanSupplier cancellation, Consumer<Event> events)
                throws Exception;
    }

    private record Registration(String id, Set<String> operations, String requiredPermission,
                                Handler handler) {
        private Registration {
            operations = operations == null ? Set.of() : Set.copyOf(operations);
            requiredPermission = requiredPermission == null ? "" : requiredPermission.strip();
            handler = java.util.Objects.requireNonNull(handler, "handler");
        }
    }
}
