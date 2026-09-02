package com.javaclaw.builtin.extensions;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.ScheduleActionContracts;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ScheduleTargetCatalogPort;

/** 在目录字段、持久 Definition 与最终 JSON payload 之间转换固定 Action 参数。 */
final class ScheduleActionParameters {
    private ScheduleActionParameters() {}

    static ScheduleActionContracts.Target target(
            ScheduleTargetCatalogPort.ActionOption option, List<ScheduleActionContracts.Argument> arguments) {
        ScheduleTargetCatalogPort.ActionOption checked = Objects.requireNonNull(option, "option");
        List<ScheduleActionContracts.Field> fields =
                checked.fields().stream().map(ScheduleActionParameters::field).toList();
        return new ScheduleActionContracts.Target(
                checked.extensionId(),
                checked.operation(),
                fields,
                arguments,
                checked.schemaHash(),
                checked.expectedRevision());
    }

    static CanonicalPayload payload(ScheduleActionContracts.Target target, ExtensionPayloadCodec payloads) {
        ScheduleActionContracts.Target checked = Objects.requireNonNull(target, "target");
        Map<String, Object> values = new LinkedHashMap<>();
        for (ScheduleActionContracts.Argument argument : checked.arguments()) {
            if (argument.value().isBlank()) {
                continue;
            }
            values.put(argument.name(), value(argument));
        }
        return Objects.requireNonNull(payloads, "payloads").encode(values);
    }

    static List<ScheduleActionContracts.Argument> emptyArguments(ScheduleTargetCatalogPort.ActionOption option) {
        return Objects.requireNonNull(option, "option").fields().stream()
                .map(field -> new ScheduleActionContracts.Argument(
                        field.name(),
                        ScheduleActionContracts.ValueType.valueOf(field.type().name()),
                        ""))
                .toList();
    }

    private static ScheduleActionContracts.Field field(ScheduleTargetCatalogPort.ActionField field) {
        return new ScheduleActionContracts.Field(
                field.name(),
                field.label(),
                ScheduleActionContracts.ValueType.valueOf(field.type().name()),
                field.required());
    }

    private static Object value(ScheduleActionContracts.Argument argument) {
        return switch (argument.type()) {
            case STRING -> argument.value();
            case NUMBER -> new BigDecimal(argument.value().strip()).stripTrailingZeros();
            case BOOLEAN -> Boolean.valueOf(argument.value());
        };
    }
}
