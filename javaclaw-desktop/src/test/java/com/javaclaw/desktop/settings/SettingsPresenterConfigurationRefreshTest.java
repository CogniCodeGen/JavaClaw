package com.javaclaw.desktop.settings;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.RoleLifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsPresenterConfigurationRefreshTest {
    @Test
    void 模型目录更新保留读取期间继续输入的草稿及原保存版本() {
        DeferredGateway gateway = new DeferredGateway();
        ProviderSettingsPresenter presenter = new ProviderSettingsPresenter(gateway);
        presenter.reload();
        ProviderDraft baseline = presenter.state().baseline();
        presenter.updateDraft(providerDraft(ProviderLifecycle.DISABLED));
        gateway.providerRead = new CompletableFuture<>();
        presenter.refreshForConfigurationChange(true);
        ProviderDraft later = providerDraft(ProviderLifecycle.ARCHIVED);
        presenter.updateDraft(later);
        gateway.providerRead.complete(List.of(provider(2)));

        assertEquals(later, presenter.state().draft());
        assertEquals(baseline, presenter.state().baseline());
        assertEquals(1, presenter.state().selected().orElseThrow().revision());
        assertEquals(2, presenter.state().providers().getFirst().revision());
        assertTrue(presenter.state().message().contains("保存版本保持不变"));
        gateway.nextFailure = new IllegalStateException("版本冲突");
        presenter.save();
        assertEquals(1, gateway.lastProviderUpdateOptions.expectedRevision());
    }

    @Test
    void 干净模型表单重验期间开始编辑也不能被目录响应覆盖() {
        DeferredGateway gateway = new DeferredGateway();
        ProviderSettingsPresenter presenter = new ProviderSettingsPresenter(gateway);
        presenter.reload();
        gateway.providerRead = new CompletableFuture<>();
        presenter.refreshForConfigurationChange(false);
        ProviderDraft draft = providerDraft(ProviderLifecycle.DISABLED);
        presenter.updateDraft(draft);
        gateway.providerRead.complete(List.of(provider(2)));

        assertEquals(draft, presenter.state().draft());
        assertEquals(1, presenter.state().selected().orElseThrow().revision());
        assertEquals(2, presenter.state().providers().getFirst().revision());
    }

    @Test
    void 角色草稿自动刷新目录保留精确模型引用及角色保存版本() {
        DeferredGateway gateway = new DeferredGateway();
        gateway.profiles.add(role(1, RoleLifecycle.ACTIVE));
        AgentRoleSettingsPresenter presenter = new AgentRoleSettingsPresenter(gateway);
        presenter.reload();
        AgentRoleDraft baseline = presenter.state().baseline();
        AgentRoleDraft draft = AgentRoleDraft.from(role(1, RoleLifecycle.ARCHIVED));
        presenter.updateDraft(draft);
        gateway.profiles.set(0, role(2, RoleLifecycle.ACTIVE));
        gateway.providers.set(0, provider(2));
        presenter.refreshForConfigurationChange(true);

        assertEquals(baseline, presenter.state().baseline());
        assertEquals(draft, presenter.state().draft());
        assertEquals(1, presenter.state().selected().orElseThrow().revision());
        assertEquals(2, presenter.state().roles().getFirst().revision());
        assertEquals(2, presenter.state().providers().getFirst().revision());
        assertEquals(1, presenter.state().draft().provider().orElseThrow().endpointRevision());
        assertTrue(presenter.state().message().contains("保存版本保持不变"));
    }

    @Test
    void 权限目录响应保留读取期间继续输入及原始版本() {
        DeferredGateway gateway = new DeferredGateway();
        PermissionProfile original = permission(gateway.permissions.getFirst(), 1, 10);
        gateway.permissions.add(original);
        PermissionProfileSettingsPresenter presenter = new PermissionProfileSettingsPresenter(gateway);
        presenter.reload();
        presenter.select(original);
        PermissionProfileDraft baseline = presenter.state().baseline();
        presenter.updateDraft(PermissionProfileDraft.from(permission(original, 1, 20)));
        gateway.permissionRead = new CompletableFuture<>();
        presenter.refreshForConfigurationChange(true);
        PermissionProfileDraft later = PermissionProfileDraft.from(permission(original, 1, 30));
        presenter.updateDraft(later);
        gateway.permissionRead.complete(List.of(permission(original, 2, 40)));

        assertEquals(later, presenter.state().draft());
        assertEquals(baseline, presenter.state().baseline());
        assertEquals(1, presenter.state().selected().orElseThrow().version());
        assertEquals(2, presenter.state().profiles().getFirst().version());
        assertTrue(presenter.state().message().contains("保存版本保持不变"));
    }

    @Test
    void 干净权限页刷新推进基线但保留自定义方案选择() {
        DeferredGateway gateway = new DeferredGateway();
        PermissionProfile original = permission(gateway.permissions.getFirst(), 1, 10);
        gateway.permissions.add(original);
        PermissionProfileSettingsPresenter presenter = new PermissionProfileSettingsPresenter(gateway);
        presenter.reload();
        presenter.select(original);
        gateway.permissions.set(1, permission(original, 2, 20));
        presenter.refreshForConfigurationChange(false);

        assertEquals("custom", presenter.state().selected().orElseThrow().id());
        assertEquals(2, presenter.state().selected().orElseThrow().version());
        assertEquals(20, presenter.state().draft().openFiles());
        assertFalse(presenter.state().dirty());
    }

    private static ProviderEndpoint provider(long revision) {
        return TestCoreSettingsFixtures.provider(
                revision, TestCoreSettingsFixtures.providerSpec(Optional.empty()), ProviderLifecycle.ACTIVE);
    }

    private static ProviderDraft providerDraft(ProviderLifecycle lifecycle) {
        return ProviderDraft.from(TestCoreSettingsFixtures.provider(
                1, TestCoreSettingsFixtures.providerSpec(Optional.empty()), lifecycle));
    }

    private static AgentRole role(long revision, RoleLifecycle lifecycle) {
        Instant now = Instant.parse("2026-09-01T01:00:00Z");
        return new AgentRole("reviewer", revision, lifecycle, TestCoreSettingsFixtures.profileSpec(), false, now, now);
    }

    private static PermissionProfile permission(PermissionProfile template, long revision, int openFiles) {
        ResourceLimits limits = template.resources();
        return new PermissionProfile(
                "custom",
                revision,
                template.files(),
                template.network(),
                template.processes(),
                template.tools(),
                new ResourceLimits(limits.memoryBytes(), limits.outputBytes(), limits.childProcesses(), openFiles));
    }

    private static final class DeferredGateway extends TestCoreSettingsGateway {
        private CompletableFuture<List<ProviderEndpoint>> providerRead;
        private CompletableFuture<List<PermissionProfile>> permissionRead;

        @Override
        public CompletionStage<List<ProviderEndpoint>> providers() {
            return providerRead == null ? super.providers() : providerRead;
        }

        @Override
        public CompletionStage<List<PermissionProfile>> permissionProfiles() {
            return permissionRead == null ? super.permissionProfiles() : permissionRead;
        }
    }
}
