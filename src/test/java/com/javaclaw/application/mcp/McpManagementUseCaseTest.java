package com.javaclaw.application.mcp;

import com.javaclaw.application.error.ConflictException;
import com.javaclaw.application.error.NotFoundException;
import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.mcp.McpConfigurationPort.Entry;
import com.javaclaw.application.mcp.McpManagementApplicationService.SaveCommand;
import com.javaclaw.application.mcp.McpManagementApplicationService.State;
import com.javaclaw.application.mcp.McpManagementApplicationService.Template;
import com.javaclaw.application.mcp.McpManagementApplicationService.Tool;
import com.javaclaw.application.mcp.McpManagementApplicationService.Transport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpManagementUseCaseTest {

    @Test
    void validatesPersistsAndAppliesRuntimeFromOneUseCase() {
        FakeConfigurations configurations = new FakeConfigurations();
        FakeRuntime runtime = new FakeRuntime();
        McpManagementUseCase useCase = useCase(configurations, runtime);

        var saved = useCase.save(new SaveCommand("", " remote ", Transport.HTTP, "",
                List.of(), Map.of(), "https://example.com/mcp", Map.of("Authorization", "secret"), true));

        assertTrue(saved.runtimeSucceeded());
        assertEquals("remote", saved.snapshot().servers().getFirst().name());
        assertEquals(1, runtime.restartCalls);
        assertThrows(UnsupportedOperationException.class,
                () -> saved.snapshot().servers().getFirst().headers().put("x", "y"));
        assertThrows(ConflictException.class, () -> useCase.save(new SaveCommand(
                "", "remote", Transport.HTTP, "", List.of(), Map.of(),
                "https://example.com/other", Map.of(), true)));
        assertThrows(ValidationException.class, () -> useCase.save(new SaveCommand(
                "", "bad", Transport.HTTP, "", List.of(), Map.of(),
                "ftp://example.com/mcp", Map.of(), true)));
        assertThrows(NotFoundException.class, () -> useCase.save(new SaveCommand(
                "deleted", "deleted", Transport.HTTP, "", List.of(), Map.of(),
                "https://example.com/mcp", Map.of(), true)));
    }

    @Test
    void enableStopDeleteAndFailureReturnFreshCombinedSnapshots() {
        FakeConfigurations configurations = new FakeConfigurations();
        configurations.entries.add(new Entry("local", Transport.STDIO, "npx",
                List.of("-y", "server"), Map.of(), "", Map.of(), false));
        FakeRuntime runtime = new FakeRuntime();
        McpManagementUseCase useCase = useCase(configurations, runtime);

        assertTrue(useCase.setEnabled("local", true).runtimeSucceeded());
        assertTrue(configurations.entries.getFirst().enabled());
        assertEquals(State.RUNNING, useCase.snapshot().require("local").state());

        assertTrue(useCase.stop("local").runtimeSucceeded());
        assertEquals(State.STOPPED, useCase.snapshot().require("local").state());

        runtime.startSucceeds = false;
        var failed = useCase.start("local");
        assertFalse(failed.runtimeSucceeded());
        assertEquals(State.FAILED, failed.snapshot().require("local").state());

        assertTrue(useCase.delete("local").snapshot().servers().isEmpty());
        assertThrows(NotFoundException.class, () -> useCase.delete("missing"));
    }

    @Test
    void importIsValidatedAndPersistedAsOneBatch() {
        FakeConfigurations configurations = new FakeConfigurations();
        FakeRuntime runtime = new FakeRuntime();
        McpImportPort importer = (json, fallback) -> List.of(
                new Entry("one", Transport.STDIO, "uvx", List.of("server"),
                        Map.of(), "", Map.of(), true),
                new Entry("two", Transport.HTTP, "", List.of(), Map.of(),
                        "https://example.com/mcp", Map.of(), false));
        McpManagementUseCase useCase = new McpManagementUseCase(configurations, runtime,
                importer, () -> List.of());

        var result = useCase.importJson("{}", "");

        assertEquals(1, configurations.batchCalls);
        assertEquals(2, result.snapshot().servers().size());
        assertEquals(1, runtime.restartCalls);
    }

    private static McpManagementUseCase useCase(
            FakeConfigurations configurations, FakeRuntime runtime) {
        return new McpManagementUseCase(configurations, runtime,
                (json, fallback) -> List.of(),
                () -> List.of(new Template("demo", "Demo", "description", "npx",
                        List.of("-y", "demo"), List.of("API_KEY"), "search")));
    }

    private static final class FakeConfigurations implements McpConfigurationPort {
        private final List<Entry> entries = new ArrayList<>();
        private int batchCalls;

        @Override public List<Entry> list() { return List.copyOf(entries); }
        @Override public void save(Entry entry) {
            entries.removeIf(existing -> existing.name().equals(entry.name()));
            entries.add(entry);
        }
        @Override public void saveAll(List<Entry> values) {
            batchCalls++;
            values.forEach(this::save);
        }
        @Override public boolean delete(String name) {
            return entries.removeIf(entry -> entry.name().equals(name));
        }
        @Override public String storageDescription() { return "fake-h2"; }
    }

    private static final class FakeRuntime implements McpRuntimePort {
        private final Map<String, Status> statuses = new LinkedHashMap<>();
        private boolean startSucceeds = true;
        private int restartCalls;

        @Override public Status status(String name) {
            return statuses.getOrDefault(name, new Status(State.STOPPED, List.of(), "", 0));
        }
        @Override public boolean start(Entry configuration) { return apply(configuration); }
        @Override public boolean restart(Entry configuration) { restartCalls++; return apply(configuration); }
        private boolean apply(Entry configuration) {
            statuses.put(configuration.name(), startSucceeds
                    ? new Status(State.RUNNING, List.of(new Tool("search", "搜索")), "", 100)
                    : new Status(State.FAILED, List.of(), "offline", 0));
            return startSucceeds;
        }
        @Override public void stop(String name) {
            statuses.put(name, new Status(State.STOPPED, List.of(), "", 0));
        }
        @Override public Test test(Entry configuration) {
            return new Test(true, List.of(new Tool("search", "搜索")), 10, "", "demo", "1");
        }
        @Override public List<String> stderr(String name) { return List.of("line"); }
        @Override public AutoCloseable observe(Runnable listener) { return () -> { }; }
    }
}
