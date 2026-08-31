package com.javaclaw.agent.model;

import java.util.List;
import java.util.Map;

/** Provider-neutral cloud embedding boundary. */
public interface EmbeddingGateway {
    /**
     * 按 inputs 顺序生成向量；Provider/model 必须由服务端配置，向量维度与 fingerprint 由结果元数据说明。
     *
     * @throws Exception Provider 未配置、输入不支持或调用失败
     */
    EmbeddingResult embed(String provider, String model, List<String> inputs) throws Exception;

    /**
     * 有序 Embedding 结果和模型元数据；调用方不得修改返回列表内的数组。
     *
     * @param vectors 与输入一一对应的非空向量列表；构造时逐数组复制，accessor 不再次深拷贝
     * @param metadata 模型/Schema 指纹等元数据；null 归一为空 Map
     */
    record EmbeddingResult(List<float[]> vectors, Map<String, String> metadata) {
        /** 逐个复制向量并固定元数据，隔离 Provider 复用的输入数组。 */
        public EmbeddingResult {
            vectors = vectors.stream().map(float[]::clone).toList();
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
