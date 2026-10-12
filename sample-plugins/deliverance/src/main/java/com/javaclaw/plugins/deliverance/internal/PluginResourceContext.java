package com.javaclaw.plugins.deliverance.internal;

import java.nio.file.Path;

public record PluginResourceContext(
        String pluginId,
        String pluginVersion,
        Path dataDirectory,
        int heapMiB,
        int nativeMemoryMiB,
        int computeThreads,
        int ioConcurrency) { }
