package com.javaclaw.desktop.settings;

import java.util.function.Function;

import javafx.util.StringConverter;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.InstructionScope;
import com.javaclaw.api.ManagedWorktreeArtifactKind;
import com.javaclaw.api.ManagedWorktreeState;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpCatalogKind;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpHealthState;
import com.javaclaw.api.McpOAuthState;
import com.javaclaw.api.McpSamplingRole;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.PermissionLayerKind;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.PromptOptimizationState;
import com.javaclaw.api.PromptSourceKind;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderReadiness;
import com.javaclaw.api.ProviderVerificationState;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.VaultLockReason;
import com.javaclaw.api.VaultManagementAction;
import com.javaclaw.api.VaultState;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionJobUnitState;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.protocol.BundleRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettingsLabelsTest {
    @Test
    void 下拉框和状态展示覆盖全部枚举且不泄漏机器值() {
        assertReadable(ProviderAdapter.values(), SettingsLabels::providerAdapter);
        assertReadable(ProviderLifecycle.values(), SettingsLabels::providerLifecycle);
        assertReadable(ProviderReadiness.values(), SettingsLabels::providerReadiness);
        assertReadable(ProviderVerificationState.values(), SettingsLabels::providerVerificationState);
        assertReadable(RoleLifecycle.values(), SettingsLabels::roleLifecycle);
        assertReadable(PrivateNetworkPurpose.values(), SettingsLabels::privateNetworkPurpose);
        assertReadable(ToolRisk.values(), SettingsLabels::toolRisk);
        assertReadable(ApprovalRequirement.values(), SettingsLabels::approvalRequirement);
        assertReadable(McpAuthType.values(), SettingsLabels::mcpAuthType);
        assertReadable(McpEndpointState.values(), SettingsLabels::mcpEndpointState);
        assertReadable(McpTransport.values(), SettingsLabels::mcpTransport);
        assertReadable(McpHealthState.values(), SettingsLabels::mcpHealthState);
        assertReadable(McpCatalogKind.values(), SettingsLabels::mcpCatalogKind);
        assertReadable(McpOAuthState.values(), SettingsLabels::mcpOAuthState);
        assertReadable(McpSamplingRole.values(), SettingsLabels::mcpSamplingRole);
        assertReadable(PromptSourceKind.values(), SettingsLabels::promptSourceKind);
        assertReadable(PromptOptimizationState.values(), SettingsLabels::promptOptimizationState);
        assertReadable(PermissionLayerKind.values(), SettingsLabels::permissionLayer);
        assertReadable(SecurityGrantState.values(), SettingsLabels::securityGrantState);
        assertReadable(VaultState.values(), SettingsLabels::vaultState);
        assertReadable(VaultLockReason.values(), SettingsLabels::vaultLockReason);
        assertReadable(VaultManagementAction.values(), SettingsLabels::vaultManagementAction);
        assertReadable(ExtensionState.values(), SettingsLabels::extensionState);
        assertReadable(ContributionKind.values(), SettingsLabels::contributionKind);
        assertReadable(BundleRpcContracts.HealthState.values(), SettingsLabels::bundleHealth);
        assertReadable(BundleRpcContracts.TrustState.values(), SettingsLabels::trustState);
        assertReadable(BundleRpcContracts.TrashState.values(), SettingsLabels::trashState);
        assertReadable(WorkspaceLifecycle.values(), SettingsLabels::workspaceLifecycle);
        assertReadable(ManagedWorktreeState.values(), SettingsLabels::managedWorktreeState);
        assertReadable(ManagedWorktreeArtifactKind.values(), SettingsLabels::managedWorktreeArtifactKind);
        assertReadable(InstructionScope.values(), SettingsLabels::instructionScope);
        assertReadable(ExecutionState.values(), SettingsLabels::executionState);
        assertReadable(ExtensionJobUnitState.values(), SettingsLabels::extensionJobUnitState);
    }

    @Test
    void 下拉框转换器只负责展示且不接受自由文本反向写入() {
        StringConverter<ProviderAdapter> converter = SettingsLabels.converter(SettingsLabels::providerAdapter);

        assertEquals("OpenAI 兼容接口", converter.toString(ProviderAdapter.OPENAI_COMPATIBLE));
        assertEquals("", converter.toString(null));
        assertThrows(UnsupportedOperationException.class, () -> converter.fromString("任意文本"));
    }

    @Test
    void 字符串状态保留未知扩展值并翻译平台已知值() {
        assertEquals("已启用", SettingsLabels.extensionState("ENABLED"));
        assertEquals("FUTURE_STATE", SettingsLabels.extensionState("FUTURE_STATE"));
        assertEquals("工作流", SettingsLabels.automationJobType("workflow"));
        assertEquals("future-job", SettingsLabels.automationJobType("future-job"));
        assertEquals("是", SettingsLabels.yesNo(true));
        assertEquals("否", SettingsLabels.yesNo(false));
        assertEquals("受限对话", SettingsLabels.permissionProfile("standard"));
        assertEquals("workspace-safe", SettingsLabels.permissionProfile("workspace-safe"));
    }

    private static <T extends Enum<T>> void assertReadable(T[] values, Function<T, String> labeler) {
        for (T value : values) {
            String label = labeler.apply(value);
            assertFalse(label.isBlank(), () -> value.name() + " 必须有用户可见标签");
            assertNotEquals(value.name(), label, () -> value.name() + " 不得直接显示机器枚举值");
        }
    }
}
