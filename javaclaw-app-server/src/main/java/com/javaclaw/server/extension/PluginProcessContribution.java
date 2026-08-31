package com.javaclaw.server.extension;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 进程外插件贡献声明；权限是请求上限，不是可绕过沙箱的授权。
 *
 * @param id 插件包内的进程贡献标识
 * @param kind 非空贡献种类，如 MCP、Service 或 Hook
 * @param entrypoint 通用包内相对入口；有平台入口时可为 null
 * @param platformEntrypoints 平台到包内入口的映射；null 归一为空 Map
 * @param arguments 最多 128 个启动参数；null 归一为空列表，不作 Shell 拼接
 * @param workspaceRead 是否请求工作区读权限
 * @param workspaceWrite 是否请求写权限；需要读权限，Hook 禁止写入
 * @param networkAllowlist 通过 Broker 访问的主机集合；null 归一为空集合，不开放原始网络
 * @param timeoutMillis 调用超时，单位毫秒，范围 1 到 600000；PreTool Hook 最多 5000
 * @param outputLimitBytes 输出上限，单位字节，范围 1 到 16 MiB
 * @param healthCheckMethod 健康检查方法名；null/空白使用 health
 */
public record PluginProcessContribution(
        String id,
        PluginProcessKind kind,
        String entrypoint,
        Map<String, String> platformEntrypoints,
        List<String> arguments,
        boolean workspaceRead,
        boolean workspaceWrite,
        Set<String> networkAllowlist,
        long timeoutMillis,
        long outputLimitBytes,
        String healthCheckMethod) {

    /** 校验入口、预算与权限依赖并复制集合；拒绝写型 Hook 和超过 5 秒的 PreTool Hook。 */
    public PluginProcessContribution {
        id = PluginValidation.id(id, "process id");
        kind = Objects.requireNonNull(kind, "kind");
        platformEntrypoints = platformEntrypoints == null
                ? Map.of()
                : platformEntrypoints.entrySet().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                entry -> PluginValidation.platform(entry.getKey()),
                                entry -> PluginValidation.relativePath(entry.getValue(), "platform entrypoint")));
        entrypoint = entrypoint == null || entrypoint.isBlank()
                ? null
                : PluginValidation.relativePath(entrypoint, "entrypoint");
        if (entrypoint == null && platformEntrypoints.isEmpty()) {
            throw new IllegalArgumentException("process requires entrypoint or platformEntrypoints");
        }
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        if (arguments.size() > 128 || arguments.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("plugin arguments are invalid");
        }
        if (workspaceWrite && !workspaceRead) {
            throw new IllegalArgumentException("workspaceWrite requires workspaceRead");
        }
        if (workspaceWrite && (kind == PluginProcessKind.PRE_TOOL_HOOK || kind == PluginProcessKind.POST_TOOL_HOOK)) {
            throw new IllegalArgumentException("tool hooks are audit/policy processes and cannot write");
        }
        networkAllowlist = networkAllowlist == null
                ? Set.of()
                : networkAllowlist.stream()
                        .map(PluginValidation::host)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (timeoutMillis < 1 || timeoutMillis > 10 * 60_000L) {
            throw new IllegalArgumentException("plugin timeout must be between 1ms and 10m");
        }
        if (kind == PluginProcessKind.PRE_TOOL_HOOK && timeoutMillis > 5_000L) {
            throw new IllegalArgumentException("pre-tool hook timeout cannot exceed 5s");
        }
        if (outputLimitBytes < 1 || outputLimitBytes > 16L * 1024L * 1024L) {
            throw new IllegalArgumentException("plugin output limit must be between 1B and 16MiB");
        }
        healthCheckMethod =
                healthCheckMethod == null || healthCheckMethod.isBlank() ? "health" : healthCheckMethod.strip();
        if (!healthCheckMethod.matches("[A-Za-z][A-Za-z0-9._/-]{0,159}")) {
            throw new IllegalArgumentException("healthCheckMethod is invalid");
        }
    }

    /** 按 OS/架构、OS、通用入口顺序选择已声明路径；没有可用入口时拒绝，不自动执行其他平台文件。 */
    public String entrypointForCurrentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        String platform = (os.contains("windows")
                        ? "windows"
                        : os.contains("mac") ? "macos" : os.contains("linux") ? "linux" : "unknown")
                + "-"
                + (arch.equals("amd64") || arch.equals("x86_64")
                        ? "x64"
                        : arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : arch);
        String selected = platformEntrypoints.get(platform);
        if (selected == null) {
            selected = platformEntrypoints.get(platform.substring(0, platform.indexOf('-')));
        }
        if (selected == null) {
            selected = entrypoint;
        }
        if (selected == null) {
            throw new IllegalArgumentException("plugin process has no entrypoint for " + platform);
        }
        return selected;
    }
}
