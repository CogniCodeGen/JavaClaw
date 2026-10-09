package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.StoredRun;
import com.javaclaw.framework.spi.ThreadStore;
import java.util.*;

/** Audit projection over existing durable tool events and independently persisted host surfaces. */
public final class InteractionJournal implements InteractionHistoryClient {
    private static final String TYPE = "interaction/surface";
    private static final String PRODUCER = "framework.core.InteractionJournal";
    private static final int MAX_LIMIT = 500;
    private static final int MAX_LINEAGE_DEPTH = 64;
    private final RunStore runs;
    private final ThreadStore threads;
    private final ObjectMapper json;

    public InteractionJournal(RunStore runs, ThreadStore threads, ObjectMapper json) {
        this.runs = Objects.requireNonNull(runs);
        this.threads = Objects.requireNonNull(threads);
        this.json = Objects.requireNonNull(json);
    }

    /** Supplemental lifecycle event; does not create an action receipt or modify run state. */
    public void record(RunId ownerRun, InteractionSurfaceEvent surface) {
        StoredRun owner = readable(ownerRun);
        logicalRoot(owner); // A child cannot keep writing after its logical conversation was tombstoned.
        Objects.requireNonNull(surface);
        boolean currentInvocation = ownerRun.equals(com.javaclaw.framework.spi.InteractionInvocation.currentRun());
        if (surface.causeProof() == InteractionSurfaceEvent.CauseProof.OBSERVED_AFTER) {
            String observing = currentInvocation ? surface.observedDuringInvocationId() : "";
            String sourceInvocation = surface.causedByInvocationId();
            String sourceSurface = surface.sourceSurfaceId();
            boolean verifiedSource = desktopSourceStarted(ownerRun, surface);
            // Background native polling has no active observing tool invocation. Its source
            // was captured by the host at dispatch and is checked against the durable start.
            // This retains only a low-confidence observation link, never an action receipt.
            surface = surface.withObservedDuringInvocation("").withObservedDuringInvocation(observing);
            if (verifiedSource) surface = surface.withObservedAfter(sourceInvocation, sourceSurface);
        } else if (!currentInvocation) surface = surface.withObservedDuringInvocation("");
        var payload = json.createObjectNode();
        payload.put("schemaVersion", 1);
        payload.put("producer", PRODUCER);
        payload.put("ownerRunId", ownerRun.value());
        payload.set("surface", json.valueToTree(surface));
        threads.appendInteractionOnce(owner.request().scope(), surface.eventId(), TYPE, payload);
    }

    private boolean desktopSourceStarted(RunId ownerRun, InteractionSurfaceEvent surface) {
        return runs.eventsAfter(ownerRun, 0).stream().anyMatch(event -> {
            if (!event.runId().equals(ownerRun.value()) || event.schemaVersion() != 1
                    || !event.type().equals("core.tool.started") || !event.producer().equals("framework.core")
                    || event.timestamp().toEpochMilli() > surface.observedAt().toEpochMilli()) return false;
            var payload = event.payload();
            var arguments = payload.path("arguments");
            return payload.path("trustedDesktopTool").isBoolean() && payload.path("trustedDesktopTool").booleanValue()
                    && Set.of("desktop_session_click", "desktop_session_type", "desktop_session_key",
                            "desktop_session_scroll").contains(payload.path("tool").asText())
                    && surface.causedByInvocationId().equals(payload.path("invocationId").asText())
                    && arguments.path("sessionId").isTextual() && !arguments.path("sessionId").asText().isBlank()
                    && arguments.path("observationId").isTextual() && !arguments.path("observationId").asText().isBlank()
                    && surface.contextId().equals(arguments.path("sessionId").asText());
        });
    }

    @Override public InteractionHistory history(RunId runId, int limit) {
        return history(logicalRoot(readable(runId)).request().scope(), limit);
    }

    private StoredRun logicalRoot(StoredRun current) {
        Set<RunId> seen = new HashSet<>();
        for (int depth = 0; current.request().linkage().parentRunId() != null; depth++) {
            if (depth >= MAX_LINEAGE_DEPTH || !seen.add(current.snapshot().id()))
                throw new SecurityException("invalid interaction lineage");
            StoredRun parent = readable(current.request().linkage().parentRunId());
            if (!samePrincipal(parent.request().scope(), current.request().scope()))
                throw new SecurityException("interaction lineage crosses principal");
            current = parent;
        }
        return current;
    }

    @Override public InteractionHistory history(RunScope logicalMainScope, int limit) {
        Objects.requireNonNull(logicalMainScope);
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("interaction limit must be 1..500");
        requireReadable(logicalMainScope);
        Map<RunId, StoredRun> included = new LinkedHashMap<>();
        ArrayDeque<StoredRun> pending = new ArrayDeque<>();
        for (StoredRun run : runs.scopeRuns(logicalMainScope)) {
            if (run.request().scope().equals(logicalMainScope)) pending.add(run);
        }
        while (!pending.isEmpty()) {
            StoredRun parent = pending.removeFirst();
            if (included.putIfAbsent(parent.snapshot().id(), parent) != null) continue;
            for (StoredRun child : runs.childRuns(parent.snapshot().id())) {
                if (parent.snapshot().id().equals(child.request().linkage().parentRunId())
                        && samePrincipal(logicalMainScope, child.request().scope())
                        && runs.readable(child.request().scope())) pending.add(child);
            }
        }
        List<InteractionHistory.Entry> result = new ArrayList<>();
        for (StoredRun run : included.values()) {
            for (RunEventEnvelope event : runs.eventsAfter(run.snapshot().id(), 0)) {
                InteractionHistory.Entry entry = toolEntry(event);
                if (entry != null) result.add(entry);
            }
        }
        Set<RunScope> scopes = new LinkedHashSet<>();
        included.values().forEach(run -> scopes.add(run.request().scope()));
        for (RunScope scope : scopes) {
            if (threads.find(scope).isEmpty()) continue;
            for (ThreadEvent event : threads.events(scope, 0)) {
                if (!TYPE.equals(event.type())) continue;
                JsonNode payload = event.payload();
                if (payload.path("schemaVersion").asInt() != 1
                        || !PRODUCER.equals(payload.path("producer").asText())) continue;
                RunId owner;
                try { owner = new RunId(payload.path("ownerRunId").asText()); }
                catch (RuntimeException invalid) { continue; }
                StoredRun source = included.get(owner);
                if (source == null || !scope.equals(source.request().scope())) continue;
                try {
                    InteractionSurfaceEvent surface = json.treeToValue(payload.path("surface"), InteractionSurfaceEvent.class);
                    result.add(new InteractionHistory.Entry(surface.eventId(), owner, event.sequence(),
                            surface.observedAt(), surface.mode(), surface.kind().name(),
                            surface.observedDuringInvocationId(), "", "HOST_OBSERVED", surface,
                            "thread:" + scope.sessionId() + ":" + event.sequence()));
                } catch (Exception invalid) { /* Old or incomplete records cannot become identity proof. */ }
            }
        }
        result.sort(Comparator.comparing(InteractionHistory.Entry::observedAt)
                .thenComparing(entry -> entry.ownerRunId().value())
                .thenComparingLong(InteractionHistory.Entry::eventSequence)
                .thenComparing(InteractionHistory.Entry::eventId));
        Map<InvocationKey, LinkedHashMap<SurfaceKey, InteractionSurfaceEvent>> associations = new HashMap<>();
        for (var entry : result) {
            var surface = entry.surface();
            if (surface == null || surface.surfaceId().isBlank()) continue;
            associate(associations, entry.ownerRunId(), surface.observedDuringInvocationId(), surface);
            // A later discovery belongs to its observing invocation and also to the source
            // input. Multiple candidates are retained; the link never becomes a causal receipt.
            if (surface.causeProof() != InteractionSurfaceEvent.CauseProof.UNKNOWN)
                associate(associations, entry.ownerRunId(), surface.causedByInvocationId(), surface);
        }
        result.replaceAll(entry -> {
            if (entry.surface() != null || entry.invocationId().isBlank()) return entry;
            var associated = associations.get(new InvocationKey(entry.ownerRunId(), entry.invocationId()));
            return associated == null ? entry : entry.withAssociatedSurfaces(associated.values().stream().limit(MAX_LIMIT).toList());
        });
        boolean truncated = result.size() > limit;
        if (truncated) result = new ArrayList<>(result.subList(result.size() - limit, result.size()));
        // A deletion during the query invalidates the entire projection, including descendant data.
        for (RunScope scope : scopes) requireReadable(scope);
        requireReadable(logicalMainScope);
        return new InteractionHistory(logicalMainScope, result, truncated);
    }

    private InteractionHistory.Entry toolEntry(RunEventEnvelope event) {
        if (!"framework.core".equals(event.producer())) return null;
        if (!Set.of("core.tool.started", "core.tool.completed", "core.tool.failed", "core.tool.receipt")
                .contains(event.type())) return null;
        JsonNode payload = event.payload();
        String tool = payload.path("tool").asText("");
        if (event.type().equals("core.tool.receipt")) tool = payload.path("receipt").path("tool").asText(tool);
        InteractionSurfaceEvent.Mode mode = tool.startsWith("web_") || tool.startsWith("site_") ? InteractionSurfaceEvent.Mode.BROWSER
                : tool.startsWith("desktop_session_") ? InteractionSurfaceEvent.Mode.DESKTOP : null;
        if (mode == null) return null;
        JsonNode detail = event.type().equals("core.tool.receipt") && payload.has("receipt")
                ? payload.path("receipt") : payload;
        String invocation = detail.path("invocationId").asText(payload.path("invocationId").asText(""));
        String status = detail.path("status").asText(event.type().equals("core.tool.started") ? "STARTED" : "UNKNOWN");
        String ref = detail.path("evidenceRef").asText(event.type() + ":" + event.runId() + ":" + invocation);
        return new InteractionHistory.Entry(event.runId() + ":" + event.sequence(), new RunId(event.runId()),
                event.sequence(), event.timestamp(), mode, event.type(), invocation, tool, status, null, ref);
    }

    private StoredRun readable(RunId id) {
        StoredRun run = runs.find(Objects.requireNonNull(id)).orElseThrow(() -> new IllegalArgumentException("unknown interaction run"));
        requireReadable(run.request().scope());
        return run;
    }
    private void requireReadable(RunScope scope) {
        if (!runs.readable(scope)) throw new IllegalStateException("interaction thread is not readable");
        threads.find(scope).ifPresent(thread -> {
            if (thread.status() == ThreadStatus.DELETED || thread.status() == ThreadStatus.DELETING
                    || thread.status() == ThreadStatus.FORKING)
                throw new IllegalStateException("interaction thread is not readable");
        });
    }
    private static boolean samePrincipal(RunScope a, RunScope b) {
        return a.workspaceId().equals(b.workspaceId()) && a.userId().equals(b.userId());
    }
    private static void associate(Map<InvocationKey, LinkedHashMap<SurfaceKey, InteractionSurfaceEvent>> associations,
            RunId ownerRun, String invocationId, InteractionSurfaceEvent surface) {
        if (invocationId.isBlank()) return;
        var key = new InvocationKey(ownerRun, invocationId);
        var identity = new SurfaceKey(surface.mode(), surface.runtimeId(), surface.contextId(), surface.surfaceId());
        associations.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(identity, surface);
    }
    private record InvocationKey(RunId runId, String invocationId) { }
    private record SurfaceKey(InteractionSurfaceEvent.Mode mode, String runtimeId, String contextId, String surfaceId) { }
}
