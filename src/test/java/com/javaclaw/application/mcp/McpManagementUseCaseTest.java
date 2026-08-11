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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void disabledSaveAndEnableTransitionsKeepConfigurationAndRuntimeConsistent() {
        FakeConfigurations configurations = new FakeConfigurations();
        FakeRuntime runtime = new FakeRuntime();
        McpManagementUseCase useCase = useCase(configurations, runtime);

        var saved = useCase.save(new SaveCommand("", "local", Transport.STDIO, " npx ",
                List.of(" ", "-y", " server "), Map.of(" PROFILE ", "test"),
                "", Map.of(), false));

        assertTrue(saved.runtimeSucceeded());
        Entry local = configurations.entries.getFirst();
        assertEquals("npx", local.command());
        assertEquals(List.of("-y", "server"), local.arguments());
        assertEquals(Map.of("PROFILE", "test"), local.environment());
        assertEquals(1, runtime.stopCalls);

        assertTrue(useCase.start("local").runtimeSucceeded());
        assertTrue(configurations.entries.getFirst().enabled());
        assertTrue(useCase.setEnabled("local", false).runtimeSucceeded());
        assertFalse(configurations.entries.getFirst().enabled());
        assertEquals(2, runtime.stopCalls);
    }

    @Test
    void editingAndMapNormalizationRejectAmbiguousConfiguration() {
        FakeConfigurations configurations = new FakeConfigurations();
        configurations.entries.add(new Entry("existing", Transport.STDIO, "java",
                List.of(), Map.of(), "", Map.of(), true));
        McpManagementUseCase useCase = useCase(configurations, new FakeRuntime());

        assertThrows(ValidationException.class, () -> useCase.save(new SaveCommand(
                "existing", "renamed", Transport.STDIO, "java", List.of(), Map.of(),
                "", Map.of(), true)));
        assertThrows(ValidationException.class, () -> useCase.save(new SaveCommand(
                "", "duplicate-keys", Transport.STDIO, "java", List.of(),
                orderedMap(" KEY ", "one", "KEY", "two"), "", Map.of(), true)));
        assertThrows(ValidationException.class, () -> useCase.save(new SaveCommand(
                "", "missing-command", Transport.STDIO, " ", List.of(), Map.of(),
                "", Map.of(), true)));
        assertThrows(ValidationException.class, () -> useCase.save(new SaveCommand(
                "", "missing-url", Transport.HTTP, "", List.of(), Map.of(),
                " ", Map.of(), true)));
        assertThrows(ValidationException.class, () -> useCase.save(new SaveCommand(
                "", "relative-url", Transport.HTTP, "", List.of(), Map.of(),
                "/mcp", Map.of(), true)));
        assertThrows(ValidationException.class, () -> useCase.save(new SaveCommand(
                "", "duplicate-headers", Transport.HTTP, "", List.of(), Map.of(),
                "https://example.test/mcp", orderedMap(" X-Test ", "one", "X-Test", "two"),
                true)));
    }

    @Test
    void runtimeOperationsExposeFailuresLogsTestsTemplatesAndObservation() throws Exception {
        FakeConfigurations configurations = new FakeConfigurations();
        configurations.entries.add(new Entry("remote", Transport.HTTP, "", List.of(), Map.of(),
                "https://example.test/mcp", Map.of(), true));
        FakeRuntime runtime = new FakeRuntime();
        McpManagementUseCase useCase = useCase(configurations, runtime);

        assertTrue(useCase.restart("remote").runtimeSucceeded());
        runtime.startSucceeds = false;
        assertEquals("offline", useCase.restart("remote").runtimeMessage());
        runtime.failureDetail = " ";
        assertEquals("服务器启动失败", useCase.restart("remote").runtimeMessage());

        var tested = useCase.test(new SaveCommand("", "candidate", Transport.HTTP, "",
                List.of(), Map.of(), "https://example.test/mcp", Map.of(), true));
        assertTrue(tested.success());
        assertEquals("demo", tested.serverName());
        assertEquals(List.of("line"), useCase.log("remote").stderrLines());
        assertEquals("demo", useCase.templates().getFirst().id());
        try (AutoCloseable observation = useCase.observeRuntime(() -> { })) {
            assertNotNull(observation);
        }
        assertThrows(NullPointerException.class, () -> useCase.observeRuntime(null));
    }

    @Test
    void importReportsPartialFailureAndRejectsEmptyDocuments() {
        FakeConfigurations configurations = new FakeConfigurations();
        FakeRuntime runtime = new FakeRuntime();
        McpImportPort mixed = (json, fallback) -> List.of(
                new Entry("one", Transport.HTTP, "", List.of(), Map.of(),
                        "https://one.example/mcp", Map.of(), true),
                new Entry("two", Transport.HTTP, "", List.of(), Map.of(),
                        "https://two.example/mcp", Map.of(), true),
                new Entry("paused", Transport.STDIO, "uvx", List.of("server"),
                        Map.of(), "", Map.of(), false));
        runtime.failNames.add("two");
        McpManagementUseCase useCase = new McpManagementUseCase(
                configurations, runtime, mixed, List::of);

        var preview = useCase.previewImport("{}", "fallback");
        assertEquals(3, preview.servers().size());
        var result = useCase.importJson("{}", "fallback");
        assertFalse(result.runtimeSucceeded());
        assertTrue(result.runtimeMessage().contains("1 个启动失败"));

        McpManagementUseCase empty = new McpManagementUseCase(
                configurations, runtime, (json, fallback) -> List.of(), List::of);
        assertThrows(ValidationException.class, () -> empty.importJson("{}", ""));
    }

    @Test
    void missingEntriesAndDeleteRaceUseExplicitNotFoundFailures() {
        FakeConfigurations configurations = new FakeConfigurations();
        configurations.entries.add(new Entry("gone", Transport.STDIO, "java",
                List.of(), Map.of(), "", Map.of(), true));
        configurations.deleteSucceeds = false;
        McpManagementUseCase useCase = useCase(configurations, new FakeRuntime());

        assertThrows(NotFoundException.class, () -> useCase.delete("gone"));
        assertThrows(NotFoundException.class, () -> useCase.start("missing"));
        assertThrows(NotFoundException.class, () -> useCase.restart("missing"));
        assertThrows(NotFoundException.class, () -> useCase.stop("missing"));
        assertThrows(NotFoundException.class, () -> useCase.log("missing"));
        assertThrows(ValidationException.class, () -> useCase.delete(" "));
    }

    @Test
    void constructorRejectsMissingPorts() {
        FakeConfigurations configurations = new FakeConfigurations();
        FakeRuntime runtime = new FakeRuntime();
        McpImportPort importer = (json, fallback) -> List.of();
        McpTemplatePort templates = List::of;

        assertThrows(NullPointerException.class,
                () -> new McpManagementUseCase(null, runtime, importer, templates));
        assertThrows(NullPointerException.class,
                () -> new McpManagementUseCase(configurations, null, importer, templates));
        assertThrows(NullPointerException.class,
                () -> new McpManagementUseCase(configurations, runtime, null, templates));
        assertThrows(NullPointerException.class,
                () -> new McpManagementUseCase(configurations, runtime, importer, null));
    }

    private static Map<String, String> orderedMap(
            String firstKey, String firstValue, String secondKey, String secondValue) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(firstKey, firstValue);
        values.put(secondKey, secondValue);
        return values;
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
        private boolean deleteSucceeds = true;

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
            return deleteSucceeds && entries.removeIf(entry -> entry.name().equals(name));
        }
        @Override public String storageDescription() { return "fake-h2"; }
    }

    private static final class FakeRuntime implements McpRuntimePort {
        private final Map<String, Status> statuses = new LinkedHashMap<>();
        private boolean startSucceeds = true;
        private int restartCalls;
        private int stopCalls;
        private String failureDetail = "offline";
        private final List<String> failNames = new ArrayList<>();

        @Override public Status status(String name) {
            return statuses.getOrDefault(name, new Status(State.STOPPED, List.of(), "", 0));
        }
        @Override public boolean start(Entry configuration) { return apply(configuration); }
        @Override public boolean restart(Entry configuration) { restartCalls++; return apply(configuration); }
        private boolean apply(Entry configuration) {
            boolean succeeds = startSucceeds && !failNames.contains(configuration.name());
            statuses.put(configuration.name(), succeeds
                    ? new Status(State.RUNNING, List.of(new Tool("search", "搜索")), "", 100)
                    : new Status(State.FAILED, List.of(), failureDetail, 0));
            return succeeds;
        }
        @Override public void stop(String name) {
            stopCalls++;
            statuses.put(name, new Status(State.STOPPED, List.of(), "", 0));
        }
        @Override public Test test(Entry configuration) {
            return new Test(true, List.of(new Tool("search", "搜索")), 10, "", "demo", "1");
        }
        @Override public List<String> stderr(String name) { return List.of("line"); }
        @Override public AutoCloseable observe(Runnable listener) { return () -> { }; }
    }
}
