package com.javaclaw.memory.embed;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingGatewayTest {

    @Test
    void 启动探测成功后自动结束检查状态且只执行一次() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch settled = new CountDownLatch(1);
        EmbeddingGateway gateway = new EmbeddingGateway(
                2, EmbeddingHealthStatus.CHECKING, text -> {
                    calls.incrementAndGet();
                    return new double[]{0.1, 0.2};
                });
        gateway.addHealthListener(snapshot -> {
            if (snapshot.status() != EmbeddingHealthStatus.CHECKING) settled.countDown();
        });

        gateway.startInitialProbe();

        assertTrue(settled.await(2, TimeUnit.SECONDS));
        assertEquals(EmbeddingHealthStatus.HEALTHY, gateway.healthSnapshot().status());
        gateway.startInitialProbe();
        assertEquals(1, calls.get());
    }

    @Test
    void 启动探测失败后自动结束检查状态() throws Exception {
        CountDownLatch settled = new CountDownLatch(1);
        EmbeddingGateway gateway = new EmbeddingGateway(
                2, EmbeddingHealthStatus.CHECKING,
                text -> { throw new IllegalStateException("offline"); });
        gateway.addHealthListener(snapshot -> {
            if (snapshot.status() != EmbeddingHealthStatus.CHECKING) settled.countDown();
        });

        gateway.startInitialProbe();

        assertTrue(settled.await(2, TimeUnit.SECONDS));
        assertEquals(EmbeddingHealthStatus.UNAVAILABLE, gateway.healthSnapshot().status());
        assertTrue(gateway.healthSnapshot().lastError().contains("offline"));
    }

    @Test
    void 交互调用一秒超时且熔断内十轮立即降级() {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingGateway gateway = new EmbeddingGateway(
                2, EmbeddingHealthStatus.CHECKING, text -> {
                    calls.incrementAndGet();
                    Thread.sleep(5_000);
                    return new double[]{1, 2};
                });

        long firstStarted = System.nanoTime();
        assertNull(gateway.embed("query", EmbeddingPurpose.INTERACTIVE_RECALL));
        long firstMs = (System.nanoTime() - firstStarted) / 1_000_000;

        long circuitStarted = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            assertNull(gateway.embed("query-" + i, EmbeddingPurpose.INTERACTIVE_RECALL));
        }
        long circuitMs = (System.nanoTime() - circuitStarted) / 1_000_000;

        assertTrue(firstMs >= 850 && firstMs < 1_500,
                "首次交互调用应受 1 秒超时约束，实际 " + firstMs + "ms");
        assertTrue(circuitMs < 100, "熔断内十轮应低于 100ms，实际 " + circuitMs + "ms");
        assertEquals(1, calls.get());
        assertEquals(EmbeddingHealthStatus.DEGRADED, gateway.healthSnapshot().status());
        assertFalse(gateway.healthSnapshot().usable());
        assertNotNull(gateway.healthSnapshot().circuitOpenUntil());
    }

    @Test
    void 主动探测绕过熔断并恢复健康() {
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        EmbeddingGateway gateway = new EmbeddingGateway(
                2, EmbeddingHealthStatus.CHECKING, text -> {
                    calls.incrementAndGet();
                    if (fail.get()) throw new IllegalStateException("offline");
                    return new double[]{0.25, 0.5};
                });

        assertNull(gateway.embed("first", EmbeddingPurpose.INTERACTIVE_RECALL));
        assertNotNull(gateway.healthSnapshot().circuitOpenUntil());
        fail.set(false);

        EmbeddingHealthSnapshot recovered = gateway.probe();

        assertEquals(2, calls.get());
        assertEquals(EmbeddingHealthStatus.HEALTHY, recovered.status());
        assertEquals(0, recovered.consecutiveFailures());
        assertNull(recovered.circuitOpenUntil());
    }

    @Test
    void 后台索引失败最多重试一次() {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingGateway gateway = new EmbeddingGateway(
                2, EmbeddingHealthStatus.CHECKING, text -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("offline");
                });

        assertNull(gateway.embed("document", EmbeddingPurpose.BACKGROUND_INDEX));
        assertEquals(2, calls.get());
        assertEquals(EmbeddingHealthStatus.DEGRADED, gateway.healthSnapshot().status());
    }

    @Test
    void 后台索引被生命周期中断时立即退出且不污染健康状态() throws Exception {
        CountDownLatch invoked = new CountDownLatch(1);
        AtomicBoolean interruptedAtReturn = new AtomicBoolean();
        EmbeddingGateway gateway = new EmbeddingGateway(
                2, EmbeddingHealthStatus.CHECKING, text -> {
                    invoked.countDown();
                    Thread.sleep(30_000);
                    return new double[]{0.1, 0.2};
                });

        Thread worker = Thread.ofVirtual().start(() -> {
            assertNull(gateway.embed("cancel-me", EmbeddingPurpose.BACKGROUND_INDEX));
            interruptedAtReturn.set(Thread.currentThread().isInterrupted());
        });
        assertTrue(invoked.await(1, TimeUnit.SECONDS));

        worker.interrupt();
        worker.join(2_000);

        assertFalse(worker.isAlive(), "生命周期中断后不应继续第二次后台重试");
        assertTrue(interruptedAtReturn.get(), "返回调用方前应恢复中断位");
        assertEquals(EmbeddingHealthStatus.CHECKING, gateway.healthSnapshot().status());
        assertEquals(0, gateway.healthSnapshot().consecutiveFailures());
        assertNull(gateway.healthSnapshot().circuitOpenUntil());
    }

    @Test
    void 向量实际维度与配置不一致时探测失败() {
        EmbeddingGateway gateway = new EmbeddingGateway(
                2, EmbeddingHealthStatus.CHECKING,
                text -> new double[]{0.1, 0.2, 0.3});

        EmbeddingHealthSnapshot snapshot = gateway.probe();

        assertEquals(EmbeddingHealthStatus.UNAVAILABLE, snapshot.status());
        assertTrue(snapshot.lastError().contains("实际 3，配置 2"));
    }

    @Test
    void 显式探测失败后普通失败不会降回可用性较高的状态() {
        MutableClock clock = new MutableClock();
        EmbeddingGateway gateway = new EmbeddingGateway(
                2, EmbeddingHealthStatus.CHECKING,
                text -> { throw new IllegalStateException("offline"); }, clock);

        assertEquals(EmbeddingHealthStatus.UNAVAILABLE, gateway.probe().status());
        clock.advance(Duration.ofSeconds(31));
        assertNull(gateway.embed("still-offline", EmbeddingPurpose.INTERACTIVE_RECALL));
        assertEquals(EmbeddingHealthStatus.UNAVAILABLE, gateway.healthSnapshot().status());
    }

    @Test
    void 前两次运行失败降级第三次转为不可用且成功后清零() {
        AtomicBoolean fail = new AtomicBoolean(true);
        MutableClock clock = new MutableClock();
        EmbeddingGateway gateway = new EmbeddingGateway(
                2, EmbeddingHealthStatus.CHECKING, text -> {
                    if (fail.get()) throw new IllegalStateException("offline");
                    return new double[]{0.1, 0.2};
                }, clock);

        assertNull(gateway.embed("one", EmbeddingPurpose.INTERACTIVE_RECALL));
        assertEquals(EmbeddingHealthStatus.DEGRADED, gateway.healthSnapshot().status());
        clock.advance(Duration.ofSeconds(31));
        assertNull(gateway.embed("two", EmbeddingPurpose.INTERACTIVE_RECALL));
        assertEquals(EmbeddingHealthStatus.DEGRADED, gateway.healthSnapshot().status());
        clock.advance(Duration.ofSeconds(61));
        assertNull(gateway.embed("three", EmbeddingPurpose.INTERACTIVE_RECALL));
        assertEquals(EmbeddingHealthStatus.UNAVAILABLE, gateway.healthSnapshot().status());
        assertEquals(3, gateway.healthSnapshot().consecutiveFailures());

        clock.advance(Duration.ofSeconds(121));
        fail.set(false);
        assertArrayEquals(new float[]{0.1f, 0.2f},
                gateway.embed("recovered", EmbeddingPurpose.INTERACTIVE_RECALL));
        assertEquals(EmbeddingHealthStatus.HEALTHY, gateway.healthSnapshot().status());
        assertEquals(0, gateway.healthSnapshot().consecutiveFailures());
        assertTrue(gateway.healthSnapshot().usable());
    }

    @Test
    void 关闭健康监听后不再接收状态变化() throws Exception {
        AtomicInteger notifications = new AtomicInteger();
        EmbeddingGateway gateway = new EmbeddingGateway(
                2, EmbeddingHealthStatus.CHECKING,
                text -> new double[]{0.1, 0.2});
        AutoCloseable subscription = gateway.addHealthListener(
                ignored -> notifications.incrementAndGet());
        assertEquals(1, notifications.get(), "订阅时应立即收到当前快照");

        subscription.close();
        gateway.probe();

        assertEquals(1, notifications.get());
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
