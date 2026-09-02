package com.javaclaw.builtin.extensions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.builtin.contracts.WorkflowContracts;
import com.javaclaw.builtin.contracts.WorkflowManagementContracts;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;

/** 在安全扁平管理值与 Workflow 强类型 Graph 之间转换。 */
final class WorkflowDefinitionMapper {
    private final ExtensionPayloadCodec payloads;

    WorkflowDefinitionMapper(ExtensionPayloadCodec payloads) {
        this.payloads = java.util.Objects.requireNonNull(payloads, "payloads");
    }

    WorkflowContracts.Definition definition(
            WorkflowManagementContracts.SaveRequest input, long revision, Instant updatedAt) {
        Map<String, List<WorkflowManagementContracts.ToolArgumentRow>> arguments = toolArguments(input);
        Map<String, List<WorkflowManagementContracts.InputFieldRow>> inputFields = inputFields(input);
        List<WorkflowContracts.Node> nodes = input.nodes().stream()
                .map(row -> node(
                        row,
                        arguments.getOrDefault(row.id(), List.of()),
                        inputFields.getOrDefault(row.id(), List.of())))
                .toList();
        requireAllOwnersUsed(arguments, nodes, WorkflowContracts.NodeKind.TOOL, "Tool argument");
        requireAllOwnersUsed(inputFields, nodes, WorkflowContracts.NodeKind.USER_INPUT, "Input field");
        List<WorkflowContracts.Edge> edges = input.edges().stream()
                .map(row -> new WorkflowContracts.Edge(row.from(), row.to(), optional(row.branch())))
                .toList();
        return new WorkflowContracts.Definition(
                input.id(), revision, input.name(), nodes, edges, input.maxVisits(), updatedAt);
    }

    WorkflowManagementContracts.SaveRequest management(WorkflowContracts.Definition definition) {
        return new WorkflowManagementProjection(payloads).management(definition);
    }

    private WorkflowContracts.Node node(
            WorkflowManagementContracts.NodeRow row,
            List<WorkflowManagementContracts.ToolArgumentRow> arguments,
            List<WorkflowManagementContracts.InputFieldRow> inputFields) {
        WorkflowContracts.NodeConfig config =
                switch (row.kind()) {
                    case START, END -> emptyConfig(row, arguments, inputFields);
                    case TURN -> turnConfig(row, arguments, inputFields);
                    case TOOL -> toolConfig(row, arguments, inputFields);
                    case CONDITION -> conditionConfig(row, arguments, inputFields);
                    case TRANSFORM -> transformConfig(row, arguments, inputFields);
                    case USER_INPUT -> inputConfig(row, arguments, inputFields);
                    case OUTPUT -> outputConfig(row, arguments, inputFields);
                };
        Optional<String> instruction = row.kind() == WorkflowContracts.NodeKind.TURN
                ? Optional.of(required(row.instruction(), "TURN instruction"))
                : Optional.empty();
        return new WorkflowContracts.Node(row.id(), row.kind(), row.name(), instruction, config);
    }

    private static WorkflowContracts.NodeConfig emptyConfig(
            WorkflowManagementContracts.NodeRow row,
            List<WorkflowManagementContracts.ToolArgumentRow> arguments,
            List<WorkflowManagementContracts.InputFieldRow> inputFields) {
        requireNoChildren(arguments, inputFields);
        requireBlank(row, "START/END");
        return WorkflowContracts.NodeConfig.empty();
    }

    private static WorkflowContracts.NodeConfig turnConfig(
            WorkflowManagementContracts.NodeRow row,
            List<WorkflowManagementContracts.ToolArgumentRow> arguments,
            List<WorkflowManagementContracts.InputFieldRow> inputFields) {
        requireNoChildren(arguments, inputFields);
        requireBlankExcept(row, Set.of("instruction"), "TURN");
        return WorkflowContracts.NodeConfig.empty();
    }

    private WorkflowContracts.NodeConfig toolConfig(
            WorkflowManagementContracts.NodeRow row,
            List<WorkflowManagementContracts.ToolArgumentRow> arguments,
            List<WorkflowManagementContracts.InputFieldRow> inputFields) {
        if (!inputFields.isEmpty()) {
            throw new IllegalArgumentException("TOOL node cannot own input fields");
        }
        requireBlankExcept(row, Set.of("toolName", "resultField"), "TOOL");
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (WorkflowManagementContracts.ToolArgumentRow argument : arguments) {
            WorkflowManagementSafety.requireNonSecretField(argument.name());
            if (values.putIfAbsent(argument.name(), scalar(argument)) != null) {
                throw new IllegalArgumentException("duplicate Tool argument name");
            }
        }
        var config = new WorkflowContracts.ToolConfig(
                required(row.toolName(), "toolName"),
                payloads.encode(values),
                required(row.resultField(), "resultField"));
        return nodeConfig(Optional.of(config), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static WorkflowContracts.NodeConfig conditionConfig(
            WorkflowManagementContracts.NodeRow row,
            List<WorkflowManagementContracts.ToolArgumentRow> arguments,
            List<WorkflowManagementContracts.InputFieldRow> inputFields) {
        requireNoChildren(arguments, inputFields);
        requireBlankExcept(row, Set.of("conditionField", "conditionOperator", "conditionExpected"), "CONDITION");
        WorkflowContracts.ConditionOperator operator =
                enumValue(WorkflowContracts.ConditionOperator.class, row.conditionOperator(), "conditionOperator");
        Optional<String> expected = optional(row.conditionExpected());
        var config = new WorkflowContracts.ConditionConfig(
                required(row.conditionField(), "conditionField"), operator, expected);
        return nodeConfig(Optional.empty(), Optional.of(config), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static WorkflowContracts.NodeConfig transformConfig(
            WorkflowManagementContracts.NodeRow row,
            List<WorkflowManagementContracts.ToolArgumentRow> arguments,
            List<WorkflowManagementContracts.InputFieldRow> inputFields) {
        requireNoChildren(arguments, inputFields);
        requireBlankExcept(
                row, Set.of("transformOperation", "transformTarget", "transformSource", "transformValue"), "TRANSFORM");
        var operation =
                enumValue(WorkflowContracts.TransformOperation.class, row.transformOperation(), "transformOperation");
        var config = new WorkflowContracts.TransformConfig(
                operation,
                required(row.transformTarget(), "transformTarget"),
                optional(row.transformSource()),
                optional(row.transformValue()));
        return nodeConfig(Optional.empty(), Optional.empty(), Optional.of(config), Optional.empty(), Optional.empty());
    }

    private WorkflowContracts.NodeConfig inputConfig(
            WorkflowManagementContracts.NodeRow row,
            List<WorkflowManagementContracts.ToolArgumentRow> arguments,
            List<WorkflowManagementContracts.InputFieldRow> fields) {
        if (!arguments.isEmpty()) {
            throw new IllegalArgumentException("USER_INPUT node cannot own Tool arguments");
        }
        requireBlankExcept(row, Set.of("inputPrompt", "inputResponseField", "inputTimeoutMinutes"), "USER_INPUT");
        if (fields.isEmpty() || row.inputTimeoutMinutes() < 1) {
            throw new IllegalArgumentException("USER_INPUT requires fields and a positive timeout");
        }
        var config = new WorkflowContracts.UserInputConfig(
                required(row.inputPrompt(), "inputPrompt"),
                responseSchema(fields),
                required(row.inputResponseField(), "inputResponseField"),
                Duration.ofMinutes(row.inputTimeoutMinutes()));
        return nodeConfig(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(config), Optional.empty());
    }

    private static WorkflowContracts.NodeConfig outputConfig(
            WorkflowManagementContracts.NodeRow row,
            List<WorkflowManagementContracts.ToolArgumentRow> arguments,
            List<WorkflowManagementContracts.InputFieldRow> inputFields) {
        requireNoChildren(arguments, inputFields);
        requireBlankExcept(row, Set.of("outputField"), "OUTPUT");
        var config = new WorkflowContracts.OutputConfig(required(row.outputField(), "outputField"));
        return nodeConfig(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(config));
    }

    private com.javaclaw.api.CanonicalPayload responseSchema(List<WorkflowManagementContracts.InputFieldRow> fields) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (WorkflowManagementContracts.InputFieldRow field : fields) {
            if (properties.putIfAbsent(field.name(), Map.of("type", schemaType(field.kind()))) != null) {
                throw new IllegalArgumentException("duplicate USER_INPUT field name");
            }
            if (field.required()) {
                required.add(field.name());
            }
        }
        return payloads.encode(Map.of(
                "additionalProperties", false, "properties", properties, "required", required, "type", "object"));
    }

    private static Map<String, List<WorkflowManagementContracts.ToolArgumentRow>> toolArguments(
            WorkflowManagementContracts.SaveRequest input) {
        Map<String, List<WorkflowManagementContracts.ToolArgumentRow>> grouped = new LinkedHashMap<>();
        input.toolArguments()
                .forEach(row -> grouped.computeIfAbsent(row.nodeId(), ignored -> new ArrayList<>())
                        .add(row));
        return grouped;
    }

    private static Map<String, List<WorkflowManagementContracts.InputFieldRow>> inputFields(
            WorkflowManagementContracts.SaveRequest input) {
        Map<String, List<WorkflowManagementContracts.InputFieldRow>> grouped = new LinkedHashMap<>();
        input.inputFields()
                .forEach(row -> grouped.computeIfAbsent(row.nodeId(), ignored -> new ArrayList<>())
                        .add(row));
        return grouped;
    }

    private static void requireAllOwnersUsed(
            Map<String, ? extends List<?>> grouped,
            List<WorkflowContracts.Node> nodes,
            WorkflowContracts.NodeKind requiredKind,
            String label) {
        Map<String, WorkflowContracts.NodeKind> kinds = new LinkedHashMap<>();
        nodes.forEach(node -> kinds.put(node.id(), node.kind()));
        grouped.keySet().forEach(id -> {
            if (kinds.get(id) != requiredKind) {
                throw new IllegalArgumentException(label + " references a node of the wrong kind");
            }
        });
    }

    private static Object scalar(WorkflowManagementContracts.ToolArgumentRow argument) {
        return switch (argument.kind()) {
            case STRING -> argument.value();
            case NUMBER -> WorkflowManagementSafety.number(argument.value());
            case BOOLEAN -> booleanValue(argument.value());
        };
    }

    private static String schemaType(WorkflowManagementContracts.InputKind kind) {
        return switch (kind) {
            case STRING -> "string";
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
        };
    }

    private static WorkflowContracts.NodeConfig nodeConfig(
            Optional<WorkflowContracts.ToolConfig> tool,
            Optional<WorkflowContracts.ConditionConfig> condition,
            Optional<WorkflowContracts.TransformConfig> transform,
            Optional<WorkflowContracts.UserInputConfig> input,
            Optional<WorkflowContracts.OutputConfig> output) {
        return new WorkflowContracts.NodeConfig(tool, condition, transform, input, output);
    }

    private static void requireNoChildren(List<?> arguments, List<?> inputFields) {
        if (!arguments.isEmpty() || !inputFields.isEmpty()) {
            throw new IllegalArgumentException("Workflow node kind cannot own child rows");
        }
    }

    private static void requireBlank(WorkflowManagementContracts.NodeRow row, String kind) {
        requireBlankExcept(row, Set.of(), kind);
    }

    private static void requireBlankExcept(WorkflowManagementContracts.NodeRow row, Set<String> allowed, String kind) {
        Map<String, String> fields = configurableFields(row);
        fields.forEach((name, value) -> {
            if (!allowed.contains(name) && !value.isBlank()) {
                throw new IllegalArgumentException(kind + " node contains inactive field " + name);
            }
        });
        if (!allowed.contains("inputTimeoutMinutes") && row.inputTimeoutMinutes() != 0) {
            throw new IllegalArgumentException(kind + " node contains inactive input timeout");
        }
    }

    private static Map<String, String> configurableFields(WorkflowManagementContracts.NodeRow row) {
        return Map.ofEntries(
                Map.entry("instruction", row.instruction()),
                Map.entry("toolName", row.toolName()),
                Map.entry("resultField", row.resultField()),
                Map.entry("conditionField", row.conditionField()),
                Map.entry("conditionOperator", row.conditionOperator()),
                Map.entry("conditionExpected", row.conditionExpected()),
                Map.entry("transformOperation", row.transformOperation()),
                Map.entry("transformTarget", row.transformTarget()),
                Map.entry("transformSource", row.transformSource()),
                Map.entry("transformValue", row.transformValue()),
                Map.entry("inputPrompt", row.inputPrompt()),
                Map.entry("inputResponseField", row.inputResponseField()),
                Map.entry("outputField", row.outputField()));
    }

    private static Optional<String> optional(String value) {
        String normalized = java.util.Objects.requireNonNull(value, "value").strip();
        return normalized.isEmpty() ? Optional.empty() : Optional.of(normalized);
    }

    private static String required(String value, String name) {
        return optional(value).orElseThrow(() -> new IllegalArgumentException(name + " must not be blank"));
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String name) {
        try {
            return Enum.valueOf(type, required(value, name));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(name + " is invalid", failure);
        }
    }

    private static boolean booleanValue(String value) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Tool boolean argument must be true or false");
        }
        return Boolean.parseBoolean(value);
    }
}
