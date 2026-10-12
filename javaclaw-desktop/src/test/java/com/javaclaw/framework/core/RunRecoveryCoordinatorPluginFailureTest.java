package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.*;
import com.javaclaw.framework.store.JdbcAgentDefinitionStore;
import com.javaclaw.framework.store.JdbcExecutionPlanStore;
import com.javaclaw.framework.store.JdbcRunStore;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** 验证某个插件快照恢复失败时，其他 Run 仍按原计划恢复。 */
class RunRecoveryCoordinatorPluginFailureTest {
    @Test void missingPluginBlocksOnlyItsOwnerAndContinuesWithTheNextRun() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        Clock clock = Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC);
        var source = new DriverManagerDataSource("jdbc:h2:mem:plugin-recovery-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(source).initialize();
        var jdbc = new JdbcTemplate(source);
        var transactions = new DataSourceTransactionManager(source);
        var definitions = new JdbcAgentDefinitionStore(jdbc, transactions, json, clock);
        definitions.saveAgentDraft("workspace", new AgentDefinitionDraft("fixture", "恢复测试", "fixture:model",
                Map.of(), Map.of(), json.createObjectNode(), json.createObjectNode(), RunBudget.UNBOUNDED,
                json.createObjectNode(), Map.of()), false);
        definitions.publishAgent("workspace", "fixture");
        definitions.saveProfileDraft("workspace", new RunProfileDraft("fixture", "恢复测试",
                PermissionSet.NONE, RunBudget.UNBOUNDED, Map.of(), json.createObjectNode()), false);
        definitions.publishProfile("workspace", "fixture");
        var runs = new JdbcRunStore(jdbc, transactions, json, clock);
        var plans = new JdbcExecutionPlanStore(jdbc, json, clock);
        List<String> restoredPlugins = new ArrayList<>();
        ExternalPluginRuntime plugins = new ExternalPluginRuntime() {
            @Override public Snapshot capture(RunRequest request) {
                var lock = new ExternalPluginLock("workspace", request.scope().sessionId(), "a".repeat(64), 7, List.of());
                return new Snapshot() { @Override public List<ExternalPluginLock> locks() { return List.of(lock); } };
            }
            @Override public Snapshot restore(List<ExternalPluginLock> locks) {
                String plugin = locks.getFirst().pluginId();
                restoredPlugins.add(plugin);
                if (plugin.equals("missing")) throw new IllegalStateException("原插件工件或配置不可用");
                return new Snapshot() { @Override public List<ExternalPluginLock> locks() { return locks; } };
            }
        };
        try (var extensions = new ExtensionManager(new ExtensionContext(clock, Runnable::run,
                request -> CompletableFuture.failedFuture(new AssertionError("恢复不应执行模型"))))) {
            var compiler = new AgentCompiler(definitions, extensions, json).withExternalPlugins(plugins);
            RunId missing = new RunId("a-missing"), healthy = new RunId("b-healthy");
            persistPaused(missing, "missing", compiler, runs, plans, json);
            persistPaused(healthy, "healthy", compiler, runs, plans, json);
            var recovery = new RunRecoveryCoordinator(compiler, runs, plans, json, clock, new RunUsageLedger());
            List<RunRecoveryCoordinator.RecoveredRun> attached = new ArrayList<>();
            try {
                assertDoesNotThrow(() -> recovery.recover((id, reason) -> fail("恢复不应取消未过期 Run"),
                        attached::add, id -> fail("恢复不应解绑正常 Run")));

                assertEquals(List.of("missing", "healthy"), restoredPlugins);
                assertEquals(RunState.RECOVERY_BLOCKED_MISSING_EXTENSION, runs.find(missing).orElseThrow().snapshot().state());
                var failure = runs.eventsAfter(missing, 0).getLast();
                assertEquals("core.run.recovery_blocked", failure.type());
                assertEquals("LOCKED_EXECUTION_PLAN_UNAVAILABLE", failure.payload().path("code").asText());
                assertTrue(failure.payload().path("detail").asText().contains("原插件工件或配置不可用"));
                assertEquals(List.of(healthy), attached.stream().map(value -> value.stored().snapshot().id()).toList());
                assertEquals("healthy", attached.getFirst().plan().descriptor().pluginLocks().getFirst().pluginId());
                assertEquals(RunState.PAUSED, runs.find(healthy).orElseThrow().snapshot().state());
            } finally {
                attached.forEach(value -> value.plan().close());
            }
        }
    }

    private static void persistPaused(RunId id, String plugin, AgentCompiler compiler, JdbcRunStore runs,
                                      JdbcExecutionPlanStore plans, ObjectMapper json) {
        RunRequest request = runs.prepare(RunRequest.builder().agent(AgentDefinitionRef.latest("fixture"))
                .profile(RunProfileRef.latest("fixture")).source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", plugin)).input(InputBlock.text("继续"))
                .permissionCeiling(PermissionSet.NONE).budget(RunBudget.UNBOUNDED).build());
        try (ExecutionPlan plan = compiler.compile(request)) {
            var descriptor = plan.descriptor();
            plans.save(descriptor.id(), descriptor.extensionGeneration(), json.valueToTree(descriptor), descriptor.checksum());
            runs.create(id, plan.annotate(request), descriptor.id(), event("core.run.created", json));
            runs.append(id, Set.of(RunState.CREATED), RunState.PAUSED, event("core.run.paused", json), null, null);
        }
    }

    private static RunEventDraft event(String type, ObjectMapper json) {
        return new RunEventDraft(type, 1, "framework.core", null, null, json.createObjectNode());
    }
}
