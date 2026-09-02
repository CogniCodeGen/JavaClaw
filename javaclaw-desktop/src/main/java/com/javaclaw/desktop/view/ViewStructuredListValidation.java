package com.javaclaw.desktop.view;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.extension.spi.ViewStructuredItemField;
import com.javaclaw.extension.spi.ViewStructuredItemValidation;
import com.javaclaw.extension.spi.ViewStructuredListField;

/** 在提交前校验结构化列表的行数、稳定键、类型边界和总文本量。 */
final class ViewStructuredListValidation {
    private ViewStructuredListValidation() {}

    static Optional<String> validate(ViewStructuredListField definition, List<Map<String, Object>> rows) {
        if (rows.size() < definition.minRows() || rows.size() > definition.maxRows()) {
            return Optional.of(definition.label() + "行数不符合限制");
        }
        int totalText = 0;
        Set<Object> keys = new HashSet<>();
        for (Map<String, Object> row : rows) {
            if (!keys.add(row.get(definition.itemKey()))) {
                return Optional.of(definition.label() + "包含重复行键");
            }
            for (ViewStructuredItemField field : definition.itemFields()) {
                Optional<String> failure = validate(field, row.get(field.name()));
                if (failure.isPresent()) {
                    return failure;
                }
            }
            totalText += textLength(row);
            if (totalText > ViewStructuredListField.MAX_TOTAL_TEXT_LENGTH) {
                return Optional.of(definition.label() + "文本总量超出限制");
            }
        }
        return Optional.empty();
    }

    private static Optional<String> validate(ViewStructuredItemField field, Object value) {
        ViewStructuredItemValidation validation = field.validation();
        if (isEmpty(value)) {
            return validation.required() ? Optional.of(field.label() + "不能为空") : Optional.empty();
        }
        return switch (field.type()) {
            case NUMBER -> validateNumber(field, value, validation);
            case TEXT_LIST -> validateTextList(field, value, validation);
            case MULTILINE, TEXT -> validateText(field, (String) value, validation);
            case BOOLEAN, CHOICE -> Optional.empty();
        };
    }

    private static boolean isEmpty(Object value) {
        return value == null
                || value instanceof String text && text.isBlank()
                || value instanceof List<?> values && values.isEmpty();
    }

    private static Optional<String> validateText(
            ViewStructuredItemField field, String text, ViewStructuredItemValidation validation) {
        if (text.length() > ViewStructuredListField.MAX_TEXT_LENGTH
                || validation
                        .maxLength()
                        .filter(maximum -> text.length() > maximum)
                        .isPresent()) {
            return Optional.of(field.label() + "长度超出限制");
        }
        if (validation.minLength().filter(minimum -> text.length() < minimum).isPresent()) {
            return Optional.of(field.label() + "长度不足");
        }
        return Optional.empty();
    }

    private static Optional<String> validateNumber(
            ViewStructuredItemField field, Object value, ViewStructuredItemValidation validation) {
        if (!(value instanceof BigDecimal number)) {
            return Optional.of(field.label() + "必须是数值");
        }
        if (validation
                .minimum()
                .filter(minimum -> number.compareTo(minimum) < 0)
                .isPresent()) {
            return Optional.of(field.label() + "小于允许的最小值");
        }
        if (validation
                .maximum()
                .filter(maximum -> number.compareTo(maximum) > 0)
                .isPresent()) {
            return Optional.of(field.label() + "大于允许的最大值");
        }
        return Optional.empty();
    }

    private static Optional<String> validateTextList(
            ViewStructuredItemField field, Object value, ViewStructuredItemValidation validation) {
        if (!(value instanceof List<?> values)
                || values.stream().anyMatch(entry -> !(entry instanceof String))
                || values.size() > ViewStructuredListField.MAX_TEXT_LIST_ITEMS
                || validation
                        .maxItems()
                        .filter(maximum -> values.size() > maximum)
                        .isPresent()) {
            return Optional.of(field.label() + "条目数量或类型不符合限制");
        }
        if (validation.minItems().filter(minimum -> values.size() < minimum).isPresent()) {
            return Optional.of(field.label() + "条目数量不足");
        }
        if (values.stream()
                .map(String.class::cast)
                .anyMatch(text -> text.length() > ViewStructuredListField.MAX_TEXT_LENGTH)) {
            return Optional.of(field.label() + "包含过长文本");
        }
        return Optional.empty();
    }

    private static int textLength(Map<String, Object> row) {
        int total = 0;
        for (Object value : row.values()) {
            if (value instanceof String text) {
                total += text.length();
            } else if (value instanceof List<?> texts) {
                total += texts.stream()
                        .map(String.class::cast)
                        .mapToInt(String::length)
                        .sum();
            }
        }
        return total;
    }
}
