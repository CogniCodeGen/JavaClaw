package com.javaclaw.agent.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.core.api.ThreadId;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeEventBusTest {
    @Test
    void aSlowClientIsDroppedToResyncWithoutBlockingPublishers() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch resyncEntered = new CountDownLatch(1);
        CountDownLatch resync = new CountDownLatch(1);
        try (RuntimeEventBus bus = new RuntimeEventBus(1)) {
            bus.subscribe(new RuntimeStreams.ResyncAwareSubscriber() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    // Deliberately request nothing so the bounded queue fills immediately.
                    subscribed.countDown();
                }

                @Override
                public void onNext(ThreadEvent item) {}

                @Override
                public void onError(Throwable throwable) {}

                @Override
                public void onComplete() {}

                @Override
                public void resyncRequired(String threadId, long afterSequence) {
                    resyncEntered.countDown();
                    try {
                        resync.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            assertTrue(subscribed.await(1, TimeUnit.SECONDS));

            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                for (int index = 1; index <= 10_000; index++) {
                    bus.publish(event(index));
                }
            });

            assertTrue(resyncEntered.await(1, TimeUnit.SECONDS));
            assertTrue(bus.droppedEventCount() > 0);
            resync.countDown();
        }
    }

    @Test
    void fiveHundredSlowSubscriptionsRemainBoundedAndCannotDelayTurns() throws Exception {
        int subscribers = 500;
        CountDownLatch subscribed = new CountDownLatch(subscribers);
        AtomicInteger resyncSignals = new AtomicInteger();
        try (RuntimeEventBus bus = new RuntimeEventBus(4)) {
            for (int index = 0; index < subscribers; index++) {
                bus.subscribe(new RuntimeStreams.ResyncAwareSubscriber() {
                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        // A permanently stalled client is the worst case for the bounded buffer.
                        subscribed.countDown();
                    }

                    @Override
                    public void onNext(ThreadEvent item) {}

                    @Override
                    public void onError(Throwable throwable) {}

                    @Override
                    public void onComplete() {}

                    @Override
                    public void resyncRequired(String threadId, long afterSequence) {
                        resyncSignals.incrementAndGet();
                    }
                });
            }
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));

            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                for (int sequence = 1; sequence <= 100; sequence++) {
                    bus.publish(event(sequence));
                }
            });

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (resyncSignals.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(bus.droppedEventCount() > 0);
            assertTrue(resyncSignals.get() > 0, "stalled subscribers must receive bounded resync signals");
        }
    }

    private static ThreadEvent event(long sequence) {
        return new ThreadEvent(
                "event_" + sequence,
                new ThreadId("thread"),
                null,
                sequence,
                "test/event",
                1,
                null,
                null,
                Map.of(),
                Instant.now());
    }
}
