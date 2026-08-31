package com.javaclaw.sandbox.api;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable sandbox policy. Policy composition may only remove authority.
 *
 * @param mode 非空执行权限级别
 * @param readableRoots 可读路径根集合；空集合不授予额外读取权限
 * @param writableRoots 可写路径根集合；不得覆盖受保护根目录
 * @param protectedRoots 只读保护根目录集合；权限求交时只能追加保护
 * @param network 非空网络策略
 * @param inheritedEnvironment 允许继承的环境变量名集合；不会继承未列出的变量
 * @param timeout 执行时间上限，必须为正时长
 * @param outputLimitBytes 允许保留的输出字节上限，必须为正数
 */
public record SandboxPolicy(
        SandboxMode mode,
        Set<Path> readableRoots,
        Set<Path> writableRoots,
        Set<Path> protectedRoots,
        NetworkPolicy network,
        Set<String> inheritedEnvironment,
        Duration timeout,
        long outputLimitBytes) {

    public static final long DEFAULT_OUTPUT_LIMIT = 4L * 1024L * 1024L;

    /** 规范化权限根并验证只读/保护目录不变量；拒绝保护目录内写权限和非正资源预算。 */
    public SandboxPolicy {
        mode = Objects.requireNonNull(mode, "mode");
        readableRoots = normalizedPaths(readableRoots);
        writableRoots = normalizedPaths(writableRoots);
        protectedRoots = normalizedPaths(protectedRoots);
        network = network == null ? NetworkPolicy.disabled() : network;
        inheritedEnvironment = normalizedNames(inheritedEnvironment);
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (outputLimitBytes < 1) {
            throw new IllegalArgumentException("outputLimitBytes must be positive");
        }
        if (mode == SandboxMode.READ_ONLY && !writableRoots.isEmpty()) {
            throw new IllegalArgumentException("READ_ONLY cannot declare writable roots");
        }
        for (Path writable : writableRoots) {
            if (protectedRoots.stream().anyMatch(writable::startsWith)) {
                throw new IllegalArgumentException("writable roots overlap protected roots: " + writable);
            }
        }
    }

    /** 构造无网络、5 分钟超时、4 MiB 输出上限的只读策略；仅继承固定安全环境变量名。 */
    public static SandboxPolicy readOnly(Set<Path> roots, Set<Path> protectedRoots) {
        return new SandboxPolicy(
                SandboxMode.READ_ONLY,
                roots,
                Set.of(),
                protectedRoots,
                NetworkPolicy.disabled(),
                Set.of("PATH", "LANG", "LC_ALL", "TERM"),
                Duration.ofMinutes(5),
                DEFAULT_OUTPUT_LIMIT);
    }

    /** 构造无网络的工作区写策略；写根仍不得覆盖 protectedRoots，默认预算与只读策略一致。 */
    public static SandboxPolicy workspaceWrite(
            Set<Path> readableRoots, Set<Path> writableRoots, Set<Path> protectedRoots) {
        return new SandboxPolicy(
                SandboxMode.WORKSPACE_WRITE,
                readableRoots,
                writableRoots,
                protectedRoots,
                NetworkPolicy.disabled(),
                Set.of("PATH", "LANG", "LC_ALL", "TERM"),
                Duration.ofMinutes(5),
                DEFAULT_OUTPUT_LIMIT);
    }

    /** Returns a policy no more permissive than either input. */
    public SandboxPolicy intersect(SandboxPolicy other) {
        Objects.requireNonNull(other, "other");
        SandboxMode resultingMode = mode.ordinal() <= other.mode.ordinal() ? mode : other.mode;
        Set<Path> reads = mode == SandboxMode.HOST_FULL_ACCESS
                ? other.readableRoots
                : other.mode == SandboxMode.HOST_FULL_ACCESS
                        ? readableRoots
                        : pathIntersection(readableRoots, other.readableRoots);
        Set<Path> writes = resultingMode == SandboxMode.READ_ONLY
                ? Set.of()
                : mode == SandboxMode.HOST_FULL_ACCESS
                        ? other.writableRoots
                        : other.mode == SandboxMode.HOST_FULL_ACCESS
                                ? writableRoots
                                : pathIntersection(writableRoots, other.writableRoots);
        Set<Path> protectedPaths = union(protectedRoots, other.protectedRoots);
        NetworkPolicy resultingNetwork = intersect(network, other.network);
        Set<String> environment = new LinkedHashSet<>(inheritedEnvironment);
        environment.retainAll(other.inheritedEnvironment);
        return new SandboxPolicy(
                resultingMode,
                reads,
                writes,
                protectedPaths,
                resultingNetwork,
                environment,
                timeout.compareTo(other.timeout) <= 0 ? timeout : other.timeout,
                Math.min(outputLimitBytes, other.outputLimitBytes));
    }

    /** 从 source 中仅复制 inheritedEnvironment 允许且实际存在的条目；不回退为全量环境继承。 */
    public Map<String, String> filteredEnvironment(Map<String, String> source) {
        Objects.requireNonNull(source, "source");
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        inheritedEnvironment.forEach(name -> {
            String value = source.get(name);
            if (value != null) {
                result.put(name, value);
            }
        });
        return Map.copyOf(result);
    }

    private static NetworkPolicy intersect(NetworkPolicy left, NetworkPolicy right) {
        if (left.mode() == NetworkPolicy.Mode.DISABLED || right.mode() == NetworkPolicy.Mode.DISABLED) {
            return NetworkPolicy.disabled();
        }
        if (left.mode() == NetworkPolicy.Mode.FULL) {
            return right;
        }
        if (right.mode() == NetworkPolicy.Mode.FULL) {
            return left;
        }
        if (left.mode() == NetworkPolicy.Mode.LOOPBACK && right.mode() == NetworkPolicy.Mode.LOOPBACK) {
            return new NetworkPolicy(NetworkPolicy.Mode.LOOPBACK, Set.of());
        }
        if (left.mode() == NetworkPolicy.Mode.LOOPBACK || right.mode() == NetworkPolicy.Mode.LOOPBACK) {
            NetworkPolicy allowlist = left.mode() == NetworkPolicy.Mode.ALLOWLIST ? left : right;
            Set<String> loopbackHosts = allowlist.allowedHosts().stream()
                    .filter(SandboxPolicy::isLoopbackHost)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return loopbackHosts.isEmpty()
                    ? NetworkPolicy.disabled()
                    : new NetworkPolicy(NetworkPolicy.Mode.ALLOWLIST, loopbackHosts);
        }
        Set<String> hosts = new LinkedHashSet<>(left.allowedHosts());
        hosts.retainAll(right.allowedHosts());
        return hosts.isEmpty() ? NetworkPolicy.disabled() : new NetworkPolicy(NetworkPolicy.Mode.ALLOWLIST, hosts);
    }

    private static Set<Path> normalizedPaths(Set<Path> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<Path> normalized = new LinkedHashSet<>();
        values.stream().filter(Objects::nonNull).map(SandboxPaths::canonicalize).forEach(normalized::add);
        return Set.copyOf(normalized);
    }

    private static Set<String> normalizedNames(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        values.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(value -> value.matches("[A-Za-z_][A-Za-z0-9_]*"))
                .forEach(normalized::add);
        return Set.copyOf(normalized);
    }

    private static Set<Path> pathIntersection(Set<Path> left, Set<Path> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        for (Path first : left) {
            for (Path second : right) {
                if (first.startsWith(second)) {
                    result.add(first);
                } else if (second.startsWith(first)) {
                    result.add(second);
                }
            }
        }
        return Set.copyOf(result);
    }

    private static <T> Set<T> union(Set<T> left, Set<T> right) {
        LinkedHashSet<T> result = new LinkedHashSet<>(left);
        result.addAll(right);
        return Set.copyOf(result);
    }

    private static boolean isLoopbackHost(String host) {
        return "localhost".equals(host) || "::1".equals(host) || host.startsWith("127.");
    }
}
