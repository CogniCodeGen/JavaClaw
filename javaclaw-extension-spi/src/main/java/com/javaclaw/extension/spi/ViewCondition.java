package com.javaclaw.extension.spi;

import java.util.Objects;

/**
 * 字段条件显示声明；仅比较直接绑定值，不执行表达式。
 *
 * @param binding 依赖值
 * @param operator 固定比较操作
 * @param expectedValue 比较值
 */
public record ViewCondition(ViewBinding binding, ViewConditionOperator operator, String expectedValue) {
    /** 校验条件。 */
    public ViewCondition {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(operator, "operator");
        expectedValue = Objects.requireNonNull(expectedValue, "expectedValue");
    }
}
