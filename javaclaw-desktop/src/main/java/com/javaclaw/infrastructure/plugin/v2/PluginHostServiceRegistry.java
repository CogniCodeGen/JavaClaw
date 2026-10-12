package com.javaclaw.infrastructure.plugin.v2;

import com.javaclaw.application.plugin.v2.PluginManagementService.PluginException;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 宿主显式注册的回调目录；业务处理器只收到经过认证的身份与权限。 */
public final class PluginHostServiceRegistry {
    private final Map<String, Registration> services = new ConcurrentHashMap<>();

    public AutoCloseable register(String serviceId, Set<String> operations,
                                  String requiredPermission, Handler handler) {
        return register(serviceId, operations, requiredPermission, false, handler);
    }

    public AutoCloseable register(String serviceId, Set<String> operations,
                                  String requiredPermission, boolean allowBackground, Handler handler) {
        if (serviceId == null || serviceId.isBlank() || operations == null || operations.isEmpty()
                || requiredPermission == null || requiredPermission.isBlank() || handler == null) {
            throw new IllegalArgumentException("宿主服务必须明确名称、操作、权限与处理器");
        }
        Registration registration = new Registration(Set.copyOf(operations), requiredPermission, allowBackground, handler);
        if (services.putIfAbsent(serviceId, registration) != null) throw new IllegalArgumentException("宿主服务已注册: " + serviceId);
        return () -> services.remove(serviceId, registration);
    }

    boolean allowsBackground(String serviceId, String operation) {
        Registration registration = services.get(serviceId);
        return registration != null && registration.background && registration.operations.contains(operation);
    }

    String invoke(String serviceId, HostRequest request, BooleanSupplier cancelled, Consumer<String> events) throws Exception {
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("宿主回调租约已失效");
        Registration registration = services.get(serviceId);
        if (registration == null || !registration.operations.contains(request.operation())) throw new PluginException("HOST_OPERATION_DENIED", "未注册的宿主操作");
        if (!request.grantedPermissions().contains(registration.permission)) throw new PluginException("PERMISSION_DENIED", "插件没有宿主操作权限");
        return registration.handler.invoke(request, cancelled, events);
    }

    @FunctionalInterface
    public interface Handler {
        String invoke(HostRequest request, BooleanSupplier cancelled, Consumer<String> events) throws Exception;
    }

    public record HostRequest(String workspaceId, String pluginId, String instanceId,
                              String runId, String requestId, String operation,
                              String payloadJson, Set<String> grantedPermissions, Instant deadline, boolean background) {
        public HostRequest { grantedPermissions = Set.copyOf(grantedPermissions); }
    }

    private record Registration(Set<String> operations, String permission, boolean background, Handler handler) { }
}
