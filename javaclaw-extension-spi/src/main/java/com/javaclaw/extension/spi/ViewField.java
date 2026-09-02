package com.javaclaw.extension.spi;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ViewSchema v2 表单字段。
 *
 * @param name 提交参数名
 * @param label 展示标签
 * @param type 平台字段类型
 * @param binding 初值与 dirty 比较使用的绑定
 * @param initialValue 数据源没有值时使用的字符串初值
 * @param validation 类型校验
 * @param options 静态选项
 * @param optionSource 可选动态选项来源
 * @param visibleWhen 可选条件显示
 */
public record ViewField(
        String name,
        String label,
        ViewFieldType type,
        ViewBinding binding,
        Optional<String> initialValue,
        ViewFieldValidation validation,
        List<ViewOption> options,
        Optional<ViewOptionSource> optionSource,
        Optional<ViewCondition> visibleWhen)
        implements ViewFormField {
    /** 校验字段配置和初值类型。 */
    public ViewField {
        name = ViewSchemaText.required(name, "name");
        label = ViewSchemaText.required(label, "label");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(binding, "binding");
        initialValue = Objects.requireNonNull(initialValue, "initialValue");
        Objects.requireNonNull(validation, "validation");
        options = List.copyOf(Objects.requireNonNull(options, "options"));
        optionSource = Objects.requireNonNull(optionSource, "optionSource");
        visibleWhen = Objects.requireNonNull(visibleWhen, "visibleWhen");
        validateChoice(type, options, optionSource);
        validateAttachment(type, validation);
        validateInitial(type, initialValue);
    }

    private static void validateAttachment(ViewFieldType type, ViewFieldValidation validation) {
        if (type == ViewFieldType.ATTACHMENT && validation.attachment().isEmpty()) {
            throw new IllegalArgumentException("attachment field requires an upload policy");
        }
        if (type != ViewFieldType.ATTACHMENT && validation.attachment().isPresent()) {
            throw new IllegalArgumentException("only attachment field may declare an upload policy");
        }
    }

    private static void validateChoice(
            ViewFieldType type, List<ViewOption> options, Optional<ViewOptionSource> optionSource) {
        boolean declaresOptions = !options.isEmpty() || optionSource.isPresent();
        if (type == ViewFieldType.CHOICE && !declaresOptions) {
            throw new IllegalArgumentException("choice field requires options");
        }
        if (type != ViewFieldType.CHOICE && declaresOptions) {
            throw new IllegalArgumentException("only choice field may declare options");
        }
    }

    private static void validateInitial(ViewFieldType type, Optional<String> initialValue) {
        if (type == ViewFieldType.ATTACHMENT && initialValue.isPresent()) {
            throw new IllegalArgumentException("attachment fields must not declare initialValue");
        }
        if (type == ViewFieldType.BOOLEAN
                && initialValue
                        .filter(value -> !"true".equals(value) && !"false".equals(value))
                        .isPresent()) {
            throw new IllegalArgumentException("boolean initialValue must be true or false");
        }
        if (type == ViewFieldType.NUMBER) {
            initialValue.ifPresent(value -> new BigDecimal(value));
        }
    }
}
