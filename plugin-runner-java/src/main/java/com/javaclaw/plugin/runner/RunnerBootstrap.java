package com.javaclaw.plugin.runner;

import com.javaclaw.plugin.protocol.v2.PluginIdentity;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** 凭据只从受控环境读取，入口与工件不接受远程请求覆盖。 */
public record RunnerBootstrap(PluginIdentity identity, Path artifact, String artifactFileHash,
                              String mainClass, Path privateDataDirectory,
                              int hostPort, String pluginToken, String hostToken) {
    public static RunnerBootstrap fromEnvironment(Map<String, String> values) {
        String pluginToken = required(values, "JAVACLAW_PLUGIN_TOKEN");
        String hostToken = required(values, "JAVACLAW_HOST_TOKEN");
        if (pluginToken.equals(hostToken)) throw new IllegalArgumentException("双向凭据必须不同");
        long revision = Long.parseLong(required(values, "JAVACLAW_PLUGIN_REVISION"));
        if (revision < 1) throw new IllegalArgumentException("配置版本必须为正数");
        int port = Integer.parseInt(required(values, "JAVACLAW_PLUGIN_HOST_PORT"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("宿主回环端口无效");
        String hash = required(values, "JAVACLAW_PLUGIN_HASH");
        if (!hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("工件哈希无效");
        PluginIdentity identity = PluginIdentity.newBuilder()
                .setPluginId(required(values, "JAVACLAW_PLUGIN_ID"))
                .setWorkspaceId(required(values, "JAVACLAW_WORKSPACE_ID"))
                .setArtifactHash(hash).setConfigurationRevision(revision)
                .setInstanceId(required(values, "JAVACLAW_PLUGIN_INSTANCE_ID")).build();
        return new RunnerBootstrap(identity,
                Path.of(required(values, "JAVACLAW_PLUGIN_ARTIFACT")).toAbsolutePath().normalize(),
                required(values, "JAVACLAW_PLUGIN_ARTIFACT_FILE_HASH"),
                required(values, "JAVACLAW_PLUGIN_MAIN"),
                Path.of(required(values, "JAVACLAW_PLUGIN_DATA_DIRECTORY")).toAbsolutePath().normalize(),
                port, pluginToken, hostToken);
    }
    public RunnerBootstrap {
        Objects.requireNonNull(identity, "identity");
        if (identity.getPluginId().isBlank() || identity.getWorkspaceId().isBlank() || identity.getInstanceId().isBlank()
                || !identity.getArtifactHash().matches("[a-f0-9]{64}") || identity.getConfigurationRevision() < 1) {
            throw new IllegalArgumentException("插件实例身份无效");
        }
        if (hostPort < 1 || hostPort > 65535) throw new IllegalArgumentException("宿主回环端口无效");
        Objects.requireNonNull(artifact, "artifact");
        if (artifactFileHash == null || !artifactFileHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("入口工件哈希无效");
        }
        if (mainClass == null || !mainClass.matches("[\\w$]+(?:\\.[\\w$]+)+")) {
            throw new IllegalArgumentException("插件入口类无效");
        }
        Objects.requireNonNull(privateDataDirectory, "privateDataDirectory");
        if (java.util.Objects.equals(pluginToken, hostToken)) {
            throw new IllegalArgumentException("双向凭据必须不同");
        }
        // 提前校验，避免输出握手后才发现环境凭据无效。
        com.javaclaw.plugin.sdk.BearerTokens.server(pluginToken);
        com.javaclaw.plugin.sdk.BearerTokens.client(hostToken);
    }
    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少启动环境项: " + key);
        return value;
    }
    @Override public String toString() {
        return "RunnerBootstrap[identity=" + identity.getInstanceId() + ", artifact=" + artifact + "]";
    }
}
