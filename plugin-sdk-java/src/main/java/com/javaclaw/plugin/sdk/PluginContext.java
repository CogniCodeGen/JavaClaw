package com.javaclaw.plugin.sdk;

import com.javaclaw.plugin.protocol.v2.PluginIdentity;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/** 本地执行与配置、反向宿主调用的唯一公开入口。 */
public record PluginContext(PluginIdentity identity, String configurationJson, Path privateDataDirectory,
                            ExecutorService executor, HostClient host,
                            Cancellation cancellation) {
    public PluginContext {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(configurationJson, "configurationJson");
        privateDataDirectory = Objects.requireNonNull(privateDataDirectory, "privateDataDirectory")
                .toAbsolutePath().normalize();
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(cancellation, "cancellation");
    }
}
