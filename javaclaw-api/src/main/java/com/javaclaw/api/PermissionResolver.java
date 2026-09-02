package com.javaclaw.api;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 计算系统、Workspace、执行配置、Turn grant 与工具声明的权限交集。
 *
 * <p>结果只会等于或窄于每个输入。撤权不修改冻结快照，而是在每次执行前重新加入最新配置求交。
 */
public final class PermissionResolver {
    private PermissionResolver() {}

    /**
     * 求多个配置的权限交集。
     *
     * @param profiles 按任意顺序提供的非空配置集合
     * @return 按全部约束即时计算的有效配置
     */
    public static PermissionProfile intersect(List<PermissionProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            throw new IllegalArgumentException("profiles must not be empty");
        }
        PermissionProfile result = profiles.getFirst();
        for (PermissionProfile next : profiles.subList(1, profiles.size())) {
            result = intersectPair(result, next);
        }
        return result;
    }

    private static PermissionProfile intersectPair(PermissionProfile left, PermissionProfile right) {
        long version = Math.max(left.version(), right.version());
        return new PermissionProfile(
                "effective",
                version,
                files(left.files(), right.files()),
                network(left.network(), right.network()),
                processes(left.processes(), right.processes()),
                tools(left.tools(), right.tools()),
                resources(left.resources(), right.resources()));
    }

    private static FilePermission files(FilePermission left, FilePermission right) {
        return new FilePermission(
                narrowRoots(left.readRoots(), right.readRoots()),
                narrowRoots(left.writeRoots(), right.writeRoots()),
                left.allowDelete() && right.allowDelete(),
                left.followSymbolicLinks() && right.followSymbolicLinks());
    }

    private static List<Path> narrowRoots(Collection<Path> left, Collection<Path> right) {
        ArrayList<Path> result = new ArrayList<>();
        for (Path first : left) {
            for (Path second : right) {
                if (first.startsWith(second)) {
                    result.add(first);
                } else if (second.startsWith(first)) {
                    result.add(second);
                }
            }
        }
        return result.stream().distinct().toList();
    }

    private static NetworkPermission network(NetworkPermission left, NetworkPermission right) {
        return new NetworkPermission(
                hostIntersection(left.hosts(), right.hosts()),
                portIntersection(left.ports(), right.ports()),
                left.tlsOnly() || right.tlsOnly());
    }

    private static Set<String> hostIntersection(Set<String> left, Set<String> right) {
        if (left.contains(NetworkPermission.ANY_HOST)) {
            return right;
        }
        if (right.contains(NetworkPermission.ANY_HOST)) {
            return left;
        }
        return intersection(left, right);
    }

    private static Set<Integer> portIntersection(Set<Integer> left, Set<Integer> right) {
        if (left.contains(NetworkPermission.ANY_PORT)) {
            return right;
        }
        if (right.contains(NetworkPermission.ANY_PORT)) {
            return left;
        }
        return intersection(left, right);
    }

    private static ProcessPermission processes(ProcessPermission left, ProcessPermission right) {
        Duration maxRunTime =
                left.maxRunTime().compareTo(right.maxRunTime()) <= 0 ? left.maxRunTime() : right.maxRunTime();
        return new ProcessPermission(
                intersection(left.executables(), right.executables()), left.allowPty() && right.allowPty(), maxRunTime);
    }

    private static ToolPermission tools(ToolPermission left, ToolPermission right) {
        ToolRisk risk = left.maximumRisk().ordinal() <= right.maximumRisk().ordinal()
                ? left.maximumRisk()
                : right.maximumRisk();
        ApprovalRequirement approval = left.approvalRequirement().ordinal()
                        >= right.approvalRequirement().ordinal()
                ? left.approvalRequirement()
                : right.approvalRequirement();
        return new ToolPermission(intersection(left.allowedTools(), right.allowedTools()), risk, approval);
    }

    private static ResourceLimits resources(ResourceLimits left, ResourceLimits right) {
        return new ResourceLimits(
                Math.min(left.memoryBytes(), right.memoryBytes()),
                Math.min(left.outputBytes(), right.outputBytes()),
                Math.min(left.childProcesses(), right.childProcesses()),
                Math.min(left.openFiles(), right.openFiles()));
    }

    private static <T> Set<T> intersection(Set<T> left, Set<T> right) {
        HashSet<T> result = new HashSet<>(left);
        result.retainAll(right);
        return Set.copyOf(result);
    }
}
