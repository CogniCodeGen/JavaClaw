package com.javaclaw.runtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.api.TurnId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeBudgetAndConcurrencyTest {
    @Test
    void budgetConsumesUsageToolCallsAndChildReservationsAtomically() {
        BudgetAccount account = new BudgetAccount(RuntimeFixtures.budget(), RuntimeFixtures.CLOCK);

        account.consume(new ModelUsage(10, 5, 0, 0));
        account.consumeToolCall();
        account.reserveChild(new ReservedChildBudget(20, 10));

        assertEquals(35, account.remainingOutputTokens());
        assertEquals(1, account.toolCalls());
        assertThrows(BudgetExceededException.class, () -> account.consume(new ModelUsage(71, 0, 0, 0)));
        assertThrows(BudgetExceededException.class, () -> account.consume(new ModelUsage(0, 36, 0, 0)));
    }

    @Test
    void budgetRejectsToolChildTokenWallTimeAndCancellationOverruns() {
        BudgetAccount tools =
                new BudgetAccount(new TurnBudget(10, 10, 1, 1, Duration.ofSeconds(1)), RuntimeFixtures.CLOCK);
        tools.consumeToolCall();
        assertThrows(BudgetExceededException.class, tools::consumeToolCall);

        BudgetAccount children = new BudgetAccount(RuntimeFixtures.budget(), RuntimeFixtures.CLOCK);
        children.reserveChild(new ReservedChildBudget(1, 1));
        children.reserveChild(new ReservedChildBudget(1, 1));
        assertThrows(BudgetExceededException.class, () -> children.reserveChild(new ReservedChildBudget(1, 1)));
        assertThrows(
                BudgetExceededException.class,
                () -> new BudgetAccount(new TurnBudget(1, 1, 1, 1, Duration.ofSeconds(1)), RuntimeFixtures.CLOCK)
                        .reserveChild(new ReservedChildBudget(2, 1)));

        MutableClock clock = new MutableClock(RuntimeFixtures.NOW);
        BudgetAccount timed = new BudgetAccount(new TurnBudget(1, 1, 1, 1, Duration.ofSeconds(1)), clock);
        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel("stop");

        timed.checkpoint(new CancellationSource());
        assertThrows(TurnCancelledException.class, () -> timed.checkpoint(cancelled));
        clock.advance(Duration.ofSeconds(1));
        assertThrows(BudgetExceededException.class, () -> timed.checkpoint(new CancellationSource()));
    }

    @Test
    void childLimiterCapsGlobalConcurrencyAndLeaseIsIdempotent() {
        ChildThreadLimiter limiter = new ChildThreadLimiter();
        List<ChildThreadLimiter.Lease> leases = new ArrayList<>();
        for (int index = 0; index < ChildThreadLimiter.MAX_ACTIVE_CHILDREN; index++) {
            leases.add(limiter.acquire());
        }

        assertEquals(ChildThreadLimiter.MAX_ACTIVE_CHILDREN, limiter.activeCount());
        assertThrows(BudgetExceededException.class, limiter::acquire);
        leases.getFirst().close();
        leases.getFirst().close();
        assertEquals(ChildThreadLimiter.MAX_ACTIVE_CHILDREN - 1, limiter.activeCount());
        limiter.acquire().close();
        leases.stream().skip(1).forEach(ChildThreadLimiter.Lease::close);
        assertEquals(0, limiter.activeCount());
    }

    @Test
    void eventSinkHonorsDemandSaturationAndValidation() throws Exception {
        List<ModelStreamEvent> events = new ArrayList<>();
        try (DemandControlledEventSink sink = new DemandControlledEventSink((turnId, event) -> events.add(event))) {
            sink.request(Long.MAX_VALUE);
            sink.request(1);
            sink.publish(TurnId.random(), new ModelStreamEvent.TextDelta("one"), new CancellationSource());
            sink.publish(TurnId.random(), new ModelStreamEvent.TextDelta("two"), new CancellationSource());
            assertThrows(IllegalArgumentException.class, () -> sink.request(0));
        }

        assertEquals(2, events.size());
        assertThrows(NullPointerException.class, () -> new DemandControlledEventSink(null));
    }

    @Test
    void eventSinkWakesBlockedPublisherForDemandCancellationAndClose() throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch started = new CountDownLatch(1);
            DemandControlledEventSink demanded = new DemandControlledEventSink((turnId, event) -> {});
            Future<?> published =
                    executor.submit(() -> publishAfterSignal(demanded, started, new CancellationSource()));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            demanded.request(1);
            published.get(1, TimeUnit.SECONDS);

            CancellationSource cancellation = new CancellationSource();
            DemandControlledEventSink cancelled = new DemandControlledEventSink((turnId, event) -> {});
            Future<?> cancelledPublish = executor.submit(() -> publishAfterSignal(cancelled, null, cancellation));
            cancellation.cancel("stop");
            assertThrows(Exception.class, () -> cancelledPublish.get(1, TimeUnit.SECONDS));

            DemandControlledEventSink closed = new DemandControlledEventSink((turnId, event) -> {});
            Future<?> closedPublish = executor.submit(() -> publishAfterSignal(closed, null, new CancellationSource()));
            closed.close();
            assertThrows(Exception.class, () -> closedPublish.get(1, TimeUnit.SECONDS));
            demanded.close();
            cancelled.close();
        }
    }

    @Test
    void visibleCatalogAllowsOnlyExactFrozenAndDiscoveredTools() {
        ToolDescriptor alpha = RuntimeFixtures.tool("core", "alpha", 1);
        ToolDescriptor beta = RuntimeFixtures.tool("core", "beta", 1);
        ToolCatalogSnapshot snapshot = new ToolCatalogSnapshot(
                TurnId.random(), 1, List.of(beta, alpha), RuntimeFixtures.permissions(), Instant.now());
        VisibleToolCatalog catalog = new VisibleToolCatalog(snapshot, List.of(beta));

        assertEquals(beta, catalog.requireVisible(beta.identity()));
        catalog.reveal(List.of(alpha));
        assertEquals(List.of(alpha, beta), catalog.list());
        assertFailure("TOOL_NOT_DISCOVERED", () -> catalog.requireVisible(new ToolIdentity("core", "gamma", 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.reveal(List.of(RuntimeFixtures.tool("core", "missing", 1))));
    }

    @Test
    void visibleCatalogRejectsChangedSchemaAndKeepsDuplicateRevealIdempotent() {
        ToolDescriptor original = RuntimeFixtures.tool("core", "read", 1);
        ToolCatalogSnapshot snapshot = new ToolCatalogSnapshot(
                TurnId.random(), 1, List.of(original), RuntimeFixtures.permissions(), Instant.now());
        VisibleToolCatalog catalog = new VisibleToolCatalog(snapshot, List.of(original));

        ToolDescriptor changed = new ToolDescriptor(
                original.identity(),
                "不同说明",
                original.inputSchema(),
                original.outputSchema(),
                original.risk(),
                original.tags());
        assertFailure("TOOL_SCHEMA_CHANGED", () -> catalog.reveal(List.of(changed)));
        catalog.reveal(List.of(original));
        assertEquals(List.of(original), catalog.list());
    }

    private static void publishAfterSignal(
            DemandControlledEventSink sink, CountDownLatch started, CancellationSource cancellation) {
        if (started != null) {
            started.countDown();
        }
        try {
            sink.publish(TurnId.random(), new ModelStreamEvent.TextDelta("event"), cancellation);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void assertFailure(String expectedCode, Runnable action) {
        TurnFailureException failure = assertThrows(TurnFailureException.class, action::run);
        assertEquals(expectedCode, failure.code());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
