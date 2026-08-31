package com.javaclaw.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementInteractionContractTest {
    private static final List<String> MANAGED_PAGES = List.of(
            "SettingsPane",
            "ProfilePane",
            "MemoryPane",
            "KnowledgePane",
            "SkillPane",
            "AutomationPane",
            "SchedulePane",
            "PluginPane",
            "McpPane",
            "SitePane",
            "AgentsInstructionsPane",
            "WorktreePane");

    @Test
    void everyPublishedManagementPageUsesTheSharedLifecycle() throws Exception {
        for (String page : MANAGED_PAGES) {
            String source = Files.readString(source("src/main/java/com/javaclaw/desktop/" + page + ".java"));
            assertTrue(source.contains("extends ManagedManagementPage"), page);
        }

        String lifecycle = Files.readString(source("src/main/java/com/javaclaw/desktop/ManagementPageLifecycle.java"));
        for (String operation : List.of(
                "dirtyProperty()",
                "canSaveProperty()",
                "loadStateProperty()",
                "requestSave(",
                "discard()",
                "cancelReads()",
                "dispose()")) {
            assertTrue(lifecycle.contains(operation), operation);
        }
    }

    @Test
    void navigationSaveConflictAndResourceBusyAreIndependentContracts() throws Exception {
        String controller = Files.readString(source("src/main/java/com/javaclaw/desktop/ManagementController.java"));
        assertTrue(controller.contains("resolveUnsavedChanges"));
        assertTrue(controller.contains("INITIAL_LOADING"));
        assertTrue(controller.contains("requestSave"));

        String session = Files.readString(source("src/main/java/com/javaclaw/desktop/ManagementEditSession.java"));
        assertTrue(session.contains("rpc.isConflict()"));
        assertTrue(session.contains("saveSucceeded"));
        assertTrue(session.contains("saveFailed"));
        assertTrue(session.contains("reloadServerVersion"));

        String forms = Files.readString(source("src/main/java/com/javaclaw/desktop/ManagementForms.java"));
        assertTrue(forms.contains("commandRunning"));
        assertTrue(forms.contains("放弃本地并重新加载"));

        String plugins = Files.readString(source("src/main/java/com/javaclaw/desktop/PluginPane.java"));
        assertTrue(plugins.contains("releaseAfterFailure"));
        assertTrue(plugins.contains("releaseAfterCompletion"));
        assertTrue(plugins.contains("releaseUninstalledBundle"));

        try (var paths = Files.walk(source("src/main/java/com/javaclaw/desktop"))) {
            String allSources = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (java.io.IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    })
                    .reduce("", (left, right) -> left + '\n' + right);
            assertFalse(allSources.contains("disableProperty().bind(model.busyProperty())"));
        }
    }

    private static Path source(String relative) {
        Path direct = Path.of(relative);
        if (Files.exists(direct)) {
            return direct;
        }
        return Path.of("javaclaw-desktop").resolve(relative);
    }
}
