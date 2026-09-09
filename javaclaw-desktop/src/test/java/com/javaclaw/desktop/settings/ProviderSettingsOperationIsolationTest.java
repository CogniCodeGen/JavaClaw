package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderCredentialBinding;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.client.CommandOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSettingsOperationIsolationTest {
    @Test
    void 密钥绑定等待回执时保留原服务和草稿并拒绝重入刷新或切换() {
        Gateway gateway = new Gateway();
        ProviderSettingsPresenter presenter = new ProviderSettingsPresenter(gateway);
        presenter.reload();
        ProviderEndpoint original = presenter.state().selected().orElseThrow();
        ProviderDraft before = presenter.state().draft();
        presenter.replaceSecret("temporary".toCharArray());

        presenter.select(gateway.second);
        presenter.createDraft();
        presenter.updateDraft(before.withLifecycle(ProviderLifecycle.DISABLED));
        presenter.discardDraft();
        presenter.reload();
        presenter.save();

        assertTrue(presenter.state().pending());
        assertEquals(original, presenter.state().selected().orElseThrow());
        assertEquals(before, presenter.state().draft());
        assertEquals(1, gateway.reads);
        assertEquals(0, gateway.updates);
        gateway.binding.complete(gateway.bound);
        assertFalse(presenter.state().pending());
        assertEquals(gateway.bound.provider(), presenter.state().selected().orElseThrow());
        presenter.select(gateway.second);
        assertEquals(gateway.second, presenter.state().selected().orElseThrow());
    }

    @Test
    void 保存中的编辑不解除在途状态且保存失败保留提交草稿供重试() {
        Gateway gateway = new Gateway();
        ProviderSettingsPresenter presenter = new ProviderSettingsPresenter(gateway);
        presenter.reload();
        ProviderDraft original = presenter.state().draft();
        ProviderDraft submitted = original.withLifecycle(ProviderLifecycle.DISABLED);
        presenter.updateDraft(submitted);
        presenter.save();

        presenter.updateDraft(original);
        presenter.save();
        presenter.discardDraft();
        assertTrue(presenter.state().pending());
        assertEquals(submitted, presenter.state().draft());
        assertEquals(1, gateway.updates);

        gateway.write.completeExceptionally(new IllegalStateException("本地服务暂不可用"));
        assertFalse(presenter.state().pending());
        assertTrue(presenter.state().dirty());
        assertEquals(submitted, presenter.state().draft());
        assertEquals(original, presenter.state().baseline());
        presenter.updateDraft(original);
        assertFalse(presenter.state().dirty(), "失败后应恢复可编辑，不能永久锁住表单");
    }

    private static final class Gateway extends TestCoreSettingsGateway {
        private final CompletableFuture<ProviderCredentialBinding> binding = new CompletableFuture<>();
        private final CompletableFuture<ProviderEndpoint> write = new CompletableFuture<>();
        private final ProviderEndpoint second;
        private ProviderCredentialBinding bound;
        private int reads;
        private int updates;

        private Gateway() {
            ProviderEndpoint first = providers.getFirst();
            second = new ProviderEndpoint(
                    "provider-other", 1, first.lifecycle(), first.spec(), first.createdAt(), first.updatedAt());
            providers.add(second);
        }

        @Override
        public CompletionStage<List<ProviderEndpoint>> providers() {
            reads++;
            return super.providers();
        }

        @Override
        public CompletionStage<ProviderCredentialBinding> setProviderCredential(
                ProviderEndpoint provider, long revision, char[] secret, CommandOptions options) {
            bound = super.setProviderCredential(provider, revision, secret, options)
                    .toCompletableFuture()
                    .join();
            return binding;
        }

        @Override
        public CompletionStage<ProviderEndpoint> updateProvider(
                String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle, CommandOptions options) {
            updates++;
            return write;
        }
    }
}
