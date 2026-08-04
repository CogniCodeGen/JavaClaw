package com.javaclaw.memory;

import com.javaclaw.agent.TokenTracker;
import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.memory.curation.Distiller;
import com.javaclaw.memory.curation.HabitReviewer;
import com.javaclaw.memory.correction.CorrectionEngine;
import com.javaclaw.memory.correction.CorrectionGuard;
import com.javaclaw.memory.correction.CorrectionTurnContext;
import com.javaclaw.memory.embed.EmbeddingGateway;
import com.javaclaw.memory.embed.EmbeddingPurpose;
import com.javaclaw.memory.model.AgentCheckpoint;
import com.javaclaw.memory.model.ChangeLogEntry;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Persona;
import com.javaclaw.memory.retrieval.Recaller;
import com.javaclaw.memory.store.MemoryStore;
import com.javaclaw.prompt.MemoryPrompts;
import com.javaclaw.util.SensitiveDataRedactor;
import io.agentscope.core.model.ChatModelBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
    private static final long BACKGROUND_DRAIN_GRACE_MILLIS = 1_000;
    private static final long BACKGROUND_CANCEL_WAIT_MILLIS = 5_000;

    private final EmbeddingGateway gate;
    private final ChatModelBase lightModel;
    private final TokenTracker tokenTracker;
    private BackgroundWorkTracker backgroundWork = new BackgroundWorkTracker();

    private SharedStores.Lease storeLease;
    private MemoryStore store;
    private Recaller recaller;
    private Distiller distiller;
    private HabitReviewer habitReviewer;
    private CorrectionEngine correctionEngine;

    public MemoryService(ModelFactory modelFactory, TokenTracker tokenTracker) {
        this(modelFactory, tokenTracker, new EmbeddingGateway(modelFactory));
    }

    public MemoryService(ModelFactory modelFactory, TokenTracker tokenTracker,
                         EmbeddingGateway gateway) {
        this.gate = java.util.Objects.requireNonNull(gateway, "gateway");
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

    /** 打开指定工作区的记忆库文件资产目录（例如 data/memory-stores/{workspace_id}）。 */
    public synchronized void open(Path memoryDir) {
        if (store != null) {
            return;
        }
        SharedStores.Lease acquired = SharedStores.acquire(memoryDir, gate.dimensions());
        try {
            this.storeLease = acquired;
            this.store = acquired.store();
            this.recaller = new Recaller(store, gate);
            this.distiller = new Distiller(lightModel, store, gate, tokenTracker);
            this.habitReviewer = new HabitReviewer(lightModel, store, gate, tokenTracker);
            this.correctionEngine = new CorrectionEngine(
                    store, text -> gate.embed(text, EmbeddingPurpose.BACKGROUND_INDEX));
            seedDefaultPersona();
            backgroundWork.startAccepting();
            // 把「习惯回顾」注册为定时任务模块的系统内置任务手动动作（支持「立即执行」）；
            // lambda 运行时读取 this.habitReviewer，切工作区重载后自动指向新实例。
            com.javaclaw.schedule.ScheduleManager.getInstance().registerBuiltinAction(
                    "sys:habit-review", this::reviewHabitsNow);
            log.info("记忆服务已打开: {}", memoryDir);
        } catch (RuntimeException | Error failure) {
            this.storeLease = null;
            this.store = null;
            this.recaller = null;
            this.distiller = null;
            this.habitReviewer = null;
            this.correctionEngine = null;
            try {
                acquired.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** 切工作区：关闭旧库、打开新库。 */
    public synchronized void reload(Path memoryDir) {
        close();
        open(memoryDir);
    }

    @Override
    public synchronized void close() {
        BackgroundWorkTracker closingWork = backgroundWork;
        closingWork.stopAccepting();

        boolean drained = closingWork.awaitDrained(
                BACKGROUND_DRAIN_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        if (!drained) {
            log.info("记忆后台任务未在宽限期内结束，正在请求取消");
            closingWork.cancelAll();
            drained = closingWork.awaitDrained(
                    BACKGROUND_CANCEL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        }

        SharedStores.Lease closingStoreLease = storeLease;
        storeLease = null;
        store = null;
        recaller = null;
        distiller = null;
        habitReviewer = null;
        correctionEngine = null;
        // 已关闭的一代任务可能仍在响应底层网络取消；新工作区使用独立追踪器，互不串扰。
        backgroundWork = new BackgroundWorkTracker();

        if (closingStoreLease == null) {
            return;
        }
        if (drained) {
            closingStoreLease.close();
            return;
        }

        // 极端情况下底层模型调用不响应中断，旧任务继续持有这一代存储租约；同路径的新
        // MemoryService 会复用已打开的 MemoryStore，避免撞 EclipseStore 目录锁。
        log.warn("记忆后台任务取消后仍未结束，存储租约将在任务退出后延迟释放");
        Thread.ofVirtual().name("memory-deferred-close").start(() -> {
            closingWork.awaitDrained();
            try {
                closingStoreLease.close();
            } catch (RuntimeException e) {
                log.warn("延迟释放记忆存储失败: {}", e.getMessage());
            }
        });
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

    /**
     * 在本轮模型调用前同步处理用户显式纠错，并返回需要注入/守卫的上下文。
     *
     * <p>该入口不受普通蒸馏的最短输入限制，也不走异步队列，确保“错了”这类短反馈会先于
     * 下一次召回持久化。失败时保守降级为空上下文，不阻断正常聊天。</p>
     */
    public CorrectionTurnContext prepareCorrectionTurn(
            String userInput, String previousAssistantReply) {
        CorrectionEngine engine = correctionEngine;
        if (engine == null) return CorrectionTurnContext.empty();
        try {
            return engine.prepareTurn(userInput, previousAssistantReply);
        } catch (RuntimeException e) {
            log.warn("显式纠错处理部分失败，尝试从 durable 记录恢复上下文: {}", e.getMessage());
            MemoryStore current = store;
            if (current == null) return CorrectionTurnContext.empty();
            try {
                List<com.javaclaw.memory.model.CorrectionRecord> recovered =
                        CorrectionEngine.selectRelevant(current.allCorrections(), userInput, 6);
                return recovered.isEmpty()
                        ? CorrectionTurnContext.empty()
                        : new CorrectionTurnContext(recovered, null);
            } catch (RuntimeException recoveryFailure) {
                log.warn("显式纠错上下文恢复失败（降级为普通对话）: {}",
                        recoveryFailure.getMessage());
                return CorrectionTurnContext.empty();
            }
        }
    }

    /** 回复守卫拦截一次已知错误时追加审计。 */
    public void recordCorrectionGuardViolation(CorrectionGuard.Violation violation) {
        MemoryStore current = store;
        if (current == null || violation == null) return;
        try {
            current.appendChangeLog(
                    "BLOCK_REPEAT_ERROR",
                    "CorrectionRecord",
                    violation.correction().id,
                    "system",
                    violation.wrongClaim());
        } catch (RuntimeException e) {
            log.warn("记录纠错守卫审计失败（忽略）: {}", e.getMessage());
        }
    }

    /** 全部显式纠错（含已撤销项），供诊断/测试/记忆中心展示。 */
    public List<com.javaclaw.memory.model.CorrectionRecord> corrections() {
        MemoryStore current = store;
        return current == null ? List.of() : current.allCorrections();
    }

    /**
     * 撤销一条纠错：不再参与注入与记忆写入闸门，但保留记录本身可审计。
     * 适用于“这条纠错本身记错了/用户改主意了”，但仍想留痕的情况。
     */
    public void revokeCorrection(com.javaclaw.memory.model.CorrectionRecord record) {
        MemoryStore current = store;
        if (current == null || record == null) return;
        current.updateCorrection(record,
                x -> x.status = com.javaclaw.memory.model.CorrectionRecord.Status.REVOKED,
                "user");
    }

    /** 彻底删除一条纠错记录。被它废弃过的事实需另行 {@link #restoreFact} 恢复。 */
    public void deleteCorrection(com.javaclaw.memory.model.CorrectionRecord record) {
        MemoryStore current = store;
        if (current == null || record == null) return;
        current.removeCorrection(record, "user");
    }

    /** 恢复被取代/争议化的事实，使其重新参与召回与图谱。 */
    public void restoreFact(com.javaclaw.memory.model.Fact f) {
        MemoryStore current = store;
        if (current == null || f == null || f.pending) return;
        current.restoreFact(f, "user");
    }

    // ==================== 记忆写入（轮后） ====================

    /**
     * 轮后记忆：先把情景快速落入 pending 暂存区，再异步嵌入、迁入索引并蒸馏事实。
     * 这样关闭时可以安全取消耗时模型调用，而不会丢掉已经完成回复的一轮对话。
     */
    public void rememberTurn(String sessionId, String userInput, String reply, String toolTraceJson) {
        if (SensitiveDataRedactor.containsLikelyCredential(userInput)
                || SensitiveDataRedactor.containsLikelyCredential(reply)
                || SensitiveDataRedactor.containsLikelyCredential(toolTraceJson)) {
            log.warn("本轮包含疑似凭据，已跳过长期记忆与情景索引写入");
            return;
        }
        Episode ep = new Episode(sessionId, userInput, reply);
        ep.toolTraceJson = toolTraceJson;
        MemoryStore turnStore;
        Distiller turnDistiller;
        HabitReviewer reviewer;
        BackgroundWorkTracker.WorkLease lease;
        synchronized (this) {
            turnStore = this.store;
            turnDistiller = this.distiller;
            reviewer = this.habitReviewer;
            if (turnStore == null || turnDistiller == null || reviewer == null) {
                return;
            }
            lease = backgroundWork.tryAcquire();
            if (lease == null) return;
        }

        try {
            // durable-first：这一小段本地写入完成后才把租约交给可取消工作线程。
            turnStore.addPendingEpisode(ep, "system");
            Thread worker = Thread.ofVirtual()
                    .name("memory-turn-" + (sessionId == null ? "unknown" : sessionId))
                    .unstarted(() -> {
                        try {
                            if (lease.isCancellationRequested()) return;
                            float[] embedding = gate.embed(
                                    cap(userInput) + " " + cap(reply),
                                    EmbeddingPurpose.BACKGROUND_INDEX);
                            if (lease.isCancellationRequested()) return;

                            if (embedding != null) {
                                turnStore.promotePendingEpisode(ep, embedding, "system");
                                // 嵌入可用 → 顺带把此前降级暂存的条目重嵌入迁回正式索引（有界）
                                if (!lease.isCancellationRequested()
                                        && pendingCount(turnStore) > 0) {
                                    int moved = promotePending(
                                            turnStore, 25, lease::isCancellationRequested);
                                    if (moved > 0) {
                                        log.info("嵌入恢复，已迁回 {} 条暂存记忆", moved);
                                    }
                                }
                            } else {
                                log.debug("情景嵌入不可用，情景保留在 pending 暂存区");
                            }

                            if (lease.isCancellationRequested()) return;
                            turnDistiller.distillNow(ep);
                            if (lease.isCancellationRequested()) return;
                            reviewer.maybeReviewNow();
                        } catch (RuntimeException e) {
                            log.warn("rememberTurn 失败（静默）: {}", e.getMessage());
                        } finally {
                            lease.close();
                        }
                    });
            lease.onCancel(worker::interrupt);
            worker.start();
        } catch (RuntimeException e) {
            lease.close();
            log.warn("rememberTurn 调度失败（静默）: {}", e.getMessage());
        } catch (Error e) {
            lease.close();
            throw e;
        }
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
        float[] vec = gate.embed(newText, EmbeddingPurpose.BACKGROUND_INDEX);
        if (f.pending) {
            if (vec != null) {
                // 嵌入恢复：迁入正式索引
                f.text = newText;
                f.embedding = vec;
                f.userEdited = true;
                f.userAsserted = true;
                f.sourceKind = "USER_MANUAL";
                f.superseded = false; // 用户显式编辑 = 断言现行有效，复活被取代的事实
                f.contested = false;
                f.pending = false;
                store.removePendingFact(f, "user");
                store.addFact(f, "user");
            } else {
                store.updatePendingFact(f, x -> {
                    x.text = newText;
                    x.userEdited = true;
                    x.userAsserted = true;
                    x.sourceKind = "USER_MANUAL";
                    x.superseded = false;
                    x.contested = false;
                }, "user");
            }
            return;
        }
        store.updateFact(f, x -> {
            x.text = newText;
            if (vec != null) x.embedding = vec;
            x.userEdited = true;
            x.userAsserted = true;
            x.sourceKind = "USER_MANUAL";
            x.superseded = false; // 用户显式编辑 = 断言现行有效，复活被取代的事实（userEdited 保护契约优先于软删除）
            x.contested = false;
        }, "user");
    }

    /** 新增一条事实：先嵌入再入库（嵌入不可用则降级落 pending 暂存区，仍可见）。 */
    public void addFact(String section, String text) {
        if (store == null || text == null || text.isBlank()) return;
        float[] vec = gate.embed(text, EmbeddingPurpose.BACKGROUND_INDEX);
        com.javaclaw.memory.model.Fact f = new com.javaclaw.memory.model.Fact(
                section == null || section.isBlank() ? "其它" : section.trim(), text.trim(), vec);
        f.userEdited = true; // 手动新增等同用户保护，蒸馏不得静默覆盖
        f.userAsserted = true;
        f.sourceKind = "USER_MANUAL";
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

    public com.javaclaw.memory.embed.EmbeddingHealthSnapshot embeddingHealth() {
        return gate.healthSnapshot();
    }

    public AutoCloseable onEmbeddingHealthChanged(
            java.util.function.Consumer<com.javaclaw.memory.embed.EmbeddingHealthSnapshot> listener) {
        return gate.addHealthListener(listener);
    }

    /** 主动探测嵌入端点：发一次极短嵌入，刷新 {@link #embeddingError()}。建议后台线程调用。 */
    public String probeEmbedding() {
        if (store == null) return "记忆库未打开";
        gate.probe();
        return gate.lastError();
    }

    /** 待嵌入暂存条数（事实 + 情景），>0 表示曾发生嵌入降级。 */
    public int pendingCount() {
        MemoryStore current = store;
        return current == null ? 0 : pendingCount(current);
    }

    /**
     * 尝试将 pending 暂存的事实/情景重新嵌入并迁入正式索引（嵌入恢复后调用）。
     * 有界处理（各至多 {@code limit} 条），失败/仍不可用的保留在暂存区。返回成功迁回条数。
     * 建议后台线程调用。
     */
    public int promotePending(int limit) {
        MemoryStore current = store;
        return current == null ? 0 : promotePending(current, limit);
    }

    private static int pendingCount(MemoryStore target) {
        return target.allPendingFacts().size() + target.allPendingEpisodes().size();
    }

    private int promotePending(MemoryStore target, int limit) {
        return promotePending(target, limit, () -> false);
    }

    private int promotePending(MemoryStore target, int limit,
                               java.util.function.BooleanSupplier cancelled) {
        int moved = 0;
        for (com.javaclaw.memory.model.Fact f : target.allPendingFacts()) {
            if (moved >= limit || cancelled.getAsBoolean()) break;
            float[] vec = gate.embed(f.text, EmbeddingPurpose.BACKGROUND_INDEX);
            if (vec == null || cancelled.getAsBoolean()) {
                return moved; // 嵌入仍不可用/任务已取消，停止（避免逐条空转）
            }
            if (target.promotePendingFact(f, vec, "system")) {
                moved++;
            }
        }
        int movedEp = 0;
        for (com.javaclaw.memory.model.Episode e : target.allPendingEpisodes()) {
            if (movedEp >= limit || cancelled.getAsBoolean()) break;
            float[] vec = gate.embed(cap(e.userInput) + " " + cap(e.assistantReply),
                    EmbeddingPurpose.BACKGROUND_INDEX);
            if (vec == null || cancelled.getAsBoolean()) break;
            if (target.promotePendingEpisode(e, vec, "system")) {
                movedEp++;
            }
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
            float[] vec = gate.embed(c.content, EmbeddingPurpose.BACKGROUND_INDEX);
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

    private String reviewHabitsNow() {
        HabitReviewer reviewer;
        BackgroundWorkTracker.WorkLease lease;
        synchronized (this) {
            reviewer = this.habitReviewer;
            if (reviewer == null) return "记忆服务未就绪，习惯回顾不可用";
            lease = backgroundWork.tryAcquire();
            if (lease == null) return "记忆服务正在关闭，习惯回顾不可用";
        }
        lease.onCancel(Thread.currentThread()::interrupt);
        try (lease) {
            return reviewer.reviewNow();
        }
    }

    private static String cap(String s) {
        if (s == null) return "";
        s = s.strip();
        return s.length() > EMBED_TEXT_CAP ? s.substring(0, EMBED_TEXT_CAP) : s;
    }

    /**
     * 同一目录的 MemoryStore 进程内共享租约。
     *
     * <p>运行时重建时，旧记忆任务可能仍在响应模型取消。旧租约在任务真正退出前不能强关，
     * 但新运行时又必须立即打开同一路径；共享同一个单写线程 Store 可同时满足两者。最后一个
     * 租约释放时才关闭 EclipseStore 并释放目录锁。</p>
     */
    static final class SharedStores {
        private static final Map<Path, Entry> ENTRIES = new HashMap<>();

        static Lease acquire(Path memoryDir, int dimensions) {
            Path key = java.util.Objects.requireNonNull(memoryDir, "memoryDir")
                    .toAbsolutePath().normalize();
            synchronized (ENTRIES) {
                Entry entry = ENTRIES.get(key);
                if (entry == null) {
                    MemoryStore created = new MemoryStore(key, dimensions, "workspace");
                    created.open();
                    entry = new Entry(key, dimensions, created);
                    ENTRIES.put(key, entry);
                } else if (entry.dimensions != dimensions) {
                    throw new IllegalStateException(
                            "同一记忆库不能同时使用不同向量维度: path=" + key
                                    + ", opened=" + entry.dimensions
                                    + ", requested=" + dimensions);
                }
                entry.references++;
                return new Lease(entry);
            }
        }

        private static final class Entry {
            private final Path path;
            private final int dimensions;
            private final MemoryStore store;
            private int references;

            private Entry(Path path, int dimensions, MemoryStore store) {
                this.path = java.util.Objects.requireNonNull(path);
                this.dimensions = dimensions;
                this.store = java.util.Objects.requireNonNull(store);
            }
        }

        static final class Lease implements AutoCloseable {
            private final Entry entry;
            private boolean closed;

            private Lease(Entry entry) {
                this.entry = entry;
            }

            MemoryStore store() {
                synchronized (ENTRIES) {
                    if (closed) throw new IllegalStateException("记忆存储租约已释放");
                    return entry.store;
                }
            }

            @Override
            public void close() {
                synchronized (ENTRIES) {
                    if (closed) return;
                    closed = true;
                    entry.references--;
                    if (entry.references < 0) {
                        throw new IllegalStateException("记忆存储租约计数失衡: " + entry.path);
                    }
                    if (entry.references == 0) {
                        if (!ENTRIES.remove(entry.path, entry)) {
                            throw new IllegalStateException(
                                    "记忆存储注册表状态失衡: " + entry.path);
                        }
                        entry.store.close();
                    }
                }
            }
        }
    }

    /**
     * 记忆库后台任务租约。关闭流程先停止发放租约并等待存量归零，确保没有任务仍持有即将关闭的
     * MemoryStore。包级可见仅供生命周期回归测试。
     */
    static final class BackgroundWorkTracker {
        private boolean accepting;
        private final Set<WorkLease> active = new HashSet<>();

        synchronized void startAccepting() {
            if (!active.isEmpty()) {
                throw new IllegalStateException("仍有记忆后台任务未结束");
            }
            accepting = true;
        }

        synchronized WorkLease tryAcquire() {
            if (!accepting) return null;
            WorkLease lease = new WorkLease(this);
            active.add(lease);
            return lease;
        }

        synchronized void stopAccepting() {
            accepting = false;
        }

        synchronized void awaitDrained() {
            boolean interrupted = false;
            while (!active.isEmpty()) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        synchronized boolean awaitDrained(long timeout, TimeUnit unit) {
            long remaining = unit.toNanos(timeout);
            long deadline = System.nanoTime() + remaining;
            while (!active.isEmpty()) {
                if (remaining <= 0) return false;
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                remaining = deadline - System.nanoTime();
            }
            return true;
        }

        void cancelAll() {
            List<WorkLease> snapshot;
            synchronized (this) {
                snapshot = List.copyOf(active);
            }
            for (WorkLease lease : snapshot) {
                lease.cancel();
            }
        }

        private synchronized void release(WorkLease lease) {
            if (!active.remove(lease)) {
                throw new IllegalStateException("记忆后台任务租约计数失衡");
            }
            if (active.isEmpty()) notifyAll();
        }

        static final class WorkLease implements AutoCloseable {
            private final BackgroundWorkTracker owner;
            private boolean closed;
            private Runnable cancelAction;
            private boolean cancellationRequested;
            private boolean cancelActionInvoked;

            private WorkLease(BackgroundWorkTracker owner) {
                this.owner = owner;
            }

            void onCancel(Runnable action) {
                java.util.Objects.requireNonNull(action, "action");
                synchronized (this) {
                    if (closed) return;
                    if (cancelAction != null) {
                        throw new IllegalStateException("取消动作只能绑定一次");
                    }
                    cancelAction = action;
                    if (cancellationRequested && !cancelActionInvoked) {
                        cancelActionInvoked = true;
                        runCancellation(action);
                    }
                }
            }

            boolean isCancellationRequested() {
                synchronized (this) {
                    return cancellationRequested;
                }
            }

            void cancel() {
                synchronized (this) {
                    if (closed || cancellationRequested) return;
                    cancellationRequested = true;
                    if (cancelAction != null && !cancelActionInvoked) {
                        cancelActionInvoked = true;
                        runCancellation(cancelAction);
                    }
                }
            }

            private static void runCancellation(Runnable action) {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    log.warn("取消记忆后台任务失败: {}", e.getMessage());
                }
            }

            @Override
            public synchronized void close() {
                if (closed) return;
                closed = true;
                cancelAction = null;
                owner.release(this);
            }
        }
    }
}
