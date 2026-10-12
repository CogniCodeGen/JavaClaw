package com.javaclaw.platform.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.core.ThreadLifecycleRegistry;
import com.javaclaw.framework.core.ThreadProjectionRegistry;
import com.javaclaw.infrastructure.memory.ThreadMemoryProjectionAdapter;
import com.javaclaw.memory.MemoryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class WorkspaceThreadConfiguration {
    @Bean ThreadMemoryProjectionAdapter threadMemoryProjectionAdapter(MemoryService memory, ObjectMapper json) {
        return new ThreadMemoryProjectionAdapter(memory, json);
    }
    @Bean(destroyMethod = "close") AutoCloseable threadMemoryRegistration(
            ThreadMemoryProjectionAdapter adapter, ThreadProjectionRegistry projections, ThreadLifecycleRegistry lifecycle,
            MemoryService memory, com.javaclaw.framework.store.JdbcThreadStore store,
            com.javaclaw.framework.api.ThreadClient threads,
            com.javaclaw.workflow.service.WorkflowService workflows,
            com.javaclaw.task.sdd.run.SddTaskManager sddTasks) {
        AutoCloseable projection = projections.register(adapter);
        AutoCloseable listener = lifecycle.register(adapter);
        AutoCloseable workflowListener = lifecycle.register(workflows);
        AutoCloseable sddListener = lifecycle.register(sddTasks);
        var owner = memory.defaultScope();
        try {
            for (var pending : store.pendingLifecycle(owner.workspaceId(), owner.userId())) {
                if (pending.status() == com.javaclaw.framework.api.ThreadStatus.FORKING) threads.resume(pending.scope());
                else threads.delete(pending.scope());
            }
        } catch (RuntimeException failure) {
            try { projection.close(); listener.close(); workflowListener.close(); sddListener.close(); }
            catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        return () -> { projection.close(); listener.close(); workflowListener.close(); sddListener.close(); };
    }
}
