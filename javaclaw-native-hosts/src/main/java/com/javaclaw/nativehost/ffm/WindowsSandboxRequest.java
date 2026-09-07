package com.javaclaw.nativehost.ffm;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import com.javaclaw.api.ResourceLimits;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;

/**
 * 交给 Windows AppContainer/Job Object 的不可变执行请求。
 *
 * @param arguments 不经 shell 的目标 argv
 * @param context 工作目录与显式环境
 * @param readRoots 只读根目录
 * @param writeRoots 可写根目录
 * @param allowDelete 是否授予删除权限
 * @param timeout 最长执行时间
 * @param limits Job Object 资源上限
 * @param networkAccess 当前调用由服务端绑定的离线或精确代理边界
 */
public record WindowsSandboxRequest(
        List<String> arguments,
        WindowsSandboxContext context,
        List<Path> readRoots,
        List<Path> writeRoots,
        boolean allowDelete,
        Duration timeout,
        ResourceLimits limits,
        SandboxNetworkAccess networkAccess) {
    /** 复制集合并拒绝空 argv、NUL、无效时限和空依赖。 */
    public WindowsSandboxRequest {
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (arguments.isEmpty()
                || arguments.stream().anyMatch(value -> value == null || value.isEmpty() || value.indexOf('\0') >= 0)) {
            throw new IllegalArgumentException("Windows sandbox arguments are invalid");
        }
        Objects.requireNonNull(context, "context");
        readRoots = normalize(readRoots, "readRoots");
        writeRoots = normalize(writeRoots, "writeRoots");
        if (timeout == null
                || timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("Windows sandbox timeout must be between 1 ms and 24 hours");
        }
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(networkAccess, "networkAccess");
    }

    /**
     * 保留旧宿主请求的离线语义。
     *
     * @param arguments 固定 argv
     * @param context 显式进程上下文
     * @param readRoots 只读根
     * @param writeRoots 可写根
     * @param allowDelete 删除权限
     * @param timeout 有界时限
     * @param limits 资源预算
     */
    public WindowsSandboxRequest(
            List<String> arguments,
            WindowsSandboxContext context,
            List<Path> readRoots,
            List<Path> writeRoots,
            boolean allowDelete,
            Duration timeout,
            ResourceLimits limits) {
        this(arguments, context, readRoots, writeRoots, allowDelete, timeout, limits, SandboxNetworkAccess.offline());
    }

    private static List<Path> normalize(List<Path> values, String name) {
        return Objects.requireNonNull(values, name).stream()
                .map(path -> Objects.requireNonNull(path, name + " element"))
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
    }
}
