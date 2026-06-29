package com.javaclaw.memory.embed;

import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.config.AgentConfig;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 嵌入闸门 —— 文本 → float[] 向量，统一供记忆写入/检索使用。
 *
 * <p>包裹 {@link EmbeddingModel}（OpenAI 兼容，复用 {@code rag.embedding.*}）。
 * 关键特性：<b>任何失败都返回 null 而非抛异常</b>，让上层降级（无向量召回 / 跳过该事实蒸馏），
 * 绝不阻塞主对话。AgentScope 嵌入产 {@code double[]}，这里转 {@code float[]} 以喂 JVector。</p>
 *
 * @author JavaClaw
 */
public class EmbeddingGate {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingGate.class);

    private final EmbeddingModel model;
    private final int dimensions;

    public EmbeddingGate(ModelFactory modelFactory) {
        EmbeddingModel m = null;
        try {
            m = modelFactory.createEmbeddingModel();
        } catch (Exception e) {
            log.warn("嵌入模型创建失败，记忆检索将降级（无向量召回）: {}", e.getMessage());
        }
        this.model = m;
        this.dimensions = AgentConfig.getInstance().getRagEmbeddingDimensions();
    }

    /** 嵌入维度（来自配置，与 MemoryStore 向量索引一致） */
    public int dimensions() {
        return dimensions;
    }

    /**
     * 文本转向量；模型不可用 / 文本空 / 调用失败 一律返回 null（上层降级）。
     */
    public float[] embed(String text) {
        if (model == null || text == null || text.isBlank()) {
            return null;
        }
        try {
            double[] d = model.embed(TextBlock.builder().text(text).build()).block();
            if (d == null) {
                return null;
            }
            float[] f = new float[d.length];
            for (int i = 0; i < d.length; i++) {
                f[i] = (float) d[i];
            }
            return f;
        } catch (Exception e) {
            log.warn("文本嵌入失败（已降级跳过）: {}", e.getMessage());
            return null;
        }
    }
}
