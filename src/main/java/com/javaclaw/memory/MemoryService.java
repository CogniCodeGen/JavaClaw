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

    /**
     * 设置嵌入降级通知回调（首次失败触发一次）。
     * 嵌入端点配错/失效时记忆全链路静默降级，若无此通知用户可能长期毫无感知。
     */
    public void setOnEmbeddingDegraded(java.util.function.Consumer<String> callback) {
        gate.setOnDegraded(callback);
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
                        // 嵌入可用 → 顺带把此前降级暂存的条目重嵌入迁回正式索引（有界）
                        if (pendingCount() > 0) {
                            int moved = promotePending(25);
                            if (moved > 0) log.info("嵌入恢复，已迁回 {} 条暂存记忆", moved);
                        }
                    } else {
                        store.addPendingEpisode(ep, "system"); // 降级：纯文本暂存，仍可见
                        log.debug("情景嵌入不可用，降级暂存情景（本轮记忆无向量）");
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

    /** 保存结构化人格：组装为 markdown 正文（实际注入文本）并持久化结构化字段。 */
    public void setPersonaStructured(String identity, String tone,
                                     List<String> preferences, List<String> taboos) {
        if (store == null) return;
        List<String> prefs = preferences == null ? List.of() : preferences;
        List<String> tabs = taboos == null ? List.of() : taboos;
        String content = assemblePersona(identity, tone, prefs, tabs);
        store.updatePersona(p -> {
            p.structured = true;
            p.identity = identity;
            p.tone = tone;
            p.preferences = new java.util.ArrayList<>(prefs);
            p.taboos = new java.util.ArrayList<>(tabs);
            p.content = content;
        }, "user");
    }

    /** 把结构化人格字段组装成注入用 markdown 正文。 */
    public static String assemblePersona(String identity, String tone,
                                         List<String> preferences, List<String> taboos) {
        StringBuilder sb = new StringBuilder("# 人格\n");
        if (identity != null && !identity.isBlank()) {
            sb.append("\n## 身份\n").append(identity.strip()).append('\n');
        }
        if (tone != null && !tone.isBlank()) {
            sb.append("\n## 语气\n").append(tone.strip()).append('\n');
        }
        if (preferences != null && !preferences.isEmpty()) {
            sb.append("\n## 偏好\n");
            for (String p : preferences) {
                if (p != null && !p.isBlank()) sb.append("- ").append(p.strip()).append('\n');
            }
        }
        if (taboos != null && !taboos.isEmpty()) {
            sb.append("\n## 禁忌\n");
            for (String t : taboos) {
                if (t != null && !t.isBlank()) sb.append("- ").append(t.strip()).append('\n');
            }
        }
        return sb.toString();
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

    /** 全部事实：正式（已索引）+ pending（降级暂存）合并，供 UI 展示。 */
    public List<com.javaclaw.memory.model.Fact> facts() {
        if (store == null) return List.of();
        List<com.javaclaw.memory.model.Fact> out = new java.util.ArrayList<>(store.allFacts());
        out.addAll(store.allPendingFacts());
        return out;
    }

    public void deleteFact(com.javaclaw.memory.model.Fact f) {
        if (store == null) return;
        if (f.pending) store.removePendingFact(f, "user");
        else store.removeFact(f, "user");
    }

    /** 切换事实置顶位（钉住/取消钉住）；pending 事实路由到暂存区。 */
    public void togglePin(com.javaclaw.memory.model.Fact f) {
        if (store == null) return;
        if (f.pending) store.updatePendingFact(f, x -> x.pinned = !x.pinned, "user");
        else store.updateFact(f, x -> x.pinned = !x.pinned, "user");
    }

    /**
     * 编辑事实正文：重新嵌入并置 userEdited 保护位（蒸馏不得再静默覆盖）。
     * pending 事实编辑时若嵌入已恢复 → 顺带迁入正式索引；否则仍留暂存区。
     */
    public void editFact(com.javaclaw.memory.model.Fact f, String newText) {
        if (store == null) return;
        float[] vec = gate.embed(newText);
        if (f.pending) {
            if (vec != null) {
                // 嵌入恢复：迁入正式索引
                f.text = newText;
                f.embedding = vec;
                f.userEdited = true;
                f.pending = false;
                store.removePendingFact(f, "user");
                store.addFact(f, "user");
            } else {
                store.updatePendingFact(f, x -> { x.text = newText; x.userEdited = true; }, "user");
            }
            return;
        }
        store.updateFact(f, x -> {
            x.text = newText;
            if (vec != null) x.embedding = vec;
            x.userEdited = true;
        }, "user");
    }

    /** 新增一条事实：先嵌入再入库（嵌入不可用则降级落 pending 暂存区，仍可见）。 */
    public void addFact(String section, String text) {
        if (store == null || text == null || text.isBlank()) return;
        float[] vec = gate.embed(text);
        com.javaclaw.memory.model.Fact f = new com.javaclaw.memory.model.Fact(
                section == null || section.isBlank() ? "其它" : section.trim(), text.trim(), vec);
        f.userEdited = true; // 手动新增等同用户保护，蒸馏不得静默覆盖
        if (vec != null) store.addFact(f, "user");
        else store.addPendingFact(f, "user");
    }

    /** 全部情景：正式 + pending 合并，供 UI 展示。 */
    public List<com.javaclaw.memory.model.Episode> episodes() {
        if (store == null) return List.of();
        List<com.javaclaw.memory.model.Episode> out = new java.util.ArrayList<>(store.allEpisodes());
        out.addAll(store.allPendingEpisodes());
        return out;
    }

    // ==================== 降级暂存：状态与迁回 ====================

    /** 最近一次嵌入失败原因；嵌入健康时为 null（供 UI 降级横幅）。 */
    public String embeddingError() {
        return gate.lastError();
    }

    /** 主动探测嵌入端点：发一次极短嵌入，刷新 {@link #embeddingError()}。建议后台线程调用。 */
    public String probeEmbedding() {
        if (store == null) return "记忆库未打开";
        gate.embed("记忆嵌入健康探测");
        return gate.lastError();
    }

    /** 待嵌入暂存条数（事实 + 情景），>0 表示曾发生嵌入降级。 */
    public int pendingCount() {
        if (store == null) return 0;
        return store.allPendingFacts().size() + store.allPendingEpisodes().size();
    }

    /**
     * 尝试将 pending 暂存的事实/情景重新嵌入并迁入正式索引（嵌入恢复后调用）。
     * 有界处理（各至多 {@code limit} 条），失败/仍不可用的保留在暂存区。返回成功迁回条数。
     * 建议后台线程调用。
     */
    public int promotePending(int limit) {
        if (store == null) return 0;
        int moved = 0;
        for (com.javaclaw.memory.model.Fact f : store.allPendingFacts()) {
            if (moved >= limit) break;
            float[] vec = gate.embed(f.text);
            if (vec == null) return moved; // 嵌入仍不可用，停止（避免逐条空转）
            f.embedding = vec;
            f.pending = false;
            store.removePendingFact(f, "system");
            store.addFact(f, "system");
            moved++;
        }
        int movedEp = 0;
        for (com.javaclaw.memory.model.Episode e : store.allPendingEpisodes()) {
            if (movedEp >= limit) break;
            float[] vec = gate.embed(cap(e.userInput) + " " + cap(e.assistantReply));
            if (vec == null) break;
            e.embedding = vec;
            e.pending = false;
            store.removePendingEpisode(e, "system");
            store.addEpisode(e, "system");
            movedEp++;
        }
        return moved + movedEp;
    }

    /** 回填全部 pending（嵌入可用时一次性迁回正式索引，无条数上限）。返回成功迁回条数；建议后台线程调用。 */
    public int promoteAllPending() {
        return promotePending(Integer.MAX_VALUE);
    }

    public List<com.javaclaw.memory.model.EntityNode> entities() {
        return store != null ? store.allEntities() : List.of();
    }

    public List<com.javaclaw.memory.model.KnowledgeChunk> knowledge() {
        return store != null ? store.allKnowledge() : List.of();
    }

    /** 删除某文档的全部分块，返回删除数量。 */
    public int deleteKnowledgeDoc(String docName) {
        return store != null ? store.removeKnowledgeByDoc(docName, "user") : 0;
    }

    /**
     * 重建某文档的向量索引：对每个分块重新嵌入并回填向量（嵌入不可用则跳过该块）。
     * 返回成功重嵌入的分块数。建议后台线程调用。
     */
    public int reindexKnowledgeDoc(String docName) {
        if (store == null || docName == null) return 0;
        int n = 0;
        for (com.javaclaw.memory.model.KnowledgeChunk c : store.allKnowledge()) {
            if (!docName.equals(c.docName)) continue;
            float[] vec = gate.embed(c.content);
            if (vec != null) {
                store.updateKnowledgeChunk(c, x -> x.embedding = vec, "user");
                n++;
            }
        }
        return n;
    }

    /** 记忆统计（累计召回 / 命中 / 蒸馏 / 合并）；服务未就绪或无统计时返回 null。 */
    public com.javaclaw.memory.model.MemoryStats stats() {
        return store != null && store.root() != null ? store.root().stats : null;
    }

    /**
     * 物化一张记忆图谱快照（事实/情景/实体节点 + source/about/semantic 边）供 UI 渲染。
     * 纯读、含向量近邻即时检索；建议在后台线程调用（不阻塞 JavaFX 线程）。
     * 服务未就绪时返回空图。
     */
    public com.javaclaw.memory.graph.MemoryGraph graph() {
        if (store == null) {
            return com.javaclaw.memory.graph.MemoryGraph.empty();
        }
        double semThreshold = com.javaclaw.config.AgentConfig.getInstance().getMemoryGraphSemanticThreshold();
        int maxNodes = com.javaclaw.config.AgentConfig.getInstance().getMemoryGraphMaxNodes();
        var opt = new com.javaclaw.memory.graph.MemoryGraphBuilder.Options(
                maxNodes, semThreshold, 3, true, 36);
        return com.javaclaw.memory.graph.MemoryGraphBuilder.build(store, opt);
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
