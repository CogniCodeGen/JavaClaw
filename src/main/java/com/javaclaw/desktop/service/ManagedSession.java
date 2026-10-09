package com.javaclaw.desktop.service;

import com.javaclaw.desktop.api.*;
import com.javaclaw.desktop.spi.DesktopPlatformSession;
import com.javaclaw.desktop.spi.DesktopClickGuard;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static com.javaclaw.desktop.service.DesktopClickProgress.*;

import static com.javaclaw.desktop.service.DefaultDesktopSessionService.COMMITTED_OBSERVATION_MAX_AGE_MILLIS;
import static com.javaclaw.desktop.service.DefaultDesktopSessionService.FOREGROUND_LEASE_SECONDS;
import static com.javaclaw.desktop.service.DefaultDesktopSessionService.FRAME_STALE_MILLIS;
import static com.javaclaw.desktop.service.DefaultDesktopSessionService.PENDING_OBSERVATION_MAX_AGE_MILLIS;
import static com.javaclaw.desktop.service.DefaultDesktopSessionService.UNKNOWN_SETTLE_MILLIS;
import static com.javaclaw.desktop.service.DefaultDesktopSessionService.PendingInput;
import static com.javaclaw.desktop.service.DefaultDesktopSessionService.TargetCoordinator;
import static com.javaclaw.desktop.service.DefaultDesktopSessionService.TargetKey;
import static com.javaclaw.desktop.service.DefaultDesktopSessionService.requireSameTarget;
import static com.javaclaw.desktop.service.DefaultDesktopSessionService.samePixels;

/** Per-window capture, observation, and input state owned by the desktop service. */
final class ManagedSession implements AutoCloseable {
    private final DefaultDesktopSessionService service;
    final String id = UUID.randomUUID().toString();
    final DesktopSessionOwner owner;
    final DesktopTarget target;
    final DesktopInputPolicy inputPolicy;
    final TargetKey targetKey;
    final TargetCoordinator coordinator;
    volatile boolean controlGranted;
    final DesktopPlatformSession platform;
    final LatestPublisher<DesktopFrame> frames;
    final LatestPublisher<DesktopSessionState> states;
    final BoundedPublisher<DesktopActionEvent> actions;
    final LatestPublisher<DesktopVirtualInputState> virtualInputs;
    volatile DesktopFrame latest;
    private DesktopSurfaceSnapshot latestSurface;
    /** Last actual identity only; it survives frame loss for readonly candidate filtering. */
    volatile DesktopSurfaceSnapshot windowIdentity;
    volatile boolean capturedAnyFrame;
    volatile DesktopSessionState.Kind stateKind = DesktopSessionState.Kind.PAUSED;
    volatile String stateDetail = "等待目标窗口的首帧画面";
    volatile boolean foregroundGranted;
    boolean foregroundLease;
    long foregroundLeaseEpoch;
    long foregroundPreparedAtMillis;
    DesktopObservation pendingObservation;
    long pendingActionEpoch;
    DesktopObservation committedObservation;
    private boolean committedObservationInterpreted;
    long committedActionEpoch;
    long committedObservationAtMillis;
    private AcceptedBackgroundClick acceptedBackgroundClick;
    private long nextActionId;
    private record FeedbackTarget(DesktopAction.Kind kind, int x, int y) { }
    private FeedbackTarget feedbackTarget;
    volatile boolean closed;

    ManagedSession(DefaultDesktopSessionService service, DesktopSessionOwner owner,
                   DesktopTarget target, boolean controlGranted, DesktopInputPolicy inputPolicy,
                   DesktopPlatformSession platform) {
        this.service = Objects.requireNonNull(service);
        this.frames = new LatestPublisher<>(service.workers);
        this.states = new LatestPublisher<>(service.workers);
        this.actions = new BoundedPublisher<>(service.workers, 32);
        this.virtualInputs = new LatestPublisher<>(service.workers);
        this.owner = owner;
        this.target = target;
        this.targetKey = new TargetKey(target.providerId(), target.id());
        this.coordinator = service.targets.computeIfAbsent(targetKey, ignored -> new TargetCoordinator());
        this.controlGranted = controlGranted;
        this.inputPolicy = Objects.requireNonNull(inputPolicy);
        this.platform = platform;
    }

    DesktopSessionInfo info() {
        return new DesktopSessionInfo(id, platform.currentTarget(), controlGranted,
                foregroundGranted, inputPolicy);
    }

    void requireOpen() {
        if (closed) throw new IllegalStateException("desktop session is closed");
    }

    synchronized void state(DesktopSessionState.Kind kind, String detail) {
        if (closed && kind != DesktopSessionState.Kind.CLOSED) return;
        if (stateKind != kind || !stateDetail.equals(detail)) {
            stateKind = kind;
            stateDetail = detail;
            states.submit(new DesktopSessionState(id, kind, detail, service.clock.millis()));
        }
    }

    synchronized DesktopSessionState currentState() {
        requireOpen();
        long now = service.clock.millis();
        if (service.pendingInputs.containsKey(targetKey)) {
            return new DesktopSessionState(id, DesktopSessionState.Kind.PAUSED,
                    committedObservation == null
                            ? "上次输入结果未知，等待输入结束后的新画面"
                            : "上次输入尚未取得本工作区可用的新观察基线", now);
        }
        if (stateKind == DesktopSessionState.Kind.PAUSED
                || stateKind == DesktopSessionState.Kind.FOREGROUND_REQUIRED
                || stateKind == DesktopSessionState.Kind.FOREGROUND_READY) {
            return new DesktopSessionState(id, stateKind, stateDetail, now);
        }
        DesktopFrame frame = latest;
        if (frame == null || now - frame.capturedAtMillis() > FRAME_STALE_MILLIS) {
            return new DesktopSessionState(id, DesktopSessionState.Kind.PAUSED,
                    capturedAnyFrame ? "画面已失帧或采集权限已撤销"
                            : "等待目标窗口的首帧画面", now);
        }
        return new DesktopSessionState(id, stateKind, stateDetail, now);
    }

    /** Exact committed capture identity only; supplemental tracking never creates an input baseline. */
    synchronized Optional<DesktopSurfaceSnapshot> windowActionSource(String observationId) {
        DesktopObservation observation = committedObservation;
        if (closed || observation == null || !observation.observationId().equals(observationId)
                || committedActionEpoch != coordinator.actionEpoch
                || !matchesCapture(observation.capturedSurface(), observation.frame())) return Optional.empty();
        return Optional.of(observation.capturedSurface());
    }

    Optional<DesktopSurfaceSnapshot> windowInventoryIdentity() {
        return closed ? Optional.empty() : Optional.ofNullable(windowIdentity);
    }

    synchronized Optional<DesktopObservation> captureObservation() {
        return captureObservation(-1);
    }

    synchronized Optional<DesktopObservation> captureObservation(long capturedAfterMillis) {
        service.requireEnabled();
        requireOpen();
        if (foregroundGranted && !foregroundLease) prepareForegroundLease();
        DesktopFrame frame = latest;
        long captureBoundary = capturedAfterMillis;
        if (capturedAfterMillis >= 0 && coordinator.lastDispatchAtMillis > 0)
            captureBoundary = Math.max(captureBoundary, coordinator.lastDispatchAtMillis + UNKNOWN_SETTLE_MILLIS);
        if (frame == null || frame.capturedAtMillis() <= captureBoundary
                || service.clock.millis() - frame.capturedAtMillis() > FRAME_STALE_MILLIS)
            return Optional.empty();
        if (foregroundLease && frame.capturedAtMillis() < foregroundPreparedAtMillis)
            return Optional.empty();
        PendingInput uncertain = service.pendingInputs.get(targetKey);
        if (uncertain != null
                && frame.capturedAtMillis() <= Math.max(uncertain.attemptedAtMillis(),
                        coordinator.lastDispatchAtMillis)
                        + UNKNOWN_SETTLE_MILLIS)
            return Optional.empty();
        if (coordinator.lastDispatchAtMillis > 0
                && frame.capturedAtMillis() <= coordinator.lastDispatchAtMillis)
            return Optional.empty();
        List<DesktopElement> candidates;
        try { candidates = platform.elements(frame); }
        catch (RuntimeException unavailable) { candidates = List.of(); }
        String elementDiagnostics;
        try { elementDiagnostics = platform.elementDiagnostics(); }
        catch (RuntimeException unavailable) { elementDiagnostics = ""; }
        DesktopFrame now = latest;
        if (now == null || now.windowGeneration() != frame.windowGeneration()
                || !Objects.equals(now.targetId(), frame.targetId())
                || now.width() != frame.width() || now.height() != frame.height())
            return Optional.empty();
        String observationId = UUID.randomUUID().toString();
        List<DesktopElement> elements = candidates.stream().limit(512)
                .filter(element -> element.x() >= 0 && element.y() >= 0
                        && (long) element.x() + element.width() <= frame.width()
                        && (long) element.y() + element.height() <= frame.height())
                .map(element -> new DesktopElement(observationId + ":" + element.id(),
                        element.role(), element.label(), element.x(), element.y(),
                        element.width(), element.height(), element.actions()))
                .toList();
        pendingObservation = new DesktopObservation(id, observationId, frame, elements,
                List.of(), elementDiagnostics, latestSurface);
        pendingActionEpoch = coordinator.actionEpoch;
        return Optional.of(pendingObservation);
    }

    synchronized void discardPendingObservation(String observationId) {
        if (pendingObservation != null && pendingObservation.observationId().equals(observationId))
            pendingObservation = null;
    }

    synchronized boolean commitObservation(String observationId,
                                           List<DesktopVisualRegion> visualRegions,
                                           boolean interpreted) {
        service.requireEnabled();
        requireOpen();
        DesktopObservation pending = pendingObservation;
        if (pending == null || !pending.observationId().equals(observationId)) return false;
        DesktopFrame frame = latest;
        if (frame == null || frame.windowGeneration() != pending.frame().windowGeneration()
                || !Objects.equals(frame.targetId(), pending.frame().targetId())
                || !Objects.equals(frame.targetId(), target.id())
                || frame.width() != pending.frame().width()
                || frame.height() != pending.frame().height()
                || pendingActionEpoch != coordinator.actionEpoch
                || service.clock.millis() - pending.frame().capturedAtMillis()
                        > PENDING_OBSERVATION_MAX_AGE_MILLIS)
            return false;
        PendingInput uncertain = service.pendingInputs.get(targetKey);
        if (uncertain != null && (!interpreted
                || pending.frame().capturedAtMillis() <= Math.max(
                        uncertain.attemptedAtMillis(), coordinator.lastDispatchAtMillis)
                        + UNKNOWN_SETTLE_MILLIS)) return false;
        if (!validVisualRegions(pending, visualRegions)) return false;
        requireSameTarget(target, platform.currentTarget());
        // A clock, spinner, or cursor outside the target can advance the native
        // content revision while a vision request is in flight. Action admission
        // checks the selected region against these captured pixels instead.
        committedObservation = new DesktopObservation(id, observationId,
                pending.frame(), pending.elements(), visualRegions,
                pending.elementDiagnostics(), pending.capturedSurface());
        committedObservationInterpreted = interpreted;
        committedActionEpoch = coordinator.actionEpoch;
        committedObservationAtMillis = service.clock.millis();
        pendingObservation = null;
        // The coordinator lock guarantees the native attempt has returned. A new,
        // interpreted post-settle frame supplies a baseline for subsequent input;
        // it does not establish what the original business action accomplished.
        if (uncertain != null && uncertain.owner().workspaceId().equals(owner.workspaceId())
                && service.pendingInputs.remove(targetKey, uncertain)) {
            service.refreshedInputs.put(targetKey,
                    new DefaultDesktopSessionService.RefreshedInput(
                            uncertain, owner, observationId));
            state(DesktopSessionState.Kind.LIVE,
                    "已建立新的输入观察基线；上次业务效果仍待核验");
            frames.submit(committedObservation.frame());
        } else if (service.pendingInputs.containsKey(targetKey)) {
            state(DesktopSessionState.Kind.PAUSED,
                    "已取得新画面；其他工作区的输入屏障仍然有效");
        } else if (stateKind == DesktopSessionState.Kind.FOREGROUND_READY
                || stateKind == DesktopSessionState.Kind.FOREGROUND_REQUIRED) {
            state(DesktopSessionState.Kind.LIVE, "已准备前台模拟输入");
        }
        return true;
    }

    synchronized boolean reconcilePendingAction(String actionObservationId,
                                                 String evidenceObservationId) {
        service.requireEnabled();
        requireOpen();
        PendingInput pending = service.pendingInputs.get(targetKey);
        DesktopObservation evidence = committedObservation;
        if (pending == null || !pending.owner().equals(owner)
                || actionObservationId == null
                || !pending.actionObservationId().equals(actionObservationId)
                || evidence == null || evidenceObservationId == null
                || !evidence.observationId().equals(evidenceObservationId)
                || evidence.frame().capturedAtMillis() < Math.max(
                        pending.attemptedAtMillis(), coordinator.lastDispatchAtMillis)
                        + UNKNOWN_SETTLE_MILLIS
                || committedActionEpoch != coordinator.actionEpoch
                || !evidence.frame().targetId().equals(target.id())) return false;
        requireSameTarget(target, platform.currentTarget());
        if (!service.pendingInputs.remove(targetKey, pending)) return false;
        state(DesktopSessionState.Kind.LIVE, "上次操作效果已由可信核验器确认");
        frames.submit(evidence.frame());
        return true;
    }

    private boolean validVisualRegions(DesktopObservation pending,
                                       List<DesktopVisualRegion> regions) {
        if (regions.size() > 40) return false;
        for (int i = 0; i < regions.size(); i++) {
            DesktopVisualRegion region = regions.get(i);
            if (!region.id().equals(pending.observationId() + ":v" + i)
                    || (long) region.x() + region.width() > pending.frame().width()
                    || (long) region.y() + region.height() > pending.frame().height())
                return false;
        }
        return true;
    }

    private void prepareForegroundLease() {
        if (foregroundLease) return;
        try {
            service.observer.beforeForegroundAction(id).toCompletableFuture()
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            platform.prepareForeground();
        } catch (Exception failure) {
            // Native preparation may have changed focus before reporting failure.
            // Restore it best-effort, preserving the original reason for refusal.
            try { platform.restoreForeground(); }
            catch (RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
            try { service.observer.afterForegroundAction(id); }
            catch (RuntimeException overlayFailure) { failure.addSuppressed(overlayFailure); }
            throw new IllegalStateException("无法准备目标窗口的前台输入: "
                    + failure.getMessage(), failure);
        }
        foregroundLease = true;
        foregroundPreparedAtMillis = service.clock.millis();
        long lease = ++foregroundLeaseEpoch;
        CompletableFuture.delayedExecutor(FOREGROUND_LEASE_SECONDS,
                java.util.concurrent.TimeUnit.SECONDS, service.workers)
                .execute(() -> {
                    synchronized (ManagedSession.this) {
                        if (foregroundLease && foregroundLeaseEpoch == lease) {
                            String detail = "前台观察已过期，请重新观察目标窗口";
                            try { endForegroundLease(); }
                            catch (RuntimeException failure) {
                                detail += "；恢复先前焦点失败: " + failure.getMessage();
                            } finally {
                                committedObservation = null;
                                pendingObservation = null;
                                state(DesktopSessionState.Kind.FOREGROUND_READY, detail);
                            }
                        }
                    }
                });
    }

    void endForegroundLease() {
        if (!foregroundLease) return;
        foregroundLease = false;
        foregroundLeaseEpoch++;
        try { platform.restoreForeground(); }
        finally { service.observer.afterForegroundAction(id); }
    }

    /** Called only after native polling has released its own platform lock. */
    private synchronized void invalidatePreview(String reason) {
        if (closed) return;
        latest = null;
        latestSurface = null;
        pendingObservation = null;
        committedObservation = null;
        String detail = reason;
        try { endForegroundLease(); }
        catch (RuntimeException failure) {
            detail += "；恢复先前焦点失败: " + failure.getMessage();
        }
        state(DesktopSessionState.Kind.PAUSED, detail);
    }

    void poll() {
        long lastFrameAt = service.clock.millis();
        long nextAccessCheckAt = 0;
        boolean accessReady = true;
        String accessDetail = "";
        while (!closed) {
            try {
                if (!service.consent.enabled()) {
                    service.sessions.remove(id, this);
                    close();
                    break;
                }
                long now = service.clock.millis();
                if (now >= nextAccessCheckAt) {
                    DesktopAvailability access = service.consent.accessStatus(
                            DesktopConsentPort.Purpose.OBSERVE);
                    accessReady = access.available();
                    accessDetail = access.detail();
                    nextAccessCheckAt = now + 1_000;
                }
                if (!accessReady) {
                    invalidatePreview("系统权限已撤销或不可用：" + accessDetail);
                    Thread.sleep(250);
                    continue;
                }
                DesktopTarget current = platform.currentTarget();
                if (current.minimized()) {
                    invalidatePreview("目标窗口已最小化");
                    Thread.sleep(250);
                    continue;
                }
                if (!current.visible()) {
                    invalidatePreview("目标窗口当前不可见或无法采集");
                    Thread.sleep(250);
                    continue;
                }
                Optional<DesktopFrame> frame;
                DesktopSurfaceSnapshot capturedSurface = null;
                // Native polling updates currentSurface together with its returned frame.
                // Release the platform monitor before taking the session lock below;
                // observation and action paths acquire these locks in the other order.
                synchronized (platform) {
                    frame = platform.pollFrame(200);
                    if (frame.isPresent()) {
                        try {
                            DesktopSurfaceSnapshot candidate = platform.currentSurface().orElse(null);
                            if (matchesCapture(candidate, frame.get())) capturedSurface = candidate;
                        } catch (RuntimeException unavailable) {
                            // Providers without a capture-bound surface remain usable,
                            // but cannot supply native stage identity evidence.
                        }
                    }
                }
                if (frame.isPresent()) {
                    DesktopFrame value = frame.get();
                    synchronized (this) {
                        if (closed) break;
                        DesktopFrame previous = latest;
                        if (previous != null && (value.windowGeneration()
                                != previous.windowGeneration()
                                || !Objects.equals(value.targetId(), previous.targetId())
                                || value.width() != previous.width()
                                || value.height() != previous.height())) {
                            pendingObservation = null;
                            committedObservation = null;
                            try { endForegroundLease(); }
                            catch (RuntimeException focusFailure) {
                                state(DesktopSessionState.Kind.PAUSED,
                                        "窗口已变化且恢复先前焦点失败: "
                                                + focusFailure.getMessage());
                            }
                        }
                        latest = value;
                        latestSurface = capturedSurface;
                        if (capturedSurface != null) windowIdentity = capturedSurface;
                        capturedAnyFrame = true;
                        lastFrameAt = service.clock.millis();
                        if (!service.pendingInputs.containsKey(targetKey)) frames.submit(value);
                        if (inputPolicy == DesktopInputPolicy.BACKGROUND_STRICT && controlGranted && targetActive())
                            state(DesktopSessionState.Kind.PAUSED, "目标应用正在活动，后台输入已暂停");
                        else if (!service.pendingInputs.containsKey(targetKey)
                                && stateKind != DesktopSessionState.Kind.FOREGROUND_REQUIRED
                                && stateKind != DesktopSessionState.Kind.FOREGROUND_READY)
                            state(DesktopSessionState.Kind.LIVE, "实时预览");
                    }
                    // Native capture retains only its newest frame; the small overlay needs
                    // a modest update rate rather than one heap copy for every display pulse.
                    Thread.sleep(80);
                } else if (service.clock.millis() - lastFrameAt > FRAME_STALE_MILLIS) {
                    invalidatePreview("画面已失帧或采集权限已撤销");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException failure) {
                invalidatePreview("采集失败: " + failure.getMessage());
                try { Thread.sleep(250); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    private boolean matchesCapture(DesktopSurfaceSnapshot surface, DesktopFrame frame) {
        return surface != null && !surface.runtimeId().isBlank() && !surface.surfaceId().isBlank()
                && surface.providerId().equals(target.providerId())
                && surface.logicalTargetId().equals(target.id())
                && surface.logicalTargetId().equals(frame.targetId())
                && !surface.applicationId().isBlank()
                && surface.applicationId().equals(target.applicationId())
                && surface.generation() == frame.windowGeneration()
                && surface.contentRevision() == frame.contentRevision()
                && surface.observedAtMillis() == frame.capturedAtMillis();
    }

    synchronized void rejected(DesktopAction action) {
        long actionId = ++nextActionId;
        long now = service.clock.millis();
        actions.submit(new DesktopActionEvent(id, action.kind(),
                DesktopActionEvent.Phase.STARTED, null, action.windowGeneration(), now,
                actionId, null, DesktopActionResult.Reason.NONE));
        feedbackTarget = new FeedbackTarget(action.kind(), action.x(), action.y());
        finishFeedback(actionId, new DesktopActionResult(DesktopActionResult.Status.DENIED,
                "输入权限未授予", action.windowGeneration()));
    }

    synchronized DesktopActionResult perform(DesktopAction action) {
        return perform(action, false);
    }

    synchronized DesktopActionResult perform(DesktopAction action, boolean manualAuthorized) {
        long actionId = ++nextActionId;
        feedbackTarget = new FeedbackTarget(action.kind(), action.x(), action.y());
        actions.submit(new DesktopActionEvent(id, action.kind(),
                DesktopActionEvent.Phase.STARTED, null, action.windowGeneration(), service.clock.millis(),
                actionId, null, DesktopActionResult.Reason.NONE));
        try {
            DesktopActionResult result = performChecked(action, manualAuthorized);
            finishFeedback(actionId, result);
            return result;
        } catch (RuntimeException failure) {
            finishFeedback(actionId, new DesktopActionResult(
                    failure instanceof SecurityException ? DesktopActionResult.Status.DENIED
                            : DesktopActionResult.Status.FAILED, "输入准备失败", action.windowGeneration()));
            throw failure;
        }
    }

    private void finishFeedback(long actionId, DesktopActionResult result) {
        FeedbackTarget action = feedbackTarget;
        long now = service.clock.millis();
        actions.submit(new DesktopActionEvent(id, action.kind(), DesktopActionEvent.Phase.FINISHED,
                result.status(), result.windowGeneration(), now, actionId, result.delivery(), result.reason()));
        virtualInputs.submit(new DesktopVirtualInputState(id, result.windowGeneration(),
                Math.max(0, action.x()), Math.max(0, action.y()),
                result.delivery() != DesktopActionResult.Delivery.NOT_SENT, 0,
                DesktopVirtualInputState.Phase.FINISHED, now, actionId, result.delivery()));
    }

    private DesktopActionResult performChecked(DesktopAction action, boolean manualAuthorized) {
        service.requireControlEnabled(inputPolicy);
        requireOpen();
        if (coordinator.manualLease != null && !manualAuthorized)
            return new DesktopActionResult(DesktopActionResult.Status.DENIED,
                    "该目标的自动输入已暂停，等待人工输入面板释放控制",
                    action.windowGeneration(), DesktopActionResult.Mode.NONE,
                    DesktopActionResult.Reason.POLICY_BLOCKED, false, action.observationId(),
                    DesktopActionResult.NextStep.OBSERVE);
        if (!controlGranted)
            return new DesktopActionResult(DesktopActionResult.Status.DENIED,
                    "本会话只允许观察；请对该 targetId 调用 desktop_session_open(control=true)，"
                            + "经正常授权后重新观察再输入", action.windowGeneration(),
                    DesktopActionResult.Mode.NONE, DesktopActionResult.Reason.SESSION_CONTROL_REQUIRED,
                    false, action.observationId(), DesktopActionResult.NextStep.OPEN_SESSION);
        if (service.pendingInputs.containsKey(targetKey))
            return new DesktopActionResult(DesktopActionResult.Status.DENIED,
                    "同一窗口有结果未知的输入；先获取输入结束后的新观察，禁止直接重试",
                    action.windowGeneration(), DesktopActionResult.Mode.NONE,
                    DesktopActionResult.Reason.DELIVERY_UNCERTAIN, false, action.observationId(),
                    DesktopActionResult.NextStep.OBSERVE);
        if (foregroundGranted && action.kind() == DesktopAction.Kind.TYPE
                && action.textOperation() == DesktopAction.TextOperation.SET_TEXT)
            return new DesktopActionResult(DesktopActionResult.Status.UNSUPPORTED,
                    "系统输入仅支持INSERT_TEXT；SET_TEXT需要后台公开的整值设置能力",
                    action.windowGeneration(), DesktopActionResult.Mode.NONE,
                    DesktopActionResult.Reason.UNSUPPORTED_ACTION, false,
                    action.observationId(), DesktopActionResult.NextStep.OBSERVE);
        DesktopFrame frame = latest;
        if (frame == null || service.clock.millis() - frame.capturedAtMillis() > FRAME_STALE_MILLIS)
            return new DesktopActionResult(DesktopActionResult.Status.STALE_FRAME,
                    "没有新的实时画面，请重新观察", action.windowGeneration())
                    .withContext(DesktopActionResult.Mode.NONE, action.observationId(),
                            DesktopActionResult.NextStep.OBSERVE);
        if (frame.windowGeneration() != action.windowGeneration())
            return new DesktopActionResult(DesktopActionResult.Status.STALE_FRAME,
                    "目标窗口或画面内容已变化，请基于新帧重新定位", frame.windowGeneration())
                    .withContext(DesktopActionResult.Mode.NONE, action.observationId(),
                            DesktopActionResult.NextStep.OBSERVE);
        DesktopObservation observed = committedObservation;
        if (observed == null || action.observationId().isBlank()
                || !observed.observationId().equals(action.observationId()))
            return new DesktopActionResult(DesktopActionResult.Status.STALE_FRAME,
                    "观察 ID 不存在或已使用，请重新观察目标窗口", frame.windowGeneration())
                    .withContext(DesktopActionResult.Mode.NONE, action.observationId(),
                            DesktopActionResult.NextStep.OBSERVE);
        if (committedActionEpoch != coordinator.actionEpoch)
            return new DesktopActionResult(DesktopActionResult.Status.STALE_FRAME,
                    "同一窗口已被其他会话操作，请重新观察", frame.windowGeneration())
                    .withContext(DesktopActionResult.Mode.NONE, action.observationId(),
                            DesktopActionResult.NextStep.OBSERVE);
        if (service.clock.millis() - committedObservationAtMillis
                > COMMITTED_OBSERVATION_MAX_AGE_MILLIS
                || frame.windowGeneration() != observed.frame().windowGeneration()
                || !Objects.equals(frame.targetId(), observed.frame().targetId())
                || frame.width() != observed.frame().width()
                || frame.height() != observed.frame().height()
                || (action.kind() == DesktopAction.Kind.CLICK
                    && !Objects.equals(frame.geometry(), observed.frame().geometry())))
            return new DesktopActionResult(DesktopActionResult.Status.STALE_FRAME,
                    "观察后的目标画面已变化，请重新定位", frame.windowGeneration())
                    .withContext(DesktopActionResult.Mode.NONE, action.observationId(),
                            DesktopActionResult.NextStep.OBSERVE);
        int x = action.x();
        int y = action.y();
        int regionX;
        int regionY;
        int regionWidth;
        int regionHeight;
        if (!action.elementId().isBlank()) {
            DesktopElement element = observed.elements().stream()
                    .filter(candidate -> candidate.id().equals(action.elementId()))
                    .findFirst().orElse(null);
            DesktopVisualRegion visual = observed.visualRegions().stream()
                    .filter(candidate -> candidate.id().equals(action.elementId()))
                    .findFirst().orElse(null);
            if (element == null && visual == null)
                return new DesktopActionResult(DesktopActionResult.Status.STALE_FRAME,
                        "目标 ID 不属于当前观察", frame.windowGeneration())
                        .withContext(DesktopActionResult.Mode.NONE, action.observationId(),
                                DesktopActionResult.NextStep.OBSERVE);
            if (element != null) {
                int required = switch (action.kind()) {
                    case CLICK -> DesktopElement.PRESS;
                    case TYPE -> inputPolicy == DesktopInputPolicy.BACKGROUND_STRICT
                            ? action.textOperation() == DesktopAction.TextOperation.SET_TEXT
                                ? DesktopElement.SET_TEXT : DesktopElement.INSERT_TEXT
                            : DesktopElement.WRITE;
                    case SCROLL -> DesktopElement.SCROLL;
                    case KEY -> 0;
                };
                if (required != 0 && (element.actions() & required) == 0) {
                    String capability = switch (action.kind()) {
                        case CLICK -> "PRESS 按压";
                        case TYPE -> "WRITE 写入";
                        case SCROLL -> "SCROLL 滚动";
                        case KEY -> "按键";
                    };
                    return new DesktopActionResult(inputPolicy == DesktopInputPolicy.BACKGROUND_STRICT
                                ? DesktopActionResult.Status.UNSUPPORTED : DesktopActionResult.Status.FAILED,
                            "所选辅助功能目标不支持" + capability
                                    + "动作；请重新观察并选择具备该能力的控件",
                            frame.windowGeneration(), DesktopActionResult.Mode.NONE,
                            inputPolicy == DesktopInputPolicy.BACKGROUND_STRICT
                                ? DesktopActionResult.Reason.UNSUPPORTED_ACTION : DesktopActionResult.Reason.INVALID_TARGET,
                            false, action.observationId(), DesktopActionResult.NextStep.OBSERVE);
                }
                x = element.centerX();
                y = element.centerY();
                regionX = element.x();
                regionY = element.y();
                regionWidth = element.width();
                regionHeight = element.height();
            } else {
                x = visual.centerX();
                y = visual.centerY();
                regionX = visual.x();
                regionY = visual.y();
                regionWidth = visual.width();
                regionHeight = visual.height();
            }
        } else {
            // A raw point still carries an observation ID. Check a neighborhood
            // around it because the model did not name a bounded target.
            regionX = Math.max(0, x - 24);
            regionY = Math.max(0, y - 24);
            regionWidth = Math.min(frame.width(), x + 25) - regionX;
            regionHeight = Math.min(frame.height(), y + 25) - regionY;
        }
        if (action.kind() != DesktopAction.Kind.KEY
                && (x < 0 || y < 0 || x >= frame.width() || y >= frame.height()))
            return new DesktopActionResult(DesktopActionResult.Status.STALE_FRAME,
                    "操作坐标不在观察到的窗口内", frame.windowGeneration())
                    .withContext(DesktopActionResult.Mode.NONE, action.observationId(),
                            DesktopActionResult.NextStep.OBSERVE);
        if (action.kind() == DesktopAction.Kind.KEY
                ? frame.contentRevision() != observed.frame().contentRevision()
                : !samePixels(observed.frame(), frame,
                        regionX, regionY, regionWidth, regionHeight)) {
            committedObservation = null;
            pendingObservation = null;
            return new DesktopActionResult(DesktopActionResult.Status.STALE_FRAME,
                    "观察目标区域已变化，请重新观察并定位", frame.windowGeneration())
                    .withContext(DesktopActionResult.Mode.NONE, action.observationId(),
                            DesktopActionResult.NextStep.OBSERVE);
        }
        DesktopAction resolved = new DesktopAction(action.kind(), x, y, action.button(),
                action.clicks(), action.amount(), action.text(), action.windowGeneration(),
                action.observationId(), action.elementId(), frame.contentRevision(), action.textOperation());
        DesktopClickGuard clickGuard = null;
        if (resolved.kind() == DesktopAction.Kind.CLICK) {
            try { clickGuard = DesktopClickGuard.capture(frame, latestSurface,
                    regionX, regionY, regionWidth, regionHeight); }
            catch (IllegalArgumentException invalidRegion) {
                return new DesktopActionResult(DesktopActionResult.Status.FAILED,
                        invalidRegion.getMessage(), frame.windowGeneration(), DesktopActionResult.Mode.NONE,
                        DesktopActionResult.Reason.INVALID_TARGET, false, action.observationId(),
                        DesktopActionResult.NextStep.OBSERVE);
            }
        }
        if (inputPolicy == DesktopInputPolicy.BACKGROUND_STRICT) {
            if (targetActive()) {
                state(DesktopSessionState.Kind.PAUSED, "目标窗口正在被用户使用，后台输入已暂停");
                return new DesktopActionResult(DesktopActionResult.Status.DENIED,
                        "目标窗口当前处于活动状态；切换到其他应用后重新观察再操作",
                        frame.windowGeneration(), DesktopActionResult.Mode.NONE,
                        DesktopActionResult.Reason.TARGET_ACTIVE, false,
                        action.observationId(), DesktopActionResult.NextStep.OBSERVE);
            }
            if (!DesktopInputCapabilities.forElements(frame, observed.elements()).supports(resolved, observed.elements())) {
                return new DesktopActionResult(DesktopActionResult.Status.UNSUPPORTED,
                        "该目标未公开所需的后台语义能力；不会改用系统鼠标键盘",
                        frame.windowGeneration(), DesktopActionResult.Mode.NONE,
                        DesktopActionResult.Reason.UNSUPPORTED_ACTION, false,
                        action.observationId(), DesktopActionResult.NextStep.OBSERVE);
            }
        }
        if (!foregroundGranted && unchangedAcceptedClick(resolved, observed, frame)) {
            // Equal pixels establish visible non-progress, never the absence of a
            // business effect. Refuse this new dispatch; the earlier SENT action
            // remains unresolved and must not become a synthetic mouse retry.
            return new DesktopActionResult(DesktopActionResult.Status.FAILED,
                    "DESKTOP_NO_PROGRESS：上次后台点击已被系统接受，但新的完整画面未变化；"
                            + "已阻止重复点击。上次业务效果仍未知，请核验或向用户澄清，禁止自动改用前台重试。",
                    frame.windowGeneration(), DesktopActionResult.Mode.BACKGROUND_SEMANTIC,
                    DesktopActionResult.Reason.NO_PROGRESS, false, action.observationId(),
                    DesktopActionResult.NextStep.RECONCILE);
        }
        AcceptedBackgroundClick clickCandidate = !foregroundGranted
                ? backgroundClickCandidate(resolved, observed, frame) : null;
        DesktopActionResult.Mode mode = foregroundGranted
                ? DesktopActionResult.Mode.FOREGROUND_SYNTHETIC
                : DesktopActionResult.Mode.BACKGROUND_SEMANTIC;
        if (foregroundGranted && !foregroundLease)
            return new DesktopActionResult(DesktopActionResult.Status.STALE_FRAME,
                    "前台观察租约已结束，请重新观察", frame.windowGeneration())
                    .withContext(DesktopActionResult.Mode.NONE, action.observationId(),
                            DesktopActionResult.NextStep.OBSERVE);
        DesktopActionResult result;
        feedbackTarget = new FeedbackTarget(resolved.kind(), resolved.x(), resolved.y());
        virtualInputs.submit(new DesktopVirtualInputState(id, frame.windowGeneration(),
                Math.max(0, resolved.x()), Math.max(0, resolved.y()), true,
                resolved.kind() == DesktopAction.Kind.CLICK ? resolved.button() : 0,
                DesktopVirtualInputState.Phase.TARGETING, service.clock.millis(), nextActionId, null));
        try { result = resolved.kind() == DesktopAction.Kind.CLICK
                ? platform.performClick(resolved, foregroundGranted, clickGuard)
                : platform.perform(resolved, foregroundGranted); }
        catch (RuntimeException uncertain) {
            result = new DesktopActionResult(DesktopActionResult.Status.UNKNOWN,
                    "输入接口异常，可能已有部分操作生效: " + uncertain.getMessage(),
                    frame.windowGeneration());
        }
        if (result.dispatchAttempted()
                && result.status() != DesktopActionResult.Status.ACCEPTED
                && result.status() != DesktopActionResult.Status.VERIFIED
                && result.status() != DesktopActionResult.Status.UNKNOWN) {
            result = new DesktopActionResult(DesktopActionResult.Status.UNKNOWN,
                    "输入已尝试派发但接口返回失败；效果待核验: " + result.detail(),
                    result.windowGeneration(), result.mode(),
                    DesktopActionResult.Reason.DELIVERY_UNCERTAIN, true,
                    action.observationId(), DesktopActionResult.NextStep.OBSERVE);
        }
        if (inputPolicy == DesktopInputPolicy.BACKGROUND_STRICT
                && targetActive()) {
            state(DesktopSessionState.Kind.PAUSED, "目标应用已激活，后续后台输入已暂停；请重新观察");
        }
        if (foregroundGranted) {
            try { endForegroundLease(); }
            catch (RuntimeException restoreFailure) {
                // Focus restoration occurs after input. It must never replace the
                // delivery status with FAILED, which could make a retry look safe.
                result = new DesktopActionResult(result.status(),
                        result.detail() + "；焦点恢复失败: " + restoreFailure.getMessage(),
                        result.windowGeneration(), result.mode(), result.reason(),
                        result.dispatchAttempted(), result.observationId(), result.nextStep());
            }
        }
        committedObservation = null;
        pendingObservation = null;
        // Every native attempt consumes its observation, including failures that
        // definitely sent no input. A second action needs fresh target evidence.
        DesktopActionResult.NextStep nextStep = DesktopActionResult.NextStep.OBSERVE;
        if (result.dispatchAttempted() || result.status() == DesktopActionResult.Status.UNKNOWN) {
            if (!foregroundGranted) coordinator.backgroundInputOwners.add(owner);
            coordinator.actionEpoch++;
            coordinator.lastDispatchAtMillis = service.clock.millis();
            acceptedBackgroundClick = result.status() == DesktopActionResult.Status.ACCEPTED
                    && clickCandidate != null
                    ? clickCandidate.dispatched(coordinator.actionEpoch, coordinator.lastDispatchAtMillis)
                    : null;
            latest = null;
            latestSurface = null;
        }
        if (result.status() == DesktopActionResult.Status.UNSUPPORTED && !foregroundGranted) {
            state(DesktopSessionState.Kind.PAUSED,
                    "后台公开语义能力不支持该操作：" + result.detail());
            nextStep = DesktopActionResult.NextStep.OBSERVE;
        } else if (result.status() == DesktopActionResult.Status.UNKNOWN) {
            service.pendingInputs.put(targetKey, new PendingInput(owner,
                    action.observationId(), coordinator.lastDispatchAtMillis));
            state(DesktopSessionState.Kind.PAUSED, "操作结果未知，已停止自动重试");
            nextStep = DesktopActionResult.NextStep.OBSERVE;
        } else if (result.status() == DesktopActionResult.Status.STALE_FRAME) {
            state(DesktopSessionState.Kind.PAUSED, result.detail());
            nextStep = DesktopActionResult.NextStep.OBSERVE;
        } else if (result.status() == DesktopActionResult.Status.DENIED
                && !service.consent.accessStatus(DesktopConsentPort.Purpose.CONTROL).available()) {
            nextStep = DesktopActionResult.NextStep.CHECK_PERMISSIONS;
        }
        return result.withContext(mode, action.observationId(), nextStep);
    }

    private boolean targetActive() {
        try { return platform.isTargetActive().orElse(false); }
        catch (RuntimeException unavailable) { return true; }
    }

    private AcceptedBackgroundClick backgroundClickCandidate(DesktopAction action,
            DesktopObservation observation, DesktopFrame frame) {
        if (!committedObservationInterpreted || action.kind() != DesktopAction.Kind.CLICK
                || action.button() != 1 || action.clicks() != 1
                || !matchesCapture(latestSurface, frame)) return null;
        ClickControl control = clickControl(action, observation);
        if (control == null) return null;
        byte[] pixels = pixelDigest(frame);
        return pixels == null ? null : new AcceptedBackgroundClick(latestSurface,
                frame.width(), frame.height(), frame.stride(), pixels, control, 0, 0);
    }

    private boolean unchangedAcceptedClick(DesktopAction action,
            DesktopObservation observation, DesktopFrame frame) {
        AcceptedBackgroundClick previous = acceptedBackgroundClick;
        if (previous == null || !committedObservationInterpreted
                || action.kind() != DesktopAction.Kind.CLICK
                || action.button() != 1 || action.clicks() != 1
                || previous.actionEpoch() != coordinator.actionEpoch
                || observation.frame().capturedAtMillis()
                    <= previous.completedAtMillis() + UNKNOWN_SETTLE_MILLIS
                || !matchesCapture(observation.capturedSurface(), observation.frame())
                || !matchesCapture(latestSurface, frame)
                || !sameClickSurface(previous.surface(), observation.capturedSurface())
                || !sameClickSurface(previous.surface(), latestSurface)
                || previous.width() != frame.width() || previous.height() != frame.height()
                || previous.stride() != frame.stride()
                || previous.width() != observation.frame().width()
                || previous.height() != observation.frame().height()
                || previous.stride() != observation.frame().stride()) return false;
        ClickControl control = clickControl(action, observation);
        if (control == null || !previous.control().equals(control)) return false;
        byte[] observedPixels = pixelDigest(observation.frame());
        byte[] currentPixels = pixelDigest(frame);
        return observedPixels != null && currentPixels != null
                && java.util.Arrays.equals(previous.pixels(), observedPixels)
                && java.util.Arrays.equals(previous.pixels(), currentPixels);
    }

    @Override public void close() {
      synchronized (coordinator) {
       synchronized (this) {
        if (closed) return;
        if (coordinator.manualLease != null && coordinator.manualLease.sessionId().equals(id)) {
            coordinator.manualLease = null;
            coordinator.actionEpoch++;
        }
        closed = true;
        latest = null;
        latestSurface = null;
        windowIdentity = null;
        acceptedBackgroundClick = null;
        state(DesktopSessionState.Kind.CLOSED, "会话已关闭");
        try { endForegroundLease(); }
        finally {
            try { platform.close(); }
            finally {
                frames.close();
                states.close();
                actions.close();
                virtualInputs.close();
                service.observer.closed(id);
            }
        }
       }
      }
    }
}
