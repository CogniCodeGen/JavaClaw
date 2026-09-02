package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.protocol.BuiltinExtensionRpcContracts;

/** 只通过当前 Java SDK 会话执行内置能力管理请求。 */
final class SdkBuiltinExtensionSettingsGateway implements BuiltinExtensionSettingsGateway {
    private final DesktopPresenter desktop;

    /** @param desktop 拥有 SDK 会话和后台执行器的 Presenter */
    SdkBuiltinExtensionSettingsGateway(DesktopPresenter desktop) {
        this.desktop = Objects.requireNonNull(desktop, "desktop");
    }

    @Override
    public CompletionStage<List<BuiltinExtensionRpcContracts.Status>> builtinExtensions() {
        return desktop.submitSettingsRequest(
                client -> client.builtinExtensionManagement().list());
    }

    @Override
    public CompletionStage<BuiltinExtensionRpcContracts.Status> setBuiltinExtensionEnabled(
            BuiltinExtensionRpcContracts.Status current, boolean enabled) {
        BuiltinExtensionRpcContracts.Status checked = Objects.requireNonNull(current, "current");
        CommandOptions options = CommandOptions.create(checked.stateRevision());
        return desktop.submitSettingsRequest(client -> enabled
                ? client.builtinExtensionManagement().enable(checked.id(), options)
                : client.builtinExtensionManagement().disable(checked.id(), options));
    }
}
