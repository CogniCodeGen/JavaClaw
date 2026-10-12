package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunEventEnvelope;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Read-only identity bindings derived from complete, tool-bound native catalog snapshots. */
final class DesktopApplicationIdentityBindings {
    private static final String TOOL = "desktop_session_applications";
    private final List<Snapshot> snapshots;
    private final List<Page> provenPages;

    private DesktopApplicationIdentityBindings(List<Snapshot> snapshots, List<Page> provenPages) {
        this.snapshots = List.copyOf(snapshots);
        this.provenPages = List.copyOf(provenPages);
    }

    static DesktopApplicationIdentityBindings fromEvents(List<RunEventEnvelope> events) {
        Map<String, List<Page>> pages = new LinkedHashMap<>();
        for (RunEventEnvelope completed : events) {
            if (!host(completed, "core.tool.completed", 2)
                    || !completed.payload().path("tool").asText("").equals(TOOL)
                    || !completed.payload().path("status").asText("").equals("SUCCEEDED")) continue;
            String invocation = completed.payload().path("invocationId").asText("");
            if (invocation.isBlank()) continue;
            RunEventEnvelope started = events.stream().filter(event ->
                    host(event, "core.tool.started", 1)
                            && event.runId().equals(completed.runId())
                            && event.sequence() < completed.sequence()
                            && event.payload().path("tool").asText("").equals(TOOL)
                            && event.payload().path("invocationId").asText("").equals(invocation))
                    .reduce((first, last) -> last).orElse(null);
            RunEventEnvelope receipt = events.stream().filter(event ->
                    host(event, "core.tool.receipt", 1)
                            && event.runId().equals(completed.runId())
                            && event.sequence() > completed.sequence()
                            && event.payload().path("tool").asText("").equals(TOOL)
                            && event.payload().path("invocationId").asText("").equals(invocation)
                            && event.payload().path("operation").asText("").equals("applications")
                            && event.payload().path("status").asText("").equals("OBSERVED")
                            && event.payload().path("evidenceRef").asText("").equals(
                                    "core.tool.completed:" + completed.runId() + ":" + invocation))
                    .findFirst().orElse(null);
            if (started == null || receipt == null) continue;
            try { Instant.parse(receipt.payload().path("observedAt").asText()); }
            catch (RuntimeException invalid) { continue; }
            JsonNode output = completed.payload().path("output"); // Never modelOutput or display text.
            if (!output.path("protocol").asText("").equals("computer-use")
                    || output.path("schemaVersion").asInt(-1) != 1
                    || !output.path("kind").asText("").equals("desktop.applications")
                    || !output.path("catalogId").asText("").matches("[0-9a-f]{64}")
                    || !output.path("truncated").isBoolean()
                    || !output.path("hasMore").isBoolean()
                    || !output.path("applications").isArray()) continue;
            JsonNode arguments = started.payload().path("arguments");
            String query = normalize(output.path("query").asText(""));
            int offset = output.path("offset").asInt(-1);
            int count = output.path("count").asInt(-1);
            int total = output.path("totalCount").asInt(-1);
            int catalogTotal = output.path("catalogTotalCount").asInt(-1);
            if (!query.equals(normalize(arguments.path("query").asText("")))
                    || offset != arguments.path("offset").asInt(0)
                    || offset < 0 || count < 0 || total < 0 || catalogTotal < total
                    || count != output.path("applications").size()
                    || (long) offset + count > total
                    || output.path("hasMore").asBoolean() != ((long) offset + count < total)
                    || (output.path("hasMore").asBoolean()
                            && output.path("nextOffset").asInt(-1) != offset + count)) continue;
            Map<String, Set<String>> aliases = new LinkedHashMap<>();
            boolean valid = true;
            for (JsonNode application : output.path("applications")) {
                String applicationId = normalize(application.path("applicationId").asText(""));
                if (applicationId.isBlank() || !application.path("aliases").isArray()) {
                    valid = false;
                    break;
                }
                List<String> labels = new ArrayList<>();
                for (String field : List.of("name", "displayName", "applicationId", "launchName")) {
                    if (application.path(field).isTextual()) labels.add(application.path(field).asText());
                }
                for (JsonNode alias : application.path("aliases")) {
                    if (!alias.isTextual()) { valid = false; break; }
                    labels.add(alias.asText());
                }
                if (!valid) break;
                for (String label : labels) {
                    String normalized = normalize(label);
                    if (!normalized.isBlank()) aliases.computeIfAbsent(normalized,
                            ignored -> new LinkedHashSet<>()).add(applicationId);
                }
            }
            if (!valid) continue;
            String key = completed.runId() + "/" + output.path("catalogId").asText()
                    + "/" + query + "/" + total + "/" + catalogTotal;
            pages.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Page(
                    completed.runId(), query, offset, count, total, catalogTotal,
                    receipt.sequence(), output.path("truncated").asBoolean(), aliases,
                    output.path("catalogId").asText(), started.sequence(), invocation,
                    receipt.payload().path("evidenceRef").asText(), arguments.path("query").asText("")));
        }
        List<Snapshot> snapshots = new ArrayList<>();
        for (List<Page> group : pages.values()) {
            // Repeated reads of one offset contribute once; use the earliest proven page.
            Map<Integer, Page> offsets = new LinkedHashMap<>();
            group.stream().sorted(Comparator.comparingLong(Page::sequence))
                    .forEach(page -> offsets.putIfAbsent(page.offset(), page));
            List<Page> ordered = offsets.values().stream()
                    .sorted(Comparator.comparingInt(Page::offset)).toList();
            int cursor = 0;
            long sequence = 0;
            Map<String, Set<String>> aliases = new LinkedHashMap<>();
            for (Page page : ordered) {
                if (page.offset() != cursor) break;
                cursor += page.count();
                sequence = Math.max(sequence, page.sequence());
                page.aliases().forEach((alias, identities) -> aliases.computeIfAbsent(alias,
                        ignored -> new LinkedHashSet<>()).addAll(identities));
            }
            Page first = ordered.getFirst();
            boolean complete = cursor == first.total() && ordered.stream().noneMatch(Page::truncated);
            int contiguousCursor = cursor;
            snapshots.add(new Snapshot(first.runId(), first.query(),
                    first.total() == first.catalogTotal(),
                    complete ? sequence : group.stream().mapToLong(Page::sequence).max().orElse(sequence),
                    complete, aliases, first.catalogId(), cursor, first.total(), first.catalogTotal(),
                    ordered.stream().anyMatch(Page::truncated), ordered.stream()
                            .filter(page -> page.offset() < contiguousCursor || page.total() == 0)
                            .map(Page::evidenceRef).toList(), first.requestedQuery()));
        }
        return new DesktopApplicationIdentityBindings(snapshots,
                pages.values().stream().flatMap(List::stream).toList());
    }

    /** Read-only preparation uses the same proven pages as matches, never a second alias parser. */
    TaskResultEvaluator.DesktopApplicationIdentityState preparationState(List<RunEventEnvelope> events,
            String runId, String requested, long beforeSequence) {
        String wanted = normalize(requested);
        var latest = events.stream().filter(event -> host(event, "core.tool.started", 1)
                        && event.runId().equals(runId) && event.sequence() < beforeSequence
                        && event.payload().path("tool").asText().equals(TOOL)
                        && (normalize(event.payload().path("arguments").path("query").asText(""))
                                .equals(wanted)
                            || event.payload().path("arguments").path("query").asText("").isBlank()
                            || provenPages.stream().anyMatch(page -> page.startedSequence() == event.sequence()
                                && page.total() == page.catalogTotal())))
                .max(Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
        if (latest == null) return state(TaskResultEvaluator.DesktopApplicationIdentityStatus.ABSENT,
                requested, 0, "", "", List.of());
        var matchingPages = provenPages.stream().filter(value -> value.runId().equals(runId)
                        && value.startedSequence() == latest.sequence()
                        && value.invocationId().equals(latest.payload().path("invocationId").asText()))
                .toList();
        var page = matchingPages.size() == 1 ? matchingPages.getFirst() : null;
        // A failed, pending or malformed latest attempt cannot fall back to an older successful read.
        if (page == null) return state(TaskResultEvaluator.DesktopApplicationIdentityStatus.INVALID,
                requested, 0, "", "", List.of());
        Snapshot snapshot = snapshots.stream().filter(value -> value.runId().equals(runId)
                        && value.catalogId().equals(page.catalogId()) && value.query().equals(page.query())
                        && value.total() == page.total() && value.catalogTotal() == page.catalogTotal())
                .findFirst().orElse(null);
        if (snapshot == null || snapshot.requestedQuery().length() > 512
                || provenPages.stream().filter(value -> value.runId().equals(runId)
                    && value.catalogId().equals(page.catalogId()) && value.query().equals(page.query())
                    && value.total() == page.total() && value.catalogTotal() == page.catalogTotal())
                    .anyMatch(value -> !value.requestedQuery().equals(snapshot.requestedQuery())
                        || !boundOnce(value, events))
                || snapshot.evidenceRefs().size() > 16
                || snapshot.evidenceRefs().stream().anyMatch(ref -> ref.isBlank() || ref.length() > 256)) {
            return state(TaskResultEvaluator.DesktopApplicationIdentityStatus.INVALID,
                    requested, 0, "", "", List.of());
        }
        var group = provenPages.stream().filter(value -> value.runId().equals(runId)
                && value.catalogId().equals(page.catalogId()) && value.query().equals(page.query())
                && value.total() == page.total() && value.catalogTotal() == page.catalogTotal())
                .sorted(Comparator.comparingLong(Page::startedSequence)).toList();
        int expectedOffset = 0;
        Map<Integer, Page> visitedOffsets = new LinkedHashMap<>();
        for (Page proven : group) {
            Page prior = visitedOffsets.putIfAbsent(proven.offset(), proven);
            if (snapshot.complete() && prior != null && prior.count() == proven.count()
                    && prior.aliases().equals(proven.aliases())) continue;
            if (proven.offset() != expectedOffset || proven.count() == 0 && proven.total() != 0) {
                return state(TaskResultEvaluator.DesktopApplicationIdentityStatus.INVALID,
                        snapshot.requestedQuery(), snapshot.nextOffset(), snapshot.catalogId(), "", snapshot.evidenceRefs());
            }
            expectedOffset += proven.count();
        }
        if (snapshot.truncated()) return state(TaskResultEvaluator.DesktopApplicationIdentityStatus.TRUNCATED,
                snapshot.requestedQuery(), snapshot.nextOffset(), snapshot.catalogId(), "", snapshot.evidenceRefs());
        if (!snapshot.complete()) {
            if (provenPages.stream().filter(value -> value.runId().equals(runId)
                    && value.query().equals(page.query())).map(Page::catalogId).distinct().count() > 1) {
                return state(TaskResultEvaluator.DesktopApplicationIdentityStatus.INVALID,
                        snapshot.requestedQuery(), snapshot.nextOffset(), snapshot.catalogId(), "", snapshot.evidenceRefs());
            }
            long repeats = provenPages.stream().filter(value -> value.runId().equals(runId)
                    && value.catalogId().equals(page.catalogId()) && value.query().equals(page.query())
                    && value.total() == page.total() && value.catalogTotal() == page.catalogTotal()
                    && value.offset() == page.offset()).count();
            if (snapshot.nextOffset() <= 0 || snapshot.nextOffset() >= snapshot.total()
                    || repeats > 1 || page.count() == 0) {
                return state(TaskResultEvaluator.DesktopApplicationIdentityStatus.INVALID,
                        snapshot.requestedQuery(), snapshot.nextOffset(), snapshot.catalogId(), "", snapshot.evidenceRefs());
            }
            return state(TaskResultEvaluator.DesktopApplicationIdentityStatus.INCOMPLETE,
                    snapshot.requestedQuery(), snapshot.nextOffset(), snapshot.catalogId(), "", snapshot.evidenceRefs());
        }
        Set<String> identities = snapshot.aliases().getOrDefault(wanted, Set.of());
        if (identities.isEmpty()) return state(TaskResultEvaluator.DesktopApplicationIdentityStatus.EMPTY,
                snapshot.requestedQuery(), snapshot.nextOffset(), snapshot.catalogId(), "", snapshot.evidenceRefs());
        if (identities.size() != 1) return state(TaskResultEvaluator.DesktopApplicationIdentityStatus.AMBIGUOUS,
                snapshot.requestedQuery(), snapshot.nextOffset(), snapshot.catalogId(), "", snapshot.evidenceRefs());
        String canonical = canonicalIdentity(requested, runId, beforeSequence);
        if (canonical.length() > 256 || !canonical.equals(identities.iterator().next())) {
            return state(TaskResultEvaluator.DesktopApplicationIdentityStatus.INVALID,
                    snapshot.requestedQuery(), snapshot.nextOffset(), snapshot.catalogId(), "", snapshot.evidenceRefs());
        }
        return state(TaskResultEvaluator.DesktopApplicationIdentityStatus.COMPLETE,
                snapshot.requestedQuery(), snapshot.nextOffset(), snapshot.catalogId(), canonical, snapshot.evidenceRefs());
    }

    private static TaskResultEvaluator.DesktopApplicationIdentityState state(
            TaskResultEvaluator.DesktopApplicationIdentityStatus status, String query, int offset,
            String catalogId, String identity, List<String> refs) {
        return new TaskResultEvaluator.DesktopApplicationIdentityState(status, query, offset,
                catalogId, identity, refs);
    }

    private static boolean boundOnce(Page page, List<RunEventEnvelope> events) {
        for (String type : List.of("core.tool.started", "core.tool.completed", "core.tool.receipt")) {
            int version = type.equals("core.tool.completed") ? 2 : 1;
            long count = events.stream().filter(event -> host(event, type, version)
                    && event.runId().equals(page.runId()) && event.payload().path("tool").asText().equals(TOOL)
                    && event.payload().path("invocationId").asText().equals(page.invocationId())).count();
            if (count != 1) return false;
        }
        return true;
    }

    boolean matches(String expected, String target, String applicationId,
            String runId, long sequence) {
        String wanted = normalize(expected);
        String identity = normalize(applicationId);
        if (!wanted.isBlank() && wanted.equals(identity)) return true;
        Resolution resolution = resolve(wanted, runId, sequence);
        if (resolution.ambiguous()) return false;
        if (!resolution.applicationId().isBlank()) {
            // A native app ID is authoritative even when a window reuses another app's display name.
            return !identity.isBlank() ? resolution.applicationId().equals(identity)
                    : resolution.applicationId().equals(normalize(target));
        }
        // Existing exact identities remain usable when discovery was unnecessary.
        return !wanted.isBlank() && (wanted.equals(identity) || wanted.equals(normalize(target)));
    }

    /** Only native catalog proof before this start can bind a launch's physical application resource. */
    String canonicalIdentity(String requested, String runId, long sequence) {
        String wanted = normalize(requested);
        if (wanted.isBlank()) return "";
        Resolution resolution = resolve(wanted, runId, sequence);
        if (resolution.ambiguous()) return "";
        if (!resolution.applicationId().isBlank()) return resolution.applicationId();
        // A filtered catalog can explicitly register its canonical ID even when its query
        // was the display alias. This permits that exact ID, never unqueried aliases.
        Snapshot explicitIdentity = snapshots.stream()
                .filter(snapshot -> snapshot.runId().equals(runId) && snapshot.sequence() < sequence
                        && snapshot.aliases().getOrDefault(wanted, Set.of()).contains(wanted))
                .max(Comparator.comparingLong(Snapshot::sequence)).orElse(null);
        return explicitIdentity != null && explicitIdentity.complete()
                && explicitIdentity.aliases().get(wanted).size() == 1 ? wanted : "";
    }

    private Resolution resolve(String expected, String runId, long sequence) {
        Snapshot latest = snapshots.stream().filter(snapshot -> snapshot.runId().equals(runId)
                        && snapshot.sequence() < sequence
                        && (snapshot.fullCatalog() || snapshot.query().isBlank()
                                || snapshot.query().equals(expected)))
                .max(Comparator.comparingLong(Snapshot::sequence)).orElse(null);
        if (latest == null) return new Resolution("", false);
        if (!latest.complete()) return new Resolution("", true);
        Set<String> identities = latest.aliases().getOrDefault(expected, Set.of());
        return new Resolution(identities.size() == 1 ? identities.iterator().next() : "",
                identities.size() != 1);
    }

    static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value.strip(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private static boolean host(RunEventEnvelope event, String type, int version) {
        return event.type().equals(type) && event.schemaVersion() == version
                && event.producer().equals("framework.core");
    }

    private record Page(String runId, String query, int offset, int count, int total,
            int catalogTotal, long sequence, boolean truncated, Map<String, Set<String>> aliases,
            String catalogId, long startedSequence, String invocationId, String evidenceRef, String requestedQuery) { }
    private record Snapshot(String runId, String query, boolean fullCatalog, long sequence,
            boolean complete, Map<String, Set<String>> aliases, String catalogId, int nextOffset,
            int total, int catalogTotal, boolean truncated, List<String> evidenceRefs, String requestedQuery) { }
    private record Resolution(String applicationId, boolean ambiguous) { }
}
