package com.javaclaw.desktop.settings;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxStateDispatcherTest {
    @Test
    void 后台完成只通过Fx队列发布页面状态() {
        AtomicReference<String> value = new AtomicReference<>();
        AtomicBoolean renderedOnFx = new AtomicBoolean();
        AtomicInteger updates = new AtomicInteger();
        FxTestSupport.run(() -> {
            Runnable foreground = () -> {
                renderedOnFx.set(Platform.isFxApplicationThread());
                updates.incrementAndGet();
                value.set("foreground");
            };
            FxStateDispatcher.dispatch(foreground);
            assertEquals("foreground", value.get());
            value.set(null);

            CompletableFuture.runAsync(() -> FxStateDispatcher.dispatch(() -> {
                        renderedOnFx.set(Platform.isFxApplicationThread());
                        updates.incrementAndGet();
                        value.set("background");
                    }))
                    .join();
        });

        FxTestSupport.await(() -> "background".equals(value.get()));
        assertTrue(renderedOnFx.get());
        assertEquals(2, updates.get());
    }

    @Test
    void 调度边界拒绝空更新() {
        assertThrows(NullPointerException.class, () -> FxStateDispatcher.dispatch(null));
    }
}
