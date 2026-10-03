package com.javaclaw.desktop.api;

/** Optional presentation hook; service and platform layers do not depend on JavaFX. */
public interface DesktopSessionObserver {
    void opened(DesktopSessionOwner owner, DesktopSessionInfo info, DesktopSessionService service);
    void closed(String sessionId);

    /** Hide a floating preview before system-wide foreground input can hit it. */
    default java.util.concurrent.CompletionStage<Void> beforeForegroundAction(String sessionId) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    default void afterForegroundAction(String sessionId) {}

    DesktopSessionObserver NONE = new DesktopSessionObserver() {
        @Override public void opened(DesktopSessionOwner owner, DesktopSessionInfo info,
                                     DesktopSessionService service) {}
        @Override public void closed(String sessionId) {}
    };
}
