package com.javaclaw.ui.javafx.desktop;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaclaw.desktop.api.DesktopActionEvent;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopSessionInfo;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSessionService;
import com.javaclaw.desktop.api.DesktopSessionState;
import com.javaclaw.desktop.api.DesktopTarget;
import com.javaclaw.platform.fx.FxDispatcher;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DesktopPreviewWindowTest {
    @Test
    void closedPreviewCancelsEverySubscriptionBeforeQueuedShowCanCreateAStage() {
        List<Runnable> fxTasks = new ArrayList<>();
        FxDispatcher fx = new FxDispatcher(() -> false, fxTasks::add);
        TrackingPublisher<DesktopFrame> frames = new TrackingPublisher<>();
        TrackingPublisher<DesktopSessionState> states = new TrackingPublisher<>();
        TrackingPublisher<DesktopActionEvent> actions = new TrackingPublisher<>();
        DesktopSessionService service = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(),
                new Class<?>[] {DesktopSessionService.class}, (proxy, method, args) ->
                        switch (method.getName()) {
                            case "frames" -> frames;
                            case "states" -> states;
                            case "actions" -> actions;
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
        DesktopTarget target = new DesktopTarget("test", "target", 9, "App", "Window",
                0, 0, 10, 10, DesktopTarget.VISIBLE);
        DesktopSessionOwner owner = new DesktopSessionOwner("workspace", "scope", "agent", "run");
        DesktopSessionInfo info = new DesktopSessionInfo("session", target, true, false);
        try (DesktopPreviewWindow preview = new DesktopPreviewWindow(fx)) {
            preview.opened(owner, info, service);
            assertTrue(frames.requested.get());
            assertTrue(states.requested.get());
            assertTrue(actions.requested.get());
            preview.closed(info.sessionId());
            assertTrue(frames.cancelled.get());
            assertTrue(states.cancelled.get());
            assertTrue(actions.cancelled.get());
            for (Runnable task : List.copyOf(fxTasks)) task.run();
        }
    }

    private static final class TrackingPublisher<T> implements Flow.Publisher<T> {
        final AtomicBoolean requested = new AtomicBoolean();
        final AtomicBoolean cancelled = new AtomicBoolean();

        @Override public void subscribe(Flow.Subscriber<? super T> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long count) { requested.set(count == Long.MAX_VALUE); }
                @Override public void cancel() { cancelled.set(true); }
            });
        }
    }
}
