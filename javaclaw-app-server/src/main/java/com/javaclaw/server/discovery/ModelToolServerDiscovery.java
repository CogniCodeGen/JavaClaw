package com.javaclaw.server.discovery;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.server.model.CloudModelDescriptor;

/** 模型和第一方工具的发现适配器，返回描述而非实现对象。 */
public final class ModelToolServerDiscovery implements ServerDiscovery {
    private final Supplier<List<CloudModelDescriptor>> models;
    private final Supplier<List<ToolDescriptor>> tools;

    /** 绑定模型和工具目录；列表重载固定快照，Supplier 重载允许按当前配置刷新视图。 */
    public ModelToolServerDiscovery(List<CloudModelDescriptor> models, List<ToolDescriptor> tools) {
        this(() -> List.copyOf(models), () -> List.copyOf(tools));
    }

    /** 绑定模型和工具目录；列表重载固定快照，Supplier 重载允许按当前配置刷新视图。 */
    public ModelToolServerDiscovery(Supplier<List<CloudModelDescriptor>> models, Supplier<List<ToolDescriptor>> tools) {
        this.models = Objects.requireNonNull(models, "models");
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    @Override
    public List<CloudModelDescriptor> models() {
        return List.copyOf(models.get());
    }

    @Override
    public List<ToolDescriptor> tools() {
        return List.copyOf(tools.get());
    }
}
