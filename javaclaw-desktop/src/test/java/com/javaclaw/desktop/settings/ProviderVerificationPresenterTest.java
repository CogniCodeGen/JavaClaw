package com.javaclaw.desktop.settings;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
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
        ProviderVerificationPresenter presenter = new ProviderVerificationPresenter(gateway);
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
        ProviderVerificationPresenter presenter = new ProviderVerificationPresenter(gateway);
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
        assertTrue(presenter.state().message().contains("ACTIVE"));

        presenter.bind(Optional.of(provider), Optional.of("fake-model"), true, true);
        assertFalse(presenter.state().available());
    }
}
