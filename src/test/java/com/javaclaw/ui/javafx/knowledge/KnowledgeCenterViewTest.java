package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.memory.embed.EmbeddingHealthStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeCenterViewTest {

    @Test
    void 五种嵌入健康状态都有文字与非颜色语义() {
        assertState(EmbeddingHealthStatus.UNCONFIGURED, "RAG 未配置", "未配置", "unconfigured");
        assertState(EmbeddingHealthStatus.CHECKING, "RAG 检查中", "检查中", "checking");
        assertState(EmbeddingHealthStatus.HEALTHY, "RAG 正常", "已连接", "healthy");
        assertState(EmbeddingHealthStatus.DEGRADED, "RAG 已降级", "已降级", "degraded");
        assertState(EmbeddingHealthStatus.UNAVAILABLE, "RAG 不可用", "不可用", "unavailable");
    }

    private static void assertState(EmbeddingHealthStatus status,
                                    String badge,
                                    String connection,
                                    String style) {
        var state = KnowledgeCenterView.embeddingHealthViewState(status);
        assertEquals(badge, state.badgeText());
        assertEquals(connection, state.connectionText());
        assertEquals(style, state.style());
    }
}
