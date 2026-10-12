package com.javaclaw.api.conversation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TerminalCallbackGuardTest {

    @Test
    void 终态竞争只转发一次且丢弃迟到事件() throws Exception {
        List<ConversationOutcome> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        List<ConversationEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        TerminalCallbackGuard guard = new TerminalCallbackGuard(new ConversationCallbacks() {
            @Override public void onEvent(ConversationEvent event) { events.add(event); }
            @Override public void onTerminal(ConversationOutcome outcome) { outcomes.add(outcome); }
        });

        guard.onEvent(new ConversationEvent.Reply("before"));
        CountDownLatch ready = new CountDownLatch(1);
        Thread completed = Thread.ofVirtual().start(() -> {
            await(ready);
            guard.onTerminal(ConversationOutcome.completed());
        });
        Thread failed = Thread.ofVirtual().start(() -> {
            await(ready);
            guard.onTerminal(ConversationOutcome.failed(new IllegalStateException("boom")));
        });
        Thread cancelled = Thread.ofVirtual().start(() -> {
            await(ready);
            guard.onTerminal(ConversationOutcome.cancelled(CancellationReason.USER_REQUEST));
        });
        ready.countDown();
        completed.join();
        failed.join();
        cancelled.join();
        guard.onEvent(new ConversationEvent.Reply("late"));

        assertTrue(guard.isTerminal());
        assertEquals(1, outcomes.size());
        assertEquals(1, events.size());
    }

    @Test
    void 句柄取消产生带原因的唯一终态() {
        List<ConversationOutcome> outcomes = new ArrayList<>();
        TerminalCallbackGuard guard = new TerminalCallbackGuard(new ConversationCallbacks() {
            @Override public void onEvent(ConversationEvent event) {}
            @Override public void onTerminal(ConversationOutcome outcome) { outcomes.add(outcome); }
        });
        DefaultConversationHandle handle = new DefaultConversationHandle(guard, ignored -> true);

        assertTrue(handle.cancel(CancellationReason.MODE_SWITCH));
        assertFalse(handle.cancel(CancellationReason.USER_REQUEST));
        assertInstanceOf(ConversationOutcome.Cancelled.class, outcomes.getFirst());
        ConversationOutcome.Cancelled cancelled =
                (ConversationOutcome.Cancelled) outcomes.getFirst();
        assertEquals(CancellationReason.MODE_SWITCH, cancelled.reason());
        assertFalse(cancelled.userInitiated());
    }

    @Test
    void 终态等待已开始事件结束且之后不再投递事件() throws Exception {
        CountDownLatch eventEntered = new CountDownLatch(1);
        CountDownLatch releaseEvent = new CountDownLatch(1);
        List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        TerminalCallbackGuard guard = new TerminalCallbackGuard(new ConversationCallbacks() {
            @Override
            public void onEvent(ConversationEvent event) {
                order.add("event-start");
                eventEntered.countDown();
                await(releaseEvent);
                order.add("event-end");
            }

            @Override
            public void onTerminal(ConversationOutcome outcome) {
                order.add("terminal");
            }
        });

        Thread eventThread = Thread.ofVirtual().start(
                () -> guard.onEvent(new ConversationEvent.Reply("in-flight")));
        assertTrue(eventEntered.await(1, TimeUnit.SECONDS));
        Thread terminalThread = Thread.ofVirtual().start(
                () -> guard.onTerminal(ConversationOutcome.completed()));

        Thread.sleep(20);
        assertEquals(List.of("event-start"), order);
        releaseEvent.countDown();
        eventThread.join();
        terminalThread.join();
        guard.onEvent(new ConversationEvent.Reply("late"));

        assertEquals(List.of("event-start", "event-end", "terminal"), order);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
