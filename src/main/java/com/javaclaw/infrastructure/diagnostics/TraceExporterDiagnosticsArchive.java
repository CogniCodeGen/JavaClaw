package com.javaclaw.infrastructure.diagnostics;

import com.javaclaw.application.diagnostics.DiagnosticsArchivePort;
import com.javaclaw.diagnostics.TraceExporter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** 把现有 trace 文件格式适配到诊断应用端口；实例本身无状态且线程安全。 */
public final class TraceExporterDiagnosticsArchive implements DiagnosticsArchivePort {

    private final TraceExporter exporter;

    public TraceExporterDiagnosticsArchive(TraceExporter exporter) {
        this.exporter = java.util.Objects.requireNonNull(exporter, "exporter");
    }

    @Override
    public List<String> query(
            String keyword,
            String agent,
            String eventType,
            long sinceEpochMillis,
            int limit) throws IOException {
        return exporter.grep(keyword, agent, eventType, sinceEpochMillis, limit);
    }

    @Override
    public long export(Path target) throws IOException {
        return exporter.exportTo(target);
    }
}
