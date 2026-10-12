package com.javaclaw.ui.javafx.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaclaw.desktop.api.DesktopActionEvent;
import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopSessionInfo;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSessionService;
import com.javaclaw.desktop.api.DesktopSessionState;
import com.javaclaw.desktop.api.DesktopTarget;
import com.javaclaw.desktop.api.DesktopVirtualInputState;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class DesktopPreviewWindowTest {
    @Test
    void closedPreviewCancelsAllFourSubscriptionsBeforeQueuedShowCanCreateAWindow() {
        Fixture fixture = new Fixture();
        try (DesktopPreviewWindow preview = fixture.preview()) {
            preview.opened(fixture.owner, fixture.info, fixture.service);
            fixture.publishersRequested();
            preview.closed(fixture.info.sessionId());
            fixture.publishersCancelled();
            fixture.drain();
            assertEquals(0, fixture.creations.get());
        }
    }

    @Test
    void pauseInvalidatesQueuedPixelsAndInputAndRejectsFramesFromBeforePause() throws Exception {
        Fixture fixture = new Fixture();
        try (DesktopPreviewWindow preview = fixture.preview()) {
            preview.opened(fixture.owner, fixture.info, fixture.service);
            fixture.drain();
            fixture.state(DesktopSessionState.Kind.LIVE, 100);
            DesktopFrame first = frame(7, 101);
            fixture.frames.emit(first);
            fixture.await(() -> fixture.host.frame == first);
            DesktopVirtualInputState pointer = new DesktopVirtualInputState("session", 7,
                    1, 1, true, 1, DesktopVirtualInputState.Phase.PRESSED, 102);
            fixture.inputs.emit(pointer);
            fixture.drain();
            assertSame(pointer, fixture.host.input);

            fixture.frames.emit(frame(8, 103));
            fixture.inputs.emit(new DesktopVirtualInputState("session", 8,
                    1, 1, true, 1, DesktopVirtualInputState.Phase.PRESSED, 104));
            fixture.state(DesktopSessionState.Kind.PAUSED, 105);
            fixture.drain();
            assertNull(fixture.host.frame);
            assertNull(fixture.host.input);
            fixture.state(DesktopSessionState.Kind.LIVE, 106);
            fixture.frames.emit(first);
            fixture.drain();
            Thread.sleep(200);
            fixture.drain();
            assertNull(fixture.host.frame);
            DesktopFrame current = frame(9, 107);
            fixture.frames.emit(current);
            fixture.await(() -> fixture.host.frame == current);
            preview.closed("session");
            fixture.drain();
            assertTrue(fixture.host.closed);
            fixture.publishersCancelled();
            fixture.frames.emit(frame(10, 108));
            fixture.drain();
            assertNull(fixture.host.frame);
        }
    }

    @Test
    void foregroundHideCompletesOnUiQueueAndRestoresWithoutRecreatingHost() throws Exception {
        Fixture fixture = new Fixture();
        try (DesktopPreviewWindow preview = fixture.preview()) {
            preview.opened(fixture.owner, fixture.info, fixture.service);
            fixture.drain();
            var hidden = preview.beforeForegroundAction("session").toCompletableFuture();
            assertTrue(!hidden.isDone());
            fixture.drain();
            hidden.get(1, TimeUnit.SECONDS);
            assertTrue(!fixture.host.showing);
            preview.afterForegroundAction("session");
            fixture.drain();
            assertTrue(fixture.host.showing);
            assertEquals(1, fixture.creations.get());
        }
    }

    @Test
    void terminalReceiptClearsTargetAndLatePreparationCannotReviveIt() throws Exception {
        Fixture fixture = new Fixture();
        try (DesktopPreviewWindow preview = fixture.preview()) {
            preview.opened(fixture.owner, fixture.info, fixture.service);
            fixture.drain();
            fixture.state(DesktopSessionState.Kind.LIVE, 100);
            fixture.frames.emit(frame(7, 101));
            fixture.await(() -> fixture.host.frame != null);
            fixture.inputs.emit(new DesktopVirtualInputState("session", 7, 1, 1, true, 0,
                    DesktopVirtualInputState.Phase.TARGETING, 102, 1, null));
            fixture.drain();
            assertEquals(1, fixture.host.input.actionId());
            fixture.actions.emit(new DesktopActionEvent("session", DesktopAction.Kind.CLICK,
                    DesktopActionEvent.Phase.FINISHED, DesktopActionResult.Status.FAILED,
                    7, 103, 1, DesktopActionResult.Delivery.NOT_SENT,
                    DesktopActionResult.Reason.INVALID_TARGET));
            fixture.inputs.emit(new DesktopVirtualInputState("session", 7, 1, 1, true, 0,
                    DesktopVirtualInputState.Phase.TARGETING, 104, 1, null));
            fixture.drain();
            assertNull(fixture.host.input);
            assertTrue(fixture.host.action.contains("未派发"));
            assertTrue(fixture.host.action.contains("目标不可用"));
        }
    }

    @Test
    void coalescedTerminalPositionShowsItsResultWithoutAnyTransientPressEvent() throws Exception {
        Fixture fixture = new Fixture();
        try (DesktopPreviewWindow preview = fixture.preview()) {
            preview.opened(fixture.owner, fixture.info, fixture.service);
            fixture.drain();
            fixture.state(DesktopSessionState.Kind.LIVE, 100);
            fixture.frames.emit(frame(7, 101));
            fixture.await(() -> fixture.host.frame != null);
            fixture.inputs.emit(new DesktopVirtualInputState("session", 7, 1, 1, true, 0,
                    DesktopVirtualInputState.Phase.FINISHED, 102, 1,
                    DesktopActionResult.Delivery.MAYBE_SENT));
            fixture.drain();
            assertEquals(DesktopActionResult.Delivery.MAYBE_SENT, fixture.host.input.delivery());
            assertTrue(fixture.host.action.contains("结果未知"));
            fixture.state(DesktopSessionState.Kind.PAUSED, 103);
            fixture.drain();
            assertNull(fixture.host.input);
            fixture.state(DesktopSessionState.Kind.LIVE, 104);
            fixture.frames.emit(frame(7, 105));
            fixture.inputs.emit(new DesktopVirtualInputState("session", 7, 1, 1, true, 0,
                    DesktopVirtualInputState.Phase.FINISHED, 106, 1,
                    DesktopActionResult.Delivery.MAYBE_SENT));
            fixture.await(() -> fixture.host.frame != null);
            assertNull(fixture.host.input, "a finished marker cleared on pause must not return");
        }
    }

    private static DesktopFrame frame(long generation, long at) {
        return new DesktopFrame("target", generation, at, 2, 2, 8, new byte[16]);
    }

    private static final class Fixture {
        final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        final TrackingPublisher<DesktopFrame> frames = new TrackingPublisher<>();
        final TrackingPublisher<DesktopSessionState> states = new TrackingPublisher<>();
        final TrackingPublisher<DesktopActionEvent> actions = new TrackingPublisher<>();
        final TrackingPublisher<DesktopVirtualInputState> inputs = new TrackingPublisher<>();
        final AtomicInteger creations = new AtomicInteger();
        final FakeHost host = new FakeHost();
        final DesktopTarget target = new DesktopTarget("test", "target", 9, "App", "Window",
                0, 0, 10, 10, DesktopTarget.VISIBLE);
        final DesktopSessionOwner owner = new DesktopSessionOwner("workspace", "scope", "agent", "run");
        final DesktopSessionInfo info = new DesktopSessionInfo("session", target, true, false);
        final DesktopSessionService service = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(), new Class<?>[] {DesktopSessionService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "frames" -> frames;
                    case "states" -> states;
                    case "actions" -> actions;
                    case "virtualInputs" -> inputs;
                    case "info" -> info;
                    case "closeSession" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        DesktopPreviewWindow preview() {
            return new DesktopPreviewWindow(tasks::add, (app, title, position, stop, takeover, manual) -> {
                creations.incrementAndGet(); return host;
            });
        }
        void drain() { for (Runnable task; (task = tasks.poll()) != null;) task.run(); }
        void state(DesktopSessionState.Kind kind, long at) {
            states.emit(new DesktopSessionState("session", kind, "test", at));
        }
        void await(BooleanSupplier check) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            do { drain(); if (check.getAsBoolean()) return; Thread.sleep(10); }
            while (System.nanoTime() < until);
            assertTrue(check.getAsBoolean(), "preview work did not complete");
        }
        void publishersRequested() {
            assertTrue(frames.requested.get()); assertTrue(states.requested.get());
            assertTrue(actions.requested.get()); assertTrue(inputs.requested.get());
        }
        void publishersCancelled() {
            assertTrue(frames.cancelled.get()); assertTrue(states.cancelled.get());
            assertTrue(actions.cancelled.get()); assertTrue(inputs.cancelled.get());
        }
    }

    private static final class FakeHost implements DesktopPreviewHost {
        DesktopFrame frame;
        DesktopVirtualInputState input;
        String action = "";
        boolean showing;
        boolean closed;
        @Override public void show() { showing = true; }
        @Override public void hide() { showing = false; }
        @Override public void close() { closed = true; showing = false; clear(); }
        @Override public void frame(DesktopFrame frame, BufferedImage image) { this.frame = frame; }
        @Override public void clear() { frame = null; input = null; }
        @Override public void status(String text) { }
        @Override public void action(String text) { action = text; }
        @Override public void state(String text, boolean warning) { }
        @Override public void input(DesktopVirtualInputState input) { this.input = input; }
        @Override public void target(String app, String title) { }
        @Override public void takeoverEnabled(boolean enabled) { }
    }

    private static final class TrackingPublisher<T> implements Flow.Publisher<T> {
        final AtomicBoolean requested = new AtomicBoolean();
        final AtomicBoolean cancelled = new AtomicBoolean();
        Flow.Subscriber<? super T> subscriber;
        @Override public void subscribe(Flow.Subscriber<? super T> subscriber) {
            this.subscriber = subscriber;
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long count) { requested.set(count == Long.MAX_VALUE); }
                @Override public void cancel() { cancelled.set(true); }
            });
        }
        void emit(T value) { subscriber.onNext(value); }
    }
}
