package com.javaclaw.server.turn;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.ConfigurationProvenance;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.ResolvedTurnConfig;
import com.javaclaw.api.TurnBudget;

/** 仅在 App Server 单次解析中存在；Prompt 和目录冻结后生成唯一持久快照。 */
record ResolvedAgentConfiguration(
        AgentRole role,
        ProviderRef provider,
        PermissionProfileRef permissionProfile,
        ApprovalPolicy approvalPolicy,
        TurnBudget budget,
        PermissionProfile effectivePermissions,
        PermissionConstraint permissionConstraint,
        Optional<Set<String>> effectiveSkills,
        Optional<ReasoningPreference> reasoning,
        List<ConfigurationProvenance> provenance) {
    ResolvedAgentConfiguration withCatalog(com.javaclaw.api.ToolCatalogSnapshot catalog) {
        Set<String> discovered = catalog.tools().stream()
                .map(tool -> tool.identity().name())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        PermissionProfile bounded = AgentConfigurationResolver.constrain(
                effectivePermissions, Optional.of(discovered), approvalPolicy, permissionConstraint);
        return new ResolvedAgentConfiguration(
                role,
                provider,
                permissionProfile,
                approvalPolicy,
                budget,
                bounded,
                permissionConstraint,
                effectiveSkills,
                reasoning,
                provenance);
    }

    ResolvedTurnConfig freeze(String promptDigest, String catalogDigest) {
        return new ResolvedTurnConfig(
                new AgentRoleRef(role.id(), role.revision()),
                provider,
                permissionProfile,
                approvalPolicy,
                budget,
                effectivePermissions.tools().allowedTools(),
                reasoning,
                permissionConstraint,
                effectiveSkills,
                promptDigest,
                catalogDigest,
                provenance);
    }
}
