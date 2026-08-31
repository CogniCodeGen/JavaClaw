package com.javaclaw.server.persistence;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.knowledge.KnowledgeRepository;
import com.javaclaw.agent.knowledge.KnowledgeService;
import com.javaclaw.agent.model.EmbeddingGateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2KnowledgeRepositoryTest {
    @TempDir
    Path temporary;

    @Test
    void persistsMemoryIndexesAttachmentAndReplaysIdempotently() throws Exception {
        try (H2Persistence store = new H2Persistence(temporary.resolve("data-v4"))) {
            var workspace = store.workspaces().create("Knowledge", temporary.resolve("workspace"), "wsp");
            var attachment = store.attachments()
                    .put(
                            new ByteArrayInputStream("JavaClaw agents use a unified Thread Turn Item runtime."
                                    .getBytes(StandardCharsets.UTF_8)),
                            "text/plain");
            H2KnowledgeRepository repository = new H2KnowledgeRepository(store.database());
            KnowledgeService service = new KnowledgeService(
                    repository,
                    store.attachments(),
                    null,
                    new com.javaclaw.agent.knowledge.DocumentExtractor()::extract,
                    null,
                    null);

            var memory = service.putMemory(
                    "memory_1", workspace.id().value(), "FACT", "The project is JavaClaw 4.0", 0, "memory-put");
            assertEquals(
                    memory,
                    service.putMemory(
                            "memory_1",
                            workspace.id().value(),
                            "FACT",
                            "The project is JavaClaw 4.0",
                            0,
                            "memory-put"));
            assertThrows(
                    IllegalStateException.class,
                    () -> service.putMemory("memory_1", workspace.id().value(), "FACT", "different", 0, "memory-put"));

            var source = service.importAttachment(
                    workspace.id().value(), attachment.sha256(), "architecture.txt", "text/plain", "source-import");
            assertEquals(
                    source,
                    service.importAttachment(
                            workspace.id().value(),
                            attachment.sha256(),
                            "architecture.txt",
                            "text/plain",
                            "source-import"));
            assertEquals("READY_KEYWORD_ONLY", source.status());
            var stats = service.sourceStats(workspace.id().value()).getFirst();
            assertEquals(source.id(), stats.sourceId());
            assertEquals(source.revision(), stats.generation());
            assertTrue(stats.chunkCount() > 0);
            assertEquals("KEYWORD", stats.retrievalMode());
            assertTrue(stats.failureSummary().isBlank());
            assertTrue(stats.indexedAt() != null);
            assertFalse(service.search(workspace.id().value(), "unified runtime", 10)
                    .isEmpty());
            assertEquals(2, source.revision());
            assertEquals(1, service.sourceHistory(source.id()).size());
            assertTrue(service.sourceContent(source.id(), 2).contains("unified"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.replaceIndex(
                            source.id(),
                            "bad rebuild",
                            "c".repeat(64),
                            "d".repeat(64),
                            List.of(new KnowledgeRepository.IndexedChunk(2, "invalid ordinal", null, null, null, null)),
                            "READY_KEYWORD_ONLY",
                            2,
                            "failed-generation"));
            assertEquals(1, service.sourceHistory(source.id()).size());
            assertFalse(service.search(workspace.id().value(), "unified", 10).isEmpty());

            var skill = repository.putSkill(
                    "skill_review", "Review", "1.0.0", "{\"instructions\":\"review carefully\"}", true, 0, "skill-put");
            assertTrue(skill.enabled());
            assertEquals(1, repository.listSkills().size());
            assertTrue(repository.setSkillEnabled(skill.id(), false, skill.revision(), "skill-disable"));
            assertFalse(repository.findSkill(skill.id()).orElseThrow().enabled());
        }
    }

    @Test
    void ranksMatchingEmbeddingsAndChecksRevision() throws Exception {
        try (H2Persistence store = new H2Persistence(temporary.resolve("data-v4-vector"))) {
            var workspace = store.workspaces().create("Vector", temporary.resolve("vector-workspace"), "v-wsp");
            var attachment = store.attachments().put(new ByteArrayInputStream("placeholder".getBytes()), "text/plain");
            H2KnowledgeRepository repository = new H2KnowledgeRepository(store.database());
            var source = repository.createSource(
                    workspace.id().value(), attachment.sha256(), "vector.txt", "text/plain", "vector-source");
            String fingerprint = "a".repeat(64);
            var indexed = repository.replaceIndex(
                    source.id(),
                    "alpha beta\ngamma delta",
                    "b".repeat(64),
                    "c".repeat(64),
                    List.of(
                            new KnowledgeRepository.IndexedChunk(
                                    0, "alpha beta", new float[] {1, 0}, "openai", "embed", fingerprint),
                            new KnowledgeRepository.IndexedChunk(
                                    1, "gamma delta", new float[] {0, 1}, "openai", "embed", fingerprint)),
                    "READY",
                    source.revision(),
                    "vector-reindex");
            var hits = repository.search(workspace.id().value(), "unknown", new float[] {1, 0}, fingerprint, 10);
            assertEquals("alpha beta", hits.getFirst().content());
            assertThrows(
                    IllegalStateException.class,
                    () -> repository.deleteSource(source.id(), source.revision(), "bad-delete"));
            assertTrue(repository.deleteSource(source.id(), indexed.revision(), "delete"));
        }
    }

    @Test
    void readsEmbeddingModelAgainAfterProviderConfigurationReload() throws Exception {
        try (H2Persistence store = new H2Persistence(temporary.resolve("data-v4-reload"))) {
            var workspace = store.workspaces().create("Reload", temporary.resolve("reload-workspace"), "reload-wsp");
            var attachment = store.attachments()
                    .put(new ByteArrayInputStream("JavaClaw dynamic embedding model".getBytes()), "text/plain");
            var selectedModel = new AtomicReference<>("embedding-a");
            var invocations = new ArrayList<String>();
            EmbeddingGateway embeddings = (provider, model, inputs) -> {
                invocations.add(provider + "/" + model);
                return new EmbeddingGateway.EmbeddingResult(
                        inputs.stream().map(ignored -> new float[] {1, 0}).toList(), Map.of());
            };
            KnowledgeService service = KnowledgeService.withReloadableEmbeddingModel(
                    new H2KnowledgeRepository(store.database()),
                    store.attachments(),
                    embeddings,
                    new com.javaclaw.agent.knowledge.DocumentExtractor()::extract,
                    "openai",
                    selectedModel::get);

            var source = service.importAttachment(
                    workspace.id().value(), attachment.sha256(), "reload.txt", "text/plain", "reload-source");
            assertEquals("READY", source.status());
            selectedModel.set("embedding-b");
            var rebuilt = service.reindex(source.id(), source.revision());
            assertEquals("READY", rebuilt.status());
            service.search(workspace.id().value(), "dynamic", 10);

            assertEquals(List.of("openai/embedding-a", "openai/embedding-b", "openai/embedding-b"), invocations);
        }
    }
}
