package com.javaclaw.framework.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.core.ThreadProjectionRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Restartable append-only rollout and memory outbox consumer. */
public final class ThreadRolloutProjector {
    private static final Logger log = LoggerFactory.getLogger(ThreadRolloutProjector.class);
    private final JdbcTemplate jdbc;
    private final JdbcThreadStore threads;
    private final ObjectMapper json;
    private final Path directory;
    private final ThreadProjectionRegistry projections;
    public ThreadRolloutProjector(JdbcTemplate jdbc, JdbcThreadStore threads, ObjectMapper json,
                                  Path directory, ThreadProjectionRegistry projections) {
        this.jdbc = jdbc; this.threads = threads; this.json = json;
        this.directory = directory.toAbsolutePath().normalize(); this.projections = projections;
    }
    public synchronized void drain() {
        List<RunScope> scopes = jdbc.query("SELECT DISTINCT workspace_id,user_id,thread_id FROM agent_thread_outbox "
                        + "WHERE published_at IS NULL FETCH FIRST 100 ROWS ONLY",
                (row, index) -> new RunScope(row.getString(1), row.getString(2), row.getString(3)));
        for (RunScope scope : scopes) {
            try { project(scope); }
            catch (RuntimeException | IOException failure) {
                log.warn("Thread projection remains pending for {}: {}", scope, failure.toString());
            }
        }
    }
    public synchronized void project(RunScope scope) throws IOException {
        ThreadSnapshot thread = threads.find(scope).orElse(null);
        if (thread == null || thread.status() == ThreadStatus.DELETED || thread.status() == ThreadStatus.DELETING
                || thread.status() == ThreadStatus.FORKING) return;
        List<ThreadEvent> history = threads.events(scope, 0);
        appendRollout(scope, history);
        java.util.Set<Long> pending = new java.util.HashSet<>(jdbc.queryForList("SELECT event_sequence FROM agent_thread_outbox WHERE workspace_id=? "
                        + "AND user_id=? AND thread_id=? AND generation=? AND published_at IS NULL ORDER BY event_sequence",
                Long.class, scope.workspaceId(), scope.userId(), scope.sessionId(), thread.generation()));
        java.util.Map<String, RunRequest> requests = new java.util.HashMap<>();
        java.util.Map<String, com.fasterxml.jackson.databind.node.ArrayNode> traces = new java.util.HashMap<>();
        for (ThreadEvent event : history) {
            if (event.payload().has("request") && event.turnId() != null) {
                try { requests.put(event.turnId().value(), json.treeToValue(event.payload().get("request"), RunRequest.class)); }
                catch (Exception failure) { throw new IllegalStateException("invalid persisted turn request", failure); }
            }
            if (event.turnId() != null && event.type().startsWith("core.tool."))
                traces.computeIfAbsent(event.turnId().value(), ignored -> json.createArrayNode()).add(event.payload().path("event"));
            if (!pending.contains(event.sequence())) continue;
            if (java.util.Set.of("turn/completed", "turn/failed", "turn/cancelled").contains(event.type()) && event.turnId() != null) {
                RunRequest request = requests.get(event.turnId().value());
                if (request != null && !request.source().kind().equals("maintenance")) {
                    var trace = traces.get(event.turnId().value());
                    if (trace != null) request = request.withAttribute("framework.toolTrace", trace);
                    // A fork owns its copy even though its immutable origin IDs remain the same.
                    request = new RunRequest(request.agent(), request.profile(), request.source(), scope, request.inputs(),
                            request.linkage(), request.permissionCeiling(), request.budget(), request.idempotencyKey(), request.attributes());
                    if (!projections.project(request, event)) continue;
                }
            }
            jdbc.update("UPDATE agent_thread_outbox SET published_at=? WHERE workspace_id=? AND user_id=? AND thread_id=? "
                            + "AND event_sequence=? AND generation=?", System.currentTimeMillis(), scope.workspaceId(), scope.userId(),
                    scope.sessionId(), event.sequence(), thread.generation());
        }
    }
    private void appendRollout(RunScope scope, List<ThreadEvent> history) throws IOException {
        Path path = path(scope); Files.createDirectories(path.getParent());
        long last = 0;
        if (Files.exists(path)) {
            try (var lines = Files.lines(path)) {
                for (String line : (Iterable<String>) lines::iterator) {
                    long sequence = json.readTree(line).path("sequence").asLong();
                    if (sequence != last + 1) throw new IOException("rollout sequence gap");
                    last = sequence;
                }
            } catch (Exception damaged) {
                // The journal is authoritative. Rebuild a torn trailing write before acknowledging it.
                Files.delete(path); last = 0;
            }
        }
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            for (ThreadEvent event : history) {
                if (event.sequence() <= last) continue;
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(json.writeValueAsString(event) + "\n");
                while (bytes.hasRemaining()) file.write(bytes);
            }
            file.force(true);
        }
    }
    public synchronized void delete(RunScope scope) {
        try { Files.deleteIfExists(path(scope)); }
        catch (IOException failure) { throw new IllegalStateException("cannot delete rollout", failure); }
    }
    public Path path(RunScope scope) {
        return directory.resolve(key(scope.workspaceId())).resolve(key(scope.userId())).resolve(key(scope.sessionId()) + ".jsonl");
    }
    private static String key(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
