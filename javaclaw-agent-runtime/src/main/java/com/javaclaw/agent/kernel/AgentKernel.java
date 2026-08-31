package com.javaclaw.agent.kernel;

import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.TurnExecutionContext;

/** Provider-neutral execution kernel owned by the Agent Runtime. */
@FunctionalInterface
public interface AgentKernel {
    /**
     * 在给定 Turn 上下文内执行一次请求，通过 sink 发布 Item；取消时必须停止后续副作用。
     *
     * @throws Exception 模型、工具或上下文构建失败；运行时负责将失败收敛为 Turn 终态
     */
    void execute(TurnExecutionContext context, ItemSink sink) throws Exception;
}
