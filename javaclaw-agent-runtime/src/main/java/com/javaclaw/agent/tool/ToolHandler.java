package com.javaclaw.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.sandbox.api.SandboxExecutor;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** 完成 Schema、Hook、审批和权限求交后的工具执行边界。 */
@FunctionalInterface
public interface ToolHandler {
    /**
     * 执行已治理的调用；子进程必须通过 context.sandbox，返回结果仍会统一脱敏与持久化。
     *
     * @throws Exception 工具执行失败或取消
     */
    Result execute(Context context) throws Exception;

    /**
     * 工具处理器可见的最小执行上下文；不能绕过提供的沙箱和 Item 通道。
     *
     * @param call 当前工具调用及 Thread/Turn 配置快照，调用时非空
     * @param arguments 工具参数 JSON；Hook 改写后必须重新通过 Schema 校验
     * @param sandboxPolicy 本次调用已求交的最终权限上限
     * @param sandbox 唯一受监督的子进程执行端口
     * @param events 本 Turn 的 Item 生命周期输出端口
     */
    record Context(
            ToolExecutionContext call,
            JsonNode arguments,
            SandboxPolicy sandboxPolicy,
            SandboxExecutor sandbox,
            ItemSink events) {}

    /**
     * 工具生成的审计载荷和模型结果；尚未完成最终脱敏。
     *
     * @param item 最终工具 Item，治理层要求非空
     * @param modelContent 返回模型的文本，治理层要求非空
     */
    record Result(ThreadItem item, String modelContent) {}
}
