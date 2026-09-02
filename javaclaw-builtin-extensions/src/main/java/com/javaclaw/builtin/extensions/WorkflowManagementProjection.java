package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.javaclaw.builtin.contracts.WorkflowContracts;
import com.javaclaw.builtin.contracts.WorkflowManagementContracts;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;

/** 把已保存的 Workflow Definition 投影成安全、可重复编辑的扁平值。 */
final class WorkflowManagementProjection {
    private static final Set<String> SCHEMA_KEYS = Set.of("type", "properties", "required", "additionalProperties");

    private final ExtensionPayloadCodec payloads;

    WorkflowManagementProjection(ExtensionPayloadCodec payloads) {
        this.payloads = java.util.Objects.requireNonNull(payloads, "payloads");
    }

    WorkflowManagementContracts.SaveRequest management(WorkflowContracts.Definition definition) {
        List<WorkflowManagementContracts.NodeRow> nodes = new ArrayList<>();
        List<WorkflowManagementContracts.ToolArgumentRow> arguments = new ArrayList<>();
        List<WorkflowManagementContracts.InputFieldRow> inputFields = new ArrayList<>();
        definition.nodes().forEach(node -> {
            nodes.add(nodeRow(node));
            node.config().tool().ifPresent(config -> arguments.addAll(toolArgumentRows(node.id(), config)));
            node.config().input().ifPresent(config -> inputFields.addAll(inputFieldRows(node.id(), config)));
        });
        List<WorkflowManagementContracts.EdgeRow> edges =
                definition.edges().stream().map(this::edgeRow).toList();
        return new WorkflowManagementContracts.SaveRequest(
                definition.id(), definition.name(), nodes, arguments, inputFields, edges, definition.maxVisits());
    }

    private WorkflowManagementContracts.EdgeRow edgeRow(WorkflowContracts.Edge edge) {
        return new WorkflowManagementContracts.EdgeRow(
                stableRowId("edge", edge.from(), edge.to(), edge.branch().orElse("")),
                edge.from(),
                edge.to(),
                edge.branch().orElse(""));
    }

    private WorkflowManagementContracts.NodeRow nodeRow(WorkflowContracts.Node node) {
        NodeValues values = NodeValues.from(node);
        return new WorkflowManagementContracts.NodeRow(
                node.id(),
                node.kind(),
                node.name(),
                node.instruction().orElse(""),
                values.toolName(),
                values.resultField(),
                values.conditionField(),
                values.conditionOperator(),
                values.conditionExpected(),
                values.transformOperation(),
                values.transformTarget(),
                values.transformSource(),
                values.transformValue(),
                values.inputPrompt(),
                values.inputResponseField(),
                values.inputTimeoutMinutes(),
                values.outputField());
    }

    private List<WorkflowManagementContracts.ToolArgumentRow> toolArgumentRows(
            String nodeId, WorkflowContracts.ToolConfig config) {
        Map<?, ?> values = payloads.decode(config.arguments(), Map.class);
        List<WorkflowManagementContracts.ToolArgumentRow> rows = new ArrayList<>();
        values.forEach((name, value) -> rows.add(toolArgumentRow(nodeId, name, value)));
        return List.copyOf(rows);
    }

    private static WorkflowManagementContracts.ToolArgumentRow toolArgumentRow(
            String nodeId, Object name, Object value) {
        if (!(name instanceof String field)) {
            throw new IllegalStateException("Workflow Tool argument name is not text");
        }
        WorkflowManagementSafety.requireNonSecretField(field);
        ScalarValue scalar = scalarValue(value);
        return new WorkflowManagementContracts.ToolArgumentRow(
                stableRowId("argument", nodeId, field), nodeId, field, scalar.kind(), scalar.value());
    }

    private List<WorkflowManagementContracts.InputFieldRow> inputFieldRows(
            String nodeId, WorkflowContracts.UserInputConfig config) {
        Map<?, ?> schema = payloads.decode(config.responseSchema(), Map.class);
        if (!schema.keySet().equals(SCHEMA_KEYS)
                || !"object".equals(schema.get("type"))
                || !Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            throw new IllegalStateException("Workflow input schema is not editable by the safe management form");
        }
        Map<?, ?> properties = requireMap(schema.get("properties"), "properties");
        Set<?> required = Set.copyOf(requireList(schema.get("required"), "required"));
        if (required.stream().anyMatch(name -> !(name instanceof String) || !properties.containsKey(name))) {
            throw new IllegalStateException("Workflow input required fields must exist in properties");
        }
        List<WorkflowManagementContracts.InputFieldRow> rows = new ArrayList<>();
        properties.forEach((name, descriptor) -> rows.add(inputFieldRow(nodeId, name, descriptor, required)));
        return List.copyOf(rows);
    }

    private static WorkflowManagementContracts.InputFieldRow inputFieldRow(
            String nodeId, Object name, Object descriptor, Set<?> required) {
        if (!(name instanceof String field)) {
            throw new IllegalStateException("Workflow input field name is not text");
        }
        Map<?, ?> shape = requireMap(descriptor, "field schema");
        if (!shape.keySet().equals(Set.of("type")) || !(shape.get("type") instanceof String type)) {
            throw new IllegalStateException("Workflow input field schema is not a primitive type");
        }
        var kind =
                switch (type) {
                    case "string" -> WorkflowManagementContracts.InputKind.STRING;
                    case "number" -> WorkflowManagementContracts.InputKind.NUMBER;
                    case "boolean" -> WorkflowManagementContracts.InputKind.BOOLEAN;
                    default -> throw new IllegalStateException("Workflow input field type is unsupported");
                };
        return new WorkflowManagementContracts.InputFieldRow(
                stableRowId("input", nodeId, field), nodeId, field, kind, required.contains(field));
    }

    private static ScalarValue scalarValue(Object value) {
        if (value instanceof String text) {
            return new ScalarValue(WorkflowManagementContracts.ScalarKind.STRING, text);
        }
        if (value instanceof Boolean bool) {
            return new ScalarValue(WorkflowManagementContracts.ScalarKind.BOOLEAN, bool.toString());
        }
        if (value instanceof Number number) {
            return new ScalarValue(
                    WorkflowManagementContracts.ScalarKind.NUMBER, WorkflowManagementSafety.numberText(number));
        }
        throw new IllegalStateException("Workflow Tool arguments must be flat primitive values");
    }

    private static String stableRowId(String kind, String... values) {
        return WorkflowManagementSafety.stableRowId(kind, values);
    }

    private static Map<?, ?> requireMap(Object value, String name) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalStateException(name + " is not an object");
    }

    private static List<?> requireList(Object value, String name) {
        if (value instanceof List<?> list) {
            return list;
        }
        throw new IllegalStateException(name + " is not an array");
    }

    private record ScalarValue(WorkflowManagementContracts.ScalarKind kind, String value) {}

    private record NodeValues(
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
        private static NodeValues from(WorkflowContracts.Node node) {
            Builder values = new Builder();
            node.config().tool().ifPresent(config -> values.tool(config.toolName(), config.resultField()));
            node.config().condition().ifPresent(values::condition);
            node.config().transform().ifPresent(values::transform);
            node.config().input().ifPresent(values::input);
            node.config().output().ifPresent(config -> values.outputField = config.field());
            return values.build();
        }
    }

    private static final class Builder {
        private String toolName = "";
        private String resultField = "";
        private String conditionField = "";
        private String conditionOperator = "";
        private String conditionExpected = "";
        private String transformOperation = "";
        private String transformTarget = "";
        private String transformSource = "";
        private String transformValue = "";
        private String inputPrompt = "";
        private String inputResponseField = "";
        private int inputTimeoutMinutes;
        private String outputField = "";

        private void tool(String name, String result) {
            toolName = name;
            resultField = result;
        }

        private void condition(WorkflowContracts.ConditionConfig config) {
            conditionField = config.field();
            conditionOperator = config.operator().name();
            conditionExpected = config.expected().orElse("");
        }

        private void transform(WorkflowContracts.TransformConfig config) {
            transformOperation = config.operation().name();
            transformTarget = config.target();
            transformSource = config.source().orElse("");
            transformValue = config.value().orElse("");
        }

        private void input(WorkflowContracts.UserInputConfig config) {
            if (config.timeout().toSeconds() % 60 != 0) {
                throw new IllegalStateException("Workflow input timeout is not editable in whole minutes");
            }
            inputPrompt = config.prompt();
            inputResponseField = config.responseField();
            inputTimeoutMinutes = Math.toIntExact(config.timeout().toMinutes());
        }

        private NodeValues build() {
            return new NodeValues(
                    toolName,
                    resultField,
                    conditionField,
                    conditionOperator,
                    conditionExpected,
                    transformOperation,
                    transformTarget,
                    transformSource,
                    transformValue,
                    inputPrompt,
                    inputResponseField,
                    inputTimeoutMinutes,
                    outputField);
        }
    }
}
