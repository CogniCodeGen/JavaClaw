package com.javaclaw.framework.spi;

import java.util.List;

/** 持久化的外部能力快照；恢复时必须匹配原工件与配置。 */
public record ExternalPluginLock(String workspaceId, String pluginId, String artifactHash,
                                 long configurationRevision, List<Contribution> contributions,
                                 String hostConfigurationJson) {
    public ExternalPluginLock {
        contributions = List.copyOf(contributions);
        hostConfigurationJson = hostConfigurationJson == null ? "{}" : hostConfigurationJson;
    }
    public ExternalPluginLock(String workspaceId, String pluginId, String artifactHash,
                              long configurationRevision, List<Contribution> contributions) {
        this(workspaceId, pluginId, artifactHash, configurationRevision, contributions, "{}");
    }

    public record Contribution(String id, String capability, String name, String description,
                               String inputSchemaJson, String metadataJson) { }
}
