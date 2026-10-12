package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.builtin.BuiltinCapabilityExtension;
import com.javaclaw.framework.extension.ExtensionArtifact;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.ExecutionPlanStore;
import com.javaclaw.framework.spi.ExtensionContext;
import com.javaclaw.framework.spi.ExternalPluginLock;
import com.javaclaw.framework.spi.ExternalPluginRuntime;
import com.javaclaw.framework.store.JdbcAgentDefinitionStore;
import com.javaclaw.framework.store.JdbcExecutionPlanStore;
import com.javaclaw.framework.store.JdbcRunStore;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 真实编译器取得的插件和框架租约在运行初始化失败后必须全部归还。 */
class AgentEnginePlanCleanupTest {
    @Test void invalidMainToolGroupsCloseTheCompiledPlanBeforeAnyRunIsCreated() {
        try (var fixture = new Fixture()) {
            try (var engine = fixture.engine(fixture.plans)) {
                SecurityException failure = assertThrows(SecurityException.class, () -> engine.start(
                        fixture.request().withAttribute("framework.allowedToolGroups",
                                JsonNodeFactory.instance.textNode("web"))));
                assertTrue(failure.getMessage().contains("must be an array"));
                fixture.assertAllLeasesReleased();
            }
        }
    }

    @Test void persistenceFailureAfterAnnotationAlsoClosesTheCompiledPlan() {
        try (var fixture = new Fixture()) {
            IllegalStateException expected = new IllegalStateException("计划存储不可用");
            ExecutionPlanStore unavailable = new ExecutionPlanStore() {
                @Override public void save(String id, long generation, JsonNode plan, String checksum) { throw expected; }
                @Override public Optional<JsonNode> find(String id) { return Optional.empty(); }
            };
            try (var engine = fixture.engine(unavailable)) {
                assertSame(expected, assertThrows(IllegalStateException.class,
                        () -> engine.start(fixture.request())));
                fixture.assertAllLeasesReleased();
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        private final Clock clock = Clock.systemUTC();
        private final AtomicInteger acquired = new AtomicInteger(), released = new AtomicInteger(), stopped = new AtomicInteger();
        private final JdbcAgentDefinitionStore definitions;
        private final JdbcRunStore runs;
        private final JdbcExecutionPlanStore plans;
        private final ExtensionManager extensions;
        private final ExternalPluginRuntime plugins = new ExternalPluginRuntime() {
            @Override public Snapshot capture(RunRequest request) {
                acquired.incrementAndGet();
                return new Snapshot() { @Override public void close() { released.incrementAndGet(); } };
            }
            @Override public Snapshot restore(List<ExternalPluginLock> locks) {
                throw new AssertionError("新运行不应恢复插件租约");
            }
        };

        private Fixture() {
            var dataSource = new DriverManagerDataSource("jdbc:h2:mem:plan-cleanup-" + UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1", "sa", "");
            new SchemaInitializer(dataSource).initialize();
            var jdbc = new JdbcTemplate(dataSource);
            var transactions = new DataSourceTransactionManager(dataSource);
            definitions = new JdbcAgentDefinitionStore(jdbc, transactions, json, clock);
            runs = new JdbcRunStore(jdbc, transactions, json, clock);
            plans = new JdbcExecutionPlanStore(jdbc, json, clock);
            extensions = new ExtensionManager(new ExtensionContext(clock, Runnable::run,
                    request -> CompletableFuture.failedFuture(new AssertionError("初始化失败不应调用模型"))));
            extensions.publish(List.of(ExtensionArtifact.builtin(new BuiltinCapabilityExtension(
                    "interaction.run", "交互主角色", "验证计划注解", JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.objectNode(), List.of(), ignored -> { }) {
                @Override public void stop() { stopped.incrementAndGet(); }
            })));
            var agent = new AgentDefinitionDraft("test.agent", "测试", "test:model", Map.of(),
                    Map.of(new CapabilityId("interaction.run"), JsonNodeFactory.instance.objectNode()),
                    null, null, RunBudget.UNBOUNDED, null, Map.of());
            definitions.saveAgentDraft("workspace", agent, false);
            definitions.publishAgent("workspace", agent.id());
            var profile = new RunProfileDraft("test.profile", "测试", PermissionSet.UNRESTRICTED,
                    RunBudget.UNBOUNDED, Map.of(), JsonNodeFactory.instance.objectNode());
            definitions.saveProfileDraft("workspace", profile, false);
            definitions.publishProfile("workspace", profile.id());
        }
        private AgentEngine engine(ExecutionPlanStore store) {
            return new AgentEngine(new AgentCompiler(definitions, extensions, json).withExternalPlugins(plugins),
                    runs, store, request -> CompletableFuture.failedFuture(new AssertionError("运行不应启动")),
                    Runnable::run, json, clock, new RunUsageLedger());
        }
        private RunRequest request() {
            return RunRequest.builder().agent(AgentDefinitionRef.latest("test.agent"))
                    .profile(RunProfileRef.latest("test.profile")).source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", "session")).input(InputBlock.text("测试"))
                    .permissionCeiling(PermissionSet.UNRESTRICTED).budget(RunBudget.UNBOUNDED).build();
        }
        private void assertAllLeasesReleased() {
            assertEquals(1, acquired.get());
            assertEquals(1, released.get());
            assertTrue(runs.nonTerminalRuns().isEmpty());
            extensions.publish(List.of());
            assertEquals(1, stopped.get(), "未归还的框架租约会阻止旧扩展停用");
        }
        @Override public void close() { extensions.close(); }
    }
}
