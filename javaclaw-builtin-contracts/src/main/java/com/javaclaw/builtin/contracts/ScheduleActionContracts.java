package com.javaclaw.builtin.contracts;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** SchedulableAction 的冻结字段、固定参数与目录身份契约。 */
public final class ScheduleActionContracts {
    private ScheduleActionContracts() {}

    /** 固定参数的有限标量类型。 */
    public enum ValueType {
        /** UTF-8 文本。 */
        STRING,
        /** 有限十进制数。 */
        NUMBER,
        /** 布尔值。 */
        BOOLEAN
    }

    /**
     * Occurrence 中冻结的字段声明。
     *
     * @param name 安全参数名
     * @param label 保存时的用户可见标签
     * @param type 标量类型
     * @param required 是否必须提供非空值
     */
    public record Field(String name, String label, ValueType type, boolean required) {
        /** 校验字段名、标签和类型。 */
        public Field {
            name = ContractValidation.text(name, "name");
            if (!name.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) {
                throw new IllegalArgumentException("action field name is unsafe");
            }
            label = ContractValidation.text(label, "label");
            if (label.length() > 240) {
                throw new IllegalArgumentException("action field label is too long");
            }
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * 一个固定参数的可审阅文本表示。
     *
     * <p>NUMBER 与 BOOLEAN 在生成调用 payload 时才转换为相应 JSON 标量；不存在脚本、表达式或动态类型转换。
     *
     * @param name 参数名
     * @param type 保存时声明的标量类型
     * @param value 固定值；可选字段使用空字符串表示省略
     */
    public record Argument(String name, ValueType type, String value) {
        /** 校验稳定名称、类型和有界文本。 */
        public Argument {
            name = ContractValidation.text(name, "name");
            Objects.requireNonNull(type, "type");
            value = Objects.requireNonNull(value, "value");
            if (value.length() > 4_000 || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("action argument is too long or contains NUL");
            }
        }
    }

    /**
     * 显式 SchedulableAction 目标。
     *
     * @param extensionId 扩展所有者
     * @param operation 扩展显式声明为可调度的 operation
     * @param fields 保存时冻结的完整字段 Schema
     * @param arguments 保存时冻结的完整固定参数
     * @param schemaHash 字段 Schema SHA-256
     * @param expectedRevision 命令目标的固定 revision；无版本目标为 0
     */
    public record Target(
            String extensionId,
            String operation,
            List<Field> fields,
            List<Argument> arguments,
            String schemaHash,
            long expectedRevision) {
        /** 校验 Action 字段、参数、Schema 摘要与 revision。 */
        public Target {
            extensionId = ContractValidation.text(extensionId, "extensionId");
            operation = ContractValidation.text(operation, "operation");
            fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
            arguments = normalizeArguments(fields, arguments);
            schemaHash = digest(schemaHash);
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("expectedRevision must not be negative");
            }
        }

        private static List<Argument> normalizeArguments(List<Field> fields, List<Argument> arguments) {
            if (fields.size() > 32
                    || fields.stream().map(Field::name).distinct().count() != fields.size()) {
                throw new IllegalArgumentException("action fields must be unique and not exceed 32");
            }
            Map<String, Argument> byName = new LinkedHashMap<>();
            for (Argument argument : List.copyOf(Objects.requireNonNull(arguments, "arguments"))) {
                if (byName.putIfAbsent(argument.name(), argument) != null) {
                    throw new IllegalArgumentException("action argument names must be unique");
                }
            }
            if (byName.size() != fields.size()) {
                throw new IllegalArgumentException("action arguments must cover every declared field");
            }
            return fields.stream()
                    .map(field -> requireArgument(field, byName.remove(field.name())))
                    .toList();
        }

        private static Argument requireArgument(Field field, Argument argument) {
            if (argument == null || argument.type() != field.type()) {
                throw new IllegalArgumentException("action argument type does not match field: " + field.name());
            }
            String value = argument.value();
            if (field.required() && value.isBlank()) {
                throw new IllegalArgumentException("required action argument is blank: " + field.name());
            }
            if (!value.isBlank()) {
                validateValue(field.type(), value);
            }
            return argument;
        }

        private static void validateValue(ValueType type, String value) {
            switch (type) {
                case STRING -> {
                    // 文本保留原值，不解释表达式或占位符。
                }
                case NUMBER -> new BigDecimal(value.strip());
                case BOOLEAN -> {
                    if (!"true".equals(value) && !"false".equals(value)) {
                        throw new IllegalArgumentException("boolean action argument must be true or false");
                    }
                }
            }
        }

        private static String digest(String value) {
            String normalized = ContractValidation.text(value, "schemaHash").toLowerCase(java.util.Locale.ROOT);
            if (!normalized.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("schemaHash must be a SHA-256 digest");
            }
            return normalized;
        }
    }
}
