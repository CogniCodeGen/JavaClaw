package com.javaclaw.builtin.extensions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.ManagedExtensionStore;
import com.javaclaw.extension.spi.VersionedDocument;

/** Schedule 私有 H2 schema 中的 Occurrence 权威读写。 */
final class ScheduleOccurrenceStore {
    private static final ExtensionId OWNER = new ExtensionId(BuiltinExtensionIds.SCHEDULE);
    private static final Set<ScheduleContracts.OccurrenceState> ACTIVE = Set.of(
            ScheduleContracts.OccurrenceState.PENDING,
            ScheduleContracts.OccurrenceState.DISPATCHED,
            ScheduleContracts.OccurrenceState.RUNNING);

    private ScheduleOccurrenceStore() {}

    static ScheduleContracts.Occurrence create(
            ManagedExtensionStore store,
            ExtensionPayloadCodec payloads,
            WorkspaceId workspaceId,
            ScheduleContracts.Definition definition,
            Instant scheduledFor,
            String invocationKey,
            Instant now)
            throws Exception {
        return store.inTransaction(
                OWNER,
                transaction ->
                        create(transaction, payloads, workspaceId, definition, scheduledFor, invocationKey, now));
    }

    static ScheduleContracts.Occurrence create(
            ExtensionTransaction transaction,
            ExtensionPayloadCodec payloads,
            WorkspaceId workspaceId,
            ScheduleContracts.Definition definition,
            Instant scheduledFor,
            String invocationKey,
            Instant now) {
        String id = occurrenceId(payloads, definition, scheduledFor, invocationKey);
        Optional<VersionedDocument> existing = transaction.get(collection(workspaceId), id);
        if (existing.isPresent()) {
            return payloads.decode(existing.orElseThrow().payload(), ScheduleContracts.Occurrence.class);
        }
        boolean running = hasActive(transaction, payloads, workspaceId, definition.id());
        ScheduleContracts.OccurrenceStatus occurrenceStatus = running
                ? status(ScheduleContracts.OccurrenceState.SKIPPED, Optional.empty(), Optional.of("ALREADY_RUNNING"))
                : status(ScheduleContracts.OccurrenceState.PENDING, Optional.empty(), Optional.empty());
        ScheduleContracts.Occurrence occurrence = new ScheduleContracts.Occurrence(
                new ScheduleContracts.OccurrenceIdentity(id, definition.id(), definition.revision()),
                definition,
                scheduledFor,
                occurrenceStatus,
                now,
                now);
        transaction.put(collection(workspaceId), id, 0, payloads.encode(occurrence));
        return occurrence;
    }

    static ScheduleContracts.Occurrence transition(
            ManagedExtensionStore store,
            ExtensionPayloadCodec payloads,
            WorkspaceId workspaceId,
            String occurrenceId,
            ScheduleContracts.OccurrenceStatus status,
            Instant now)
            throws Exception {
        return store.inTransaction(OWNER, transaction -> {
            VersionedDocument document = transaction
                    .get(collection(workspaceId), occurrenceId)
                    .orElseThrow(() -> new IllegalArgumentException("Schedule Occurrence does not exist"));
            ScheduleContracts.Occurrence current =
                    payloads.decode(document.payload(), ScheduleContracts.Occurrence.class);
            if (current.status().equals(status)) {
                return current;
            }
            requireTransition(current.status().state(), status.state());
            ScheduleContracts.Occurrence updated = new ScheduleContracts.Occurrence(
                    current.identity(), current.definition(), current.scheduledFor(), status, current.createdAt(), now);
            transaction.put(collection(workspaceId), occurrenceId, document.revision(), payloads.encode(updated));
            return updated;
        });
    }

    static ScheduleContracts.OccurrencePage list(
            ManagedExtensionStore store,
            ExtensionPayloadCodec payloads,
            WorkspaceId workspaceId,
            ScheduleContracts.OccurrenceQuery query)
            throws Exception {
        return store.inTransaction(OWNER, transaction -> page(transaction, payloads, workspaceId, query));
    }

    static List<ScheduleContracts.Occurrence> active(
            ManagedExtensionStore store, ExtensionPayloadCodec payloads, WorkspaceId workspaceId) throws Exception {
        return store.inTransaction(
                OWNER,
                transaction -> all(transaction, payloads, workspaceId).stream()
                        .filter(value -> ACTIVE.contains(value.status().state()))
                        .toList());
    }

    private static ScheduleContracts.OccurrencePage page(
            ExtensionTransaction transaction,
            ExtensionPayloadCodec payloads,
            WorkspaceId workspaceId,
            ScheduleContracts.OccurrenceQuery query) {
        List<ScheduleContracts.Occurrence> matches = new ArrayList<>();
        String cursor = query.afterKey();
        while (matches.size() < query.limit()) {
            List<VersionedDocument> documents = transaction.list(collection(workspaceId), cursor, 500);
            for (VersionedDocument document : documents) {
                ScheduleContracts.Occurrence occurrence =
                        payloads.decode(document.payload(), ScheduleContracts.Occurrence.class);
                if (query.scheduleId()
                        .filter(id -> !id.equals(occurrence.identity().scheduleId()))
                        .isEmpty()) {
                    matches.add(occurrence);
                    if (matches.size() == query.limit()) {
                        return new ScheduleContracts.OccurrencePage(List.copyOf(matches), document.key());
                    }
                }
            }
            if (documents.size() < 500) {
                return new ScheduleContracts.OccurrencePage(List.copyOf(matches), "");
            }
            cursor = documents.getLast().key();
        }
        return new ScheduleContracts.OccurrencePage(List.copyOf(matches), cursor);
    }

    private static boolean hasActive(
            ExtensionTransaction transaction,
            ExtensionPayloadCodec payloads,
            WorkspaceId workspaceId,
            String scheduleId) {
        return all(transaction, payloads, workspaceId).stream()
                .anyMatch(value -> value.identity().scheduleId().equals(scheduleId)
                        && ACTIVE.contains(value.status().state()));
    }

    private static List<ScheduleContracts.Occurrence> all(
            ExtensionTransaction transaction, ExtensionPayloadCodec payloads, WorkspaceId workspaceId) {
        List<ScheduleContracts.Occurrence> values = new ArrayList<>();
        String cursor = "";
        while (true) {
            List<VersionedDocument> page = transaction.list(collection(workspaceId), cursor, 500);
            page.stream()
                    .map(document -> payloads.decode(document.payload(), ScheduleContracts.Occurrence.class))
                    .forEach(values::add);
            if (page.size() < 500) {
                return List.copyOf(values);
            }
            cursor = page.getLast().key();
        }
    }

    private static String occurrenceId(
            ExtensionPayloadCodec payloads,
            ScheduleContracts.Definition definition,
            Instant scheduledFor,
            String invocationKey) {
        String digest = payloads.encode(new OccurrenceSeed(
                        definition.id(), definition.revision(), scheduledFor, requireInvocationKey(invocationKey)))
                .sha256();
        return "occ-" + digest.substring(0, 40);
    }

    private static String requireInvocationKey(String invocationKey) {
        String value = Objects.requireNonNull(invocationKey, "invocationKey").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Schedule invocation key must not be blank");
        }
        return value;
    }

    private static String collection(WorkspaceId workspaceId) {
        return "occurrences." + Objects.requireNonNull(workspaceId, "workspaceId");
    }

    private static ScheduleContracts.OccurrenceStatus status(
            ScheduleContracts.OccurrenceState state, Optional<String> jobId, Optional<String> reason) {
        return new ScheduleContracts.OccurrenceStatus(state, jobId, reason);
    }

    private static void requireTransition(
            ScheduleContracts.OccurrenceState current, ScheduleContracts.OccurrenceState next) {
        boolean valid =
                switch (current) {
                    case PENDING ->
                        next == ScheduleContracts.OccurrenceState.DISPATCHED
                                || next == ScheduleContracts.OccurrenceState.FAILED
                                || next == ScheduleContracts.OccurrenceState.CANCELLED;
                    case DISPATCHED -> next == ScheduleContracts.OccurrenceState.RUNNING || terminal(next);
                    case RUNNING -> terminal(next);
                    case COMPLETED, FAILED, CANCELLED, SKIPPED -> false;
                };
        if (!valid) {
            throw new IllegalArgumentException("invalid Schedule Occurrence state transition");
        }
    }

    private static boolean terminal(ScheduleContracts.OccurrenceState state) {
        return state == ScheduleContracts.OccurrenceState.COMPLETED
                || state == ScheduleContracts.OccurrenceState.FAILED
                || state == ScheduleContracts.OccurrenceState.CANCELLED;
    }

    private record OccurrenceSeed(
            String scheduleId, long scheduleRevision, Instant scheduledFor, String invocationKey) {}
}
