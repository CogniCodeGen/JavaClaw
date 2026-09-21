package com.javaclaw.desktop.settings;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ProviderConfiguration;
import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.api.ProviderModelPreviewRequest;
import com.javaclaw.api.ProviderModelPreviewResult;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.facade.PreparedProviderConfiguration;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.DesktopConfigurationChange;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.protocol.ProviderConfigurationRpcContracts;

/** 通过 Presenter 当前 SDK 会话执行 Provider 完整配置，不直连模型服务。 */
abstract class SdkProviderConfigurationSettingsGateway extends SdkRoleExecutionSettingsGateway {
    private final DesktopPresenter desktop;

    SdkProviderConfigurationSettingsGateway(DesktopPresenter desktop) {
        super(desktop);
        this.desktop = Objects.requireNonNull(desktop, "desktop");
    }

    @Override
    public com.javaclaw.desktop.DesktopNotificationSubscription onProviderConfigurationSessionInvalidated(
            Runnable listener) {
        return new ProviderConfigurationSessionWatch(desktop, listener);
    }

    @Override
    public CompletionStage<Boolean> providerConfigurationSupported() {
        return desktop.submitSettingsRequest(client -> client.server()
                .capabilities()
                .stableCapabilities()
                .contains(ProviderConfigurationRpcContracts.CAPABILITY));
    }

    @Override
    public CompletionStage<ProviderModelPreviewResult> previewProviderModels(
            ProviderModelPreviewRequest request, char[] secret, CancellationToken cancellation) {
        return withSecret(
                secret, (client, owned) -> client.providers().configuration().preview(request, owned, cancellation));
    }

    @Override
    public CompletionStage<PreparedProviderConfiguration> prepareProviderConfiguration(
            ProviderConfiguration configuration, char[] secret, CommandOptions options) {
        return withSecret(
                secret, (client, owned) -> client.providers().configuration().prepare(configuration, owned, options));
    }

    @Override
    public CompletionStage<ProviderConfigurationResult> saveProviderConfiguration(
            PreparedProviderConfiguration prepared) {
        return changed(
                desktop.submitSettingsRequest(
                        client -> client.providers().configuration().save(prepared)),
                DesktopConfigurationChange.Kind.PROVIDERS);
    }

    @Override
    public CompletionStage<Optional<ProviderConfigurationResult>> providerConfigurationResult(
            PreparedProviderConfiguration prepared) {
        return desktop.submitSettingsRequest(
                        client -> client.providers().configuration().result(prepared))
                .thenApply(result -> {
                    if (result.isPresent()) {
                        desktop.configurationEvents()
                                .publish(new DesktopConfigurationChange(
                                        DesktopConfigurationChange.Kind.PROVIDERS, Optional.empty(), Optional.empty()));
                    }
                    return result;
                });
    }

    private <T> CompletionStage<T> withSecret(char[] secret, BiFunction<JavaClawClient, char[], T> operation) {
        char[] owned = Objects.requireNonNull(secret, "secret").clone();
        Arrays.fill(secret, '\0');
        try {
            // SDK 请求失败或会话失效而未执行回调时也清零；秘密不进入页面状态。
            return desktop.submitSettingsRequest(client -> operation.apply(client, owned))
                    .whenComplete((result, failure) -> Arrays.fill(owned, '\0'));
        } catch (RuntimeException failure) {
            Arrays.fill(owned, '\0');
            throw failure;
        }
    }
}
