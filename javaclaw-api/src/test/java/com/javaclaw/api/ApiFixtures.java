package com.javaclaw.api;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class ApiFixtures {
    static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    static final String DIGEST = "a".repeat(64);

    private ApiFixtures() {}

    static CanonicalPayload payload() {
        return new CanonicalPayload("{\"value\":1}");
    }

    static TurnBudget budget() {
        return new TurnBudget(1_000, 500, 10, 2, Duration.ofMinutes(2));
    }

    static PermissionProfile profile(String id, long version) {
        return new PermissionProfile(
                id,
                version,
                new FilePermission(List.of(Path.of("/workspace")), List.of(Path.of("/workspace")), true, true),
                new NetworkPermission(Set.of(NetworkPermission.ANY_HOST), Set.of(443, 8443), false),
                new ProcessPermission(Set.of("java", "git"), true, Duration.ofMinutes(2)),
                new ToolPermission(Set.of("read", "write"), ToolRisk.EXTERNAL_EFFECT, ApprovalRequirement.NONE),
                new ResourceLimits(1_024, 512, 4, 16));
    }

    static ResolvedTurnConfig config(String catalogDigest) {
        return new ResolvedTurnConfig(
                new AgentRoleRef("default", 1),
                new ProviderRef("provider", 1, "model"),
                new PermissionProfileRef("standard", 1),
                ApprovalPolicy.RISKY,
                budget(),
                Set.of("read", "write"),
                Optional.empty(),
                PermissionConstraint.INHERIT,
                Optional.empty(),
                DIGEST,
                catalogDigest,
                List.of());
    }

    static ToolDescriptor tool(String name, String description, Set<String> tags) {
        return new ToolDescriptor(
                new ToolIdentity("core", name, 1), description, payload(), payload(), ToolRisk.READ_ONLY, tags);
    }
}
