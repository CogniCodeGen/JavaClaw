package com.javaclaw.extension.spi;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 结构化列表一行中的显式标量字段。
 *
 * @param name 行对象字段名
 * @param label 展示标签
 * @param type 平台标量类型
 * @param initialValue 非文本列表字段的字符串初值
 * @param initialTextList 一维文本列表初值
 * @param validation 有限类型校验
 * @param options CHOICE 的固定选项
 */
public record ViewStructuredItemField(
        String name,
        String label,
        ViewStructuredItemType type,
        Optional<String> initialValue,
        List<String> initialTextList,
        ViewStructuredItemValidation validation,
        List<ViewOption> options) {
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    /** 校验字段类型、初值和固定选项。 */
    public ViewStructuredItemField {
        name = ViewSchemaText.required(name, "name");
        label = ViewSchemaText.required(label, "label");
        Objects.requireNonNull(type, "type");
        initialValue = Objects.requireNonNull(initialValue, "initialValue");
        initialTextList = List.copyOf(Objects.requireNonNull(initialTextList, "initialTextList"));
        Objects.requireNonNull(validation, "validation");
        options = List.copyOf(Objects.requireNonNull(options, "options"));
        if (!FIELD_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("name is not a safe field name");
        }
        validateText(label, "label");
        validateValidation(type, validation);
        validateOptions(type, options);
        validateInitial(type, initialValue, initialTextList, options);
    }

    private static void validateValidation(ViewStructuredItemType type, ViewStructuredItemValidation validation) {
        boolean textBounds =
                validation.minLength().isPresent() || validation.maxLength().isPresent();
        boolean numberBounds =
                validation.minimum().isPresent() || validation.maximum().isPresent();
        boolean listBounds =
                validation.minItems().isPresent() || validation.maxItems().isPresent();
        if (textBounds && type != ViewStructuredItemType.TEXT && type != ViewStructuredItemType.MULTILINE) {
            throw new IllegalArgumentException("only text item fields may declare length bounds");
        }
        if (numberBounds && type != ViewStructuredItemType.NUMBER) {
            throw new IllegalArgumentException("only number item fields may declare number bounds");
        }
        if (listBounds && type != ViewStructuredItemType.TEXT_LIST) {
            throw new IllegalArgumentException("only text list item fields may declare item bounds");
        }
    }

    private static void validateOptions(ViewStructuredItemType type, List<ViewOption> options) {
        if (type == ViewStructuredItemType.CHOICE && options.isEmpty()) {
            throw new IllegalArgumentException("choice item field requires options");
        }
        if (type != ViewStructuredItemType.CHOICE && !options.isEmpty()) {
            throw new IllegalArgumentException("only choice item field may declare options");
        }
        if (options.size() > ViewStructuredListField.MAX_OPTIONS
                || options.stream()
                                .map(ViewOption::value)
                                .collect(java.util.stream.Collectors.toSet())
                                .size()
                        != options.size()) {
            throw new IllegalArgumentException("choice options are invalid");
        }
        options.forEach(option -> {
            validateText(option.value(), "option value");
            validateText(option.label(), "option label");
        });
    }

    private static void validateInitial(
            ViewStructuredItemType type,
            Optional<String> initialValue,
            List<String> initialTextList,
            List<ViewOption> options) {
        if (type == ViewStructuredItemType.TEXT_LIST) {
            if (initialValue.isPresent() || initialTextList.size() > ViewStructuredListField.MAX_TEXT_LIST_ITEMS) {
                throw new IllegalArgumentException("text list initial value is invalid");
            }
            initialTextList.forEach(value -> validateText(value, "text list value"));
            return;
        }
        if (!initialTextList.isEmpty()) {
            throw new IllegalArgumentException("only text list item field may declare initialTextList");
        }
        initialValue.ifPresent(value -> validateScalarInitial(type, value, options));
    }

    private static void validateScalarInitial(ViewStructuredItemType type, String value, List<ViewOption> options) {
        validateText(value, "initialValue");
        switch (type) {
            case BOOLEAN -> {
                if (!"true".equals(value) && !"false".equals(value)) {
                    throw new IllegalArgumentException("boolean initialValue must be true or false");
                }
            }
            case NUMBER -> new BigDecimal(value);
            case CHOICE -> {
                if (options.stream().noneMatch(option -> option.value().equals(value))) {
                    throw new IllegalArgumentException("choice initialValue is not declared");
                }
            }
            case MULTILINE, TEXT -> {
                // 文本只受长度上限约束。
            }
            case TEXT_LIST -> throw new IllegalStateException("text list is validated separately");
        }
    }

    private static void validateText(String value, String label) {
        if (Objects.requireNonNull(value, label).length() > ViewStructuredListField.MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(label + " is too long");
        }
    }
}
