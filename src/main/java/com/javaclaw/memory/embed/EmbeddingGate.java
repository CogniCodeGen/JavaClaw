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

    /** 最近一次嵌入失败的原因（供上层在导入失败时呈现给用户）；成功时清空为 null。 */
    private volatile String lastError;

    /** 降级通知回调（整个实例生命周期只触发一次，避免每轮刷 Toast）；由上层接到 UI 通知端口。 */
    private volatile java.util.function.Consumer<String> onDegraded;
    private final java.util.concurrent.atomic.AtomicBoolean degradedNotified =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public EmbeddingGate(ModelFactory modelFactory) {
        EmbeddingModel m = null;
        try {
            m = modelFactory.createEmbeddingModel();
        } catch (Exception e) {
            this.lastError = "嵌入模型创建失败: " + describe(e);
            log.warn("嵌入模型创建失败，记忆检索将降级（无向量召回）: {}", e.getMessage());
        }
        this.model = m;
        this.dimensions = AgentConfig.getInstance().getRagEmbeddingDimensions();
    }

    /** 嵌入维度（来自配置，与 MemoryStore 向量索引一致） */
    public int dimensions() {
        return dimensions;
    }

    /** 最近一次嵌入失败原因；无失败时为 null。 */
    public String lastError() {
        return lastError;
    }

    /** 嵌入模型是否已成功创建（仅表示对象就绪，不代表端点可用）。 */
    public boolean isModelReady() {
        return model != null;
    }

    /** 设置降级通知回调（首次嵌入失败时触发一次，让用户可感知记忆系统已降级）。 */
    public void setOnDegraded(java.util.function.Consumer<String> callback) {
        this.onDegraded = callback;
    }

    /** 首次失败时通知上层（幂等，只触发一次；回调异常不影响降级语义）。 */
    private void notifyDegradedOnce() {
        java.util.function.Consumer<String> cb = onDegraded;
        if (cb != null && degradedNotified.compareAndSet(false, true)) {
            try {
                cb.accept(lastError);
            } catch (Exception e) {
                log.warn("降级通知回调异常（忽略）: {}", e.getMessage());
            }
        }
    }

    /**
     * 文本转向量；模型不可用 / 文本空 / 调用失败 一律返回 null（上层降级）。
     * 失败原因记录到 {@link #lastError()} 供上层呈现。
     */
    public float[] embed(String text) {
        if (model == null) {
            // 保留构造时记录的真实失败原因（如 build 异常），仅在没有时给通用提示
            if (lastError == null || lastError.isBlank()) {
                lastError = "嵌入模型未创建（请检查 rag.embedding.* 配置：模型名 / baseUrl / API Key）";
            }
            notifyDegradedOnce();
            return null;
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            double[] d = model.embed(TextBlock.builder().text(text).build()).block();
            if (d == null) {
                lastError = "嵌入服务返回空结果（请检查模型名是否为嵌入模型、baseUrl 是否指向 embeddings 端点）";
                notifyDegradedOnce();
                return null;
            }
            float[] f = new float[d.length];
            for (int i = 0; i < d.length; i++) {
                f[i] = (float) d[i];
            }
            lastError = null;
            return f;
        } catch (Exception e) {
            lastError = describe(e);
            log.warn("文本嵌入失败（已降级跳过）: {}", lastError);
            notifyDegradedOnce();
            return null;
        }
    }

    /** 提取异常的可读信息（含根因），便于用户判断是鉴权 / 端点 / 网络问题。 */
    private static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 4) {
            String msg = cur.getMessage();
            String part = (msg == null || msg.isBlank()) ? cur.getClass().getSimpleName() : msg.trim();
            if (sb.indexOf(part) < 0) {
                if (sb.length() > 0) sb.append(" ← ");
                sb.append(part);
            }
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }
}
