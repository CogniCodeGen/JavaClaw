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
    /** Read-only installed application metadata within the owner's enabled desktop scope. */
    default CompletionStage<DesktopApplicationCatalog> discoverApplications(DesktopSessionOwner owner) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException("当前桌面平台不支持安装应用发现"));
    }
    CompletionStage<DesktopApplicationLaunchResult> launchApplication(
            DesktopSessionOwner owner, String application);
    CompletionStage<DesktopSessionInfo> open(DesktopSessionOwner owner, String targetId,
                                              boolean requestControl);
    CompletionStage<Optional<DesktopFrame>> snapshot(DesktopSessionOwner owner, String sessionId);
    /** Capture a frame and bounded accessibility catalog without committing an input baseline. */
    default CompletionStage<Optional<DesktopObservation>> captureObservation(
            DesktopSessionOwner owner, String sessionId) {
        return snapshot(owner, sessionId).thenApply(frame -> frame.map(value ->
                new DesktopObservation(sessionId, java.util.UUID.randomUUID().toString(),
                        value, List.of())));
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
    /** Current capture state for explaining why a fresh frame is unavailable. */
    DesktopSessionState state(DesktopSessionOwner owner, String sessionId);
    CompletionStage<DesktopActionResult> perform(DesktopSessionOwner owner, String sessionId,
                                                  DesktopAction action);
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
    Flow.Publisher<DesktopFrame> frames(DesktopSessionOwner owner, String sessionId);
    Flow.Publisher<DesktopSessionState> states(DesktopSessionOwner owner, String sessionId);
    Flow.Publisher<DesktopActionEvent> actions(DesktopSessionOwner owner, String sessionId);
    DesktopSessionInfo info(DesktopSessionOwner owner, String sessionId);
    void closeSession(DesktopSessionOwner owner, String sessionId);
    void closeScope(String workspaceId, String scopeId);
    void closeWorkspace(String workspaceId);
    @Override void close();
}
