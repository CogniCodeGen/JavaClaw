package com.javaclaw.memory.curation;

import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.SqlPropertyStore;
import com.javaclaw.memory.embed.TestEmbeddingGatewayFactory;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.store.MemoryStore;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DistillerBehaviorTest {

    Path temporaryDirectory;

    private final AtomicInteger stores = new AtomicInteger();
    private AnnotationConfigApplicationContext context;
    private AgentConfig settings;
    private SqlPropertyStore propertyStore;

    @BeforeAll
    void createConfiguration(@TempDir Path temporaryDirectory) {
        this.temporaryDirectory = temporaryDirectory;
        context = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data")));
        settings = context.getBean(AgentConfig.class);
        propertyStore = context.getBean(SqlPropertyStore.class);
    }

    @BeforeEach
    void resetMemorySettings() {
        assertTrue(propertyStore.save("agent", new Properties()));
        settings.reload();
    }

    @AfterAll
    void closeConfiguration() {
        context.close();
    }

    @Test
    void invalidShortAndBlankEpisodesNeverCallTheModel() throws Exception {
        try (Fixture fixture = fixture((text, timeout) -> vector(1, 0, 0, 0))) {
            fixture.distiller.distillNow(null);
            fixture.distiller.distillNow(new Episode("s", null, "reply"));
            fixture.distiller.distillNow(new Episode("s", "short", "reply"));
            fixture.distiller.distillNow(new Episode("s", "long enough input", null));
            fixture.distiller.distillNow(new Episode("s", "long enough input", " "));
            assertEquals(0, fixture.model.calls);

            fixture.model.respond("无");
            fixture.distiller.distillNow(new Episode(
                    "s", "不是张三，而是李四", "previous answer"));
            assertEquals(1, fixture.model.calls,
                    "显式纠错即使很短也必须越过普通蒸馏长度门槛");
        }
    }

    @Test
    void emptyNoneFailureAndLongReplyPathsAreContained() throws Exception {
        try (Fixture fixture = fixture((text, timeout) -> vector(1, 0, 0, 0))) {
            fixture.model.empty();
            fixture.distiller.distillNow(episode("first valid input", "answer"));
            assertTrue(fixture.store.allFacts().isEmpty());

            fixture.model.respond("- 无。");
            fixture.distiller.distill(episode("second valid input", "answer")).block();
            assertTrue(fixture.store.allFacts().isEmpty());

            fixture.model.fail(new IllegalStateException("model unavailable"));
            assertDoesNotThrow(() -> fixture.distiller.distillNow(
                    episode("third valid input", "answer")));

            fixture.model.respond("无");
            fixture.distiller.distillNow(episode(
                    "fourth valid input", "r".repeat(6_001)));
            String prompt = fixture.model.lastMessages.get(1).getTextContent();
            assertTrue(prompt.contains("...(截断)"));
            assertFalse(prompt.contains("r".repeat(6_001)));
        }
    }

    @Test
    void factsEntitiesCredentialsAndMixedModelBlocksAreHandledDeterministically() throws Exception {
        try (Fixture fixture = fixture((text, timeout) -> {
            if (text.contains("Kotlin")) return vector(0, 1, 0, 0);
            return vector(1, 0, 0, 0);
        })) {
            fixture.model.respondWithNullContentThen("""
                    - 用户使用 Java
                    - 无。
                    - api_key=sk-live-secret-value
                    - 用户计划学习 Kotlin
                    """);
            fixture.model.respond("""
                    - Java | technology
                    invalid line
                    - A | person
                    - authorization: Bearer secret-value | token
                    - Kotlin | language
                    """);

            fixture.distiller.distillNow(episode(
                    "请记录我的长期技术偏好设置", "好的，我会记住。"));

            List<Fact> facts = fixture.store.allFacts();
            assertEquals(2, facts.size());
            assertTrue(facts.stream().allMatch(f -> "DISTILLED".equals(f.sourceKind)));
            assertTrue(facts.stream().noneMatch(f -> f.text.contains("api_key")));
            assertEquals(2, fixture.store.allEntities().size());
            assertTrue(facts.stream().anyMatch(f -> f.text.contains("Java")
                    && f.about.stream().anyMatch(e -> "Java".equals(e.name))));
            assertTrue(facts.stream().anyMatch(f -> f.text.contains("Kotlin")
                    && f.about.stream().anyMatch(e -> "Kotlin".equals(e.name))));
        }
    }

    @Test
    void embeddingFailureCreatesPendingFactsWhileDuplicateMentionsMerge() throws Exception {
        try (Fixture unavailable = fixture((text, timeout) -> null)) {
            unavailable.model.respond("- 用户喜欢离线工作");
            unavailable.model.respond("无");
            unavailable.distiller.distillNow(episode(
                    "请记住我偏好离线模式", "已经记住"));
            assertTrue(unavailable.store.allFacts().isEmpty());
            assertEquals(1, unavailable.store.allPendingFacts().size());
            assertTrue(unavailable.store.allPendingFacts().getFirst().pending);
        }

        try (Fixture duplicate = fixture((text, timeout) -> vector(1, 0, 0, 0))) {
            Fact existing = new Fact("preferences", "用户喜欢 Java",
                    storedVector(1, 0, 0, 0));
            duplicate.store.addFact(existing, "test");
            duplicate.model.respond("- 用户仍然喜欢 Java");
            duplicate.model.respond("无");
            duplicate.distiller.distillNow(episode(
                    "再次说明我的语言偏好", "了解"));
            assertEquals(1, duplicate.store.allFacts().size());
            assertEquals(1, existing.mergeCount);
        }

        try (Fixture protectedFact = fixture((text, timeout) -> vector(1, 0, 0, 0))) {
            Fact existing = new Fact("preferences", "用户喜欢 Java",
                    storedVector(1, 0, 0, 0));
            existing.userEdited = true;
            protectedFact.store.addFact(existing, "user");
            protectedFact.model.respond("- 用户仍然喜欢 Java");
            protectedFact.model.respond("无");
            protectedFact.distiller.distillNow(episode(
                    "再次说明我的语言偏好", "了解"));
            assertEquals(2, protectedFact.store.allFacts().size());
            assertEquals(0, existing.mergeCount);
        }
    }

    @Test
    void relatedUnprotectedFactCanBeSupersededByAValidatedModelVerdict() throws Exception {
        try (Fixture fixture = fixture((text, timeout) -> text.contains("Kotlin")
                ? vector(0.8, 0.6, 0, 0) : vector(1, 0, 0, 0))) {
            Fact old = new Fact("tools", "用户主要使用 Java", storedVector(1, 0, 0, 0));
            fixture.store.addFact(old, "test");
            fixture.model.respond("- 用户现在主要使用 Kotlin");
            fixture.model.respond("无");
            fixture.model.respond("输出：1");

            fixture.distiller.distillNow(episode(
                    "我已经从 Java 切换到 Kotlin", "已更新技术栈"));

            assertTrue(old.superseded);
            assertEquals(2, fixture.store.allFacts().size());
            assertTrue(fixture.store.allFacts().stream()
                    .anyMatch(f -> f.text.contains("Kotlin") && !f.superseded));
        }
    }

    @Test
    void disabledEntityAndSupersedeGatesAvoidUnnecessaryModelCalls() throws Exception {
        configure("memory.graph.entities.enabled", "false");
        configure("memory.supersede.enabled", "false");
        try (Fixture fixture = fixture((text, timeout) -> vector(0.8, 0.6, 0, 0))) {
            fixture.store.addFact(new Fact(
                    "tools", "old fact", storedVector(1, 0, 0, 0)), "test");
            fixture.model.respond("- replacement fact");

            fixture.distiller.distillNow(episode(
                    "long enough replacement request", "replacement answer"));

            assertEquals(1, fixture.model.calls);
            assertTrue(fixture.store.allEntities().isEmpty());
            assertEquals(2, fixture.store.allFacts().size());
        }
    }

    private void configure(String key, String value) {
        Properties properties = settings.snapshotProperties();
        properties.setProperty(key, value);
        assertTrue(propertyStore.save("agent", properties));
        settings.reload();
    }

    private Fixture fixture(TestEmbeddingGatewayFactory.Invoker invoker) {
        TestEmbeddingGatewayFactory.Fixture embedding =
                TestEmbeddingGatewayFactory.create(4, invoker);
        MemoryStore store = new MemoryStore(
                temporaryDirectory.resolve("memory-" + stores.incrementAndGet()),
                4, "distiller-test");
        store.open();
        FakeModel model = new FakeModel();
        Distiller distiller = new Distiller(
                model, store, embedding.gateway(), null, settings);
        return new Fixture(model, store, embedding, distiller);
    }

    private static Episode episode(String input, String reply) {
        return new Episode("session", input, reply);
    }

    private static double[] vector(double... values) {
        return values;
    }

    private static float[] storedVector(float... values) {
        return values;
    }

    private record Fixture(
            FakeModel model,
            MemoryStore store,
            TestEmbeddingGatewayFactory.Fixture embedding,
            Distiller distiller) implements AutoCloseable {
        @Override
        public void close() {
            store.close();
            embedding.close();
        }
    }

    private static final class FakeModel extends ChatModelBase {
        private final Deque<Flux<ChatResponse>> responses = new ArrayDeque<>();
        private int calls;
        private List<Msg> lastMessages = List.of();

        void respond(String text) {
            responses.add(Flux.just(response(List.of(text(text)))));
        }

        void respondWithNullContentThen(String text) {
            List<ChatResponse> values = new ArrayList<>();
            values.add(response(null));
            values.add(response(List.of(text(text))));
            responses.add(Flux.fromIterable(values));
        }

        void empty() {
            responses.add(Flux.empty());
        }

        void fail(Throwable failure) {
            responses.add(Flux.error(failure));
        }

        @Override
        public String getModelName() {
            return "distiller-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {
            calls++;
            lastMessages = List.copyOf(messages);
            return responses.isEmpty() ? Flux.empty() : responses.removeFirst();
        }

        private static ChatResponse response(List<ContentBlock> content) {
            return ChatResponse.builder().content(content).build();
        }

        private static TextBlock text(String value) {
            return TextBlock.builder().text(value).build();
        }
    }
}
