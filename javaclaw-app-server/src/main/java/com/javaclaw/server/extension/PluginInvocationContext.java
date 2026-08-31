package com.javaclaw.server.extension;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.sandbox.api.SandboxPolicy;

/**
 * 单次插件调用的工作区和权限快照，避免复用其他 Turn 的授权环境。
 *
 * @param workspaceRoot 非空、规范化工作区路径
 * @param protectedRoots 受保护根集合；null 归一为空集合
 * @param authorityCeiling 非空授权上限，必须与插件声明求交
 * @param environment 显式环境候选集合；null 归一为空 Map，执行前还需按策略过滤
 */
public record PluginInvocationContext(
        Path workspaceRoot, Set<Path> protectedRoots, SandboxPolicy authorityCeiling, Map<String, String> environment) {
    /** 规范化工作区并固定保护根与环境快照；不直接赋予子进程任何新增权限。 */
    public PluginInvocationContext {
        workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot")
                .toAbsolutePath()
                .normalize();
        protectedRoots = protectedRoots == null ? Set.of() : Set.copyOf(protectedRoots);
        authorityCeiling = Objects.requireNonNull(authorityCeiling, "authorityCeiling");
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }
}
