package com.javaclaw.builtin.contracts;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AttachmentRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemorySkillScheduleContractsTest {
    @Test
    void memoryAndSkillContractsCopySearchResultsAndValidateLimits() {
        MemoryContracts.Memory memory = new MemoryContracts.Memory(
                "memory",
                1,
                MemoryContracts.MemoryKind.FACT,
                "workspace",
                "Remember",
                Set.of("java"),
                false,
                Optional.empty(),
                BuiltinContractsFixtures.NOW,
                BuiltinContractsFixtures.NOW);
        MemoryContracts.SearchRequest memorySearch =
                new MemoryContracts.SearchRequest("remember", Set.of("workspace"), Set.of("java"), 100);
        ArrayList<MemoryContracts.Memory> memories = new ArrayList<>(List.of(memory));
        MemoryContracts.SearchResult memoryResult = new MemoryContracts.SearchResult(memories);
        String digest = BuiltinContractsFixtures.payload().sha256();
        SkillContracts.Summary skill = new SkillContracts.Summary("skill", 1, digest, "Testing", "Find bugs");
        SkillContracts.SearchRequest skillSearch = new SkillContracts.SearchRequest("test", 1);
        SkillContracts.SearchResult skillResult = new SkillContracts.SearchResult(List.of(skill), digest);

        memories.clear();
        assertEquals(1, memoryResult.matches().size());
        assertEquals(Set.of("workspace"), memorySearch.scopes());
        assertEquals("test", skillSearch.query());
        assertEquals(skill, skillResult.matches().getFirst());
        assertThrows(
                IllegalArgumentException.class,
                () -> new MemoryContracts.SearchRequest("query", Set.of(), Set.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new SkillContracts.SearchRequest("query", 101));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MemoryContracts.Memory(
                        "memory",
                        1,
                        MemoryContracts.MemoryKind.FACT,
                        "scope",
                        "content",
                        Set.of(" "),
                        false,
                        Optional.empty(),
                        BuiltinContractsFixtures.NOW,
                        BuiltinContractsFixtures.NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillContracts.Draft(
                        "skill",
                        1,
                        "name",
                        "description",
                        " ",
                        List.of(),
                        BuiltinContractsFixtures.NOW,
                        BuiltinContractsFixtures.NOW));
    }

    @Test
    void skillResourcesRejectDuplicateIdentityAndUnsafeExecutableMedia() {
        SkillContracts.Resource first = new SkillContracts.Resource("same", "text/markdown", "a".repeat(64), false);
        SkillContracts.Resource duplicate = new SkillContracts.Resource("same", "text/plain", "b".repeat(64), false);

        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillContracts.Draft(
                        "skill",
                        1,
                        "name",
                        "description",
                        "instructions",
                        List.of(first, duplicate),
                        BuiltinContractsFixtures.NOW,
                        BuiltinContractsFixtures.NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillContracts.Resource("script", "text/plain", "c".repeat(64), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillContracts.AddResourceRequest(
                        "skill",
                        "too-large",
                        new AttachmentRef(
                                "d".repeat(64),
                                "application/octet-stream",
                                "large.bin",
                                SkillContracts.MAXIMUM_RESOURCE_BYTES + 1),
                        false));
    }

    @Test
    void scheduleContractsRequirePositiveTimingAndNonNegativeRevision() {
        ScheduleContracts.Timing timing =
                ScheduleContracts.Timing.fixed(Duration.ofMinutes(5), BuiltinContractsFixtures.NOW);
        var budget = new OrchestrationContracts.ExecutionBudget(5, 1_000, 500, 10);
        ScheduleContracts.Target target = ScheduleContracts.Target.definition(new ScheduleContracts.DefinitionTarget(
                BuiltinExtensionIds.PLAN, "plan", 1, new AgentProfileRef("profile", 1), budget));
        ScheduleContracts.Definition schedule = new ScheduleContracts.Definition(
                "schedule",
                1,
                "Daily",
                true,
                timing,
                target,
                ScheduleContracts.OverlapPolicy.SKIP_IF_RUNNING,
                ScheduleContracts.MisfirePolicy.DO_NOT_CATCH_UP,
                BuiltinContractsFixtures.NOW);

        assertEquals(Duration.ofMinutes(5), schedule.timing().interval().orElseThrow());
        assertEquals(
                BuiltinExtensionIds.PLAN,
                schedule.target().definition().orElseThrow().extensionId());
        assertThrows(
                IllegalArgumentException.class,
                () -> ScheduleContracts.Timing.fixed(Duration.ZERO, BuiltinContractsFixtures.NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> ScheduleContracts.Timing.fixed(Duration.ofSeconds(-1), BuiltinContractsFixtures.NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScheduleActionContracts.Target(
                        BuiltinExtensionIds.PLAN,
                        "run",
                        List.of(),
                        List.of(),
                        BuiltinContractsFixtures.payload().sha256(),
                        -1));
        assertThrows(
                NullPointerException.class,
                () -> new ScheduleContracts.Definition(
                        "schedule",
                        1,
                        "name",
                        true,
                        null,
                        target,
                        ScheduleContracts.OverlapPolicy.SKIP_IF_RUNNING,
                        ScheduleContracts.MisfirePolicy.DO_NOT_CATCH_UP,
                        BuiltinContractsFixtures.NOW));
    }

    @Test
    void scheduleActionFreezesSchemaAndRequiresEveryFixedArgumentWithExactType() {
        ScheduleActionContracts.Field scope =
                new ScheduleActionContracts.Field("scope", "范围", ScheduleActionContracts.ValueType.STRING, true);
        ScheduleActionContracts.Field enabled =
                new ScheduleActionContracts.Field("enabled", "启用", ScheduleActionContracts.ValueType.BOOLEAN, false);
        ScheduleActionContracts.Target target = new ScheduleActionContracts.Target(
                "javaclaw.safe",
                "refresh",
                List.of(scope, enabled),
                List.of(
                        new ScheduleActionContracts.Argument("enabled", ScheduleActionContracts.ValueType.BOOLEAN, ""),
                        new ScheduleActionContracts.Argument(
                                "scope", ScheduleActionContracts.ValueType.STRING, "workspace")),
                BuiltinContractsFixtures.payload().sha256(),
                2);

        assertEquals(
                List.of("scope", "enabled"),
                target.arguments().stream()
                        .map(ScheduleActionContracts.Argument::name)
                        .toList());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScheduleActionContracts.Target(
                        "javaclaw.safe",
                        "refresh",
                        List.of(scope),
                        List.of(),
                        BuiltinContractsFixtures.payload().sha256(),
                        2));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScheduleActionContracts.Target(
                        "javaclaw.safe",
                        "refresh",
                        List.of(scope),
                        List.of(new ScheduleActionContracts.Argument(
                                "scope", ScheduleActionContracts.ValueType.BOOLEAN, "true")),
                        BuiltinContractsFixtures.payload().sha256(),
                        2));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScheduleActionContracts.Target(
                        "javaclaw.safe",
                        "refresh",
                        List.of(enabled),
                        List.of(new ScheduleActionContracts.Argument(
                                "enabled", ScheduleActionContracts.ValueType.BOOLEAN, "TRUE")),
                        BuiltinContractsFixtures.payload().sha256(),
                        2));
    }

    @Test
    void builtInExtensionIdentifiersAreUnique() {
        Set<String> identifiers = Set.of(
                BuiltinExtensionIds.PLAN,
                BuiltinExtensionIds.LOOP,
                BuiltinExtensionIds.WORKFLOW,
                BuiltinExtensionIds.SDD,
                BuiltinExtensionIds.SCHEDULE,
                BuiltinExtensionIds.MEMORY,
                BuiltinExtensionIds.KNOWLEDGE,
                BuiltinExtensionIds.SKILL,
                BuiltinExtensionIds.SITE);

        assertEquals(9, identifiers.size());
    }
}
