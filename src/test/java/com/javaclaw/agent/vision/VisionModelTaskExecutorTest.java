package com.javaclaw.agent.vision;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.CancellationToken;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.framework.spi.RunCancelledException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class VisionModelTaskExecutorTest {
    @Test
    void publicDesktopInspectionRespectsOwnerCancellationBeforeSendingImage() {
        AtomicBoolean sent = new AtomicBoolean();
        var vision = new VisionPreprocessor(request -> {
            sent.set(true);
            return new CompletableFuture<>();
        }, RunId.random(), () -> true);

        assertThrows(RunCancelledException.class, () -> vision.inspectDesktopFrameStructured(
                new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_ARGB),
                "查看本地模型", true));
        assertFalse(sent.get());
    }

    @Test
    void cancelsUnderlyingRequestAtDeadlineEvenWhenGatewayIgnoresFutureCancellation() throws Exception {
        var actualCancellation = new CountDownLatch(1);
        var futureCancellation = new CountDownLatch(1);
        var never = new CompletableFuture<ModelTaskResult>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                futureCancellation.countDown();
                return false;
            }
        };
        var owner = new AtomicBoolean();
        var executor = new VisionModelTaskExecutor(request -> {
            request.cancellation().onCancel(actualCancellation::countDown);
            return never;
        }, RunId.random(), owner::get);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            var failure = assertThrows(TimeoutException.class,
                    () -> call(executor, Duration.ofMillis(80)));
            assertTrue(failure.getMessage().contains("vision.test"));
        });
        assertTrue(actualCancellation.await(1, TimeUnit.SECONDS));
        assertTrue(futureCancellation.await(1, TimeUnit.SECONDS));
        assertFalse(owner.get(), "一次视觉超时不能停止整个用户任务");
    }

    @Test
    void pollsCancellationTokensWithoutNotificationSupport() throws Exception {
        var owner = new AtomicBoolean();
        var entered = new CountDownLatch(1);
        var actualCancellation = new CountDownLatch(1);
        var executor = new VisionModelTaskExecutor(request -> {
            request.cancellation().onCancel(actualCancellation::countDown);
            entered.countDown();
            return new CompletableFuture<>();
        }, RunId.random(), owner::get);
        var result = CompletableFuture.runAsync(() -> {
            assertThrows(RunCancelledException.class, () -> call(executor, Duration.ofSeconds(30)));
        });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        owner.set(true);
        result.get(2, TimeUnit.SECONDS);
        assertTrue(actualCancellation.await(1, TimeUnit.SECONDS));
    }

    @Test
    void exceptionalGatewayCompletionDuringOwnerCancellationPreservesCancellation() throws Exception {
        var owner = new AtomicBoolean();
        var entered = new CountDownLatch(1);
        var pending = new CompletableFuture<ModelTaskResult>();
        var executor = new VisionModelTaskExecutor(request -> {
            entered.countDown();
            return pending;
        }, RunId.random(), owner::get);
        var waiting = CompletableFuture.runAsync(() ->
                assertThrows(RunCancelledException.class, () -> call(executor, Duration.ofSeconds(30))));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        owner.set(true);
        pending.completeExceptionally(new RunCancelledException());
        waiting.get(2, TimeUnit.SECONDS);
    }

    @Test
    void ownerBudgetBoundsRequestAndWait() {
        var requestTimeout = new AtomicReference<Duration>();
        CancellationToken owner = new CancellationToken() {
            @Override public boolean cancelled() { return false; }
            @Override public Duration remaining() { return Duration.ofMillis(80); }
        };
        var executor = new VisionModelTaskExecutor(request -> {
            requestTimeout.set(request.timeout());
            return new CompletableFuture<>();
        }, RunId.random(), owner);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThrows(TimeoutException.class, () -> call(executor, Duration.ofSeconds(30))));
        assertEquals(Duration.ofMillis(80), requestTimeout.get());
    }

    @Test
    void keepsSuccessfulOutputAndDisablesRetriesAndCache() throws Exception {
        var expected = JsonNodeFactory.instance.objectNode().put("text", "本地模型列表");
        var owner = RunId.random();
        var executor = new VisionModelTaskExecutor(request -> {
            assertEquals(owner, request.ownerRunId());
            assertEquals(0, request.maxRetries());
            assertFalse(request.cacheAllowed());
            return CompletableFuture.completedFuture(new ModelTaskResult(expected, "test", 1, 2, false, Map.of()));
        }, owner, () -> false);
        assertEquals(expected, call(executor, Duration.ofSeconds(1)));
    }

    @Test
    void interruptStopsRequestAndPreservesInterruptFlag() throws Exception {
        var entered = new CountDownLatch(1);
        var actualCancellation = new CountDownLatch(1);
        var executor = new VisionModelTaskExecutor(request -> {
            request.cancellation().onCancel(actualCancellation::countDown);
            entered.countDown();
            return new CompletableFuture<>();
        }, RunId.random(), () -> false);
        var stopped = new CompletableFuture<Boolean>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                assertThrows(RunCancelledException.class, () -> call(executor, Duration.ofSeconds(30)));
                stopped.complete(Thread.currentThread().isInterrupted());
            } catch (Throwable failure) { stopped.completeExceptionally(failure); }
        });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        worker.interrupt();
        assertTrue(stopped.get(2, TimeUnit.SECONDS));
        assertTrue(actualCancellation.await(1, TimeUnit.SECONDS));
    }

    @Test
    void includesSuppressedAuditFailureWithoutLeakingCredentials() {
        var timeout = new TimeoutException("任务超时: vision.desktop.structured");
        timeout.addSuppressed(new IllegalStateException("owner changed state"));
        timeout.addSuppressed(new IllegalArgumentException("password: real-secret-value"));
        String diagnostic = VisionModelTaskExecutor.failureSummary(new ExecutionException(timeout));
        assertTrue(diagnostic.contains("TimeoutException"));
        assertTrue(diagnostic.contains("suppressed IllegalStateException: owner changed state"));
        assertTrue(diagnostic.contains("<敏感内容已隐藏>"));
        assertFalse(diagnostic.contains("real-secret-value"));
        assertTrue(VisionModelTaskExecutor.failureSummary(new TimeoutException()).contains("TimeoutException"));
        timeout.addSuppressed(new IllegalStateException());
        assertTrue(VisionModelTaskExecutor.failureSummary(timeout).contains("suppressed IllegalStateException"));
    }

    @Test
    void blockedCancelCallbacksAndFutureCancelCannotHoldTheWaiter() throws Exception {
        var release = new CountDownLatch(1);
        var callbackEntered = new CountDownLatch(1);
        var futureCancelEntered = new CountDownLatch(1);
        var otherCallback = new CountDownLatch(1);
        var token = new AtomicReference<CancellationToken>();
        var executor = new VisionModelTaskExecutor(request -> {
            token.set(request.cancellation());
            request.cancellation().onCancel(() -> {
                callbackEntered.countDown();
                awaitIgnoringInterrupt(release);
            });
            request.cancellation().onCancel(otherCallback::countDown);
            return new CompletableFuture<>() {
                @Override public boolean cancel(boolean mayInterruptIfRunning) {
                    futureCancelEntered.countDown();
                    awaitIgnoringInterrupt(release);
                    return super.cancel(mayInterruptIfRunning);
                }
            };
        }, RunId.random(), () -> false);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    assertThrows(TimeoutException.class, () -> call(executor, Duration.ofMillis(80))));
            assertTrue(token.get().cancelled());
            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
            assertTrue(futureCancelEntered.await(1, TimeUnit.SECONDS));
            assertTrue(otherCallback.await(1, TimeUnit.SECONDS), "阻塞消费者不得挡住其余取消通知");
        } finally { release.countDown(); }
    }

    @Test
    void blockedGatewayAdmissionIsAlsoBoundedAndLateSubmissionIsCancelled() throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var lateCancel = new CountDownLatch(1);
        var executor = new VisionModelTaskExecutor(request -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
            return new CompletableFuture<>() {
                @Override public boolean cancel(boolean mayInterruptIfRunning) {
                    lateCancel.countDown();
                    return super.cancel(mayInterruptIfRunning);
                }
            };
        }, RunId.random(), () -> false);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    assertThrows(TimeoutException.class, () -> call(executor, Duration.ofMillis(150))));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
        } finally { release.countDown(); }
        assertTrue(lateCancel.await(1, TimeUnit.SECONDS));
    }

    private static void awaitIgnoringInterrupt(CountDownLatch release) {
        boolean interrupted = false;
        while (release.getCount() > 0) {
            try { release.await(); } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static com.fasterxml.jackson.databind.JsonNode call(VisionModelTaskExecutor executor, Duration timeout)
            throws Exception {
        var node = JsonNodeFactory.instance.objectNode();
        return executor.execute("vision.test", node, List.of(), node, timeout);
    }
}
