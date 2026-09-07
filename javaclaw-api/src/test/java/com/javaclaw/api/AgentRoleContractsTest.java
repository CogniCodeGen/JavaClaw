package com.javaclaw.api;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRoleContractsTest {
    @Test
    void absentNarrowingInheritsWhileAnEmptySetDisablesAllCapabilities() {
        Set<String> mutable = new HashSet<>(Set.of("read"));
        CapabilityNarrowing narrowed = new CapabilityNarrowing(Optional.of(mutable), Optional.of(Set.of()));
        mutable.add("write");
        assertEquals(Set.of("read"), narrowed.capabilities().orElseThrow());
        assertTrue(narrowed.skills().orElseThrow().isEmpty());
        assertTrue(CapabilityNarrowing.inherit().capabilities().isEmpty());
        assertThrows(NullPointerException.class, () -> new CapabilityNarrowing(null, Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CapabilityNarrowing(Optional.of(Set.of(" ")), Optional.empty()));
    }

    @Test
    void roleOwnsItsVersionAndExtensionDataWithoutExecutionGrants() {
        Map<String, String> extensions = new HashMap<>(Map.of("vendor", "label = \"value\""));
        AgentRoleSpec spec = spec(extensions);
        extensions.put("other", "ignored = true");
        AgentRole role = new AgentRole(
                "custom", 2, RoleLifecycle.ACTIVE, spec, false, Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
        assertEquals(new AgentRoleRef("custom", 2), role.ref());
        assertEquals(Set.of("vendor"), role.spec().extensions().keySet());
        assertFalse(role.builtin());
        assertEquals("Custom", role.spec().name());
        assertThrows(IllegalArgumentException.class, () -> new AgentRoleRef("bad/id", 1));
        assertThrows(IllegalArgumentException.class, () -> new AgentRoleRef("custom", 0));
        assertThrows(IllegalArgumentException.class, () -> spec(Map.of("bad/id", "a = 1")));
        assertThrows(NullPointerException.class, () -> new ModelPreference(null));
    }

    @Test
    void defaultsAndOverridesPreserveExplicitEmptyCapabilitiesAndReasoning() {
        ExecutionDefaults defaults = new ExecutionDefaults(
                new AgentRoleRef("default", 1),
                new ProviderRef("provider", 1, "model"),
                new PermissionProfileRef("standard", 1),
                ApprovalPolicy.EVERY_CALL,
                ApiFixtures.budget(),
                Set.of(),
                Optional.of(ReasoningPreference.HIGH));
        ExecutionOverrides overrides = defaults.overrides();
        assertEquals(defaults.role(), overrides.role().orElseThrow());
        assertEquals(defaults.provider(), overrides.provider().orElseThrow());
        assertEquals(defaults.permissionProfile(), overrides.permissionProfile().orElseThrow());
        assertTrue(overrides.visibleCapabilities().orElseThrow().isEmpty());
        assertEquals(Optional.of(ReasoningPreference.HIGH), overrides.reasoning());
        assertTrue(ExecutionOverrides.empty().visibleCapabilities().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutionConfiguration(
                        Optional.empty(), Optional.of(ThreadId.random()), overrides, 1, Instant.EPOCH));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutionConfiguration(Optional.empty(), Optional.empty(), overrides, 0, Instant.EPOCH));
    }

    @Test
    void noToolTurnHasAZeroToolBudgetWithoutWeakeningTokenOrTimeLimits() {
        assertEquals(0, new TurnBudget(1, 1, 0, 0, Duration.ofSeconds(1)).toolCalls());
        assertThrows(IllegalArgumentException.class, () -> new TurnBudget(1, 1, -1, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new TurnBudget(0, 1, 0, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new TurnBudget(1, 1, 0, 0, Duration.ZERO));
    }

    @Test
    void approvalIntersectionNeverReducesEitherBoundary() {
        for (ApprovalPolicy first : ApprovalPolicy.values()) {
            for (ApprovalPolicy second : ApprovalPolicy.values()) {
                ApprovalPolicy effective = first.intersect(second);
                assertTrue(effective.ordinal() >= first.ordinal());
                assertTrue(effective.ordinal() >= second.ordinal());
                assertEquals(ApprovalRequirement.valueOf(effective.name()), effective.requirement());
            }
        }
        assertThrows(NullPointerException.class, () -> ApprovalPolicy.RISKY.intersect(null));
    }

    @Test
    void resolvedSummaryDerivesModelLockFromExplicitRoleProvenance() {
        ResolvedTurnConfig ordinary = ApiFixtures.config(ApiFixtures.DIGEST);
        ConfigurationProvenance source = new ConfigurationProvenance("provider", ConfigurationSource.ROLE, "worker", 2);
        ResolvedTurnConfig locked = new ResolvedTurnConfig(
                ordinary.role(),
                ordinary.provider(),
                ordinary.permissionProfile(),
                ordinary.approvalPolicy(),
                ordinary.budget(),
                ordinary.effectiveCapabilities(),
                ordinary.reasoning(),
                PermissionConstraint.READ_ONLY,
                Optional.of(Set.of("approved-skill")),
                ordinary.promptManifestDigest(),
                ordinary.toolCatalogDigest(),
                List.of(source));
        assertTrue(locked.summary().modelLocked());
        assertFalse(ordinary.summary().modelLocked());
        assertEquals(locked.role(), locked.summary().role());
        assertEquals(List.of(source), locked.summary().provenance());
        assertEquals(Optional.of(Set.of("approved-skill")), locked.effectiveSkills());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConfigurationProvenance("provider", ConfigurationSource.ROLE, "worker", -1));
    }

    @Test
    void fileContractsRejectUnsafeBasenamesAndRequireVerifiedDigests() {
        AgentRoleFileExport file = new AgentRoleFileExport(
                "custom.agent.toml", "name = \"Custom\"\n", ApiFixtures.DIGEST, AgentRoleFileFormat.CODEX_PORTABLE);
        AgentRoleFilePreview preview = new AgentRoleFilePreview(
                "preview",
                "custom",
                spec(Map.of()),
                ApiFixtures.DIGEST,
                Optional.of("model"),
                List.of("create"),
                AgentRoleFileFormat.CODEX_PORTABLE);
        assertEquals("custom.agent.toml", file.filename());
        assertEquals(Optional.of("model"), preview.unresolvedModel());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRoleFileExport(
                        "../custom.agent.toml", "", ApiFixtures.DIGEST, AgentRoleFileFormat.CODEX_PORTABLE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRoleFilePreview(
                        "preview",
                        "custom",
                        spec(Map.of()),
                        "invalid",
                        Optional.empty(),
                        List.of(),
                        AgentRoleFileFormat.JAVACLAW_LOSSLESS));
    }

    @Test
    void turnRejectsAClientSummaryThatDiffersFromTheFrozenExecution() {
        ResolvedTurnConfig config = ApiFixtures.config(ApiFixtures.DIGEST);
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentTurn(
                        TurnId.random(),
                        ThreadId.random(),
                        TurnStatus.QUEUED,
                        1,
                        config.budget(),
                        new AgentRoleRef("misleading", 1),
                        config.provider(),
                        config.permissionProfile(),
                        java.nio.file.Path.of("."),
                        config.promptManifestDigest(),
                        config.toolCatalogDigest(),
                        Optional.empty(),
                        Instant.EPOCH,
                        Instant.EPOCH,
                        config.summary()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentTurn(
                        TurnId.random(),
                        ThreadId.random(),
                        TurnStatus.QUEUED,
                        1,
                        config.budget(),
                        config.role(),
                        config.provider(),
                        config.permissionProfile(),
                        java.nio.file.Path.of("."),
                        "b".repeat(64),
                        config.toolCatalogDigest(),
                        Optional.empty(),
                        Instant.EPOCH,
                        Instant.EPOCH,
                        config.summary()));
    }

    private static AgentRoleSpec spec(Map<String, String> extensions) {
        return new AgentRoleSpec(
                " Custom ",
                " description ",
                " instructions ",
                Optional.empty(),
                Optional.empty(),
                CapabilityNarrowing.inherit(),
                PermissionConstraint.INHERIT,
                extensions);
    }
}
