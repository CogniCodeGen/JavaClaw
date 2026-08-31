package com.javaclaw.core.api;

/** Receives provider chunks before the complete response is assembled. */
public interface ModelStreamSink {
    ModelStreamSink IGNORE = new ModelStreamSink() {};

    /** 接收新增文本片段；调用方不得将累计全文重复作为增量发送。 */
    default void text(String fragment) {}

    /** 接收可展示的推理摘要片段；不得传递 Provider 的原始思维链。 */
    default void reasoningSummary(String fragment) {}

    /** 接收本次模型调用的用量更新；实现可忽略通知，但不应将更新重复累加为多次调用。 */
    default void usage(ModelUsage value) {}
}
