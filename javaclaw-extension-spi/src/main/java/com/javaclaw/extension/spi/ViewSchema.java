package com.javaclaw.extension.spi;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 平台安全渲染的 ViewSchema v2 页面。
 *
 * <p>Schema 只引用平台控件、固定 operation 和字段名，不允许 FXML、CSS、Java Controller、JavaScript、表达式或 URL。
 *
 * @param schemaVersion 固定为 {@value #CURRENT_VERSION}
 * @param viewId 页面标识
 * @param title 页面标题
 * @param dataSources 页面使用的数据源
 * @param nodes 顶层平台节点
 * @param graphBrowsing Graph.id 对应的浏览声明；空Map维持旧页面wire形状
 */
public record ViewSchema(
        int schemaVersion,
        String viewId,
        String title,
        List<ViewDataSource> dataSources,
        List<Node> nodes,
        Map<String, GraphBrowsing> graphBrowsing) {
    /** 当前唯一支持的 ViewSchema 版本。 */
    public static final int CURRENT_VERSION = 2;

    /**
     * 创建不声明增量图谱浏览的兼容页面。
     *
     * @param schemaVersion 固定版本2
     * @param viewId 页面标识
     * @param title 标题
     * @param dataSources 标准数据源
     * @param nodes 原有节点，包括未修改的Graph构造
     */
    public ViewSchema(
            int schemaVersion, String viewId, String title, List<ViewDataSource> dataSources, List<Node> nodes) {
        this(schemaVersion, viewId, title, dataSources, nodes, Map.of());
    }

    /** 校验版本和页面结构。 */
    public ViewSchema {
        if (schemaVersion != CURRENT_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be 2");
        }
        viewId = ViewSchemaText.required(viewId, "viewId");
        title = ViewSchemaText.required(title, "title");
        dataSources = List.copyOf(Objects.requireNonNull(dataSources, "dataSources"));
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        graphBrowsing = Map.copyOf(Objects.requireNonNull(graphBrowsing, "graphBrowsing"));
        validateGraphBrowsing(nodes, graphBrowsing);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("nodes must not be empty");
        }
    }

    private static void validateGraphBrowsing(List<Node> nodes, Map<String, GraphBrowsing> browsing) {
        if (browsing.size() > 32) {
            throw new IllegalArgumentException("too many graph browsing declarations");
        }
        var graphs = nodes.stream()
                .filter(Graph.class::isInstance)
                .map(Node::id)
                .collect(java.util.stream.Collectors.toSet());
        for (String id : browsing.keySet()) {
            if (!id.matches("[A-Za-z][A-Za-z0-9._-]{0,127}") || !graphs.contains(id)) {
                throw new IllegalArgumentException("graph browsing declaration must reference an existing Graph");
            }
        }
    }

    /** ViewSchema v2 安全节点。 */
    public sealed interface Node
            permits Form, ListView, Table, Card, Progress, Timeline, Markdown, Code, Artifact, Graph {
        /**
         * 返回页面内唯一节点标识。
         *
         * @return 节点标识
         */
        String id();
    }

    /**
     * 带初值、校验和 revision 提交语义的表单。
     *
     * @param id 节点标识
     * @param title 区块标题
     * @param fields 字段
     * @param submit 提交操作
     */
    public record Form(String id, String title, List<? extends ViewFormField> fields, ViewAction submit)
            implements Node {
        /** 校验表单。 */
        public Form {
            id = ViewSchemaText.required(id, "id");
            title = ViewSchemaText.required(title, "title");
            fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("fields must not be empty");
            }
            Objects.requireNonNull(submit, "submit");
        }
    }

    /**
     * 可分页、选择并触发行操作的列表。
     *
     * @param id 节点标识
     * @param title 区块标题
     * @param sourceId 数据源标识
     * @param keyField 稳定行键字段
     * @param titleField 标题字段
     * @param detailField 说明字段
     * @param selection 选择模式
     * @param actions 行操作
     */
    public record ListView(
            String id,
            String title,
            String sourceId,
            String keyField,
            String titleField,
            String detailField,
            ViewSelectionMode selection,
            List<ViewAction> actions)
            implements Node {
        /** 校验列表。 */
        public ListView {
            id = ViewSchemaText.required(id, "id");
            title = ViewSchemaText.required(title, "title");
            sourceId = ViewSchemaText.required(sourceId, "sourceId");
            keyField = ViewSchemaText.required(keyField, "keyField");
            titleField = ViewSchemaText.required(titleField, "titleField");
            detailField = ViewSchemaText.required(detailField, "detailField");
            Objects.requireNonNull(selection, "selection");
            actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        }
    }

    /**
     * 可分页、选择并触发行操作的表格。
     *
     * @param id 节点标识
     * @param title 区块标题
     * @param sourceId 数据源标识
     * @param keyField 稳定行键字段
     * @param columns 列定义
     * @param selection 选择模式
     * @param actions 行操作
     */
    public record Table(
            String id,
            String title,
            String sourceId,
            String keyField,
            List<Column> columns,
            ViewSelectionMode selection,
            List<ViewAction> actions)
            implements Node {
        /** 校验表格。 */
        public Table {
            id = ViewSchemaText.required(id, "id");
            title = ViewSchemaText.required(title, "title");
            sourceId = ViewSchemaText.required(sourceId, "sourceId");
            keyField = ViewSchemaText.required(keyField, "keyField");
            columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("columns must not be empty");
            }
            Objects.requireNonNull(selection, "selection");
            actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        }
    }

    /**
     * 静态说明和安全 command 卡片。
     *
     * @param id 节点标识
     * @param title 标题
     * @param body 正文
     * @param actions 操作
     */
    public record Card(String id, String title, String body, List<ViewAction> actions) implements Node {
        /** 校验卡片。 */
        public Card {
            id = ViewSchemaText.required(id, "id");
            title = ViewSchemaText.required(title, "title");
            body = Objects.requireNonNull(body, "body");
            actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        }
    }

    /**
     * 进度展示。
     *
     * @param id 节点标识
     * @param title 区块标题
     * @param sourceId 数据源标识
     * @param valueField 0 到 1 数值字段
     * @param labelField 标签字段
     */
    public record Progress(String id, String title, String sourceId, String valueField, String labelField)
            implements Node {
        /** 校验字段。 */
        public Progress {
            id = ViewSchemaText.required(id, "id");
            title = ViewSchemaText.required(title, "title");
            sourceId = ViewSchemaText.required(sourceId, "sourceId");
            valueField = ViewSchemaText.required(valueField, "valueField");
            labelField = ViewSchemaText.required(labelField, "labelField");
        }
    }

    /**
     * 时间线展示。
     *
     * @param id 节点标识
     * @param title 区块标题
     * @param sourceId 数据源标识
     * @param timeField 时间字段
     * @param contentField 正文字段
     */
    public record Timeline(String id, String title, String sourceId, String timeField, String contentField)
            implements Node {
        /** 校验字段。 */
        public Timeline {
            id = ViewSchemaText.required(id, "id");
            title = ViewSchemaText.required(title, "title");
            sourceId = ViewSchemaText.required(sourceId, "sourceId");
            timeField = ViewSchemaText.required(timeField, "timeField");
            contentField = ViewSchemaText.required(contentField, "contentField");
        }
    }

    /**
     * Markdown 展示。
     *
     * @param id 节点标识
     * @param title 区块标题
     * @param source 数据绑定
     */
    public record Markdown(String id, String title, ViewBinding source) implements Node {
        /** 校验绑定。 */
        public Markdown {
            id = ViewSchemaText.required(id, "id");
            title = ViewSchemaText.required(title, "title");
            Objects.requireNonNull(source, "source");
        }
    }

    /**
     * 只读代码展示。
     *
     * @param id 节点标识
     * @param title 区块标题
     * @param source 代码绑定
     * @param language 可选语言绑定
     */
    public record Code(String id, String title, ViewBinding source, Optional<ViewBinding> language) implements Node {
        /** 校验绑定。 */
        public Code {
            id = ViewSchemaText.required(id, "id");
            title = ViewSchemaText.required(title, "title");
            Objects.requireNonNull(source, "source");
            language = Objects.requireNonNull(language, "language");
        }
    }

    /**
     * 内容寻址 Artifact 展示。
     *
     * @param id 节点标识
     * @param title 区块标题
     * @param reference Artifact 引用绑定
     * @param mediaType MIME 类型绑定
     */
    public record Artifact(String id, String title, ViewBinding reference, ViewBinding mediaType) implements Node {
        /** 校验绑定。 */
        public Artifact {
            id = ViewSchemaText.required(id, "id");
            title = ViewSchemaText.required(title, "title");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(mediaType, "mediaType");
        }
    }

    /**
     * 平台拥有的只读 Graph；所有节点和边均来自声明数据，不执行表达式。
     *
     * @param id 节点标识
     * @param title 区块标题
     * @param nodeSourceId 节点数据源
     * @param edgeSourceId 边数据源
     * @param nodeIdField 节点 ID 字段
     * @param nodeLabelField 节点标签字段
     * @param nodeKindField 节点种类字段
     * @param edgeFromField 起点字段
     * @param edgeToField 终点字段
     */
    public record Graph(
            String id,
            String title,
            String nodeSourceId,
            String edgeSourceId,
            String nodeIdField,
            String nodeLabelField,
            String nodeKindField,
            String edgeFromField,
            String edgeToField)
            implements Node {
        /** 校验 Graph 字段。 */
        public Graph {
            id = ViewSchemaText.required(id, "id");
            title = ViewSchemaText.required(title, "title");
            nodeSourceId = ViewSchemaText.required(nodeSourceId, "nodeSourceId");
            edgeSourceId = ViewSchemaText.required(edgeSourceId, "edgeSourceId");
            nodeIdField = ViewSchemaText.required(nodeIdField, "nodeIdField");
            nodeLabelField = ViewSchemaText.required(nodeLabelField, "nodeLabelField");
            nodeKindField = ViewSchemaText.required(nodeKindField, "nodeKindField");
            edgeFromField = ViewSchemaText.required(edgeFromField, "edgeFromField");
            edgeToField = ViewSchemaText.required(edgeToField, "edgeToField");
        }
    }

    /**
     * 表格列。
     *
     * @param field 数据字段名
     * @param label 列名
     * @param width 可选逻辑宽度
     */
    public record Column(String field, String label, Optional<Integer> width) {
        /** 校验列。 */
        public Column {
            field = ViewSchemaText.required(field, "field");
            label = ViewSchemaText.required(label, "label");
            width = Objects.requireNonNull(width, "width");
            if (width.filter(value -> value < 1).isPresent()) {
                throw new IllegalArgumentException("width must be positive");
            }
        }
    }
}
