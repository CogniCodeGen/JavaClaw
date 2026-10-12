package com.javaclaw.framework.springai;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/** Provider 可见工具目录及其 Schema 预算统计。 */
record ToolCatalogProjection(
        List<ToolCallback> callbacks,
        int schemaCharacters,
        int availableToolCount) {

    ToolCatalogProjection {
        callbacks = List.copyOf(callbacks);
    }
}
