package com.javaclaw.agent.automation;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchedulePreviewsTest {
    @Test
    void returnsRequestedFutureTimesInTheSelectedZoneWithoutSavingState() {
        List<Instant> values =
                SchedulePreviews.calculate("0 0 9 * * ?", "Asia/Shanghai", 5, Instant.parse("2026-08-30T00:00:00Z"));

        assertEquals(
                List.of(
                        Instant.parse("2026-08-30T01:00:00Z"),
                        Instant.parse("2026-08-31T01:00:00Z"),
                        Instant.parse("2026-09-01T01:00:00Z"),
                        Instant.parse("2026-09-02T01:00:00Z"),
                        Instant.parse("2026-09-03T01:00:00Z")),
                values);
    }

    @Test
    void rejectsInvalidCronZoneAndUnsafeCounts() {
        Instant after = Instant.parse("2026-08-30T00:00:00Z");

        assertThrows(IllegalArgumentException.class, () -> SchedulePreviews.calculate("bad", "UTC", 5, after));
        assertThrows(
                java.time.DateTimeException.class,
                () -> SchedulePreviews.calculate("0 0 9 * * ?", "Mars/Base", 5, after));
        assertThrows(IllegalArgumentException.class, () -> SchedulePreviews.calculate("0 0 9 * * ?", "UTC", 0, after));
        assertThrows(IllegalArgumentException.class, () -> SchedulePreviews.calculate("0 0 9 * * ?", "UTC", 21, after));
    }
}
