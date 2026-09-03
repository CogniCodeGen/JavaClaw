package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.client.CommandOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSettingsPresenterStateTest {
    @Test
    void 脏草稿阻止刷新切换新建归档和凭据变更() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderEndpoint selected = gateway.providers.getFirst();
        ProviderEndpoint bound = gateway.setProviderCredential(
                        selected, 0, "secret".toCharArray(), CommandOptions.create(selected.revision()))
                .toCompletableFuture()
                .join()
                .provider();
        ProviderSettingsPresenter presenter = new ProviderSettingsPresenter(gateway, () -> "new-provider");
        presenter.reload();
        presenter.updateDraft(withDisplayName(presenter.state().draft(), "未保存名称"));

        presenter.reload();
        assertEquals("请先保存或放弃模型服务草稿", presenter.state().message());
        presenter.select(bound);
        assertEquals("未保存名称", presenter.state().draft().displayName());
        presenter.createDraft();
        assertEquals(bound.id(), presenter.state().draft().id());
        presenter.archive();
        assertEquals(ProviderLifecycle.ACTIVE, gateway.providers.getFirst().lifecycle());

        char[] replacement = "replacement".toCharArray();
        presenter.replaceSecret(replacement);
        assertEquals(1, gateway.providerCredentialSetCalls);
        assertTrue(allZero(replacement), "即使操作被草稿保护拒绝，调用方 Secret 也必须立即清零");
        presenter.clearSecret();
        assertEquals(0, gateway.providerCredentialClearCalls);
    }

    @Test
    void 本地校验拒绝空配置非法标识和空密钥() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderSettingsPresenter presenter = new ProviderSettingsPresenter(gateway, () -> "bad id");

        presenter.save();
        assertEquals(SettingsLoadState.ERROR, presenter.state().phase());
        assertThrows(IllegalArgumentException.class, presenter::createDraft);

        presenter = new ProviderSettingsPresenter(gateway);
        presenter.reload();
        presenter.replaceSecret(null);
        assertTrue(presenter.state().message().contains("密钥不能为空"));
        char[] empty = new char[0];
        presenter.replaceSecret(empty);
        assertTrue(presenter.state().message().contains("密钥不能为空"));
    }

    @Test
    void 空目录归档与凭据失败都形成明确状态() {
        TestCoreSettingsGateway emptyGateway = new TestCoreSettingsGateway();
        emptyGateway.providers.clear();
        ProviderSettingsPresenter empty = new ProviderSettingsPresenter(emptyGateway);
        empty.reload();
        assertTrue(empty.state().selected().isEmpty());
        assertEquals("尚未配置模型服务", empty.state().message());

        TestCoreSettingsGateway archiveGateway = new TestCoreSettingsGateway();
        ProviderSettingsPresenter archive = new ProviderSettingsPresenter(archiveGateway);
        archive.reload();
        archive.archive();
        assertEquals(
                ProviderLifecycle.ARCHIVED,
                archive.state().selected().orElseThrow().lifecycle());

        TestCoreSettingsGateway writeGateway = new TestCoreSettingsGateway();
        ProviderSettingsPresenter write = new ProviderSettingsPresenter(writeGateway);
        write.reload();
        writeGateway.nextFailure = new IllegalStateException("vault unavailable");
        write.replaceSecret("secret".toCharArray());
        assertEquals(SettingsLoadState.ERROR, write.state().phase());
        assertTrue(write.state().message().contains("vault unavailable"));

        ProviderEndpoint current = writeGateway.providers.getFirst();
        ProviderEndpoint bound = writeGateway
                .setProviderCredential(current, 0, "secret".toCharArray(), CommandOptions.create(current.revision()))
                .toCompletableFuture()
                .join()
                .provider();
        write.discardDraft();
        write.select(bound);
        writeGateway.nextFailure = new IllegalStateException("vault clear unavailable");
        write.clearSecret();
        assertEquals(SettingsLoadState.ERROR, write.state().phase());
        assertTrue(write.state().message().contains("vault clear unavailable"));
    }

    @Test
    void 首次配置恢复检测同标识不同内容并拒绝覆盖() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderSettingsPresenter presenter = new ProviderSettingsPresenter(gateway, () -> "provider-main");
        presenter.createDraft();
        presenter.updateDraft(withDisplayName(presenter.state().draft(), "冲突的新配置"));
        gateway.nextProviderCreateResponseFailure = new IllegalStateException("response lost");

        presenter.save();
        presenter.reload();

        assertTrue(presenter.state().revisionConflict());
        assertTrue(presenter.state().dirty());
        assertEquals("模型服务标识已存在且配置不同；未覆盖权威版本", presenter.state().message());
    }

    @Test
    void 无模型的连接壳不能执行本地探测() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderEndpoint existing = gateway.providers.getFirst();
        ProviderEndpointSpec current = existing.spec();
        ProviderEndpointSpec shell = new ProviderEndpointSpec(
                current.displayName(),
                current.adapter(),
                current.baseUri(),
                current.authentication(),
                List.of(),
                Optional.empty(),
                current.timeout(),
                current.maximumRetries(),
                current.options());
        gateway.providers.clear();
        gateway.providers.add(TestCoreSettingsFixtures.provider(1, shell, ProviderLifecycle.DISABLED));
        ProviderSettingsPresenter presenter = new ProviderSettingsPresenter(gateway);
        presenter.reload();

        IllegalStateException failure = assertThrows(IllegalStateException.class, presenter::probe);
        assertEquals("请先保存至少一个模型", failure.getMessage());
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

    private static boolean allZero(char[] value) {
        for (char character : value) {
            if (character != '\0') {
                return false;
            }
        }
        return true;
    }
}
