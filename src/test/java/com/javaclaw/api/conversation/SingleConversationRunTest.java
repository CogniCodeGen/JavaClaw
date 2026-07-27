package com.javaclaw.api.conversation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SingleConversationRunTest {

    @Test
    void 拒绝重叠启动且旧句柄不能取消下一代运行() {
        SingleConversationRun gate = new SingleConversationRun();
        AtomicReference<ConversationCallbacks> firstCallbacks = new AtomicReference<>();
        AtomicReference<ConversationCallbacks> secondCallbacks = new AtomicReference<>();
        List<ConversationOutcome> rejected = new ArrayList<>();
        AtomicInteger cancellations = new AtomicInteger();

        ConversationHandle first = gate.start(callbacks(), firstCallbacks::set,
                reason -> {
                    cancellations.incrementAndGet();
                    return true;
                });
        ConversationHandle overlap = gate.start(recording(rejected), ignored -> fail(),
                reason -> true);

        assertTrue(overlap.isTerminal());
        assertInstanceOf(ConversationOutcome.Failed.class, rejected.getFirst());
        firstCallbacks.get().onTerminal(ConversationOutcome.completed());

        ConversationHandle second = gate.start(callbacks(), secondCallbacks::set,
                reason -> {
                    cancellations.incrementAndGet();
                    return true;
                });
        assertFalse(first.cancel(CancellationReason.USER_REQUEST));
        assertEquals(0, cancellations.get());
        assertTrue(second.cancel(CancellationReason.USER_REQUEST));
        assertEquals(1, cancellations.get());
        assertNotNull(secondCallbacks.get());
    }

    @Test
    void 终态回调前释放门禁以便立即开始下一轮() {
        SingleConversationRun gate = new SingleConversationRun();
        AtomicReference<ConversationCallbacks> source = new AtomicReference<>();
        AtomicReference<ConversationHandle> next = new AtomicReference<>();

        gate.start(new ConversationCallbacks() {
            @Override public void onEvent(ConversationEvent event) {}
            @Override public void onTerminal(ConversationOutcome outcome) {
                next.set(gate.start(callbacks(), ignored -> {}, ignored -> true));
            }
        }, source::set, ignored -> true);

        source.get().onTerminal(ConversationOutcome.completed());

        assertNotNull(next.get());
        assertFalse(next.get().isTerminal());
    }

    @Test
    void 取消与完成竞争始终只有一个终态() throws Exception {
        for (int i = 0; i < 200; i++) {
            SingleConversationRun gate = new SingleConversationRun();
            AtomicReference<ConversationCallbacks> source = new AtomicReference<>();
            List<ConversationOutcome> outcomes =
                    java.util.Collections.synchronizedList(new ArrayList<>());
            CountDownLatch ready = new CountDownLatch(1);
            CountDownLatch releaseCancel = new CountDownLatch(1);
            ConversationHandle handle = gate.start(recording(outcomes), source::set, ignored -> {
                ready.countDown();
                await(releaseCancel);
                return true;
            });

            Thread cancelling = Thread.ofVirtual().start(
                    () -> handle.cancel(CancellationReason.USER_REQUEST));
            assertTrue(ready.await(1, TimeUnit.SECONDS));
            Thread completing = Thread.ofVirtual().start(
                    () -> source.get().onTerminal(ConversationOutcome.completed()));
            releaseCancel.countDown();
            cancelling.join();
            completing.join();

            assertEquals(1, outcomes.size());
            assertInstanceOf(ConversationOutcome.Cancelled.class, outcomes.getFirst());
        }
    }

    private static ConversationCallbacks callbacks() {
        return new ConversationCallbacks() {
            @Override public void onEvent(ConversationEvent event) {}
            @Override public void onTerminal(ConversationOutcome outcome) {}
        };
    }

    private static ConversationCallbacks recording(List<ConversationOutcome> outcomes) {
        return new ConversationCallbacks() {
            @Override public void onEvent(ConversationEvent event) {}
            @Override public void onTerminal(ConversationOutcome outcome) {
                outcomes.add(outcome);
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
