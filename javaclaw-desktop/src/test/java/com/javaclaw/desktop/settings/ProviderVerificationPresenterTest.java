package com.javaclaw.desktop.settings;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
