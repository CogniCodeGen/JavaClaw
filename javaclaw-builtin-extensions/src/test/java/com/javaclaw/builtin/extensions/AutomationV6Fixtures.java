package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Optional;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ResolvedTurnConfig;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.UnattendedExecutionScope;

/** 自动化测试显式构造独立选择和已冻结配置。 */
final class AutomationV6Fixtures {
    private AutomationV6Fixtures() {}

    static ExecutionOverrides selection(AgentRoleRef role) {
        return new ExecutionOverrides(
                Optional.of(role),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    static AutomationExecutionSnapshot snapshot(
            AgentRoleRef role,
            ProviderRef provider,
            PermissionProfileRef permission,
            TurnBudget budget,
            ToolCatalogSnapshot catalog,
            Optional<UnattendedExecutionScope> scope) {
        ResolvedTurnConfig config = new ResolvedTurnConfig(
                role,
                provider,
                permission,
                ApprovalPolicy.NONE,
                budget,
                catalog.permissionCeiling().tools().allowedTools(),
                Optional.empty(),
                PermissionConstraint.INHERIT,
                Optional.empty(),
                "a".repeat(64),
                catalog.digest(),
                List.of());
        return new AutomationExecutionSnapshot(config, catalog, scope);
    }

    static com.javaclaw.api.ProviderEndpoint provider() {
        var spec = new com.javaclaw.api.ProviderEndpointSpec(
                "Local",
                com.javaclaw.api.ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(java.net.URI.create("http://localhost:8080/v1")),
                com.javaclaw.api.ProviderAuthentication.NONE,
                List.of(new com.javaclaw.api.ProviderModelSpec(
                        "model",
                        "Model",
                        java.util.Set.of(com.javaclaw.api.ProviderModelPurpose.CHAT),
                        java.util.OptionalInt.empty())),
                Optional.empty(),
                java.time.Duration.ofSeconds(30),
                0,
                com.javaclaw.api.ProviderAdapterOptions.defaults(com.javaclaw.api.ProviderAdapter.OPENAI_COMPATIBLE));
        return new com.javaclaw.api.ProviderEndpoint(
                "provider",
                1,
                com.javaclaw.api.ProviderLifecycle.ACTIVE,
                spec,
                BuiltinExtensionTestSupport.NOW,
                BuiltinExtensionTestSupport.NOW);
    }
}
