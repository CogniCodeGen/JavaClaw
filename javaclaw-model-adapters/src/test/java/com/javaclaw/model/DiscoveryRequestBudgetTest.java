package com.javaclaw.model;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryRequestBudgetTest {
    @Test
    void 所有分页请求共享同一总时限() throws Exception {
        AtomicLong now = new AtomicLong();
        DiscoveryRequestBudget budget = DiscoveryRequestBudget.start(Duration.ofSeconds(30), 20, now::get);

        assertEquals(30_000, budget.acquireTimeoutMillis());
        now.set(TimeUnit.SECONDS.toNanos(29));
        assertEquals(1_000, budget.acquireTimeoutMillis());
        now.set(TimeUnit.SECONDS.toNanos(30));
        IOException failure = assertThrows(IOException.class, budget::acquireTimeoutMillis);
        assertTrue(failure.getMessage().contains("timeout"));
    }

    @Test
    void 第二十一个请求在发出前被拒绝() throws Exception {
        DiscoveryRequestBudget budget = DiscoveryRequestBudget.start(Duration.ofSeconds(30), 20, () -> 0L);

        for (int request = 0; request < 20; request++) {
            assertEquals(30_000, budget.acquireTimeoutMillis());
        }
        IOException failure = assertThrows(IOException.class, budget::acquireTimeoutMillis);
        assertTrue(failure.getMessage().contains("request limit"));
    }

    @Test
    void 关闭发现客户端会释放线程池与连接池() {
        OkHttpClient client =
                DiscoveryHttpClients.google(Duration.ofSeconds(1), new com.javaclaw.api.CancellationSource());
        var executor = client.dispatcher().executorService();

        DiscoveryHttpClients.close(client);

        assertTrue(executor.isShutdown());
        assertEquals(0, client.connectionPool().connectionCount());
    }
}
