package com.javaclaw.framework.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.ModelPolicyRefs;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunProfileDraft;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.ExtensionContext;
import com.javaclaw.framework.store.JdbcAgentDefinitionStore;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinDefinitionBootstrapTest {

    @Test
    void installsTheCompleteBuiltinSetAndRepairsAMissingPublishedAgent() {
        Fixture fixture = new Fixture();
        try (ExtensionManager extensions = fixture.extensions();
             BuiltinDefinitionRegistry.Registration ignored = fixture.registry.register(
                     "workspace", fixture.definitions, fixture.models, extensions)) {
            assertEquals(BuiltinDefinitionBootstrap.SYSTEM_PROFILE_IDS,
                    fixture.definitions.listProfileDrafts("workspace").stream()
                            .map(draft -> draft.id()).collect(java.util.stream.Collectors.toSet()));
            fixture.definitions.resolveAgent(
                    "workspace", AgentDefinitionRef.latest("system.default"));
            RunBudget global = fixture.definitions.resolveAgent(
                    "workspace", AgentDefinitionRef.latest("system.default")).budgetPolicy();
            assertBudget(global, 250_000, 80_000, 120);
            RunBudget chat = fixture.definitions.resolveProfile(
                    "workspace", RunProfileRef.latest("chat")).budget();
            assertEquals(120_000, chat.maxInputTokens());
            assertEquals(32_000, chat.maxOutputTokens());
            assertEquals(16, chat.maxToolCalls());
            RunBudget plan = fixture.definitions.resolveProfile(
                    "workspace", RunProfileRef.latest("plan")).budget();
            assertEquals(80_000, plan.maxInputTokens());
            assertEquals(24_000, plan.maxOutputTokens());
            assertEquals(8, plan.maxToolCalls());
            for (String profileId : List.of("plugin", "subagent")) {
                assertBudget(fixture.definitions.resolveProfile(
                        "workspace", RunProfileRef.latest(profileId)).budget()
                        .restrictWith(global), 250_000, 80_000, 120);
            }
            for (String profileId : List.of("schedule", "loop", "sdd")) {
                assertBudget(fixture.definitions.resolveProfile(
                        "workspace", RunProfileRef.latest(profileId)).budget()
                        .restrictWith(global), 250_000, 80_000, 120);
            }
            assertBudget(chat.restrictWith(global), 120_000, 32_000, 16);
            assertBudget(plan.restrictWith(global), 80_000, 24_000, 8);

            fixture.jdbc.update("""
                    DELETE FROM agent_definition_versions
                    WHERE workspace_id = ? AND definition_id = ?
                    """, "workspace", "system.default");
            assertThrows(java.util.NoSuchElementException.class,
                    () -> fixture.definitions.resolveAgent(
                            "workspace", AgentDefinitionRef.latest("system.default")));

            assertTrue(fixture.registry.ensureAgent(
                    "workspace", AgentDefinitionRef.latest("system.default")));
            assertEquals(2, fixture.definitions.resolveAgent(
                    "workspace", AgentDefinitionRef.latest("system.default")).version());
        }
    }

    @Test
    void completesADevelopmentDatabaseContainingOnlyTheFirstTwoProfiles() {
        Fixture fixture = new Fixture();
        try (ExtensionManager extensions = fixture.extensions()) {
            BuiltinDefinitionRegistry.Registration first = fixture.registry.register(
                    "workspace", fixture.definitions, fixture.models, extensions);
            first.close();
            fixture.jdbc.update("DELETE FROM agent_definition_versions WHERE workspace_id = ?",
                    "workspace");
            fixture.jdbc.update("DELETE FROM agent_definitions WHERE workspace_id = ?",
                    "workspace");
            fixture.jdbc.update("""
                    DELETE FROM run_profile_versions
                    WHERE workspace_id = ? AND profile_id NOT IN ('chat', 'plan')
                    """, "workspace");
            fixture.jdbc.update("""
                    DELETE FROM run_profiles
                    WHERE workspace_id = ? AND id NOT IN ('chat', 'plan')
                    """, "workspace");

            try (BuiltinDefinitionRegistry.Registration ignored = fixture.registry.register(
                    "workspace", fixture.definitions, fixture.models, extensions)) {
                assertEquals(BuiltinDefinitionBootstrap.SYSTEM_PROFILE_IDS,
                        fixture.definitions.listProfileDrafts("workspace").stream()
                                .map(draft -> draft.id())
                                .collect(java.util.stream.Collectors.toSet()));
                fixture.definitions.resolveAgent(
                        "workspace", AgentDefinitionRef.latest("system.default"));
            }
        }
    }

    @Test
    void rollsBackTheWholeBuiltinGenerationWhenAReservedIdIsOccupied() {
        Fixture fixture = new Fixture();
        fixture.definitions.saveProfileDraft("workspace", new RunProfileDraft(
                "schedule", "User Schedule", PermissionSet.UNRESTRICTED,
                RunBudget.UNBOUNDED, Map.of(), JsonNodeFactory.instance.objectNode()), false);

        try (ExtensionManager extensions = fixture.extensions()) {
            assertThrows(IllegalStateException.class, () -> new BuiltinDefinitionBootstrap(
                    "workspace", fixture.definitions, fixture.models, extensions));
        }

        assertEquals(List.of("schedule"), fixture.definitions.listProfileDrafts("workspace")
                .stream().map(draft -> draft.id()).toList());
        assertTrue(fixture.definitions.listAgentDrafts("workspace").isEmpty());
        assertThrows(java.util.NoSuchElementException.class,
                () -> fixture.definitions.resolveAgent(
                        "workspace", AgentDefinitionRef.latest("system.default")));
    }

    @Test
    void closedWorkspaceLeaseCannotProvisionDefinitions() {
        Fixture fixture = new Fixture();
        try (ExtensionManager extensions = fixture.extensions()) {
            BuiltinDefinitionRegistry.Registration registration = fixture.registry.register(
                    "workspace", fixture.definitions, fixture.models, extensions);
            registration.close();
            assertFalse(fixture.registry.ensureAgent(
                    "workspace", AgentDefinitionRef.latest("system.default")));
        }
    }

    private static final class Fixture {
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        private final JdbcTemplate jdbc;
        private final JdbcAgentDefinitionStore definitions;
        private final BuiltinDefinitionRegistry registry = new BuiltinDefinitionRegistry();
        private final ModelPolicyRefs models = new ModelPolicyRefs(
                "workspace:high", "workspace:normal", "workspace:light");

        private Fixture() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:h2:mem:builtins-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
            new SchemaInitializer(dataSource).initialize();
            jdbc = new JdbcTemplate(dataSource);
            definitions = new JdbcAgentDefinitionStore(
                    jdbc, new DataSourceTransactionManager(dataSource), json, Clock.systemUTC());
        }

        private ExtensionManager extensions() {
            return new ExtensionManager(new ExtensionContext(
                    Clock.systemUTC(), Runnable::run,
                    request -> CompletableFuture.failedFuture(
                            new AssertionError("model task not expected"))));
        }
    }

    private static void assertBudget(
            RunBudget budget, long input, long output, int toolCalls) {
        assertEquals(input, budget.maxInputTokens());
        assertEquals(output, budget.maxOutputTokens());
        assertEquals(toolCalls, budget.maxToolCalls());
    }
}
