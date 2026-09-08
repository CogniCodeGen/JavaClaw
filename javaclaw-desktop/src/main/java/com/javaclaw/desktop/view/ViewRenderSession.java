package com.javaclaw.desktop.view;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.extension.spi.GraphBrowsing;
import com.javaclaw.extension.spi.ViewSchema;

/**
 * 平台页面会话；调用者按 Workspace、页面和 schema 身份拥有并关闭它。
 *
 * <p>同 schema 数据刷新保留根节点和 Graph 实例。表单仅在调用方通过 dirty/revision 检查后重建； Graph 不拥有 SDK，也不保留跨 Workspace 的位置或选择。
 */
public final class ViewRenderSession implements AutoCloseable {
    private final ViewSchema schema;
    private final ViewSchemaRenderer renderer;
    private final ViewGraphRenderer graphs = new ViewGraphRenderer(new PlatformComponentFactory());
    private final Map<String, ViewGraphRenderer.GraphView> graphViews = new LinkedHashMap<>();
    private final Map<String, ViewGraphWindow> windows = new LinkedHashMap<>();
    private final VBox root;
    private boolean closed;

    /**
     * @param schema 已通过平台策略的声明式页面
     * @param renderer 原生组件工厂
     */
    public ViewRenderSession(ViewSchema schema, ViewSchemaRenderer renderer) {
        this.schema = ViewSchemaPolicy.requireSupported(schema);
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        root = new PlatformComponentFactory().page(schema.title());
        for (ViewSchema.Node node : schema.nodes()) {
            if (node instanceof ViewSchema.Graph graph && schema.graphBrowsing().containsKey(graph.id())) {
                windows.put(
                        graph.id(),
                        new ViewGraphWindow(graph, schema.graphBrowsing().get(graph.id())));
            }
        }
    }

    /** @return 当前稳定页面根节点 */
    public Node node() {
        return root;
    }

    /**
     * @param candidate 待刷新 schema
     * @return 是否可在当前会话内应用
     */
    public boolean accepts(ViewSchema candidate) {
        return !closed && schema.equals(candidate);
    }

    /**
     * @param data 权威查询数据
     * @param interactions 受限平台交互；调用方须先处理未保存草稿
     */
    public void apply(ViewData data, ViewInteractionHandler interactions) {
        if (closed) {
            throw new IllegalStateException("页面会话已关闭");
        }
        renderer.cancelUploads(root);
        ViewCommandBindingResolver bindings = new ViewCommandBindingResolver(schema, data);
        ArrayList<Node> nodes = new ArrayList<>();
        nodes.add(root.getChildren().getFirst());
        for (ViewSchema.Node definition : schema.nodes()) {
            if (definition instanceof ViewSchema.Graph graph) {
                var view = graphViews.computeIfAbsent(
                        graph.id(),
                        ignored -> graphs.create(graph, schema.graphBrowsing().containsKey(graph.id())));
                if (windows.containsKey(graph.id())) {
                    windows.get(graph.id()).observe(data);
                }
                view.apply(data, interactions);
                nodes.add(view.node());
            } else {
                nodes.add(renderer.renderNode(definition, data, interactions, bindings));
            }
        }
        for (int index = 0; index < nodes.size(); index++) {
            if (index >= root.getChildren().size()) {
                root.getChildren().add(nodes.get(index));
            } else if (root.getChildren().get(index) != nodes.get(index)) {
                root.getChildren().set(index, nodes.get(index));
            }
        }
        root.getChildren().remove(nodes.size(), root.getChildren().size());
    }

    /** @return 当前会话的受限图谱窗口副本，用于下一次只读查询 */
    public Map<String, String> graphWindows() {
        Map<String, String> result = new LinkedHashMap<>();
        windows.forEach((id, window) -> result.put(id, window.encode()));
        return Map.copyOf(result);
    }

    /** @param graphId 页面图标识 @return 已由 schema 授予的只读浏览声明 */
    public GraphBrowsing browsing(String graphId) {
        return Objects.requireNonNull(schema.graphBrowsing().get(graphId), "图谱未声明浏览能力");
    }

    /** @param action 用户筛选动作；清除本地展开游标，不修改业务状态 */
    public void filterGraph(ViewGraphAction action) {
        window(action.graphId()).filter(action.filter().orElseThrow());
    }

    /** @param action 节点展开动作 @return 已验证当前窗口成员的只读查询参数 */
    public CanonicalPayload neighbors(ViewGraphAction action) {
        return window(action.graphId()).neighbors(action.nodeId());
    }

    /** @param action 原始展开动作 @param result 已通过页面 epoch 校验的查询结果 */
    public void acceptNeighbors(ViewGraphAction action, CanonicalPayload result) {
        window(action.graphId()).accept(action.nodeId(), result);
    }

    private ViewGraphWindow window(String id) {
        if (closed) {
            throw new IllegalStateException("页面会话已关闭");
        }
        return Objects.requireNonNull(windows.get(id), "图谱未声明浏览能力");
    }

    /** 页面离开导航时释放图谱 WebKit；保留原生草稿和会话窗口，仍不拥有后台 SDK 操作。 */
    public void suspend() {
        graphViews.values().forEach(ViewGraphRenderer.GraphView::suspend);
    }

    /** 返回原作用域时按需恢复图谱，不能重新发送原页面业务命令。 */
    public void resume() {
        if (!closed) {
            graphViews.values().forEach(ViewGraphRenderer.GraphView::resume);
        }
    }

    /** 释放上传、WebView、桥及图数据；离开作用域后旧交互不能访问新页面。 */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            renderer.cancelUploads(root);
            graphViews.values().forEach(ViewGraphRenderer.GraphView::close);
            graphViews.clear();
            windows.clear();
            root.getChildren().clear();
        }
    }
}
