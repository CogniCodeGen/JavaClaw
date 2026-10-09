package com.javaclaw.desktop.api;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Platform-neutral, permission-scoped desktop sessions. */
public interface DesktopSessionService extends AutoCloseable {
    /** Owner-scoped in-memory session IDs. Empty Optional means this inventory is unknown. */
    default Optional<List<String>> liveSessionIds(DesktopSessionOwner owner) {
        return Optional.empty();
    }

    DesktopAvailability availability();
    CompletionStage<List<DesktopTarget>> discoverTargets();
    /** Owner-scoped discovery also records lifecycle observations; no target is selected. */
    default CompletionStage<List<DesktopTarget>> discoverTargets(DesktopSessionOwner owner) {
        return discoverTargets();
    }
    /** Optional action association; a begin alone is never evidence that input was sent. */
    default void beginWindowAction(DesktopSessionOwner owner, String sessionId,
            String invocationId, String observationId) { }
    default void beginWindowAction(DesktopSessionOwner owner, String sessionId, String invocationId) {
        beginWindowAction(owner, sessionId, invocationId, "");
    }
    /** Only a matching actual service dispatch may establish an observed-after source. */
    default void finishWindowAction(DesktopSessionOwner owner, String sessionId,
            String invocationId, DesktopActionResult result) { }
    default DesktopWindowTrackingSnapshot snapshotWindowTracking(DesktopSessionOwner owner,
            long afterSequence, int limit) {
        return new DesktopWindowTrackingSnapshot(List.of(), 0, afterSequence, false, false);
    }
    /** One owner listener; snapshot is the compensation path for failed or late persistence. */
    default AutoCloseable bindWindowObserver(DesktopSessionOwner owner,
            java.util.function.Consumer<DesktopWindowTrackingEvent> observer) { return () -> { }; }
    /** Same actual application/process candidates; wait is bounded to at most three seconds. */
    default CompletionStage<DesktopWindowCandidates> discoverWindowCandidates(DesktopSessionOwner owner,
            String sessionId, String invocationId, long waitMillis) {
        return java.util.concurrent.CompletableFuture.completedFuture(new DesktopWindowCandidates(
                sessionId, "", "", List.of(), 0, 0, false, false, false));
    }
    /** Read-only installed application metadata within the owner's enabled desktop scope. */
    default CompletionStage<DesktopApplicationCatalog> discoverApplications(DesktopSessionOwner owner) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException("当前桌面平台不支持安装应用发现"));
    }
    CompletionStage<DesktopApplicationLaunchResult> launchApplication(
            DesktopSessionOwner owner, String application);
    CompletionStage<DesktopSessionInfo> open(DesktopSessionOwner owner, String targetId,
                                              boolean requestControl);
    default DesktopInputPolicy defaultInputPolicy() { return DesktopInputPolicy.BACKGROUND_STRICT; }
    default CompletionStage<DesktopSessionInfo> open(DesktopSessionOwner owner, String targetId,
            boolean requestControl, DesktopInputPolicy policy) {
        if (policy != DesktopInputPolicy.BACKGROUND_STRICT)
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new UnsupportedOperationException("service does not support explicit input policies"));
        return open(owner, targetId, requestControl);
    }
    CompletionStage<Optional<DesktopFrame>> snapshot(DesktopSessionOwner owner, String sessionId);
    /** Capture a frame and bounded accessibility catalog without committing an input baseline. */
    default CompletionStage<Optional<DesktopObservation>> captureObservation(
            DesktopSessionOwner owner, String sessionId) {
        return snapshot(owner, sessionId).thenApply(frame -> frame.map(value ->
                new DesktopObservation(sessionId, java.util.UUID.randomUUID().toString(),
                        value, List.of())));
    }
    /** One non-waiting capture attempt strictly after a host-established input boundary. */
    default CompletionStage<Optional<DesktopObservation>> captureObservation(
            DesktopSessionOwner owner, String sessionId, long capturedAfterMillis) {
        return captureObservation(owner, sessionId).thenApply(observation -> observation
                .filter(value -> value.frame().capturedAtMillis() > capturedAfterMillis));
    }
    /** Legacy commit without interpretation proof; cannot refresh uncertain input. */
    default CompletionStage<Boolean> commitObservation(
            DesktopSessionOwner owner, String sessionId, String observationId) {
        return java.util.concurrent.CompletableFuture.completedFuture(false);
    }
    /**
     * Commit an interpreted frame and its observation-scoped visual regions. A settled,
     * post-input frame may refresh input admission without verifying the previous effect.
     */
    default CompletionStage<Boolean> commitObservation(
            DesktopSessionOwner owner, String sessionId, String observationId,
            List<DesktopVisualRegion> visualRegions) {
        return commitObservation(owner, sessionId, observationId);
    }
    /** Release an unused foreground lease after observation failure or cancellation. */
    default CompletionStage<Void> releaseForeground(
            DesktopSessionOwner owner, String sessionId) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    /** Failure cleanup belongs to one capture; an old read must not clear a newer baseline. */
    default CompletionStage<Void> releaseForeground(
            DesktopSessionOwner owner, String sessionId, String observationId) {
        return releaseForeground(owner, sessionId);
    }
    /** Current capture state for explaining why a fresh frame is unavailable. */
    DesktopSessionState state(DesktopSessionOwner owner, String sessionId);
    CompletionStage<DesktopActionResult> perform(DesktopSessionOwner owner, String sessionId,
                                                  DesktopAction action);
    /** Wait for in-flight input, then pause all automated input to this target. */
    default CompletionStage<String> acquireManualControl(DesktopSessionOwner owner, String sessionId) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException("人工输入暂停屏障不可用"));
    }
    /** The opaque lease is never exposed to agent tools. Normal observation checks still apply. */
    default CompletionStage<DesktopActionResult> performManual(DesktopSessionOwner owner,
            String sessionId, String lease, DesktopAction action) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException("人工输入不可用"));
    }
    default CompletionStage<Void> releaseManualControl(DesktopSessionOwner owner,
            String sessionId, String lease) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    /** The caller lost the result of a started action; fence later input before waiting for native completion. */
    default void markDeliveryUncertain(DesktopSessionOwner owner, String sessionId,
                                       String actionObservationId) {
    }
    /** The caller received the native result; its lost-result fence is no longer needed. */
    default void acknowledgeActionResult(DesktopSessionOwner owner, String sessionId,
                                         String actionObservationId) {
    }
    /** Trusted effect verifiers only: observing a frame alone never clears uncertain input. */
    default CompletionStage<Boolean> reconcilePendingAction(DesktopSessionOwner owner,
            String sessionId, String actionObservationId, String evidenceObservationId) {
        return java.util.concurrent.CompletableFuture.completedFuture(false);
    }
    CompletionStage<Boolean> authorizeForeground(DesktopSessionOwner owner, String sessionId);
    /**
     * Select system mouse/keyboard input before a session's first background dispatch.
     * Implementations must refuse automatic mode changes after background input;
     * explicit user takeover remains a separate operation. Legacy services fail closed.
     */
    default CompletionStage<Boolean> authorizeSystemInput(DesktopSessionOwner owner, String sessionId) {
        return java.util.concurrent.CompletableFuture.completedFuture(false);
    }
    Flow.Publisher<DesktopFrame> frames(DesktopSessionOwner owner, String sessionId);
    Flow.Publisher<DesktopSessionState> states(DesktopSessionOwner owner, String sessionId);
    Flow.Publisher<DesktopActionEvent> actions(DesktopSessionOwner owner, String sessionId);
    default Flow.Publisher<DesktopVirtualInputState> virtualInputs(DesktopSessionOwner owner, String sessionId) {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { }
                @Override public void cancel() { }
            });
            subscriber.onComplete();
        };
    }
    DesktopSessionInfo info(DesktopSessionOwner owner, String sessionId);
    /** Supplemental identity history; does not capture or authorize input. */
    default Optional<DesktopSurfaceSnapshot> surface(DesktopSessionOwner owner, String sessionId) {
        return Optional.empty();
    }
    void closeSession(DesktopSessionOwner owner, String sessionId);
    void closeScope(String workspaceId, String scopeId);
    void closeWorkspace(String workspaceId);
    @Override void close();
}
