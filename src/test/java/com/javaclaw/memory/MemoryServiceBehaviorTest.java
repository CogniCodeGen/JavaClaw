package com.javaclaw.memory;

import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.memory.correction.CorrectionGuard;
import com.javaclaw.memory.correction.CorrectionTurnContext;
import com.javaclaw.memory.embed.EmbeddingHealthStatus;
import com.javaclaw.memory.embed.TestEmbeddingGatewayFactory;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.model.KnowledgeChunk;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryServiceBehaviorTest {

    private final AtomicInteger stores = new AtomicInteger();
    private Path temporaryDirectory;
    private AnnotationConfigApplicationContext context;
    private AgentConfig settings;

    @BeforeAll
    void createConfiguration(@TempDir Path temporaryDirectory) {
        this.temporaryDirectory = temporaryDirectory;
        context = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data")));
        settings = context.getBean(AgentConfig.class);
    }

    @AfterAll
    void closeConfiguration() {
        context.close();
    }

    @Test
    void unopenedServiceReturnsStableEmptyValuesAndPersonaAssemblyIsPure() throws Exception {
        try (Fixture fixture = fixture((text, timeout) -> vector(1, 0, 0, 0))) {
            MemoryService service = fixture.service;
            Fact fact = new Fact("test", "value", storedVector(1, 0, 0, 0));

            assertEquals("", service.recall("anything"));
            assertFalse(service.prepareCorrectionTurn("x", "y").hasCorrections());
            service.recordCorrectionGuardViolation(null);
            assertTrue(service.corrections().isEmpty());
            service.revokeCorrection(null);
            service.deleteCorrection(null);
            service.restoreFact(null);
            service.restoreFact(fact);
            service.rememberTurn("session", "question", "answer", null);
            assertNull(service.getPersona());
            service.setPersona("persona", "user");
            service.setPersonaStructured("identity", "tone", null, null);
            service.checkpoint("key", "[]");
            assertNull(service.loadCheckpoint("key"));
            service.deleteCheckpoint("key");
            assertTrue(service.recentChangeLog(5).isEmpty());
            assertTrue(service.facts().isEmpty());
            service.deleteFact(fact);
            service.togglePin(fact);
            service.editFact(fact, "new");
            service.addFact("test", "value");
            assertTrue(service.episodes().isEmpty());
            assertEquals(0, service.pendingCount());
            assertEquals(0, service.promotePending(5));
            assertEquals(0, service.promoteAllPending());
            assertTrue(service.entities().isEmpty());
            assertTrue(service.knowledge().isEmpty());
            assertEquals(0, service.deleteKnowledgeDoc("doc"));
            assertEquals(0, service.reindexKnowledgeDoc("doc"));
            assertNull(service.stats());
            assertTrue(service.graph().nodes().isEmpty());
            assertNull(service.store());
            assertEquals("记忆服务未就绪，习惯回顾不可用", service.reviewHabitsNow());
            assertEquals("记忆库未打开", service.probeEmbedding());
            assertNull(service.embeddingError());
            assertNotNull(service.embeddingHealth());

            List<EmbeddingHealthStatus> statuses = new ArrayList<>();
            AutoCloseable subscription = service.onEmbeddingHealthChanged(
                    snapshot -> statuses.add(snapshot.status()));
            assertEquals(List.of(EmbeddingHealthStatus.HEALTHY), statuses);
            subscription.close();
            service.setOnEmbeddingDegraded(message -> { });
            service.close();
            service.close();
        }

        assertEquals("# 人格\n", MemoryService.assemblePersona(null, " ", null, List.of()));
        String assembled = MemoryService.assemblePersona(
                " assistant ", " concise ",
                java.util.Arrays.asList(" reliable ", null, " "),
                java.util.Arrays.asList(" no secrets ", null));
        assertTrue(assembled.contains("## 身份\nassistant"));
        assertTrue(assembled.contains("## 语气\nconcise"));
        assertTrue(assembled.contains("- reliable"));
        assertTrue(assembled.contains("- no secrets"));
    }

    @Test
    void openCrudGraphAndReloadKeepWorkspaceStateScopedToTheActiveStore() throws Exception {
        try (Fixture fixture = fixture((text, timeout) -> text.contains("second")
                ? vector(0, 1, 0, 0) : vector(1, 0, 0, 0))) {
            MemoryService service = fixture.service;
            Path firstDirectory = nextStoreDirectory();
            service.open(firstDirectory);
            service.open(firstDirectory);
            assertNotNull(service.getPersona());
            assertNotNull(service.stats());
            assertNotNull(service.store());

            service.setPersona("plain", "user");
            assertEquals("plain", service.getPersona().content);
            service.setPersonaStructured("helper", "direct",
                    List.of("clarity"), List.of("secrets"));
            assertTrue(service.getPersona().structured);
            assertTrue(service.getPersona().content.contains("clarity"));

            service.checkpoint("session", "[1]");
            assertEquals("[1]", service.loadCheckpoint("session").messagesJson);
            service.deleteCheckpoint("session");
            assertNull(service.loadCheckpoint("session"));

            service.addFact(null, null);
            service.addFact(null, " ");
            service.addFact(null, "first fact");
            Fact first = service.facts().getFirst();
            assertEquals("其它", first.section);
            assertTrue(first.userEdited);
            service.togglePin(first);
            assertTrue(first.pinned);
            service.editFact(first, "second fact");
            assertEquals("second fact", first.text);
            assertTrue(first.userAsserted);
            first.superseded = true;
            first.contested = true;
            service.restoreFact(first);
            assertFalse(first.superseded);
            assertFalse(first.contested);

            Fact pending = new Fact("pending", "pending text", null);
            service.store().addPendingFact(pending, "test");
            service.togglePin(pending);
            assertTrue(pending.pinned);
            service.editFact(pending, "second pending text");
            assertFalse(pending.pending);
            Fact removable = new Fact("pending", "remove pending", null);
            service.store().addPendingFact(removable, "test");
            service.deleteFact(removable);
            assertFalse(service.facts().contains(removable));
            service.deleteFact(first);
            assertFalse(service.facts().contains(first));

            Episode episode = new Episode("session", "question", "answer");
            episode.embedding = storedVector(1, 0, 0, 0);
            service.store().addEpisode(episode, "test");
            Episode pendingEpisode = new Episode("session", "pending question", "answer");
            service.store().addPendingEpisode(pendingEpisode, "test");
            assertEquals(2, service.episodes().size());

            service.store().getOrCreateEntity("Java", "language", "test");
            assertEquals(1, service.entities().size());
            KnowledgeChunk firstChunk = new KnowledgeChunk(
                    "guide.md", "WORKSPACE", "first", storedVector(1, 0, 0, 0));
            KnowledgeChunk secondChunk = new KnowledgeChunk(
                    "other.md", "WORKSPACE", "second", storedVector(1, 0, 0, 0));
            service.store().addKnowledgeChunk(firstChunk, "test");
            service.store().addKnowledgeChunk(secondChunk, "test");
            assertEquals(2, service.knowledge().size());
            assertEquals(1, service.reindexKnowledgeDoc("guide.md"));
            assertEquals(0, service.reindexKnowledgeDoc(null));
            assertEquals(1, service.deleteKnowledgeDoc("guide.md"));
            assertFalse(service.graph().nodes().isEmpty());
            assertFalse(service.recall("second fact").isBlank());
            assertFalse(service.recentChangeLog(5).isEmpty());

            Path secondDirectory = nextStoreDirectory();
            service.reload(secondDirectory);
            assertTrue(service.facts().isEmpty());
            assertNotNull(service.getPersona());
        }
    }

    @Test
    void degradedEmbeddingKeepsPendingContentUntilProbeRestoresTheEndpoint() throws Exception {
        AtomicBoolean available = new AtomicBoolean(false);
        try (Fixture fixture = fixture((text, timeout) -> {
            if (!available.get()) return null;
            return vector(1, 0, 0, 0);
        })) {
            MemoryService service = fixture.service;
            service.open(nextStoreDirectory());
            List<EmbeddingHealthStatus> statuses = new ArrayList<>();
            AutoCloseable subscription = service.onEmbeddingHealthChanged(
                    snapshot -> statuses.add(snapshot.status()));

            service.addFact("offline", "pending fact");
            Fact pending = service.facts().getFirst();
            assertTrue(pending.pending);
            service.togglePin(pending);
            service.editFact(pending, "edited while offline");
            assertTrue(pending.pending);
            assertEquals(1, service.pendingCount());
            assertEquals(0, service.promotePending(0));
            assertEquals(0, service.promotePending(5));
            assertNotNull(service.embeddingError());

            KnowledgeChunk chunk = new KnowledgeChunk(
                    "offline.md", "WORKSPACE", "content", storedVector(0, 1, 0, 0));
            service.store().addKnowledgeChunk(chunk, "test");
            assertEquals(0, service.reindexKnowledgeDoc("offline.md"));

            available.set(true);
            assertNull(service.probeEmbedding());
            assertEquals(1, service.promoteAllPending());
            assertFalse(pending.pending);
            assertEquals(1, service.reindexKnowledgeDoc("offline.md"));
            assertTrue(statuses.contains(EmbeddingHealthStatus.DEGRADED));
            assertEquals(EmbeddingHealthStatus.HEALTHY,
                    service.embeddingHealth().status());
            subscription.close();
        }
    }

    @Test
    void correctionsRememberingAndClosingRespectSecretsAndDurableFirstWrites() throws Exception {
        try (Fixture fixture = fixture((text, timeout) -> vector(1, 0, 0, 0))) {
            MemoryService service = fixture.service;
            service.open(nextStoreDirectory());
            CorrectionTurnContext ordinary = service.prepareCorrectionTurn(
                    "普通问题", "previous answer");
            assertFalse(ordinary.hasCorrections());
            CorrectionTurnContext secret = service.prepareCorrectionTurn(
                    "密码不是 old-secret，而是 new-secret", "previous answer");
            assertFalse(secret.hasCorrections());

            CorrectionTurnContext correction = service.prepareCorrectionTurn(
                    "我的名字不是张三，而是李四", "我误称你为张三");
            assertTrue(correction.hasCorrections());
            assertNotNull(correction.newlyApplied());
            assertFalse(service.corrections().isEmpty());
            service.recordCorrectionGuardViolation(new CorrectionGuard.Violation(
                    correction.newlyApplied(), correction.newlyApplied().wrongClaim));
            service.revokeCorrection(correction.newlyApplied());
            assertEquals(com.javaclaw.memory.model.CorrectionRecord.Status.REVOKED,
                    correction.newlyApplied().status);
            service.deleteCorrection(correction.newlyApplied());
            assertTrue(service.corrections().isEmpty());

            service.rememberTurn("secret", "authorization: Bearer secret-value",
                    "answer", null);
            assertTrue(service.episodes().isEmpty());
            fixture.model.respond("无");
            service.rememberTurn(null, "long enough ordinary question",
                    "long enough ordinary answer", "{}");
            await(() -> service.store().allPendingEpisodes().isEmpty()
                    && !service.store().allEpisodes().isEmpty(), Duration.ofSeconds(3));
            assertEquals(1, service.episodes().size());

            fixture.embedding.tasks().close();
            service.rememberTurn("closed", "another ordinary question",
                    "another ordinary answer", null);
            service.close();
            assertNull(service.store());
            assertEquals("记忆服务未就绪，习惯回顾不可用", service.reviewHabitsNow());
        }
    }

    private Fixture fixture(TestEmbeddingGatewayFactory.Invoker invoker) {
        TestEmbeddingGatewayFactory.Fixture embedding =
                TestEmbeddingGatewayFactory.create(4, invoker);
        FakeModel model = new FakeModel();
        FakeModelFactory modelFactory = new FakeModelFactory(settings, model);
        MemoryService service = new MemoryService(
                modelFactory, null, embedding.gateway(), embedding.tasks(), settings);
        return new Fixture(service, model, modelFactory, embedding);
    }

    private Path nextStoreDirectory() {
        return temporaryDirectory.resolve("service-memory-" + stores.incrementAndGet());
    }

    private static void await(Check condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.satisfied() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.satisfied(), "异步记忆任务未在时限内完成");
    }

    @FunctionalInterface
    private interface Check {
        boolean satisfied();
    }

    private static double[] vector(double... values) {
        return values;
    }

    private static float[] storedVector(float... values) {
        return values;
    }

    private record Fixture(
            MemoryService service,
            FakeModel model,
            FakeModelFactory modelFactory,
            TestEmbeddingGatewayFactory.Fixture embedding) implements AutoCloseable {
        @Override
        public void close() {
            service.close();
            modelFactory.close();
            embedding.close();
        }
    }

    private static final class FakeModelFactory extends ModelFactory {
        private final ChatModelBase model;

        private FakeModelFactory(AgentConfig settings, ChatModelBase model) {
            super(settings);
            this.model = model;
        }

        @Override
        public ChatModelBase createLightChatModel() {
            return model;
        }
    }

    private static final class FakeModel extends ChatModelBase {
        private final Deque<Flux<ChatResponse>> responses = new ArrayDeque<>();

        void respond(String text) {
            responses.add(Flux.just(ChatResponse.builder()
                    .content(List.<ContentBlock>of(
                            TextBlock.builder().text(text).build()))
                    .build()));
        }

        @Override
        public String getModelName() {
            return "memory-service-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {
            return responses.isEmpty() ? Flux.empty() : responses.removeFirst();
        }
    }
}
