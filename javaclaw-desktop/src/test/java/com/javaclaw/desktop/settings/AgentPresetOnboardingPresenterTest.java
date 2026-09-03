package com.javaclaw.desktop.settings;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.desktop.DesktopTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPresetOnboardingPresenterTest {
    @Test
    void 显式确认后创建精确权限三个普通智能体并只绑定default() {
        TestAgentPresetOnboardingGateway gateway = gateway();
        AgentPresetOnboardingPresenter presenter = new AgentPresetOnboardingPresenter(gateway.workspace, gateway);

        presenter.reload();
        assertEquals(
                AgentPresetOnboardingPhase.REVIEW_PERMISSIONS, presenter.state().phase());
        assertEquals(1, presenter.chatProviders().size(), "Embedding-only 模型不能出现在智能体候选中");
        selectAllModels(presenter);

        presenter.confirmPermissions(false);
        assertEquals(0, gateway.permissionInstantiations);
        presenter.confirmPermissions(true);
        assertEquals(
                AgentPresetOnboardingPhase.SELECT_CONFIGURATION,
                presenter.state().phase());
        assertEquals(2, gateway.permissionInstantiations);

        presenter.selectTools(AgentPresetOnboardingPolicy.REVIEW, Set.of("*"));
        assertTrue(presenter.state().selection().reviewTools().isEmpty(), "通配符不能进入权限或 Profile");
        presenter.selectTools(AgentPresetOnboardingPolicy.REVIEW, Set.of("read_file"));
        presenter.selectTools(AgentPresetOnboardingPolicy.DEVELOPER, Set.of("read_file", "write_file"));
        presenter.apply();

        assertTrue(presenter.state().completed());
        assertEquals(3, gateway.profiles.size());
        AgentPresetOnboardingIds ids = AgentPresetOnboardingIds.forWorkspace(gateway.workspace.id());
        AgentProfile defaultProfile = profile(gateway, ids.defaultProfile());
        AgentProfile worker = profile(gateway, ids.workerProfile());
        AgentProfile explorer = profile(gateway, ids.explorerProfile());
        assertEquals(
                ids.developerPermission(),
                defaultProfile.spec().permissionProfile().id());
        assertEquals(
                ids.developerPermission(), worker.spec().permissionProfile().id());
        assertEquals(ids.reviewPermission(), explorer.spec().permissionProfile().id());
        assertEquals(
                new TurnBudget(32_000, 4_000, 32, 4, Duration.ofMinutes(15)),
                defaultProfile.spec().budget());
        assertEquals(
                new TurnBudget(32_000, 4_000, 24, 0, Duration.ofMinutes(10)),
                worker.spec().budget());
        assertEquals(
                new TurnBudget(32_000, 4_000, 24, 0, Duration.ofMinutes(5)),
                explorer.spec().budget());
        assertEquals(
                new AgentProfileRef(defaultProfile.id(), defaultProfile.revision()),
                gateway.binding.orElseThrow().profile());
        String onboardingPrefix = "onboarding." + gateway.workspace.id() + ".";
        assertTrue(gateway.writes.stream()
                .allMatch(options -> options.idempotencyKey().startsWith(onboardingPrefix)));
        assertTrue(gateway.permissions.stream()
                .flatMap(permission -> permission.tools().allowedTools().stream())
                .noneMatch("*"::equals));
        assertExplorerReadOnly(gateway, explorer);
    }

    @Test
    void 中断后只补缺失步骤且同一逻辑命令复用确定性幂等键() {
        TestAgentPresetOnboardingGateway gateway = gateway();
        AgentPresetOnboardingIds ids = AgentPresetOnboardingIds.forWorkspace(gateway.workspace.id());
        AgentPresetOnboardingPresenter presenter = readyPresenter(gateway);
        gateway.failProfileOnce = ids.workerProfile();

        presenter.apply();
        assertEquals(AgentPresetOnboardingPhase.ERROR, presenter.state().phase());
        assertEquals(1, gateway.profileCreations);
        assertEquals(ids.defaultProfile(), gateway.profiles.getFirst().id());
        String failedKey =
                gateway.profileWrites.get(ids.workerProfile()).getFirst().idempotencyKey();

        presenter.reload();
        assertEquals(
                AgentPresetOnboardingPhase.SELECT_CONFIGURATION,
                presenter.state().phase());
        presenter.apply();

        assertTrue(presenter.state().completed());
        assertEquals(3, gateway.profileCreations, "已经成功的 default 不应重建");
        assertEquals(2, gateway.permissionInstantiations, "已经持久化的权限不应再次实例化");
        assertEquals(
                failedKey,
                gateway.profileWrites.get(ids.workerProfile()).getLast().idempotencyKey(),
                "同一内容的重试必须复用确定性幂等键");
    }

    @Test
    void 确定性Profile标识被不同提示词占用时停止且不覆盖() {
        TestAgentPresetOnboardingGateway gateway = gateway();
        gateway.addPermissionProfiles();
        AgentPresetOnboardingIds ids = AgentPresetOnboardingIds.forWorkspace(gateway.workspace.id());
        PermissionProfile developer = permission(gateway, ids.developerPermission());
        AgentProfileSpec conflicting = new AgentProfileSpec(
                "默认智能体",
                "不同的提示词",
                chatProvider(gateway),
                new PermissionProfileRef(developer.id(), developer.version()),
                developer.tools().allowedTools(),
                new TurnBudget(32_000, 4_000, 32, 4, Duration.ofMinutes(15)));
        gateway.profiles.add(new AgentProfile(
                ids.defaultProfile(),
                1,
                ProfileLifecycle.ACTIVE,
                conflicting,
                DesktopTestFixtures.NOW,
                DesktopTestFixtures.NOW));
        AgentPresetOnboardingPresenter presenter = new AgentPresetOnboardingPresenter(gateway.workspace, gateway);

        presenter.reload();

        assertTrue(presenter.state().conflict());
        assertTrue(presenter.state().message().contains("不同内容"));
        assertEquals(0, gateway.profileCreations);
        assertFalse(gateway.binding.isPresent());
    }

    @Test
    void 已有Profile引用旧Provider版本时不能误判初始化完成() {
        TestAgentPresetOnboardingGateway gateway = gateway();
        gateway.addPermissionProfiles();
        AgentPresetOnboardingIds ids = AgentPresetOnboardingIds.forWorkspace(gateway.workspace.id());
        addPresetProfiles(gateway, new ProviderRef(gateway.provider.id(), 2, "chat-model"));
        AgentProfile defaultProfile = profile(gateway, ids.defaultProfile());
        gateway.binding = Optional.of(new com.javaclaw.api.ProfileBinding(
                gateway.workspace.id(),
                Optional.empty(),
                new AgentProfileRef(defaultProfile.id(), defaultProfile.revision()),
                1,
                DesktopTestFixtures.NOW));
        AgentPresetOnboardingPresenter presenter = new AgentPresetOnboardingPresenter(gateway.workspace, gateway);

        presenter.reload();

        assertEquals(AgentPresetOnboardingPhase.CONFLICT, presenter.state().phase());
        assertFalse(presenter.state().completed());
    }

    @Test
    void 合法历史精确Provider版本在最新版本启用时仍可完成初始化() {
        TestAgentPresetOnboardingGateway gateway = gateway();
        gateway.addPermissionProfiles();
        AgentPresetOnboardingIds ids = AgentPresetOnboardingIds.forWorkspace(gateway.workspace.id());
        addPresetProfiles(gateway, chatProvider(gateway));
        bindDefault(gateway, ids.defaultProfile());
        gateway.advanceProvider(com.javaclaw.api.ProviderLifecycle.ACTIVE);
        AgentPresetOnboardingPresenter presenter = new AgentPresetOnboardingPresenter(gateway.workspace, gateway);

        presenter.reload();

        assertTrue(presenter.state().completed());
        assertEquals(
                1,
                presenter
                        .state()
                        .selection()
                        .provider(AgentPresetOnboardingPolicy.DEFAULT)
                        .orElseThrow()
                        .endpointRevision());
    }

    @Test
    void Provider最新版本停用或归档会立即阻断历史精确引用() {
        for (com.javaclaw.api.ProviderLifecycle lifecycle :
                Set.of(com.javaclaw.api.ProviderLifecycle.DISABLED, com.javaclaw.api.ProviderLifecycle.ARCHIVED)) {
            TestAgentPresetOnboardingGateway gateway = gateway();
            gateway.addPermissionProfiles();
            AgentPresetOnboardingIds ids = AgentPresetOnboardingIds.forWorkspace(gateway.workspace.id());
            addPresetProfiles(gateway, chatProvider(gateway));
            bindDefault(gateway, ids.defaultProfile());
            gateway.advanceProvider(lifecycle);
            AgentPresetOnboardingPresenter presenter = new AgentPresetOnboardingPresenter(gateway.workspace, gateway);

            presenter.reload();

            assertEquals(AgentPresetOnboardingPhase.CONFLICT, presenter.state().phase());
            assertFalse(presenter.state().completed());
        }
    }

    @Test
    void 已完成初始化再次读取只做校验不产生写入() {
        TestAgentPresetOnboardingGateway gateway = gateway();
        AgentPresetOnboardingPresenter first = readyPresenter(gateway);
        first.apply();
        int writes = gateway.writes.size();

        AgentPresetOnboardingPresenter resumed = new AgentPresetOnboardingPresenter(gateway.workspace, gateway);
        resumed.reload();

        assertTrue(resumed.state().completed());
        assertEquals(writes, gateway.writes.size());
    }

    @Test
    void Workspace不同则资源标识与幂等键均不同() {
        var firstWorkspace = DesktopTestFixtures.workspace();
        var secondWorkspace = new com.javaclaw.api.Workspace(
                com.javaclaw.api.WorkspaceId.parse("2a3d76fb-fca6-47dd-bfa2-c6217cd40dc8"),
                "另一个工作区",
                java.nio.file.Path.of("/tmp/javaclaw-desktop-other"),
                com.javaclaw.api.WorkspaceLifecycle.ACTIVE,
                1,
                DesktopTestFixtures.NOW,
                DesktopTestFixtures.NOW);
        AgentPresetOnboardingIds first = AgentPresetOnboardingIds.forWorkspace(firstWorkspace.id());
        AgentPresetOnboardingIds second = AgentPresetOnboardingIds.forWorkspace(secondWorkspace.id());

        assertNotEquals(first.defaultProfile(), second.defaultProfile());
        assertNotEquals(
                AgentPresetOnboardingCommands.instantiatePermission(firstWorkspace.id(), "workspace-review"),
                AgentPresetOnboardingCommands.instantiatePermission(secondWorkspace.id(), "workspace-review"));
        assertEquals(
                AgentPresetOnboardingCommands.instantiatePermission(firstWorkspace.id(), "workspace-review"),
                AgentPresetOnboardingCommands.instantiatePermission(firstWorkspace.id(), "workspace-review"));
    }

    private static AgentPresetOnboardingPresenter readyPresenter(TestAgentPresetOnboardingGateway gateway) {
        AgentPresetOnboardingPresenter presenter = new AgentPresetOnboardingPresenter(gateway.workspace, gateway);
        presenter.reload();
        selectAllModels(presenter);
        presenter.confirmPermissions(true);
        presenter.selectTools(AgentPresetOnboardingPolicy.REVIEW, Set.of("read_file"));
        presenter.selectTools(AgentPresetOnboardingPolicy.DEVELOPER, Set.of("read_file", "write_file"));
        return presenter;
    }

    private static void selectAllModels(AgentPresetOnboardingPresenter presenter) {
        ProviderRef provider = presenter.chatProviders().getFirst();
        presenter.selectProvider(AgentPresetOnboardingPolicy.DEFAULT, provider);
        presenter.selectProvider(AgentPresetOnboardingPolicy.WORKER, provider);
        presenter.selectProvider(AgentPresetOnboardingPolicy.EXPLORER, provider);
    }

    private static void addPresetProfiles(TestAgentPresetOnboardingGateway gateway, ProviderRef provider) {
        AgentPresetOnboardingIds ids = AgentPresetOnboardingIds.forWorkspace(gateway.workspace.id());
        PermissionProfile review = permission(gateway, ids.reviewPermission());
        PermissionProfile developer = permission(gateway, ids.developerPermission());
        gateway.profiles.add(profile(
                ids.defaultProfile(),
                "默认智能体",
                "default prompt",
                provider,
                developer,
                new TurnBudget(32_000, 4_000, 32, 4, Duration.ofMinutes(15))));
        gateway.profiles.add(profile(
                ids.workerProfile(),
                "Worker",
                "worker prompt",
                provider,
                developer,
                new TurnBudget(32_000, 4_000, 24, 0, Duration.ofMinutes(10))));
        gateway.profiles.add(profile(
                ids.explorerProfile(),
                "Explorer",
                "explorer prompt",
                provider,
                review,
                new TurnBudget(32_000, 4_000, 24, 0, Duration.ofMinutes(5))));
    }

    private static AgentProfile profile(
            String id,
            String displayName,
            String prompt,
            ProviderRef provider,
            PermissionProfile permission,
            TurnBudget budget) {
        return new AgentProfile(
                id,
                1,
                ProfileLifecycle.ACTIVE,
                new AgentProfileSpec(
                        displayName,
                        prompt,
                        provider,
                        new PermissionProfileRef(permission.id(), permission.version()),
                        permission.tools().allowedTools(),
                        budget),
                DesktopTestFixtures.NOW,
                DesktopTestFixtures.NOW);
    }

    private static AgentProfile profile(TestAgentPresetOnboardingGateway gateway, String id) {
        return gateway.profiles.stream()
                .filter(profile -> profile.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static void bindDefault(TestAgentPresetOnboardingGateway gateway, String profileId) {
        AgentProfile defaultProfile = profile(gateway, profileId);
        gateway.binding = Optional.of(new com.javaclaw.api.ProfileBinding(
                gateway.workspace.id(),
                Optional.empty(),
                new AgentProfileRef(defaultProfile.id(), defaultProfile.revision()),
                1,
                DesktopTestFixtures.NOW));
    }

    private static PermissionProfile permission(TestAgentPresetOnboardingGateway gateway, String id) {
        return gateway.permissions.stream()
                .filter(permission -> permission.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static ProviderRef chatProvider(TestAgentPresetOnboardingGateway gateway) {
        return new ProviderRef(gateway.provider.id(), gateway.provider.revision(), "chat-model");
    }

    private static void assertExplorerReadOnly(TestAgentPresetOnboardingGateway gateway, AgentProfile explorer) {
        PermissionProfile permission =
                permission(gateway, explorer.spec().permissionProfile().id());
        assertTrue(permission.files().writeRoots().isEmpty());
        assertFalse(permission.files().allowDelete());
        assertTrue(permission.network().hosts().isEmpty());
        assertTrue(permission.processes().executables().isEmpty());
        assertFalse(permission.processes().allowPty());
        assertEquals(com.javaclaw.api.ToolRisk.READ_ONLY, permission.tools().maximumRisk());
        assertEquals(Set.of("read_file"), explorer.spec().visibleTools());
    }

    private static TestAgentPresetOnboardingGateway gateway() {
        return new TestAgentPresetOnboardingGateway(DesktopTestFixtures.workspace());
    }
}
