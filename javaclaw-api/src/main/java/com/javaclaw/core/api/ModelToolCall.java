package com.javaclaw.core.api;

/**
 * 模型生成的工具调用提案；这里只保存参数 JSON，执行前仍必须经过 Schema 与权限校验。
 *
 * @param id 非空白模型调用标识，用于匹配工具结果
 * @param name 非空白工具名称
 * @param argumentsJson 非空白原始参数 JSON；本类型不替代 Schema 校验
 */
public record ModelToolCall(String id, String name, String argumentsJson) {
    /** 校验调用标识、名称和参数文本非空；不授权也不执行工具。 */
    public ModelToolCall {
        id = ThreadId.required(id, "id");
        name = ThreadId.required(name, "name");
        argumentsJson = ThreadId.required(argumentsJson, "argumentsJson");
    }
}
