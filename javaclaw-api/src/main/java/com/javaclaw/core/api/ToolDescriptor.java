package com.javaclaw.core.api;

/**
 * 模型可见的工具名称、说明和输入 Schema；不携带执行权限或实现对象。
 *
 * @param name 非空白工具名称
 * @param description 展示与模型提示说明；null 归一为空字符串
 * @param inputSchemaJson 非空白 JSON Schema 文本，执行前由治理管线校验
 */
public record ToolDescriptor(String name, String description, String inputSchemaJson) {
    /** 校验名称和 Schema 文本非空，保留原有 Schema 内容不做重写。 */
    public ToolDescriptor {
        name = ThreadId.required(name, "name");
        description = description == null ? "" : description;
        inputSchemaJson = ThreadId.required(inputSchemaJson, "inputSchemaJson");
    }
}
