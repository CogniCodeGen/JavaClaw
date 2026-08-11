package com.javaclaw.platform.fx;

import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiAsyncActionTest {

    private final FxDispatcher immediateFx = new FxDispatcher(() -> true, Runnable::run);

    @Test
    void mapsSuccessAndFailureToObservableState() throws Exception {
        try (ManagedTaskExecutor executor = new ManagedTaskExecutor();
             UiAsyncAction<String> action = new UiAsyncAction<>(executor, immediateFx)) {
            CountDownLatch success = new CountDownLatch(1);
            AtomicReference<String> value = new AtomicReference<>();

            action.execute(TaskSpec.io("load"), context -> "ready", result -> {
                value.set(result);
                success.countDown();
            }, ignored -> {
            });

            assertTrue(success.await(2, TimeUnit.SECONDS));
            assertEquals("ready", value.get());
            assertFalse(action.busyProperty().get());
            assertNull(action.failureProperty().get());

            CountDownLatch failed = new CountDownLatch(1);
            action.execute(TaskSpec.io("fail"), context -> {
                throw new IllegalStateException("broken");
            }, ignored -> {
            }, failure -> failed.countDown());
            assertTrue(failed.await(2, TimeUnit.SECONDS));
            assertEquals("broken", action.failureProperty().get().getMessage());
        }
    }

    @Test
    void ignoresLateResultFromReplacedExecution() throws Exception {
        try (ManagedTaskExecutor executor = new ManagedTaskExecutor();
             UiAsyncAction<String> action = new UiAsyncAction<>(executor, immediateFx)) {
            AtomicBoolean release = new AtomicBoolean(false);
            AtomicReference<String> visible = new AtomicReference<>();
            CountDownLatch secondFinished = new CountDownLatch(1);

            action.execute(TaskSpec.io("old"), context -> {
                while (!release.get()) {
                    if (Thread.interrupted()) {
                        // 模拟不合作的第三方调用：仍会迟到返回，UI 必须丢弃。
                    }
                    Thread.onSpinWait();
                }
                return "old";
            }, visible::set, ignored -> {
            });
            action.execute(TaskSpec.io("new"), context -> "new", value -> {
                visible.set(value);
                secondFinished.countDown();
            }, ignored -> {
            });

            assertTrue(secondFinished.await(2, TimeUnit.SECONDS));
            release.set(true);
            Thread.sleep(30);
            assertEquals("new", visible.get());
            assertFalse(action.busyProperty().get());
        }
    }
}
