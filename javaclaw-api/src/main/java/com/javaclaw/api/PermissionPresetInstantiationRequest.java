package com.javaclaw.api;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 针对固定 Workspace 预览或实例化 PermissionProfile 预设的请求。
 *
 * @param presetId 预设稳定标识
 * @param presetRevision 精确预设版本
 * @param workspaceId 固定 Workspace
 * @param profileId 要创建的普通 PermissionProfile 标识
 * @param allowedTools 用户确认的精确工具名称
 * @param executables 用户确认的精确 executable；预设不允许时必须为空
 */
public record PermissionPresetInstantiationRequest(
        String presetId,
        long presetRevision,
        WorkspaceId workspaceId,
        String profileId,
        Set<String> allowedTools,
        Set<String> executables) {
    /** 校验固定作用域和精确选择，不接受通配符。 */
    public PermissionPresetInstantiationRequest {
        presetId = Preconditions.identifier(presetId, "presetId");
        presetRevision = Preconditions.positive(presetRevision, "presetRevision");
        Objects.requireNonNull(workspaceId, "workspaceId");
        profileId = Preconditions.identifier(profileId, "profileId");
        if ("standard".equals(profileId)) {
            throw new IllegalArgumentException("profileId standard is reserved for the built-in template");
        }
        allowedTools = names(allowedTools, "allowedTool", 240, 10_000);
        executables = names(executables, "executable", 1_000, 1_000);
    }

    private static Set<String> names(Set<String> values, String name, int maximumLength, int maximumEntries) {
        Set<String> checked = Objects.requireNonNull(values, name).stream()
                .map(value -> Preconditions.boundedText(value, name, maximumLength))
                .collect(Collectors.toUnmodifiableSet());
        if (checked.size() > maximumEntries) {
            throw new IllegalArgumentException(name + " must not exceed " + maximumEntries + " entries");
        }
        if (checked.stream().anyMatch(value -> value.indexOf('*') >= 0)) {
            throw new IllegalArgumentException(name + " must not use a wildcard");
        }
        return checked;
    }
}
