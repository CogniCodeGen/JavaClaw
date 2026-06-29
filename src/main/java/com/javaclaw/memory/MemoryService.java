package com.javaclaw.memory;

import com.javaclaw.agent.TokenTracker;
import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.memory.curation.Distiller;
import com.javaclaw.memory.embed.EmbeddingGate;
import com.javaclaw.memory.model.AgentCheckpoint;
import com.javaclaw.memory.model.ChangeLogEntry;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Persona;
import com.javaclaw.memory.retrieval.Recaller;
import com.javaclaw.memory.store.MemoryStore;
import com.javaclaw.prompt.MemoryPrompts;
import io.agentscope.core.model.ChatModelBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.List;

/**
 * 记忆服务门面 —— 上层（ChatService 等）唯一入口，整合存储基座 + 嵌入 + 召回 + 蒸馏。
 *
 * <p>取代旧 {@code WorkspaceContextFiles} + {@code MemoryCurator} 双件：</p>
 * <ul>
 *   <li>{@link #recall(String)} 每轮注入（人格 + 相关事实 + 相关情景），替代 buildContextInjection</li>
 *   <li>{@link #rememberTurn} 轮后落情景 + 异步蒸馏事实，替代 distillFromTurn/consolidate</li>
 *   <li>人格/检查点/变更日志 透传 {@link MemoryStore}</li>
 *   <li>{@link #reload(Path)} 切工作区时重开库</li>
 * </ul>
 *
 * <p>全流程失败静默、降级不阻塞主对话。</p>
 *
 * @author JavaClaw
 */
public class MemoryService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private static final int EMBED_TEXT_CAP = 2000;

    private final EmbeddingGate gate;
    private final ChatModelBase lightModel;
    private final TokenTracker tokenTracker;

    private MemoryStore store;
    private Recaller recaller;
    private Distiller distiller;

    public MemoryService(ModelFactory modelFactory, TokenTracker tokenTracker) {
        this.gate = new EmbeddingGate(modelFactory);
        this.lightModel = modelFactory.createLightChatModel();
        this.tokenTracker = tokenTracker;
    }

    // ==================== 生命周期 ====================

    /** 打开指定工作区的记忆库（目录如 {workspace}/data/memory-store）。 */
    public synchronized void open(Path memoryDir) {
        if (store != null) {
            return;
        }
        this.store = new MemoryStore(memoryDir, gate.dimensions(), "workspace");
        this.store.open();
        this.recaller = new Recaller(store, gate);
        this.distiller = new Distiller(lightModel, store, gate, tokenTracker);
        seedDefaultPersona();
        log.info("记忆服务已打开: {}", memoryDir);
    }

    /** 切工作区：关闭旧库、打开新库。 */
    public synchronized void reload(Path memoryDir) {
        close();
        open(memoryDir);
    }

    @Override
    public synchronized void close() {
        if (store != null) {
            store.close();
            store = null;
            recaller = null;
            distiller = null;
        }
    }

    private void seedDefaultPersona() {
        if (store.getPersona() == null) {
            store.setPersona(MemoryPrompts.DEFAULT_AGENTS_SKELETON, "system");
            log.info("已写入默认人格骨架");
        }
    }

    // ==================== 召回（注入） ====================

    /** 构建本轮注入上下文；服务未就绪时返回空串。 */
    public String recall(String query) {
        if (recaller == null) {
            return "";
        }
        try {
            return recaller.recall(query);
        } catch (Exception e) {
            log.warn("记忆召回异常（已降级为空注入）: {}", e.getMessage());
            return "";
        }
    }

    // ==================== 记忆写入（轮后） ====================

    /**
     * 轮后记忆：异步落情景 + 蒸馏事实。失败静默，不阻塞调用方。
     * 嵌入不可用时跳过（无向量则不入索引，记忆本轮降级）。
     */
    public void rememberTurn(String sessionId, String userInput, String reply, String toolTraceJson) {
        if (store == null || distiller == null) {
            return;
        }
        Episode ep = new Episode(sessionId, userInput, reply);
        ep.toolTraceJson = toolTraceJson;
        Mono.fromRunnable(() -> {
                    ep.embedding = gate.embed(cap(userInput) + " " + cap(reply));
                    if (ep.embedding != null) {
                        store.addEpisode(ep, "system");
                    } else {
                        log.debug("情景嵌入不可用，跳过情景落库（本轮记忆降级）");
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(distiller.distill(ep))
                .subscribe(null, e -> log.warn("rememberTurn 失败（静默）: {}", e.getMessage()));
    }

    // ==================== 人格 / 检查点 / 审计 透传 ====================

    public Persona getPersona() {
        return store != null ? store.getPersona() : null;
    }

    public void setPersona(String content, String actor) {
        if (store != null) store.setPersona(content, actor);
    }

    public void checkpoint(String key, String messagesJson) {
        if (store != null) store.checkpoint(key, messagesJson);
    }

    public AgentCheckpoint loadCheckpoint(String key) {
        return store != null ? store.loadCheckpoint(key) : null;
    }

    public void deleteCheckpoint(String key) {
        if (store != null) store.removeCheckpoint(key);
    }

    public List<ChangeLogEntry> recentChangeLog(int limit) {
        return store != null ? store.recentChangeLog(limit) : List.of();
    }

    // ==================== 记忆中心 UI 便捷方法 ====================

    public List<com.javaclaw.memory.model.Fact> facts() {
        return store != null ? store.allFacts() : List.of();
    }

    public void deleteFact(com.javaclaw.memory.model.Fact f) {
        if (store != null) store.removeFact(f, "user");
    }

    /** 编辑事实正文：重新嵌入并置 userEdited 保护位（蒸馏不得再静默覆盖）。 */
    public void editFact(com.javaclaw.memory.model.Fact f, String newText) {
        if (store == null) return;
        float[] vec = gate.embed(newText);
        store.updateFact(f, x -> {
            x.text = newText;
            if (vec != null) x.embedding = vec;
            x.userEdited = true;
        }, "user");
    }

    public MemoryStore store() {
        return store;
    }

    private static String cap(String s) {
        if (s == null) return "";
        s = s.strip();
        return s.length() > EMBED_TEXT_CAP ? s.substring(0, EMBED_TEXT_CAP) : s;
    }
}
