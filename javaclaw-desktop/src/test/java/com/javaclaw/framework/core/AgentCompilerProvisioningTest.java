package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentDefinitionDraft;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunProfileDraft;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.DefinitionProvisioner;
import com.javaclaw.framework.spi.ExtensionContext;
import com.javaclaw.framework.spi.RunConstraints;
import com.javaclaw.framework.store.JdbcAgentDefinitionStore;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentCompilerProvisioningTest {

    @Test
    void retriesLatestDefinitionsAfterTheWorkspaceProvisionerRepairsThem() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:compiler-provision-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(dataSource).initialize();
        JdbcAgentDefinitionStore definitions = new JdbcAgentDefinitionStore(
                new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource),
                json, Clock.systemUTC());
        AtomicInteger repairs = new AtomicInteger();
        DefinitionProvisioner provisioner = new DefinitionProvisioner() {
            @Override
            public boolean ensureAgent(String workspaceId, AgentDefinitionRef reference) {
                repairs.incrementAndGet();
                AgentDefinitionDraft draft = new AgentDefinitionDraft(
                        reference.id(), "Repaired", "workspace:high", Map.of("system", "test"),
                        Map.of(), JsonNodeFactory.instance.objectNode(),
                        JsonNodeFactory.instance.objectNode(), RunBudget.UNBOUNDED,
                        JsonNodeFactory.instance.objectNode(), Map.of());
                definitions.saveAgentDraft(workspaceId, draft, false);
                definitions.publishAgent(workspaceId, draft.id());
                return true;
            }

            @Override
            public boolean ensureProfile(String workspaceId, RunProfileRef reference) {
                repairs.incrementAndGet();
                RunProfileDraft draft = new RunProfileDraft(
                        reference.id(), "Repaired", PermissionSet.UNRESTRICTED,
                        RunBudget.UNBOUNDED, Map.of(), JsonNodeFactory.instance.objectNode());
                definitions.saveProfileDraft(workspaceId, draft, false);
                definitions.publishProfile(workspaceId, draft.id());
                return true;
            }
        };

        try (ExtensionManager extensions = new ExtensionManager(new ExtensionContext(
                Clock.systemUTC(), Runnable::run,
                request -> CompletableFuture.failedFuture(
                        new AssertionError("model task not expected"))))) {
            AgentCompiler compiler = new AgentCompiler(
                    definitions, extensions, json,
                    new RunConstraints(PermissionSet.UNRESTRICTED, RunBudget.UNBOUNDED),
                    provisioner);
            RunRequest request = RunRequest.builder()
                    .agent(AgentDefinitionRef.latest("system.default"))
                    .profile(RunProfileRef.latest("chat"))
                    .source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", "session"))
                    .input(InputBlock.text("hello"))
                    .permissionCeiling(PermissionSet.UNRESTRICTED)
                    .budget(RunBudget.UNBOUNDED)
                    .build();

            try (ExecutionPlan plan = compiler.compile(request)) {
                assertEquals(1, plan.descriptor().definition().version());
                assertEquals(1, plan.descriptor().profile().version());
            }
            assertEquals(2, repairs.get());
        }
    }
}
