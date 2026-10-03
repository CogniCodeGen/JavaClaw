package com.javaclaw.memory.curation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.SqlPropertyStore;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskRequest;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.memory.embed.TestEmbeddingGatewayFactory;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.MemoryStats;
import com.javaclaw.memory.store.MemoryStore;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HabitReviewerCrossBatchTest {
    private Path temporary;
    private AnnotationConfigApplicationContext context;
    private AgentConfig settings;

    @BeforeAll
    void createConfiguration(@TempDir Path temporary) {
        this.temporary = temporary;
        context = ApplicationContexts.createRoot(new DataRoot(temporary.resolve("config")));
        Properties values = new Properties();
        values.setProperty("memory.habit.review.min.episodes", "20");
        values.setProperty("memory.habit.review.max.episodes", "20");
        assertTrue(context.getBean(SqlPropertyStore.class).save("agent", values));
        settings = context.getBean(AgentConfig.class);
        settings.reload();
    }

    @AfterAll
    void closeConfiguration() {
        context.close();
    }

    @Test
    void boundarySingletonPromotesAfterStoreReopen() {
        Path directory = temporary.resolve("cross-batch");
        FakeGateway gateway = new FakeGateway();
        ObjectNode first = emptyReview();
        first.withArray("observations").addObject()
                .put("text", "用户偏好表格展示")
                .put("evidence", 20);
        gateway.respond(first);
        ObjectNode second = emptyReview();
        second.withArray("habits").addObject()
                .put("text", "用户偏好表格展示")
                .put("confidence", .99)
                .putArray("evidence").add(1).add(21);
        gateway.respond(second);

        try (var embedding = embedding();
             MemoryStore store = open(directory)) {
            for (int index = 1; index <= 40; index++) {
                String input = index == 20 || index == 21
                        ? "用户希望用表格展示方案 " + "x".repeat(550)
                        : "无关讨论 " + "x".repeat(565);
                store.addEpisode(episode(index, input), "test");
            }
            new HabitReviewer(gateway, store, embedding.gateway(), settings).reviewNow(RunId.random());
            assertEquals(20, store.habitReviewProgress().cursorTimestamp());
            assertEquals(1, store.habitReviewProgress().observations().size());
            assertTrue(store.allFacts().isEmpty());
        }
        try (var embedding = embedding();
             MemoryStore store = open(directory)) {
            new HabitReviewer(gateway, store, embedding.gateway(), settings).reviewNow(RunId.random());
            assertEquals(40, store.habitReviewProgress().cursorTimestamp());
            assertTrue(store.habitReviewProgress().observations().isEmpty());
            assertEquals(1, store.allFacts().size());
            assertTrue(store.allFacts().getFirst().evidenceKeys.contains("s:turn-20"));
            assertTrue(store.allFacts().getFirst().evidenceKeys.contains("s:turn-21"));
            assertEquals(2, gateway.requests.size());
            String sent = gateway.requests.get(1).input().path("digest").asText();
            assertTrue(sent.contains("#21 既有未验证单轮线索"));
            assertTrue(sent.contains("s:turn-20"));
            assertEquals(1, gateway.requests.get(1).input().path("priorObservationCount").asInt());
        }
    }

    @Test
    void budgetLimitedCycleDrainsAcrossRestartsAndNextCycleKeepsMinimum() {
        Path directory = temporary.resolve("budget-limited-cycle");
        FakeGateway gateway = new FakeGateway();
        for (int index = 0; index < 5; index++) gateway.respond(emptyReview());
        long firstCursor;
        try (var embedding = embedding(); MemoryStore store = open(directory)) {
            for (int index = 1; index <= 40; index++) {
                store.addEpisode(episode(index, "x".repeat(700)), "test");
            }
            new HabitReviewer(gateway, store, embedding.gateway(), settings)
                    .reviewNow(RunId.random());
            firstCursor = store.habitReviewProgress().cursorTimestamp();
            assertTrue(firstCursor > 0 && firstCursor < 20);
            assertTrue(store.habitReviewProgress().draining());
            assertTrue(store.habitReviewProgress().pendingEvidenceKeys().isEmpty());
        }
        try (var embedding = embedding(); MemoryStore store = open(directory)) {
            assertTrue(store.habitReviewProgress().draining());
            new HabitReviewer(gateway, store, embedding.gateway(), settings)
                    .reviewNow(RunId.random());
            assertTrue(store.habitReviewProgress().cursorTimestamp() > firstCursor);
            assertTrue(store.habitReviewProgress().cursorTimestamp() < 40);
            assertTrue(store.habitReviewProgress().draining());
        }
        try (var embedding = embedding(); MemoryStore store = open(directory)) {
            new HabitReviewer(gateway, store, embedding.gateway(), settings)
                    .reviewNow(RunId.random());
            assertEquals(40, store.habitReviewProgress().cursorTimestamp());
            assertFalse(store.habitReviewProgress().draining());
            assertTrue(store.habitReviewProgress().pendingEvidenceKeys().isEmpty());
            assertTrue(store.lastHabitReviewAt() > 0);
            assertEquals(3, gateway.requests.size());

            for (int index = 41; index <= 59; index++) {
                store.addEpisode(episode(index, "x".repeat(700)), "test");
            }
            new HabitReviewer(gateway, store, embedding.gateway(), settings)
                    .reviewNow(RunId.random());
            assertEquals(3, gateway.requests.size());
            assertEquals(40, store.habitReviewProgress().cursorTimestamp());

            store.addEpisode(episode(60, "x".repeat(700)), "test");
            new HabitReviewer(gateway, store, embedding.gateway(), settings)
                    .reviewNow(RunId.random());
            assertEquals(4, gateway.requests.size());
            assertTrue(store.habitReviewProgress().draining());
            new HabitReviewer(gateway, store, embedding.gateway(), settings)
                    .reviewNow(RunId.random());
            assertEquals(5, gateway.requests.size());
            assertEquals(60, store.habitReviewProgress().cursorTimestamp());
            assertFalse(store.habitReviewProgress().draining());
        }
    }

    @Test
    void deletedTailCompletesPersistedDrainWithoutCallingModel() {
        Path directory = temporary.resolve("deleted-drain-tail");
        FakeGateway gateway = new FakeGateway();
        gateway.respond(emptyReview());
        AtomicInteger latestLive = new AtomicInteger(Integer.MAX_VALUE);
        long cursor;
        try (var embedding = embedding(); MemoryStore store = open(directory)) {
            for (int index = 1; index <= 40; index++) {
                store.addEpisode(episode(index, "x".repeat(700)), "test");
            }
            new HabitReviewer(gateway, store, embedding.gateway(), settings,
                    () -> store.episodesSince(0, Integer.MAX_VALUE).stream()
                            .filter(item -> item.timestamp <= latestLive.get()).toList(), Runnable::run,
                    item -> item.timestamp <= latestLive.get()).reviewNow(RunId.random());
            cursor = store.habitReviewProgress().cursorTimestamp();
            assertTrue(store.habitReviewProgress().draining());
        }
        latestLive.set((int) cursor);
        try (var embedding = embedding(); MemoryStore store = open(directory)) {
            new HabitReviewer(gateway, store, embedding.gateway(), settings,
                    () -> store.episodesSince(0, Integer.MAX_VALUE).stream()
                            .filter(item -> item.timestamp <= latestLive.get()).toList(), Runnable::run,
                    item -> item.timestamp <= latestLive.get()).reviewNow(RunId.random());
            assertEquals(cursor, store.habitReviewProgress().cursorTimestamp());
            assertFalse(store.habitReviewProgress().draining());
            assertEquals(2, store.habitReviewProgress().revision());
            assertTrue(store.lastHabitReviewAt() > 0);
            assertEquals(1, gateway.requests.size());
        }
    }

    @Test
    void trulyOversizedEvidenceRemainsPendingAcrossReopen() {
        Path directory = temporary.resolve("single-oversized");
        FakeGateway gateway = new FakeGateway();
        gateway.respond(emptyReview());
        try (var embedding = embedding(); MemoryStore store = open(directory)) {
            store.addEpisode(episode(1, "x".repeat(12_001)), "test");
            for (int index = 2; index <= 21; index++) {
                store.addEpisode(episode(index, "habit evidence"), "test");
            }
            new HabitReviewer(gateway, store, embedding.gateway(), settings)
                    .reviewNow(RunId.random());
            assertEquals(List.of("s:turn-1"), store.habitReviewProgress().pendingEvidenceKeys());
            assertEquals(21, store.habitReviewProgress().cursorTimestamp());
        }
        try (var embedding = embedding(); MemoryStore store = open(directory)) {
            new HabitReviewer(gateway, store, embedding.gateway(), settings)
                    .reviewNow(RunId.random());
            assertEquals(1, gateway.requests.size());
            assertEquals(List.of("s:turn-1"), store.habitReviewProgress().pendingEvidenceKeys());
            assertEquals(21, store.habitReviewProgress().cursorTimestamp());
        }
    }

    @Test
    void fullObservationLedgerDoesNotAdvanceCursorOrWriteFacts() {
        Path directory = temporary.resolve("ledger-full");
        FakeGateway gateway = new FakeGateway();
        ObjectNode output = emptyReview();
        output.withArray("observations").addObject().put("text", "新增单轮线索").put("evidence", 1);
        gateway.respond(output);
        try (var embedding = embedding();
             MemoryStore store = open(directory)) {
            for (int index = 1; index <= 84; index++) {
                store.addEpisode(episode(index, "第 " + index + " 轮内容"), "test");
            }
            List<MemoryStats.HabitObservation> old = new ArrayList<>();
            for (int index = 1; index <= 64; index++) {
                old.add(new MemoryStats.HabitObservation("单轮线索 " + index, "s:turn-" + index));
            }
            store.markHabitReviewProgress(0, 64, "s:turn-64", List.of(),
                    old, false, "test", "previous batches");
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> new HabitReviewer(gateway, store, embedding.gateway(), settings)
                            .reviewNow(RunId.random()));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(failure.getCause().getMessage().contains("台账已满"));
            assertEquals(64, store.habitReviewProgress().cursorTimestamp());
            assertEquals(64, store.habitReviewProgress().observations().size());
            assertTrue(store.allFacts().isEmpty());
        }
        try (MemoryStore reopened = open(directory)) {
            assertEquals(64, reopened.habitReviewProgress().cursorTimestamp());
            assertEquals(64, reopened.habitReviewProgress().observations().size());
            assertFalse(reopened.habitReviewProgress().cursorEvidenceKey().isBlank());
        }
    }

    @Test
    void olderObservationsRemainPersistedWhenOnlySomeFitWithNextEpisode() {
        Path directory = temporary.resolve("prior-observations-and-large-episode");
        FakeGateway gateway = new FakeGateway();
        gateway.respond(emptyReview());
        try (var embedding = embedding(); MemoryStore store = open(directory)) {
            for (int index = 1; index <= 64; index++) {
                store.addEpisode(episode(index, "较短的旧证据"), "test");
            }
            List<MemoryStats.HabitObservation> old = new ArrayList<>();
            for (int index = 1; index <= 64; index++) {
                old.add(new MemoryStats.HabitObservation(
                        "尚待验证的旧线索" + "x".repeat(65), "s:turn-" + index));
            }
            store.markHabitReviewProgress(0, 64, "s:turn-64", List.of(),
                    old, false, "test", "previous batch");
            store.addEpisode(episode(65, "新一轮完整证据" + "x".repeat(6_000)), "test");

            new HabitReviewer(gateway, store, embedding.gateway(), settings)
                    .reviewNow(RunId.random());

            assertEquals(65, store.habitReviewProgress().cursorTimestamp());
            assertEquals(old.size(), store.habitReviewProgress().observations().size());
            assertEquals(old.stream().map(value -> value.evidenceKey + ":" + value.text).toList(),
                    store.habitReviewProgress().observations().stream()
                            .map(value -> value.evidenceKey + ":" + value.text).toList());
            assertEquals(1, gateway.requests.size());
            int presented = gateway.requests.getFirst().input().path("priorObservationCount").asInt();
            assertTrue(presented > 0 && presented < old.size());
            assertTrue(gateway.requests.getFirst().input().path("digest").asText().length() <= 12_000);
        }
        try (MemoryStore reopened = open(directory)) {
            assertEquals(65, reopened.habitReviewProgress().cursorTimestamp());
            assertEquals(64, reopened.habitReviewProgress().observations().size());
        }
    }

    @Test
    void deletedPriorEvidenceCannotPromoteAcrossBatches() {
        Path directory = temporary.resolve("deleted-prior");
        FakeGateway gateway = new FakeGateway();
        ObjectNode output = emptyReview();
        output.withArray("habits").addObject().put("text", "用户偏好表格展示")
                .put("confidence", .99).putArray("evidence").add(1).add(21);
        gateway.respond(output);
        AtomicBoolean oldLive = new AtomicBoolean(true);
        gateway.beforeRespond = () -> oldLive.set(false);
        try (var embedding = embedding();
             MemoryStore store = open(directory)) {
            for (int index = 1; index <= 40; index++) {
                store.addEpisode(episode(index, "用户希望用表格展示方案"), "test");
            }
            store.markHabitReviewProgress(0, 20, "s:turn-20", List.of(),
                    List.of(new MemoryStats.HabitObservation("用户偏好表格展示", "s:turn-20")),
                    false, "test", "first batch");
            new HabitReviewer(gateway, store, embedding.gateway(), settings,
                    () -> store.episodesSince(0, Integer.MAX_VALUE), Runnable::run,
                    episode -> !episode.evidenceKey().equals("s:turn-20") || oldLive.get())
                    .reviewNow(RunId.random());
            assertEquals(40, store.habitReviewProgress().cursorTimestamp());
            assertTrue(store.habitReviewProgress().observations().isEmpty());
            assertTrue(store.allFacts().isEmpty());
        }
    }

    @Test
    void staleConcurrentReviewIsRejectedBeforeAnyFactWrite() {
        Path directory = temporary.resolve("stale-review");
        FakeGateway gateway = new FakeGateway();
        ObjectNode output = emptyReview();
        output.withArray("habits").addObject().put("text", "用户偏好表格展示")
                .put("confidence", .99).putArray("evidence").add(1).add(2);
        gateway.respond(output);
        try (var embedding = embedding();
             MemoryStore store = open(directory)) {
            for (int index = 1; index <= 20; index++) {
                store.addEpisode(episode(index, "用户希望用表格展示方案"), "test");
            }
            gateway.beforeRespond = () -> store.markHabitReviewProgress(
                    99, 1, "s:turn-1", List.of(), List.of(), false, "other", "newer review");
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> new HabitReviewer(gateway, store, embedding.gateway(), settings)
                            .reviewNow(RunId.random()));
            assertTrue(failure.getCause() instanceof IllegalStateException,
                    "过期回顾应由存储边界拒绝提交");
            assertEquals(1, store.habitReviewProgress().cursorTimestamp());
            assertEquals(1, store.habitReviewProgress().revision());
            assertTrue(store.allFacts().isEmpty());
        }
    }

    @Test
    void embeddingDoesNotHoldStoreLockAndStaleReviewCannotCommit() throws Exception {
        Path directory = temporary.resolve("embedding-outside-lock");
        FakeGateway gateway = new FakeGateway();
        ObjectNode output = emptyReview();
        output.withArray("habits").addObject().put("text", "用户偏好表格展示")
                .put("confidence", .99).putArray("evidence").add(1).add(2);
        gateway.respond(output);
        CountDownLatch embeddingStarted = new CountDownLatch(1);
        CountDownLatch releaseEmbedding = new CountDownLatch(1);

        try (var embedding = TestEmbeddingGatewayFactory.create(4, (text, timeout) -> {
                 embeddingStarted.countDown();
                 if (!releaseEmbedding.await(15, TimeUnit.SECONDS)) {
                     throw new IllegalStateException("embedding was not released");
                 }
                 return new double[]{1, 0, 0, 0};
             });
             MemoryStore store = open(directory)) {
            for (int index = 1; index <= 20; index++) {
                store.addEpisode(episode(index, "用户希望用表格展示方案"), "test");
            }
            CompletableFuture<String> review = CompletableFuture.supplyAsync(() ->
                    new HabitReviewer(gateway, store, embedding.gateway(), settings)
                            .reviewNow(RunId.random()));
            try {
                assertTrue(embeddingStarted.await(5, TimeUnit.SECONDS));
                CompletableFuture<Void> concurrentWrite = CompletableFuture.runAsync(() -> {
                    store.checkpoint("other-write", "[]");
                    store.markHabitReviewProgress(99, 1, "s:turn-1", List.of(),
                            List.of(), false, "other", "newer review");
                });
                concurrentWrite.get(3, TimeUnit.SECONDS);
                assertEquals("[]", store.loadCheckpoint("other-write").messagesJson);
            } finally {
                releaseEmbedding.countDown();
            }
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> review.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof RuntimeException
                    && failure.getCause().getCause() instanceof IllegalStateException,
                    "过期回顾应由存储边界拒绝提交");
            assertTrue(store.allFacts().isEmpty());
            assertEquals(1, store.habitReviewProgress().revision());
        }
    }

    @Test
    void overflowOutputIsRejectedBeforeEmbedding() {
        FakeGateway gateway = new FakeGateway();
        ObjectNode output = emptyReview();
        output.put("observationOverflow", true);
        output.withArray("habits").addObject().put("text", "用户偏好表格展示")
                .put("confidence", .99).putArray("evidence").add(1).add(2);
        gateway.respond(output);
        AtomicInteger embeddingCalls = new AtomicInteger();

        try (var embedding = TestEmbeddingGatewayFactory.create(4, (text, timeout) -> {
                 embeddingCalls.incrementAndGet();
                 return new double[]{1, 0, 0, 0};
             });
             MemoryStore store = open(temporary.resolve("overflow-before-embedding"))) {
            for (int index = 1; index <= 20; index++) {
                store.addEpisode(episode(index, "用户希望用表格展示方案"), "test");
            }
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new HabitReviewer(gateway, store, embedding.gateway(), settings)
                            .reviewNow(RunId.random()));
            assertTrue(failure.getMessage().contains("线索超出模型输出容量"));
            assertEquals(0, embeddingCalls.get());
            assertTrue(store.allFacts().isEmpty());
        }
    }

    private static Episode episode(int index, String input) {
        Episode episode = new Episode("s", input, "reply");
        episode.turnId = "turn-" + index;
        episode.timestamp = index;
        episode.embedding = new float[]{1, 0, 0, 0};
        return episode;
    }

    private static ObjectNode emptyReview() {
        ObjectNode output = JsonNodeFactory.instance.objectNode();
        output.putArray("habits");
        output.putArray("observations");
        output.put("observationOverflow", false);
        return output;
    }

    private static TestEmbeddingGatewayFactory.Fixture embedding() {
        return TestEmbeddingGatewayFactory.create(4, (text, timeout) -> new double[]{1, 0, 0, 0});
    }

    private static MemoryStore open(Path directory) {
        MemoryStore store = new MemoryStore(directory, 4, "habit-observation-test");
        store.open();
        return store;
    }

    private static final class FakeGateway implements ModelTaskGateway {
        private final Deque<JsonNode> responses = new ArrayDeque<>();
        private final List<ModelTaskRequest> requests = new ArrayList<>();
        private Runnable beforeRespond = () -> { };

        void respond(JsonNode output) {
            responses.addLast(output.deepCopy());
        }

        @Override
        public CompletionStage<ModelTaskResult> execute(ModelTaskRequest request) {
            requests.add(request);
            beforeRespond.run();
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    responses.removeFirst(), "habit-test", 0, 0, false, Map.of()));
        }
    }
}
