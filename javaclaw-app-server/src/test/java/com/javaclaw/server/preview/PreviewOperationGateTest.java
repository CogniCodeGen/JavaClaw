package com.javaclaw.server.preview;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewOperationGateTest {
    @Test
    void 停止准入立即拒绝新操作且关闭等待已有来源读取退出() throws Exception {
        var gate = new PreviewOperationGate();
        var waiting = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        Thread closing;
        try (var operation = gate.enter()) {
            closing = Thread.ofVirtual().start(() -> {
                gate.stopAccepting();
                waiting.countDown();
                gate.awaitIdle();
                returned.countDown();
            });
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, gate::enter);
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS));
            assertFalse(Thread.currentThread().isInterrupted());
        }
        assertTrue(returned.await(2, TimeUnit.SECONDS));
        closing.join();
        assertThrows(IllegalStateException.class, gate::enter);
    }

    @Test
    void 相对资源嵌套读取退出后仍可正常关闭() {
        var gate = new PreviewOperationGate();
        try (var parent = gate.enter();
                var chunk = gate.enter()) {
            assertFalse(Thread.currentThread().isInterrupted());
        }
        gate.stopAccepting();
        gate.awaitIdle();
    }
}
