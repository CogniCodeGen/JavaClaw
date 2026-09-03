package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSetupPresenterTest {
    @Test
    void 首次配置按权威状态恢复四个步骤并使用确定性命令() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderSettingsPresenter presenter = new ProviderSettingsPresenter(gateway, () -> "recoverable-provider");
        presenter.createDraft();
        presenter.updateDraft(withDisplayName(presenter.state().draft(), "可恢复模型服务"));

        presenter.save();

        assertEquals(ProviderLifecycle.DISABLED, gateway.lastProviderCreateLifecycle);
        assertTrue(presenter.state().selected().orElseThrow().spec().models().isEmpty());
        assertEquals(ProviderSetupPhase.CREDENTIAL, presenter.state().setupPhase());
        assertTrue(gateway.lastProviderCreateOptions.idempotencyKey().startsWith("provider-setup.connection."));

        presenter.replaceSecret("temporary-secret".toCharArray());

        assertEquals(ProviderSetupPhase.MODELS, presenter.state().setupPhase());
        assertTrue(gateway.lastProviderCredentialOptions.idempotencyKey().startsWith("provider-setup.credential."));

        ProviderModelSpec model = new ProviderModelSpec(
                "chat-model", "Chat Model", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty());
        presenter.updateDraft(
                presenter.state().draft().withModels(List.of(model)).withLifecycle(ProviderLifecycle.ACTIVE));
        presenter.save();

        assertEquals(ProviderSetupPhase.COMPLETE, presenter.state().setupPhase());
        assertEquals(
                ProviderLifecycle.ACTIVE,
                presenter.state().selected().orElseThrow().lifecycle());
        assertTrue(gateway.lastProviderUpdateOptions.idempotencyKey().startsWith("provider-setup.models-enable."));
    }

    @Test
    void 连接壳提交后丢失响应会重新读取同一标识并继续缺失步骤() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderSettingsPresenter presenter = new ProviderSettingsPresenter(gateway, () -> "response-lost-provider");
        presenter.createDraft();
        presenter.updateDraft(withDisplayName(presenter.state().draft(), "丢失响应模型服务"));
        gateway.nextProviderCreateResponseFailure = new IllegalStateException("连接在提交后中断");

        presenter.save();
        presenter.reload();

        assertEquals(
                "response-lost-provider",
                presenter.state().selected().orElseThrow().id());
        assertEquals(ProviderSetupPhase.CREDENTIAL, presenter.state().setupPhase());
        assertTrue(presenter.state().message().contains("恢复首次配置"));
    }

    @Test
    void 同一首次配置内容生成相同幂等键而不同内容不会碰撞() {
        ProviderDraft first = withDisplayName(ProviderDraft.forNew("provider-id"), "模型服务 A");
        ProviderDraft same = withDisplayName(ProviderDraft.forNew("provider-id"), "模型服务 A");
        ProviderDraft changed = withDisplayName(ProviderDraft.forNew("provider-id"), "模型服务 B");

        String firstKey = ProviderSetupCommands.createShell(
                        "provider-id", ProviderSetupCommands.connectionShell(first.toSpec()))
                .idempotencyKey();
        String sameKey = ProviderSetupCommands.createShell(
                        "provider-id", ProviderSetupCommands.connectionShell(same.toSpec()))
                .idempotencyKey();
        String changedKey = ProviderSetupCommands.createShell(
                        "provider-id", ProviderSetupCommands.connectionShell(changed.toSpec()))
                .idempotencyKey();

        assertEquals(firstKey, sameKey);
        org.junit.jupiter.api.Assertions.assertNotEquals(firstKey, changedKey);
    }

    private static ProviderDraft withDisplayName(ProviderDraft draft, String displayName) {
        return new ProviderDraft(
                draft.id(),
                displayName,
                draft.adapter(),
                draft.baseUri(),
                draft.authentication(),
                draft.models(),
                draft.credential(),
                draft.timeoutSeconds(),
                draft.maximumRetries(),
                draft.organization(),
                draft.project(),
                draft.apiVersion(),
                draft.reasoningSummary(),
                draft.lifecycle());
    }
}
