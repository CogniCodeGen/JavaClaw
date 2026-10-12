package com.javaclaw.memory.store;

import com.javaclaw.memory.model.Fact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class MemoryStoreConcurrencyTest {

    @Test
    void 并发虚拟线程写入经公平锁串行化且不丢数据(@TempDir Path dir) throws Exception {
        int writes = 24;
        try (MemoryStore store = new MemoryStore(dir, 4, "concurrency-test")) {
            store.open();
            CountDownLatch ready = new CountDownLatch(writes);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Thread> workers = new ArrayList<>();

            for (int i = 0; i < writes; i++) {
                int index = i;
                workers.add(Thread.ofVirtual().start(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        store.addFact(new Fact(
                                "concurrency", "fact-" + index,
                                new float[]{1, 0, 0, 0}), "test");
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    }
                }));
            }

            ready.await();
            start.countDown();
            for (Thread worker : workers) {
                worker.join();
            }
            if (failure.get() != null) {
                fail("并发写入失败", failure.get());
            }

            assertEquals(writes, store.allFacts().size());
        }
    }
}
