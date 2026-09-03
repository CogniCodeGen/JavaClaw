package com.javaclaw.desktop.settings;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.desktop.DesktopTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPresetOnboardingEdgeCasesTest {
    @Test
    void 选择和值状态拒绝未知预设通配符与非法代次() {
        ProviderRef provider = new ProviderRef("provider-main", 1, "chat-model");
        AgentPresetOnboardingSelection selection = AgentPresetOnboardingSelection.empty();

        assertFalse(selection.modelsComplete());
        selection = selection.withProvider(AgentPresetOnboardingPolicy.DEFAULT, provider);
        assertFalse(selection.modelsComplete());
        selection = selection.withProvider(AgentPresetOnboardingPolicy.WORKER, provider);
        assertFalse(selection.modelsComplete());
        selection = selection.withProvider(AgentPresetOnboardingPolicy.EXPLORER, provider);
        assertTrue(selection.modelsComplete());
        assertEquals(
                provider, selection.provider(AgentPresetOnboardingPolicy.WORKER).orElseThrow());
        selection = selection.withTools(AgentPresetOnboardingPolicy.REVIEW, Set.of("read_file"));
        selection = selection.withTools(AgentPresetOnboardingPolicy.DEVELOPER, Set.of("write_file"));
        assertEquals(Set.of("read_file"), selection.tools(AgentPresetOnboardingPolicy.REVIEW));
        assertEquals(Set.of("write_file"), selection.tools(AgentPresetOnboardingPolicy.DEVELOPER));

        AgentPresetOnboardingSelection current = selection;
        assertThrows(IllegalArgumentException.class, () -> current.withProvider("unknown", provider));
        assertThrows(IllegalArgumentException.class, () -> current.withTools("unknown", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> current.provider("unknown"));
        assertThrows(IllegalArgumentException.class, () -> current.tools("unknown"));
        assertThrows(
                IllegalArgumentException.class,
                () -> current.withTools(AgentPresetOnboardingPolicy.REVIEW, Set.of(" ")));
        assertThrows(
                IllegalArgumentException.class,
                () -> current.withTools(AgentPresetOnboardingPolicy.REVIEW, Set.of("core/*")));

        AgentPresetOnboardingState initial = AgentPresetOnboardingState.initial(DesktopTestFixtures.workspace());
        assertFalse(initial.pending());
        assertFalse(initial.completed());
        assertFalse(initial.conflict());
        assertTrue(withPhase(initial, AgentPresetOnboardingPhase.LOADING).pending());
        assertTrue(withPhase(initial, AgentPresetOnboardingPhase.APPLYING).pending());
        assertTrue(withPhase(initial, AgentPresetOnboardingPhase.COMPLETED).completed());
        assertTrue(withPhase(initial, AgentPresetOnboardingPhase.CONFLICT).conflict());
        assertEquals(
                "",
                new AgentPresetOnboardingState(
                                initial.phase(),
                                initial.workspace(),
                                initial.catalog(),
                                initial.selection(),
                                initial.permissions(),
                                null,
                                0)
                        .message());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentPresetOnboardingState(
                        initial.phase(),
                        initial.workspace(),
                        initial.catalog(),
                        initial.selection(),
                        initial.permissions(),
                        "",
                        -1));
    }

    @Test
    void 策略逐项拒绝不完整阶段模型与目录() {
        TestAgentPresetOnboardingGateway gateway = gateway();
        AgentPresetOnboardingCatalog catalog = catalog(gateway);
        AgentPresetOnboardingPolicy policy = new AgentPresetOnboardingPolicy(gateway.workspace);
        AgentPresetOnboardingState initial = AgentPresetOnboardingState.initial(gateway.workspace);

        assertThrows(IllegalStateException.class, () -> policy.requireReadyToApply(initial));
        assertThrows(IllegalStateException.class, () -> policy.requireCatalog(withProfilePresets(catalog, List.of())));
        assertThrows(
                IllegalStateException.class, () -> policy.requireCatalog(withPermissionPresets(catalog, List.of())));

        AgentPresetOnboardingSelection complete = completeSelection(catalog);
        AgentPresetPermissionSetup noPreviews = AgentPresetPermissionSetup.empty();
        AgentPresetOnboardingState selectWithoutConfirmation = state(
                gateway,
                catalog,
                complete,
                new AgentPresetPermissionSetup(
                        noPreviews.reviewPreview(), noPreviews.developerPreview(), List.of(), List.of(), false, true));
        assertThrows(IllegalStateException.class, () -> policy.requireReadyToApply(selectWithoutConfirmation));

        AgentPresetOnboardingState missingModels = state(
                gateway,
                catalog,
                AgentPresetOnboardingSelection.empty(),
                new AgentPresetPermissionSetup(Optional.empty(), Optional.empty(), List.of(), List.of(), true, true));
        assertThrows(IllegalStateException.class, () -> policy.requireReadyToApply(missingModels));

        ProviderRef stale = new ProviderRef("provider-main", 99, "chat-model");
        AgentPresetOnboardingSelection staleModels = AgentPresetOnboardingSelection.empty()
                .withProvider(AgentPresetOnboardingPolicy.DEFAULT, stale)
                .withProvider(AgentPresetOnboardingPolicy.WORKER, stale)
                .withProvider(AgentPresetOnboardingPolicy.EXPLORER, stale);
        assertThrows(
                IllegalStateException.class,
                () -> policy.requireReadyToApply(state(
                        gateway,
                        catalog,
                        staleModels,
                        new AgentPresetPermissionSetup(
                                Optional.empty(), Optional.empty(), List.of(), List.of(), true, true))));
    }

    @Test
    void 策略校验Profile每一项内容和Provider实时状态() {
        TestAgentPresetOnboardingGateway gateway = gateway();
        gateway.addPermissionProfiles();
        AgentPresetOnboardingCatalog catalog = catalog(gateway);
        AgentPresetOnboardingPolicy policy = new AgentPresetOnboardingPolicy(gateway.workspace);
        AgentPresetOnboardingSelection selection = completeSelection(catalog)
                .withTools(AgentPresetOnboardingPolicy.REVIEW, Set.of("read_file"))
                .withTools(AgentPresetOnboardingPolicy.DEVELOPER, Set.of("read_file", "write_file"));
        AgentProfileSpec expected = policy.profileSpec(catalog, selection, AgentPresetOnboardingPolicy.DEFAULT);

        List<AgentProfile> invalidProfiles = List.of(
                profile(expected, ProfileLifecycle.ARCHIVED),
                profile(withDisplayName(expected), ProfileLifecycle.ACTIVE),
                profile(withInstruction(expected), ProfileLifecycle.ACTIVE),
                profile(withPermission(expected), ProfileLifecycle.ACTIVE),
                profile(withTools(expected), ProfileLifecycle.ACTIVE),
                profile(withBudget(expected), ProfileLifecycle.ACTIVE),
                profile(withProvider(expected), ProfileLifecycle.ACTIVE));
        for (AgentProfile invalid : invalidProfiles) {
            assertThrows(
                    AgentPresetOnboardingConflictException.class,
                    () -> policy.requireProfileMatches(catalog, invalid, expected));
        }

        AgentProfile valid = profile(expected, ProfileLifecycle.ACTIVE);
        policy.requireProfileMatches(catalog, valid, expected);
        AgentPresetOnboardingCatalog unavailableVersion = new AgentPresetOnboardingCatalog(
                catalog.profilePresets(),
                catalog.permissionPresets(),
                catalog.providers(),
                List.of(),
                catalog.profiles(),
                catalog.permissions(),
                catalog.workspaceBinding());
        assertThrows(
                AgentPresetOnboardingConflictException.class,
                () -> policy.requireProfileMatches(unavailableVersion, valid, expected));

        gateway.advanceProvider(ProviderLifecycle.DISABLED);
        assertThrows(
                AgentPresetOnboardingConflictException.class,
                () -> policy.requireProfileMatches(catalog(gateway), valid, expected));
    }

    @Test
    void 策略区分已冻结工具与默认绑定的精确版本() {
        TestAgentPresetOnboardingGateway gateway = gateway();
        gateway.addPermissionProfiles();
        AgentPresetOnboardingCatalog catalog = catalog(gateway);
        AgentPresetOnboardingPolicy policy = new AgentPresetOnboardingPolicy(gateway.workspace);
        AgentPresetOnboardingSelection selection = completeSelection(catalog);

        assertFalse(policy.toolsFrozenByExistingProfile(catalog, AgentPresetOnboardingPolicy.REVIEW));
        assertFalse(policy.toolsFrozenByExistingProfile(catalog, AgentPresetOnboardingPolicy.DEVELOPER));
        policy.requireExistingToolsAvailable(catalog, selection, AgentPresetOnboardingPolicy.REVIEW, List.of());
        assertFalse(policy.initializationComplete(catalog));

        AgentProfileSpec explorerSpec = policy.profileSpec(catalog, selection, AgentPresetOnboardingPolicy.EXPLORER);
        AgentProfile explorer =
                profile(explorerSpec, ProfileLifecycle.ACTIVE, policy.ids().explorerProfile());
        AgentPresetOnboardingCatalog withExplorer = withProfiles(catalog, List.of(explorer), Optional.empty());
        assertTrue(policy.toolsFrozenByExistingProfile(withExplorer, AgentPresetOnboardingPolicy.REVIEW));

        AgentPresetOnboardingSelection selectedTool =
                selection.withTools(AgentPresetOnboardingPolicy.REVIEW, Set.of("read_file"));
        assertThrows(
                AgentPresetOnboardingConflictException.class,
                () -> policy.requireExistingToolsAvailable(
                        withExplorer, selectedTool, AgentPresetOnboardingPolicy.REVIEW, List.of()));
    }

    private static AgentPresetOnboardingState withPhase(
            AgentPresetOnboardingState source, AgentPresetOnboardingPhase phase) {
        return new AgentPresetOnboardingState(
                phase,
                source.workspace(),
                source.catalog(),
                source.selection(),
                source.permissions(),
                source.message(),
                source.epoch());
    }

    private static AgentPresetOnboardingState state(
            TestAgentPresetOnboardingGateway gateway,
            AgentPresetOnboardingCatalog catalog,
            AgentPresetOnboardingSelection selection,
            AgentPresetPermissionSetup permissions) {
        return new AgentPresetOnboardingState(
                AgentPresetOnboardingPhase.SELECT_CONFIGURATION,
                gateway.workspace,
                catalog,
                selection,
                permissions,
                "",
                1);
    }

    private static AgentPresetOnboardingSelection completeSelection(AgentPresetOnboardingCatalog catalog) {
        ProviderRef provider = new ProviderRef(
                catalog.providers().getFirst().id(),
                catalog.providers().getFirst().revision(),
                "chat-model");
        return AgentPresetOnboardingSelection.empty()
                .withProvider(AgentPresetOnboardingPolicy.DEFAULT, provider)
                .withProvider(AgentPresetOnboardingPolicy.WORKER, provider)
                .withProvider(AgentPresetOnboardingPolicy.EXPLORER, provider);
    }

    private static AgentProfile profile(AgentProfileSpec spec, ProfileLifecycle lifecycle) {
        return profile(spec, lifecycle, "test-profile");
    }

    private static AgentProfile profile(AgentProfileSpec spec, ProfileLifecycle lifecycle, String id) {
        return new AgentProfile(id, 1, lifecycle, spec, DesktopTestFixtures.NOW, DesktopTestFixtures.NOW);
    }

    private static AgentProfileSpec copy(
            AgentProfileSpec source,
            String displayName,
            String instruction,
            PermissionProfileRef permission,
            Set<String> tools,
            TurnBudget budget,
            ProviderRef provider) {
        return new AgentProfileSpec(displayName, instruction, provider, permission, tools, budget);
    }

    private static AgentProfileSpec withDisplayName(AgentProfileSpec source) {
        return copy(
                source,
                "other",
                source.systemInstruction(),
                source.permissionProfile(),
                source.visibleTools(),
                source.budget(),
                source.provider());
    }

    private static AgentProfileSpec withInstruction(AgentProfileSpec source) {
        return copy(
                source,
                source.displayName(),
                "other prompt",
                source.permissionProfile(),
                source.visibleTools(),
                source.budget(),
                source.provider());
    }

    private static AgentProfileSpec withPermission(AgentProfileSpec source) {
        PermissionProfileRef permission = new PermissionProfileRef(
                source.permissionProfile().id(), source.permissionProfile().version() + 1);
        return copy(
                source,
                source.displayName(),
                source.systemInstruction(),
                permission,
                source.visibleTools(),
                source.budget(),
                source.provider());
    }

    private static AgentProfileSpec withTools(AgentProfileSpec source) {
        return copy(
                source,
                source.displayName(),
                source.systemInstruction(),
                source.permissionProfile(),
                Set.of(),
                source.budget(),
                source.provider());
    }

    private static AgentProfileSpec withBudget(AgentProfileSpec source) {
        TurnBudget budget = new TurnBudget(1_000, 500, 1, 0, Duration.ofSeconds(10));
        return copy(
                source,
                source.displayName(),
                source.systemInstruction(),
                source.permissionProfile(),
                source.visibleTools(),
                budget,
                source.provider());
    }

    private static AgentProfileSpec withProvider(AgentProfileSpec source) {
        ProviderRef provider = new ProviderRef("provider-main", 99, "chat-model");
        return copy(
                source,
                source.displayName(),
                source.systemInstruction(),
                source.permissionProfile(),
                source.visibleTools(),
                source.budget(),
                provider);
    }

    private static AgentPresetOnboardingCatalog catalog(TestAgentPresetOnboardingGateway gateway) {
        return gateway.load(gateway.workspace.id()).toCompletableFuture().join();
    }

    private static AgentPresetOnboardingCatalog withProfilePresets(
            AgentPresetOnboardingCatalog source, List<com.javaclaw.api.AgentProfilePreset> presets) {
        return new AgentPresetOnboardingCatalog(
                presets,
                source.permissionPresets(),
                source.providers(),
                source.availableProviderVersions(),
                source.profiles(),
                source.permissions(),
                source.workspaceBinding());
    }

    private static AgentPresetOnboardingCatalog withPermissionPresets(
            AgentPresetOnboardingCatalog source, List<com.javaclaw.api.PermissionPresetDescriptor> presets) {
        return new AgentPresetOnboardingCatalog(
                source.profilePresets(),
                presets,
                source.providers(),
                source.availableProviderVersions(),
                source.profiles(),
                source.permissions(),
                source.workspaceBinding());
    }

    private static AgentPresetOnboardingCatalog withProfiles(
            AgentPresetOnboardingCatalog source, List<AgentProfile> profiles, Optional<ProfileBinding> binding) {
        return new AgentPresetOnboardingCatalog(
                source.profilePresets(),
                source.permissionPresets(),
                source.providers(),
                source.availableProviderVersions(),
                profiles,
                source.permissions(),
                binding);
    }

    private static TestAgentPresetOnboardingGateway gateway() {
        return new TestAgentPresetOnboardingGateway(DesktopTestFixtures.workspace());
    }
}
