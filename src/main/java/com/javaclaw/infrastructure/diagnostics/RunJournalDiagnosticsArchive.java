package com.javaclaw.infrastructure.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.diagnostics.DiagnosticsArchivePort;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.diagnostics.TraceExporter;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.ThreadStore;
import com.javaclaw.util.SensitiveDataRedactor;
import org.springframework.dao.DataAccessException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/** Read-only, scoped diagnostic projection of the authoritative Framework journal. */
public final class RunJournalDiagnosticsArchive implements DiagnosticsArchivePort {
    private static final String USER = "local-user";
    private static final int PAYLOAD_SUMMARY_LIMIT = 2048;
    private static final Comparator<Row> ORDER = Comparator.comparingLong(Row::timestamp)
            .thenComparing(Row::runId).thenComparingLong(Row::sequence);

    private final RunStore runs;
    private final ThreadStore threads;
    private final WorkspaceManager workspaces;
    private final ObjectMapper json;
    private final TraceExporter exporter;

    public RunJournalDiagnosticsArchive(RunStore runs, ThreadStore threads,
                                        WorkspaceManager workspaces, ObjectMapper json,
                                        TraceExporter exporter) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.json = Objects.requireNonNull(json, "json");
        this.exporter = Objects.requireNonNull(exporter, "exporter");
    }

    @Override
    public List<String> query(String keyword, String agent, String eventType,
                              long sinceEpochMillis, int limit) throws IOException {
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("诊断查询条数必须在 1 到 10000 之间");
        String workspace = workspaces.getCurrentWorkspaceId();
        PriorityQueue<Row> latest = new PriorityQueue<>(ORDER);
        visit(workspace, (scope, request, event) -> {
            if (event.timestamp().toEpochMilli() < sinceEpochMillis) return;
            if (present(agent) && !agent.equals(request.agent().id())) return;
            if (present(eventType) && !matches(eventType, event.type())) return;
            String payload = SensitiveDataRedactor.redactText(event.payload().toString());
            Row row = project(scope, request, event, payload, false);
            // Never search an unredacted payload, even when it is not displayed.
            if (present(keyword) && !row.line().contains(keyword)) return;
            latest.add(project(scope, request, event, payload, true));
            if (latest.size() > limit) latest.remove();
        });
        checkWorkspace(workspace);
        List<Row> ordered = latest.stream().sorted(ORDER.reversed()).toList();
        Set<RunScope> readable = new LinkedHashSet<>();
        for (RunScope scope : ordered.stream().map(Row::scope).distinct().toList()) {
            checkWorkspace(workspace);
            if (runs.readable(scope)) readable.add(scope);
        }
        List<String> result = new ArrayList<>(ordered.size());
        for (Row row : ordered) {
            checkWorkspace(workspace);
            if (readable.contains(row.scope())) result.add(row.line());
        }
        return List.copyOf(result);
    }

    @Override
    public long export(Path target) throws IOException {
        String workspace = workspaces.getCurrentWorkspaceId();
        checkWorkspace(workspace);
        Path destination = target.toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        Path staged = Files.createTempFile(destination.getParent(), ".javaclaw-diagnostics-", ".zip");
        Set<RunScope> included = new LinkedHashSet<>();
        try {
            long bytes = exporter.exportTo(staged, output -> visit(workspace, (scope, request, event) -> {
                Row row = project(scope, request, event,
                        SensitiveDataRedactor.redactText(event.payload().toString()), false);
                checkWorkspace(workspace);
                output.write(row.line().getBytes(StandardCharsets.UTF_8));
                output.write('\n');
                included.add(scope);
            }));
            checkWorkspace(workspace);
            for (RunScope scope : included) checkScope(workspace, scope);
            try {
                Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return bytes;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private void visit(String workspace, EventVisitor visitor) throws IOException {
        try {
            checkWorkspace(workspace);
            for (var thread : threads.list(workspace, USER, true)) {
                RunScope scope = thread.scope();
                if (!workspace.equals(scope.workspaceId()) || !USER.equals(scope.userId())
                        || !runs.readable(scope)) continue;
                checkWorkspace(workspace);
                for (var run : runs.scopeRuns(scope)) {
                    if (!scope.equals(run.request().scope())) continue;
                    checkScope(workspace, scope);
                    var events = runs.eventsAfter(run.snapshot().id(), 0);
                    checkScope(workspace, scope);
                    for (RunEventEnvelope event : events) {
                        checkWorkspace(workspace);
                        if (event.runId().equals(run.snapshot().id().value()))
                            visitor.accept(scope, run.request(), event);
                    }
                    checkScope(workspace, scope);
                }
            }
            checkWorkspace(workspace);
        } catch (DataAccessException failure) {
            throw new IOException("读取持久诊断轨迹失败", failure);
        }
    }

    private Row project(RunScope scope, RunRequest request, RunEventEnvelope event,
                        String payload, boolean summarize) throws IOException {
        var row = json.createObjectNode();
        row.put("ts", event.timestamp().toEpochMilli());
        row.put("agent", request.agent().id());
        row.put("profile", request.profile().id());
        row.put("event", category(event.type()));
        row.put("type", event.type());
        row.put("runId", event.runId());
        row.put("sequence", event.sequence());
        row.put("producer", event.producer());
        row.put("schemaVersion", event.schemaVersion());
        if (summarize && payload.codePointCount(0, payload.length()) > PAYLOAD_SUMMARY_LIMIT) {
            row.put("payload", payload.substring(0, payload.offsetByCodePoints(0, PAYLOAD_SUMMARY_LIMIT)));
            row.put("payloadTruncated", true);
        } else row.put("payload", payload);
        return new Row(scope, event.timestamp().toEpochMilli(), event.runId(),
                event.sequence(), json.writeValueAsString(row));
    }

    private void checkScope(String workspace, RunScope scope) throws IOException {
        checkWorkspace(workspace);
        if (!runs.readable(scope)) throw new IOException("诊断轨迹所属会话已删除，请重新查询或导出");
    }

    private void checkWorkspace(String workspace) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("诊断操作已取消");
        if (!Objects.equals(workspace, workspaces.getCurrentWorkspaceId()))
            throw new IOException("工作区已切换，请在当前工作区重新查询或导出");
    }

    private static boolean matches(String filter, String type) {
        return filter.equals(type) || switch (filter) {
            case "tool_call" -> type.equals("core.tool.started");
            case "tool_result" -> Set.of("core.tool.completed", "core.tool.failed", "core.tool.receipt").contains(type);
            case "model_call" -> type.startsWith("core.model.") || type.startsWith("core.model_task.");
            case "error" -> type.endsWith(".failed") || type.equals("core.tool.arguments_rejected");
            default -> false;
        };
    }

    private static String category(String type) {
        if (matches("error", type)) return "error";
        if (matches("tool_call", type)) return "tool_call";
        if (matches("tool_result", type)) return "tool_result";
        if (matches("model_call", type)) return "model_call";
        return type;
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }

    private record Row(RunScope scope, long timestamp, String runId, long sequence, String line) { }

    @FunctionalInterface
    private interface EventVisitor {
        void accept(RunScope scope, RunRequest request, RunEventEnvelope event) throws IOException;
    }
}
