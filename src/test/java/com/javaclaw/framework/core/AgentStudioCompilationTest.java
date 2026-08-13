package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentDefinitionDraft;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.CapabilityId;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunProfileDraft;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.extension.ExtensionArtifact;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.AdvisorSpec;
import com.javaclaw.framework.spi.AdvisorSpecFactory;
import com.javaclaw.framework.spi.AgentFrameworkExtension;
import com.javaclaw.framework.spi.CapabilityDescriptor;
import com.javaclaw.framework.spi.ExtensionContext;
import com.javaclaw.framework.spi.ExtensionDescriptor;
import com.javaclaw.framework.spi.ExtensionRegistrar;
import com.javaclaw.framework.spi.ExtensionScope;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.HotUpdateCompatibility;
import com.javaclaw.framework.spi.SemanticVersion;
import com.javaclaw.framework.spi.RunConstraints;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.store.JdbcAgentDefinitionStore;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStudioCompilationTest {

    @Test
    void installedCapabilityAppearsValidatesPublishesAndCompilesWithoutStudioCodeChanges()
            throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:studio-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(dataSource).initialize();
        JdbcAgentDefinitionStore definitions = new JdbcAgentDefinitionStore(
                new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource),
                json, Clock.systemUTC());

        try (ExtensionManager extensions = new ExtensionManager(new ExtensionContext(
                Clock.systemUTC(), Runnable::run,
                request -> CompletableFuture.failedFuture(
                        new AssertionError("model task not expected"))))) {
            extensions.publish(List.of(ExtensionArtifact.builtin(new CiCapabilityExtension())));
            DefaultAgentStudio studio = new DefaultAgentStudio(definitions, extensions, json);
            CapabilityId capability = new CapabilityId("ci.critic");
            assertTrue(studio.capabilityForms().stream()
                    .anyMatch(form -> form.id().equals(capability)));

            ObjectNode invalidConfig = JsonNodeFactory.instance.objectNode().put("threshold", 0);
            AgentDefinitionDraft invalid = agentDraft(capability, invalidConfig);
            assertFalse(studio.validateAgent("workspace", invalid).valid());
            studio.saveAgentDraft("workspace", invalid);
            assertThrows(IllegalArgumentException.class,
                    () -> studio.publishAgent("workspace", invalid.id()));

            ObjectNode inlineSecret = JsonNodeFactory.instance.objectNode()
                    .put("threshold", 3).put("credential", "actual-api-key");
            assertTrue(studio.validateAgent("workspace",
                    agentDraft(capability, inlineSecret)).issues().stream()
                    .anyMatch(issue -> issue.code().equals("secret.reference")));
            ObjectNode inlineSecretArray = JsonNodeFactory.instance.objectNode().put("threshold", 3);
            inlineSecretArray.putArray("credentials").add("secret:first").add("actual-api-key");
            assertTrue(studio.validateAgent("workspace",
                    agentDraft(capability, inlineSecretArray)).issues().stream()
                    .anyMatch(issue -> issue.path().endsWith("/credentials/1")
                            && issue.code().equals("secret.reference")));
            ObjectNode secretReference = JsonNodeFactory.instance.objectNode()
                    .put("threshold", 3).put("credential", "secret:provider/main");
            secretReference.putArray("credentials")
                    .add("secret:provider/backup").add("credential:provider/fallback");
            assertTrue(studio.validateAgent(
                    "workspace", agentDraft(capability, secretReference)).valid());

            ObjectNode validConfig = JsonNodeFactory.instance.objectNode().put("threshold", 3);
            AgentDefinitionDraft valid = agentDraft(capability, validConfig);
            studio.saveAgentDraft("workspace", valid);
            var publishedAgent = studio.publishAgent("workspace", valid.id());

            RunProfileDraft profileDraft = new RunProfileDraft(
                    "interactive", "Interactive", PermissionSet.of("tool.read"),
                    new RunBudget(Duration.ofMinutes(2), 2_000, 1_000, 4,
                            new BigDecimal("1.00")), Map.of(),
                    JsonNodeFactory.instance.objectNode());
            studio.saveProfileDraft("workspace", profileDraft);
            var publishedProfile = studio.publishProfile("workspace", profileDraft.id());

            AgentCompiler compiler = new AgentCompiler(definitions, extensions, json);
            RunRequest request = RunRequest.builder()
                    .agent(new AgentDefinitionRef(publishedAgent.id(), publishedAgent.version()))
                    .profile(new RunProfileRef(publishedProfile.id(), publishedProfile.version()))
                    .source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", "session"))
                    .input(InputBlock.text("review this"))
                    .permissionCeiling(PermissionSet.of("tool.read", "tool.write"))
                    .budget(RunBudget.UNBOUNDED)
                    .build();

            try (ExecutionPlan plan = compiler.compile(request)) {
                assertEquals(3, plan.descriptor().compiledCapabilities()
                        .get(capability).path("normalizedThreshold").asInt());
                assertEquals(PermissionSet.of("tool.read"), plan.descriptor().permissions());
                assertEquals(List.of("ci.critic.advisor"), plan.descriptor().advisors().stream()
                        .map(AdvisorSpec::id).toList());
                assertEquals("ci_critic", plan.toolFactories().getFirst().create(null)
                        .descriptor().name());
                assertEquals("1.0.0", plan.descriptor().extensionLocks().getFirst()
                        .version().toString());
            }

            AgentCompiler systemRestricted = new AgentCompiler(
                    definitions, extensions, json,
                    new RunConstraints(PermissionSet.of("tool.read"),
                            new RunBudget(Duration.ofSeconds(30), 500, 250, 1,
                                    new BigDecimal("0.25"))));
            try (ExecutionPlan plan = systemRestricted.compile(request)) {
                assertEquals(PermissionSet.of("tool.read"), plan.descriptor().permissions());
                assertEquals(1, plan.descriptor().budget().maxToolCalls());
                assertEquals(Duration.ofSeconds(30), plan.descriptor().budget().timeout());
            }

            ObjectNode disabledConfig = JsonNodeFactory.instance.objectNode()
                    .put("threshold", 3).put("enabled", false);
            AgentDefinitionDraft disabled = agentDraft(capability, disabledConfig);
            studio.saveAgentDraft("workspace", disabled);
            var disabledAgent = studio.publishAgent("workspace", disabled.id());
            RunRequest disabledRequest = RunRequest.builder()
                    .agent(new AgentDefinitionRef(disabledAgent.id(), disabledAgent.version()))
                    .profile(new RunProfileRef(publishedProfile.id(), publishedProfile.version()))
                    .source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", "session-disabled"))
                    .input(InputBlock.text("skip critic"))
                    .permissionCeiling(PermissionSet.of("tool.read"))
                    .budget(RunBudget.UNBOUNDED)
                    .build();
            try (ExecutionPlan plan = compiler.compile(disabledRequest)) {
                assertTrue(plan.descriptor().compiledCapabilities().isEmpty());
                assertTrue(plan.descriptor().extensionLocks().isEmpty());
                assertTrue(plan.descriptor().advisors().isEmpty());
                assertTrue(plan.toolFactories().isEmpty());
            }
        }
    }

    private static AgentDefinitionDraft agentDraft(
            CapabilityId capability, ObjectNode configuration) {
        return new AgentDefinitionDraft(
                "user.critic", "Critic", "workspace:normal",
                Map.of("system", "Review output"), Map.of(capability, configuration),
                JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(),
                RunBudget.UNBOUNDED, JsonNodeFactory.instance.objectNode(),
                Map.of("ci.critic", "^1.0.0"));
    }

    private static final class CiCapabilityExtension implements AgentFrameworkExtension {
        private static final CapabilityId ID = new CapabilityId("ci.critic");
        private final ExtensionDescriptor descriptor = new ExtensionDescriptor(
                "ci.critic", SemanticVersion.parse("1.0.0"), ">=2.0.0 <3.0.0",
                ">=2.0.0 <3.0.0", List.of(), Set.of(), ExtensionScope.PLAN_SCOPED,
                HotUpdateCompatibility.PLAN_ISOLATED, 1, Map.of());

        @Override
        public ExtensionDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public void register(ExtensionRegistrar registrar) {
            ObjectNode schema = JsonNodeFactory.instance.objectNode();
            schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
            schema.put("type", "object");
            schema.putArray("required").add("threshold");
            ObjectNode threshold = schema.putObject("properties").putObject("threshold");
            threshold.put("type", "integer");
            threshold.put("minimum", 1);
            threshold.put("maximum", 10);
            schema.withObject("/properties").putObject("enabled").put("type", "boolean");
            schema.withObject("/properties").putObject("credential")
                    .put("type", "string").put("format", "secret-ref");
            schema.withObject("/properties").putObject("credentials")
                    .put("type", "array").putObject("items")
                    .put("type", "string").put("format", "secret-ref");
            schema.put("additionalProperties", false);
            registrar.capability(new CapabilityDescriptor(
                    ID, "CI Critic", "Test capability dynamically rendered by Agent Studio",
                    schema, JsonNodeFactory.instance.objectNode()),
                    (id, configuration, context) -> JsonNodeFactory.instance.objectNode()
                            .put("normalizedThreshold", configuration.path("threshold").asInt()));
            registrar.advisor(new AdvisorSpecFactory() {
                @Override public CapabilityId capabilityId() { return ID; }
                @Override public String advisorId() { return "ci.critic.advisor"; }
                @Override
                public AdvisorSpec create(
                        com.fasterxml.jackson.databind.JsonNode configuration,
                        com.javaclaw.framework.spi.CompilationContext context) {
                    return new AdvisorSpec("ci.critic.advisor", "evaluation", 500,
                            configuration);
                }
                @Override
                public org.springframework.ai.chat.client.advisor.api.Advisor createAdvisor(
                        AdvisorSpec specification,
                        com.javaclaw.framework.spi.AdvisorRuntimeContext context) {
                    return new org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor(
                            specification.order());
                }
            });
            registrar.tool(context -> new FrameworkTool() {
                @Override
                public ToolDescriptor descriptor() {
                    return new ToolDescriptor("ci_critic", "Review a value",
                            JsonNodeFactory.instance.objectNode().put("type", "object"),
                            PermissionSet.of("tool.read"), true);
                }

                @Override
                public com.fasterxml.jackson.databind.JsonNode execute(
                        com.fasterxml.jackson.databind.JsonNode arguments,
                        com.javaclaw.framework.spi.ToolExecutionContext executionContext) {
                    return JsonNodeFactory.instance.objectNode().put("reviewed", true);
                }
            });
        }
    }
}
