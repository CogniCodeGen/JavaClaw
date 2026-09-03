package com.javaclaw.server.model;

import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.extension.spi.EmbeddingPort;

/** 在 App Server 内按精确 Provider 版本创建 Embedding Adapter 的窄边界。 */
@FunctionalInterface
public interface EmbeddingAdapterFactory {
    /**
     * 创建一个由调用方负责关闭的精确模型 Adapter。
     *
     * @param endpoint Provider 的精确不可变版本
     * @param reference 该版本内的精确 Embedding 模型引用
     * @return 独立或可关闭的 Embedding 能力
     */
    EmbeddingPort create(ProviderEndpoint endpoint, ProviderRef reference);
}
