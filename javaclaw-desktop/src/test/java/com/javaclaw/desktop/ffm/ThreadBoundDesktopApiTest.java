package com.javaclaw.desktop.ffm;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopApplicationCatalog;
import com.javaclaw.desktop.api.DesktopApplicationLaunch;
import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopElement;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopInputPolicy;
import com.javaclaw.desktop.nativebridge.DesktopBridge.NativeWindow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThreadBoundDesktopApiTest {
    private static final NativeWindow WINDOW = new NativeWindow(10, 20, 30, 0, 0, 2, 2, 2, "app", "title", "app");
    private static final DesktopFrame FRAME = new DesktopFrame("target", 1, 1, 1, 1, 4, new byte[4]);
    private static final DesktopAction ACTION = new DesktopAction(DesktopAction.Kind.TYPE, -1, -1, 0, 0, 0, "text", 1);
    private static final DesktopActionResult UNKNOWN = new DesktopActionResult(DesktopActionResult.Status.UNKNOWN, "partial", 1);

    @Test void virtualCallerRunsTheWholeNativeScopeOnOnePlatformThreadAcrossFutureAndSleep() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        AtomicReference<Thread> before = new AtomicReference<>(), after = new AtomicReference<>();
        try (var executor = new ThreadBoundDesktopApi.DesktopExecutor(1, 2)) {
            var api = new ThreadBoundDesktopApi(new FakeApi() {
                @Override public List<NativeWindow> windows() {
                    before.set(Thread.currentThread());
                    entered.countDown();
                    release.join();
                    try { Thread.sleep(5); }
                    catch (InterruptedException failure) { throw new AssertionError("Worker was interrupted", failure); }
                    after.set(Thread.currentThread());
                    return super.windows();
                }
            }, executor);
            VirtualCall<List<NativeWindow>> caller = virtualCall(api::windows);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(before.get().isVirtual());
                assertTrue(before.get().isDaemon());
                assertTrue(before.get().getName().startsWith("JavaClaw-desktop-ffm-"));
                assertFalse(caller.result().isDone());
            } finally { release.complete(null); }
            assertEquals(List.of(WINDOW), caller.finish());
            assertSame(before.get(), after.get());
        }
    }

    @Test void everyApiAndSessionMethodIncludingCloseUsesThePlatformExecutor() throws Exception {
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
        try (var executor = new ThreadBoundDesktopApi.DesktopExecutor(1, 4)) {
            var api = new ThreadBoundDesktopApi(new FakeApi() {
                @Override void record(String operation) {
                    super.record(operation);
                    calls.add(operation);
                    threads.add(Thread.currentThread());
                }
            }, executor);
            virtualCall(() -> {
                assertTrue(Thread.currentThread().isVirtual());
                api.availability(false, DesktopInputPolicy.BACKGROUND_STRICT);
                api.availability(true, DesktopInputPolicy.SYSTEM_EXPLICIT);
                assertEquals(List.of(WINDOW), api.windows());
                assertTrue(api.applications().applications().isEmpty());
                assertEquals(10, api.launch("fixture").processId());
                assertEquals(Optional.of(true), api.windowExists(10, 20, 30));
                SystemDesktopSession session = api.open(WINDOW);
                assertSame(WINDOW, session.current());
                assertSame(FRAME, session.capture("target", 100).orElseThrow());
                assertEquals(List.of(), session.elements(FRAME));
                assertEquals("diagnostic", session.diagnostics());
                assertEquals(Optional.of(false), session.targetActive());
                session.prepareForeground();
                session.restoreForeground();
                assertSame(UNKNOWN, session.perform(ACTION, true));
                session.close();
                return null;
            }).finish();
            assertEquals(List.of("availability:false", "availability:true", "windows", "applications", "launch:fixture",
                    "windowExists", "open", "current", "capture", "elements", "diagnostics", "targetActive",
                    "prepareForeground", "restoreForeground", "perform", "close"), calls);
            assertEquals(1, threads.stream().distinct().count());
        }
    }

    @Test void workerReentryExecutesInlineWithoutWaitingForItsOwnSingleWorker() throws Exception {
        AtomicReference<ThreadBoundDesktopApi> bound = new AtomicReference<>();
        AtomicReference<Thread> outer = new AtomicReference<>(), inner = new AtomicReference<>();
        try (var executor = new ThreadBoundDesktopApi.DesktopExecutor(1, 1)) {
            var api = new ThreadBoundDesktopApi(new FakeApi() {
                @Override public DesktopAvailability availability(boolean request, DesktopInputPolicy policy) {
                    outer.set(Thread.currentThread());
                    assertEquals(List.of(WINDOW), bound.get().windows());
                    return super.availability(request, policy);
                }
                @Override public List<NativeWindow> windows() {
                    inner.set(Thread.currentThread());
                    return super.windows();
                }
            }, executor);
            bound.set(api);
            virtualCall(() -> api.availability(false, DesktopInputPolicy.BACKGROUND_STRICT)).finish();
            assertSame(outer.get(), inner.get());
        }
    }

    @Test void originalRuntimeExceptionAndLinkageErrorAreReturnedWithoutReexecution() throws Exception {
        IllegalArgumentException runtime = new IllegalArgumentException("original");
        LinkageError linkage = new LinkageError("original native failure");
        AtomicInteger applications = new AtomicInteger(), launches = new AtomicInteger();
        try (var executor = new ThreadBoundDesktopApi.DesktopExecutor(1, 2)) {
            var api = new ThreadBoundDesktopApi(new FakeApi() {
                @Override public DesktopApplicationCatalog applications() { applications.incrementAndGet(); throw runtime; }
                @Override public DesktopApplicationLaunch launch(String application) { launches.incrementAndGet(); throw linkage; }
            }, executor);
            ExecutionException first = assertThrows(ExecutionException.class, () -> virtualCall(api::applications).finish());
            ExecutionException second = assertThrows(ExecutionException.class, () -> virtualCall(() -> api.launch("fixture")).finish());
            assertSame(runtime, first.getCause());
            assertSame(linkage, second.getCause());
            assertEquals(1, applications.get());
            assertEquals(1, launches.get());
        }
    }

    @Test void interruptAfterInputStartedWaitsForItsOnlyRealResultAndRestoresCallerInterrupt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        AtomicInteger dispatches = new AtomicInteger();
        try (var executor = new ThreadBoundDesktopApi.DesktopExecutor(1, 2)) {
            var api = new ThreadBoundDesktopApi(new FakeApi() {
                @Override public SystemDesktopSession open(NativeWindow window) {
                    return new FakeSession(this::record) {
                        @Override public DesktopActionResult perform(DesktopAction action, boolean foreground) {
                            dispatches.incrementAndGet();
                            entered.countDown();
                            release.join();
                            assertFalse(Thread.currentThread().isInterrupted());
                            return super.perform(action, foreground);
                        }
                    };
                }
            }, executor);
            SystemDesktopSession session = api.open(WINDOW);
            VirtualCall<DesktopActionResult> caller = virtualCall(() -> session.perform(ACTION, true));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                caller.thread().interrupt();
                assertFalse(caller.result().isDone());
            } finally { release.complete(null); }
            assertSame(UNKNOWN, caller.finish());
            assertTrue(caller.interrupted().get());
            assertEquals(1, dispatches.get());
            session.close();
        }
    }

    @Test void queuedInterruptedOperationIsCancelledBeforeAnyNativeDispatch() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        AtomicInteger launches = new AtomicInteger();
        try (var executor = new ThreadBoundDesktopApi.DesktopExecutor(1, 1)) {
            var api = blockingApi(executor, entered, release, launches, new AtomicInteger(), new AtomicInteger());
            VirtualCall<List<NativeWindow>> blocker = virtualCall(api::windows);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                VirtualCall<DesktopApplicationLaunch> queued = virtualCall(() -> api.launch("fixture"));
                await(() -> executor.queuedOperations() == 1);
                queued.thread().interrupt();
                ExecutionException failure = assertThrows(ExecutionException.class, queued::finish);
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                assertInstanceOf(InterruptedException.class, failure.getCause().getCause());
                assertTrue(queued.interrupted().get());
                assertEquals(0, launches.get());
            } finally { release.complete(null); }
            blocker.finish();
            assertTrue(api.availability(false, DesktopInputPolicy.BACKGROUND_STRICT).available());
            assertEquals(0, launches.get());
        }
    }

    @Test void saturatedQueueRejectsNormalWorkButInterruptedCloseStillRunsOnAWorker() throws Exception {
        cleanupSurvivesSaturationAndInterrupt(true);
    }

    @Test void interruptedForegroundRestoreStillRunsWhenThePlatformQueueIsSaturated() throws Exception {
        cleanupSurvivesSaturationAndInterrupt(false);
    }

    private static void cleanupSurvivesSaturationAndInterrupt(boolean close) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cleanupRequested = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        AtomicInteger launches = new AtomicInteger(), closes = new AtomicInteger(), restores = new AtomicInteger();
        try (var executor = new ThreadBoundDesktopApi.DesktopExecutor(1, 1)) {
            var api = blockingApi(executor, entered, release, launches, closes, restores);
            SystemDesktopSession session = api.open(WINDOW);
            VirtualCall<List<NativeWindow>> blocker = virtualCall(api::windows);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                VirtualCall<DesktopAvailability> queued = virtualCall(() -> api.availability(false, DesktopInputPolicy.BACKGROUND_STRICT));
                await(() -> executor.queuedOperations() == 1);
                ExecutionException rejected = assertThrows(ExecutionException.class, () -> virtualCall(() -> api.launch("fixture")).finish());
                assertInstanceOf(RejectedExecutionException.class, rejected.getCause());
                assertEquals(0, launches.get());
                VirtualCall<Void> closing = virtualCall(() -> {
                    Thread.currentThread().interrupt();
                    cleanupRequested.countDown();
                    if (close) session.close();
                    else session.restoreForeground();
                    return null;
                });
                assertTrue(cleanupRequested.await(5, TimeUnit.SECONDS));
                await(() -> closing.thread().getState() == Thread.State.WAITING);
                assertFalse(closing.result().isDone());
                release.complete(null);
                blocker.finish();
                queued.finish();
                closing.finish();
                assertTrue(closing.interrupted().get());
                assertEquals(close ? 1 : 0, closes.get());
                assertEquals(close ? 0 : 1, restores.get());
                if (!close) session.close();
            } finally { release.complete(null); }
        }
    }

    @Test void alreadyInterruptedCallerNeverEnqueuesAnOrdinaryOperation() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        try (var executor = new ThreadBoundDesktopApi.DesktopExecutor(1, 1)) {
            var api = new ThreadBoundDesktopApi(new FakeApi() {
                @Override public DesktopApplicationLaunch launch(String application) {
                    launches.incrementAndGet();
                    return super.launch(application);
                }
            }, executor);
            VirtualCall<DesktopApplicationLaunch> caller = virtualCall(() -> {
                Thread.currentThread().interrupt();
                return api.launch("fixture");
            });
            ExecutionException failure = assertThrows(ExecutionException.class, caller::finish);
            assertInstanceOf(InterruptedException.class, failure.getCause().getCause());
            assertTrue(caller.interrupted().get());
            assertEquals(0, launches.get());
        }
    }

    private static ThreadBoundDesktopApi blockingApi(ThreadBoundDesktopApi.DesktopExecutor executor,
            CountDownLatch entered, CompletableFuture<Void> release, AtomicInteger launches, AtomicInteger closes,
            AtomicInteger restores) {
        return new ThreadBoundDesktopApi(new FakeApi() {
            @Override void record(String operation) {
                super.record(operation);
                if (operation.equals("restoreForeground")) restores.incrementAndGet();
            }
            @Override public List<NativeWindow> windows() {
                entered.countDown();
                release.join();
                return super.windows();
            }
            @Override public DesktopApplicationLaunch launch(String application) {
                launches.incrementAndGet();
                return super.launch(application);
            }
            @Override public SystemDesktopSession open(NativeWindow window) {
                return new FakeSession(this::record) {
                    @Override public void close() { closes.incrementAndGet(); super.close(); }
                };
            }
        }, executor);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
        assertTrue(condition.getAsBoolean(), "Expected the bounded executor operation to be queued");
    }

    private static <T> VirtualCall<T> virtualCall(Callable<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread thread = Thread.ofVirtual().start(() -> {
            try { result.complete(operation.call()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        return new VirtualCall<>(thread, result, interrupted);
    }

    private record VirtualCall<T>(Thread thread, CompletableFuture<T> result, AtomicBoolean interrupted) {
        T finish() throws Exception {
            try { return result.get(5, TimeUnit.SECONDS); }
            finally { thread.join(5000); assertFalse(thread.isAlive(), "Virtual caller did not terminate"); }
        }
    }

    private static class FakeApi implements SystemDesktopApi {
        void record(String operation) { assertFalse(Thread.currentThread().isVirtual(), operation); }
        @Override public DesktopAvailability availability(boolean request, DesktopInputPolicy policy) {
            record("availability:" + request); return new DesktopAvailability(true, "fixture", 1, "");
        }
        @Override public List<NativeWindow> windows() { record("windows"); return List.of(WINDOW); }
        @Override public DesktopApplicationCatalog applications() { record("applications"); return new DesktopApplicationCatalog(List.of(), false); }
        @Override public DesktopApplicationLaunch launch(String application) { record("launch:" + application); return new DesktopApplicationLaunch(10, "fixture"); }
        @Override public Optional<Boolean> windowExists(long processId, long windowId, long processInstanceId) { record("windowExists"); return Optional.of(true); }
        @Override public SystemDesktopSession open(NativeWindow window) { record("open"); return new FakeSession(this::record); }
    }

    private static class FakeSession implements SystemDesktopSession {
        private final Consumer<String> record;
        FakeSession(Consumer<String> record) { this.record = record; }
        @Override public NativeWindow current() { record.accept("current"); return WINDOW; }
        @Override public Optional<DesktopFrame> capture(String targetId, int timeoutMillis) { record.accept("capture"); return Optional.of(FRAME); }
        @Override public List<DesktopElement> elements(DesktopFrame frame) { record.accept("elements"); return List.of(); }
        @Override public String diagnostics() { record.accept("diagnostics"); return "diagnostic"; }
        @Override public Optional<Boolean> targetActive() { record.accept("targetActive"); return Optional.of(false); }
        @Override public void prepareForeground() { record.accept("prepareForeground"); }
        @Override public void restoreForeground() { record.accept("restoreForeground"); }
        @Override public DesktopActionResult perform(DesktopAction action, boolean foreground) { record.accept("perform"); return UNKNOWN; }
        @Override public void close() { record.accept("close"); }
    }
}
