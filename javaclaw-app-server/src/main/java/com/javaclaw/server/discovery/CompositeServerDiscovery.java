package com.javaclaw.server.discovery;

import java.util.ArrayList;
import java.util.List;

import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.server.model.CloudModelDescriptor;

/** 聚合多个独立发现源的公开能力视图，不执行模型或工具。 */
public final class CompositeServerDiscovery implements ServerDiscovery {
    private final List<ServerDiscovery> delegates;

    /** 复制发现源列表，固定合并顺序，避免运行中重排能力来源。 */
    public CompositeServerDiscovery(List<ServerDiscovery> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public List<CloudModelDescriptor> models() {
        return merge(ServerDiscovery::models);
    }

    @Override
    public List<ToolDescriptor> tools() {
        return merge(ServerDiscovery::tools);
    }

    @Override
    public List<ProcessView> mcpServers() {
        return merge(ServerDiscovery::mcpServers);
    }

    @Override
    public List<PluginView> plugins() {
        return merge(ServerDiscovery::plugins);
    }

    private <T> List<T> merge(java.util.function.Function<ServerDiscovery, List<T>> getter) {
        ArrayList<T> result = new ArrayList<>();
        delegates.forEach(delegate -> result.addAll(getter.apply(delegate)));
        return List.copyOf(result);
    }
}
