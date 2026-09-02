package com.javaclaw.runtime;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;

/** Turn 内只增不减的可见工具子集；底层来源始终是同一个冻结目录。 */
final class VisibleToolCatalog {
    private final ToolCatalogSnapshot snapshot;
    private final LinkedHashMap<String, ToolDescriptor> visible = new LinkedHashMap<>();

    VisibleToolCatalog(ToolCatalogSnapshot snapshot, List<ToolDescriptor> initial) {
        this.snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
        reveal(initial);
    }

    void reveal(List<ToolDescriptor> tools) {
        for (ToolDescriptor descriptor : tools) {
            ToolDescriptor frozen = snapshot.require(descriptor.identity());
            if (!frozen.equals(descriptor)) {
                throw new TurnFailureException("TOOL_SCHEMA_CHANGED", "工具描述与冻结目录不一致");
            }
            // ToolCatalogSnapshot 已在冻结边界拒绝重名；这里只维护只增不减的可见子集。
            visible.putIfAbsent(descriptor.identity().name(), descriptor);
        }
    }

    ToolDescriptor requireVisible(ToolIdentity identity) {
        ToolDescriptor descriptor = visible.get(identity.name());
        if (descriptor == null || !descriptor.identity().equals(identity)) {
            throw new TurnFailureException("TOOL_NOT_DISCOVERED", "模型请求了本轮尚未发现的工具");
        }
        return descriptor;
    }

    List<ToolDescriptor> list() {
        return visible.values().stream()
                .sorted(Comparator.comparing(value -> value.identity().name()))
                .toList();
    }
}
