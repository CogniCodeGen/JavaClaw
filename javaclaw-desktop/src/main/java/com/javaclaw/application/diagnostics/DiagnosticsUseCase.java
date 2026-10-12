package com.javaclaw.application.diagnostics;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** 无页面状态的诊断用例实现；时间边界只在每次查询开始时计算一次。 */
public final class DiagnosticsUseCase implements DiagnosticsApplicationService {

    private final DiagnosticsArchivePort archive;
    private final Clock clock;

    public DiagnosticsUseCase(DiagnosticsArchivePort archive, Clock clock) {
        this.archive = Objects.requireNonNull(archive, "archive");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<String> query(Query query) throws IOException {
        Objects.requireNonNull(query, "query");
        return List.copyOf(archive.query(
                query.keyword(),
                query.agent(),
                query.eventType(),
                resolveSince(query.range()),
                query.limit()));
    }

    @Override
    public ExportReceipt export(Path target) throws IOException {
        Objects.requireNonNull(target, "target");
        Path normalized = target.toAbsolutePath().normalize();
        return new ExportReceipt(normalized, archive.export(normalized));
    }

    private long resolveSince(TimeRange range) {
        Duration lookback = range.lookback();
        return lookback == null ? 0L : clock.instant().minus(lookback).toEpochMilli();
    }
}
