package com.javaclaw.desktop.service;

import com.javaclaw.desktop.api.*;
import com.javaclaw.desktop.spi.DesktopPlatformProvider;
import com.javaclaw.desktop.spi.DesktopPlatformSession;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;

/** Sole owner of platform sessions, consent, polling, and workspace isolation. */
public final class DefaultDesktopSessionService implements DesktopSessionService {
    static final long FRAME_STALE_MILLIS = 1_500;
    static final long PENDING_OBSERVATION_MAX_AGE_MILLIS = 120_000;
    static final long COMMITTED_OBSERVATION_MAX_AGE_MILLIS = 60_000;
    static final long UNKNOWN_SETTLE_MILLIS = 150;
    static final long FOREGROUND_LEASE_SECONDS = 180;
    private static final long LAUNCH_DISCOVERY_MILLIS = 3_000;
    private static final long LAUNCH_DISCOVERY_INTERVAL_MILLIS = 200;
    private final List<DesktopPlatformProvider> providers;
    final DesktopConsentPort consent;
    private final java.util.function.Supplier<DesktopInputPolicy> inputPolicy;
    final DesktopSessionObserver observer;
    final Clock clock;
    final ExecutorService workers;
    final Map<String, ManagedSession> sessions = new ConcurrentHashMap<>();
    /** One native open per owner and exact target, even for concurrent callers. */
    private final Map<SessionKey, CompletableFuture<ManagedSession>> openings = new ConcurrentHashMap<>();
    /** Exact target coordinators serialize input across owners and track intervening actions. */
    final Map<TargetKey, TargetCoordinator> targets = new ConcurrentHashMap<>();
    /** An uncertain input survives closing and reopening a session within its Run scope. */
    final Map<TargetKey, PendingInput> pendingInputs = new ConcurrentHashMap<>();
    /** Idempotent callback replay after durable reconciliation within a live scope. */
    private final Map<TargetKey, ReconciledInput> reconciledInputs = new ConcurrentHashMap<>();
    /** Input baselines are separate from trusted business-effect reconciliation. */
    final Map<TargetKey, RefreshedInput> refreshedInputs = new ConcurrentHashMap<>();
    /** Retain dispatch identity until the tool receives its result, even if the session closes. */
    private final Map<ActionKey, TargetKey> unacknowledgedActions = new ConcurrentHashMap<>();
    /** Platform processes outlive Run scopes; closing a scope must not replay a launch. */
    private final Map<LaunchKey, LaunchAdmission> launchAdmissions = new ConcurrentHashMap<>();
    private final Map<DesktopSessionOwner, DesktopWindowTracker> windowTrackers = new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService windowWatch = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("desktop-window-watch").factory());
    // Incremented when a scope/workspace closes so an in-flight consent dialog cannot
    // publish a session after its owner has gone away. Guarded by this service's monitor.
    private final Map<String, Long> workspaceEpochs = new HashMap<>();
    private final Map<ScopeKey, Long> scopeEpochs = new HashMap<>();
    private volatile boolean closed;

    public DefaultDesktopSessionService(List<DesktopPlatformProvider> providers,
                                        DesktopConsentPort consent) {
        this(providers, consent, Clock.systemUTC(), DesktopSessionObserver.NONE);
    }

    public DefaultDesktopSessionService(List<DesktopPlatformProvider> providers,
                                        DesktopConsentPort consent, Clock clock) {
        this(providers, consent, clock, DesktopSessionObserver.NONE);
    }

    public DefaultDesktopSessionService(List<DesktopPlatformProvider> providers,
                                        DesktopConsentPort consent, Clock clock,
                                        DesktopSessionObserver observer) {
        this(providers, consent, clock, observer, () -> DesktopInputPolicy.BACKGROUND_STRICT);
    }

    public DefaultDesktopSessionService(List<DesktopPlatformProvider> providers,
            DesktopConsentPort consent, Clock clock, DesktopSessionObserver observer,
            java.util.function.Supplier<DesktopInputPolicy> inputPolicy) {
        this.inputPolicy = Objects.requireNonNull(inputPolicy);
        this.providers = List.copyOf(providers);
        this.consent = Objects.requireNonNull(consent);
        this.clock = Objects.requireNonNull(clock);
        this.observer = Objects.requireNonNull(observer);
        this.workers = Executors.newVirtualThreadPerTaskExecutor();
        windowWatch.scheduleWithFixedDelay(this::watchWindows, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override public DesktopInputPolicy defaultInputPolicy() {
        return Objects.requireNonNullElse(inputPolicy.get(), DesktopInputPolicy.BACKGROUND_STRICT);
    }

    @Override public DesktopAvailability availability() { return selection().availability(); }

    @Override public synchronized Optional<List<String>> liveSessionIds(DesktopSessionOwner owner) {
        Objects.requireNonNull(owner, "owner");
        return Optional.of(sessions.values().stream()
                .filter(session -> !session.closed && session.owner.equals(owner))
                .map(session -> session.id).sorted().toList());
    }

    @Override public CompletionStage<List<DesktopTarget>> discoverTargets() {
        return CompletableFuture.supplyAsync(() -> {
            Selection s = selection();
            return s.provider() == null ? List.of() : List.copyOf(s.provider().discoverTargets());
        }, workers);
    }

    @Override public CompletionStage<List<DesktopTarget>> discoverTargets(DesktopSessionOwner owner) {
        Objects.requireNonNull(owner, "owner");
        TrackingLease lease = trackingLease(owner);
        return CompletableFuture.supplyAsync(() -> {
            requireLaunchActive(owner, lease.scope(), lease.workspaceEpoch(), lease.scopeEpoch());
            Selection selected = selection();
            if (selected.provider() == null) return List.of();
            List<DesktopTarget> discovered = List.copyOf(selected.provider().discoverTargets());
            trackDiscovery(owner, selected.provider(), discovered, lease);
            return discovered;
        }, workers);
    }

    @Override public AutoCloseable bindWindowObserver(DesktopSessionOwner owner,
            java.util.function.Consumer<DesktopWindowTrackingEvent> listener) {
        Objects.requireNonNull(owner, "owner"); ensureOpen();
        return windowTrackers.computeIfAbsent(owner, ignored -> new DesktopWindowTracker(workers)).bind(listener);
    }

    @Override public DesktopWindowTrackingSnapshot snapshotWindowTracking(DesktopSessionOwner owner,
            long afterSequence, int limit) {
        Objects.requireNonNull(owner, "owner");
        if (afterSequence < 0 || limit < 1 || limit > 500)
            throw new IllegalArgumentException("invalid window history bounds");
        DesktopWindowTracker tracker = windowTrackers.get(owner);
        return tracker == null ? new DesktopWindowTrackingSnapshot(List.of(), 0, afterSequence, false, false)
                : tracker.snapshot(afterSequence, limit);
    }

    @Override public void beginWindowAction(DesktopSessionOwner owner, String sessionId,
            String invocationId, String observationId) {
        ManagedSession session = owned(owner, sessionId);
        if (invocationId == null || invocationId.isBlank() || observationId == null || observationId.isBlank()) return;
        try {
            DesktopWindowTracker tracker = windowTrackers.computeIfAbsent(owner, ignored -> new DesktopWindowTracker(workers));
            // Optional pre-action inventory is bounded and cannot change input admission.
            DesktopPlatformProvider provider = provider(session.target.providerId());
            if (provider != null) refreshWindowTracking(owner, tracker, provider, 200);
            session.windowActionSource(observationId).ifPresent(surface -> tracker.sourceBegin(sessionId,
                    invocationId, observationId, session.target.id(), surface));
        } catch (RuntimeException unavailable) { /* Input admission remains the responsibility of perform. */ }
    }

    @Override public void finishWindowAction(DesktopSessionOwner owner, String sessionId,
            String invocationId, DesktopActionResult result) {
        DesktopWindowTracker tracker = windowTrackers.get(owner);
        if (tracker == null || result == null || invocationId == null) return;
        if (tracker.finish(sessionId, invocationId, result)) try {
            ManagedSession session = sessions.get(sessionId);
            if (session != null && session.owner.equals(owner)) queueWindowTracking(owner, tracker, session);
        } catch (RuntimeException unavailable) { /* Preserve the already settled physical action result. */ }
    }

    @Override public CompletionStage<DesktopWindowCandidates> discoverWindowCandidates(DesktopSessionOwner owner,
            String sessionId, String invocationId, long waitMillis) {
        if (waitMillis < 0 || waitMillis > 3_000) throw new IllegalArgumentException("window wait must be 0..3000ms");
        requireEnabled();
        ManagedSession session = owned(owner, sessionId);
        TrackingLease lease = trackingLease(owner);
        return CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime(), minimumInventoryAt = clock.millis();
            long deadline = started + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(250, waitMillis));
            DesktopWindowTracker tracker = windowTrackers.computeIfAbsent(owner, ignored -> new DesktopWindowTracker(workers));
            DesktopPlatformProvider provider = provider(session.target.providerId());
            DesktopWindowCandidates result;
            do {
                requireLaunchActive(owner, lease.scope(), lease.workspaceEpoch(), lease.scopeEpoch());
                session.requireOpen();
                long remaining = Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                if (provider != null) refreshWindowTracking(owner, tracker, provider, Math.min(250, remaining), lease);
                // Discovery identities are immutable and do not take the session's native-input
                // monitor. A capture/perform in flight must not extend this bounded readonly wait.
                DesktopSurfaceSnapshot identity = session.windowInventoryIdentity().orElseGet(() ->
                        provider == null ? null : safeTargetIdentity(provider, session.target));
                long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                result = tracker.candidates(sessionId, session.target.id(), identity, invocationId,
                        clock.millis(), elapsed, System.nanoTime() >= deadline);
                if (result.observedAtMillis() < minimumInventoryAt)
                    result = new DesktopWindowCandidates(result.sessionId(), result.sourceTargetId(), result.sourceInvocationId(),
                            List.of(), result.observedAtMillis(), result.waitedMillis(), result.timedOut(), false, result.truncated());
                if (waitMillis == 0 || result.candidates().stream().anyMatch(DesktopWindowCandidate::observedAfterAction)
                        || System.nanoTime() >= deadline) {
                    requireLaunchActive(owner, lease.scope(), lease.workspaceEpoch(), lease.scopeEpoch());
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
        }, workers);
    }

    private DesktopPlatformProvider provider(String id) {
        return providers.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
    }

    private TrackingLease trackingLease(DesktopSessionOwner owner) {
        synchronized (this) {
            ensureOpen(); requireEnabled();
            ScopeKey scope = new ScopeKey(owner.workspaceId(), owner.scopeId());
            return new TrackingLease(scope, workspaceEpochs.getOrDefault(owner.workspaceId(), 0L),
                    scopeEpochs.getOrDefault(scope, 0L));
        }
    }

    private void watchWindows() {
        if (closed) return;
        try {
            Map<DesktopSessionOwner, ManagedSession> active = new HashMap<>();
            sessions.values().stream().filter(session -> !session.closed)
                    .forEach(session -> active.putIfAbsent(session.owner, session));
            active.forEach((owner, session) -> {
                DesktopWindowTracker tracker = windowTrackers.computeIfAbsent(owner, ignored -> new DesktopWindowTracker(workers));
                long now = System.nanoTime();
                if (now < tracker.nextWatchAtNanos) return;
                tracker.nextWatchAtNanos = now + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
                queueWindowTracking(owner, tracker, session);
            });
        } catch (RuntimeException unavailable) { /* Supplemental history cannot stop session capture. */ }
    }

    private void queueWindowTracking(DesktopSessionOwner owner, DesktopWindowTracker tracker, ManagedSession session) {
        try {
            synchronized (this) {
                if (closed || sessions.get(session.id) != session || session.closed || !session.owner.equals(owner)) return;
                TrackingLease lease = trackingLease(owner);
                DesktopPlatformProvider selected = provider(session.target.providerId());
                if (selected != null) workers.execute(() -> refreshWindowTracking(owner, tracker, selected, 500, lease));
            }
        } catch (RuntimeException unavailable) { /* Optional late history never changes an input outcome. */ }
    }

    private boolean refreshWindowTracking(DesktopSessionOwner owner, DesktopWindowTracker tracker,
            DesktopPlatformProvider provider, long timeoutMillis) {
        TrackingLease lease;
        try { lease = trackingLease(owner); }
        catch (RuntimeException unavailable) { return false; }
        return refreshWindowTracking(owner, tracker, provider, timeoutMillis, lease);
    }

    private boolean refreshWindowTracking(DesktopSessionOwner owner, DesktopWindowTracker tracker,
            DesktopPlatformProvider provider, long timeoutMillis, TrackingLease lease) {
        if (!tracker.scanning.compareAndSet(false, true)) return false;
        try { requireLaunchActive(owner, lease.scope(), lease.workspaceEpoch(), lease.scopeEpoch()); }
        catch (RuntimeException unavailable) { tracker.scanning.set(false); return false; }
        CompletableFuture<WindowScan> scan;
        try {
            scan = CompletableFuture.supplyAsync(() -> windowScan(provider, tracker,
                    List.copyOf(provider.discoverTargets())), workers);
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
            // A late readonly enumeration is not published as a fresh before/after sample.
            // Keep its single-flight lease until the actual native read exits.
            scan.whenComplete((ignored, failure) -> tracker.scanning.set(false));
            return false;
        }
    }

    private void trackDiscovery(DesktopSessionOwner owner, DesktopPlatformProvider provider,
            List<DesktopTarget> targets, TrackingLease lease) {
        try {
            DesktopWindowTracker tracker = windowTrackers.computeIfAbsent(owner, ignored -> new DesktopWindowTracker(workers));
            if (!tracker.scanning.compareAndSet(false, true)) return;
            try { applyWindowScan(owner, tracker, provider.id(), windowScan(provider, tracker, targets), lease); }
            finally { tracker.scanning.set(false); }
        } catch (RuntimeException unavailable) { /* Preserve the original discovery result. */ }
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
            catch (RuntimeException unknown) { /* Enumeration absence is not a close proof. */ }
        }
        return new WindowScan(discovered, closedTargets, clock.millis(), truncated);
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
        synchronized (this) {
            requireLaunchActive(owner, lease.scope(), lease.workspaceEpoch(), lease.scopeEpoch());
            if (windowTrackers.get(owner) != tracker) return;
            tracker.scan(providerId, value.targets(), value.closed(), value.at(), value.truncated());
        }
    }

    private record TrackingLease(ScopeKey scope, long workspaceEpoch, long scopeEpoch) { }
    private record WindowScan(List<DesktopWindowTracker.Discovered> targets, Set<String> closed, long at, boolean truncated) { }

    @Override public CompletionStage<DesktopApplicationCatalog> discoverApplications(DesktopSessionOwner owner) {
        Objects.requireNonNull(owner, "owner");
        ScopeKey scope = new ScopeKey(owner.workspaceId(), owner.scopeId());
        long workspaceEpoch;
        long scopeEpoch;
        synchronized (this) {
            ensureOpen();
            requireEnabled(DesktopConsentPort.Purpose.OBSERVE);
            workspaceEpoch = workspaceEpochs.getOrDefault(owner.workspaceId(), 0L);
            scopeEpoch = scopeEpochs.getOrDefault(scope, 0L);
        }
        return CompletableFuture.supplyAsync(() -> {
            requireLaunchActive(owner, scope, workspaceEpoch, scopeEpoch);
            Selection selected = selection();
            if (selected.provider() == null)
                throw new IllegalStateException(selected.availability().detail());
            DesktopApplicationCatalog result = selected.provider().discoverApplications();
            requireLaunchActive(owner, scope, workspaceEpoch, scopeEpoch);
            return result;
        }, workers);
    }

    @Override public CompletionStage<DesktopApplicationLaunchResult> launchApplication(
            DesktopSessionOwner owner, String application) {
        if (owner == null) throw new DesktopApplicationLaunchRejectedException(
                DesktopApplicationLaunchRejectedException.Reason.INVALID_ARGUMENTS,
                "启动应用需要有效的工作区与运行来源");
        String name = validApplicationName(application);
        ScopeKey scope = new ScopeKey(owner.workspaceId(), owner.scopeId());
        long workspaceEpoch;
        long scopeEpoch;
        synchronized (this) {
            requireLaunchReady();
            workspaceEpoch = workspaceEpochs.getOrDefault(owner.workspaceId(), 0L);
            scopeEpoch = scopeEpochs.getOrDefault(scope, 0L);
        }
        return submitLaunch(() -> {
            Selection selected = admitLaunch(owner, scope, workspaceEpoch, scopeEpoch);
            LaunchIdentity identity = resolveLaunchIdentity(selected.provider(), name);
            String canonicalId = identity.applicationId();
            LaunchKey key = new LaunchKey(selected.provider().id(), canonicalId.isBlank()
                    ? "unresolved-provider"
                    : "application:" + normalizeApplicationIdentity(canonicalId));
            // Unsupported or truncated directories cannot make separate aliases
            // safe launch identities. They share one conservative provider fence.
            if (!canonicalId.isBlank()) {
                LaunchAdmission unresolved = launchAdmissions.get(
                        new LaunchKey(selected.provider().id(), "unresolved-provider"));
                if (unresolved != null) synchronized (unresolved) {
                    String oldIdentity = unresolved.applicationId;
                    boolean pending = unresolved.preparing || unresolved.uncertain || unresolved.launched != null;
                    // Only an identity captured with the old native admission
                    // can narrow that admission. A later catalog cannot prove
                    // which app an earlier unbound request actually targeted.
                    if (pending && !oldIdentity.isBlank()
                            && normalizeApplicationIdentity(canonicalId).equals(normalizeApplicationIdentity(oldIdentity)))
                        launchAdmissions.putIfAbsent(key, unresolved);
                }
            }
            LaunchAdmission admission = launchAdmissions.computeIfAbsent(key,
                    ignored -> new LaunchAdmission(name, canonicalId));
            synchronized (admission) {
                admission.preparing = false;
                requireLaunchActive(owner, scope, workspaceEpoch, scopeEpoch);
                List<DesktopTarget> current;
                try { current = List.copyOf(selected.provider().discoverTargets()); }
                catch (RuntimeException beforeDispatch) {
                    throw new DesktopApplicationLaunchRejectedException(
                            DesktopApplicationLaunchRejectedException.Reason.PRE_DISPATCH_FAILURE,
                            0, "应用窗口发现失败，本次启动请求尚未派发", beforeDispatch);
                }
                List<DesktopTarget> running = canonicalId.isBlank() ? List.of()
                        : applicationWindows(selected.provider().id(), canonicalId, 0, current);
                if (!running.isEmpty()) {
                    DesktopTarget first = running.getFirst();
                    admission.launched = new DesktopApplicationLaunch(first.processId(), canonicalId,
                            "已发现该应用的现有进程与窗口，未再次启动；不可见或最小化窗口需要恢复后重新发现");
                    admission.applicationId = canonicalId;
                    admission.requestedName = name;
                    admission.uncertain = false;
                    requireLaunchActive(owner, scope, workspaceEpoch, scopeEpoch);
                    return new DesktopApplicationLaunchResult(first.processId(), canonicalId,
                            applicationTargets(selected.provider().id(), canonicalId, 0, running),
                            admission.launched.detail(), false);
                }
                if (admission.uncertain) {
                    DesktopApplicationLaunch prior = admission.launched;
                    throw new DesktopApplicationLaunchUncertainException(
                            "之前的启动派发尚未确认；未再次发送，请先发现应用窗口",
                            prior == null ? 0 : prior.processId(),
                            prior == null ? admission.applicationId : prior.applicationId(), null);
                }
                if (admission.launched != null) {
                    DesktopApplicationLaunch prior = admission.launched;
                    List<DesktopTarget> knownWindows = applicationWindows(selected.provider().id(),
                            prior.applicationId(), prior.processId(), current);
                    List<DesktopTarget> windows = applicationTargets(selected.provider().id(),
                            prior.applicationId(), prior.processId(), knownWindows);
                    Optional<Boolean> alive;
                    try { alive = selected.provider().isLaunchedApplicationRunning(prior); }
                    catch (RuntimeException unavailable) { alive = Optional.empty(); }
                    // Missing windows are not proof of a failed launch. Only an
                    // explicit proof of application termination permits another request;
                    // a launcher PID disappearing is not sufficient after a handoff.
                    if (!knownWindows.isEmpty() || !alive.equals(Optional.of(false))) {
                        if (canonicalId.isBlank()
                                && !normalizeApplicationIdentity(name).equals(normalizeApplicationIdentity(admission.requestedName))
                                && !normalizeApplicationIdentity(name).equals(normalizeApplicationIdentity(admission.applicationId)))
                            throw new DesktopApplicationLaunchRejectedException(
                                    DesktopApplicationLaunchRejectedException.Reason.AMBIGUOUS_APPLICATION,
                                    "应用目录不能唯一绑定当前请求；先前启动进程仍需发现，请使用已确认的 applicationId");
                        requireLaunchActive(owner, scope, workspaceEpoch, scopeEpoch);
                        return new DesktopApplicationLaunchResult(prior.processId(),
                                prior.applicationId(), windows,
                                windows.isEmpty() ? "先前的启动已接受，窗口尚未发现；未再次启动"
                                        : "已复用先前启动的应用窗口，未再次启动", false);
                    }
                    admission.launched = null;
                }
                requireLaunchActive(owner, scope, workspaceEpoch, scopeEpoch);
                requireNoUnboundLaunchReplay(selected.provider().id(), canonicalId, admission);
                // Install the uncertainty fence before entering the platform call.
                admission.requestedName = name;
                admission.applicationId = canonicalId;
                admission.uncertain = true;
                DesktopApplicationLaunch launched;
                try { launched = Objects.requireNonNull(selected.provider().launchApplication(
                        identity.launchName().isBlank() ? name : identity.launchName()),
                        "platform returned no launch admission"); }
                catch (DesktopApplicationLaunchRejectedException rejected) {
                    admission.uncertain = false;
                    throw rejected;
                } catch (DesktopApplicationLaunchUncertainException uncertain) {
                    if (!uncertain.applicationId().isBlank()) admission.applicationId = uncertain.applicationId();
                    if (uncertain.processId() > 0) admission.launched = new DesktopApplicationLaunch(
                            uncertain.processId(), uncertain.applicationId(), "启动派发尚未确认");
                    throw uncertain;
                }
                admission.launched = launched;
                if (!launched.applicationId().isBlank()) admission.applicationId = launched.applicationId();
                admission.uncertain = false;
                if (!launched.applicationId().isBlank()) launchAdmissions.putIfAbsent(
                        new LaunchKey(selected.provider().id(),
                                "application:" + normalizeApplicationIdentity(launched.applicationId())), admission);
                return discoverLaunchedApplication(owner, scope, workspaceEpoch, scopeEpoch, selected, launched);
            }
        });
    }

    private DesktopApplicationLaunchResult discoverLaunchedApplication(DesktopSessionOwner owner,
            ScopeKey scope, long workspaceEpoch, long scopeEpoch, Selection selected,
            DesktopApplicationLaunch launched) {
            try {
                long deadline = System.nanoTime() +
                        java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(LAUNCH_DISCOVERY_MILLIS);
                while (true) {
                    requireLaunchActive(owner, scope, workspaceEpoch, scopeEpoch);
                    List<DesktopTarget> targets = applicationTargets(selected.provider().id(),
                            launched.applicationId(), launched.processId(), selected.provider().discoverTargets());
                    if (!targets.isEmpty()) {
                        requireLaunchActive(owner, scope, workspaceEpoch, scopeEpoch);
                        return new DesktopApplicationLaunchResult(
                                launched.processId(), launched.applicationId(), targets,
                                launched.detail());
                    }
                    if (System.nanoTime() >= deadline) {
                        requireLaunchActive(owner, scope, workspaceEpoch, scopeEpoch);
                        return new DesktopApplicationLaunchResult(
                                launched.processId(), launched.applicationId(), List.of(),
                                launched.detail());
                    }
                    try { Thread.sleep(LAUNCH_DISCOVERY_INTERVAL_MILLIS); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new DesktopApplicationLaunchUncertainException(
                                "等待应用窗口时被中断；请先重新发现窗口", launched.processId(),
                                launched.applicationId(), interrupted);
                    }
                }
            } catch (RuntimeException afterLaunch) {
                if (afterLaunch instanceof DesktopApplicationLaunchUncertainException) throw afterLaunch;
                throw new DesktopApplicationLaunchUncertainException(
                        "启动请求可能已生效；窗口发现未确认，请先重新发现窗口",
                        launched.processId(), launched.applicationId(), afterLaunch);
            }
    }

    private static LaunchIdentity resolveLaunchIdentity(DesktopPlatformProvider provider, String requested) {
        DesktopApplicationCatalog catalog;
        try { catalog = provider.discoverApplications(); }
        catch (UnsupportedOperationException legacyProvider) { return new LaunchIdentity("", "", null); }
        catch (RuntimeException beforeDispatch) {
            throw new DesktopApplicationLaunchRejectedException(
                    DesktopApplicationLaunchRejectedException.Reason.PRE_DISPATCH_FAILURE,
                    0, "已安装应用身份发现失败，本次启动请求尚未派发", beforeDispatch);
        }
        return resolveLaunchIdentity(catalog, requested);
    }

    private static LaunchIdentity resolveLaunchIdentity(DesktopApplicationCatalog catalog, String requested) {
        String needle = normalizeApplicationIdentity(requested);
        List<DesktopApplicationInfo> matches = catalog.applications().stream()
                .filter(app -> matchesLaunchName(app, needle, !catalog.truncated()))
                .collect(java.util.stream.Collectors.toMap(
                        app -> normalizeApplicationIdentity(app.applicationId()),
                        app -> app, (first, duplicate) -> first)).values().stream().toList();
        if (matches.size() > 1) throw new DesktopApplicationLaunchRejectedException(
                DesktopApplicationLaunchRejectedException.Reason.AMBIGUOUS_APPLICATION,
                "已安装应用名称对应多个身份，请使用目录中的准确 applicationId 或 launchName");
        return matches.isEmpty() ? new LaunchIdentity("", "", catalog)
                : new LaunchIdentity(matches.getFirst().applicationId(), matches.getFirst().launchName(), catalog);
    }

    private static boolean matchesLaunchName(DesktopApplicationInfo app, String normalizedName, boolean aliases) {
        return normalizeApplicationIdentity(app.applicationId()).equals(normalizedName)
                || aliases && (normalizeApplicationIdentity(app.name()).equals(normalizedName)
                    || normalizeApplicationIdentity(app.displayName()).equals(normalizedName)
                    || normalizeApplicationIdentity(app.launchName()).equals(normalizedName)
                    || app.aliases().stream().anyMatch(alias -> normalizeApplicationIdentity(alias).equals(normalizedName)));
    }

    private static String normalizeApplicationIdentity(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .strip().toLowerCase(java.util.Locale.ROOT);
    }

    private void requireNoUnboundLaunchReplay(String providerId, String currentIdentity,
            LaunchAdmission currentAdmission) {
        LaunchAdmission unresolved = launchAdmissions.get(new LaunchKey(providerId, "unresolved-provider"));
        if (unresolved == null || unresolved == currentAdmission) return;
        synchronized (unresolved) {
            if ((unresolved.preparing || unresolved.uncertain)
                    && (unresolved.applicationId.isBlank()
                        || normalizeApplicationIdentity(currentIdentity)
                            .equals(normalizeApplicationIdentity(unresolved.applicationId)))) {
                DesktopApplicationLaunch prior = unresolved.launched;
                throw new DesktopApplicationLaunchUncertainException(
                        "先前启动的真实应用身份或派发尚未确认；本次未再次启动，请先发现现有窗口",
                        prior == null ? 0 : prior.processId(), unresolved.applicationId, null);
            }
        }
    }

    private static List<DesktopTarget> applicationTargets(String providerId, String applicationId,
            long processId, List<DesktopTarget> candidates) {
        return applicationWindows(providerId, applicationId, processId, candidates).stream()
                .filter(target -> target.visible() && !target.minimized()).toList();
    }

    private static List<DesktopTarget> applicationWindows(String providerId, String applicationId,
            long processId, List<DesktopTarget> candidates) {
        return candidates.stream().filter(target -> providerId.equals(target.providerId())
                && target.processId() > 0 && !target.systemSurface()
                && (!applicationId.isBlank()
                    ? normalizeApplicationIdentity(applicationId).equals(normalizeApplicationIdentity(target.applicationId()))
                    : processId > 0 && target.processId() == processId)).toList();
    }

    private record LaunchKey(String providerId, String identity) { }
    private record LaunchIdentity(String applicationId, String launchName, DesktopApplicationCatalog catalog) { }
    private static final class LaunchAdmission {
        DesktopApplicationLaunch launched;
        String requestedName = "";
        String applicationId = "";
        boolean preparing = true;
        boolean uncertain;

        LaunchAdmission(String requestedName, String applicationId) {
            this.requestedName = requestedName;
            this.applicationId = applicationId;
        }
    }

    @Override public CompletionStage<DesktopSessionInfo> open(
            DesktopSessionOwner owner, String targetId, boolean requestControl) {
        return open(owner, targetId, requestControl, DesktopInputPolicy.BACKGROUND_STRICT);
    }

    @Override public CompletionStage<DesktopSessionInfo> open(DesktopSessionOwner owner,
            String targetId, boolean requestControl, DesktopInputPolicy policy) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(targetId);
        Objects.requireNonNull(policy);
        SessionKey key = new SessionKey(owner, targetId);
        long workspaceEpoch;
        long scopeEpoch;
        ScopeKey scope = new ScopeKey(owner.workspaceId(), owner.scopeId());
        synchronized (this) {
            ensureOpen();
            requireEnabled(DesktopConsentPort.Purpose.OBSERVE);
            if (requestControl) requireControlEnabled(policy);
            workspaceEpoch = workspaceEpochs.getOrDefault(owner.workspaceId(), 0L);
            scopeEpoch = scopeEpochs.getOrDefault(scope, 0L);
        }
        CompletableFuture<ManagedSession> opening = openings.computeIfAbsent(key, ignored ->
                CompletableFuture.supplyAsync(() -> openOrReuse(owner, targetId, requestControl, policy,
                        scope, workspaceEpoch, scopeEpoch), workers));
        opening.whenComplete((ignored, failure) -> openings.remove(key, opening));
        return opening.thenApplyAsync(session -> ensureAccess(session, requestControl, policy,
                scope, workspaceEpoch, scopeEpoch), workers);
    }

    private ManagedSession openOrReuse(DesktopSessionOwner owner, String targetId,
            boolean requestControl, DesktopInputPolicy policy, ScopeKey scope,
            long workspaceEpoch, long scopeEpoch) {
        ensureOpen();
        requireEnabled(DesktopConsentPort.Purpose.OBSERVE);
        if (requestControl) requireControlEnabled(policy);
        ManagedSession existing = sessions.values().stream()
                .filter(session -> !session.closed && session.owner.equals(owner)
                        && session.target.id().equals(targetId))
                .findFirst().orElse(null);
        if (existing != null) {
            if (existing.inputPolicy != policy)
                throw new IllegalStateException("会话输入策略不可变；请关闭当前会话后按宿主设置重新打开，并先核验已有输入");
            if (!consent.request(owner, existing.target, DesktopConsentPort.Purpose.OBSERVE))
                throw new SecurityException("用户未授权观察该目标");
            return existing;
        }
        Selection s = selection();
        if (s.provider() == null) throw new IllegalStateException(s.availability().detail());
        DesktopTarget target = s.provider().discoverTargets().stream()
                .filter(t -> t.id().equals(targetId)
                        && t.providerId().equals(s.provider().id())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("目标已不存在，请重新发现窗口"));
        if (!consent.request(owner, target, DesktopConsentPort.Purpose.OBSERVE))
            throw new SecurityException("用户未授权观察该目标");
        if (requestControl && !consent.request(owner, target, controlPurpose(policy)))
            throw new SecurityException("用户未授权控制该目标");
        requireEnabled(DesktopConsentPort.Purpose.OBSERVE);
        if (requestControl) requireControlEnabled(policy);
        DesktopPlatformSession platform = s.provider().open(target);
        ManagedSession session = new ManagedSession(this, owner, target, requestControl, policy, platform);
        try {
            requireSameTarget(target, session.platform.currentTarget());
            if (requestControl && policy == DesktopInputPolicy.SYSTEM_EXPLICIT) {
                synchronized (session.coordinator) { requireInitialSystemInput(session); }
            }
            DesktopSessionInfo info = session.info();
            synchronized (DefaultDesktopSessionService.this) {
                if (closed
                        || !consent.accessStatus(DesktopConsentPort.Purpose.OBSERVE).available()
                        || (requestControl && !consent.accessStatus(
                                controlPurpose(policy)).available())
                        || workspaceEpochs.getOrDefault(owner.workspaceId(), 0L) != workspaceEpoch
                        || scopeEpochs.getOrDefault(scope, 0L) != scopeEpoch)
                    throw new IllegalStateException("桌面会话所属工作区或运行作用域已关闭");
                sessions.put(session.id, session);
                try {
                    DesktopSurfaceSnapshot identity = safeTargetIdentity(s.provider(), info.target());
                    if (identity != null) session.windowIdentity = identity;
                    windowTrackers.computeIfAbsent(owner, ignored -> new DesktopWindowTracker(workers))
                            .opened(info.target(), identity, session.id, clock.millis());
                } catch (RuntimeException unavailable) { /* History cannot change a successful session admission. */ }
                observer.opened(owner, info, this);
                workers.execute(session::poll);
            }
        } catch (RuntimeException | Error failure) {
            sessions.remove(session.id, session);
            session.close();
            throw failure;
        }
        return session;
    }

    private DesktopSessionInfo ensureAccess(ManagedSession session, boolean requestControl,
            DesktopInputPolicy policy, ScopeKey scope, long workspaceEpoch, long scopeEpoch) {
        requireEnabled(DesktopConsentPort.Purpose.OBSERVE);
        if (requestControl) requireControlEnabled(policy);
        session.requireOpen();
        if (session.inputPolicy != policy)
            throw new IllegalStateException("会话输入策略不可变，请关闭后重新打开");
        requireSameTarget(session.target, session.platform.currentTarget());
        if (requestControl && (!session.controlGranted
                || policy == DesktopInputPolicy.SYSTEM_EXPLICIT && !session.foregroundGranted)
                && !consent.request(session.owner, session.target, controlPurpose(policy)))
            throw new SecurityException("用户未授权控制该目标");
        synchronized (this) {
            if (closed || session.closed || sessions.get(session.id) != session
                    || workspaceEpochs.getOrDefault(session.owner.workspaceId(), 0L) != workspaceEpoch
                    || scopeEpochs.getOrDefault(scope, 0L) != scopeEpoch)
                throw new IllegalStateException("桌面会话所属工作区或运行作用域已关闭");
            requireEnabled(DesktopConsentPort.Purpose.OBSERVE);
            if (requestControl) requireControlEnabled(policy);
            synchronized (session.coordinator) {
              synchronized (session) {
                if (requestControl && policy == DesktopInputPolicy.SYSTEM_EXPLICIT
                        && !session.foregroundGranted) {
                    requireInitialSystemInput(session);
                    session.foregroundGranted = true;
                }
                if (requestControl && !session.controlGranted) {
                    session.controlGranted = true;
                    session.pendingObservation = null;
                    session.committedObservation = null;
                    session.state(DesktopSessionState.Kind.PAUSED,
                            "会话已获得控制能力；输入前请重新观察目标窗口");
                }
                return session.info();
              }
            }
        }
    }

    @Override public CompletionStage<Optional<DesktopFrame>> snapshot(
            DesktopSessionOwner owner, String sessionId) {
        requireEnabled();
        ManagedSession session = owned(owner, sessionId);
        return CompletableFuture.supplyAsync(() -> {
            requireEnabled();
            session.requireOpen();
            DesktopFrame frame = session.latest;
            if (frame == null || clock.millis() - frame.capturedAtMillis() > FRAME_STALE_MILLIS)
                return Optional.empty();
            return Optional.of(frame);
        }, workers);
    }

    @Override public CompletionStage<Optional<DesktopObservation>> captureObservation(
            DesktopSessionOwner owner, String sessionId) {
        return captureObservation(owner, sessionId, -1);
    }

    @Override public CompletionStage<Optional<DesktopObservation>> captureObservation(
            DesktopSessionOwner owner, String sessionId, long capturedAfterMillis) {
        requireEnabled();
        ManagedSession session = owned(owner, sessionId);
        CompletableFuture<Optional<DesktopObservation>> capture = new CompletableFuture<>();
        workers.execute(() -> {
            Optional<DesktopObservation> observation;
            try {
                synchronized (session.coordinator) {
                    if (capture.isCancelled()) return;
                    observation = session.captureObservation(capturedAfterMillis);
                }
            } catch (Throwable failure) {
                capture.completeExceptionally(failure);
                return;
            }
            // Complete outside the coordinator: caller continuations may take service locks.
            // A cancelled catalog read must not leave a late pending observation. Matching the
            // ID also preserves a newer capture that won the coordinator in the meantime.
            if (!capture.complete(observation)) synchronized (session.coordinator) {
                observation.ifPresent(value -> session.discardPendingObservation(value.observationId()));
            }
        });
        return capture;
    }

    @Override public CompletionStage<Boolean> commitObservation(
            DesktopSessionOwner owner, String sessionId, String observationId) {
        requireEnabled();
        ManagedSession session = owned(owner, sessionId);
        // The legacy three-argument call has no proof that the captured pixels were
        // successfully interpreted. In particular it cannot unlock UNKNOWN input.
        return CompletableFuture.supplyAsync(() -> {
            synchronized (session.coordinator) {
                return session.commitObservation(observationId, List.of(), false);
            }
        }, workers);
    }

    @Override public CompletionStage<Boolean> commitObservation(
            DesktopSessionOwner owner, String sessionId, String observationId,
            List<DesktopVisualRegion> visualRegions) {
        requireEnabled();
        ManagedSession session = owned(owner, sessionId);
        List<DesktopVisualRegion> submitted = List.copyOf(visualRegions);
        return CompletableFuture.supplyAsync(() -> {
            synchronized (session.coordinator) {
                return session.commitObservation(observationId, submitted, true);
            }
        }, workers);
    }

    @Override public CompletionStage<Void> releaseForeground(
            DesktopSessionOwner owner, String sessionId) {
        return releaseForegroundChecked(owner, sessionId, null);
    }

    @Override public CompletionStage<Void> releaseForeground(
            DesktopSessionOwner owner, String sessionId, String observationId) {
        return releaseForegroundChecked(owner, sessionId, Objects.requireNonNull(observationId));
    }

    private CompletionStage<Void> releaseForegroundChecked(
            DesktopSessionOwner owner, String sessionId, String observationId) {
        ManagedSession session = owned(owner, sessionId);
        return CompletableFuture.runAsync(() -> {
            synchronized (session.coordinator) {
                synchronized (session) {
                    if (observationId != null) {
                        DesktopObservation newest = session.pendingObservation != null
                                ? session.pendingObservation : session.committedObservation;
                        if (observationId.isBlank() ? newest != null
                                : newest == null || !observationId.equals(newest.observationId())) return;
                    }
                    try { session.endForegroundLease(); }
                    finally {
                        session.pendingObservation = null;
                        session.committedObservation = null;
                        if (session.foregroundGranted && !session.closed) {
                            session.state(DesktopSessionState.Kind.FOREGROUND_READY,
                                    "前台观察已结束；下次操作前请重新观察");
                        }
                    }
                }
            }
        }, workers);
    }

    @Override public CompletionStage<DesktopActionResult> perform(
            DesktopSessionOwner owner, String sessionId, DesktopAction action) {
        Objects.requireNonNull(action);
        ManagedSession session = owned(owner, sessionId);
        try { requireControlEnabled(session.inputPolicy); }
        catch (SecurityException denied) {
            session.rejected(action);
            throw denied;
        }
        unacknowledgedActions.putIfAbsent(new ActionKey(owner, sessionId,
                action.observationId()), session.targetKey);
        return CompletableFuture.supplyAsync(() -> performTracked(session, action, false, null), workers);
    }

    private DesktopActionResult performTracked(ManagedSession session, DesktopAction action,
            boolean manualAuthorized, String manualLease) {
        DesktopActionResult result;
        DesktopWindowTracker refresh = null;
        synchronized (session.coordinator) {
            if (manualAuthorized) {
                try {
                    session.requireOpen();
                    requireManualLease(session, session.owner, manualLease);
                } catch (RuntimeException denied) {
                    session.rejected(action);
                    throw denied;
                }
            }
            result = session.perform(action, manualAuthorized);
            DesktopWindowTracker tracker = windowTrackers.get(session.owner);
            if (tracker != null) try {
                if (tracker.actualOutcome(session.id, action.observationId(), result, clock.millis()))
                    refresh = tracker;
            } catch (RuntimeException unavailable) { /* Tracking never changes physical input outcome. */ }
        }
        // Closing a session takes the service monitor before its target coordinator.
        // Supplemental discovery must acquire the service monitor only after releasing input.
        if (refresh != null) queueWindowTracking(session.owner, refresh, session);
        return result;
    }

    @Override public CompletionStage<String> acquireManualControl(
            DesktopSessionOwner owner, String sessionId) {
        ManagedSession session = owned(owner, sessionId);
        return CompletableFuture.supplyAsync(() -> {
            synchronized (session.coordinator) {
                session.requireOpen();
                requireControlEnabled(session.inputPolicy);
                if (!session.controlGranted) throw new SecurityException("会话没有控制权限");
                if (session.coordinator.manualLease != null)
                    throw new IllegalStateException("该目标已有人工输入面板");
                String token = java.util.UUID.randomUUID().toString();
                session.coordinator.manualLease = new ManualLease(owner, sessionId, token);
                session.coordinator.actionEpoch++;
                synchronized (session) {
                    session.pendingObservation = null;
                    session.committedObservation = null;
                }
                return token;
            }
        }, workers);
    }

    @Override public CompletionStage<DesktopActionResult> performManual(
            DesktopSessionOwner owner, String sessionId, String lease, DesktopAction action) {
        ManagedSession session = owned(owner, sessionId);
        Objects.requireNonNull(action);
        try { requireControlEnabled(session.inputPolicy); }
        catch (SecurityException denied) {
            session.rejected(action);
            throw denied;
        }
        unacknowledgedActions.putIfAbsent(new ActionKey(owner, sessionId,
                action.observationId()), session.targetKey);
        return CompletableFuture.supplyAsync(() -> performTracked(session, action, true, lease), workers);
    }

    @Override public CompletionStage<Void> releaseManualControl(
            DesktopSessionOwner owner, String sessionId, String lease) {
        ManagedSession session = owned(owner, sessionId);
        return CompletableFuture.runAsync(() -> {
            synchronized (session.coordinator) {
                session.requireOpen();
                requireManualLease(session, owner, lease);
                session.coordinator.manualLease = null;
                session.coordinator.actionEpoch++;
                synchronized (session) {
                    session.pendingObservation = null;
                    session.committedObservation = null;
                }
            }
        }, workers);
    }

    private static void requireManualLease(ManagedSession session, DesktopSessionOwner owner,
            String token) {
        ManualLease lease = session.coordinator.manualLease;
        if (lease == null || !lease.owner().equals(owner) || !lease.sessionId().equals(session.id)
                || !lease.token().equals(token))
            throw new SecurityException("人工输入租约已失效或不属于该会话");
    }

    @Override public void markDeliveryUncertain(DesktopSessionOwner owner, String sessionId,
                                                String actionObservationId) {
        if (actionObservationId == null || actionObservationId.isBlank())
            throw new IllegalArgumentException("action observation ID is required");
        ActionKey action = new ActionKey(owner, sessionId, actionObservationId);
        TargetKey targetKey = unacknowledgedActions.get(action);
        if (targetKey == null)
            throw new IllegalStateException("started desktop action is no longer available");
        // Do not acquire the session or target lock: native input may still hold both.
        // The caller has lost its result, so even a later VERIFIED native return must
        // not allow another Run to treat this action as safely retryable.
        pendingInputs.putIfAbsent(targetKey,
                new PendingInput(owner, actionObservationId, clock.millis()));
        unacknowledgedActions.remove(action, targetKey);
    }

    @Override public void acknowledgeActionResult(DesktopSessionOwner owner, String sessionId,
                                                  String actionObservationId) {
        unacknowledgedActions.remove(new ActionKey(owner, sessionId, actionObservationId));
    }

    @Override public CompletionStage<Boolean> reconcilePendingAction(
            DesktopSessionOwner owner, String sessionId, String actionObservationId,
            String evidenceObservationId) {
        ManagedSession session = owned(owner, sessionId);
        return CompletableFuture.supplyAsync(() -> {
            synchronized (session.coordinator) {
                if (session.reconcilePendingAction(actionObservationId, evidenceObservationId)) {
                    refreshedInputs.remove(session.targetKey);
                    reconciledInputs.put(session.targetKey, new ReconciledInput(owner,
                            actionObservationId, evidenceObservationId));
                    return true;
                }
                RefreshedInput refreshed = refreshedInputs.get(session.targetKey);
                if (!pendingInputs.containsKey(session.targetKey) && refreshed != null
                        && refreshed.observer().equals(owner)
                        && refreshed.pending().actionObservationId().equals(actionObservationId)
                        && refreshed.evidenceObservationId().equals(evidenceObservationId)) {
                    requireSameTarget(session.target, session.platform.currentTarget());
                    if (!refreshedInputs.remove(session.targetKey, refreshed)) return false;
                    // Only this explicit trusted-verifier call establishes effect
                    // reconciliation; baseline refresh alone never does so.
                    reconciledInputs.put(session.targetKey, new ReconciledInput(owner,
                            actionObservationId, evidenceObservationId));
                    return true;
                }
                ReconciledInput prior = reconciledInputs.get(session.targetKey);
                return !pendingInputs.containsKey(session.targetKey) && prior != null
                        && prior.owner().equals(owner)
                        && prior.actionObservationId().equals(actionObservationId)
                        && prior.evidenceObservationId().equals(evidenceObservationId);
            }
        }, workers);
    }

    @Override public DesktopSessionState state(DesktopSessionOwner owner, String sessionId) {
        requireEnabled();
        ManagedSession session = owned(owner, sessionId);
        return session.currentState();
    }

    @Override public CompletionStage<Boolean> authorizeForeground(
            DesktopSessionOwner owner, String sessionId) {
        return authorizeForeground(owner, sessionId, false);
    }

    @Override public CompletionStage<Boolean> authorizeSystemInput(
            DesktopSessionOwner owner, String sessionId) {
        return authorizeForeground(owner, sessionId, true);
    }

    private CompletionStage<Boolean> authorizeForeground(
            DesktopSessionOwner owner, String sessionId, boolean initialSystemInput) {
        ManagedSession session = owned(owner, sessionId);
        if (session.inputPolicy != DesktopInputPolicy.SYSTEM_EXPLICIT)
            return CompletableFuture.completedFuture(false);
        requireEnabled(DesktopConsentPort.Purpose.FOREGROUND_TAKEOVER);
        return CompletableFuture.supplyAsync(() -> {
            requireEnabled(DesktopConsentPort.Purpose.FOREGROUND_TAKEOVER);
            session.requireOpen();
            if (!session.controlGranted) return false;
            if (initialSystemInput) {
                synchronized (session.coordinator) {
                    synchronized (session) { requireInitialSystemInput(session); }
                }
            }
            boolean approved = consent.request(owner, session.target,
                    DesktopConsentPort.Purpose.FOREGROUND_TAKEOVER);
            synchronized (session.coordinator) {
                synchronized (session) {
                    session.requireOpen();
                    if (initialSystemInput) requireInitialSystemInput(session);
                    if (approved && !session.closed) {
                        session.foregroundGranted = true;
                        session.committedObservation = null;
                        session.pendingObservation = null;
                        session.state(DesktopSessionState.Kind.FOREGROUND_READY,
                                "前台模拟输入已就绪，请先重新观察目标窗口");
                    }
                    return approved && !session.closed;
                }
            }
        }, workers);
    }

    /** Called under target then session locks, including after external consent returns. */
    private void requireInitialSystemInput(ManagedSession session) {
        session.requireOpen();
        boolean priorBackground = session.coordinator.backgroundInputOwners.stream().anyMatch(prior ->
                prior.workspaceId().equals(session.owner.workspaceId())
                        && prior.scopeId().equals(session.owner.scopeId()));
        if (!session.foregroundGranted && (priorBackground || pendingInputs.containsKey(session.targetKey)))
            throw new IllegalStateException("该窗口在本任务内已有后台输入或结果未知的操作；"
                    + "禁止自动改用系统键鼠重试，请先核验已有操作或开始新任务");
    }

    @Override public Flow.Publisher<DesktopFrame> frames(DesktopSessionOwner owner, String sessionId) {
        requireEnabled();
        return owned(owner, sessionId).frames;
    }

    @Override public Flow.Publisher<DesktopVirtualInputState> virtualInputs(
            DesktopSessionOwner owner, String sessionId) {
        requireEnabled();
        return owned(owner, sessionId).virtualInputs;
    }

    @Override public Flow.Publisher<DesktopSessionState> states(
            DesktopSessionOwner owner, String sessionId) {
        requireEnabled();
        return owned(owner, sessionId).states;
    }

    @Override public Flow.Publisher<DesktopActionEvent> actions(
            DesktopSessionOwner owner, String sessionId) {
        requireEnabled();
        return owned(owner, sessionId).actions;
    }

    @Override public DesktopSessionInfo info(DesktopSessionOwner owner, String sessionId) {
        requireEnabled();
        return owned(owner, sessionId).info();
    }

    @Override public Optional<DesktopSurfaceSnapshot> surface(DesktopSessionOwner owner, String sessionId) {
        requireEnabled();
        return owned(owner, sessionId).platform.currentSurface();
    }

    @Override public synchronized void closeSession(DesktopSessionOwner owner, String sessionId) {
        ManagedSession session = owned(owner, sessionId);
        if (sessions.remove(session.id, session)) session.close();
    }

    @Override public synchronized void closeScope(String workspaceId, String scopeId) {
        scopeEpochs.merge(new ScopeKey(workspaceId, scopeId), 1L, Long::sum);
        closeMatching(s -> s.owner.workspaceId().equals(workspaceId)
                && s.owner.scopeId().equals(scopeId));
        targets.values().forEach(coordinator -> coordinator.backgroundInputOwners.removeIf(owner ->
                owner.workspaceId().equals(workspaceId) && owner.scopeId().equals(scopeId)));
        pendingInputs.entrySet().removeIf(entry ->
                entry.getValue().owner.workspaceId().equals(workspaceId)
                        && entry.getValue().owner.scopeId().equals(scopeId));
        reconciledInputs.entrySet().removeIf(entry ->
                entry.getValue().owner().workspaceId().equals(workspaceId)
                        && entry.getValue().owner().scopeId().equals(scopeId));
        refreshedInputs.entrySet().removeIf(entry ->
                (entry.getValue().pending().owner().workspaceId().equals(workspaceId)
                        && entry.getValue().pending().owner().scopeId().equals(scopeId))
                        || (entry.getValue().observer().workspaceId().equals(workspaceId)
                        && entry.getValue().observer().scopeId().equals(scopeId)));
        unacknowledgedActions.keySet().removeIf(key ->
                key.owner().workspaceId().equals(workspaceId)
                        && key.owner().scopeId().equals(scopeId));
        windowTrackers.forEach((owner, tracker) -> {
            if (owner.workspaceId().equals(workspaceId) && owner.scopeId().equals(scopeId)) tracker.detach();
        });
    }

    @Override public synchronized void closeWorkspace(String workspaceId) {
        workspaceEpochs.merge(workspaceId, 1L, Long::sum);
        closeMatching(s -> s.owner.workspaceId().equals(workspaceId));
        targets.values().forEach(coordinator -> coordinator.backgroundInputOwners.removeIf(owner ->
                owner.workspaceId().equals(workspaceId)));
        pendingInputs.entrySet().removeIf(entry ->
                entry.getValue().owner.workspaceId().equals(workspaceId));
        reconciledInputs.entrySet().removeIf(entry ->
                entry.getValue().owner().workspaceId().equals(workspaceId));
        refreshedInputs.entrySet().removeIf(entry ->
                entry.getValue().pending().owner().workspaceId().equals(workspaceId)
                        || entry.getValue().observer().workspaceId().equals(workspaceId));
        unacknowledgedActions.keySet().removeIf(key ->
                key.owner().workspaceId().equals(workspaceId));
        windowTrackers.entrySet().removeIf(entry -> {
            if (!entry.getKey().workspaceId().equals(workspaceId)) return false;
            entry.getValue().detach(); return true;
        });
    }

    private void closeMatching(java.util.function.Predicate<ManagedSession> predicate) {
        for (ManagedSession session : new ArrayList<>(sessions.values()))
            if (predicate.test(session) && sessions.remove(session.id, session)) session.close();
    }

    @Override public synchronized void close() {
        closed = true;
        closeMatching(s -> true);
        pendingInputs.clear();
        reconciledInputs.clear();
        refreshedInputs.clear();
        unacknowledgedActions.clear();
        targets.clear();
        launchAdmissions.clear();
        windowWatch.shutdownNow();
        windowTrackers.values().forEach(DesktopWindowTracker::detach);
        windowTrackers.clear();
        // Allow already queued completion signals to reach frame/state subscribers.
        workers.shutdown();
    }

    private ManagedSession owned(DesktopSessionOwner owner, String id) {
        Objects.requireNonNull(owner);
        ManagedSession session = sessions.get(id);
        if (session == null || !session.owner.equals(owner))
            throw new SecurityException("桌面会话不存在或不属于当前工作区与运行来源");
        return session;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("desktop service is closed");
    }

    void requireEnabled() {
        requireEnabled(DesktopConsentPort.Purpose.OBSERVE);
    }

    static DesktopConsentPort.Purpose controlPurpose(DesktopInputPolicy policy) {
        return policy == DesktopInputPolicy.SYSTEM_EXPLICIT
                ? DesktopConsentPort.Purpose.FOREGROUND_TAKEOVER : DesktopConsentPort.Purpose.CONTROL;
    }

    void requireControlEnabled(DesktopInputPolicy policy) {
        requireEnabled(controlPurpose(policy));
    }

    void requireEnabled(DesktopConsentPort.Purpose purpose) {
        DesktopAvailability access = consent.accessStatus(purpose);
        if (!access.available()) throw new SecurityException(access.detail());
    }

    static void requireSameTarget(DesktopTarget expected, DesktopTarget current) {
        if (!expected.providerId().equals(current.providerId())
                || !expected.id().equals(current.id())
                || expected.processId() != current.processId()
                || !expected.application().equals(current.application()))
            throw new IllegalStateException("目标窗口身份已变化，请重新发现窗口");
    }

    private synchronized void requireLaunchActive(DesktopSessionOwner owner, ScopeKey scope,
                                                  long workspaceEpoch, long scopeEpoch) {
        ensureOpen();
        requireEnabled();
        if (workspaceEpochs.getOrDefault(owner.workspaceId(), 0L) != workspaceEpoch
                || scopeEpochs.getOrDefault(scope, 0L) != scopeEpoch)
            throw new IllegalStateException("桌面会话所属工作区或运行作用域已关闭");
    }

    private void requireLaunchReady() {
        if (closed) throw new DesktopApplicationLaunchRejectedException(
                DesktopApplicationLaunchRejectedException.Reason.SERVICE_UNAVAILABLE,
                "桌面服务已关闭，启动请求尚未派发");
        DesktopAvailability access = consent.accessStatus(DesktopConsentPort.Purpose.OBSERVE);
        if (!access.available()) throw new DesktopApplicationLaunchRejectedException(
                DesktopApplicationLaunchRejectedException.Reason.ACCESS_DENIED, access.detail());
    }

    private CompletionStage<DesktopApplicationLaunchResult> submitLaunch(
            java.util.function.Supplier<DesktopApplicationLaunchResult> task) {
        try { return CompletableFuture.supplyAsync(task, workers); }
        catch (java.util.concurrent.RejectedExecutionException unavailable) {
            throw new DesktopApplicationLaunchRejectedException(
                    DesktopApplicationLaunchRejectedException.Reason.SERVICE_UNAVAILABLE,
                    0, "桌面启动服务不可用，启动请求尚未派发", unavailable);
        }
    }

    private synchronized Selection admitLaunch(DesktopSessionOwner owner, ScopeKey scope,
            long workspaceEpoch, long scopeEpoch) {
        try {
            requireLaunchReady();
            if (workspaceEpochs.getOrDefault(owner.workspaceId(), 0L) != workspaceEpoch
                    || scopeEpochs.getOrDefault(scope, 0L) != scopeEpoch)
                throw new DesktopApplicationLaunchRejectedException(
                        DesktopApplicationLaunchRejectedException.Reason.SCOPE_CLOSED,
                        "桌面会话所属工作区或运行作用域已关闭，启动请求尚未派发");
            Selection selected = selection();
            if (selected.provider() == null) throw new DesktopApplicationLaunchRejectedException(
                    DesktopApplicationLaunchRejectedException.Reason.UNSUPPORTED,
                    selected.availability().detail());
            return selected;
        } catch (DesktopApplicationLaunchRejectedException rejected) { throw rejected; }
        catch (RuntimeException beforeDispatch) {
            throw new DesktopApplicationLaunchRejectedException(
                    DesktopApplicationLaunchRejectedException.Reason.PRE_DISPATCH_FAILURE,
                    0, "启动请求准备失败，尚未发送到系统", beforeDispatch);
        }
    }

    private static String validApplicationName(String application) {
        if (application == null) throw new DesktopApplicationLaunchRejectedException(
                DesktopApplicationLaunchRejectedException.Reason.INVALID_ARGUMENTS,
                "应用名称或 bundle ID 不能为空");
        String name = application.strip();
        if (name.isEmpty() || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256
                || name.contains("..") || name.indexOf(':') >= 0
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.chars().anyMatch(Character::isISOControl))
            throw new DesktopApplicationLaunchRejectedException(
                    DesktopApplicationLaunchRejectedException.Reason.INVALID_ARGUMENTS,
                    "请输入已安装应用的准确名称或 bundle ID，不能使用路径或命令");
        return name;
    }

    /** Exact pixels in a bounded target and a small surrounding hit area. */
    static boolean samePixels(DesktopFrame observed, DesktopFrame current,
                                      int x, int y, int width, int height) {
        if (width < 1 || height < 1 || x < 0 || y < 0
                || (long) x + width > observed.width()
                || (long) y + height > observed.height()
                || observed.width() != current.width() || observed.height() != current.height())
            return false;
        int left = Math.max(0, x - 8);
        int top = Math.max(0, y - 8);
        int right = Math.min(observed.width(), x + width + 8);
        int bottom = Math.min(observed.height(), y + height + 8);
        byte[] before = observed.bgraPremultiplied();
        byte[] after = current.bgraPremultiplied();
        int rowBytes = (right - left) * 4;
        for (int row = top; row < bottom; row++) {
            int beforeStart = row * observed.stride() + left * 4;
            int afterStart = row * current.stride() + left * 4;
            if (java.util.Arrays.mismatch(before, beforeStart, beforeStart + rowBytes,
                    after, afterStart, afterStart + rowBytes) >= 0)
                return false;
        }
        return true;
    }

    private Selection selection() {
        DesktopAvailability access = consent.accessStatus();
        if (!access.available()) return new Selection(null, access);
        // Permission can be granted or revoked while the process is running. A cached
        // positive probe would make the public availability API lie after revocation.
        List<String> reasons = new ArrayList<>();
        for (DesktopPlatformProvider provider : providers) {
            DesktopAvailability availability;
            try { availability = provider.probe(); }
            catch (RuntimeException | LinkageError e) {
                reasons.add(provider.id() + ": " + e.getMessage());
                continue;
            }
            if (availability.available()) return new Selection(provider, availability);
            reasons.add(provider.id() + ": " + availability.detail());
        }
        return new Selection(null, new DesktopAvailability(false, "", 0,
                reasons.isEmpty() ? "没有已安装的桌面平台实现" : String.join("; ", reasons)));
    }

    private record Selection(DesktopPlatformProvider provider, DesktopAvailability availability) {}
    private record ScopeKey(String workspaceId, String scopeId) {}
    private record SessionKey(DesktopSessionOwner owner, String targetId) {}
    record TargetKey(String providerId, String targetId) {}
    private record ActionKey(DesktopSessionOwner owner, String sessionId,
                             String actionObservationId) {}
    static final class TargetCoordinator {
        long actionEpoch;
        long lastDispatchAtMillis;
        ManualLease manualLease;
        /** Retained across close/reopen in a task, cleared only when its scope ends. */
        final java.util.Set<DesktopSessionOwner> backgroundInputOwners = ConcurrentHashMap.newKeySet();
    }
    record ManualLease(DesktopSessionOwner owner, String sessionId, String token) {}
    record PendingInput(DesktopSessionOwner owner, String actionObservationId,
                        long attemptedAtMillis) {}
    record RefreshedInput(PendingInput pending, DesktopSessionOwner observer,
                          String evidenceObservationId) {}
    private record ReconciledInput(DesktopSessionOwner owner, String actionObservationId,
                                   String evidenceObservationId) {}
}
