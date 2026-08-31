package com.javaclaw.agent.model;

import com.javaclaw.core.api.ModelRequest;
import com.javaclaw.core.api.ModelResponse;
import com.javaclaw.core.api.ModelStreamSink;
import com.javaclaw.core.api.TurnConfig;

/** Cloud-provider adapter boundary. Model implementations do not own thread state. */
@FunctionalInterface
public interface ModelGateway {
    /**
     * 完成一次非流式模型调用，返回最终文本、工具提案和用量；不得执行模型提出的工具。
     *
     * @throws Exception Provider 调用失败或取消
     */
    ModelResponse complete(ModelRequest request) throws Exception;

    /** Providers override for true streaming; the fallback emits one bounded final chunk. */
    default ModelResponse stream(ModelRequest request, ModelStreamSink sink) throws Exception {
        ModelResponse response = complete(request);
        if (!response.text().isEmpty()) {
            sink.text(response.text());
        }
        if (!response.reasoningSummary().isEmpty()) {
            sink.reasoningSummary(response.reasoningSummary());
        }
        sink.usage(response.usage());
        return response;
    }

    /** 返回给定 Provider 配置的压缩能力；默认使用可移植摘要，不猜测兼容端点能力。 */
    default CompactionStrategy compactionStrategy(TurnConfig config) {
        return CompactionStrategy.SUMMARY;
    }

    /** 调用 Provider 原生 compact endpoint；不支持时明确失败，不在同一次操作中自动降级。 */
    default NativeCompactionResult compact(ModelRequest request) throws Exception {
        throw new UnsupportedOperationException("native context compaction is unavailable");
    }
}
