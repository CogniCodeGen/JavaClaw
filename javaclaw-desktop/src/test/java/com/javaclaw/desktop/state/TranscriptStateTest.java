package com.javaclaw.desktop.state;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.TurnId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TranscriptStateTest {
    private final TurnId turnId = TurnId.random();

    @Test
    void appendRequiresStrictNonOverlappingSequence() {
        TranscriptState first = TranscriptState.empty().append(List.of(item(1)), 1);
        TranscriptState second = first.append(List.of(item(2), item(3)), 3);

        assertEquals(
                List.of(1L, 2L, 3L),
                second.items().stream().map(ItemEnvelope::sequence).toList());
        assertEquals(3, second.nextSequence());
        assertThrows(IllegalArgumentException.class, () -> second.append(List.of(item(3)), 3));
        assertThrows(IllegalArgumentException.class, () -> second.append(List.of(), 2));
    }

    private ItemEnvelope item(long sequence) {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        return new ItemEnvelope(
                ItemId.random(),
                turnId,
                sequence,
                "test",
                "test/item@1",
                "test",
                ItemStatus.COMPLETED,
                new CanonicalPayload("{}"),
                now,
                Optional.of(now));
    }
}
