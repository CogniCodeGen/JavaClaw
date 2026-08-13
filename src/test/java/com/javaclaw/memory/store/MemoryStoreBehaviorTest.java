package com.javaclaw.memory.store;

import com.javaclaw.memory.model.CorrectionRecord;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.model.KnowledgeChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStoreBehaviorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void lifecycleFactsCorrectionsAndPendingPromotionKeepStoreInvariants() {
        MemoryStore store = new MemoryStore(temporaryDirectory.resolve("facts"), 4, "behavior");
        assertFalse(store.isOpen());
        assertNull(store.root());
        assertEquals("behavior", store.label());
        assertTrue(store.searchFacts(vector(1, 0, 0, 0), 1, 0).isEmpty());
        assertThrows(IllegalStateException.class,
                () -> store.addFact(fact("closed"), "test"));
        store.close();

        store.open();
        store.open();
        assertTrue(store.isOpen());
        assertNotNull(store.root());
        try {
            Fact first = fact("first");
            store.addFact(first, "test");
            assertNotNull(first.id);
            assertTrue(first.createdAt > 0);
            Fact preidentified = fact("second");
            preidentified.id = "fixed-id";
            preidentified.createdAt = 7;
            store.addFact(preidentified, "test");
            assertEquals("fixed-id", preidentified.id);
            assertEquals(7, preidentified.createdAt);

            assertTrue(store.searchFacts(null, 1, 0).isEmpty());
            assertTrue(store.searchFacts(vector(1, 0, 0, 0), 0, 0).isEmpty());
            assertEquals(2, store.searchFacts(vector(1, 0, 0, 0), 5, 0.9).size());
            assertTrue(store.searchFacts(vector(0, 1, 0, 0), 5, 0.9).isEmpty());

            store.updateFact(first, value -> value.text = "updated", "user");
            store.mergeFact(first, "distiller", "duplicate");
            assertEquals("updated", first.text);
            assertEquals(1, first.mergeCount);
            store.supersedeFact(first, "distiller", "replacement");
            assertTrue(first.superseded);
            store.restoreFact(first, "user");
            assertFalse(first.superseded);
            store.contestFact(first, "user", "needs review");
            assertTrue(first.contested);
            store.restoreFact(first, "user");
            assertFalse(first.contested);

            CorrectionRecord correction = correction("错误结论", "正确结论");
            store.addCorrection(correction, "user");
            assertNotNull(correction.id);
            store.updateCorrection(correction,
                    value -> value.sourceInput = "updated correction", "user");
            assertEquals("updated correction", store.allCorrections().getFirst().sourceInput);

            int beforeBlocked = store.allFacts().size();
            Fact blocked = new Fact("test", "错误结论", vector(1, 0, 0, 0));
            blocked.sourceKind = "DISTILLED";
            store.addFact(blocked, "distiller");
            assertEquals(beforeBlocked, store.allFacts().size());

            Fact pendingBlocked = new Fact("test", "错误结论", null);
            store.addPendingFact(pendingBlocked, "user");
            store.updatePendingFact(pendingBlocked,
                    value -> value.sourceKind = "DISTILLED", "user");
            assertFalse(store.promotePendingFact(
                    pendingBlocked, vector(1, 0, 0, 0), "system"));
            assertFalse(store.allPendingFacts().contains(pendingBlocked));

            store.removeCorrection(correction, "user");
            assertTrue(store.allCorrections().isEmpty());

            Fact pending = new Fact("pending", "offline", null);
            pending.id = "pending-id";
            pending.createdAt = 8;
            store.addPendingFact(pending, "user");
            assertTrue(pending.pending);
            assertNull(pending.embedding);
            store.updatePendingFact(pending, value -> value.pinned = true, "user");
            assertTrue(pending.pinned);
            assertTrue(store.promotePendingFact(pending, vector(0, 1, 0, 0), "system"));
            assertFalse(pending.pending);
            assertFalse(store.promotePendingFact(pending, vector(0, 1, 0, 0), "system"));

            Fact removablePending = new Fact("pending", "remove", null);
            store.addPendingFact(removablePending, "user");
            store.removePendingFact(removablePending, "user");
            assertFalse(store.allPendingFacts().contains(removablePending));

            store.removeFact(preidentified, "user");
            assertFalse(store.allFacts().contains(preidentified));
            assertTrue(store.recentChangeLog(2).size() <= 2);
        } finally {
            store.close();
        }
        assertFalse(store.isOpen());
        assertThrows(IllegalStateException.class,
                () -> store.setPersona("closed", "test"));
    }

    @Test
    void supersededFactsDoNotHideLaterValidMatchesWhenSearchWindowExpands() {
        try (MemoryStore store = open("expanded-search")) {
            List<Fact> values = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                Fact value = fact("fact-" + index);
                store.addFact(value, "test");
                values.add(value);
            }
            for (int index = 0; index < 10; index++) {
                store.supersedeFact(values.get(index), "test", "newer");
            }

            List<MemoryStore.Scored<Fact>> found = store.searchFacts(
                    vector(1, 0, 0, 0), 2, 0.9);
            assertEquals(2, found.size());
            assertTrue(found.stream().noneMatch(hit -> hit.entity().superseded));
        }
    }

    @Test
    void episodesEntitiesKnowledgeCheckpointsPersonaAndAuditRoundTrip() {
        try (MemoryStore store = open("other-models")) {
            Episode old = new Episode("session", "old", "answer");
            old.timestamp = 1;
            old.embedding = vector(1, 0, 0, 0);
            store.addEpisode(old, "test");
            Episode recent = new Episode("session", "recent", "answer");
            recent.embedding = vector(1, 0, 0, 0);
            store.addEpisode(recent, "test");
            assertNotNull(recent.id);
            assertTrue(recent.timestamp > 1);
            assertTrue(store.searchEpisodes(null, 1, 0).isEmpty());
            assertTrue(store.searchEpisodes(vector(1, 0, 0, 0), 0, 0).isEmpty());
            assertEquals(2, store.searchEpisodes(vector(1, 0, 0, 0), 5, 0.9).size());

            Episode pending = new Episode("session", "pending", "answer");
            store.addPendingEpisode(pending, "test");
            assertTrue(pending.pending);
            assertEquals(2, store.episodesSince(0, 2).size());
            assertTrue(store.promotePendingEpisode(
                    pending, vector(0, 1, 0, 0), "system"));
            assertFalse(store.promotePendingEpisode(
                    pending, vector(0, 1, 0, 0), "system"));
            Episode removedPending = new Episode("session", "remove", "answer");
            removedPending.id = "known";
            removedPending.timestamp = 9;
            store.addPendingEpisode(removedPending, "test");
            store.removePendingEpisode(removedPending, "test");
            assertTrue(store.allPendingEpisodes().isEmpty());
            assertEquals(3, store.allEpisodes().size());

            assertEquals(0, store.lastHabitReviewAt());
            store.markHabitReview(42, "system", "reviewed");
            assertEquals(42, store.lastHabitReviewAt());

            assertNull(store.getOrCreateEntity(null, null, "test"));
            assertNull(store.getOrCreateEntity(" ", null, "test"));
            var entity = store.getOrCreateEntity(" Java ", null, "test");
            assertEquals("topic", entity.type);
            assertSame(entity, store.getOrCreateEntity("java", "language", "test"));
            assertEquals("language",
                    store.getOrCreateEntity("Kotlin", "language", "test").type);
            assertEquals(2, store.allEntities().size());

            KnowledgeChunk first = new KnowledgeChunk(
                    "guide.md", "WORKSPACE", "first", vector(1, 0, 0, 0));
            KnowledgeChunk second = new KnowledgeChunk(
                    "guide.md", "WORKSPACE", "second", vector(0, 1, 0, 0));
            second.id = "known-chunk";
            store.addKnowledgeChunk(first, "test");
            store.addKnowledgeChunk(second, "test");
            assertNotNull(first.id);
            assertEquals("known-chunk", second.id);
            assertTrue(store.searchKnowledge(null, 1, 0).isEmpty());
            assertEquals(1, store.searchKnowledge(vector(1, 0, 0, 0), 1, 0.9).size());
            store.updateKnowledgeChunk(first, value -> value.content = "updated", "test");
            assertEquals("updated", first.content);
            assertEquals(0, store.removeKnowledgeByDoc("missing.md", "test"));
            assertEquals(2, store.removeKnowledgeByDoc("guide.md", "test"));
            store.addKnowledgeChunk(new KnowledgeChunk(
                    "other.md", "GLOBAL", "content", vector(1, 0, 0, 0)), "test");
            store.clearKnowledge("test");
            assertTrue(store.allKnowledge().isEmpty());

            assertNull(store.getPersona());
            store.setPersona("first persona", "user");
            assertEquals("first persona", store.getPersona().content);
            store.setPersona("second persona", "user");
            assertEquals("second persona", store.getPersona().content);
            store.updatePersona(value -> {
                value.structured = true;
                value.identity = "assistant";
            }, "user");
            assertTrue(store.getPersona().structured);
            store.appendChangeLog("TEST", "Value", "id", "test", "detail");
            assertFalse(store.recentChangeLog(Integer.MAX_VALUE).isEmpty());
        }
    }

    private MemoryStore open(String name) {
        MemoryStore store = new MemoryStore(temporaryDirectory.resolve(name), 4, name);
        store.open();
        return store;
    }

    private static Fact fact(String text) {
        return new Fact("test", text, vector(1, 0, 0, 0));
    }

    private static CorrectionRecord correction(String wrong, String correct) {
        CorrectionRecord value = new CorrectionRecord();
        value.type = CorrectionRecord.Type.FACT_REPLACEMENT;
        value.scope = CorrectionRecord.Scope.USER;
        value.status = CorrectionRecord.Status.ACTIVE;
        value.wrongClaim = wrong;
        value.correctClaim = correct;
        value.sourceInput = "不是" + wrong + "，而是" + correct;
        return value;
    }

    private static float[] vector(float... values) {
        return values;
    }
}
