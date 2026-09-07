package com.javaclaw.extension.spi;

import java.util.List;
import java.util.Optional;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.AutomationExecutionSnapshot;
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
}
