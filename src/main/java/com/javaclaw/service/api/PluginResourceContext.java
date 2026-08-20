package com.javaclaw.service.api;

import java.nio.file.Path;

public record PluginResourceContext(
        String pluginId,
        String pluginVersion,
        Path dataDirectory,
        int heapMiB,
        int nativeMemoryMiB,
        int computeThreads,
        int ioConcurrency) { }
