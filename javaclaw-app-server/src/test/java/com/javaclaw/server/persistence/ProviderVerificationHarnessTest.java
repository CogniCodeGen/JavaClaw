package com.javaclaw.server.persistence;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderVerificationState;
import com.javaclaw.api.TurnId;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelUsage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderVerificationHarnessTest {
    private static final ProviderRef PROVIDER = new ProviderRef("provider-main", 2, "test-model");

    @Test
    void 最小Harness不公开工具且只返回脱敏Usage() {
        CompletingGateway gateway = new CompletingGateway();
        try (ProviderVerificationHarness harness = new ProviderVerificationHarness(gateway, Clock.systemUTC())) {
            var result = harness.execute(endpoint(Duration.ofSeconds(1)), PROVIDER, new CancellationSource());

            assertEquals(ProviderVerificationState.SUCCEEDED, result.state());
            assertEquals(1, gateway.calls.get());
            assertTrue(gateway.invocation.get().tools().isEmpty());
            assertEquals(512, gateway.invocation.get().maximumOutputTokens());
            assertEquals(2, result.usage().orElseThrow().inputTokens());
            assertFalse(result.toString().contains("SENSITIVE MODEL RESPONSE"));
        }
    }

    @Test
    void 超时发布取消并中断拥有的Adapter线程() throws Exception {
        BlockingGateway gateway = new BlockingGateway();
        ProviderVerificationHarness harness = new ProviderVerificationHarness(
                gateway, Clock.systemUTC(), Duration.ofMillis(40), Executors.newVirtualThreadPerTaskExecutor());
        try (harness) {
            var result = harness.execute(endpoint(Duration.ofSeconds(5)), PROVIDER, new CancellationSource());

            assertEquals(ProviderVerificationState.TIMED_OUT, result.state());
            assertEquals(1, gateway.calls.get());
            assertTrue(gateway.released.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void 外部取消停止在途调用且预取消不会进入Adapter() throws Exception {
        BlockingGateway gateway = new BlockingGateway();
        try (ProviderVerificationHarness harness = new ProviderVerificationHarness(
                gateway, Clock.systemUTC(), Duration.ofSeconds(2), Executors.newVirtualThreadPerTaskExecutor())) {
            CancellationSource cancellation = new CancellationSource();
            CompletableFuture<com.javaclaw.api.ProviderVerificationResult> running = CompletableFuture.supplyAsync(
                    () -> harness.execute(endpoint(Duration.ofSeconds(2)), PROVIDER, cancellation));
            assertTrue(gateway.entered.await(1, TimeUnit.SECONDS));
            cancellation.cancel("test cancellation");

            assertEquals(
                    ProviderVerificationState.CANCELLED,
                    running.get(1, TimeUnit.SECONDS).state());
            assertTrue(gateway.released.await(1, TimeUnit.SECONDS));
        }

        CompletingGateway neverCalled = new CompletingGateway();
        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel("cancel before start");
        try (ProviderVerificationHarness harness = new ProviderVerificationHarness(neverCalled, Clock.systemUTC())) {
            assertEquals(
                    ProviderVerificationState.CANCELLED,
                    harness.execute(endpoint(Duration.ofSeconds(1)), PROVIDER, cancelled)
                            .state());
            assertEquals(0, neverCalled.calls.get());
        }
    }

    @Test
    void Harness把模型拒绝和执行线程Error映射为脱敏失败() {
        try (ProviderVerificationHarness harness =
                new ProviderVerificationHarness(new ContentFilteredGateway(), Clock.systemUTC())) {
            var filtered = harness.execute(endpoint(Duration.ofSeconds(1)), PROVIDER, new CancellationSource());

            assertEquals(ProviderVerificationState.FAILED, filtered.state());
            assertEquals(Optional.of("MODEL_CONTENT_FILTERED"), filtered.errorCode());
            assertTrue(filtered.usage().isPresent());
        }

        try (ProviderVerificationHarness harness =
                new ProviderVerificationHarness(new ErrorGateway(), Clock.systemUTC())) {
            var failed = harness.execute(endpoint(Duration.ofSeconds(1)), PROVIDER, new CancellationSource());

            assertEquals(ProviderVerificationState.FAILED, failed.state());
            assertEquals(Optional.of("VERIFICATION_EXECUTION_FAILED"), failed.errorCode());
            assertTrue(failed.usage().isEmpty());
        }
    }

    @Test
    void Harness拒绝零和负的平台超时上限() {
        ExecutorService zeroExecutor = Executors.newSingleThreadExecutor();
        ExecutorService negativeExecutor = Executors.newSingleThreadExecutor();
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ProviderVerificationHarness(
                            new CompletingGateway(), Clock.systemUTC(), Duration.ZERO, zeroExecutor));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ProviderVerificationHarness(
                            new CompletingGateway(), Clock.systemUTC(), Duration.ofMillis(-1), negativeExecutor));
        } finally {
            zeroExecutor.shutdownNow();
            negativeExecutor.shutdownNow();
        }
    }

    private static ProviderEndpoint endpoint(Duration timeout) {
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                "Provider",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("http://127.0.0.1:1")),
                ProviderAuthentication.API_KEY,
                List.of(new ProviderModelSpec(
                        "test-model", "test-model", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())),
                Optional.of(new CredentialRef("provider", "credential-1")),
                timeout,
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
        java.time.Instant now = java.time.Instant.parse("2026-09-01T08:00:00Z");
        return new ProviderEndpoint("provider-main", 2, ProviderLifecycle.ACTIVE, spec, now, now);
    }

    private static ModelCapabilities capabilities() {
        return new ModelCapabilities(true, true, true, false, false, false, false);
    }

    private static final class CompletingGateway implements ModelGateway {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<ModelInvocation> invocation = new AtomicReference<>();

        @Override
        public ModelCapabilities capabilities(String modelId) {
            return ProviderVerificationHarnessTest.capabilities();
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId, ModelInvocation value, ModelEventSink events, CancellationToken cancellation) {
            calls.incrementAndGet();
            invocation.set(value);
            return new ModelInvocationResult(
                    "SENSITIVE MODEL RESPONSE",
                    List.of(),
                    new ModelUsage(2, 1, 0, 0),
                    Optional.empty(),
                    Optional.empty(),
                    ModelFinishReason.COMPLETE);
        }
    }

    private static final class BlockingGateway implements ModelGateway {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public ModelCapabilities capabilities(String modelId) {
            return ProviderVerificationHarnessTest.capabilities();
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation)
                throws Exception {
            calls.incrementAndGet();
            entered.countDown();
            try {
                while (!cancellation.isCancelled()) {
                    Thread.sleep(10);
                }
                cancellation.throwIfCancelled();
                throw new AssertionError("cancelled invocation returned");
            } finally {
                released.countDown();
            }
        }
    }

    private static final class ContentFilteredGateway implements ModelGateway {
        @Override
        public ModelCapabilities capabilities(String modelId) {
            return ProviderVerificationHarnessTest.capabilities();
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation) {
            return new ModelInvocationResult(
                    "filtered",
                    List.of(),
                    new ModelUsage(1, 0, 0, 0),
                    Optional.empty(),
                    Optional.empty(),
                    ModelFinishReason.CONTENT_FILTER);
        }
    }

    private static final class ErrorGateway implements ModelGateway {
        @Override
        public ModelCapabilities capabilities(String modelId) {
            return ProviderVerificationHarnessTest.capabilities();
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation) {
            throw new AssertionError("provider implementation crashed outside the checked-exception boundary");
        }
    }
}
