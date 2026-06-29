package com.javaclaw.memory.retrieval;

import com.javaclaw.config.AgentConfig;
import com.javaclaw.memory.embed.EmbeddingGate;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.model.Persona;
import com.javaclaw.memory.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 召回器 —— 每轮对话前按 query 做多索引检索，构建注入系统提示词的上下文块。
 *
 * <p>组成（取代旧 buildContextInjection 的整文件注入）：</p>
 * <ul>
 *   <li><b>人格</b>：整段注入（身份级，不参与 Top-K），永远在</li>
 *   <li><b>相关事实</b>：语义 Top-K（JVector）</li>
 *   <li><b>相关情景</b>：情景 Top-K（历史对话片段）</li>
 * </ul>
 *
 * <p>降级：嵌入不可用 / query 空 → 仅注入人格（等价于"无检索"，但对话不受影响）。</p>
 *
 * @author JavaClaw
 */
public class Recaller {

    private static final Logger log = LoggerFactory.getLogger(Recaller.class);

    private final MemoryStore store;
    private final EmbeddingGate gate;

    public Recaller(MemoryStore store, EmbeddingGate gate) {
        this.store = store;
        this.gate = gate;
    }

    /**
     * 构建本轮注入上下文。query 为空时只注入人格。
     *
     * @return {@code <loaded_context>...</loaded_context>} 文本；无任何内容时返回空串
     */
    public String recall(String query) {
        AgentConfig cfg = AgentConfig.getInstance();
        int topK = cfg.getMemoryRecallTopK();
        int epK = cfg.getMemoryRecallEpisodes();
        double threshold = cfg.getMemoryRecallThreshold();
        int budget = cfg.getMemoryRecallMaxChars();

        Persona persona = store.getPersona();
        String personaText = persona != null && persona.content != null ? persona.content.trim() : "";

        List<MemoryStore.Scored<Fact>> facts = List.of();
        List<MemoryStore.Scored<Episode>> episodes = List.of();
        float[] q = gate.embed(query);
        if (q != null) {
            try {
                facts = store.searchFacts(q, topK, threshold);
                episodes = store.searchEpisodes(q, epK, threshold);
            } catch (Exception e) {
                log.warn("记忆召回失败（已降级为仅人格注入）: {}", e.getMessage());
            }
        }

        if (personaText.isEmpty() && facts.isEmpty() && episodes.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("\n\n<loaded_context>\n");
        if (!personaText.isEmpty()) {
            sb.append("<persona>\n").append(personaText).append("\n</persona>\n");
        }

        int used = personaText.length();
        if (!facts.isEmpty()) {
            sb.append("<relevant_memory>\n");
            for (MemoryStore.Scored<Fact> s : facts) {
                String line = "- " + s.entity().text + "\n";
                if (used + line.length() > budget) break;
                sb.append(line);
                used += line.length();
            }
            sb.append("</relevant_memory>\n");
        }
        if (!episodes.isEmpty()) {
            sb.append("<relevant_history>\n");
            for (MemoryStore.Scored<Episode> s : episodes) {
                Episode e = s.entity();
                String snippet = "- 用户曾问：" + brief(e.userInput) + "\n";
                if (used + snippet.length() > budget) break;
                sb.append(snippet);
                used += snippet.length();
            }
            sb.append("</relevant_history>\n");
        }
        sb.append("</loaded_context>\n");

        log.debug("记忆召回 — 事实 {} 条，情景 {} 条，注入约 {} 字符", facts.size(), episodes.size(), used);
        return sb.toString();
    }

    private static String brief(String s) {
        if (s == null) return "";
        s = s.strip().replace("\n", " ");
        return s.length() > 100 ? s.substring(0, 100) + "…" : s;
    }
}
