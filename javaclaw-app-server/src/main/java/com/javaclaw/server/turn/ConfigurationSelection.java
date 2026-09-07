package com.javaclaw.server.turn;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.ConfigurationProvenance;
import com.javaclaw.api.ConfigurationSource;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.TurnBudget;

/** 单次解析的局部累加器；引用按优先级覆盖，能力、审批和预算只能收窄。 */
final class ConfigurationSelection {
    AgentRoleRef role = new AgentRoleRef("default", 1);
    ProviderRef provider;
    PermissionProfileRef permission = new PermissionProfileRef("standard", 1);
    ApprovalPolicy approval = ApprovalPolicy.NONE;
    TurnBudget budget = new TurnBudget(65_536, 16_384, 256, 4, Duration.ofMinutes(30));
    Optional<Set<String>> capabilities = Optional.empty();
    Optional<ReasoningPreference> reasoning = Optional.empty();
    final List<ConfigurationProvenance> provenance = new ArrayList<>();

    ConfigurationSelection() {
        for (String field : List.of("role", "permissionProfile", "approvalPolicy", "budget", "capabilities")) {
            provenance.add(new ConfigurationProvenance(field, ConfigurationSource.SYSTEM, "platform-defaults", 1));
        }
    }

    void apply(ExecutionOverrides overrides, ConfigurationSource source, String id, long revision) {
        overrides.role().ifPresent(value -> role = value);
        overrides.provider().ifPresent(value -> provider = value);
        overrides.permissionProfile().ifPresent(value -> permission = value);
        overrides.reasoning().ifPresent(value -> reasoning = Optional.of(value));
        overrides.approvalPolicy().ifPresent(value -> approval = stricter(approval, value));
        overrides.budget().ifPresent(value -> budget = narrowBudget(budget, value));
        overrides.visibleCapabilities().ifPresent(value -> capabilities = narrow(capabilities, Optional.of(value)));
        record("role", overrides.role(), source, id, revision);
        record("provider", overrides.provider(), source, id, revision);
        record("permissionProfile", overrides.permissionProfile(), source, id, revision);
        record("reasoning", overrides.reasoning(), source, id, revision);
        record("approvalPolicy", overrides.approvalPolicy(), source, id, revision);
        record("budget", overrides.budget(), source, id, revision);
        record("capabilities", overrides.visibleCapabilities(), source, id, revision);
    }

    private void record(String field, Optional<?> value, ConfigurationSource source, String id, long revision) {
        if (value.isPresent()) {
            provenance.add(new ConfigurationProvenance(field, source, id, revision));
        }
    }

    static ApprovalPolicy stricter(ApprovalPolicy left, ApprovalPolicy right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    static TurnBudget narrowBudget(TurnBudget left, TurnBudget right) {
        return new TurnBudget(
                Math.min(left.inputTokens(), right.inputTokens()),
                Math.min(left.outputTokens(), right.outputTokens()),
                Math.min(left.toolCalls(), right.toolCalls()),
                Math.min(left.childThreads(), right.childThreads()),
                left.wallTime().compareTo(right.wallTime()) <= 0 ? left.wallTime() : right.wallTime());
    }

    static Optional<Set<String>> narrow(Optional<Set<String>> left, Optional<Set<String>> right) {
        if (left.isEmpty()) {
            return right.map(Set::copyOf);
        }
        if (right.isEmpty()) {
            return left.map(Set::copyOf);
        }
        Set<String> result = new HashSet<>(left.orElseThrow());
        result.retainAll(right.orElseThrow());
        return Optional.of(Set.copyOf(result));
    }
}
