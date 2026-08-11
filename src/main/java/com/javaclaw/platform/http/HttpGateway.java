package com.javaclaw.platform.http;

import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskHandle;
import com.javaclaw.platform.execution.TaskSpec;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * HTTP 访问的统一边界。
 *
 * <p>请求运行在托管 I/O 虚拟线程上。只有 GET、HEAD、PUT、DELETE、OPTIONS、TRACE 可启用
 * 自动重试；POST、PATCH 等非幂等方法配置多次尝试会在发送前被拒绝。每次重试都从供应器创建
 * 新请求，避免复用一次性请求正文。取消会中断退避或当前同步发送。</p>
 */
public final class HttpGateway {

    private static final Set<String> IDEMPOTENT_METHODS =
            Set.of("GET", "HEAD", "PUT", "DELETE", "OPTIONS", "TRACE");

    private final ManagedTaskExecutor executor;
    private final Transport transport;

    public HttpGateway(ManagedTaskExecutor executor, HttpClient client) {
        this(executor, request -> {
            HttpResponse<byte[]> response = client.send(
                    request, HttpResponse.BodyHandlers.ofByteArray());
            return new HttpResult(response.uri(), response.statusCode(),
                    response.headers(), response.body());
        });
    }

    HttpGateway(ManagedTaskExecutor executor, Transport transport) {
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
    }

    public TaskHandle<HttpResult> send(
            String name, Supplier<HttpRequest> requestFactory, HttpRetryPolicy policy) {
        java.util.Objects.requireNonNull(requestFactory, "requestFactory");
        HttpRetryPolicy checkedPolicy = policy == null ? HttpRetryPolicy.none() : policy;
        HttpRequest first = java.util.Objects.requireNonNull(
                requestFactory.get(), "requestFactory returned null");
        ensureRetrySafety(first.method(), checkedPolicy);
        Duration timeout = first.timeout().orElse(Duration.ofSeconds(60));
        Duration totalTimeout = timeout.multipliedBy(checkedPolicy.maxAttempts())
                .plus(checkedPolicy.maxBackoff().multipliedBy(
                        Math.max(0, checkedPolicy.maxAttempts() - 1L)))
                .plusSeconds(1);
        return executor.submit(TaskSpec.io(name).withTimeout(totalTimeout), context -> {
            IOException lastIoFailure = null;
            for (int attempt = 1; attempt <= checkedPolicy.maxAttempts(); attempt++) {
                context.cancellation().throwIfCancellationRequested();
                HttpRequest request = attempt == 1 ? first
                        : java.util.Objects.requireNonNull(requestFactory.get(),
                        "requestFactory returned null");
                if (!request.method().equalsIgnoreCase(first.method())) {
                    throw new IllegalArgumentException("重试请求的 HTTP method 不一致");
                }
                try {
                    HttpResult result = transport.send(request);
                    if (attempt == checkedPolicy.maxAttempts()
                            || !checkedPolicy.retryableStatuses().contains(result.statusCode())) {
                        return result;
                    }
                } catch (IOException failure) {
                    lastIoFailure = failure;
                    if (attempt == checkedPolicy.maxAttempts()) {
                        throw failure;
                    }
                }
                sleep(checkedPolicy.backoffAfterAttempt(attempt), context);
            }
            throw lastIoFailure == null
                    ? new IOException("HTTP 请求未产生响应") : lastIoFailure;
        });
    }

    public TaskHandle<HttpResult> send(String name, Supplier<HttpRequest> requestFactory) {
        return send(name, requestFactory, HttpRetryPolicy.none());
    }

    public HttpResult sendAndWait(
            String name, Supplier<HttpRequest> requestFactory, HttpRetryPolicy policy)
            throws IOException, InterruptedException {
        TaskHandle<HttpResult> handle = send(name, requestFactory, policy);
        try {
            return handle.completion().get();
        } catch (InterruptedException interrupted) {
            handle.cancel();
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (CancellationException cancelled) {
            throw new IOException("HTTP 请求已取消: " + name, cancelled);
        } catch (ExecutionException failed) {
            Throwable cause = unwrap(failed);
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("HTTP 请求失败: " + name, cause);
        }
    }

    private static void ensureRetrySafety(String method, HttpRetryPolicy policy) {
        if (policy.maxAttempts() > 1
                && !IDEMPOTENT_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("非幂等 HTTP 方法禁止自动重试: " + method);
        }
    }

    private static void sleep(
            Duration duration, com.javaclaw.platform.execution.TaskContext context)
            throws InterruptedException {
        if (duration.isZero()) {
            return;
        }
        Thread.sleep(duration);
        context.cancellation().throwIfCancellationRequested();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException
                || current instanceof java.util.concurrent.CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @FunctionalInterface
    interface Transport {
        HttpResult send(HttpRequest request) throws IOException, InterruptedException;
    }
}
