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
    final List<DesktopPlatformProvider> providers;
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
    private final DesktopWindowTracking windowTracking;
    private final DesktopApplicationLauncher applications;
    // Incremented when a scope/workspace closes so an in-flight consent dialog cannot
    // publish a session after its owner has gone away. Guarded by this service's monitor.
    final Map<String, Long> workspaceEpochs = new HashMap<>();
    final Map<ScopeKey, Long> scopeEpochs = new HashMap<>();
    volatile boolean closed;

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
        applications = new DesktopApplicationLauncher(this);
        windowTracking = new DesktopWindowTracking(this);
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
            windowTracking.trackDiscovery(owner, selected.provider(), discovered, lease);
            return discovered;
        }, workers);
    }

    @Override public AutoCloseable bindWindowObserver(DesktopSessionOwner owner,
            java.util.function.Consumer<DesktopWindowTrackingEvent> listener) {
        return windowTracking.bindWindowObserver(owner, listener);
    }

    @Override public DesktopWindowTrackingSnapshot snapshotWindowTracking(DesktopSessionOwner owner,
            long afterSequence, int limit) {
        return windowTracking.snapshotWindowTracking(owner, afterSequence, limit);
    }

    @Override public void beginWindowAction(DesktopSessionOwner owner, String sessionId,
            String invocationId, String observationId) {
        windowTracking.beginWindowAction(owner, sessionId, invocationId, observationId);
    }

    @Override public void finishWindowAction(DesktopSessionOwner owner, String sessionId,
            String invocationId, DesktopActionResult result) {
        windowTracking.finishWindowAction(owner, sessionId, invocationId, result);
    }

    @Override public CompletionStage<DesktopWindowCandidates> discoverWindowCandidates(DesktopSessionOwner owner,
            String sessionId, String invocationId, long waitMillis) {
        return windowTracking.discoverWindowCandidates(owner, sessionId, invocationId, waitMillis);
    }

    TrackingLease trackingLease(DesktopSessionOwner owner) {
        synchronized (this) {
            ensureOpen(); requireEnabled();
            ScopeKey scope = new ScopeKey(owner.workspaceId(), owner.scopeId());
            return new TrackingLease(scope, workspaceEpochs.getOrDefault(owner.workspaceId(), 0L),
                    scopeEpochs.getOrDefault(scope, 0L));
        }
    }

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
        return applications.launchApplication(owner, application);
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
                windowTracking.opened(session, s.provider(), info);
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
            refresh = windowTracking.actualOutcome(session, action, result);
        }
        // Closing a session takes the service monitor before its target coordinator.
        // Supplemental discovery must acquire the service monitor only after releasing input.
        if (refresh != null) windowTracking.queueWindowTracking(session.owner, refresh, session);
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
        windowTracking.closeScope(workspaceId, scopeId);
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
        windowTracking.closeWorkspace(workspaceId);
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
        applications.close();
        windowTracking.close();
        // Allow already queued completion signals to reach frame/state subscribers.
        workers.shutdown();
    }

    ManagedSession owned(DesktopSessionOwner owner, String id) {
        Objects.requireNonNull(owner);
        ManagedSession session = sessions.get(id);
        if (session == null || !session.owner.equals(owner))
            throw new SecurityException("桌面会话不存在或不属于当前工作区与运行来源");
        return session;
    }

    void ensureOpen() {
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

    synchronized void requireLaunchActive(DesktopSessionOwner owner, ScopeKey scope,
                                                  long workspaceEpoch, long scopeEpoch) {
        ensureOpen();
        requireEnabled();
        if (workspaceEpochs.getOrDefault(owner.workspaceId(), 0L) != workspaceEpoch
                || scopeEpochs.getOrDefault(scope, 0L) != scopeEpoch)
            throw new IllegalStateException("桌面会话所属工作区或运行作用域已关闭");
    }

    void requireLaunchReady() {
        if (closed) throw new DesktopApplicationLaunchRejectedException(
                DesktopApplicationLaunchRejectedException.Reason.SERVICE_UNAVAILABLE,
                "桌面服务已关闭，启动请求尚未派发");
        DesktopAvailability access = consent.accessStatus(DesktopConsentPort.Purpose.OBSERVE);
        if (!access.available()) throw new DesktopApplicationLaunchRejectedException(
                DesktopApplicationLaunchRejectedException.Reason.ACCESS_DENIED, access.detail());
    }

    synchronized Selection admitLaunch(DesktopSessionOwner owner, ScopeKey scope,
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

    static boolean samePixels(DesktopFrame observed, DesktopFrame current,
            int x, int y, int width, int height) {
        return DesktopClickProgress.samePixels(observed, current, x, y, width, height);
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

    record TrackingLease(ScopeKey scope, long workspaceEpoch, long scopeEpoch) { }
    record Selection(DesktopPlatformProvider provider, DesktopAvailability availability) {}
    record ScopeKey(String workspaceId, String scopeId) {}
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
