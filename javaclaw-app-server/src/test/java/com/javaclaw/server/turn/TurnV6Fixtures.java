package com.javaclaw.server.turn;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ResolvedTurnConfigSummary;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.UnattendedExecutionScope;
import com.javaclaw.server.TurnContractFixtures;

/** Turn 测试使用同一配置来源构造状态与摘要，避免测试夹具中的重复字段漂移。 */
final class TurnV6Fixtures {
    private TurnV6Fixtures() {}

    static ExecutionOverrides selection(AgentRoleRef role) {
        return TurnContractFixtures.select(role);
    }

    static AutomationExecutionSnapshot snapshot(
            AgentRoleRef role,
            ProviderRef provider,
            PermissionProfileRef permission,
            TurnBudget budget,
            ToolCatalogSnapshot catalog,
            Optional<UnattendedExecutionScope> scope) {
        return new AutomationExecutionSnapshot(
                TurnContractFixtures.configuration(
                        new TurnContractFixtures.Selection(budget, role, provider, permission),
                        TurnContractFixtures.PROMPT_SNAPSHOT,
                        catalog),
                catalog,
                scope);
    }

    static void defaults(
            com.javaclaw.server.persistence.ExecutionConfigurationService configurations,
            AgentRoleRef role,
            ProviderRef provider,
            PermissionProfileRef permission,
            TurnBudget budget,
            Set<String> capabilities) {
        ExecutionOverrides defaults = new ExecutionOverrides(
                Optional.of(role),
                Optional.of(provider),
                Optional.of(permission),
                Optional.of(ApprovalPolicy.NONE),
                Optional.of(budget),
                Optional.of(capabilities),
                Optional.empty());
        var json = new com.javaclaw.protocol.CanonicalJson();
        configurations.update(
                new com.javaclaw.server.persistence.CommandIdentity(
                        "execution/default/update",
                        "test-defaults",
                        0,
                        json.encode(defaults).sha256()),
                Optional.empty(),
                Optional.empty(),
                defaults);
    }

    static ResolvedTurnConfigSummary summary(
            TurnBudget budget,
            AgentRoleRef role,
            ProviderRef provider,
            PermissionProfileRef permission,
            String promptDigest,
            String catalogDigest) {
        return new ResolvedTurnConfigSummary(
                role,
                provider,
                permission,
                ApprovalPolicy.NONE,
                budget,
                Set.of(),
                Optional.empty(),
                promptDigest,
                catalogDigest,
                false,
                List.of());
    }
}
