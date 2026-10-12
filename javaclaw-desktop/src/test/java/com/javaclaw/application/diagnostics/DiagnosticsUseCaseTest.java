package com.javaclaw.application.diagnostics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiagnosticsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");

    @Test
    void resolvesTimeRangeOnceAndNormalizesFilters() throws Exception {
        RecordingArchive archive = new RecordingArchive();
        DiagnosticsUseCase useCase = new DiagnosticsUseCase(
                archive, Clock.fixed(NOW, ZoneOffset.UTC));

        List<String> result = useCase.query(new DiagnosticsApplicationService.Query(
                DiagnosticsApplicationService.TimeRange.LAST_HOUR,
                "  planner  ", "  error ", "  timeout  ", 2000));

        assertEquals(List.of("trace"), result);
        assertEquals("planner", archive.agent);
        assertEquals("error", archive.eventType);
        assertEquals("timeout", archive.keyword);
        assertEquals(NOW.minusSeconds(3600).toEpochMilli(), archive.since);
        assertEquals(2000, archive.limit);
    }

    @Test
    void allRangeUsesZeroAndBlankFiltersBecomeNull() throws Exception {
        RecordingArchive archive = new RecordingArchive();
        DiagnosticsUseCase useCase = new DiagnosticsUseCase(
                archive, Clock.fixed(NOW, ZoneOffset.UTC));

        useCase.query(new DiagnosticsApplicationService.Query(
                DiagnosticsApplicationService.TimeRange.ALL, " ", null, "", 1));

        assertEquals(0L, archive.since);
        assertNull(archive.agent);
        assertNull(archive.eventType);
        assertNull(archive.keyword);
    }

    @Test
    void returnsNormalizedExportReceipt() throws Exception {
        RecordingArchive archive = new RecordingArchive();
        DiagnosticsUseCase useCase = new DiagnosticsUseCase(
                archive, Clock.fixed(NOW, ZoneOffset.UTC));

        DiagnosticsApplicationService.ExportReceipt receipt =
                useCase.export(Path.of("build", "..", "diagnostics.zip"));

        assertEquals(Path.of("diagnostics.zip").toAbsolutePath().normalize(), receipt.target());
        assertEquals(4096L, receipt.bytes());
        assertEquals(receipt.target(), archive.exportTarget);
    }

    @Test
    void rejectsUnboundedOrEmptyQueryLimits() {
        assertThrows(IllegalArgumentException.class, () ->
                new DiagnosticsApplicationService.Query(
                        DiagnosticsApplicationService.TimeRange.ALL,
                        null, null, null, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new DiagnosticsApplicationService.Query(
                        DiagnosticsApplicationService.TimeRange.ALL,
                        null, null, null, 10_001));
    }

    private static final class RecordingArchive implements DiagnosticsArchivePort {
        private String keyword;
        private String agent;
        private String eventType;
        private long since;
        private int limit;
        private Path exportTarget;

        @Override
        public List<String> query(
                String keyword, String agent, String eventType, long since, int limit) {
            this.keyword = keyword;
            this.agent = agent;
            this.eventType = eventType;
            this.since = since;
            this.limit = limit;
            return List.of("trace");
        }

        @Override
        public long export(Path target) throws IOException {
            exportTarget = target;
            return 4096L;
        }
    }
}
