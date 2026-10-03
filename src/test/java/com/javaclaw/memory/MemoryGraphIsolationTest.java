package com.javaclaw.memory;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.infrastructure.memory.EclipseStoreMemoryExtensionAdapter;
import com.javaclaw.memory.embed.TestEmbeddingGatewayFactory;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.model.PreferenceProposal;
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

    @Test void deferredMemorySearchUsesIndexedVersionsAndFetchChecksCurrentBody() throws Exception {
        try (var fixture = fixture(ignored -> empty(), false)) {
            var scope = thread("deferred");
            var selected = fixture.memory.inScope(scope);
            Episode episode = new Episode("deferred", "用户提到蓝色偏好", "已经记录");
            episode.toolTraceJson = "large-trace-".repeat(10_000);
            selected.store().addPendingEpisode(episode, "test");
            Fact fact = new Fact("偏好", "用户偏好蓝色", null);
            fact.userAsserted = true;
            selected.store().addPendingFact(fact, "test");
            RunRequest request = RunRequest.builder().agent(AgentDefinitionRef.latest("test"))
                    .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", "deferred"))
                    .input(InputBlock.text("蓝色偏好")).build();
            var source = new EclipseStoreMemoryExtensionAdapter(fixture.memory,
                    ignored -> CompletableFuture.completedFuture(empty()));

            var candidates = source.search(request, "偏好", 8);
            var foundEpisode = candidates.stream().filter(value -> value.id().equals(
                    "thread:episode:" + episode.id)).findFirst().orElseThrow();
            var foundFact = candidates.stream().filter(value -> value.id().equals(
                    "thread:fact:" + fact.id)).findFirst().orElseThrow();
            assertEquals(episode.deferredContextDigest, foundEpisode.version());
            assertEquals(fact.deferredContextDigest, foundFact.version());
            assertTrue(episode.deferredSearchText.length() <= 769);
            assertTrue(fact.deferredSearchText.length() <= 768);

            episode.toolTraceJson = "changed-after-index";
            var searchedAgain = source.search(request, "偏好", 8).stream()
                    .filter(value -> value.id().equals(foundEpisode.id())).findFirst().orElseThrow();
            assertEquals(foundEpisode.version(), searchedAgain.version(),
                    "搜索不应拼接未选中的完整工具轨迹来重新计算版本");
            assertThrows(IllegalStateException.class,
                    () -> source.fetch(request, foundEpisode.id(), foundEpisode.version()));
            selected.store().updatePendingFact(fact, value -> value.text = "用户偏好绿色", "test");
            var revisedFact = source.search(request, "偏好", 8).stream()
                    .filter(value -> value.id().equals(foundFact.id())).findFirst().orElseThrow();
            assertFalse(revisedFact.version().equals(foundFact.version()));
            assertTrue(source.fetch(request, revisedFact.id(), revisedFact.version()).contains("绿色"));
            RunRequest otherThread = RunRequest.builder().agent(AgentDefinitionRef.latest("test"))
                    .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", "other"))
                    .input(InputBlock.text("蓝色偏好")).build();
            assertThrows(IllegalStateException.class,
                    () -> source.fetch(otherThread, revisedFact.id(), revisedFact.version()));
        }
    }

    @Test void deferredMemorySearchOnlyReadsBoundedMetadata() throws Exception {
        try (var fixture = fixture(ignored -> empty(), false)) {
            var scope = thread("bounded-search");
            Episode episode = new Episode("bounded-search", "普通请求",
                    "front-marker " + "x".repeat(100_000) + " tail-marker");
            fixture.memory.inScope(scope).store().addPendingEpisode(episode, "test");
            RunRequest request = RunRequest.builder().agent(AgentDefinitionRef.latest("test"))
                    .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", "bounded-search"))
                    .input(InputBlock.text("普通请求")).build();
            var source = new EclipseStoreMemoryExtensionAdapter(fixture.memory,
                    ignored -> CompletableFuture.completedFuture(empty()));

            assertTrue(episode.deferredSearchText.length() <= 769);
            assertTrue(episode.deferredSummary.length() <= 181);
            assertTrue(source.search(request, "front-marker", 8).stream()
                    .anyMatch(value -> value.id().equals("thread:episode:" + episode.id)));
            assertEquals(source.search(request, "unrelated-request", 8),
                    source.search(request, "tail-marker", 8),
                    "a recent summary may still be offered, but the unindexed tail must not affect search");
        }
    }

    @Test void deferredMemoryUsesTheSameVerifiedPendingFactsAsImmediateRecall() throws Exception {
        try (var fixture = fixture(ignored -> empty(), false)) {
            var scope = thread("verified-pending");
            Fact distilled = new Fact("项目", "阶段记录中的已蒸馏事实", null);
            distilled.sourceKind = "DISTILLED";
            fixture.memory.inScope(scope).store().addPendingFact(distilled, "test");
            Fact habit = new Fact("偏好", "阶段记录中的已核实习惯", null);
            habit.sourceKind = "HABIT_REVIEW";
            fixture.memory.inScope(scope.habits()).store().addPendingFact(habit, "test");
            Fact unverified = new Fact("猜测", "阶段记录中的未核实猜测", null);
            unverified.sourceKind = "MODEL_GUESS";
            fixture.memory.inScope(scope).store().addPendingFact(unverified, "test");
            var source = new EclipseStoreMemoryExtensionAdapter(fixture.memory,
                    ignored -> CompletableFuture.completedFuture(empty()));
            RunRequest request = request("verified-pending", "阶段记录");

            String immediate = fixture.memory.recall(scope, "阶段记录", 8);
            assertTrue(immediate.contains(distilled.text));
            assertTrue(immediate.contains(habit.text));
            assertFalse(immediate.contains(unverified.text));
            var candidates = source.search(request, "阶段记录", 8);
            assertTrue(candidates.stream().anyMatch(value -> value.id().equals(
                    "thread:fact:" + distilled.id)));
            assertTrue(candidates.stream().anyMatch(value -> value.id().equals(
                    "habits:fact:" + habit.id)));
            assertFalse(candidates.stream().anyMatch(value -> value.id().endsWith(unverified.id)));
            var selected = candidates.stream().filter(value -> value.id().endsWith(distilled.id))
                    .findFirst().orElseThrow();
            assertTrue(source.fetch(request, selected.id(), selected.version()).contains(distilled.text));
            assertThrows(IllegalStateException.class, () -> source.fetch(request,
                    "thread:fact:" + unverified.id, unverified.deferredContextDigest));
        }
    }

    @Test void shortOrOfflineSearchOffersRecentEpisodeSummariesWithoutReadingBodies() throws Exception {
        try (var fixture = fixture(ignored -> empty(), false)) {
            var scope = thread("recent-candidates");
            var selected = fixture.memory.inScope(scope);
            List<Episode> episodes = new java.util.ArrayList<>();
            for (int index = 0; index < 6; index++) {
                Episode episode = new Episode("recent-candidates", "历史主题 " + index,
                        "仅正文可见的完整回复 " + index + " " + "x".repeat(1000));
                episode.timestamp = 100 + index;
                selected.store().addPendingEpisode(episode, "test");
                episodes.add(episode);
            }
            for (int index = 0; index < 8; index++) {
                Fact fact = new Fact("干扰项", "继续处理事实 " + index, null);
                fact.userAsserted = true;
                selected.store().addPendingFact(fact, "test");
            }
            var source = new EclipseStoreMemoryExtensionAdapter(fixture.memory,
                    ignored -> CompletableFuture.completedFuture(empty()));
            RunRequest request = request("recent-candidates", "继续");

            var shortQuery = source.search(request, "继续", 8);
            assertEquals(8, shortQuery.size());
            assertTrue(shortQuery.stream().allMatch(value -> value.id().startsWith("thread:fact:")),
                    "相关事实应先占满候选位，近期情景只补充空位");
            var offlineQuery = source.search(request, "这是一条与过去主题完全无关的长问题", 8);
            for (int index = 3; index < 6; index++) {
                Episode episode = episodes.get(index);
                var candidate = offlineQuery.stream().filter(value -> value.id().equals(
                        "thread:episode:" + episode.id)).findFirst().orElseThrow();
                assertEquals(episode.deferredSummary, candidate.summary());
                assertFalse(candidate.summary().contains("仅正文可见的完整回复"));
                assertTrue(source.fetch(request, candidate.id(), candidate.version())
                        .contains("仅正文可见的完整回复"));
            }
            assertTrue(offlineQuery.stream().noneMatch(value -> value.id().equals(
                    "thread:episode:" + episodes.get(0).id)));
            assertTrue(offlineQuery.stream().anyMatch(value -> value.id().equals(
                    "thread:episode:" + episodes.getLast().id)));
        }
    }

    @Test void smallLimitKeepsBothMatchingFactsAheadOfUnrelatedRecentEpisode() throws Exception {
        try (var fixture = fixture(ignored -> empty(), false)) {
            var selected = fixture.memory.inScope(thread("two-facts"));
            Fact first = new Fact("偏好", "用户偏好蓝色", null);
            first.userAsserted = true;
            selected.store().addPendingFact(first, "test");
            Fact second = new Fact("项目", "项目采用蓝色方案", null);
            second.userAsserted = true;
            selected.store().addPendingFact(second, "test");
            Episode episode = new Episode("two-facts", "无关的近期讨论", "历史回复");
            episode.timestamp = 100;
            selected.store().addPendingEpisode(episode, "test");
            var source = new EclipseStoreMemoryExtensionAdapter(fixture.memory,
                    ignored -> CompletableFuture.completedFuture(empty()));

            var candidates = source.search(request("two-facts", "蓝色"), "蓝色", 2);
            assertEquals(2, candidates.size());
            assertTrue(candidates.stream().anyMatch(value -> value.id().equals("thread:fact:" + first.id)));
            assertTrue(candidates.stream().anyMatch(value -> value.id().equals("thread:fact:" + second.id)));
        }
    }

    @Test void shortQueryKeepsMatchingFactWhenRecentEpisodesExceedSmallLimit() throws Exception {
        try (var fixture = fixture(ignored -> empty(), false)) {
            var selected = fixture.memory.inScope(thread("small-limit"));
            Fact fact = new Fact("偏好", "用户偏好蓝色", null);
            fact.userAsserted = true;
            fact.pinned = true;
            selected.store().addPendingFact(fact, "test");
            for (int index = 0; index < 4; index++) {
                Episode episode = new Episode("small-limit", "历史主题 " + index, "历史回复 " + index);
                episode.timestamp = 100 + index;
                selected.store().addPendingEpisode(episode, "test");
            }
            var source = new EclipseStoreMemoryExtensionAdapter(fixture.memory,
                    ignored -> CompletableFuture.completedFuture(empty()));
            RunRequest request = request("small-limit", "蓝色");

            for (int limit = 1; limit <= 3; limit++) {
                var candidates = source.search(request, "蓝色", limit);
                assertEquals(limit, candidates.size());
                assertTrue(candidates.stream().anyMatch(value -> value.id().equals(
                        "thread:fact:" + fact.id)), "小 topK 应保留精确匹配事实");
            }
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
            fixture.memory.rememberTurn(source, null, "turn", 1,
                    "我喜欢使用简洁的中文回答。", "已记录", null, true);
            fixture.memory.rememberPreferenceProposal(source, "turn",
                    new PreferenceProposal("我喜欢使用简洁的中文回答。", .99));
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
                     embedding.gateway(), embedding.tasks(), settings,
                     new com.fasterxml.jackson.databind.ObjectMapper())) {
            memory.open(path, "workspace", "user");
            assertThrows(IllegalStateException.class, () -> memory.inScope(thread("deleted")));
            assertEquals(1, memory.facts().size());
            assertFalse(memory.scopes().contains(thread("deleted")));
        }
    }

    @Test void preferenceProposalRequiresCommittedTurnAndVerbatimUserEvidence() throws Exception {
        try (var fixture = fixture(ignored -> empty(), false)) {
            var source = thread("preference-evidence");
            var proposal = new PreferenceProposal("I prefer concise answers", .99);
            fixture.memory.rememberPreferenceProposal(source, "turn", proposal);
            assertTrue(fixture.memory.facts().isEmpty());

            fixture.memory.rememberTurn(source, null, "turn", 1,
                    "I prefer concise answers", "Understood", null, true);
            fixture.memory.rememberPreferenceProposal(source, "turn",
                    new PreferenceProposal("I prefer verbose answers", .99));
            fixture.memory.rememberPreferenceProposal(source, "turn",
                    new PreferenceProposal("I prefer concise answers", .5));
            fixture.memory.rememberTerminal(source, null, "failed-turn", 2,
                    "I prefer verbose answers", "", null, true,
                    source.threadId(), "failed-turn", MemoryTurnStatus.FAILED);
            fixture.memory.rememberPreferenceProposal(source, "failed-turn",
                    new PreferenceProposal("I prefer verbose answers", .99));
            assertTrue(fixture.memory.facts().isEmpty());

            fixture.memory.rememberPreferenceProposal(source, "turn", proposal);
            fixture.memory.rememberPreferenceProposal(source, "turn", proposal);
            assertEquals(1, fixture.memory.facts().size());
            assertEquals("I prefer concise answers", fixture.memory.facts().getFirst().text);
            assertEquals(List.of("preference-evidence:turn"),
                    fixture.memory.facts().getFirst().evidenceKeys);
        }
    }

    @Test void distillerPromotesStructuredPreferenceProposalFromCommittedTurn() throws Exception {
        var output = JsonNodeFactory.instance.objectNode();
        output.putArray("facts");
        output.putArray("entities");
        var claims = output.putArray("preferenceClaims");
        claims.addObject()
                .put("sourceQuote", "I prefer concise answers")
                .put("confidence", .99);
        claims.addObject()
                .put("sourceQuote", "I prefer verbose answers")
                .put("confidence", .99);
        try (var fixture = fixture(ignored -> new ModelTaskResult(
                output, "test", 0, 0, false, Map.of()), false)) {
            var source = thread("structured-preference");
            fixture.memory.rememberTurn(source, RunId.random(), "turn", 1,
                    "I prefer concise answers", "Understood", null, true);
            await(() -> fixture.memory.facts().size() == 1);
            assertEquals("I prefer concise answers", fixture.memory.facts().getFirst().text);
            assertEquals(List.of("structured-preference:turn"),
                    fixture.memory.facts().getFirst().evidenceKeys);
        }
    }

    @Test void lateModelCompletionCannotResurrectDeletedGraph() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        CompletableFuture<ModelTaskResult> response = new CompletableFuture<>();
        try (var embedding = TestEmbeddingGatewayFactory.create(4, (text, timeout) -> new double[]{1, 0, 0, 0});
             var memory = new MemoryService(request -> { called.countDown(); return response; },
                     embedding.gateway(), embedding.tasks(), settings,
                     new com.fasterxml.jackson.databind.ObjectMapper())) {
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
             }, embedding.gateway(), embedding.tasks(), settings,
                     new com.fasterxml.jackson.databind.ObjectMapper())) {
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
            output.putArray("observations");
            output.put("observationOverflow", false);
            response.complete(new ModelTaskResult(output, "test", 0, 0, false, Map.of()));
            await(() -> memory.store().lastHabitReviewAt() > 0);
            assertTrue(memory.facts().isEmpty());
            assertFalse(memory.scopes().contains(thread("removed-evidence")));
        } finally { response.complete(empty()); }
    }

    @Test void unscopedStoreIsNotDiscoveredOrRecalledByCurrentGraphs() throws Exception {
        Path path = temporary.resolve("unscoped");
        try (var embedding = TestEmbeddingGatewayFactory.create(4, (text, timeout) -> new double[]{1, 0, 0, 0});
             var memory = new MemoryService(request -> CompletableFuture.completedFuture(empty()),
                     embedding.gateway(), embedding.tasks(), settings,
                     new com.fasterxml.jackson.databind.ObjectMapper())) {
            memory.open(path);
            memory.addFact("历史", "旧会话私有的独角兽计划");
        }
        try (var embedding = TestEmbeddingGatewayFactory.create(4, (text, timeout) -> new double[]{1, 0, 0, 0});
             var memory = new MemoryService(request -> CompletableFuture.completedFuture(empty()),
                     embedding.gateway(), embedding.tasks(), settings,
                     new com.fasterxml.jackson.databind.ObjectMapper())) {
            memory.open(path, "workspace", "local-user");
            assertEquals(1, memory.scopes().size());
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
                embedding.gateway(), embedding.tasks(), settings,
                new com.fasterxml.jackson.databind.ObjectMapper());
        Path path = temporary.resolve("graphs-" + stores.incrementAndGet());
        memory.open(path, "workspace", "user");
        return new Fixture(path, memory, embedding);
    }
    private static MemoryGraphScope thread(String id) {
        return new MemoryGraphScope("workspace", "user", id, MemoryGraphScope.Kind.THREAD);
    }
    private static RunRequest request(String threadId, String text) {
        return RunRequest.builder().agent(AgentDefinitionRef.latest("test"))
                .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", threadId))
                .input(InputBlock.text(text)).build();
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
