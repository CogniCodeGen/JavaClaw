package com.javaclaw.service.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerExecutorTest {

    @Test
    @Timeout(5)
    void runsCallsAndReportsTaskFailures() {
        try (RunnerExecutor executor = new RunnerExecutor()) {
            AtomicInteger runs = new AtomicInteger();
            executor.run("run", runs::incrementAndGet).toCompletableFuture().join();
            assertEquals(1, runs.get());
            assertEquals("value", executor.call("call", () -> "value")
                    .toCompletableFuture().join());

            CompletionException failure = assertThrows(CompletionException.class,
                    () -> executor.call("failure", () -> {
                        throw new IllegalStateException("synthetic failure");
                    }).toCompletableFuture().join());
            assertTrue(failure.getCause() instanceof IllegalStateException);
        }
    }

    @Test
    @Timeout(5)
    void validatesSchedulesAndRegistrationsCanCancel() throws Exception {
        try (RunnerExecutor executor = new RunnerExecutor()) {
            assertThrows(IllegalArgumentException.class,
                    () -> executor.scheduleAtFixedRate("null", null, null, () -> { }));
            assertThrows(IllegalArgumentException.class,
                    () -> executor.scheduleAtFixedRate("zero", null, Duration.ZERO, () -> { }));
            assertThrows(IllegalArgumentException.class,
                    () -> executor.scheduleAtFixedRate("negative", null,
                            Duration.ofMillis(-1), () -> { }));
            assertThrows(IllegalArgumentException.class,
                    () -> executor.schedule(Duration.ofMillis(-1), () -> { }));

            CountDownLatch once = new CountDownLatch(1);
            executor.schedule(null, once::countDown);
            assertTrue(once.await(2, TimeUnit.SECONDS));

            CountDownLatch repeated = new CountDownLatch(2);
            var registration = executor.scheduleAtFixedRate(
                    "periodic", Duration.ofMillis(-1), Duration.ofMillis(10),
                    repeated::countDown);
            assertTrue(repeated.await(2, TimeUnit.SECONDS));
            registration.close();
        }
    }

    @Test
    void rejectsNewWorkAfterCloseAndCloseIsIdempotent() {
        RunnerExecutor executor = new RunnerExecutor();
        executor.close();
        executor.close();

        CompletionException failure = assertThrows(CompletionException.class,
                () -> executor.call("closed", () -> "never").toCompletableFuture().join());
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertThrows(IllegalStateException.class, () -> executor.scheduleAtFixedRate(
                "closed", Duration.ZERO, Duration.ofSeconds(1), () -> { }));
        assertThrows(IllegalStateException.class,
                () -> executor.schedule(Duration.ZERO, () -> { }));
    }
}
