package com.javaclaw.server.rpc;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.NegotiatedCapabilities;
import com.javaclaw.protocol.ViewSchemaWireCodec;

/**
 * 连接协商后的纯结果投影；旧客户端保留原五字段页面，不接收未声明的图谱浏览元数据。
 *
 * <p>旧 Graph 不提供行选择，作为主数据源的图谱须投影为既有单选列表；保留节点与字段身份， 让详情和命令继续使用同一权威行及 revision，不改变扩展的操作或权限。
 */
final class ViewCapabilityProjection {
    private ViewCapabilityProjection() {}

    static CanonicalPayload project(
            String method, CanonicalPayload result, NegotiatedCapabilities capabilities, CanonicalJson json) {
        if (!"extension/view/list".equals(method)
                || capabilities.allows(ViewSchemaWireCodec.GRAPH_BROWSING_CAPABILITY)) {
            return result;
        }
        var codec = new ViewSchemaWireCodec(json);
        var views = json.decode(result, ExtensionRpcContracts.ViewListResult.class).views().stream()
                .map(document -> {
                    var schema = codec.decode(document.schema());
                    return new ExtensionRpcContracts.ViewDocument(
                            document.extensionId(), document.viewId(), codec.encode(legacy(schema)));
                })
                .toList();
        return json.encode(new ExtensionRpcContracts.ViewListResult(views));
    }

    private static ViewSchema legacy(ViewSchema schema) {
        Set<String> masters = new HashSet<>();
        schema.dataSources()
                .forEach(source -> source.argumentBindings().forEach(binding -> masters.add(binding.sourceId())));
        // 已有单选列表或表格可以承担主从绑定，不必改变旁边仅作展示的基础 Graph。
        for (ViewSchema.Node node : schema.nodes()) {
            if (node instanceof ViewSchema.ListView list && list.selection() == ViewSelectionMode.SINGLE) {
                masters.remove(list.sourceId());
            } else if (node instanceof ViewSchema.Table table && table.selection() == ViewSelectionMode.SINGLE) {
                masters.remove(table.sourceId());
            }
        }
        List<ViewSchema.Node> nodes =
                schema.nodes().stream().map(node -> legacyNode(node, masters)).toList();
        return new ViewSchema(schema.schemaVersion(), schema.viewId(), schema.title(), schema.dataSources(), nodes);
    }

    private static ViewSchema.Node legacyNode(ViewSchema.Node node, Set<String> masters) {
        if (!(node instanceof ViewSchema.Graph graph) || !masters.contains(graph.nodeSourceId())) {
            return node;
        }
        return new ViewSchema.ListView(
                graph.id(),
                graph.title(),
                graph.nodeSourceId(),
                graph.nodeIdField(),
                graph.nodeLabelField(),
                graph.nodeKindField(),
                ViewSelectionMode.SINGLE,
                List.of());
    }
}
