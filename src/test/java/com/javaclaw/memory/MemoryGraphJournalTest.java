package com.javaclaw.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.framework.spi.ThreadJournal;
import com.javaclaw.infrastructure.memory.ThreadMemoryProjectionAdapter;
import com.javaclaw.memory.embed.TestEmbeddingGatewayFactory;
import com.javaclaw.memory.model.*;
import com.javaclaw.memory.retrieval.Recaller;
import com.javaclaw.memory.store.MemoryStore;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryGraphJournalTest {
    private Path temporary;
    private AnnotationConfigApplicationContext context;
    private AgentConfig settings;
    private ObjectMapper json;

    @BeforeAll void setup(@TempDir Path temporary) {
        this.temporary = temporary;
        context = ApplicationContexts.createRoot(new DataRoot(temporary.resolve("config")));
        settings = context.getBean(AgentConfig.class);
        json = context.getBean(ObjectMapper.class);
    }
    @AfterAll void cleanup() { context.close(); }

    @Test void forkReplaysManualEditsAndCorrectionsAtSnapshotCutoff() {
        List<ThreadEvent> journal = new ArrayList<>();
        try (var fixture = fixture(temporary.resolve("fork"), true)) {
            fixture.memory.bindThreadJournal(json, (scope, id, type, payload) -> {
                long sequence = journal.size() + 1;
                journal.add(new ThreadEvent(scope, sequence, Instant.now(), type, null, payload));
                return sequence;
            });
            var source = scope("source");
            var target = scope("target");
            var sourceMemory = fixture.memory.inScope(source);
            sourceMemory.addFact("约定", "发布采用蓝色方案");
            var fact = sourceMemory.facts().getFirst();
            sourceMemory.editFact(fact, "发布采用绿色方案");
            var correction = new CorrectionRecord();
            correction.type = CorrectionRecord.Type.FACT_REPLACEMENT;
            correction.scope = CorrectionRecord.Scope.PROJECT;
            correction.status = CorrectionRecord.Status.ACTIVE;
            correction.wrongClaim = "发布采用蓝色方案";
            correction.correctClaim = "发布采用绿色方案";
            sourceMemory.store().addCorrection(correction, "user");
            List<ThreadEvent> cutoff = List.copyOf(journal);
            sourceMemory.editFact(fact, "后来的紫色方案");
            var now = Instant.now();
            ThreadSnapshot snapshot = new ThreadSnapshot(new ThreadId("target"), runScope("target"),
                    "fork", ThreadStatus.FORKING, ThreadConfiguration.DEFAULT, null, null,
                    new ThreadId("source"), cutoff.size(), 1, cutoff.size(), now, now);
            new ThreadMemoryProjectionAdapter(fixture.memory, json).forked(snapshot, cutoff);
            assertEquals("发布采用绿色方案", fixture.memory.inScope(target).facts().getFirst().text);
            assertEquals("发布采用绿色方案", fixture.memory.inScope(target).corrections().getFirst().correctClaim);
            fixture.memory.deleteThread(source);
            assertEquals("发布采用绿色方案", fixture.memory.inScope(target).facts().getFirst().text);
        }
        try (var fixture = fixture(temporary.resolve("fork"), true)) {
            assertEquals("发布采用绿色方案", fixture.memory.inScope(scope("target")).facts().getFirst().text);
            assertEquals(1, fixture.memory.inScope(scope("target")).corrections().size());
        }
    }

    @Test void failedJournalCommitRemainsDurableAndReplaysWithSameMutationIdentity() {
        Path path = temporary.resolve("retry");
        List<String> attempts = new ArrayList<>();
        AtomicBoolean failing = new AtomicBoolean(true);
        ThreadJournal journal = (scope, id, type, payload) -> {
            attempts.add(id);
            if (failing.get()) throw new IllegalStateException("database temporarily unavailable");
            return 1;
        };
        try (var fixture = fixture(path, true)) {
            fixture.memory.bindThreadJournal(json, journal);
            var scoped = fixture.memory.inScope(scope("retry"));
            assertThrows(RuntimeException.class, () -> scoped.addFact("约定", "需要可靠保存的编辑"));
            assertEquals(1, scoped.store().root().pendingGraphSnapshots.size());
        }
        failing.set(false);
        try (var fixture = fixture(path, true)) {
            fixture.memory.bindThreadJournal(json, journal);
            var scoped = fixture.memory.inScope(scope("retry"));
            assertEquals(1, scoped.facts().size());
            assertTrue(scoped.store().root().pendingGraphSnapshots.isEmpty());
            assertEquals(attempts.getFirst(), attempts.getLast());
        }
    }

    @Test void migrationHidesAssignedOriginalsPermanentlyAfterThreadDeletion() {
        Path path = temporary.resolve("legacy");
        try (var fixture = fixture(path, false)) {
            MemoryService old = fixture.memory;
            old.setPersona("个人旧人格", "user");
            var episode = new Episode("known", "迁移会话的原始问题", "迁移会话的原始回复");
            old.store().addPendingEpisode(episode, "test");
            Fact assigned = new Fact("项目", "明确归属的历史事实", null);
            assigned.source = episode;
            old.store().addPendingFact(assigned, "test");
            var correction = new CorrectionRecord();
            correction.type = CorrectionRecord.Type.FACT_REPLACEMENT;
            correction.scope = CorrectionRecord.Scope.PROJECT;
            correction.status = CorrectionRecord.Status.ACTIVE;
            correction.targetFactId = assigned.id;
            correction.sourceInput = "迁移会话的私密纠错原文";
            old.store().addCorrection(correction, "test");
            old.store().addPendingFact(new Fact("其它", "没有来源的待归属事实", null), "test");
            Fact habit = new Fact("习惯", "旧库归纳的稳定习惯", null);
            habit.sourceKind = "HABIT_REVIEW";
            old.store().addPendingFact(habit, "test");
        }
        try (var fixture = fixture(path, true)) {
            assertEquals(3, fixture.memory.migrateLegacy(json, "known"::equals));
            assertEquals(0, fixture.memory.migrateLegacy(json, "known"::equals));
            assertEquals("个人旧人格", fixture.memory.getPersona().content);
            assertEquals(1, fixture.memory.facts().size());
            MemoryGraphScope legacy = new MemoryGraphScope("workspace", "local-user", "", MemoryGraphScope.Kind.LEGACY);
            MemoryService quarantined = fixture.memory.inScope(legacy);
            assertEquals(List.of("没有来源的待归属事实"), quarantined.facts().stream().map(f -> f.text).toList());
            assertTrue(quarantined.episodes().isEmpty());
            assertTrue(quarantined.corrections().isEmpty());
            assertFalse(quarantined.graph().nodes().stream().anyMatch(n -> n.detail().contains("迁移会话")));
            fixture.memory.deleteThread(scope("known"));
            assertTrue(quarantined.episodes().isEmpty());
            assertFalse(quarantined.recentChangeLog(500).stream().anyMatch(log -> log.detail.contains("迁移会话")));
        }
        try (var fixture = fixture(path, true)) {
            MemoryGraphScope legacy = new MemoryGraphScope("workspace", "local-user", "", MemoryGraphScope.Kind.LEGACY);
            assertTrue(fixture.memory.inScope(legacy).episodes().isEmpty());
            assertEquals(1, fixture.memory.inScope(legacy).facts().size());
            assertTrue(fixture.memory.inScope(legacy).corrections().isEmpty());
            assertFalse(fixture.memory.inScope(legacy).recentChangeLog(500).stream()
                    .anyMatch(log -> log.detail.contains("私密纠错原文")));
        }
    }

    @Test void missingGraphRestoresFromJournalAndDeletedThreadNeverRecreatesFiles() throws Exception {
        Path path = temporary.resolve("lost-graph");
        List<ThreadEvent> history = new ArrayList<>();
        ThreadSnapshot active = thread("recover", ThreadStatus.ACTIVE);
        var historyStore = historyStore(active, history);
        ThreadJournal journal = (scope, id, type, payload) -> {
            long sequence = history.size() + 1;
            history.add(new ThreadEvent(scope, sequence, Instant.now(), type, null, payload));
            return sequence;
        };
        try (var fixture = fixture(path, true)) {
            fixture.memory.bindThreadJournal(json, journal, historyStore);
            new ThreadMemoryProjectionAdapter(fixture.memory, json);
            fixture.memory.inScope(scope("recover")).addFact("人工编辑", "从H2日志恢复的确认结论");
        }
        try (var files = java.nio.file.Files.walk(scope("recover").directory(path))) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList())
                java.nio.file.Files.delete(file);
        }
        try (var fixture = fixture(path, true)) {
            fixture.memory.bindThreadJournal(json, journal, historyStore);
            new ThreadMemoryProjectionAdapter(fixture.memory, json);
            assertEquals("从H2日志恢复的确认结论", fixture.memory.inScope(scope("recover")).facts().getFirst().text);
            assertTrue(fixture.memory.inScope(scope("recover")).store().root().historyRecovered);
        }
        try (var fixture = fixture(path, true)) {
            fixture.memory.bindThreadJournal(json, journal, historyStore(thread("deleted", ThreadStatus.DELETED), List.of()));
            new ThreadMemoryProjectionAdapter(fixture.memory, json);
            assertThrows(IllegalStateException.class, () -> fixture.memory.inScope(scope("deleted")));
            assertFalse(java.nio.file.Files.exists(scope("deleted").directory(path)));
        }
    }

    @Test void copiedSnapshotBindsMaintenanceToCopiedTurnAndPreservesOriginalEvidence() throws Exception {
        try (var fixture = fixture(temporary.resolve("copied-identities"), true)) {
            var source = fixture.memory.inScope(scope("original"));
            Episode episode = new Episode("original", "原始用户输入", "已确认的输出");
            episode.turnId = "old-run";
            episode.ownerRunId = "old-run";
            episode.originThreadId = "original";
            episode.originTurnId = "old-run";
            episode.sourceEventSequence = 2;
            episode.distilled = true;
            source.store().addPendingEpisode(episode, "test");
            var serialized = json.valueToTree(com.javaclaw.memory.graph.MemoryGraphSnapshot.capture(source.store(), 1));
            RunRequest request = RunRequest.builder().agent(AgentDefinitionRef.latest("test"))
                    .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat())
                    .scope(runScope("copied")).input(InputBlock.text("原始用户输入")).build()
                    .withAttribute("memory.originThreadId", json.getNodeFactory().textNode("original"))
                    .withAttribute("memory.originTurnId", json.getNodeFactory().textNode("old-run"));
            var created = json.createObjectNode(); created.set("request", json.valueToTree(request));
            var completed = json.createObjectNode();
            completed.putObject("event").putObject("payload").putObject("output").put("text", "已确认的输出");
            var graph = json.createObjectNode(); graph.set("snapshot", serialized);
            List<ThreadEvent> history = List.of(
                    new ThreadEvent(runScope("copied"), 1, Instant.now(), "turn/created", new TurnId("new-run"), created),
                    new ThreadEvent(runScope("copied"), 2, Instant.now(), "turn/completed", new TurnId("new-run"), completed),
                    new ThreadEvent(runScope("copied"), 3, Instant.now(), "memory/graph-snapshot", null, graph));
            new ThreadMemoryProjectionAdapter(fixture.memory, json).forked(thread("copied", ThreadStatus.FORKING), history);
            fixture.memory.deleteThread(scope("original"));
            Episode copied = fixture.memory.inScope(scope("copied")).episodes().getFirst();
            assertEquals("new-run", copied.turnId);
            assertEquals("new-run", copied.ownerRunId);
            assertEquals("original:old-run", copied.evidenceKey());
            assertEquals("copied", copied.sessionId);
        }
    }

    @Test void immediateNextRecallCatchesCommittedTerminalBeforeOutboxDelivery() {
        List<ThreadEvent> history = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (var fixture = fixture(temporary.resolve("immediate-recall"), true)) {
            fixture.memory.bindThreadJournal(json, (scope, id, type, payload) -> 100,
                    historyStore(thread("immediate", ThreadStatus.ACTIVE), history));
            new ThreadMemoryProjectionAdapter(fixture.memory, json);
            fixture.memory.inScope(scope("immediate")); // Existing graph is already marked recovered.
            history.addAll(terminalHistory("immediate", "first-turn", "我偏好简短句子", "批准银杏计划", "completed"));
            String recalled = fixture.memory.recall(scope("immediate"), "银杏计划", 8);
            assertTrue(recalled.contains("批准银杏计划"), recalled);
            assertEquals(1, fixture.memory.inScope(scope("immediate")).episodes().size());
            assertTrue(fixture.memory.facts().stream().anyMatch(f -> f.text.contains("我偏好简短句子")));
            assertEquals(2, fixture.memory.inScope(scope("immediate")).store().root().observedThreadSequence);
        }
    }

    @Test void unsuccessfulTerminalsAreRecallableHistoryWithoutHabitEvidenceOrDistillation() {
        List<ThreadEvent> history = terminalHistory("failed", "failed-turn", "我偏好尚未确认的风格", "", "failed");
        try (var fixture = fixture(temporary.resolve("failed-terminal"), true)) {
            fixture.memory.bindThreadJournal(json, (scope, id, type, payload) -> 100,
                    historyStore(thread("failed", ThreadStatus.ACTIVE), history));
            new ThreadMemoryProjectionAdapter(fixture.memory, json);
            Episode episode = fixture.memory.inScope(scope("failed")).episodes().getFirst();
            assertEquals("failed", episode.terminalStatus);
            assertTrue(episode.distilled);
            assertFalse(episode.habitEvidence);
            assertNull(episode.ownerRunId);
            assertTrue(fixture.memory.facts().isEmpty());
            String recalled = fixture.memory.recall(scope("failed"), "尚未确认的风格", 8);
            assertTrue(recalled.contains("failed"), recalled);
        }
    }

    @Test void recallWaitsForAtomicPromotionAndKeepsDistilledOriginalEvidence() throws Exception {
        var promotionGap = new CountDownLatch(1);
        var finishPromotion = new CountDownLatch(1);
        var queryStarted = new CountDownLatch(1);
        try (var embedding = TestEmbeddingGatewayFactory.create(4, (text, timeout) -> {
            queryStarted.countDown();
            return null; // 原始证据召回不依赖向量服务可用。
        });
             var store = new MemoryStore(temporary.resolve("promotion-recall"), 4, "promotion-recall");
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            store.open();
            Episode episode = new Episode("promotion", "确认银杏计划", "批准银杏计划");
            episode.turnId = "committed-turn";
            episode.distilled = true;
            store.addPendingEpisode(episode, "test");
            var promotion = workers.submit(() -> store.withProjectionLock(() -> {
                // 可控地停在真实迁移的 remove/add 间隙，读取必须等待同一原子变更结束。
                store.removePendingEpisode(episode, "test");
                promotionGap.countDown();
                try {
                    assertTrue(finishPromotion.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                episode.pending = false;
                episode.embedding = new float[]{1, 0, 0, 0};
                store.addEpisode(episode, "test");
            }));
            try {
                assertTrue(promotionGap.await(5, TimeUnit.SECONDS));
                var recall = workers.submit(() -> Recaller.recallGraphs(
                        List.of(store), embedding.gateway(), settings, "银杏计划", 8));
                assertTrue(queryStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> recall.get(200, TimeUnit.MILLISECONDS));
                finishPromotion.countDown();
                promotion.get(5, TimeUnit.SECONDS);
                String recalled = recall.get(5, TimeUnit.SECONDS);
                assertTrue(recalled.contains("批准银杏计划"), recalled);
                assertTrue(recalled.contains("promotion:committed-turn"), recalled);
            } finally {
                finishPromotion.countDown();
            }
        }
    }

    private List<ThreadEvent> terminalHistory(String threadId, String turnId, String input, String output, String status) {
        RunRequest request = RunRequest.builder().agent(AgentDefinitionRef.latest("test"))
                .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat())
                .scope(runScope(threadId)).input(InputBlock.text(input)).build();
        var created = json.createObjectNode(); created.set("request", json.valueToTree(request));
        var terminal = json.createObjectNode();
        terminal.putObject("event").putObject("payload").putObject("output").put("text", output);
        return List.of(new ThreadEvent(runScope(threadId), 1, Instant.now(), "turn/created", new TurnId(turnId), created),
                new ThreadEvent(runScope(threadId), 2, Instant.now(), "turn/" + status, new TurnId(turnId), terminal));
    }

    private static ThreadSnapshot thread(String id, ThreadStatus status) {
        var now = Instant.now();
        return new ThreadSnapshot(new ThreadId(id), runScope(id), id, status,
                ThreadConfiguration.DEFAULT, null, null, null, 0, 1, 0, now, now);
    }
    private static com.javaclaw.framework.spi.ThreadStore historyStore(ThreadSnapshot thread, List<ThreadEvent> history) {
        return (com.javaclaw.framework.spi.ThreadStore) java.lang.reflect.Proxy.newProxyInstance(
                MemoryGraphJournalTest.class.getClassLoader(),
                new Class<?>[]{com.javaclaw.framework.spi.ThreadStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "find" -> java.util.Optional.of(new ThreadSnapshot(thread.id(), thread.scope(), thread.title(),
                            thread.status(), thread.configuration(), thread.parentThreadId(), thread.parentTurnId(),
                            thread.forkSourceThreadId(), thread.forkSequence(), thread.generation(),
                            history.stream().mapToLong(ThreadEvent::sequence).max().orElse(0), thread.createdAt(), thread.updatedAt()));
                    case "events" -> List.copyOf(history);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Fixture fixture(Path path, boolean scoped) {
        var embedding = TestEmbeddingGatewayFactory.create(4, (text, timeout) -> new double[]{1, 0, 0, 0});
        var memory = new MemoryService(request -> {
            var output = JsonNodeFactory.instance.objectNode();
            output.putArray("facts"); output.putArray("entities"); output.putArray("habits");
            return CompletableFuture.completedFuture(new ModelTaskResult(output, "test", 0, 0, false, Map.of()));
        }, embedding.gateway(), embedding.tasks(), settings);
        if (scoped) memory.open(path, "workspace", "local-user"); else memory.open(path);
        return new Fixture(memory, embedding);
    }
    private static MemoryGraphScope scope(String id) {
        return new MemoryGraphScope("workspace", "local-user", id, MemoryGraphScope.Kind.THREAD);
    }
    private static RunScope runScope(String id) { return new RunScope("workspace", "local-user", id); }
    private record Fixture(MemoryService memory, TestEmbeddingGatewayFactory.Fixture embedding) implements AutoCloseable {
        @Override public void close() { memory.close(); embedding.close(); }
    }
}
