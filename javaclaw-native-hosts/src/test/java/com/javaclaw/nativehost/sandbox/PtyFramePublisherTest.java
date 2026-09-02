package com.javaclaw.nativehost.sandbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.SandboxFrame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PtyFramePublisherTest {
    @Test
    void deliversQueuedFramesOnlyAfterDemandAndThenCompletes() throws Exception {
        PtyFramePublisher publisher = new PtyFramePublisher();
        RecordingSubscriber subscriber = new RecordingSubscriber(0, false);
        publisher.subscribe(subscriber);

        assertTrue(publisher.emit(frame(1)));
        assertEquals(List.of(), subscriber.values);
        subscriber.subscription.request(1);
        publisher.complete();

        assertEquals(List.of(1), subscriber.values);
        assertTrue(subscriber.completed);
        assertNull(subscriber.failure);
    }

    @Test
    void terminalFailureCanBePublishedBeforeSubscription() {
        PtyFramePublisher publisher = new PtyFramePublisher();
        IllegalStateException expected = new IllegalStateException("failure");
        publisher.fail(expected);
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE, false);

        publisher.subscribe(subscriber);
        publisher.complete();

        assertEquals(expected, subscriber.failure);
        assertFalse(subscriber.completed);
    }

    @Test
    void rejectsSecondSubscriberWithTerminalError() {
        PtyFramePublisher publisher = new PtyFramePublisher();
        RecordingSubscriber first = new RecordingSubscriber(1, false);
        RecordingSubscriber second = new RecordingSubscriber(1, false);

        publisher.subscribe(first);
        publisher.subscribe(second);
        second.subscription.request(1);
        second.subscription.cancel();

        assertInstanceOf(IllegalStateException.class, second.failure);
    }

    @Test
    void invalidDemandFailsSubscriptionAndStopsProducer() throws Exception {
        PtyFramePublisher publisher = new PtyFramePublisher();
        RecordingSubscriber subscriber = new RecordingSubscriber(0, false);
        publisher.subscribe(subscriber);

        subscriber.subscription.request(0);

        assertInstanceOf(IllegalArgumentException.class, subscriber.failure);
        assertFalse(publisher.emit(frame(1)));
        subscriber.subscription.request(1);
    }

    @Test
    void subscriberFailureCancelsPublisher() throws Exception {
        PtyFramePublisher publisher = new PtyFramePublisher();
        RecordingSubscriber subscriber = new RecordingSubscriber(1, true);
        publisher.subscribe(subscriber);

        assertTrue(publisher.emit(frame(1)));
        assertFalse(publisher.emit(frame(2)));
        publisher.fail(new IllegalStateException("ignored after cancellation"));
    }

    @Test
    void explicitCancellationClearsQueuedFrames() throws Exception {
        PtyFramePublisher publisher = new PtyFramePublisher();
        RecordingSubscriber subscriber = new RecordingSubscriber(0, false);
        publisher.subscribe(subscriber);
        assertTrue(publisher.emit(frame(1)));

        subscriber.subscription.cancel();
        publisher.complete();

        assertFalse(publisher.emit(frame(2)));
        assertEquals(List.of(), subscriber.values);
    }

    @Test
    void fullQueueBlocksProducerUntilDemandArrives() throws Exception {
        PtyFramePublisher publisher = new PtyFramePublisher();
        RecordingSubscriber subscriber = new RecordingSubscriber(0, false);
        publisher.subscribe(subscriber);
        for (int index = 0; index < 32; index++) {
            assertTrue(publisher.emit(frame(index)));
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var blocked = executor.submit(() -> publisher.emit(frame(32)));
            Thread.sleep(50);
            assertFalse(blocked.isDone());
            subscriber.subscription.request(1);
            assertTrue(blocked.get(1, TimeUnit.SECONDS));
        }
        subscriber.subscription.cancel();
    }

    @Test
    void demandSaturatesWithoutOverflow() throws Exception {
        PtyFramePublisher publisher = new PtyFramePublisher();
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE, false);
        publisher.subscribe(subscriber);
        subscriber.subscription.request(10);

        assertTrue(publisher.emit(frame(7)));
        publisher.complete();

        assertEquals(List.of(7), subscriber.values);
        assertTrue(subscriber.completed);
    }

    private static SandboxFrame frame(int value) {
        return new SandboxFrame("terminal", new byte[] {(byte) value}, Instant.EPOCH);
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<SandboxFrame> {
        private final List<Integer> values = new ArrayList<>();
        private final long initialDemand;
        private final boolean failOnNext;
        private Flow.Subscription subscription;
        private Throwable failure;
        private boolean completed;

        private RecordingSubscriber(long initialDemand, boolean failOnNext) {
            this.initialDemand = initialDemand;
            this.failOnNext = failOnNext;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            if (initialDemand > 0) {
                value.request(initialDemand);
            }
        }

        @Override
        public void onNext(SandboxFrame item) {
            if (failOnNext) {
                throw new IllegalStateException("subscriber failure");
            }
            values.add(Byte.toUnsignedInt(item.bytes()[0]));
        }

        @Override
        public void onError(Throwable throwable) {
            failure = throwable;
        }

        @Override
        public void onComplete() {
            completed = true;
        }
    }
}
