package com.javaclaw.inference.api;

/** 一次 Run 固定的模型提供者身份，不携带插件代码或秘密。 */
public record InferenceProviderSnapshot(String workspaceId, String pluginId, String artifactHash,
                                         long configurationRevision, String hostConfigurationJson) {
    public InferenceProviderSnapshot {
        if (workspaceId == null || workspaceId.isBlank() || pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("模型提供者工作区和插件 ID 不能为空");
        }
        if (artifactHash == null || !artifactHash.matches("[a-fA-F0-9]{64}") || configurationRevision < 1) {
            throw new IllegalArgumentException("模型提供者工件或配置版本无效");
        }
        hostConfigurationJson = hostConfigurationJson == null || hostConfigurationJson.isBlank() ? "{}" : hostConfigurationJson;
    }
    public InferenceProviderSnapshot(String workspaceId, String pluginId, String artifactHash, long configurationRevision) {
        this(workspaceId, pluginId, artifactHash, configurationRevision, "{}");
    }
}
