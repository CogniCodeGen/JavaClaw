package com.javaclaw.agent.prompt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Codex 兼容的 AGENTS.md 发现设置。
 *
 * @param projectRootMarkers 用于向上识别项目根的文件或目录名
 * @param projectDocFallbackFilenames 标准文件名之后尝试的兼容文件名
 * @param projectDocMaxBytes 项目层指令链允许注入的 UTF-8 字节总量
 * @param configurationRevision 进程内配置修订；变化时现有 Thread 刷新一次解析快照
 */
public record AgentsInstructionSettings(
        List<String> projectRootMarkers,
        List<String> projectDocFallbackFilenames,
        int projectDocMaxBytes,
        long configurationRevision) {
    public static final int DEFAULT_PROJECT_DOC_MAX_BYTES = 32 * 1024;

    /** 创建官方默认设置：以 {@code .git} 为根标记，不启用 fallback，项目预算为 32 KiB。 */
    public static AgentsInstructionSettings defaults() {
        return new AgentsInstructionSettings(List.of(".git"), List.of(), DEFAULT_PROJECT_DOC_MAX_BYTES, 0);
    }

    /** 创建不携带外部配置修订的设置，供固定配置和测试使用。 */
    public AgentsInstructionSettings(
            List<String> projectRootMarkers, List<String> projectDocFallbackFilenames, int projectDocMaxBytes) {
        this(projectRootMarkers, projectDocFallbackFilenames, projectDocMaxBytes, 0);
    }

    /** 固定并校验文件名配置；空 root marker 列表禁用向上遍历，标准候选顺序不允许由 fallback 改写。 */
    public AgentsInstructionSettings {
        projectRootMarkers = names(projectRootMarkers, "projectRootMarkers", false);
        projectDocFallbackFilenames = names(projectDocFallbackFilenames, "projectDocFallbackFilenames", true);
        if (projectDocMaxBytes < 1 || projectDocMaxBytes > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("projectDocMaxBytes must be between 1 and 4194304");
        }
        if (configurationRevision < 0) {
            throw new IllegalArgumentException("configurationRevision cannot be negative");
        }
    }

    /** 返回设置内容摘要，供 Thread 缓存判断环境配置是否发生变化。 */
    public String fingerprint() {
        ArrayList<String> fields = new ArrayList<>();
        fields.addAll(projectRootMarkers);
        fields.add("--fallback--");
        fields.addAll(projectDocFallbackFilenames);
        fields.add(Integer.toString(projectDocMaxBytes));
        fields.add(Long.toString(configurationRevision));
        return PromptHashes.sequence(fields);
    }

    private static List<String> names(List<String> values, String field, boolean rejectStandardNames) {
        Objects.requireNonNull(values, field);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : values) {
            String value = Objects.requireNonNull(raw, field + " entry").strip();
            if (value.isEmpty()
                    || value.equals(".")
                    || value.equals("..")
                    || value.indexOf('/') >= 0
                    || value.indexOf('\\') >= 0) {
                throw new IllegalArgumentException(field + " entries must be plain file names");
            }
            if (rejectStandardNames && ("AGENTS.md".equals(value) || "AGENTS.override.md".equals(value))) {
                throw new IllegalArgumentException("fallback names cannot repeat standard AGENTS.md candidates");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }
}
