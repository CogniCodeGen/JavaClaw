package com.javaclaw.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSessionService;
import com.javaclaw.desktop.api.DesktopSessionState;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.CancellationToken;
import com.javaclaw.framework.spi.RunCancelledException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DesktopInteractionEventSourceTest {
    @Test
    void onlyFreshChangedFramesWakeTheOwnerAndBothSubscriptionsAreReleased() throws Exception {
        Fixture fixture = new Fixture();
        var result = fixture.await(30_000);
        fixture.frames.emit(frame(99, 2, 2));
        fixture.frames.emit(frame(101, 1, 1));
        assertFalse(result.isDone());
        fixture.frames.emit(frame(102, 1, 2));
        JsonNode event = result.get(2, TimeUnit.SECONDS);
        assertEquals("FRAME_AVAILABLE", event.path("event").asText());
        assertEquals("session", event.path("sessionId").asText());
        assertEquals("target", event.path("targetId").asText());
        assertEquals(102, event.path("capturedAtMillis").asLong());
        fixture.assertReleased();
        assertEquals(1, fixture.snapshots.get(), "订阅后只读一次快照，不轮询");
        fixture.states.emit(new DesktopSessionState("session", DesktopSessionState.Kind.CLOSED, "", 103));
        assertEquals(event, result.join());
    }

    @Test
    void missingLegacyGenerationOrRevisionAndChangedGenerationAreConservativeWakeSignals() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            Fixture fixture = new Fixture();
            var wait = fixture.waitSpec(30_000);
            if (scenario == 0) wait.remove("afterWindowGeneration");
            if (scenario == 1) wait.remove("afterContentRevision");
            var result = fixture.source.await(fixture.request, wait, fixture.cancellation).toCompletableFuture();
            fixture.frames.emit(frame(101, scenario == 2 ? 2 : 1, 1));
            assertEquals("FRAME_AVAILABLE", result.get(2, TimeUnit.SECONDS).path("event").asText());
            fixture.assertReleased();
        }
    }

    @Test
    void onlyFreshNonLiveStateChangesWakeTheWaiter() throws Exception {
        Fixture fixture = new Fixture();
        var result = fixture.await(30_000);
        fixture.states.emit(new DesktopSessionState("session", DesktopSessionState.Kind.LIVE, "", 101));
        fixture.states.emit(new DesktopSessionState("session", DesktopSessionState.Kind.PAUSED, "", 100));
        assertFalse(result.isDone());
        fixture.states.emit(new DesktopSessionState("session", DesktopSessionState.Kind.CLOSED, "", 102));
        assertEquals("STATE_CHANGED", result.get(2, TimeUnit.SECONDS).path("event").asText());
        assertEquals("CLOSED", result.join().path("state").asText());
        fixture.assertReleased();
    }

    @Test
    void snapshotClosesTheSubscriptionRaceWithoutGrantingInputOrTaskAcceptance() throws Exception {
        Fixture fixture = new Fixture();
        fixture.snapshot = CompletableFuture.completedFuture(Optional.of(frame(102, 2, 1)));
        JsonNode result = fixture.await(30_000).get(2, TimeUnit.SECONDS);
        assertEquals("FRAME_AVAILABLE", result.path("event").asText());
        assertFalse(result.has("inputAuthority"));
        assertFalse(result.has("acceptanceEvidence"));
        fixture.assertReleased();
    }

    @Test
    void publisherCompletionDoesNotClaimAChangeAndTimeoutIsBounded() throws Exception {
        Fixture fixture = new Fixture();
        var result = fixture.await(0);
        fixture.frames.subscriber.onComplete();
        JsonNode timeout = result.get(2, TimeUnit.SECONDS);
        assertEquals("WAIT_TIMEOUT", timeout.path("event").asText());
        assertEquals(1, timeout.path("timeoutMillis").asLong());
        fixture.assertReleased();
    }

    @Test
    void nativeFailureAndSnapshotFailureAreNotConvertedToSuccessfulObservations() {
        for (int scenario = 0; scenario < 3; scenario++) {
            Fixture fixture = new Fixture();
            var failure = new IllegalStateException("原生源不可用");
            if (scenario == 0) fixture.statesFailure = failure;
            if (scenario == 1) fixture.snapshot = CompletableFuture.failedFuture(failure);
            var result = fixture.await(30_000);
            if (scenario == 2) fixture.frames.subscriber.onError(failure);
            assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
            assertEquals(1, fixture.frames.cancelled.get());
            assertEquals(scenario == 0 ? 0 : 1, fixture.states.cancelled.get());
            assertEquals(1, fixture.cancellation.closed.get());
        }
    }

    @Test
    void cancellationBeforeOrAfterSubscriptionRejectsTheWaitAndReleasesLateSubscribers() {
        for (boolean before : new boolean[]{false, true}) {
            Fixture fixture = new Fixture();
            fixture.cancellation.cancelled = before;
            var result = fixture.await(30_000);
            if (!before) fixture.cancellation.cancel();
            assertInstanceOf(RunCancelledException.class, assertThrows(CompletionException.class, result::join).getCause());
            fixture.assertReleased();
        }
    }

    @Test
    void duplicateSubscriptionsAreCancelledWithoutReplacingTheFirst() throws Exception {
        Fixture fixture = new Fixture();
        var result = fixture.await(30_000);
        fixture.frames.subscriber.onSubscribe(fixture.frames.subscription());
        assertEquals(1, fixture.frames.cancelled.get());
        assertEquals(1, fixture.frames.requested.get());
        fixture.frames.emit(frame(101, 2, 1));
        assertEquals("FRAME_AVAILABLE", result.get(2, TimeUnit.SECONDS).path("event").asText());
        assertEquals(2, fixture.frames.cancelled.get());
        assertEquals(1, fixture.states.cancelled.get());
    }

    private static DesktopFrame frame(long time, long generation, long revision) {
        return new DesktopFrame("target", generation, time, 1, 1, 4, new byte[4], revision);
    }

    private static final class Fixture {
        final RunRequest request = RunRequest.builder().agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "thread")).input(InputBlock.text("等待窗口变化")).build();
        final Publisher<DesktopFrame> frames = new Publisher<>();
        final Publisher<DesktopSessionState> states = new Publisher<>();
        final Cancellation cancellation = new Cancellation();
        final AtomicInteger snapshots = new AtomicInteger();
        CompletableFuture<Optional<DesktopFrame>> snapshot = CompletableFuture.completedFuture(Optional.empty());
        RuntimeException statesFailure;
        final DesktopSessionService sessions = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(), new Class<?>[]{DesktopSessionService.class}, (proxy, method, args) -> {
                    assertEquals(new DesktopSessionOwner("workspace", "thread", "chat", "desktop"), args[0]);
                    assertEquals("session", args[1]);
                    return switch (method.getName()) {
                        case "frames" -> frames;
                        case "states" -> { if (statesFailure != null) throw statesFailure; yield states; }
                        case "snapshot" -> { snapshots.incrementAndGet(); yield snapshot; }
                        default -> throw new AssertionError("事件等待不得调用原生输入：" + method.getName());
                    };
                });
        final DesktopInteractionEventSource source = new DesktopInteractionEventSource(sessions);

        com.fasterxml.jackson.databind.node.ObjectNode waitSpec(long timeout) {
            return JsonNodeFactory.instance.objectNode().put("sessionId", "session")
                    .put("afterCapturedAtMillis", 100).put("afterWindowGeneration", 1)
                    .put("afterContentRevision", 1).put("timeoutMillis", timeout);
        }
        CompletableFuture<JsonNode> await(long timeout) {
            return source.await(request, waitSpec(timeout), cancellation).toCompletableFuture();
        }
        void assertReleased() {
            cancellation.released.orTimeout(2, TimeUnit.SECONDS).join();
            assertEquals(1, frames.cancelled.get());
            assertEquals(1, states.cancelled.get());
            assertEquals(1, cancellation.closed.get());
        }
    }

    private static final class Publisher<T> implements Flow.Publisher<T> {
        Flow.Subscriber<? super T> subscriber;
        final AtomicInteger requested = new AtomicInteger();
        final AtomicInteger cancelled = new AtomicInteger();
        @Override public void subscribe(Flow.Subscriber<? super T> value) {
            subscriber = value;
            value.onSubscribe(subscription());
        }
        Flow.Subscription subscription() {
            return new Flow.Subscription() {
                @Override public void request(long n) { assertEquals(Long.MAX_VALUE, n); requested.incrementAndGet(); }
                @Override public void cancel() { cancelled.incrementAndGet(); }
            };
        }
        void emit(T value) { subscriber.onNext(value); }
    }

    private static final class Cancellation implements CancellationToken {
        boolean cancelled;
        Runnable callback;
        final AtomicInteger closed = new AtomicInteger();
        final CompletableFuture<Void> released = new CompletableFuture<>();
        @Override public boolean cancelled() { return cancelled; }
        @Override public CancellationRegistration onCancel(Runnable callback) {
            this.callback = callback;
            if (cancelled) callback.run();
            return () -> { closed.incrementAndGet(); released.complete(null); };
        }
        void cancel() { cancelled = true; callback.run(); }
    }
}
