package com.javaclaw.desktop.settings;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderVerificationResult;
import com.javaclaw.api.ProviderVerificationState;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.ProviderVerificationRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderVerificationPresenterTest {
    @Test
    void 只有双重确认后才通过异步SDK执行精确模型验证() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderEndpoint initial = gateway.providers.getFirst();
        ProviderEndpoint bound = gateway.setProviderCredential(
                        initial, 0, "secret".toCharArray(), CommandOptions.create(initial.revision()))
                .toCompletableFuture()
                .join()
                .provider();
        ProviderVerificationPresenter presenter = new ProviderVerificationPresenter(gateway, ProviderModelPurpose.CHAT);
        AtomicReference<ProviderVerificationSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);
        presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false);

        assertTrue(latest.get().available());
        presenter.verify(true, "确认");
        assertEquals(0, gateway.providerVerificationCalls);
        assertEquals(SettingsLoadState.ERROR, latest.get().phase());

        presenter.verify(true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
        assertEquals(1, gateway.providerVerificationCalls);
        assertEquals(
                ProviderVerificationState.SUCCEEDED,
                latest.get().result().orElseThrow().state());
        assertEquals(SettingsLoadState.READY, latest.get().phase());
    }

    @Test
    void 停用缺凭据或脏草稿都明确关闭计费验证() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderVerificationPresenter presenter = new ProviderVerificationPresenter(gateway, ProviderModelPurpose.CHAT);
        ProviderEndpoint provider = gateway.providers.getFirst();

        presenter.bind(Optional.of(provider), Optional.of("fake-model"), false, false);
        assertFalse(presenter.state().available());
        assertTrue(presenter.state().message().contains("CredentialRef"));

        ProviderEndpoint disabled = new ProviderEndpoint(
                provider.id(),
                provider.revision(),
                ProviderLifecycle.DISABLED,
                provider.spec(),
                provider.createdAt(),
                provider.updatedAt());
        presenter.bind(Optional.of(disabled), Optional.of("fake-model"), true, false);
        assertFalse(presenter.state().available());
        assertEquals("只有已启用的模型服务可以执行可能计费的验证", presenter.state().message());

        presenter.bind(Optional.of(provider), Optional.of("fake-model"), true, true);
        assertFalse(presenter.state().available());
    }

    @Test
    void 向量Presenter只接受Embedding用途并保留独立结果() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderEndpoint initial = gateway.providers.getFirst();
        ProviderEndpoint bound = gateway.setProviderCredential(
                        initial, 0, "secret".toCharArray(), CommandOptions.create(initial.revision()))
                .toCompletableFuture()
                .join()
                .provider();
        ProviderEndpoint endpoint = withPurposes(bound);
        ProviderVerificationPresenter presenter =
                new ProviderVerificationPresenter(gateway, ProviderModelPurpose.EMBEDDING);

        presenter.bind(Optional.of(endpoint), Optional.of("fake-model"), true, false);
        presenter.verify(true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);

        assertEquals(ProviderModelPurpose.EMBEDDING, presenter.state().purpose());
        assertEquals(
                ProviderModelPurpose.EMBEDDING,
                presenter.state().result().orElseThrow().purpose());
        assertEquals(Optional.empty(), presenter.state().result().orElseThrow().usage());
    }

    @Test
    void 选择和凭据条件逐项决定是否允许计费验证() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderEndpoint initial = gateway.providers.getFirst();
        ProviderEndpoint bound = gateway.setProviderCredential(
                        initial, 0, "secret".toCharArray(), CommandOptions.create(initial.revision()))
                .toCompletableFuture()
                .join()
                .provider();
        ProviderVerificationPresenter presenter = new ProviderVerificationPresenter(gateway, ProviderModelPurpose.CHAT);

        presenter.bind(Optional.empty(), Optional.empty(), true, false);
        assertEquals("请选择已保存的模型服务", presenter.state().message());

        presenter.bind(Optional.of(bound), Optional.of("missing-model"), true, false);
        assertEquals("请选择精确模型", presenter.state().message());

        ProviderEndpoint embeddingOnly = withPurpose(bound, ProviderModelPurpose.EMBEDDING);
        presenter.bind(Optional.of(embeddingOnly), Optional.of("fake-model"), true, false);
        assertFalse(presenter.state().available());

        presenter.bind(Optional.of(bound), Optional.of("fake-model"), false, false);
        assertEquals("CredentialRef 不可用，计费验证已关闭", presenter.state().message());

        ProviderEndpoint noAuthentication = withoutAuthentication(initial);
        presenter.bind(Optional.of(noAuthentication), Optional.of("fake-model"), true, false);
        assertTrue(presenter.state().available());
        long epoch = presenter.state().epoch();
        presenter.bind(Optional.of(noAuthentication), Optional.of("fake-model"), true, false);
        assertEquals(epoch, presenter.state().epoch(), "相同精确引用和可用性不应使已有验证结果失效");
    }

    @Test
    void 计费验证拒绝未确认并呈现远端失败与用途不匹配() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderEndpoint bound = bindCredential(gateway);
        ProviderVerificationPresenter presenter = new ProviderVerificationPresenter(gateway, ProviderModelPurpose.CHAT);
        presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false);

        presenter.verify(false, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
        assertEquals(0, gateway.providerVerificationCalls);

        presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false);
        gateway.nextFailure = new IllegalStateException("provider unavailable");
        presenter.verify(true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
        assertEquals(SettingsLoadState.ERROR, presenter.state().phase());
        assertTrue(presenter.state().message().contains("provider unavailable"));

        ProviderVerificationPresenter mismatched = new ProviderVerificationPresenter(
                overrideVerification(
                        gateway,
                        reference -> CompletableFuture.completedFuture(TestCoreSettingsFixtures.verification(
                                reference, ProviderModelPurpose.EMBEDDING, bound.updatedAt()))),
                ProviderModelPurpose.CHAT);
        mismatched.bind(Optional.of(bound), Optional.of("fake-model"), true, false);
        mismatched.verify(true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
        assertEquals("模型服务返回了不匹配的验证用途", mismatched.state().message());
    }

    @Test
    void 旧计费验证响应不会覆盖新的模型选择() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderEndpoint bound = bindCredential(gateway);
        CompletableFuture<ProviderVerificationResult> pending = new CompletableFuture<>();
        ProviderVerificationPresenter presenter = new ProviderVerificationPresenter(
                overrideVerification(gateway, ignored -> pending), ProviderModelPurpose.CHAT);
        presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false);

        presenter.verify(true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
        presenter.bind(Optional.empty(), Optional.empty(), true, false);
        pending.complete(TestCoreSettingsFixtures.verification(
                new ProviderRef(bound.id(), bound.revision(), "fake-model"),
                ProviderModelPurpose.CHAT,
                bound.updatedAt()));

        assertTrue(presenter.state().result().isEmpty());
        assertEquals("请选择已保存的模型服务", presenter.state().message());
    }

    @Test
    void 配置更新与未保存草稿分别提示且更新完成直接恢复验证() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderEndpoint bound = bindCredential(gateway);
        ProviderVerificationPresenter presenter = new ProviderVerificationPresenter(gateway, ProviderModelPurpose.CHAT);
        presenter.bind(Optional.of(bound), Optional.of("fake-model"), false, true, true);
        assertFalse(presenter.state().available());
        assertEquals("正在更新模型配置，请稍候…", presenter.state().message());
        long busyEpoch = presenter.state().epoch();

        presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, true, false);

        assertFalse(presenter.state().available());
        assertEquals("请先保存或放弃模型服务草稿", presenter.state().message());
        assertTrue(presenter.state().epoch() > busyEpoch, "可用性相同但阻塞原因改变仍须更新绑定");
        presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false, false);
        assertTrue(presenter.state().available());
        presenter.verify(true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
        assertEquals(1, gateway.providerVerificationCalls);
        assertTrue(presenter.state().result().isPresent());
    }

    @Test
    void 同引用重绑保留在途阶段且重复验证不会再次请求或取消原响应() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderEndpoint bound = bindCredential(gateway);
        CompletableFuture<ProviderVerificationResult> pending = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        ProviderVerificationPresenter presenter = new ProviderVerificationPresenter(
                overrideVerification(gateway, ignored -> {
                    calls.incrementAndGet();
                    return pending;
                }),
                ProviderModelPurpose.CHAT);
        presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false, false);
        presenter.verify(true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
        ProviderVerificationSettingsState saving = presenter.state();
        assertTrue(saving.pending());

        presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false, false);
        presenter.verify(true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
        presenter.verify(false, "错误确认");

        assertSame(saving, presenter.state());
        assertEquals(1, calls.get());
        ProviderVerificationResult result = TestCoreSettingsFixtures.verification(
                saving.provider().orElseThrow(), ProviderModelPurpose.CHAT, bound.updatedAt());
        pending.complete(result);
        ProviderVerificationSettingsState completed = presenter.state();
        assertEquals(SettingsLoadState.READY, completed.phase());
        assertEquals(saving.epoch(), completed.epoch());
        assertEquals(Optional.of(result), completed.result());
        presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false, false);
        assertSame(completed, presenter.state());
        presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false, true);
        assertEquals("正在更新模型配置，请稍候…", presenter.state().message());
        assertFalse(presenter.state().available());
        assertEquals(completed.result(), presenter.state().result());
        assertEquals(completed.epoch(), presenter.state().epoch());
        presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false, false);
        assertTrue(presenter.state().available());
        assertEquals(completed.result(), presenter.state().result());
    }

    @Test
    void 同引用重绑保留失败说明并允许用户再次显式确认重试() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderEndpoint bound = bindCredential(gateway);
        ProviderVerificationPresenter presenter = new ProviderVerificationPresenter(gateway, ProviderModelPurpose.CHAT);
        presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false, false);
        gateway.nextFailure = new IllegalStateException("测试连接暂不可用");
        presenter.verify(true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
        ProviderVerificationSettingsState failed = presenter.state();
        assertEquals(SettingsLoadState.ERROR, failed.phase());
        assertTrue(failed.message().contains("测试连接暂不可用"));

        presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false, false);

        assertSame(failed, presenter.state());
        presenter.verify(true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
        assertEquals(2, gateway.providerVerificationCalls);
        assertEquals(SettingsLoadState.READY, presenter.state().phase());
        assertTrue(presenter.state().result().isPresent());
    }

    @Test
    void 在途验证遇到目录刷新只更新可用性并在当前忙碌状态接收精确结果() {
        for (boolean completeWhileBusy : List.of(false, true)) {
            TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
            ProviderEndpoint bound = bindCredential(gateway);
            CompletableFuture<ProviderVerificationResult> pending = new CompletableFuture<>();
            AtomicInteger calls = new AtomicInteger();
            ProviderVerificationPresenter presenter = new ProviderVerificationPresenter(
                    overrideVerification(gateway, ignored -> {
                        calls.incrementAndGet();
                        return pending;
                    }),
                    ProviderModelPurpose.CHAT);
            presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false, false);
            presenter.verify(true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
            long epoch = presenter.state().epoch();

            presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false, true);
            assertTrue(presenter.state().pending());
            assertFalse(presenter.state().available());
            assertEquals(epoch, presenter.state().epoch());
            presenter.verify(true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
            if (!completeWhileBusy) {
                presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false, false);
                assertTrue(presenter.state().pending());
                presenter.verify(true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
            }
            assertEquals(1, calls.get());
            ProviderVerificationResult result = TestCoreSettingsFixtures.verification(
                    presenter.state().provider().orElseThrow(), ProviderModelPurpose.CHAT, bound.updatedAt());
            pending.complete(result);

            assertEquals(SettingsLoadState.READY, presenter.state().phase());
            assertEquals(!completeWhileBusy, presenter.state().available());
            if (completeWhileBusy) {
                assertEquals("正在更新模型配置，请稍候…", presenter.state().message());
            }
            assertEquals(epoch, presenter.state().epoch());
            assertEquals(Optional.of(result), presenter.state().result());
            presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false, false);
            assertTrue(presenter.state().available());
            assertEquals(epoch, presenter.state().epoch());
            assertEquals(Optional.of(result), presenter.state().result());
        }
    }

    @Test
    void 在途验证遇到凭据失效或服务停用时不接受旧结果() {
        for (boolean credentialLost : List.of(false, true)) {
            TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
            ProviderEndpoint bound = bindCredential(gateway);
            CompletableFuture<ProviderVerificationResult> pending = new CompletableFuture<>();
            ProviderVerificationPresenter presenter = new ProviderVerificationPresenter(
                    overrideVerification(gateway, ignored -> pending), ProviderModelPurpose.CHAT);
            presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false, false);
            presenter.verify(true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
            long epoch = presenter.state().epoch();
            ProviderEndpoint current = credentialLost
                    ? bound
                    : new ProviderEndpoint(
                            bound.id(),
                            bound.revision(),
                            ProviderLifecycle.DISABLED,
                            bound.spec(),
                            bound.createdAt(),
                            bound.updatedAt());

            presenter.bind(Optional.of(current), Optional.of("fake-model"), !credentialLost, false, false);
            pending.complete(TestCoreSettingsFixtures.verification(
                    new ProviderRef(bound.id(), bound.revision(), "fake-model"),
                    ProviderModelPurpose.CHAT,
                    bound.updatedAt()));

            assertTrue(presenter.state().epoch() > epoch);
            assertFalse(presenter.state().available());
            assertFalse(presenter.state().pending());
            assertTrue(presenter.state().result().isEmpty());
        }
    }

    @Test
    void 同模型出现草稿使旧验证失效而不会覆盖恢复可用的状态() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderEndpoint bound = bindCredential(gateway);
        CompletableFuture<ProviderVerificationResult> pending = new CompletableFuture<>();
        ProviderVerificationPresenter presenter = new ProviderVerificationPresenter(
                overrideVerification(gateway, ignored -> pending), ProviderModelPurpose.CHAT);
        presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false, false);
        presenter.verify(true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
        long previousEpoch = presenter.state().epoch();

        presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, true, false);
        presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false, false);
        ProviderVerificationSettingsState restored = presenter.state();
        pending.complete(TestCoreSettingsFixtures.verification(
                restored.provider().orElseThrow(), ProviderModelPurpose.CHAT, bound.updatedAt()));

        assertTrue(restored.epoch() > previousEpoch);
        assertTrue(restored.available());
        assertSame(restored, presenter.state());
        assertTrue(presenter.state().result().isEmpty());
    }

    @Test
    void 验证结果必须匹配精确服务标识版本与模型标识() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderEndpoint bound = bindCredential(gateway);
        List<ProviderRef> mismatches = List.of(
                new ProviderRef("another-provider", bound.revision(), "fake-model"),
                new ProviderRef(bound.id(), bound.revision() + 1, "fake-model"),
                new ProviderRef(bound.id(), bound.revision(), "another-model"));
        for (ProviderRef mismatch : mismatches) {
            ProviderVerificationPresenter presenter = new ProviderVerificationPresenter(
                    overrideVerification(
                            gateway,
                            ignored -> CompletableFuture.completedFuture(TestCoreSettingsFixtures.verification(
                                    mismatch, ProviderModelPurpose.CHAT, bound.updatedAt()))),
                    ProviderModelPurpose.CHAT);
            presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false, false);

            presenter.verify(true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);

            assertEquals(SettingsLoadState.ERROR, presenter.state().phase());
            assertEquals("模型服务返回了不匹配的模型版本", presenter.state().message());
            assertTrue(presenter.state().result().isEmpty());
        }
    }

    @Test
    void 空验证响应不能伪装成功或令页面永久停留在请求中() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderEndpoint bound = bindCredential(gateway);
        ProviderVerificationPresenter presenter = new ProviderVerificationPresenter(
                overrideVerification(gateway, ignored -> CompletableFuture.completedFuture(null)),
                ProviderModelPurpose.CHAT);
        presenter.bind(Optional.of(bound), Optional.of("fake-model"), true, false, false);

        presenter.verify(true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);

        assertEquals(SettingsLoadState.ERROR, presenter.state().phase());
        assertEquals("模型服务未返回验证结果", presenter.state().message());
        assertFalse(presenter.state().pending());
        assertTrue(presenter.state().result().isEmpty());
    }

    private static ProviderEndpoint withPurposes(ProviderEndpoint endpoint) {
        ProviderEndpointSpec current = endpoint.spec();
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                current.displayName(),
                current.adapter(),
                current.baseUri(),
                current.authentication(),
                List.of(new ProviderModelSpec(
                        "fake-model",
                        "Fake model",
                        Set.of(ProviderModelPurpose.CHAT, ProviderModelPurpose.EMBEDDING),
                        OptionalInt.empty())),
                current.credential(),
                current.timeout(),
                current.maximumRetries(),
                current.options());
        return new ProviderEndpoint(
                endpoint.id(),
                endpoint.revision(),
                endpoint.lifecycle(),
                spec,
                endpoint.createdAt(),
                endpoint.updatedAt());
    }

    private static ProviderEndpoint withPurpose(ProviderEndpoint endpoint, ProviderModelPurpose purpose) {
        ProviderEndpointSpec current = endpoint.spec();
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                current.displayName(),
                current.adapter(),
                current.baseUri(),
                current.authentication(),
                List.of(new ProviderModelSpec("fake-model", "Fake model", Set.of(purpose), OptionalInt.empty())),
                current.credential(),
                current.timeout(),
                current.maximumRetries(),
                current.options());
        return new ProviderEndpoint(
                endpoint.id(),
                endpoint.revision(),
                endpoint.lifecycle(),
                spec,
                endpoint.createdAt(),
                endpoint.updatedAt());
    }

    private static ProviderEndpoint withoutAuthentication(ProviderEndpoint endpoint) {
        ProviderEndpointSpec current = endpoint.spec();
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                current.displayName(),
                current.adapter(),
                current.baseUri(),
                ProviderAuthentication.NONE,
                current.models(),
                Optional.empty(),
                current.timeout(),
                current.maximumRetries(),
                current.options());
        return new ProviderEndpoint(
                endpoint.id(),
                endpoint.revision(),
                endpoint.lifecycle(),
                spec,
                endpoint.createdAt(),
                endpoint.updatedAt());
    }

    private static ProviderEndpoint bindCredential(TestCoreSettingsGateway gateway) {
        ProviderEndpoint initial = gateway.providers.getFirst();
        return gateway.setProviderCredential(
                        initial, 0, "secret".toCharArray(), CommandOptions.create(initial.revision()))
                .toCompletableFuture()
                .join()
                .provider();
    }

    private static CoreSettingsGateway overrideVerification(
            TestCoreSettingsGateway delegate,
            Function<ProviderRef, CompletionStage<ProviderVerificationResult>> verification) {
        return (CoreSettingsGateway) Proxy.newProxyInstance(
                CoreSettingsGateway.class.getClassLoader(),
                new Class<?>[] {CoreSettingsGateway.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("verifyProviderRoundTrip")) {
                        return verification.apply((ProviderRef) args[0]);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
