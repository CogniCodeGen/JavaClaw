package com.javaclaw.desktop.service;

import com.javaclaw.desktop.api.*;
import com.javaclaw.desktop.spi.DesktopPlatformProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import com.javaclaw.desktop.service.DefaultDesktopSessionService.TrackingLease;

/** 有界窗口发现和只读历史追踪，共用服务的所有权锁与关闭代次。 */
final class DesktopWindowTracking {
    private final DefaultDesktopSessionService service;
    private final Map<DesktopSessionOwner, DesktopWindowTracker> windowTrackers = new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService windowWatch = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("desktop-window-watch").factory());

    DesktopWindowTracking(DefaultDesktopSessionService service) {
        this.service = service;
        windowWatch.scheduleWithFixedDelay(this::watchWindows, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    void closeScope(String workspaceId, String scopeId) {
        windowTrackers.forEach((owner, tracker) -> {
            if (owner.workspaceId().equals(workspaceId) && owner.scopeId().equals(scopeId)) tracker.detach();
        });
    }

    void closeWorkspace(String workspaceId) {
        windowTrackers.entrySet().removeIf(entry -> {
            if (!entry.getKey().workspaceId().equals(workspaceId)) return false;
            entry.getValue().detach(); return true;
        });
    }

    void close() {
        windowWatch.shutdownNow();
        windowTrackers.values().forEach(DesktopWindowTracker::detach);
        windowTrackers.clear();
    }

    AutoCloseable bindWindowObserver(DesktopSessionOwner owner,
            java.util.function.Consumer<DesktopWindowTrackingEvent> listener) {
        Objects.requireNonNull(owner, "owner"); service.ensureOpen();
        return windowTrackers.computeIfAbsent(owner, ignored -> new DesktopWindowTracker(service.workers)).bind(listener);
    }

    DesktopWindowTrackingSnapshot snapshotWindowTracking(DesktopSessionOwner owner,
            long afterSequence, int limit) {
        Objects.requireNonNull(owner, "owner");
        if (afterSequence < 0 || limit < 1 || limit > 500)
            throw new IllegalArgumentException("invalid window history bounds");
        DesktopWindowTracker tracker = windowTrackers.get(owner);
        return tracker == null ? new DesktopWindowTrackingSnapshot(List.of(), 0, afterSequence, false, false)
                : tracker.snapshot(afterSequence, limit);
    }

    void beginWindowAction(DesktopSessionOwner owner, String sessionId,
            String invocationId, String observationId) {
        ManagedSession session = service.owned(owner, sessionId);
        if (invocationId == null || invocationId.isBlank() || observationId == null || observationId.isBlank()) return;
        try {
            DesktopWindowTracker tracker = windowTrackers.computeIfAbsent(owner, ignored -> new DesktopWindowTracker(service.workers));
            // 可选动作前目录读取有界，不能改变输入准入。
            DesktopPlatformProvider provider = provider(session.target.providerId());
            if (provider != null) refreshWindowTracking(owner, tracker, provider, 200);
            session.windowActionSource(observationId).ifPresent(surface -> tracker.sourceBegin(sessionId,
                    invocationId, observationId, session.target.id(), surface));
        } catch (RuntimeException unavailable) { /* 输入准入仍由 perform 负责。 */ }
    }

    void finishWindowAction(DesktopSessionOwner owner, String sessionId,
            String invocationId, DesktopActionResult result) {
        DesktopWindowTracker tracker = windowTrackers.get(owner);
        if (tracker == null || result == null || invocationId == null) return;
        if (tracker.finish(sessionId, invocationId, result)) try {
            ManagedSession session = service.sessions.get(sessionId);
            if (session != null && session.owner.equals(owner)) queueWindowTracking(owner, tracker, session);
        } catch (RuntimeException unavailable) { /* 保留已结算的实际动作结果。 */ }
    }

    CompletionStage<DesktopWindowCandidates> discoverWindowCandidates(DesktopSessionOwner owner,
            String sessionId, String invocationId, long waitMillis) {
        if (waitMillis < 0 || waitMillis > 3_000) throw new IllegalArgumentException("window wait must be 0..3000ms");
        service.requireEnabled();
        ManagedSession session = service.owned(owner, sessionId);
        TrackingLease lease = service.trackingLease(owner);
        return CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime(), minimumInventoryAt = service.clock.millis();
            long deadline = started + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(250, waitMillis));
            DesktopWindowTracker tracker = windowTrackers.computeIfAbsent(owner, ignored -> new DesktopWindowTracker(service.workers));
            DesktopPlatformProvider provider = provider(session.target.providerId());
            DesktopWindowCandidates result;
            do {
                service.requireLaunchActive(owner, lease.scope(), lease.workspaceEpoch(), lease.scopeEpoch());
                session.requireOpen();
                long remaining = Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                if (provider != null) refreshWindowTracking(owner, tracker, provider, Math.min(250, remaining), lease);
                // 发现身份不可变，不获取会话的原生输入锁；
                // 进行中的捕获或输入不能延长本次有界只读等待。
                DesktopSurfaceSnapshot identity = session.windowInventoryIdentity().orElseGet(() ->
                        provider == null ? null : safeTargetIdentity(provider, session.target));
                long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                result = tracker.candidates(sessionId, session.target.id(), identity, invocationId,
                        service.clock.millis(), elapsed, System.nanoTime() >= deadline);
                if (result.observedAtMillis() < minimumInventoryAt)
                    result = new DesktopWindowCandidates(result.sessionId(), result.sourceTargetId(), result.sourceInvocationId(),
                            List.of(), result.observedAtMillis(), result.waitedMillis(), result.timedOut(), false, result.truncated());
                if (waitMillis == 0 || result.candidates().stream().anyMatch(DesktopWindowCandidate::observedAfterAction)
                        || System.nanoTime() >= deadline) {
                    service.requireLaunchActive(owner, lease.scope(), lease.workspaceEpoch(), lease.scopeEpoch());
                    session.requireOpen();
                    return result;
                }
                long sleepMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (sleepMillis <= 0) continue;
                try { Thread.sleep(Math.min(100, sleepMillis)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException("window discovery interrupted");
                }
            } while (true);
        }, service.workers);
    }

    private DesktopPlatformProvider provider(String id) {
        return service.providers.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
    }

    private void watchWindows() {
        if (service.closed) return;
        try {
            Map<DesktopSessionOwner, ManagedSession> active = new HashMap<>();
            service.sessions.values().stream().filter(session -> !session.closed)
                    .forEach(session -> active.putIfAbsent(session.owner, session));
            active.forEach((owner, session) -> {
                DesktopWindowTracker tracker = windowTrackers.computeIfAbsent(owner, ignored -> new DesktopWindowTracker(service.workers));
                long now = System.nanoTime();
                if (now < tracker.nextWatchAtNanos) return;
                tracker.nextWatchAtNanos = now + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
                queueWindowTracking(owner, tracker, session);
            });
        } catch (RuntimeException unavailable) { /* 补充历史追踪不能阻断会话捕获。 */ }
    }

    void opened(ManagedSession session, DesktopPlatformProvider provider, DesktopSessionInfo info) {
        try {
            DesktopSurfaceSnapshot identity = safeTargetIdentity(provider, info.target());
            if (identity != null) session.windowIdentity = identity;
            windowTrackers.computeIfAbsent(session.owner, ignored -> new DesktopWindowTracker(service.workers))
                    .opened(info.target(), identity, session.id, service.clock.millis());
        } catch (RuntimeException unavailable) { /* 历史追踪不得改变成功的会话准入。 */ }
    }

    DesktopWindowTracker actualOutcome(ManagedSession session, DesktopAction action, DesktopActionResult result) {
        DesktopWindowTracker tracker = windowTrackers.get(session.owner);
        if (tracker != null) try {
            if (tracker.actualOutcome(session.id, action.observationId(), result, service.clock.millis())) return tracker;
        } catch (RuntimeException unavailable) { /* 历史追踪不得改变实际输入结果。 */ }
        return null;
    }

    void queueWindowTracking(DesktopSessionOwner owner, DesktopWindowTracker tracker, ManagedSession session) {
        try {
            synchronized (service) {
                if (service.closed || service.sessions.get(session.id) != session || session.closed || !session.owner.equals(owner)) return;
                TrackingLease lease = service.trackingLease(owner);
                DesktopPlatformProvider selected = provider(session.target.providerId());
                if (selected != null) service.workers.execute(() -> refreshWindowTracking(owner, tracker, selected, 500, lease));
            }
        } catch (RuntimeException unavailable) { /* 可选的迟到历史不能改变输入结果。 */ }
    }

    private boolean refreshWindowTracking(DesktopSessionOwner owner, DesktopWindowTracker tracker,
            DesktopPlatformProvider provider, long timeoutMillis) {
        TrackingLease lease;
        try { lease = service.trackingLease(owner); }
        catch (RuntimeException unavailable) { return false; }
        return refreshWindowTracking(owner, tracker, provider, timeoutMillis, lease);
    }

    private boolean refreshWindowTracking(DesktopSessionOwner owner, DesktopWindowTracker tracker,
            DesktopPlatformProvider provider, long timeoutMillis, TrackingLease lease) {
        if (!tracker.scanning.compareAndSet(false, true)) return false;
        try { service.requireLaunchActive(owner, lease.scope(), lease.workspaceEpoch(), lease.scopeEpoch()); }
        catch (RuntimeException unavailable) { tracker.scanning.set(false); return false; }
        CompletableFuture<WindowScan> scan;
        try {
            scan = CompletableFuture.supplyAsync(() -> windowScan(provider, tracker,
                    List.copyOf(provider.discoverTargets())), service.workers);
        } catch (RuntimeException unavailable) { tracker.scanning.set(false); return false; }
        try {
            WindowScan value = scan.get(Math.max(1, timeoutMillis), java.util.concurrent.TimeUnit.MILLISECONDS);
            applyWindowScan(owner, tracker, provider.id(), value, lease);
            tracker.scanning.set(false);
            return true;
        } catch (InterruptedException interrupted) {
            scan.whenComplete((ignored, failure) -> tracker.scanning.set(false));
            Thread.currentThread().interrupt(); return false;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException unavailable) {
            // 迟到的只读枚举不作为新的动作前后样本发布。
            // 实际原生读取退出前保留单飞租约。
            scan.whenComplete((ignored, failure) -> tracker.scanning.set(false));
            return false;
        }
    }

    void trackDiscovery(DesktopSessionOwner owner, DesktopPlatformProvider provider,
            List<DesktopTarget> targets, TrackingLease lease) {
        try {
            DesktopWindowTracker tracker = windowTrackers.computeIfAbsent(owner, ignored -> new DesktopWindowTracker(service.workers));
            if (!tracker.scanning.compareAndSet(false, true)) return;
            try { applyWindowScan(owner, tracker, provider.id(), windowScan(provider, tracker, targets), lease); }
            finally { tracker.scanning.set(false); }
        } catch (RuntimeException unavailable) { /* 保留原始发现结果。 */ }
    }

    private WindowScan windowScan(DesktopPlatformProvider provider, DesktopWindowTracker tracker, List<DesktopTarget> targets) {
        boolean truncated = targets.size() > 512;
        List<DesktopWindowTracker.Discovered> discovered = targets.stream().limit(512)
                .filter(target -> provider.id().equals(target.providerId()))
                .map(target -> new DesktopWindowTracker.Discovered(target, safeTargetIdentity(provider, target))).toList();
        Set<String> present = new java.util.HashSet<>();
        discovered.forEach(item -> present.add(DesktopWindowTracker.key(item.target(), item.surface())));
        Set<String> closedTargets = new java.util.HashSet<>();
        if (!truncated) for (var known : tracker.known(provider.id())) {
            String key = DesktopWindowTracker.key(known.target(), known.surface());
            if (present.contains(key)) continue;
            try { if (provider.targetExists(known.target()).equals(Optional.of(false))) closedTargets.add(key); }
            catch (RuntimeException unknown) { /* 枚举缺失不是窗口关闭证明。 */ }
        }
        return new WindowScan(discovered, closedTargets, service.clock.millis(), truncated);
    }

    private static DesktopSurfaceSnapshot safeTargetIdentity(DesktopPlatformProvider provider, DesktopTarget target) {
        try {
            DesktopSurfaceSnapshot identity = provider.surfaceForTarget(target).orElse(null);
            return identity != null && identity.providerId().equals(target.providerId())
                    && identity.logicalTargetId().equals(target.id())
                    && !identity.runtimeId().isBlank() && !identity.surfaceId().isBlank()
                    && !identity.applicationId().isBlank() && identity.applicationId().equals(target.applicationId())
                    ? identity : null;
        } catch (RuntimeException unavailable) { return null; }
    }

    private void applyWindowScan(DesktopSessionOwner owner, DesktopWindowTracker tracker,
            String providerId, WindowScan value, TrackingLease lease) {
        synchronized (service) {
            service.requireLaunchActive(owner, lease.scope(), lease.workspaceEpoch(), lease.scopeEpoch());
            if (windowTrackers.get(owner) != tracker) return;
            tracker.scan(providerId, value.targets(), value.closed(), value.at(), value.truncated());
        }
    }

    private record WindowScan(List<DesktopWindowTracker.Discovered> targets, Set<String> closed, long at, boolean truncated) { }

}
