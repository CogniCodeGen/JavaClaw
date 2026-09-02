package com.javaclaw.extension.spi;

import java.util.Objects;

/**
 * 把页面权威数据绑定到 command 参数。
 *
 * <p>绑定只描述已声明数据源中的直接字段，不支持路径、表达式或脚本。Desktop 在提交时最后写入该参数，用户表单值、固定参数和行参数都不能覆盖它。
 *
 * @param argumentName command 参数名
 * @param binding 权威数据的直接字段绑定
 */
public record ViewCommandBinding(String argumentName, ViewBinding binding) {
    /** 校验并规范化参数名和绑定。 */
    public ViewCommandBinding {
        argumentName = ViewSchemaText.required(argumentName, "argumentName");
        binding = Objects.requireNonNull(binding, "binding");
    }
}
