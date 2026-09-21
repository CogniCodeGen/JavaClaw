package com.javaclaw.platform.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.core.*;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.store.*;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class ThreadRuntimeConfiguration {
    @Bean(destroyMethod = "close") com.javaclaw.application.agent.SubAgentApprovalObserver subAgentApprovalObserver(
            ObjectProvider<AgentClient> agents,
            @org.springframework.beans.factory.annotation.Qualifier("agentKernelExecutor") java.util.concurrent.Executor executor) {
        return new com.javaclaw.application.agent.SubAgentApprovalObserver(agents::getObject, executor);
    }
    @Bean com.javaclaw.infrastructure.agent.LegacyThreadImporter legacyThreadImporter(
            JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager transactions,
            ObjectMapper json, JdbcRunStore runs) {
        var importer = new com.javaclaw.infrastructure.agent.LegacyThreadImporter(jdbc, transactions, json, runs);
        importer.migrate();
        return importer;
    }
    @Bean JdbcThreadStore threadStore(JdbcRunStore runs) { return runs.threads(); }
    @Bean ThreadLifecycleRegistry threadLifecycleRegistry(JdbcTemplate jdbc,
            org.springframework.transaction.PlatformTransactionManager transactions,
            com.javaclaw.platform.json.JsonCodec json, WorkspaceManager workspaces) {
        var lifecycle = new ThreadLifecycleRegistry();
        lifecycle.register(new com.javaclaw.infrastructure.thread.SddThreadArtifactCleaner(jdbc, transactions, json));
        lifecycle.register(new com.javaclaw.infrastructure.memory.ThreadMemoryArtifactCleaner(workspaces.getGlobalDataPath()));
        return lifecycle;
    }
    @Bean ThreadProjectionRegistry threadProjectionRegistry() { return new ThreadProjectionRegistry(); }
    @Bean ThreadRolloutProjector threadRolloutProjector(JdbcTemplate jdbc, JdbcThreadStore threads,
                                                       ObjectMapper json, WorkspaceManager workspaces,
                                                       ThreadProjectionRegistry projections) {
        return new ThreadRolloutProjector(jdbc, threads, json, workspaces.getGlobalDataPath().resolve("rollouts"), projections);
    }
    @Bean ThreadClient threadClient(JdbcThreadStore threads, JdbcRunStore runs, AgentClient agents,
                                    ThreadLifecycleRegistry lifecycle, ThreadRolloutProjector rollouts) {
        return new DefaultThreadClient(threads, runs, agents, lifecycle, rollouts);
    }
    @Bean StepClient stepClient(JdbcRunStore runs) { return new RunStepQuery(runs); }
    @Bean(destroyMethod = "close") ThreadProjectionPump threadProjectionPump(
            ManagedTaskExecutor executor, ThreadRolloutProjector projector) {
        return new ThreadProjectionPump(executor, projector);
    }
    @Bean @Primary ModelTaskGateway maintenanceModelTasks(
            com.javaclaw.framework.springai.SpringAiModelTaskGateway delegate,
            JdbcRunStore runs, ObjectProvider<AgentClient> agents) {
        return new MaintenanceModelTaskGateway(delegate, runs, agents::getObject);
    }
}
