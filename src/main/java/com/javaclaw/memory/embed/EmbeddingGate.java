package com.javaclaw.memory.embed;

import com.javaclaw.agent.model.ModelFactory;

/**
 * @deprecated 使用工作区级 {@link EmbeddingGateway}。仅保留源码兼容，生产组合根不再创建本类。
 */
@Deprecated
public final class EmbeddingGate extends EmbeddingGateway {
    public EmbeddingGate(ModelFactory modelFactory) {
        super(modelFactory);
    }
}
