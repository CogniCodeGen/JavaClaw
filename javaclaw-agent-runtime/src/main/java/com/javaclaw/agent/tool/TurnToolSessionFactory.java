package com.javaclaw.agent.tool;

import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.TurnExecutionContext;

/** Opens a bounded, immutable tool session for each Turn. */
public interface TurnToolSessionFactory extends AutoCloseable {
    /** 为本 Turn 建立固定目录和资源作用域；调用方应使用 try-with-resources 关闭返回会话。 */
    TurnToolSession open(TurnExecutionContext context, ItemSink events);

    @Override
    default void close() {}
}
