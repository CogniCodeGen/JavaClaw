package com.javaclaw.memory.curation;

import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.MemoryStats;
import com.javaclaw.memory.store.MemoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HabitReviewerInputTest {
    @Test
    void preservesEntireSelectedEpisodesAndStopsWhenDigestCannotFit() {
        String input = "evidence".repeat(50);
        String digest = HabitReviewer.boundedDigest(List.of(new Episode("s", input, "reply")));
        assertTrue(digest.contains(input), "单轮证据不能按固定前缀截断");

        List<Episode> oversized = IntStream.range(0, 32)
                .mapToObj(index -> new Episode("s", input + index, "reply"))
                .toList();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> HabitReviewer.boundedDigest(oversized));
        assertTrue(failure.getMessage().contains("水位未推进"));
        assertEquals(32, oversized.size());
    }

    @Test
    void longDefaultWindowAdvancesInCompleteBatchesWithoutLosingEqualTimestamps() {
        List<Episode> evidence = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            Episode episode = new Episode("s", "x".repeat(250), "reply");
            episode.turnId = "turn-%02d".formatted(index);
            episode.timestamp = 10;
            evidence.add(episode);
        }
        MemoryStore.HabitReviewProgress firstCursor =
                progress(0, "");
        HabitReviewer.ReviewBatch first = HabitReviewer.selectBatch(evidence, firstCursor, 20, 60);
        assertTrue(first.episodes().size() >= 20);
        assertTrue(first.episodes().size() < 60);
        assertTrue(first.digest().length() <= 12_000);

        for (int index = 60; index < 80; index++) {
            Episode episode = new Episode("s", "x".repeat(250), "reply");
            episode.turnId = "turn-%02d".formatted(index);
            episode.timestamp = 11;
            evidence.add(episode);
        }
        MemoryStore.HabitReviewProgress nextCursor = progress(
                first.cursorTimestamp(), first.cursorEvidenceKey());
        HabitReviewer.ReviewBatch second = HabitReviewer.selectBatch(evidence, nextCursor, 20, 60);
        assertTrue(second.episodes().size() >= 20);
        HashSet<String> reviewed = new HashSet<>();
        first.episodes().forEach(episode -> reviewed.add(episode.evidenceKey()));
        second.episodes().forEach(episode -> reviewed.add(episode.evidenceKey()));
        assertEquals(first.episodes().size() + second.episodes().size(), reviewed.size());
        assertEquals(80, reviewed.size());
    }

    @Test
    void oversizedEvidenceIsPendingWithoutBlockingLaterEpisodes() {
        Episode oversized = new Episode("s", "x".repeat(12_001), "reply");
        oversized.turnId = "oversized";
        oversized.timestamp = 1;
        List<Episode> evidence = new ArrayList<>();
        evidence.add(oversized);
        for (int index = 0; index < 20; index++) {
            Episode normal = new Episode("s", "habit evidence", "reply");
            normal.turnId = "normal-" + index;
            normal.timestamp = index + 2;
            evidence.add(normal);
        }
        HabitReviewer.ReviewBatch batch = HabitReviewer.selectBatch(evidence,
                progress(0, ""), 20, 60);

        assertEquals(List.of(oversized.evidenceKey()), batch.oversizedKeys());
        assertEquals(20, batch.episodes().size());
        assertEquals(21, batch.cursorTimestamp());
    }

    @Test
    void cumulativeLimitEndsBatchWithoutCallingAnyEpisodeOversized() {
        List<Episode> evidence = IntStream.range(0, 40).mapToObj(index -> {
            Episode episode = new Episode("s", "x".repeat(700), "reply");
            episode.turnId = "turn-" + index;
            episode.timestamp = index + 1;
            return episode;
        }).toList();
        HabitReviewer.ReviewBatch first = HabitReviewer.selectBatch(evidence,
                progress(0, ""), 20, 60);

        assertTrue(first.episodes().size() > 0);
        assertTrue(first.episodes().size() < 20);
        assertTrue(first.moreProcessable());
        assertTrue(first.oversizedKeys().isEmpty());
        assertEquals(first.episodes().size(), first.cursorTimestamp());
        assertTrue(first.digest().length() <= 12_000);
    }

    @Test
    void oldObservationsGiveWayToAWholeEpisodeWithoutBeingDiscarded() {
        List<MemoryStats.HabitObservation> old = IntStream.range(0, 64)
                .mapToObj(index -> new MemoryStats.HabitObservation(
                        "尚待验证的旧线索" + "x".repeat(65), "s:old-" + index))
                .toList();
        Episode next = new Episode("s", "新一轮完整证据" + "x".repeat(6_000), "reply");
        next.turnId = "next";
        next.timestamp = 65;
        MemoryStore.HabitReviewProgress progress = new MemoryStore.HabitReviewProgress(
                64, "s:old-63", List.of(), old, 1, true);

        HabitReviewer.ReviewBatch batch = HabitReviewer.selectBatch(List.of(next), progress, 20, 20);

        assertEquals(List.of(next), batch.episodes());
        assertTrue(batch.priorObservationCount() < old.size());
        assertTrue(batch.priorObservationCount() > 0);
        assertTrue(batch.oversizedKeys().isEmpty());
    }

    private static MemoryStore.HabitReviewProgress progress(long timestamp, String evidenceKey) {
        return new MemoryStore.HabitReviewProgress(timestamp, evidenceKey,
                List.of(), List.of(), 0, false);
    }
}
