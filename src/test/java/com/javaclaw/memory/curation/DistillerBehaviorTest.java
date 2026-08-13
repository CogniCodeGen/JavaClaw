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
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.store.MemoryStore;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DistillerBehaviorTest {
    private final AtomicInteger stores = new AtomicInteger();
    private Path temporaryDirectory;
    private AnnotationConfigApplicationContext context;
    private AgentConfig settings;
    private SqlPropertyStore properties;

    @BeforeAll
    void createConfiguration(@TempDir Path temporaryDirectory) {
        this.temporaryDirectory = temporaryDirectory;
        context = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data")));
        settings = context.getBean(AgentConfig.class);
        properties = context.getBean(SqlPropertyStore.class);
    }

    @BeforeEach
    void resetMemorySettings() {
        assertTrue(properties.save("agent", new Properties()));
        settings.reload();
    }

    @AfterAll
    void closeConfiguration() {
        context.close();
    }

    @Test
    void modelUseRequiresAnOwnerRunAndAnEligibleEpisode() {
        try (Fixture fixture = fixture((text, timeout) -> vector(1, 0, 0, 0))) {
            RunId owner = RunId.random();
            fixture.gateway.respond(extraction());
            fixture.distiller.distillNow(null, episode("long enough user input", "reply"));
            fixture.distiller.distillNow(owner, null);
            fixture.distiller.distillNow(owner, new Episode("s", null, "reply"));
            fixture.distiller.distillNow(owner, new Episode("s", "short", "reply"));
            fixture.distiller.distillNow(owner, new Episode("s", "long enough input", " "));
            assertEquals(0, fixture.gateway.calls);

            fixture.distiller.distillNow(owner,
                    new Episode("s", "不是张三，而是李四", "previous answer"));
            assertEquals(1, fixture.gateway.calls,
                    "显式纠错必须越过普通蒸馏长度门槛");
            assertEquals(owner, fixture.gateway.requests.getFirst().ownerRunId());
        }
    }

    @Test
    void structuredHighConfidenceEvidencePromotesFactsAndEntities() {
        try (Fixture fixture = fixture((text, timeout) -> text.contains("Kotlin")
                ? vector(0, 1, 0, 0) : vector(1, 0, 0, 0))) {
            ObjectNode response = extraction();
            addFact(response, "用户偏好 Java", 0.96);
            addFact(response, "用户计划学习 Kotlin", 0.91);
            addFact(response, "api_key=sk-live-secret-value", 0.99);
            addEntity(response, "Java", "technology");
            addEntity(response, "Kotlin", "language");
            fixture.gateway.respond(response);

            fixture.distiller.distillNow(RunId.random(), episode(
                    "请记住用户偏好 Java，用户计划学习 Kotlin", "好的，我会记住"));

            List<Fact> facts = fixture.store.allFacts();
            assertEquals(2, facts.size());
            assertTrue(facts.stream().allMatch(f -> "DISTILLED".equals(f.sourceKind)));
            assertTrue(facts.stream().noneMatch(f -> f.text.contains("api_key")));
            assertEquals(2, fixture.store.allEntities().size());
            assertTrue(facts.stream().anyMatch(f -> f.text.contains("Java")
                    && f.about.stream().anyMatch(e -> "Java".equals(e.name))));
            assertTrue(facts.stream().anyMatch(f -> f.text.contains("Kotlin")
                    && f.about.stream().anyMatch(e -> "Kotlin".equals(e.name))));
            assertEquals("memory.distillation.extract",
                    fixture.gateway.requests.getFirst().purpose());
        }
    }

    @Test
    void lowConfidenceOrUnavailableEmbeddingsRemainPendingAndFailuresAreContained() {
        try (Fixture low = fixture((text, timeout) -> vector(1, 0, 0, 0))) {
            ObjectNode response = extraction();
            addFact(response, "用户偏好离线模式", 0.4);
            low.gateway.respond(response);
            low.distiller.distillNow(RunId.random(), episode(
                    "请记住用户偏好离线模式", "已经记住"));
            assertTrue(low.store.allFacts().isEmpty());
            assertEquals(1, low.store.allPendingFacts().size());
        }

        try (Fixture unavailable = fixture((text, timeout) -> null)) {
            ObjectNode response = extraction();
            addFact(response, "用户偏好离线模式", 0.99);
            unavailable.gateway.respond(response);
            unavailable.distiller.distillNow(RunId.random(), episode(
                    "请记住用户偏好离线模式", "已经记住"));
            assertEquals(1, unavailable.store.allPendingFacts().size());

            unavailable.gateway.fail(new IllegalStateException("model unavailable"));
            assertDoesNotThrow(() -> unavailable.distiller.distillNow(
                    RunId.random(), episode("another valid user input", "answer")));
        }
    }

    @Test
    void duplicateFactsMergeAndModelApprovedReplacementSupersedesOldFact() {
        try (Fixture duplicate = fixture((text, timeout) -> vector(1, 0, 0, 0))) {
            Fact existing = new Fact("preferences", "用户喜欢 Java", storedVector(1, 0, 0, 0));
            duplicate.store.addFact(existing, "test");
            ObjectNode response = extraction();
            addFact(response, "用户喜欢 Java", 0.99);
            duplicate.gateway.respond(response);
            duplicate.distiller.distillNow(RunId.random(), episode(
                    "再次说明用户喜欢 Java", "了解"));
            assertEquals(1, duplicate.store.allFacts().size());
            assertEquals(1, existing.mergeCount);
        }

        try (Fixture replacement = fixture((text, timeout) -> text.contains("Kotlin")
                ? vector(0.8, 0.6, 0, 0) : vector(1, 0, 0, 0))) {
            Fact old = new Fact("tools", "用户主要使用 Java", storedVector(1, 0, 0, 0));
            replacement.store.addFact(old, "test");
            ObjectNode extraction = extraction();
            addFact(extraction, "用户现在主要使用 Kotlin", 0.99);
            replacement.gateway.respond(extraction);
            replacement.gateway.respond(indexes(1));

            replacement.distiller.distill(RunId.random(), episode(
                    "用户现在主要使用 Kotlin，已经从 Java 切换", "已更新技术栈")).block();

            assertTrue(old.superseded);
            assertEquals(2, replacement.store.allFacts().size());
            assertTrue(replacement.store.allFacts().stream()
                    .anyMatch(f -> f.text.contains("Kotlin") && !f.superseded));
        }
    }

    private Fixture fixture(TestEmbeddingGatewayFactory.Invoker invoker) {
        TestEmbeddingGatewayFactory.Fixture embedding =
                TestEmbeddingGatewayFactory.create(4, invoker);
        MemoryStore store = new MemoryStore(
                temporaryDirectory.resolve("memory-" + stores.incrementAndGet()),
                4, "distiller-test");
        store.open();
        FakeGateway gateway = new FakeGateway();
        return new Fixture(gateway, store, embedding,
                new Distiller(gateway, store, embedding.gateway(), settings));
    }

    private static Episode episode(String input, String reply) {
        return new Episode("session", input, reply);
    }

    private static ObjectNode extraction() {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.putArray("facts");
        result.putArray("entities");
        return result;
    }

    private static void addFact(ObjectNode result, String text, double confidence) {
        result.withArray("facts").addObject().put("text", text).put("confidence", confidence);
    }

    private static void addEntity(ObjectNode result, String name, String type) {
        result.withArray("entities").addObject().put("name", name).put("type", type);
    }

    private static ObjectNode indexes(int... values) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        var indexes = result.putArray("indexes");
        for (int value : values) indexes.add(value);
        return result;
    }

    private static double[] vector(double... values) { return values; }
    private static float[] storedVector(float... values) { return values; }

    private record Fixture(
            FakeGateway gateway,
            MemoryStore store,
            TestEmbeddingGatewayFactory.Fixture embedding,
            Distiller distiller) implements AutoCloseable {
        @Override
        public void close() {
            store.close();
            embedding.close();
        }
    }

    private static final class FakeGateway implements ModelTaskGateway {
        private final Deque<Object> responses = new ArrayDeque<>();
        private final List<ModelTaskRequest> requests = new ArrayList<>();
        private int calls;

        void respond(JsonNode output) { responses.addLast(output.deepCopy()); }
        void fail(Throwable failure) { responses.addLast(failure); }

        @Override
        public CompletionStage<ModelTaskResult> execute(ModelTaskRequest request) {
            calls++;
            requests.add(request);
            Object value = responses.isEmpty() ? extraction() : responses.removeFirst();
            if (value instanceof Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    (JsonNode) value, "distiller-test", 3, 2, false, Map.of()));
        }
    }
}
