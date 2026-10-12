package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.spi.ModelTaskRequest;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.framework.spi.RunEventDraft;
import com.javaclaw.framework.store.JdbcRunStore;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RunEventModelTaskAuditSinkTest {

    @Test
    void failedTaskPersistsRootCauseAndRedactsSecretsInBothMessages() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:model-audit-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(dataSource).initialize();
        var runs = new JdbcRunStore(new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource),
                new ObjectMapper().findAndRegisterModules(), Clock.systemUTC());
        RunId owner = RunId.random();
        RunRequest run = RunRequest.builder()
                .agent(AgentDefinitionRef.latest("test"))
                .profile(RunProfileRef.latest("test"))
                .source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "session"))
                .input(InputBlock.text("test task")).build();
        var empty = JsonNodeFactory.instance.objectNode();
        runs.create(owner, run, "test-plan", new RunEventDraft(
                "core.run.created", 1, "test", null, null, empty));
        runs.append(owner, Set.of(RunState.CREATED), RunState.RUNNING,
                new RunEventDraft("core.run.started", 1, "test", null, null, empty), null, null);
        var audit = new RunEventModelTaskAuditSink(runs);
        var request = new ModelTaskRequest("context.on_demand.select_v2", ModelTier.LIGHT,
                empty, List.of(), empty, owner, "context", Duration.ofSeconds(5),
                0, () -> false, false);

        audit.failed(request, new IllegalStateException("Request failed",
                new IOException("Connection refused")));
        audit.failed(request, new IllegalStateException("Request failed: token=opaque-token-value",
                new IOException("Connection refused: token=opaque-token-value")));

        List<RunEventEnvelope> failures = runs.eventsAfter(owner, 0).stream()
                .filter(event -> event.type().equals("core.model_task.failed")).toList();
        assertEquals(2, failures.size());
        var ordinary = failures.getFirst().payload();
        assertEquals("context.on_demand.select_v2", ordinary.path("purpose").asText());
        assertEquals("LIGHT", ordinary.path("tier").asText());
        assertEquals(IllegalStateException.class.getName(), ordinary.path("errorType").asText());
        assertEquals("Request failed", ordinary.path("message").asText());
        assertEquals(IOException.class.getName(), ordinary.path("causeType").asText(), ordinary.toPrettyString());
        assertEquals("Connection refused", ordinary.path("causeMessage").asText());

        var sensitive = failures.getLast().payload();
        assertEquals(IOException.class.getName(), sensitive.path("causeType").asText());
        assertEquals("<敏感内容已隐藏>", sensitive.path("message").asText());
        assertEquals("<敏感内容已隐藏>", sensitive.path("causeMessage").asText());
        assertFalse(sensitive.toString().contains("opaque-token-value"));
    }
}
