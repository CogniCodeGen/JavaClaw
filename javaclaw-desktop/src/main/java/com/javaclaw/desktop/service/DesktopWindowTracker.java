package com.javaclaw.desktop.service;

import com.javaclaw.desktop.api.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Owner-local supplemental history. No record here is an input or consent capability. */
final class DesktopWindowTracker {
    private static final int MAX_WINDOWS = 512, MAX_EVENTS = 4096, MAX_PENDING = 64;
    private static final long ASSOCIATION_MILLIS = 10_000;
    final AtomicBoolean scanning = new AtomicBoolean();
    volatile long nextWatchAtNanos;
    private final Map<String, Known> windows = new LinkedHashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final ArrayDeque<DesktopWindowTrackingEvent> events = new ArrayDeque<>();
    private final Executor executor;
    private Consumer<DesktopWindowTrackingEvent> observer;
    private CompletableFuture<Void> delivery = CompletableFuture.completedFuture(null);
    private long sequence, inventoryAt, dispatchSerial, observerGeneration;
    private boolean truncated, available;
    private Origin origin;

    DesktopWindowTracker(Executor executor) { this.executor = executor; }

    synchronized AutoCloseable bind(Consumer<DesktopWindowTrackingEvent> listener) {
        observer = Objects.requireNonNull(listener);
        long generation = ++observerGeneration;
        return () -> { synchronized (DesktopWindowTracker.this) {
            if (observerGeneration == generation && observer == listener) {
                observer = null; observerGeneration++;
            }
        } };
    }

    synchronized void detach() { observer = null; observerGeneration++; pending.clear(); origin = null; }

    synchronized void scan(String provider, List<Discovered> discovered, Set<String> provedClosed,
            long at, boolean cut) {
        Set<String> present = new HashSet<>();
        for (Discovered item : discovered) {
            DesktopTarget target = item.target();
            if (!target.providerId().equals(provider)) continue;
            String key = key(target, item.surface());
            if (!present.add(key)) continue;
            Known previous = windows.get(key);
            boolean hidden = target.minimized() || !target.visible();
            if (previous == null) {
                if (windows.size() >= MAX_WINDOWS) { truncated = true; continue; }
                Known added = new Known(target, item.surface(), true, hidden, false, at);
                windows.put(key, added);
                emit(added, DesktopWindowTrackingEvent.Kind.DISCOVERED, "", at, key);
                if (hidden) emit(added, DesktopWindowTrackingEvent.Kind.HIDDEN, "", at, key);
            } else {
                Known current = new Known(target, item.surface(), true, hidden, false, previous.firstSeenAt());
                windows.put(key, current);
                if (!previous.present() || previous.hidden() != hidden)
                    emit(current, hidden ? DesktopWindowTrackingEvent.Kind.HIDDEN
                            : DesktopWindowTrackingEvent.Kind.SHOWN, "", at, key);
                if (associationEligible(current, key, at))
                    emit(current, DesktopWindowTrackingEvent.Kind.DISCOVERED, "", at, key);
            }
        }
        // A partial inventory cannot establish even temporary absence of an omitted entry.
        if (!cut) for (var entry : new ArrayList<>(windows.entrySet())) {
            Known previous = entry.getValue();
            if (!previous.target().providerId().equals(provider) || present.contains(entry.getKey())
                    || previous.closed()) continue;
            boolean closed = provedClosed.contains(entry.getKey());
            if (!previous.present() && !closed) continue;
            Known current = new Known(previous.target(), previous.surface(), false, previous.hidden(), closed, previous.firstSeenAt());
            windows.put(entry.getKey(), current);
            emit(current, closed ? DesktopWindowTrackingEvent.Kind.CLOSED
                    : DesktopWindowTrackingEvent.Kind.UNAVAILABLE, "", at, entry.getKey());
        }
        inventoryAt = at; available = true; truncated |= cut;
    }

    synchronized List<Discovered> known(String provider) {
        return windows.values().stream().filter(k -> k.target().providerId().equals(provider)
                && !k.closed()).map(k -> new Discovered(k.target(), k.surface())).toList();
    }

    synchronized void opened(DesktopTarget target, DesktopSurfaceSnapshot surface, String session, long at) {
        String key = key(target, surface);
        Known previous = windows.get(key);
        Known current = new Known(target, surface, true, target.minimized() || !target.visible(), false,
                previous == null ? at : previous.firstSeenAt());
        if (previous == null) {
            if (windows.size() >= MAX_WINDOWS) { truncated = true; return; }
            windows.put(key, current);
            emit(current, DesktopWindowTrackingEvent.Kind.DISCOVERED, session, at, key);
        } else windows.put(key, current);
        // OPENED describes a host capture session, not an assertion that the OS just created a window.
        emit(current, DesktopWindowTrackingEvent.Kind.OPENED, session, at, key);
    }

    synchronized void sourceBegin(String session, String invocation, String observation,
            String target, DesktopSurfaceSnapshot surface) {
        if (invocation == null || invocation.isBlank() || observation == null || observation.isBlank()
                || surface == null || surface.runtimeId().isBlank() || surface.surfaceId().isBlank()) return;
        if (pending.size() >= MAX_PENDING) pending.remove(pending.keySet().iterator().next());
        pending.put(invocation, new Pending(session, observation, target, surface, Set.copyOf(windows.keySet()), null, 0, 0));
    }

    synchronized boolean actualOutcome(String session, String observation, DesktopActionResult result, long at) {
        long serial = result.dispatchAttempted() ? ++dispatchSerial : 0;
        // Every real dispatched input ends an older association, including delivery-unknown input.
        if (result.dispatchAttempted()) origin = null;
        List<String> matching = pending.entrySet().stream().filter(entry -> entry.getValue().session().equals(session)
                && entry.getValue().observation().equals(observation)).map(Map.Entry::getKey).toList();
        if (matching.size() != 1) {
            // The service must not guess which concurrent invocation owned one observation token.
            matching.forEach(pending::remove); return false;
        }
        String invocation = matching.getFirst(); Pending before = pending.get(invocation);
        Pending settled = new Pending(before.session(), before.observation(), before.target(), before.surface(),
                before.baseline(), result, at, serial);
        pending.put(invocation, settled);
        if (!result.dispatchAttempted() || !observation.equals(result.observationId())) return false;
        origin = new Origin(session, invocation, before.target(), before.surface(), before.baseline(), at, new HashSet<>());
        return true;
    }

    synchronized boolean finish(String session, String invocation, DesktopActionResult result) {
        Pending before = pending.remove(invocation);
        if (before == null || !before.session().equals(session) || before.actual() == null
                || !before.actual().equals(result) || !result.dispatchAttempted()
                || before.serial() != dispatchSerial
                || !before.observation().equals(result.observationId())) return false;
        if (origin == null || !origin.invocation().equals(invocation))
            origin = new Origin(session, invocation, before.target(), before.surface(), before.baseline(), before.at(), new HashSet<>());
        return true;
    }

    synchronized DesktopWindowCandidates candidates(String session, String target,
            DesktopSurfaceSnapshot selected, String requestedInvocation, long now, long waited, boolean timedOut) {
        Origin source = originFor(requestedInvocation, now);
        if (source != null && (selected == null || !source.session().equals(session)
                || !sameProcess(source.surface(), selected))) source = null;
        List<DesktopWindowCandidate> result = new ArrayList<>();
        if (selected != null && !selected.runtimeId().isBlank() && !selected.applicationId().isBlank())
            for (var entry : windows.entrySet()) {
                Known known = entry.getValue(); DesktopSurfaceSnapshot identity = known.surface();
                if (!known.present() || known.closed() || identity == null
                        || !known.target().providerId().equals(selected.providerId())
                        || !identity.runtimeId().equals(selected.runtimeId())
                        || !identity.applicationId().equals(selected.applicationId())) continue;
                boolean after = source != null && inventoryAt > source.at()
                        && source.associated().contains(entry.getKey()) && !source.baseline().contains(entry.getKey())
                        && !source.surface().surfaceId().equals(identity.surfaceId());
                result.add(new DesktopWindowCandidate(known.target(), identity.runtimeId(), identity.surfaceId(),
                        identity.surfaceId().equals(selected.surfaceId()), after));
            }
        return new DesktopWindowCandidates(session, target, source == null ? "" : source.invocation(),
                result, inventoryAt, waited, timedOut, available && selected != null
                        && !selected.runtimeId().isBlank() && !selected.applicationId().isBlank(), truncated);
    }

    synchronized DesktopWindowTrackingSnapshot snapshot(long after, int limit) {
        if (after < 0 || limit < 1 || limit > 500) throw new IllegalArgumentException("invalid window history bounds");
        long first = events.isEmpty() ? sequence + 1 : events.getFirst().sequence();
        List<DesktopWindowTrackingEvent> selected = events.stream().filter(e -> e.sequence() > after).limit(limit).toList();
        long next = selected.isEmpty() ? after : selected.getLast().sequence();
        return new DesktopWindowTrackingSnapshot(selected, first, next, sequence > next,
                truncated || after < first - 1);
    }

    private Origin originFor(String requested, long now) {
        return origin != null && now >= origin.at() && now - origin.at() <= ASSOCIATION_MILLIS
                && (requested == null || requested.isBlank() || requested.equals(origin.invocation())) ? origin : null;
    }

    private void emit(Known known, DesktopWindowTrackingEvent.Kind kind, String session, long at, String key) {
        DesktopTarget target = known.target(); DesktopSurfaceSnapshot identity = known.surface();
        Origin source = originFor("", at);
        boolean related = kind == DesktopWindowTrackingEvent.Kind.DISCOVERED && associationEligible(known, key, at);
        if (related) source.associated().add(key);
        DesktopWindowTrackingEvent event = new DesktopWindowTrackingEvent(++sequence, UUID.randomUUID().toString(), at,
                target.providerId(), identity == null ? "" : identity.runtimeId(), identity == null ? "" : identity.surfaceId(),
                target.id(), target.applicationId(), target.parentTargetId(), target.relationProof(), kind,
                related ? source.session() : session, related ? source.invocation() : "",
                related ? source.target() : "", related ? source.surface().surfaceId() : "",
                related ? DesktopWindowTrackingEvent.Association.OBSERVED_AFTER : DesktopWindowTrackingEvent.Association.NONE);
        events.addLast(event);
        if (events.size() > MAX_EVENTS) events.removeFirst();
        Consumer<DesktopWindowTrackingEvent> listener = observer;
        long generation = observerGeneration;
        if (listener != null) {
            try {
                delivery = delivery.handle((ignored, failure) -> null).thenRunAsync(() -> {
                    synchronized (DesktopWindowTracker.this) {
                        if (observerGeneration != generation || observer != listener) return;
                    }
                    // Do not hold the tracker monitor across durable persistence. A callback
                    // already entered here still has to pass the journal's scope/readability gate.
                    try { listener.accept(event); } catch (RuntimeException ignored) { /* Snapshot compensates. */ }
                }, executor);
            } catch (RuntimeException unavailable) { /* Retain event for the snapshot compensation path. */ }
        }
    }

    static String key(DesktopTarget target, DesktopSurfaceSnapshot identity) {
        return target.providerId() + "\0" + (identity == null ? "target\0" + target.id()
                : identity.runtimeId() + "\0" + identity.surfaceId());
    }

    private boolean associationEligible(Known known, String key, long at) {
        Origin source = originFor("", at); DesktopSurfaceSnapshot identity = known.surface();
        return source != null && at > source.at() && identity != null
                && sameProcess(source.surface(), identity) && !source.baseline().contains(key)
                && !source.associated().contains(key) && !source.surface().surfaceId().equals(identity.surfaceId());
    }

    private static boolean sameProcess(DesktopSurfaceSnapshot first, DesktopSurfaceSnapshot second) {
        return first.providerId().equals(second.providerId()) && !first.runtimeId().isBlank()
                && first.runtimeId().equals(second.runtimeId()) && !first.applicationId().isBlank()
                && first.applicationId().equals(second.applicationId());
    }

    record Discovered(DesktopTarget target, DesktopSurfaceSnapshot surface) { }
    private record Known(DesktopTarget target, DesktopSurfaceSnapshot surface, boolean present, boolean hidden,
                         boolean closed, long firstSeenAt) { }
    private record Pending(String session, String observation, String target, DesktopSurfaceSnapshot surface,
                           Set<String> baseline, DesktopActionResult actual, long at, long serial) { }
    private record Origin(String session, String invocation, String target, DesktopSurfaceSnapshot surface,
                          Set<String> baseline, long at, Set<String> associated) { }
}
