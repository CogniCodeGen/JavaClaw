package com.javaclaw.desktop.golden;

import javafx.application.Platform;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GoldenAsyncFailuresTest {
    @Test
    void JavaFX未捕获异常使渲染失败并恢复原处理器() {
        Thread.UncaughtExceptionHandler previousDefault = Thread.getDefaultUncaughtExceptionHandler();
        Thread.UncaughtExceptionHandler previousFx =
                FxTestSupport.call(() -> Thread.currentThread().getUncaughtExceptionHandler());
        IllegalStateException expected = new IllegalStateException("异步 FX 渲染失败");
        AssertionError failure = assertThrows(AssertionError.class, () -> {
            try (GoldenAsyncFailures failures = new GoldenAsyncFailures()) {
                // 不使用 FxTestSupport.run 的异常包装，验证 JavaFX 自己上报的未捕获异常。
                Platform.runLater(() -> {
                    throw expected;
                });
            }
        });
        assertSame(expected, failure.getCause());
        assertSame(previousDefault, Thread.getDefaultUncaughtExceptionHandler());
        assertSame(previousFx, FxTestSupport.call(() -> Thread.currentThread().getUncaughtExceptionHandler()));
    }

    @Test
    void 后台虚拟线程未捕获异常使渲染失败() {
        IllegalStateException expected = new IllegalStateException("后台 SDK 完成时渲染失败");
        AssertionError failure = assertThrows(AssertionError.class, () -> {
            try (GoldenAsyncFailures failures = new GoldenAsyncFailures()) {
                Thread worker = Thread.ofVirtual().start(() -> {
                    throw expected;
                });
                worker.join();
            }
        });
        assertSame(expected, failure.getCause());
    }

    @Test
    void 无异步异常的渲染正常结束() {
        try (GoldenAsyncFailures failures = new GoldenAsyncFailures()) {
            FxTestSupport.run(() -> {});
        }
    }
}
