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
        this.providers = List.copyOf(providers);
        this.consent = Objects.requireNonNull(consent);
        this.clock = Objects.requireNonNull(clock);
        this.observer = Objects.requireNonNull(observer);
        this.workers = Executors.newVirtualThreadPerTaskExecutor();
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
        Objects.requireNonNull(owner);
        Objects.requireNonNull(targetId);
        SessionKey key = new SessionKey(owner, targetId);
        long workspaceEpoch;
        long scopeEpoch;
        ScopeKey scope = new ScopeKey(owner.workspaceId(), owner.scopeId());
        synchronized (this) {
            ensureOpen();
            requireEnabled(DesktopConsentPort.Purpose.OBSERVE);
            if (requestControl) requireEnabled(DesktopConsentPort.Purpose.CONTROL);
            workspaceEpoch = workspaceEpochs.getOrDefault(owner.workspaceId(), 0L);
            scopeEpoch = scopeEpochs.getOrDefault(scope, 0L);
        }
        CompletableFuture<ManagedSession> opening = openings.computeIfAbsent(key, ignored ->
                CompletableFuture.supplyAsync(() -> openOrReuse(owner, targetId, requestControl,
                        scope, workspaceEpoch, scopeEpoch), workers));
        opening.whenComplete((ignored, failure) -> openings.remove(key, opening));
        return opening.thenApplyAsync(session -> ensureAccess(session, requestControl,
                scope, workspaceEpoch, scopeEpoch), workers);
    }

    private ManagedSession openOrReuse(DesktopSessionOwner owner, String targetId,
            boolean requestControl, ScopeKey scope, long workspaceEpoch, long scopeEpoch) {
        ensureOpen();
        requireEnabled(DesktopConsentPort.Purpose.OBSERVE);
        if (requestControl) requireEnabled(DesktopConsentPort.Purpose.CONTROL);
        ManagedSession existing = sessions.values().stream()
                .filter(session -> !session.closed && session.owner.equals(owner)
                        && session.target.id().equals(targetId))
                .findFirst().orElse(null);
        if (existing != null) {
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
        if (requestControl && !consent.request(owner, target, DesktopConsentPort.Purpose.CONTROL))
            throw new SecurityException("用户未授权控制该目标");
        requireEnabled(DesktopConsentPort.Purpose.OBSERVE);
        if (requestControl) requireEnabled(DesktopConsentPort.Purpose.CONTROL);
        DesktopPlatformSession platform = s.provider().open(target);
        ManagedSession session = new ManagedSession(this, owner, target, requestControl, platform);
        try {
            requireSameTarget(target, session.platform.currentTarget());
            DesktopSessionInfo info = session.info();
            synchronized (DefaultDesktopSessionService.this) {
                if (closed
                        || !consent.accessStatus(DesktopConsentPort.Purpose.OBSERVE).available()
                        || (requestControl && !consent.accessStatus(
                                DesktopConsentPort.Purpose.CONTROL).available())
                        || workspaceEpochs.getOrDefault(owner.workspaceId(), 0L) != workspaceEpoch
                        || scopeEpochs.getOrDefault(scope, 0L) != scopeEpoch)
                    throw new IllegalStateException("桌面会话所属工作区或运行作用域已关闭");
                sessions.put(session.id, session);
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
            ScopeKey scope, long workspaceEpoch, long scopeEpoch) {
        requireEnabled(DesktopConsentPort.Purpose.OBSERVE);
        if (requestControl) requireEnabled(DesktopConsentPort.Purpose.CONTROL);
        session.requireOpen();
        requireSameTarget(session.target, session.platform.currentTarget());
        if (requestControl && !session.controlGranted
                && !consent.request(session.owner, session.target, DesktopConsentPort.Purpose.CONTROL))
            throw new SecurityException("用户未授权控制该目标");
        synchronized (this) {
            if (closed || session.closed || sessions.get(session.id) != session
                    || workspaceEpochs.getOrDefault(session.owner.workspaceId(), 0L) != workspaceEpoch
                    || scopeEpochs.getOrDefault(scope, 0L) != scopeEpoch)
                throw new IllegalStateException("桌面会话所属工作区或运行作用域已关闭");
            requireEnabled(DesktopConsentPort.Purpose.OBSERVE);
            if (requestControl) requireEnabled(DesktopConsentPort.Purpose.CONTROL);
            synchronized (session) {
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
        requireEnabled();
        ManagedSession session = owned(owner, sessionId);
        return CompletableFuture.supplyAsync(() -> {
            synchronized (session.coordinator) { return session.captureObservation(); }
        }, workers);
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
        ManagedSession session = owned(owner, sessionId);
        return CompletableFuture.runAsync(() -> {
            synchronized (session) {
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
        }, workers);
    }

    @Override public CompletionStage<DesktopActionResult> perform(
            DesktopSessionOwner owner, String sessionId, DesktopAction action) {
        Objects.requireNonNull(action);
        ManagedSession session = owned(owner, sessionId);
        try { requireEnabled(DesktopConsentPort.Purpose.CONTROL); }
        catch (SecurityException denied) {
            session.rejected(action);
            throw denied;
        }
        unacknowledgedActions.putIfAbsent(new ActionKey(owner, sessionId,
                action.observationId()), session.targetKey);
        return CompletableFuture.supplyAsync(() -> {
            synchronized (session.coordinator) { return session.perform(action); }
        }, workers);
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
        requireEnabled(DesktopConsentPort.Purpose.FOREGROUND_TAKEOVER);
        ManagedSession session = owned(owner, sessionId);
        return CompletableFuture.supplyAsync(() -> {
            requireEnabled(DesktopConsentPort.Purpose.FOREGROUND_TAKEOVER);
            session.requireOpen();
            if (!session.controlGranted) return false;
            boolean approved = consent.request(owner, session.target,
                    DesktopConsentPort.Purpose.FOREGROUND_TAKEOVER);
            synchronized (session) {
                if (approved && !session.closed) {
                    session.foregroundGranted = true;
                    session.committedObservation = null;
                    session.pendingObservation = null;
                    session.state(DesktopSessionState.Kind.FOREGROUND_READY,
                            "前台模拟输入已就绪，请先重新观察目标窗口");
                }
                return approved && !session.closed;
            }
        }, workers);
    }

    @Override public Flow.Publisher<DesktopFrame> frames(DesktopSessionOwner owner, String sessionId) {
        requireEnabled();
        return owned(owner, sessionId).frames;
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
    }

    @Override public synchronized void closeWorkspace(String workspaceId) {
        workspaceEpochs.merge(workspaceId, 1L, Long::sum);
        closeMatching(s -> s.owner.workspaceId().equals(workspaceId));
        pendingInputs.entrySet().removeIf(entry ->
                entry.getValue().owner.workspaceId().equals(workspaceId));
        reconciledInputs.entrySet().removeIf(entry ->
                entry.getValue().owner().workspaceId().equals(workspaceId));
        refreshedInputs.entrySet().removeIf(entry ->
                entry.getValue().pending().owner().workspaceId().equals(workspaceId)
                        || entry.getValue().observer().workspaceId().equals(workspaceId));
        unacknowledgedActions.keySet().removeIf(key ->
                key.owner().workspaceId().equals(workspaceId));
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
    }
    record PendingInput(DesktopSessionOwner owner, String actionObservationId,
                        long attemptedAtMillis) {}
    record RefreshedInput(PendingInput pending, DesktopSessionOwner observer,
                          String evidenceObservationId) {}
    private record ReconciledInput(DesktopSessionOwner owner, String actionObservationId,
                                   String evidenceObservationId) {}
}
