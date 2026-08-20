package com.javaclaw.infrastructure.serviceplugin;

import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.EndpointConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;
import com.javaclaw.platform.system.SystemMemoryProbe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Global admission ledger. It is a protection budget, not an OS/container hard quota. */
public final class ServicePluginResourceBudget {
    public static final long DEFAULT_SOFT_MEMORY_MIB = 18L * 1024;
    public static final long DEFAULT_HARD_MEMORY_MIB = 21L * 1024;
    public static final int DEFAULT_COMPUTE_THREADS = 8;
    public static final int DEFAULT_CONNECTIONS = 256;
    public static final int DEFAULT_ENDPOINTS = 16;

    private final Limits limits;
    private final MemoryPressureProbe pressure;
    private final Map<String, Reservation> reservations = new LinkedHashMap<>();

    public ServicePluginResourceBudget() {
        this(Limits.defaults(), ServicePluginResourceBudget::systemMemoryPressure);
    }

    ServicePluginResourceBudget(Limits limits, MemoryPressureProbe pressure) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
        this.pressure = java.util.Objects.requireNonNull(pressure, "pressure");
    }

    public synchronized Lease reserve(
            String pluginId, ResourceConfiguration resources, List<EndpointConfiguration> endpoints) {
        if (reservations.containsKey(pluginId)) {
            throw new IllegalStateException("服务插件已占用资源预算: " + pluginId);
        }
        Reservation requested = Reservation.of(resources, endpoints);
        Snapshot current = snapshotInternal();
        long memory = Math.addExact(current.memoryMiB(), requested.memoryMiB());
        int threads = Math.addExact(current.computeThreads(), requested.computeThreads());
        int connections = Math.addExact(current.connections(), requested.connections());
        int endpointCount = Math.addExact(current.endpoints(), requested.endpoints());
        int fileDescriptors = Math.addExact(current.fileDescriptors(), requested.fileDescriptors());
        if (memory > limits.hardMemoryMiB()) {
            throw exhausted("服务插件内存预留超过全局硬保护线", memory, limits.hardMemoryMiB());
        }
        if (memory > limits.softMemoryMiB() && pressure.severe()) {
            throw exhausted("系统处于内存压力且超过服务插件软预算", memory, limits.softMemoryMiB());
        }
        if (threads > limits.computeThreads()) {
            throw exhausted("服务插件计算线程超过全局预算", threads, limits.computeThreads());
        }
        if (connections > limits.connections()) {
            throw exhausted("服务插件外部连接超过全局预算", connections, limits.connections());
        }
        if (endpointCount > limits.endpoints()) {
            throw exhausted("服务插件监听端点超过全局预算", endpointCount, limits.endpoints());
        }
        if (fileDescriptors > limits.fileDescriptors()) {
            throw exhausted("服务插件文件描述符超过全局预算", fileDescriptors, limits.fileDescriptors());
        }
        reservations.put(pluginId, requested);
        return new Lease(this, pluginId, requested);
    }

    public synchronized Snapshot snapshot() { return snapshotInternal(); }

    private Snapshot snapshotInternal() {
        long memory = 0;
        int compute = 0;
        int connections = 0;
        int endpoints = 0;
        int descriptors = 0;
        for (Reservation value : reservations.values()) {
            memory = Math.addExact(memory, value.memoryMiB());
            compute = Math.addExact(compute, value.computeThreads());
            connections = Math.addExact(connections, value.connections());
            endpoints = Math.addExact(endpoints, value.endpoints());
            descriptors = Math.addExact(descriptors, value.fileDescriptors());
        }
        return new Snapshot(memory, compute, connections, endpoints, descriptors,
                Map.copyOf(reservations));
    }

    private synchronized void release(String pluginId, Reservation reservation) {
        reservations.remove(pluginId, reservation);
    }

    private static ResourceExhaustedException exhausted(String message, long actual, long limit) {
        return new ResourceExhaustedException(message + "（需要 " + actual + "，上限 " + limit + "）");
    }

    private static boolean systemMemoryPressure() {
        return isSevereMemoryPressure(SystemMemoryProbe.read());
    }

    static boolean isSevereMemoryPressure(SystemMemoryProbe.Snapshot memory) {
        if (memory == null || !memory.availableReliable() || memory.totalBytes() <= 0) return false;
        long threshold = memory.totalBytes() / 10
                + (memory.totalBytes() % 10 == 0 ? 0 : 1);
        return memory.availableBytes() < threshold;
    }

    public record Limits(long softMemoryMiB, long hardMemoryMiB, int computeThreads,
                         int connections, int endpoints, int fileDescriptors) {
        public Limits {
            if (softMemoryMiB < 256 || hardMemoryMiB < softMemoryMiB
                    || computeThreads < 1 || connections < 1 || endpoints < 1
                    || fileDescriptors < 64) {
                throw new IllegalArgumentException("服务插件资源预算无效");
            }
        }

        public static Limits defaults() {
            return new Limits(DEFAULT_SOFT_MEMORY_MIB, DEFAULT_HARD_MEMORY_MIB,
                    DEFAULT_COMPUTE_THREADS, DEFAULT_CONNECTIONS, DEFAULT_ENDPOINTS, 4096);
        }
    }

    public record Reservation(long memoryMiB, int computeThreads, int connections,
                              int endpoints, int fileDescriptors) {
        static Reservation of(ResourceConfiguration resources, List<EndpointConfiguration> endpoints) {
            ResourceConfiguration checked = resources == null
                    ? new ResourceConfiguration(256, 0, 1, 64, 64) : resources;
            List<EndpointConfiguration> checkedEndpoints = endpoints == null ? List.of() : endpoints;
            int endpointCount = Math.max(1, checkedEndpoints.size());
            int connections = Math.max(1, checkedEndpoints.stream()
                    .mapToInt(EndpointConfiguration::maxConnections).sum());
            return new Reservation(Math.max(256, checked.reservedMemoryMiB()),
                    Math.max(1, checked.computeThreads()), connections,
                    endpointCount, Math.max(64, checked.fileDescriptors()));
        }
    }

    public record Snapshot(long memoryMiB, int computeThreads, int connections,
                           int endpoints, int fileDescriptors,
                           Map<String, Reservation> reservations) { }

    public static final class Lease implements AutoCloseable {
        private ServicePluginResourceBudget owner;
        private final String pluginId;
        private final Reservation reservation;

        private Lease(ServicePluginResourceBudget owner, String pluginId, Reservation reservation) {
            this.owner = owner;
            this.pluginId = pluginId;
            this.reservation = reservation;
        }

        public Reservation reservation() { return reservation; }

        @Override public synchronized void close() {
            ServicePluginResourceBudget current = owner;
            owner = null;
            if (current != null) current.release(pluginId, reservation);
        }
    }

    @FunctionalInterface interface MemoryPressureProbe { boolean severe(); }

    public static final class ResourceExhaustedException extends RuntimeException {
        public ResourceExhaustedException(String message) { super(message); }
    }
}
