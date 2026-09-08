package com.javaclaw.desktop;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.javaclaw.client.sdk.JavaClawClient;

/** SDK 后台分派；所有完成均切回 UI 调度器，保持既有设置页 Future 契约。 */
final class DesktopRequests {
    private DesktopRequests() {}

    static void closeQuietly(JavaClawClient value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (Exception ignored) {
            // 重连失败清理保留原始错误，旧会话关闭错误不能覆盖新连接结果。
        }
    }

    static <T> CompletableFuture<T> submit(
            ExecutorService workers,
            Consumer<Runnable> ui,
            Supplier<JavaClawClient> client,
            Function<JavaClawClient, T> request) {
        Function<JavaClawClient, T> checked = Objects.requireNonNull(request, "request");
        CompletableFuture<T> result = new CompletableFuture<>();
        workers.submit(() -> {
            try {
                T value = checked.apply(client.get());
                ui.accept(() -> result.complete(value));
            } catch (Exception failure) {
                ui.accept(() -> result.completeExceptionally(failure));
            }
        });
        return result;
    }
}
