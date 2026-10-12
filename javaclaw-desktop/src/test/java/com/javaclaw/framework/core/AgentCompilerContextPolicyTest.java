package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import com.javaclaw.framework.builtin.BuiltinCapabilityExtension;
import com.javaclaw.framework.extension.ExtensionArtifact;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.DeferredContextCandidate;
import com.javaclaw.framework.spi.DeferredContextSource;
import com.javaclaw.framework.spi.ExtensionContext;
import com.javaclaw.framework.spi.ExtensionRegistrar;
import com.javaclaw.framework.spi.ExtensionStateView;
import com.javaclaw.framework.spi.FixedContextSnapshot;
import com.javaclaw.framework.spi.FixedContextSource;
import com.javaclaw.framework.spi.OnDemandContributions;
import com.javaclaw.framework.spi.PromptContributor;
import com.javaclaw.framework.spi.TurnPreparation;
import com.javaclaw.framework.store.JdbcAgentDefinitionStore;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCompilerContextPolicyTest {

    @Test
    void 上下文策略参与计划校验和与计划编号() {
        try (Fixture fixture = new Fixture()) {
            var first = contextConfiguration(8_000);
            var second = contextConfiguration(12_000);

            ExecutionPlanDescriptor firstPlan = fixture.compile(first);
            ExecutionPlanDescriptor secondPlan = fixture.compile(second);

            assertEquals(8_000, firstPlan.stepContextPolicy().maxMessageCharacters());
            assertEquals(12_000, secondPlan.stepContextPolicy().maxMessageCharacters());
            assertNotEquals(firstPlan.checksum(), secondPlan.checksum());
            assertNotEquals(firstPlan.id(), secondPlan.id());
        }
    }

    @Test
    void 未绑定能力时不启用上下文策略() {
        try (Fixture fixture = new Fixture()) {
            ExecutionPlanDescriptor plan = fixture.compile(null);

            assertNull(plan.stepContextPolicy());
            assertFalse(plan.compiledCapabilities().containsKey(
                    new CapabilityId("context.compaction")));
            assertNull(plan.onDemandContextPolicy());
        }
    }

    @Test
    void 显式关闭能力时不启用上下文策略() {
        try (Fixture fixture = new Fixture()) {
            ExecutionPlanDescriptor plan = fixture.compile(contextConfiguration(8_000)
                    .put("enabled", false));

            assertNull(plan.stepContextPolicy());
            assertFalse(plan.compiledCapabilities().containsKey(
                    new CapabilityId("context.compaction")));
            assertNull(plan.onDemandContextPolicy());
        }
    }

    @Test
    void 启用能力但不覆盖限制时使用默认策略() {
        try (Fixture fixture = new Fixture()) {
            ExecutionPlanDescriptor plan = fixture.compile(
                    JsonNodeFactory.instance.objectNode().put("enabled", true));

            assertEquals(StepContextPolicy.DEFAULT, plan.stepContextPolicy());
            assertEquals(OnDemandContextPolicy.DEFAULT, plan.onDemandContextPolicy());
            assertEquals(OnDemandContextPolicy.CANDIDATE_TOOL_SELECTION_VERSION,
                    plan.onDemandContextPolicy().toolSelectionVersion());
        }
    }

    @Test
    void 显式关闭按需能力时保留上下文裁剪但不创建按需策略() {
        try (Fixture fixture = new Fixture()) {
            ExecutionPlanDescriptor plan = fixture.compile(contextConfiguration(8_000),
                    JsonNodeFactory.instance.objectNode().put("enabled", false));

            assertEquals(8_000, plan.stepContextPolicy().maxMessageCharacters());
            assertNull(plan.onDemandContextPolicy());
        }
    }

    @Test
    void 显式关闭裁剪能力时同步关闭按需能力() {
        try (Fixture fixture = new Fixture()) {
            ExecutionPlanDescriptor plan = fixture.compile(contextConfiguration(8_000)
                            .put("enabled", false),
                    JsonNodeFactory.instance.objectNode().put("enabled", true));

            assertNull(plan.stepContextPolicy());
            assertNull(plan.onDemandContextPolicy());
            assertFalse(plan.compiledCapabilities().containsKey(
                    new CapabilityId("context.on_demand")));
        }
    }

    @Test
    void 按需配置参与计划校验和且冻结在计划中() {
        try (Fixture fixture = new Fixture()) {
            var first = JsonNodeFactory.instance.objectNode().put("enabled", true)
                    .put("searches", 2);
            var second = JsonNodeFactory.instance.objectNode().put("enabled", true)
                    .put("searches", 4);

            ExecutionPlanDescriptor firstPlan = fixture.compile(contextConfiguration(8_000), first);
            ExecutionPlanDescriptor secondPlan = fixture.compile(contextConfiguration(8_000), second);

            assertEquals(2, firstPlan.onDemandContextPolicy().searches());
            assertEquals(4, secondPlan.onDemandContextPolicy().searches());
            assertNotEquals(firstPlan.checksum(), secondPlan.checksum());
        }
    }

    @Test
    void 新编译计划不接受配置降级为旧工具选择契约() {
        try (Fixture fixture = new Fixture()) {
            var legacy = JsonNodeFactory.instance.objectNode().put("enabled", true)
                    .put("toolSelectionVersion", 1);
            var current = JsonNodeFactory.instance.objectNode().put("enabled", true);

            ExecutionPlanDescriptor first = fixture.compile(contextConfiguration(8_000), legacy);
            ExecutionPlanDescriptor second = fixture.compile(contextConfiguration(8_000), current);

            assertEquals(OnDemandContextPolicy.CANDIDATE_TOOL_SELECTION_VERSION,
                    first.onDemandContextPolicy().toolSelectionVersion());
            assertEquals(OnDemandContextPolicy.CANDIDATE_TOOL_SELECTION_VERSION,
                    second.onDemandContextPolicy().toolSelectionVersion());
        }
    }

    @Test
    void 未安装按需扩展的旧注册表继续编译为非按需计划() {
        try (Fixture fixture = new Fixture(false)) {
            ExecutionPlanDescriptor plan = fixture.compile(contextConfiguration(8_000));

            assertEquals(8_000, plan.stepContextPolicy().maxMessageCharacters());
            assertNull(plan.onDemandContextPolicy());
        }
    }

    @Test
    void 按需计划拒绝未分类的急切贡献() {
        try (Fixture fixture = new Fixture(true,
                registrar -> registrar.promptContributor((request, state) -> "unsafe"))) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> fixture.compile(contextConfiguration(8_000)));

            assertTrue(failure.getMessage().contains("lacks explicit classification"));
        }
    }

    @Test
    void 按需计划验证延迟贡献指向已注册来源() {
        Consumer<ExtensionRegistrar> missing = registrar -> registrar.promptContributor(
                OnDemandContributions.deferred(
                        (PromptContributor) (request, state) -> "unused", "missing"));
        try (Fixture fixture = new Fixture(true, missing)) {
            assertThrows(IllegalStateException.class,
                    () -> fixture.compile(contextConfiguration(8_000)));
        }

        Consumer<ExtensionRegistrar> registered = registrar -> {
            registrar.promptContributor(OnDemandContributions.deferred(
                    (PromptContributor) (request, state) -> "unused", "registered"));
            registrar.deferredContextSource(new DeferredContextSource() {
                @Override public String id() { return "registered"; }
                @Override public String description() { return "test source"; }
                @Override public PermissionSet requiredPermissions() { return PermissionSet.NONE; }
                @Override public List<DeferredContextCandidate> search(
                        RunRequest request, String query, int limit) { return List.of(); }
                @Override public String fetch(
                        RunRequest request, String candidateId, String version) { return ""; }
            });
        };
        try (Fixture fixture = new Fixture(true, registered)) {
            assertEquals(OnDemandContextPolicy.DEFAULT,
                    fixture.compile(contextConfiguration(8_000)).onDemandContextPolicy());
        }
    }

    @Test
    void 固定来源仅冻结在获授权的新按需计划() {
        Consumer<ExtensionRegistrar> fixed = registrar -> registrar.fixedContextSource(
                new FixedContextSource() {
                    @Override public String id() { return "memory.persona"; }
                    @Override public String group() { return "memory"; }
                    @Override public PermissionSet requiredPermissions() {
                        return PermissionSet.of("tool.read");
                    }
                    @Override public FixedContextSnapshot read(RunRequest request) {
                        return new FixedContextSnapshot("v1", "persona");
                    }
                });
        try (Fixture fixture = new Fixture(true, fixed)) {
            assertEquals(List.of("memory.persona"), fixture.compile(
                    contextConfiguration(8_000)).fixedContextSourceIds());
            assertEquals(List.of(), fixture.compile(contextConfiguration(8_000),
                    JsonNodeFactory.instance.objectNode().put("enabled", false))
                    .fixedContextSourceIds());
            assertEquals(List.of(), fixture.compile(contextConfiguration(8_000), null, null,
                    PermissionSet.NONE, RunBudget.UNBOUNDED, Map.of()).fixedContextSourceIds());
            assertEquals(List.of(), fixture.compile(contextConfiguration(8_000), null, null,
                    PermissionSet.UNRESTRICTED, RunBudget.UNBOUNDED,
                    Map.of("framework.disableTools", JsonNodeFactory.instance.booleanNode(true)))
                    .fixedContextSourceIds());
            assertEquals(List.of(), fixture.compile(contextConfiguration(8_000), null, null,
                    PermissionSet.UNRESTRICTED,
                    new RunBudget(Duration.ofMinutes(1), Long.MAX_VALUE, Long.MAX_VALUE,
                            0, BigDecimal.ONE), Map.of()).fixedContextSourceIds());
            assertEquals(List.of(), fixture.compile(contextConfiguration(8_000), null, null,
                    PermissionSet.UNRESTRICTED, RunBudget.UNBOUNDED,
                    Map.of("framework.allowedToolGroups",
                            JsonNodeFactory.instance.arrayNode().add("web")))
                    .fixedContextSourceIds());
        }
    }

    @Test
    void 禁用记忆召回时不冻结persona来源() {
        Consumer<ExtensionRegistrar> persona = registrar -> registrar.fixedContextSource(
                new FixedContextSource() {
                    @Override public String id() { return "memory.persona"; }
                    @Override public String group() { return "memory"; }
                    @Override public PermissionSet requiredPermissions() {
                        return PermissionSet.of("tool.read");
                    }
                    @Override public FixedContextSnapshot read(RunRequest request) {
                        return new FixedContextSnapshot("v1", "persona");
                    }
                });
        try (Fixture fixture = new Fixture(true, registrar -> {}, persona)) {
            assertEquals(List.of("memory.persona"), fixture.compile(
                    contextConfiguration(8_000), null,
                    JsonNodeFactory.instance.objectNode().put("enabled", true))
                    .fixedContextSourceIds());
            assertEquals(List.of(), fixture.compile(contextConfiguration(8_000), null,
                    JsonNodeFactory.instance.objectNode().put("enabled", false))
                    .fixedContextSourceIds());
        }
    }

    @Test
    void 仅即时贡献必须有同一扩展的Turn准备替代() {
        Consumer<ExtensionRegistrar> eager = registrar -> registrar.promptContributor(
                OnDemandContributions.eagerOnly((request, state) -> "eager"));
        try (Fixture fixture = new Fixture(true, eager)) {
            assertThrows(IllegalStateException.class,
                    () -> fixture.compile(contextConfiguration(8_000)));
        }

        Consumer<ExtensionRegistrar> prepared = registrar -> {
            eager.accept(registrar);
            registrar.turnPreparation(new TurnPreparation() {
                @Override public String id() { return "replacement"; }
                @Override public String prepare(RunRequest request, ExtensionStateView state) {
                    return "prepared";
                }
            });
        };
        try (Fixture fixture = new Fixture(true, prepared)) {
            assertEquals(OnDemandContextPolicy.DEFAULT,
                    fixture.compile(contextConfiguration(8_000)).onDemandContextPolicy());
        }
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode contextConfiguration(
            int maxMessageCharacters) {
        return JsonNodeFactory.instance.objectNode()
                .put("enabled", true)
                .put("maxMessageCharacters", maxMessageCharacters)
                .put("maxToolSchemaCharacters", 9_000)
                .put("retainedToolExchanges", 3)
                .put("maxToolResultCharacters", 2_000)
                .put("maxTools", 12);
    }

    private static final class Fixture implements AutoCloseable {
        private static final CapabilityId CONTEXT = new CapabilityId("context.compaction");
        private static final CapabilityId ON_DEMAND = new CapabilityId("context.on_demand");
        private static final CapabilityId MEMORY = new CapabilityId("memory.recall");
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        private final JdbcAgentDefinitionStore definitions;
        private final ExtensionManager extensions;
        private final AgentCompiler compiler;

        private Fixture() {
            this(true, registrar -> {});
        }

        private Fixture(boolean registerOnDemand) {
            this(registerOnDemand, registrar -> {});
        }

        private Fixture(boolean registerOnDemand,
                        Consumer<ExtensionRegistrar> onDemandContributions) {
            this(registerOnDemand, onDemandContributions, null);
        }

        private Fixture(boolean registerOnDemand,
                        Consumer<ExtensionRegistrar> onDemandContributions,
                        Consumer<ExtensionRegistrar> memoryContributions) {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:h2:mem:compiler-context-" + UUID.randomUUID()
                            + ";DB_CLOSE_DELAY=-1", "sa", "");
            new SchemaInitializer(dataSource).initialize();
            definitions = new JdbcAgentDefinitionStore(
                    new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource),
                    json, Clock.systemUTC());
            extensions = new ExtensionManager(new ExtensionContext(
                    Clock.systemUTC(), Runnable::run,
                    request -> CompletableFuture.failedFuture(
                            new AssertionError("model task not expected"))));
            var installed = new java.util.ArrayList<ExtensionArtifact>();
            installed.add(ExtensionArtifact.builtin(
                    new BuiltinCapabilityExtension(
                            CONTEXT.value(), "Context", "Bounded context",
                            JsonNodeFactory.instance.objectNode(),
                            JsonNodeFactory.instance.objectNode(), List.of(), registrar -> {})));
            if (registerOnDemand) {
                installed.add(ExtensionArtifact.builtin(new BuiltinCapabilityExtension(
                        ON_DEMAND.value(), "On Demand", "Deferred context",
                        JsonNodeFactory.instance.objectNode(),
                        JsonNodeFactory.instance.objectNode(), List.of(), onDemandContributions)));
            }
            if (memoryContributions != null) {
                installed.add(ExtensionArtifact.builtin(new BuiltinCapabilityExtension(
                        MEMORY.value(), "Memory", "Memory recall",
                        JsonNodeFactory.instance.objectNode(),
                        JsonNodeFactory.instance.objectNode(), List.of(), memoryContributions)));
            }
            extensions.publish(installed);
            publishProfile();
            compiler = new AgentCompiler(definitions, extensions, json);
        }

        private ExecutionPlanDescriptor compile(
                com.fasterxml.jackson.databind.node.ObjectNode configuration) {
            return compile(configuration, null);
        }

        private ExecutionPlanDescriptor compile(
                com.fasterxml.jackson.databind.node.ObjectNode configuration,
                com.fasterxml.jackson.databind.node.ObjectNode onDemandConfiguration) {
            return compile(configuration, onDemandConfiguration, null);
        }

        private ExecutionPlanDescriptor compile(
                com.fasterxml.jackson.databind.node.ObjectNode configuration,
                com.fasterxml.jackson.databind.node.ObjectNode onDemandConfiguration,
                com.fasterxml.jackson.databind.node.ObjectNode memoryConfiguration) {
            return compile(configuration, onDemandConfiguration, memoryConfiguration,
                    PermissionSet.UNRESTRICTED, RunBudget.UNBOUNDED, Map.of());
        }

        private ExecutionPlanDescriptor compile(
                com.fasterxml.jackson.databind.node.ObjectNode configuration,
                com.fasterxml.jackson.databind.node.ObjectNode onDemandConfiguration,
                com.fasterxml.jackson.databind.node.ObjectNode memoryConfiguration,
                PermissionSet permissions, RunBudget budget, Map<String, JsonNode> attributes) {
            var capabilities = new LinkedHashMap<CapabilityId, com.fasterxml.jackson.databind.JsonNode>();
            if (configuration != null) capabilities.put(CONTEXT, configuration);
            if (onDemandConfiguration != null) capabilities.put(ON_DEMAND, onDemandConfiguration);
            if (memoryConfiguration != null) capabilities.put(MEMORY, memoryConfiguration);
            AgentDefinitionDraft agent = new AgentDefinitionDraft(
                    "test.agent", "Test", "test:model", Map.of("system", "test"),
                    capabilities,
                    JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.objectNode(), RunBudget.UNBOUNDED,
                    JsonNodeFactory.instance.objectNode(), Map.of(CONTEXT.value(), "=2.0.0"));
            definitions.saveAgentDraft("workspace", agent, false);
            var published = definitions.publishAgent("workspace", agent.id());
            RunRequest request = RunRequest.builder()
                    .agent(new AgentDefinitionRef(published.id(), published.version()))
                    .profile(RunProfileRef.latest("test.profile"))
                    .source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", "session"))
                    .input(InputBlock.text("task"))
                    .permissionCeiling(permissions)
                    .budget(budget)
                    .attributes(attributes)
                    .build();
            try (ExecutionPlan plan = compiler.compile(request)) {
                return plan.descriptor();
            }
        }

        private void publishProfile() {
            RunProfileDraft profile = new RunProfileDraft(
                    "test.profile", "Test", PermissionSet.UNRESTRICTED,
                    RunBudget.UNBOUNDED, Map.of(), JsonNodeFactory.instance.objectNode());
            definitions.saveProfileDraft("workspace", profile, false);
            definitions.publishProfile("workspace", profile.id());
        }

        @Override
        public void close() {
            extensions.close();
        }
    }
}
