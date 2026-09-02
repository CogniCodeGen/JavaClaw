package com.javaclaw.extension.spi;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * 平台执行的有限类型校验；不接受正则或可执行表达式。
 *
 * @param required 是否必填
 * @param minLength 最小字符数
 * @param maxLength 最大字符数
 * @param minimum 最小数值
 * @param maximum 最大数值
 * @param attachment Attachment 上传策略；仅 Attachment 字段使用
 */
public record ViewFieldValidation(
        boolean required,
        Optional<Integer> minLength,
        Optional<Integer> maxLength,
        Optional<BigDecimal> minimum,
        Optional<BigDecimal> maximum,
        Optional<ViewAttachmentPolicy> attachment) {
    /** 校验上下界。 */
    public ViewFieldValidation {
        minLength = Objects.requireNonNull(minLength, "minLength");
        maxLength = Objects.requireNonNull(maxLength, "maxLength");
        minimum = Objects.requireNonNull(minimum, "minimum");
        maximum = Objects.requireNonNull(maximum, "maximum");
        attachment = Objects.requireNonNull(attachment, "attachment");
        if (minLength.filter(value -> value < 0).isPresent()
                || maxLength.filter(value -> value < 0).isPresent()
                || minLength.isPresent() && maxLength.isPresent() && minLength.get() > maxLength.get()) {
            throw new IllegalArgumentException("length bounds are invalid");
        }
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
    public static ViewFieldValidation required(boolean required) {
        return new ViewFieldValidation(
                required, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * 创建 Attachment 字段校验。
     *
     * @param required 是否必填
     * @param policy 媒体类型与原始字节上限
     * @return Attachment 校验定义
     */
    public static ViewFieldValidation attachment(boolean required, ViewAttachmentPolicy policy) {
        return new ViewFieldValidation(
                required,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(Objects.requireNonNull(policy, "policy")));
    }
}
