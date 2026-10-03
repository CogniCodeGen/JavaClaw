package com.javaclaw.framework.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.RunEventDraft;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcRunStoreScopeHistoryTest {
    @Test
    void effectHistoryIncludesTerminalRunsAndRestrictsEveryScopeComponent() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:scope-effects-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(dataSource).initialize();
        JdbcRunStore store = new JdbcRunStore(new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource), new ObjectMapper().findAndRegisterModules(),
                Clock.systemUTC());
        RunScope scope = new RunScope("workspace", "user", "session");
        RunId active = create(store, scope);
        RunId cancelled = create(store, scope);
        RunId completed = create(store, scope);
        RunId failed = create(store, scope);
        terminal(store, cancelled, RunState.CANCELLED);
        terminal(store, completed, RunState.COMPLETED);
        terminal(store, failed, RunState.FAILED);
        create(store, new RunScope("other-workspace", "user", "session"));
        create(store, new RunScope("workspace", "other-user", "session"));
        create(store, new RunScope("workspace", "user", "other-session"));

        var history = store.scopeRuns(scope);
        assertEquals(Set.of(active, cancelled, completed, failed), history.stream()
                .map(run -> run.snapshot().id()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(history.stream().allMatch(run -> run.request().scope().equals(scope)));
        assertEquals(Set.of(RunState.CREATED, RunState.CANCELLED, RunState.COMPLETED, RunState.FAILED),
                history.stream().map(run -> run.snapshot().state())
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(store.scopeRuns(new RunScope("workspace", "user", "missing-session")).isEmpty());
    }

    private static RunId create(JdbcRunStore store, RunScope scope) {
        RunId id = RunId.random();
        RunRequest request = RunRequest.builder().agent(AgentDefinitionRef.latest("test.agent"))
                .profile(RunProfileRef.latest("chat")).scope(scope).source(InvocationSource.chat())
                .input(InputBlock.text("test input")).build();
        store.create(id, request, "test-plan", new RunEventDraft("core.run.created", 1,
                "framework.core", null, null, JsonNodeFactory.instance.objectNode()));
        return id;
    }

    private static void terminal(JdbcRunStore store, RunId id, RunState state) {
        String type = switch (state) {
            case CANCELLED -> "core.run.cancelled";
            case COMPLETED -> "core.run.completed";
            case FAILED -> "core.run.failed";
            default -> throw new IllegalArgumentException("not a terminal test state");
        };
        store.append(id, Set.of(RunState.CREATED), state, new RunEventDraft(type, 1,
                "framework.core", null, null, JsonNodeFactory.instance.objectNode()), null, null).orElseThrow();
    }
}
