package com.javaclaw.extension.spi;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 平台拥有的结构化列表表单字段。
 *
 * <p>行对象只能包含声明的标量字段和稳定 {@code itemKey}，且最多 100 行。规范化过程拒绝未知字段、嵌套对象、嵌套数组、脚本和任意类型标记。
 *
 * @param name 提交参数名
 * @param label 展示标签
 * @param binding 初值与 dirty 比较使用的绑定
 * @param minRows 最少行数
 * @param maxRows 最多行数，硬上限为 100
 * @param itemKey 行对象中的稳定键字段名，该字段不可编辑
 * @param itemFields 行内显式标量字段
 * @param initialRows 数据源没有值时使用的规范初值
 * @param visibleWhen 可选字段级条件显示
 */
public record ViewStructuredListField(
        String name,
        String label,
        ViewBinding binding,
        int minRows,
        int maxRows,
        String itemKey,
        List<ViewStructuredItemField> itemFields,
        List<Map<String, Object>> initialRows,
        Optional<ViewCondition> visibleWhen)
        implements ViewFormField {
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    /** 列表行数硬上限。 */
    public static final int MAX_ROWS = 100;

    /** 每行可编辑字段硬上限。 */
    public static final int MAX_ITEM_FIELDS = 16;

    /** 固定选项硬上限。 */
    public static final int MAX_OPTIONS = 32;

    /** 一维文本列表元素硬上限。 */
    public static final int MAX_TEXT_LIST_ITEMS = 32;

    /** 单个静态或输入文本的字符硬上限。 */
    public static final int MAX_TEXT_LENGTH = 4_000;

    /** 一个结构化列表全部文本的字符硬上限。 */
    public static final int MAX_TOTAL_TEXT_LENGTH = 100_000;

    /** 单个列表可声明的最大行字段控件数量。 */
    public static final int MAX_RENDERED_INPUTS = 512;

    /** 校验列表规模、字段集合和初值。 */
    public ViewStructuredListField {
        name = ViewSchemaText.required(name, "name");
        label = ViewSchemaText.required(label, "label");
        Objects.requireNonNull(binding, "binding");
        itemKey = ViewSchemaText.required(itemKey, "itemKey");
        itemFields = List.copyOf(Objects.requireNonNull(itemFields, "itemFields"));
        visibleWhen = Objects.requireNonNull(visibleWhen, "visibleWhen");
        requireFieldName(name, "name");
        requireFieldName(itemKey, "itemKey");
        validateText(label);
        validateBounds(minRows, maxRows);
        validateFields(itemKey, itemFields, maxRows);
        initialRows = normalize(itemKey, itemFields, minRows, maxRows, initialRows);
    }

    /**
     * 把绑定值转换为可安全提交的规范行列表。
     *
     * @param value 数据源返回的列表值；{@code null} 使用声明初值
     * @return 不可变、类型明确且保留行顺序的值
     */
    public List<Map<String, Object>> normalizeRows(Object value) {
        if (value == null) {
            return initialRows;
        }
        if (!(value instanceof List<?> rows)) {
            throw new IllegalArgumentException("structured list binding must be an array");
        }
        return normalize(itemKey, itemFields, minRows, maxRows, rows);
    }

    /**
     * 创建一行带稳定键和字段初值的规范数据。
     *
     * @param key 本次编辑会话内唯一且稳定的行键
     * @return 可提交的新行
     */
    public Map<String, Object> newItem(String key) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put(itemKey, requireKey(key));
        itemFields.forEach(field -> row.put(field.name(), defaultValue(field)));
        return immutable(row);
    }

    private static void validateBounds(int minRows, int maxRows) {
        if (minRows < 0 || maxRows < 1 || maxRows > MAX_ROWS || minRows > maxRows) {
            throw new IllegalArgumentException("structured list row bounds are invalid");
        }
    }

    private static void validateFields(String itemKey, List<ViewStructuredItemField> fields, int maxRows) {
        if (fields.isEmpty() || fields.size() > MAX_ITEM_FIELDS) {
            throw new IllegalArgumentException("structured list item fields are invalid");
        }
        if ((long) fields.size() * maxRows > MAX_RENDERED_INPUTS) {
            throw new IllegalArgumentException("structured list declares too many rendered inputs");
        }
        Set<String> names = new HashSet<>();
        names.add(itemKey);
        for (ViewStructuredItemField field : fields) {
            if (!names.add(field.name())) {
                throw new IllegalArgumentException("structured list item field is duplicated: " + field.name());
            }
        }
    }

    private static List<Map<String, Object>> normalize(
            String itemKey, List<ViewStructuredItemField> fields, int minRows, int maxRows, Object value) {
        Objects.requireNonNull(value, "initialRows");
        if (!(value instanceof List<?> rows) || rows.size() < minRows || rows.size() > maxRows) {
            throw new IllegalArgumentException("structured list row count is outside bounds");
        }
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        Set<String> keys = new HashSet<>();
        int totalText = 0;
        for (Object valueRow : rows) {
            Map<String, Object> row = normalizeRow(itemKey, fields, valueRow);
            String key = (String) row.get(itemKey);
            if (!keys.add(key)) {
                throw new IllegalArgumentException("structured list itemKey is duplicated");
            }
            totalText += textLength(row);
            if (totalText > MAX_TOTAL_TEXT_LENGTH) {
                throw new IllegalArgumentException("structured list text is too large");
            }
            result.add(row);
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> normalizeRow(
            String itemKey, List<ViewStructuredItemField> fields, Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException("structured list row must be an object");
        }
        Set<String> allowed = new HashSet<>();
        allowed.add(itemKey);
        fields.forEach(field -> allowed.add(field.name()));
        source.keySet().forEach(key -> {
            if (!(key instanceof String text) || !allowed.contains(text)) {
                throw new IllegalArgumentException("structured list row contains an unknown field");
            }
        });
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put(itemKey, requireKey(source.get(itemKey)));
        fields.forEach(field -> result.put(field.name(), normalizeValue(field, source.get(field.name()))));
        return immutable(result);
    }

    private static Object normalizeValue(ViewStructuredItemField field, Object value) {
        Object candidate = value == null ? defaultValue(field) : value;
        return switch (field.type()) {
            case TEXT, MULTILINE -> text(candidate, field);
            case NUMBER -> number(candidate, field);
            case BOOLEAN -> booleanValue(candidate, field);
            case CHOICE -> choice(candidate, field);
            case TEXT_LIST -> textList(candidate, field);
        };
    }

    private static Object defaultValue(ViewStructuredItemField field) {
        if (field.type() == ViewStructuredItemType.TEXT_LIST) {
            return field.initialTextList();
        }
        String value = field.initialValue().orElse("");
        return switch (field.type()) {
            case BOOLEAN -> Boolean.parseBoolean(value);
            case NUMBER -> value.isBlank() ? "" : new BigDecimal(value).stripTrailingZeros();
            case CHOICE, MULTILINE, TEXT -> value;
            case TEXT_LIST -> throw new IllegalStateException("text list default is handled separately");
        };
    }

    private static String text(Object value, ViewStructuredItemField field) {
        if (!(value instanceof String text)) {
            throw invalidType(field);
        }
        validateText(text);
        return text;
    }

    private static Object number(Object value, ViewStructuredItemField field) {
        if (value instanceof String text && text.isBlank()) {
            return "";
        }
        if (!(value instanceof Number number)) {
            throw invalidType(field);
        }
        BigDecimal decimal = number instanceof BigDecimal exact ? exact : new BigDecimal(number.toString());
        return decimal.stripTrailingZeros();
    }

    private static Boolean booleanValue(Object value, ViewStructuredItemField field) {
        if (!(value instanceof Boolean bool)) {
            throw invalidType(field);
        }
        return bool;
    }

    private static String choice(Object value, ViewStructuredItemField field) {
        String selected = text(value, field);
        if (!selected.isEmpty()
                && field.options().stream().noneMatch(option -> option.value().equals(selected))) {
            throw new IllegalArgumentException(field.name() + " is not a declared choice");
        }
        return selected;
    }

    private static List<String> textList(Object value, ViewStructuredItemField field) {
        if (!(value instanceof List<?> values) || values.size() > MAX_TEXT_LIST_ITEMS) {
            throw invalidType(field);
        }
        List<String> result = new ArrayList<>(values.size());
        for (Object entry : values) {
            if (!(entry instanceof String text)) {
                throw invalidType(field);
            }
            validateText(text);
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static void validateText(String value) {
        if (value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("structured list text is too long");
        }
    }

    private static void requireFieldName(String value, String label) {
        if (!FIELD_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " is not a safe field name");
        }
    }

    private static String requireKey(Object value) {
        if (!(value instanceof String key) || key.isBlank() || key.length() > 128) {
            throw new IllegalArgumentException("structured list itemKey is invalid");
        }
        return key;
    }

    private static IllegalArgumentException invalidType(ViewStructuredItemField field) {
        return new IllegalArgumentException(field.name() + " has an invalid value type");
    }

    private static int textLength(Map<String, Object> row) {
        int length = 0;
        for (Object value : row.values()) {
            if (value instanceof String text) {
                length += text.length();
            } else if (value instanceof List<?> values) {
                length += values.stream()
                        .map(String.class::cast)
                        .mapToInt(String::length)
                        .sum();
            }
        }
        return length;
    }

    private static Map<String, Object> immutable(LinkedHashMap<String, Object> values) {
        return Collections.unmodifiableMap(values);
    }
}
