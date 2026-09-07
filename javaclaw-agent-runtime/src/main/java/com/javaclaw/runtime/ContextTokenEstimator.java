package com.javaclaw.runtime;

import java.util.List;

import com.javaclaw.api.ToolDescriptor;

/** 无网络、无模型调用的上下文估算；所有来源使用同一算法，实际账单始终以 Provider usage 为准。 */
public final class ContextTokenEstimator {
    /** 随 Turn 冻结的估算算法版本。 */
    public static final String VERSION = "unicode-tools-v1";

    private ContextTokenEstimator() {}

    /**
     * 估算 Unicode 文本，避免中文按四字符计一个 token 的系统性低估。
     *
     * @param value 非空文本容器
     * @return 非负 token 估算
     */
    public static long text(String value) {
        long ascii = value.codePoints().filter(code -> code < 128).count();
        long other = value.codePointCount(0, value.length()) - ascii;
        return (ascii + 3) / 4 + other;
    }

    /**
     * 估算消息及完整工具参数，调用结果正文也在本方法计入。
     *
     * @param messages 有序消息
     * @return token 估算
     */
    public static long messages(List<ModelMessage> messages) {
        long count = 0;
        for (ModelMessage message : messages) {
            count = Math.addExact(count, text(message.text()) + 4);
            for (ModelToolCall call : message.toolCalls()) {
                count = Math.addExact(
                        count, text(call.arguments().json()) + text(call.tool().name()) + 8);
            }
        }
        return count;
    }

    /**
     * 估算不可压缩的指令和本次可见工具定义。
     *
     * @param instructions 冻结指令
     * @param tools 本次工具目录
     * @return 固定 token 开销
     */
    public static long fixed(ModelInstructions instructions, List<ToolDescriptor> tools) {
        long count = text(instructions.systemInstruction())
                + text(instructions.developerInstructions())
                + text(instructions.responseContract());
        for (ToolDescriptor tool : tools) {
            count = Math.addExact(
                    count,
                    text(tool.description())
                            + text(tool.inputSchema().json())
                            + text(tool.identity().name())
                            + 8);
        }
        return count;
    }
}
