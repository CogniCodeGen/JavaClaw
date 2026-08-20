package com.javaclaw.inference.api;

/** 模型工具选择约束；运行时必须显式声明其能够执行的非默认语义。 */
public record InferenceToolChoice(Mode mode, String toolName) {

    public InferenceToolChoice {
        if (mode == null) throw new IllegalArgumentException("工具选择模式不能为空");
        toolName = toolName == null ? "" : toolName.strip();
        if (mode == Mode.NAMED && toolName.isBlank()) {
            throw new IllegalArgumentException("指定工具模式必须提供工具名称");
        }
        if (mode != Mode.NAMED && !toolName.isBlank()) {
            throw new IllegalArgumentException("只有指定工具模式可以提供工具名称");
        }
    }

    public enum Mode { AUTO, NONE, REQUIRED, NAMED }

    public static InferenceToolChoice auto() { return new InferenceToolChoice(Mode.AUTO, ""); }
    public static InferenceToolChoice none() { return new InferenceToolChoice(Mode.NONE, ""); }
    public static InferenceToolChoice required() { return new InferenceToolChoice(Mode.REQUIRED, ""); }
    public static InferenceToolChoice named(String toolName) {
        return new InferenceToolChoice(Mode.NAMED, toolName);
    }
}
