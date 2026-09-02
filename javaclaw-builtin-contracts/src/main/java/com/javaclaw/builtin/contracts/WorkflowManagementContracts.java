package com.javaclaw.builtin.contracts;

import java.util.List;
import java.util.Objects;

/** Workflow 管理中心使用的安全扁平编辑契约；不接受脚本、表达式或自由 JSON。 */
public final class WorkflowManagementContracts {
    private static final int MAX_NODES = 32;
    private static final int MAX_TOOL_ARGUMENTS = 64;
    private static final int MAX_INPUT_FIELDS = 64;
    private static final int MAX_EDGES = 100;
    private static final int MAX_TOTAL_CHARACTERS = 128 * 1024;

    private WorkflowManagementContracts() {}

    /** Tool 固定参数允许的标量类别。 */
    public enum ScalarKind {
        /** 字符串。 */
        STRING,
        /** 十进制数值。 */
        NUMBER,
        /** 布尔值。 */
        BOOLEAN
    }

    /** USER_INPUT 响应对象允许的字段类别。 */
    public enum InputKind {
        /** 字符串。 */
        STRING,
        /** 数值。 */
        NUMBER,
        /** 布尔值。 */
        BOOLEAN
    }

    /**
     * Graph 节点的扁平编辑行。
     *
     * <p>只有与 {@code kind} 对应的一组字段可以非空；保存时服务端把它转换为互斥的强类型 NodeConfig。
     *
     * @param id 稳定节点标识
     * @param kind 固定节点类别
     * @param name 显示名称
     * @param instruction TURN 指令
     * @param toolName TOOL 名称
     * @param resultField TOOL 结果字段
     * @param conditionField CONDITION 字段
     * @param conditionOperator CONDITION 操作符
     * @param conditionExpected CONDITION 比较值
     * @param transformOperation TRANSFORM 操作
     * @param transformTarget TRANSFORM 目标字段
     * @param transformSource COPY 来源字段
     * @param transformValue SET 固定值
     * @param inputPrompt USER_INPUT 问题
     * @param inputResponseField USER_INPUT 响应保存字段
     * @param inputTimeoutMinutes USER_INPUT 超时分钟数
     * @param outputField OUTPUT 字段
     */
    public record NodeRow(
            String id,
            WorkflowContracts.NodeKind kind,
            String name,
            String instruction,
            String toolName,
            String resultField,
            String conditionField,
            String conditionOperator,
            String conditionExpected,
            String transformOperation,
            String transformTarget,
            String transformSource,
            String transformValue,
            String inputPrompt,
            String inputResponseField,
            int inputTimeoutMinutes,
            String outputField) {
        /** 复制并校验基础字段；领域互斥关系由保存映射器校验。 */
        public NodeRow {
            id = identifier(id, "id");
            Objects.requireNonNull(kind, "kind");
            name = boundedRequired(name, "name", 240);
            instruction = bounded(instruction, "instruction", 16_384);
            toolName = bounded(toolName, "toolName", 320);
            resultField = bounded(resultField, "resultField", 240);
            conditionField = bounded(conditionField, "conditionField", 240);
            conditionOperator = bounded(conditionOperator, "conditionOperator", 80);
            conditionExpected = bounded(conditionExpected, "conditionExpected", 4_096);
            transformOperation = bounded(transformOperation, "transformOperation", 80);
            transformTarget = bounded(transformTarget, "transformTarget", 240);
            transformSource = bounded(transformSource, "transformSource", 240);
            transformValue = bounded(transformValue, "transformValue", 8_192);
            inputPrompt = bounded(inputPrompt, "inputPrompt", 16_384);
            inputResponseField = bounded(inputResponseField, "inputResponseField", 240);
            if (inputTimeoutMinutes < 0 || inputTimeoutMinutes > 1_440) {
                throw new IllegalArgumentException("inputTimeoutMinutes must be between 0 and 1440");
            }
            outputField = bounded(outputField, "outputField", 240);
        }
    }

    /**
     * TOOL 节点的一个固定标量参数。
     *
     * @param id 编辑行稳定标识
     * @param nodeId 所属 TOOL 节点
     * @param name 参数名
     * @param kind 标量类别
     * @param value 可解析的固定值
     */
    public record ToolArgumentRow(String id, String nodeId, String name, ScalarKind kind, String value) {
        /** 校验标量参数基础字段。 */
        public ToolArgumentRow {
            id = identifier(id, "id");
            nodeId = identifier(nodeId, "nodeId");
            name = identifier(name, "name");
            Objects.requireNonNull(kind, "kind");
            value = bounded(value, "value", 8_192);
        }
    }

    /**
     * USER_INPUT 响应对象中的一个受限字段。
     *
     * @param id 编辑行稳定标识
     * @param nodeId 所属 USER_INPUT 节点
     * @param name 字段名
     * @param kind 字段类别
     * @param required 是否必填
     */
    public record InputFieldRow(String id, String nodeId, String name, InputKind kind, boolean required) {
        /** 校验输入字段基础信息。 */
        public InputFieldRow {
            id = identifier(id, "id");
            nodeId = identifier(nodeId, "nodeId");
            name = identifier(name, "name");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * Graph 边的编辑行。
     *
     * @param id 编辑行稳定标识
     * @param from 起点节点
     * @param to 终点节点
     * @param branch CONDITION 的 TRUE/FALSE；其他边为空
     */
    public record EdgeRow(String id, String from, String to, String branch) {
        /** 校验边的基础字段。 */
        public EdgeRow {
            id = identifier(id, "id");
            from = identifier(from, "from");
            to = identifier(to, "to");
            branch = bounded(branch, "branch", 16);
        }
    }

    /**
     * 保存一个 Workflow Definition 的完整编辑输入。
     *
     * @param id Definition 标识
     * @param name 名称
     * @param nodes 节点行
     * @param toolArguments TOOL 固定参数行
     * @param inputFields USER_INPUT 响应字段行
     * @param edges 边行
     * @param maxVisits Execution 最大节点访问数
     */
    public record SaveRequest(
            String id,
            String name,
            List<NodeRow> nodes,
            List<ToolArgumentRow> toolArguments,
            List<InputFieldRow> inputFields,
            List<EdgeRow> edges,
            int maxVisits) {
        /** 复制管理输入；完整图不变量由 Definition 构造器校验。 */
        public SaveRequest {
            id = identifier(id, "id");
            name = boundedRequired(name, "name", 240);
            nodes = rows(nodes, "nodes", 2, MAX_NODES);
            toolArguments = rows(toolArguments, "toolArguments", 0, MAX_TOOL_ARGUMENTS);
            inputFields = rows(inputFields, "inputFields", 0, MAX_INPUT_FIELDS);
            edges = rows(edges, "edges", 1, MAX_EDGES);
            requireUniqueRowIds(toolArguments.stream().map(ToolArgumentRow::id).toList(), "Tool argument");
            requireUniqueRowIds(inputFields.stream().map(InputFieldRow::id).toList(), "Input field");
            requireUniqueRowIds(edges.stream().map(EdgeRow::id).toList(), "Edge");
            requireTextBudget(name, nodes, toolArguments, inputFields, edges);
            if (maxVisits < 1 || maxVisits > 10_000) {
                throw new IllegalArgumentException("maxVisits must be between 1 and 10000");
            }
        }
    }

    private static String identifier(String value, String name) {
        String normalized = boundedRequired(value, name, 96);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }

    private static String boundedRequired(String value, String name, int maximum) {
        String normalized = bounded(value, name, maximum);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String bounded(String value, String name, int maximum) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.length() > maximum || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " is too long or contains NUL");
        }
        return normalized;
    }

    private static <T> List<T> rows(List<T> values, String name, int minimum, int maximum) {
        List<T> copy = List.copyOf(Objects.requireNonNull(values, name));
        if (copy.size() < minimum || copy.size() > maximum) {
            throw new IllegalArgumentException(name + " row count is outside the supported range");
        }
        return copy;
    }

    private static void requireUniqueRowIds(List<String> ids, String label) {
        if (ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException(label + " row id must be unique");
        }
    }

    private static void requireTextBudget(
            String name,
            List<NodeRow> nodes,
            List<ToolArgumentRow> arguments,
            List<InputFieldRow> fields,
            List<EdgeRow> edges) {
        long characters = name.length();
        characters += nodes.stream()
                .mapToLong(WorkflowManagementContracts::characters)
                .sum();
        characters += arguments.stream()
                .mapToLong(row -> row.id().length()
                        + row.nodeId().length()
                        + row.name().length()
                        + row.value().length())
                .sum();
        characters += fields.stream()
                .mapToLong(row ->
                        row.id().length() + row.nodeId().length() + row.name().length())
                .sum();
        characters += edges.stream()
                .mapToLong(row -> row.id().length()
                        + row.from().length()
                        + row.to().length()
                        + row.branch().length())
                .sum();
        if (characters > MAX_TOTAL_CHARACTERS) {
            throw new IllegalArgumentException("Workflow management text exceeds 128 KiB");
        }
    }

    private static long characters(NodeRow row) {
        return row.id().length()
                + row.name().length()
                + row.instruction().length()
                + row.toolName().length()
                + row.resultField().length()
                + row.conditionField().length()
                + row.conditionOperator().length()
                + row.conditionExpected().length()
                + row.transformOperation().length()
                + row.transformTarget().length()
                + row.transformSource().length()
                + row.transformValue().length()
                + row.inputPrompt().length()
                + row.inputResponseField().length()
                + row.outputField().length();
    }
}
