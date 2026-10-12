package com.javaclaw.memory.store;

import com.javaclaw.memory.graph.MemoryGraphSnapshot;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.model.KnowledgeChunk;
import com.javaclaw.memory.model.MemoryContextBody;
import com.javaclaw.memory.model.MemoryContextIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStoreDeferredContextIndexTest {
    @TempDir Path temporaryDirectory;

    @Test
    void restoredCurrentSnapshotKeepsIndexedMetadata() {
        Episode episode = new Episode("thread", "request", "reply");
        episode.turnId = "turn";
        episode.embedding = new float[]{1, 0, 0, 0};
        MemoryContextIndex.refresh(episode);
        Fact fact = new Fact("preference", "preference", new float[]{1, 0, 0, 0});
        fact.id = "fact";
        MemoryContextIndex.refresh(fact);
        MemoryGraphSnapshot snapshot = new MemoryGraphSnapshot(1,
                List.of(fact), List.of(), List.of(episode), List.of(),
                List.of(), List.of(), null);

        try (MemoryStore store = new MemoryStore(
                temporaryDirectory.resolve("current-snapshot"), 4, "current-snapshot")) {
            store.open();
            store.restoreSnapshot(snapshot);

            Fact restoredFact = store.allFacts().getFirst();
            Episode restoredEpisode = store.allEpisodes().getFirst();
            assertEquals("preference", restoredFact.deferredSearchText);
            assertEquals("request\nreply", restoredEpisode.deferredSearchText);
            assertEquals(MemoryContextBody.digest(MemoryContextBody.fact(restoredFact)),
                    restoredFact.deferredContextDigest);
            assertEquals(MemoryContextBody.digest(MemoryContextBody.episode(restoredEpisode)),
                    restoredEpisode.deferredContextDigest);
        }
    }

    @Test
    void boundedSearchMetadataIsPersistedAcrossReopen() {
        Path directory = temporaryDirectory.resolve("deferred-index");
        String episodeId;
        String factId;
        try (MemoryStore store = new MemoryStore(directory, 4, "deferred-index")) {
            store.open();
            Episode episode = new Episode("thread", "request", "match " + "x".repeat(20_000));
            store.addPendingEpisode(episode, "test");
            Fact fact = new Fact("preference", "prefers blue", new float[]{1, 0, 0, 0});
            store.addFact(fact, "test");
            episodeId = episode.id;
            factId = fact.id;
            assertTrue(episode.deferredSearchText.length() <= 769);
            assertTrue(fact.deferredSearchText.length() <= 768);
            assertNotNull(episode.deferredContextDigest);
            assertNotNull(fact.deferredContextDigest);
        }

        try (MemoryStore reopened = new MemoryStore(directory, 4, "deferred-index")) {
            reopened.open();
            Episode episode = reopened.allPendingEpisodes().stream()
                    .filter(value -> episodeId.equals(value.id)).findFirst().orElseThrow();
            Fact fact = reopened.allFacts().stream()
                    .filter(value -> factId.equals(value.id)).findFirst().orElseThrow();
            assertTrue(episode.deferredSearchText.startsWith("request\nmatch"));
            assertEquals("prefers blue", fact.deferredSummary);
            reopened.markDistilled(episode);
            reopened.mergeFact(fact, "test", "duplicate");
        }

        try (MemoryStore reopened = new MemoryStore(directory, 4, "deferred-index")) {
            reopened.open();
            Episode episode = reopened.allPendingEpisodes().stream()
                    .filter(value -> episodeId.equals(value.id)).findFirst().orElseThrow();
            Fact fact = reopened.allFacts().stream()
                    .filter(value -> factId.equals(value.id)).findFirst().orElseThrow();
            assertNotNull(episode.deferredContextDigest);
            assertFalse(episode.deferredSearchText.isBlank());
            assertNotNull(fact.deferredContextDigest);
            assertEquals("prefers blue", fact.deferredSummary);
        }
    }

    @Test
    void knowledgeVersionsUpdateAndPersistAtOpen() {
        Path directory = temporaryDirectory.resolve("knowledge-index");
        String originalVersion;
        try (MemoryStore store = new MemoryStore(directory, 4, "knowledge-index")) {
            store.open();
            KnowledgeChunk current = new KnowledgeChunk("doc", "WORKSPACE",
                    "front " + "x".repeat(2_000), new float[]{1, 0, 0, 0});
            store.addKnowledgeChunk(current, "test");
            originalVersion = current.deferredContextDigest;
            assertTrue(current.deferredSearchText.length() <= 768);
            store.updateKnowledgeChunk(current, value -> value.content = "revised content", "test");
            assertFalse(originalVersion.equals(current.deferredContextDigest));
            assertEquals("revised content", current.deferredSummary);

        }
        try (MemoryStore reopened = new MemoryStore(directory, 4, "knowledge-index")) {
            reopened.open();
            KnowledgeChunk current = reopened.allKnowledge().stream()
                    .filter(value -> "doc".equals(value.docName)).findFirst().orElseThrow();
            assertEquals("revised content", current.deferredSummary);
            assertNotNull(current.deferredContextDigest);
        }
    }
}
