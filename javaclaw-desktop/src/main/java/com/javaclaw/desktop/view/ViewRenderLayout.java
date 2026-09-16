package com.javaclaw.desktop.view;

import java.util.Map;

import javafx.scene.Node;

import com.javaclaw.extension.spi.ViewSchema;

/**
 * 平台代码拥有的页面布局适配器；仅重组已由 ViewSchema 渲染的节点，不声明查询、命令或字段。
 *
 * <p>布局不能从扩展数据反序列化。节点映射以 schema 节点标识为键，布局须保留所有节点的可达入口；隐藏或折叠不转移节点的资源所有权。
 */
public interface ViewRenderLayout extends AutoCloseable {
    /** @return 当前布局稳定的页面根节点，不可空 */
    Node node();

    /**
     * 应用通过策略校验的页面节点；调用方已完成草稿保护。
     *
     * @param schema 权威页面定义，不可空
     * @param renderedNodes 按声明顺序提供的只读节点映射，不可空；节点仍由会话管理生命周期
     * @param data 本次权威查询数据，不可空
     */
    void apply(ViewSchema schema, Map<String, Node> renderedNodes, ViewData data);

    /** 清除本会话的布局引用；同一平台布局可在新 Workspace 会话中重新应用。 */
    @Override
    default void close() {}
}
