package com.javaclaw.extension.spi;

import java.util.List;

import com.javaclaw.api.CancellationToken;

/**
 * 内置扩展调用向量嵌入模型的受治理端口。
 *
 * <p>端口只接收纯文本，不暴露 Provider SDK、凭据或网络配置。实现必须返回实际参与本次调用的精确模型指纹；配置热切换后，旧指纹向量不得与新指纹混算。
 */
public interface EmbeddingPort {
    /**
     * 为一批文本生成向量。
     *
     * @param texts 非空文本批次；实现可以在内部按 Provider 上限分批
     * @param purpose 文档索引或查询用途
     * @param cancellation 协作式取消信号
     * @return 精确模型指纹和与输入顺序一致的向量
     * @throws Exception Provider 不可用、调用失败或响应不合法
     */
    EmbeddingBatch embed(List<String> texts, EmbeddingPurpose purpose, CancellationToken cancellation) throws Exception;

    /**
     * 创建始终安全拒绝的端口。
     *
     * @return 未配置实现
     */
    static EmbeddingPort unavailable() {
        return (texts, purpose, cancellation) -> {
            cancellation.throwIfCancelled();
            throw new EmbeddingUnavailableException("未配置可用的 Embedding Provider");
        };
    }
}
