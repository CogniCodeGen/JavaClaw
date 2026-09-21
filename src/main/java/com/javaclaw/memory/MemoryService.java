package com.javaclaw.memory;

import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.memory.curation.Distiller;
import com.javaclaw.memory.curation.HabitReviewer;
import com.javaclaw.memory.correction.CorrectionEngine;
import com.javaclaw.memory.correction.CorrectionGuard;
import com.javaclaw.memory.correction.CorrectionTurnContext;
import com.javaclaw.memory.embed.EmbeddingGateway;
import com.javaclaw.memory.embed.EmbeddingPurpose;
import com.javaclaw.memory.model.ChangeLogEntry;
import com.javaclaw.memory.model.AgentCheckpoint;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Persona;
import com.javaclaw.memory.retrieval.Recaller;
import com.javaclaw.memory.store.MemoryStore;
import com.javaclaw.platform.execution.TaskContext;
import com.javaclaw.platform.execution.TaskHandle;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.prompt.MemoryPrompts;
import com.javaclaw.util.SensitiveDataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Scoped memory lifecycle, durable raw-turn projection and bounded asynchronous curation. */
public class MemoryService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private static final int EMBED_TEXT_CAP = 2000;
    private static final long BACKGROUND_DRAIN_GRACE_MILLIS = 1_000;
    private static final long BACKGROUND_CANCEL_WAIT_MILLIS = 5_000;

    private final EmbeddingGateway gate;
    private final ModelTaskGateway modelTasks;
    private final TaskScope tasks;
    private final AgentConfig settings;
    private final java.util.concurrent.atomic.AtomicReference<RunId> lastOwnerRun =
            new java.util.concurrent.atomic.AtomicReference<>();
    private MemoryTaskTracker backgroundWork = new MemoryTaskTracker();

    private MemoryStoreRegistry.Lease storeLease;
    private MemoryStore store;
    private Recaller recaller;
    private Distiller distiller;
    private HabitReviewer habitReviewer;
    private CorrectionEngine correctionEngine;
    private Path graphRoot;
    private MemoryGraphScope graphScope;
    private MemoryService graphOwner;
    private volatile boolean catalogAccepting;
    private final java.util.concurrent.ConcurrentMap<MemoryGraphScope, MemoryService> graphs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> activeTurns = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<MemoryGraphScope> replayScopes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean resumedPending = new java.util.concurrent.atomic.AtomicBoolean();
    private MemoryGraphJournal graphJournal;
    private volatile boolean replayingFork;
    private MemoryHistoryRecovery historyRecovery;
    private java.util.function.BiConsumer<com.javaclaw.framework.api.ThreadSnapshot,
            List<com.javaclaw.framework.api.ThreadEvent>> historyReplayer, historyCatchup;

    public MemoryService(ModelTaskGateway modelTasks, EmbeddingGateway gateway,
                         TaskScope tasks, AgentConfig settings) {
        this.gate = java.util.Objects.requireNonNull(gateway, "gateway");
        this.modelTasks = java.util.Objects.requireNonNull(modelTasks, "modelTasks");
        this.tasks = java.util.Objects.requireNonNull(tasks, "tasks");
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
    }

    /** Surface the first embedding degradation without blocking a conversation. */
    public void setOnEmbeddingDegraded(java.util.function.Consumer<String> callback) {
        gate.setOnDegraded(callback);
    }


    /** 打开指定工作区的记忆库文件资产目录（例如 data/memory-stores/{workspace_id}）。 */
    public synchronized void open(Path memoryDir) {
        if (store != null) {
            return;
        }
        MemoryStoreRegistry.Lease acquired = MemoryStoreRegistry.acquire(
                memoryDir, gate.dimensions());
        try {
            this.storeLease = acquired;
            this.store = acquired.store();
            this.recaller = new Recaller(store, gate, settings);
            this.distiller = new Distiller(modelTasks, store, gate, settings);
            this.habitReviewer = new HabitReviewer(modelTasks, store, gate, settings);
            this.correctionEngine = new CorrectionEngine(
                    store, text -> gate.embed(text, EmbeddingPurpose.BACKGROUND_INDEX));
            seedDefaultPersona();
            backgroundWork.startAccepting();
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

    /** Production entry point: the old mixed store is retained solely as unassigned history. */
    public synchronized void open(Path memoryDir, String workspaceId, String userId) {
        if (store != null) return;
        graphRoot = memoryDir.toAbsolutePath().normalize();
        graphScope = new MemoryGraphScope(workspaceId, userId, "", MemoryGraphScope.Kind.WORKSPACE_HABITS);
        graphOwner = this;
        open(graphScope.directory(graphRoot));
        catalogAccepting = true;
    }

    public int migrateLegacy(com.fasterxml.jackson.databind.ObjectMapper json,
                             java.util.function.Predicate<String> knownThread) {
        return LegacyMemoryMigration.migrate(this, java.util.Objects.requireNonNull(json),
                java.util.Objects.requireNonNull(knownThread));
    }

    public MemoryGraphScope defaultScope() { return graphScope; }
    public void onHistoryRecovery(java.util.function.BiConsumer<com.javaclaw.framework.api.ThreadSnapshot,
            List<com.javaclaw.framework.api.ThreadEvent>> replay,
            java.util.function.BiConsumer<com.javaclaw.framework.api.ThreadSnapshot, List<com.javaclaw.framework.api.ThreadEvent>> catchup) {
        historyReplayer = replay; historyCatchup = catchup;
    }
    public void bindThreadJournal(com.fasterxml.jackson.databind.ObjectMapper json,
            com.javaclaw.framework.spi.ThreadJournal journal, com.javaclaw.framework.spi.ThreadStore history) {
        historyRecovery = new MemoryHistoryRecovery(history, () -> historyReplayer, () -> historyCatchup);
        bindThreadJournal(json, journal);
    }

    public void bindThreadJournal(com.fasterxml.jackson.databind.ObjectMapper json,
                                  com.javaclaw.framework.spi.ThreadJournal journal) {
        graphJournal = new MemoryGraphJournal(json, journal);
        graphs.forEach((scope, view) -> graphJournal.bind(scope, view.store));
    }

    public void beginForkReplay(MemoryGraphScope scope) {
        (graphOwner == null ? this : graphOwner).replayScopes.add(scope);
        MemoryService target = inScope(scope);
        target.replayingFork = true;
        target.store.observeMutations(null);
    }

    public void restoreForkSnapshot(MemoryGraphScope scope, com.javaclaw.memory.graph.MemoryGraphSnapshot snapshot) {
        MemoryService target = inScope(scope);
        if (!target.replayingFork) throw new IllegalStateException("只能在分支回放时恢复图谱快照");
        target.store.restoreSnapshot(snapshot);
    }

    public void endForkReplay(MemoryGraphScope scope) {
        MemoryService target = inScope(scope);
        target.store.root().historyRecovered = true;
        target.store.persistProjectionState();
        (graphOwner == null ? this : graphOwner).replayScopes.remove(scope);
        target.replayingFork = false;
        if (graphJournal != null) graphJournal.bind(scope, target.store);
        target.resumedPending.set(false);
        inScope(scope);
    }

    /** Immutable scoped view; never changes a shared current-session field. */
    public MemoryService inScope(MemoryGraphScope scope) {
        java.util.Objects.requireNonNull(scope, "scope");
        MemoryService owner = graphOwner == null ? this : graphOwner;
        if (owner.graphRoot == null) throw new IllegalStateException("记忆服务尚未配置分图根目录");
        if (!scope.workspaceId().equals(owner.graphScope.workspaceId())
                || !scope.userId().equals(owner.graphScope.userId())) {
            throw new IllegalArgumentException("不能访问其他工作区或用户的记忆图谱");
        }
        if (scope.kind() == MemoryGraphScope.Kind.LEGACY && !"local-user".equals(scope.userId()))
            throw new IllegalArgumentException("历史待归属记忆仅属于桌面本地用户");
        if (!owner.catalogAccepting) throw new IllegalStateException("记忆服务正在关闭");
        if (scope.equals(owner.graphScope)) return owner;
        Path path = scope.directory(owner.graphRoot);
        if (scope.kind() == MemoryGraphScope.Kind.THREAD && owner.historyRecovery != null) owner.historyRecovery.validate(scope);
        if (scope.kind() == MemoryGraphScope.Kind.THREAD && MemoryStoreRegistry.isDeleted(path)) {
            throw new IllegalStateException("会话记忆已删除: " + scope.threadId());
        }
        MemoryService selected = owner.graphs.computeIfAbsent(scope, key -> {
            MemoryService child = new MemoryService(owner.modelTasks, owner.gate, owner.tasks, owner.settings);
            child.graphRoot = owner.graphRoot;
            child.graphScope = key;
            child.graphOwner = owner;
            child.replayingFork = owner.replayScopes.contains(key);
            try {
                child.open(path);
                if (owner.graphJournal != null && !child.replayingFork) owner.graphJournal.bind(key, child.store);
            } catch (RuntimeException | Error failure) { child.close(); throw failure; }
            if (key.kind() == MemoryGraphScope.Kind.THREAD) {
                child.habitReviewer = new HabitReviewer(owner.modelTasks, owner.store, owner.gate,
                        owner.settings, () -> owner.habitEvidence(key.userId()),
                        action -> MemoryStoreRegistry.withLiveGraph(path, action), owner::hasLiveEvidence);
            }
            return child;
        });
        if (owner.replayScopes.contains(scope)) selected.replayingFork = true;
        if (!selected.replayingFork && scope.kind() == MemoryGraphScope.Kind.THREAD && owner.historyRecovery != null) owner.historyRecovery.recover(scope, selected);
        if (!selected.replayingFork && scope.kind() == MemoryGraphScope.Kind.THREAD && selected.resumedPending.compareAndSet(false, true)) {
            for (Episode pending : selected.episodes()) {
                if (!pending.distilled && pending.ownerRunId != null) {
                    selected.rememberEpisode(new RunId(pending.ownerRunId), pending, true);
                }
            }
        }
        return selected;
    }

    public List<MemoryGraphScope> scopes() {
        MemoryService owner = graphOwner == null ? this : graphOwner;
        if (owner.graphScope == null) return List.of();
        java.util.LinkedHashSet<MemoryGraphScope> result = new java.util.LinkedHashSet<>();
        result.add(owner.graphScope);
        Path threads = owner.graphScope.directory(owner.graphRoot).getParent().resolve("threads");
        if (java.nio.file.Files.isDirectory(threads)) {
            try (var paths = java.nio.file.Files.list(threads)) {
                paths.filter(java.nio.file.Files::isDirectory).sorted().forEach(path -> {
                    if (MemoryStoreRegistry.isDeleted(path)) return;
                    try {
                        result.add(new MemoryGraphScope(owner.graphScope.workspaceId(),
                                owner.graphScope.userId(), MemoryGraphScope.decode(path.getFileName().toString()),
                                MemoryGraphScope.Kind.THREAD));
                    } catch (IllegalArgumentException ignored) { /* unrelated directory */ }
                });
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
        if ("local-user".equals(owner.graphScope.userId()) && java.nio.file.Files.exists(owner.graphRoot.resolve("channel_0"))) {
            result.add(new MemoryGraphScope(owner.graphScope.workspaceId(), owner.graphScope.userId(),
                    "", MemoryGraphScope.Kind.LEGACY));
        }
        return List.copyOf(result);
    }

    private List<Episode> habitEvidence(String userId) {
        java.util.LinkedHashMap<String, Episode> evidence = new java.util.LinkedHashMap<>();
        for (MemoryGraphScope scope : scopes()) {
            if (scope.kind() != MemoryGraphScope.Kind.THREAD || !scope.userId().equals(userId)) continue;
            try {
                for (Episode episode : inScope(scope).episodes()) {
                    if (episode.habitEvidence) evidence.putIfAbsent(episode.evidenceKey(), episode);
                }
            } catch (IllegalStateException ignored) { /* deleted during review */ }
        }
        return evidence.values().stream().sorted(java.util.Comparator.comparingLong(e -> e.timestamp)).toList();
    }

    private boolean hasLiveEvidence(Episode episode) {
        var scope = new MemoryGraphScope(graphScope.workspaceId(), graphScope.userId(),
                episode.sessionId, MemoryGraphScope.Kind.THREAD);
        MemoryService source = graphs.get(scope);
        return !MemoryStoreRegistry.isDeleted(scope.directory(graphRoot)) && source != null
                && source.store != null && source.store.findTurn(episode.turnId) != null;
    }

    public String recall(MemoryGraphScope scope, String query, int topK) {
        if (scope.kind() == MemoryGraphScope.Kind.LEGACY) return "";
        MemoryService selected = inScope(scope);
        if (scope.kind() == MemoryGraphScope.Kind.WORKSPACE_HABITS) return selected.recall(query);
        return Recaller.recallGraphs(List.of(selected.store, inScope(scope.habits()).store),
                gate, settings, query, topK);
    }

    public CorrectionTurnContext prepareCorrectionTurn(MemoryGraphScope scope,
                                                       String input, String previousReply) {
        var correction = com.javaclaw.memory.correction.CorrectionDetector.detect(input);
        if (correction.isPresent() && correction.get().scope()
                == com.javaclaw.memory.model.CorrectionRecord.Scope.USER) {
            return inScope(scope.habits()).prepareCorrectionTurn(input, previousReply);
        }
        return inScope(scope).prepareCorrectionTurn(input, previousReply);
    }

    /** Explicit, first-person preferences need no model inference or embedding to be durable. */


    public void rememberExplicitPreference(MemoryGraphScope scope, String turnId, String input) {
        MemoryPreferenceWriter.remember(this, scope, scope.directory(graphRoot), turnId, input);
    }

    public List<com.javaclaw.memory.model.CorrectionRecord> corrections(MemoryGraphScope scope) {
        java.util.ArrayList<com.javaclaw.memory.model.CorrectionRecord> records =
                new java.util.ArrayList<>(inScope(scope).corrections());
        if (scope.kind() == MemoryGraphScope.Kind.THREAD) records.addAll(inScope(scope.habits()).corrections());
        return List.copyOf(records);
    }

    public void rememberTurn(MemoryGraphScope scope, RunId runId, String turnId,
                             long eventSequence, String input, String reply, String trace,
                             boolean reviewHabits) {
        rememberTurn(scope, runId, turnId, eventSequence, input, reply, trace, reviewHabits,
                scope.threadId(), turnId);
    }

    public void rememberTurn(MemoryGraphScope scope, RunId runId, String turnId,
                             long eventSequence, String input, String reply, String trace,
                             boolean reviewHabits, String originThreadId, String originTurnId) {
        rememberTerminal(scope, runId, turnId, eventSequence, input, reply, trace, reviewHabits,
                originThreadId, originTurnId, "completed");
    }

    public void rememberTerminal(MemoryGraphScope scope, RunId runId, String turnId, long sequence,
            String input, String reply, String trace, boolean reviewHabits,
            String originThreadId, String originTurnId, String status) {
        MemoryTurnWriter.remember(this, scope, runId, turnId, sequence, input, reply, trace,
                reviewHabits, originThreadId, originTurnId, status);
    }

    /** Idempotent lifecycle projection. The tombstone is permanent even after files are removed. */
    public void deleteThread(MemoryGraphScope scope) {
        if (scope.kind() != MemoryGraphScope.Kind.THREAD) throw new IllegalArgumentException("仅可删除会话图谱");
        MemoryService owner = graphOwner == null ? this : graphOwner;
        owner.validateOwner(scope);
        Path path = scope.directory(owner.graphRoot);
        MemoryStoreRegistry.delete(path);
        MemoryService child = owner.graphs.remove(scope);
        if (child != null) child.close();
        LegacyMemoryMigration.hideThread(owner, scope);
        // Keep promoted habits, but do not retain deleted conversation text as evidence.
        for (var fact : owner.facts()) {
            if (fact.evidenceKeys != null && fact.evidenceKeys.stream()
                    .anyMatch(key -> key.startsWith(scope.threadId() + ":"))) {
                if (fact.pending) owner.store.updatePendingFact(fact, f -> f.evidenceDeleted = true, "thread.delete");
                else owner.store.updateFact(fact, f -> f.evidenceDeleted = true, "thread.delete");
            }
        }
    }

    private void validateOwner(MemoryGraphScope scope) {
        if (graphScope == null || !graphScope.workspaceId().equals(scope.workspaceId())
                || !graphScope.userId().equals(scope.userId())) {
            throw new IllegalArgumentException("记忆图谱所有者不匹配");
        }
    }

    /** Rebuild a fork from immutable source episodes, never copy a later mutable fact state. */
    public void forkThread(MemoryGraphScope source, MemoryGraphScope target, long cutoffEventSequence) {
        validateOwner(source);
        validateOwner(target);
        MemoryForkSnapshots.copy(this, graphRoot, source, target, cutoffEventSequence);
    }

    /** 切工作区：关闭旧库、打开新库。 */
    public synchronized void reload(Path memoryDir) {
        close();
        open(memoryDir);
    }

    @Override
    public synchronized void close() {
        if (graphOwner == this) {
            catalogAccepting = false;
            graphs.values().forEach(MemoryService::close);
            graphs.clear();
        }
        MemoryTaskTracker closingWork = backgroundWork;
        closingWork.stopAccepting();

        boolean drained = closingWork.awaitDrained(
                BACKGROUND_DRAIN_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        if (!drained) {
            log.info("记忆后台任务未在宽限期内结束，正在请求取消");
            closingWork.cancelAll();
            drained = closingWork.awaitDrained(
                    BACKGROUND_CANCEL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        }

        MemoryStoreRegistry.Lease closingStoreLease = storeLease;
        storeLease = null;
        store = null;
        recaller = null;
        distiller = null;
        habitReviewer = null;
        correctionEngine = null;
        // 已关闭的一代任务可能仍在响应底层网络取消；新工作区使用独立追踪器，互不串扰。
        backgroundWork = new MemoryTaskTracker();

        if (closingStoreLease == null) {
            return;
        }
        if (drained) {
            closingStoreLease.close();
            return;
        }

        // 极端情况下底层模型调用不响应中断，旧任务继续持有这一代存储租约；最后一个
        // 托管任务退出时在原虚拟线程释放租约，无需额外创建清理线程。
        log.warn("记忆后台任务取消后仍未结束，存储租约将在任务退出后延迟释放");
        closingWork.whenDrained(() -> {
            try {
                closingStoreLease.close();
            } catch (RuntimeException e) {
                log.warn("延迟释放记忆存储失败: {}", e.getMessage());
            }
        });
    }

    private void seedDefaultPersona() {
        if (graphScope != null && graphScope.kind() != MemoryGraphScope.Kind.WORKSPACE_HABITS) return;
        if (store.getPersona() == null) {
            store.setPersona(MemoryPrompts.DEFAULT_AGENTS_SKELETON, "system");
            log.info("已写入默认人格骨架");
        }
    }


    /** 构建本轮注入上下文；服务未就绪时返回空串。 */
    public String recall(String query) {
        if (graphScope != null && graphScope.kind() == MemoryGraphScope.Kind.LEGACY) return "";
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

    public void checkpoint(String key, String messagesJson) {
        MemoryStore current = store;
        if (current != null) current.checkpoint(key, messagesJson);
    }

    public AgentCheckpoint loadCheckpoint(String key) {
        MemoryStore current = store;
        return current == null ? null : current.loadCheckpoint(key);
    }

    public void deleteCheckpoint(String key) {
        MemoryStore current = store;
        if (current != null) current.removeCheckpoint(key);
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
        if (current == null) return List.of();
        return current.allCorrections().stream().filter(c -> graphScope == null
                || graphScope.kind() != MemoryGraphScope.Kind.LEGACY
                || !current.root().migratedIds.contains("correctionrecord:" + c.id)).toList();
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


    /**
     * 轮后记忆：先把情景快速落入 pending 暂存区，再异步嵌入、迁入索引并蒸馏事实。
     * 这样关闭时可以安全取消耗时模型调用，而不会丢掉已经完成回复的一轮对话。
     */
    public void rememberTurn(String sessionId, String userInput, String reply, String toolTraceJson) {
        rememberTurn(null, sessionId, userInput, reply, toolTraceJson);
    }

    public void rememberTurn(RunId ownerRunId, String sessionId, String userInput,
                             String reply, String toolTraceJson) {
        rememberTurn(ownerRunId, sessionId, userInput, reply, toolTraceJson, true);
    }

    public void rememberTurn(RunId ownerRunId, String sessionId, String userInput,
                             String reply, String toolTraceJson, boolean reviewHabits) {
        Episode episode = new Episode(sessionId, userInput, reply);
        episode.turnId = ownerRunId == null ? null : ownerRunId.value();
        episode.originThreadId = sessionId;
        episode.originTurnId = episode.turnId;
        episode.ownerRunId = ownerRunId == null ? null : ownerRunId.value();
        episode.toolTraceJson = toolTraceJson;
        rememberEpisode(ownerRunId, episode, reviewHabits);
    }

    void rememberEpisode(RunId ownerRunId, Episode ep, boolean reviewHabits) {
        String userInput = ep.userInput;
        String reply = ep.assistantReply;
        String toolTraceJson = ep.toolTraceJson;
        String sessionId = ep.sessionId;
        if (SensitiveDataRedactor.containsLikelyCredential(userInput)
                || SensitiveDataRedactor.containsLikelyCredential(reply)
                || SensitiveDataRedactor.containsLikelyCredential(toolTraceJson)) {
            log.warn("本轮包含疑似凭据，已跳过长期记忆与情景索引写入");
            return;
        }
        if (replayingFork || ep.distilled) { store.addTurnOnce(ep, "thread.terminal"); return; }
        MemoryStore turnStore;
        Distiller turnDistiller;
        HabitReviewer reviewer;
        MemoryTaskTracker.WorkLease lease;
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
            synchronized (activeTurns) {
                if (!turnStore.addTurnOnce(ep, "system")) {
                    Episode persisted = turnStore.findTurn(ep.turnId);
                    if (persisted == null || persisted.distilled || activeTurns.contains(ep.turnId)) {
                        lease.close();
                        return;
                    }
                    ep = persisted;
                }
                if (ep.turnId != null) activeTurns.add(ep.turnId);
            }
            Episode source = ep;
            TaskHandle<Void> handle = tasks.submit(
                    TaskSpec.io("memory-turn-" + taskName(sessionId)), context -> {
                try {
                    rememberTurn(context, lease, turnStore, turnDistiller, reviewer,
                            ownerRunId, source, userInput, reply, reviewHabits);
                } catch (RuntimeException e) {
                    log.warn("rememberTurn 失败（静默）: {}", e.getMessage());
                } finally {
                    if (source.turnId != null) activeTurns.remove(source.turnId);
                    lease.close();
                }
                return null;
            });
            lease.onCancel(handle::cancel);
        } catch (RuntimeException e) {
            if (ep.turnId != null) activeTurns.remove(ep.turnId);
            lease.close();
            log.warn("rememberTurn 调度失败（静默）: {}", e.getMessage());
        } catch (Error e) {
            lease.close();
            throw e;
        }
    }

    private void rememberTurn(
            TaskContext context,
            MemoryTaskTracker.WorkLease lease,
            MemoryStore turnStore,
            Distiller turnDistiller,
            HabitReviewer reviewer,
            RunId ownerRunId,
            Episode episode,
            String userInput,
            String reply,
            boolean reviewHabits) {
        if (cancelled(context, lease)) return;
        float[] embedding = gate.embed(
                cap(userInput) + " " + cap(reply), EmbeddingPurpose.BACKGROUND_INDEX);
        if (cancelled(context, lease)) return;

        if (embedding != null) {
            turnStore.promotePendingEpisode(episode, embedding, "system");
            if (!cancelled(context, lease) && pendingCount(turnStore) > 0) {
                int moved = promotePending(turnStore, 25, () -> cancelled(context, lease));
                if (moved > 0) log.info("嵌入恢复，已迁回 {} 条暂存记忆", moved);
            }
        } else {
            log.debug("情景嵌入不可用，情景保留在 pending 暂存区");
        }

        if (cancelled(context, lease)) return;
        if (ownerRunId != null) {
            lastOwnerRun.set(ownerRunId);
            if (turnDistiller.distillWithStatus(ownerRunId, episode) && !cancelled(context, lease)) {
                turnStore.markDistilled(episode);
            }
            if (reviewHabits && !cancelled(context, lease)) reviewer.maybeReviewNow(ownerRunId);
        }
    }

    private static boolean cancelled(
            TaskContext context, MemoryTaskTracker.WorkLease lease) {
        return lease.isCancellationRequested()
                || context.cancellation().isCancellationRequested()
                || Thread.currentThread().isInterrupted();
    }

    private static String taskName(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "unknown";
        String normalized = sessionId.replaceAll("[^a-zA-Z0-9._-]", "-");
        return normalized.length() > 48 ? normalized.substring(0, 48) : normalized;
    }


    public Persona getPersona() {
        return store == null || (graphScope != null && graphScope.kind() == MemoryGraphScope.Kind.LEGACY
                && store.root().migratedIds.contains("persona")) ? null : store.getPersona();
    }

    public void setPersona(String content, String actor) {
        if (store != null) store.setPersona(content, actor);
    }

    public List<ChangeLogEntry> recentChangeLog(int limit) {
        if (store == null) return List.of();
        if (graphScope == null || graphScope.kind() != MemoryGraphScope.Kind.LEGACY) return store.recentChangeLog(limit);
        return store.recentChangeLog(Integer.MAX_VALUE).stream().filter(change ->
                !store.root().migratedIds.contains(change.type.toLowerCase(java.util.Locale.ROOT) + ":" + change.targetId)
                        && !("Persona".equals(change.type) && store.root().migratedIds.contains("persona")))
                .limit(limit).toList();
    }


    /** 全部事实：正式（已索引）+ pending（降级暂存）合并，供 UI 展示。 */
    public List<com.javaclaw.memory.model.Fact> facts() {
        if (store == null) return List.of();
        List<com.javaclaw.memory.model.Fact> out = new java.util.ArrayList<>(store.allFacts());
        out.addAll(store.allPendingFacts());
        if (graphScope != null && graphScope.kind() == MemoryGraphScope.Kind.LEGACY)
            out.removeIf(f -> store.root().migratedIds.contains("fact:" + f.id));
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

    /** 全部情景：正式 + pending 合并，供 UI 展示。 */
    public List<com.javaclaw.memory.model.Episode> episodes() {
        if (store == null) return List.of();
        List<com.javaclaw.memory.model.Episode> out = new java.util.ArrayList<>(store.allEpisodes());
        out.addAll(store.allPendingEpisodes());
        if (graphScope != null && graphScope.kind() == MemoryGraphScope.Kind.LEGACY)
            out.removeIf(e -> store.root().migratedIds.contains("episode:" + e.id));
        return out;
    }


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
        if (store == null) return List.of();
        if (graphScope != null && graphScope.kind() == MemoryGraphScope.Kind.LEGACY) {
            var referenced = facts().stream().filter(f -> f.about != null).flatMap(f -> f.about.stream())
                    .filter(java.util.Objects::nonNull).map(e -> e.id).collect(java.util.stream.Collectors.toSet());
            return store.allEntities().stream().filter(e -> referenced.contains(e.id)).toList();
        }
        return store.allEntities();
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

    /** Read-only graph visualization for this explicitly selected scope. */
    public com.javaclaw.memory.graph.MemoryGraph graph() {
        if (store == null) {
            return com.javaclaw.memory.graph.MemoryGraph.empty();
        }
        double semThreshold = settings.getMemoryGraphSemanticThreshold();
        int maxNodes = settings.getMemoryGraphMaxNodes();
        var opt = new com.javaclaw.memory.graph.MemoryGraphBuilder.Options(
                maxNodes, semThreshold, 3, true, 36);
        return com.javaclaw.memory.graph.MemoryGraphBuilder.build(store, opt,
                graphScope != null && graphScope.kind() == MemoryGraphScope.Kind.LEGACY
                        ? store.root().migratedIds : java.util.Set.of());
    }

    public MemoryStore store() {
        return store;
    }

    /** 手动执行一次习惯回顾；由工作区内置任务接线器调用。 */
    public String reviewHabitsNow() {
        HabitReviewer reviewer;
        MemoryTaskTracker.WorkLease lease;
        synchronized (this) {
            reviewer = this.habitReviewer;
            if (reviewer == null) return "记忆服务未就绪，习惯回顾不可用";
            lease = backgroundWork.tryAcquire();
            if (lease == null) return "记忆服务正在关闭，习惯回顾不可用";
        }
        lease.onCancel(Thread.currentThread()::interrupt);
        try (lease) {
            return reviewer.reviewNow(lastOwnerRun.get());
        }
    }

    public void editFact(com.javaclaw.memory.model.Fact fact, String text) {
        MemoryFactEditor.editFact(store, gate, fact, text);
    }
    public void addFact(String section, String text) {
        MemoryFactEditor.addFact(store, gate, section, text);
    }
    public void setPersonaStructured(String identity, String tone, List<String> preferences, List<String> taboos) {
        MemoryFactEditor.setPersonaStructured(store, identity, tone, preferences, taboos);
    }
    public static String assemblePersona(String identity, String tone, List<String> preferences, List<String> taboos) {
        return MemoryFactEditor.assemblePersona(identity, tone, preferences, taboos);
    }

    private static String cap(String s) {
        if (s == null) return "";
        s = s.strip();
        return s.length() > EMBED_TEXT_CAP ? s.substring(0, EMBED_TEXT_CAP) : s;
    }

}
