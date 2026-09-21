package com.javaclaw.memory;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.memory.embed.TestEmbeddingGatewayFactory;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryGraphIsolationTest {
    private Path temporary;
    private AnnotationConfigApplicationContext context;
    private AgentConfig settings;
    private final AtomicInteger stores = new AtomicInteger();

    @BeforeAll void setup(@TempDir Path temporary) {
        this.temporary = temporary;
        context = ApplicationContexts.createRoot(new DataRoot(temporary.resolve("config")));
        settings = context.getBean(AgentConfig.class);
    }
    @AfterAll void cleanup() { context.close(); }

    @Test void unindexedHistoryAndEntitySourcesStayInsideTheirThread() throws Exception {
        try (var fixture = fixture(ignored -> empty(), false)) {
            var a = thread("a");
            var b = thread("b");
            fixture.memory.rememberTurn(a, null, "a1", 3, "发布项目甲的结论是什么", "项目甲采用蓝色方案", null, false);
            fixture.memory.rememberTurn(b, null, "b1", 4, "发布项目乙的结论是什么", "项目乙采用红色方案", null, false);
            assertTrue(fixture.memory.recall(a, "继续", 8).contains("项目甲采用蓝色方案"));
            assertFalse(fixture.memory.recall(a, "继续", 8).contains("项目乙采用红色方案"));
            assertFalse(fixture.memory.recall(b, "继续", 8).contains("项目甲采用蓝色方案"));
            assertTrue(fixture.memory.inScope(a).graph().nodes().stream().anyMatch(n -> n.type().equals("episode")));
            assertThrows(IllegalArgumentException.class, () -> fixture.memory.inScope(
                    new MemoryGraphScope("other", "user", "a", MemoryGraphScope.Kind.THREAD)));
            assertThrows(IllegalArgumentException.class, () -> fixture.memory.inScope(
                    new MemoryGraphScope("workspace", "other-user", "a", MemoryGraphScope.Kind.THREAD)));
        }
    }

    @Test void duplicateTurnDeliveryAndSourceReinforcementAreIdempotent() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        try (var fixture = fixture(request -> {
            modelCalls.incrementAndGet();
            var output = JsonNodeFactory.instance.objectNode();
            output.putArray("entities");
            output.putArray("facts").addObject().put("text", "用户使用 Java 开发项目")
                    .put("confidence", .98);
            return new ModelTaskResult(output, "test", 0, 0, false, Map.of());
        }, true)) {
            var a = thread("once");
            var run = RunId.random();
            fixture.memory.rememberTurn(a, run, "t1", 5, "用户使用 Java 开发项目", "已确认", null, false);
            await(() -> fixture.memory.inScope(a).episodes().getFirst().distilled);
            fixture.memory.rememberTurn(a, run, "t1", 5, "用户使用 Java 开发项目", "已确认", null, false);
            assertEquals(1, fixture.memory.inScope(a).episodes().size());
            assertEquals(1, fixture.memory.inScope(a).facts().size());
            assertEquals(1, modelCalls.get());
            assertEquals(List.of("once:t1"), fixture.memory.inScope(a).facts().getFirst().evidenceKeys);
        }
    }

    @Test void forkPreservesEvidenceIdentityAndCutoffAfterOriginalDeletion() throws Exception {
        try (var fixture = fixture(ignored -> empty(), false)) {
            var parent = thread("parent");
            var fork = thread("fork");
            fixture.memory.rememberTurn(parent, null, "t1", 3, "第一轮选择的实现", "采用独立图谱", null, false);
            fixture.memory.rememberTurn(parent, null, "t2", 7, "之后追加的计划", "秘密的后续变化", null, false);
            fixture.memory.forkThread(parent, fork, 3);
            var copied = fixture.memory.inScope(fork).episodes();
            assertEquals(1, copied.size());
            assertEquals("parent:t1", copied.getFirst().evidenceKey());
            assertEquals("fork", copied.getFirst().sessionId);
            fixture.memory.deleteThread(parent);
            assertThrows(IllegalStateException.class, () -> fixture.memory.inScope(parent));
            assertTrue(fixture.memory.recall(fork, "继续", 8).contains("采用独立图谱"));
            assertFalse(fixture.memory.recall(fork, "继续", 8).contains("秘密的后续变化"));
        }
    }

    @Test void deletedSourceCannotBeReopenedAndPromotedPreferenceSurvivesRestart() throws Exception {
        Path path;
        try (var fixture = fixture(ignored -> empty(), false)) {
            path = fixture.path;
            var source = thread("deleted");
            fixture.memory.rememberExplicitPreference(source, "turn", "我喜欢使用简洁的中文回答。");
            assertTrue(fixture.memory.recall(thread("other"), "继续", 8).contains("我喜欢使用简洁的中文回答"));
            var stale = fixture.memory.inScope(source).store();
            fixture.memory.deleteThread(source);
            assertThrows(IllegalStateException.class, () -> stale.addPendingEpisode(
                    new Episode("deleted", "late", "late"), "late"));
            assertEquals(1, fixture.memory.facts().size());
            assertTrue(fixture.memory.facts().getFirst().evidenceDeleted);
        }
        try (var embedding = TestEmbeddingGatewayFactory.create(4, (text, timeout) -> new double[]{1, 0, 0, 0});
             var memory = new MemoryService(request -> CompletableFuture.completedFuture(empty()),
                     embedding.gateway(), embedding.tasks(), settings)) {
            memory.open(path, "workspace", "user");
            assertThrows(IllegalStateException.class, () -> memory.inScope(thread("deleted")));
            assertEquals(1, memory.facts().size());
            assertFalse(memory.scopes().contains(thread("deleted")));
        }
    }

    @Test void lateModelCompletionCannotResurrectDeletedGraph() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        CompletableFuture<ModelTaskResult> response = new CompletableFuture<>();
        try (var embedding = TestEmbeddingGatewayFactory.create(4, (text, timeout) -> new double[]{1, 0, 0, 0});
             var memory = new MemoryService(request -> { called.countDown(); return response; },
                     embedding.gateway(), embedding.tasks(), settings)) {
            Path path = temporary.resolve("late");
            memory.open(path, "workspace", "user");
            var scope = thread("late");
            memory.rememberTurn(scope, RunId.random(), "late-turn", 4,
                    "用户使用 Java 编写长时间运行程序", "完成", null, false);
            assertTrue(called.await(5, TimeUnit.SECONDS));
            Thread completion = Thread.ofVirtual().start(() -> {
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                response.complete(empty());
            });
            memory.deleteThread(scope);
            completion.join();
            assertThrows(IllegalStateException.class, () -> memory.inScope(scope));
            await(() -> !Files.exists(scope.directory(path)));
        } finally { response.complete(empty()); }
    }

    @Test void habitReviewRechecksOtherSourceGraphDeletionBeforePromotion() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        CompletableFuture<ModelTaskResult> response = new CompletableFuture<>();
        try (var embedding = TestEmbeddingGatewayFactory.create(4, (text, timeout) -> new double[]{1, 0, 0, 0});
             var memory = new MemoryService(request -> {
                 if (!request.purpose().equals("memory.habit.review")) return CompletableFuture.completedFuture(empty());
                 called.countDown(); return response;
             }, embedding.gateway(), embedding.tasks(), settings)) {
            memory.open(temporary.resolve("habit-evidence-deletion"), "workspace", "user");
            for (int index = 1; index <= 19; index++) {
                String id = index == 1 ? "removed-evidence" : "review-owner";
                Episode episode = new Episode(id, "用户多次请求用表格展示比较信息", "完成");
                episode.turnId = "evidence-" + index;
                episode.originThreadId = id;
                episode.originTurnId = episode.turnId;
                episode.timestamp = index;
                episode.habitEvidence = true;
                episode.distilled = true;
                memory.inScope(thread(id)).store().addPendingEpisode(episode, "test");
            }
            memory.rememberTurn(thread("review-owner"), RunId.random(), "trigger", 4,
                    "用户再次要求所有候选方案均用表格展示比较信息以便阅读", "完成", null, true);
            assertTrue(called.await(5, TimeUnit.SECONDS));
            memory.deleteThread(thread("removed-evidence"));
            var output = JsonNodeFactory.instance.objectNode();
            output.putArray("habits").addObject().put("text", "用户习惯以表格比较候选方案")
                    .put("confidence", .99).putArray("evidence").add(1).add(20);
            response.complete(new ModelTaskResult(output, "test", 0, 0, false, Map.of()));
            await(() -> memory.store().lastHabitReviewAt() > 0);
            assertTrue(memory.facts().isEmpty());
            assertFalse(memory.scopes().contains(thread("removed-evidence")));
        } finally { response.complete(empty()); }
    }

    @Test void legacyMixedStoreIsInspectableButNeverRecalled() throws Exception {
        Path path = temporary.resolve("legacy");
        try (var embedding = TestEmbeddingGatewayFactory.create(4, (text, timeout) -> new double[]{1, 0, 0, 0});
             var memory = new MemoryService(request -> CompletableFuture.completedFuture(empty()),
                     embedding.gateway(), embedding.tasks(), settings)) {
            memory.open(path);
            memory.addFact("历史", "旧会话私有的独角兽计划");
        }
        try (var embedding = TestEmbeddingGatewayFactory.create(4, (text, timeout) -> new double[]{1, 0, 0, 0});
             var memory = new MemoryService(request -> CompletableFuture.completedFuture(empty()),
                     embedding.gateway(), embedding.tasks(), settings)) {
            memory.open(path, "workspace", "local-user");
            var legacy = new MemoryGraphScope("workspace", "local-user", "", MemoryGraphScope.Kind.LEGACY);
            assertTrue(memory.scopes().contains(legacy));
            assertEquals(1, memory.inScope(legacy).facts().size());
            assertEquals("", memory.recall(legacy, "独角兽", 8));
            assertFalse(memory.recall(new MemoryGraphScope("workspace", "local-user", "fresh", MemoryGraphScope.Kind.THREAD), "独角兽", 8).contains("旧会话私有的独角兽计划"));
        }
    }

    private Fixture fixture(java.util.function.Function<com.javaclaw.framework.spi.ModelTaskRequest, ModelTaskResult> model,
                            boolean embeddings) {
        var embedding = TestEmbeddingGatewayFactory.create(4, (text, timeout) -> {
            if (!embeddings) throw new IllegalStateException("offline");
            return new double[]{1, 0, 0, 0};
        });
        var memory = new MemoryService(request -> CompletableFuture.completedFuture(model.apply(request)),
                embedding.gateway(), embedding.tasks(), settings);
        Path path = temporary.resolve("graphs-" + stores.incrementAndGet());
        memory.open(path, "workspace", "user");
        return new Fixture(path, memory, embedding);
    }
    private static MemoryGraphScope thread(String id) {
        return new MemoryGraphScope("workspace", "user", id, MemoryGraphScope.Kind.THREAD);
    }
    private static ModelTaskResult empty() {
        var output = JsonNodeFactory.instance.objectNode();
        output.putArray("facts"); output.putArray("entities"); output.putArray("habits");
        return new ModelTaskResult(output, "test", 0, 0, false, Map.of());
    }
    private static void await(BooleanSupplier predicate) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!predicate.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(predicate.getAsBoolean());
    }
    private record Fixture(Path path, MemoryService memory,
                           TestEmbeddingGatewayFactory.Fixture embedding) implements AutoCloseable {
        @Override public void close() { memory.close(); embedding.close(); }
    }
}
