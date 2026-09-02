package com.javaclaw.extension.spi;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * 结构化列表行字段的有限类型校验。
 *
 * @param required 是否必填
 * @param minLength 文本最小字符数
 * @param maxLength 文本最大字符数
 * @param minimum 数值最小值
 * @param maximum 数值最大值
 * @param minItems 文本列表最少元素数
 * @param maxItems 文本列表最多元素数，不能超过平台上限
 */
public record ViewStructuredItemValidation(
        boolean required,
        Optional<Integer> minLength,
        Optional<Integer> maxLength,
        Optional<BigDecimal> minimum,
        Optional<BigDecimal> maximum,
        Optional<Integer> minItems,
        Optional<Integer> maxItems) {
    /** 校验各类上下界。 */
    public ViewStructuredItemValidation {
        minLength = Objects.requireNonNull(minLength, "minLength");
        maxLength = Objects.requireNonNull(maxLength, "maxLength");
        minimum = Objects.requireNonNull(minimum, "minimum");
        maximum = Objects.requireNonNull(maximum, "maximum");
        minItems = Objects.requireNonNull(minItems, "minItems");
        maxItems = Objects.requireNonNull(maxItems, "maxItems");
        validateBounds(minLength, maxLength, ViewStructuredListField.MAX_TEXT_LENGTH, "length");
        validateBounds(minItems, maxItems, ViewStructuredListField.MAX_TEXT_LIST_ITEMS, "items");
        if (minimum.isPresent() && maximum.isPresent() && minimum.get().compareTo(maximum.get()) > 0) {
            throw new IllegalArgumentException("number bounds are invalid");
        }
    }

    /**
     * 创建只声明必填语义的校验。
     *
     * @param required 是否必填
     * @return 校验定义
     */
    public static ViewStructuredItemValidation required(boolean required) {
        return new ViewStructuredItemValidation(
                required,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static void validateBounds(
            Optional<Integer> minimum, Optional<Integer> maximum, int hardMaximum, String label) {
        if (minimum.filter(value -> value < 0 || value > hardMaximum).isPresent()
                || maximum.filter(value -> value < 0 || value > hardMaximum).isPresent()
                || minimum.isPresent() && maximum.isPresent() && minimum.get() > maximum.get()) {
            throw new IllegalArgumentException(label + " bounds are invalid");
        }
    }
}
