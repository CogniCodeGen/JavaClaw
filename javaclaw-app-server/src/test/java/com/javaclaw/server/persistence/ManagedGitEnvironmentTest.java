package com.javaclaw.server.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedGitEnvironmentTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void configuredExecutableAndVariablesIgnoreUserGitConfiguration() throws Exception {
        Path configured = temporaryDirectory.resolve("bin/git");
        String before = System.getProperty("javaclaw.git.executable");
        System.setProperty("javaclaw.git.executable", configured.toString());
        try {
            assertEquals(configured.toAbsolutePath().normalize(), ManagedGitEnvironment.executable());
        } finally {
            restoreProperty("javaclaw.git.executable", before);
        }

        Path managedRoot = Files.createDirectory(temporaryDirectory.resolve("managed"));
        Map<String, String> variables = ManagedGitEnvironment.variables(Path.of("/usr/bin/git"), managedRoot);

        assertEquals("1", variables.get("GIT_CONFIG_NOSYSTEM"));
        assertEquals("0", variables.get("GIT_TERMINAL_PROMPT"));
        assertEquals("/usr/bin", variables.get("PATH"));
        assertTrue(Files.isRegularFile(Path.of(variables.get("GIT_CONFIG_GLOBAL"))));
        assertEquals(0, Files.size(Path.of(variables.get("GIT_CONFIG_GLOBAL"))));
        assertTrue(Files.isDirectory(Path.of(variables.get("GIT_CONFIG_VALUE_0"))));
        assertEquals(variables, ManagedGitEnvironment.variables(Path.of("/usr/bin/git"), managedRoot));
    }

    @Test
    void tamperedEmptyConfigurationAndHooksFailClosed() throws Exception {
        Path configRoot = Files.createDirectory(temporaryDirectory.resolve("config-root"));
        ManagedGitEnvironment.variables(Path.of("/usr/bin/git"), configRoot);
        Files.writeString(configRoot.resolve(".gitconfig-empty"), "[credential]\n");
        assertThrows(
                PersistenceException.class, () -> ManagedGitEnvironment.variables(Path.of("/usr/bin/git"), configRoot));

        Path hooksRoot = Files.createDirectory(temporaryDirectory.resolve("hooks-root"));
        ManagedGitEnvironment.variables(Path.of("/usr/bin/git"), hooksRoot);
        Files.writeString(hooksRoot.resolve("hooks-empty/pre-commit"), "blocked");
        assertThrows(
                PersistenceException.class, () -> ManagedGitEnvironment.variables(Path.of("/usr/bin/git"), hooksRoot));
    }

    @Test
    void missingManagedRootAndUnsafeCommonRootsAreRejected() {
        Path missing = temporaryDirectory.resolve("missing/root");
        assertThrows(
                PersistenceException.class, () -> ManagedGitEnvironment.variables(Path.of("/usr/bin/git"), missing));

        Path first = temporaryDirectory.resolve("workspace/source");
        Path second = temporaryDirectory.resolve("workspace/isolation");
        assertEquals(
                temporaryDirectory.resolve("workspace").toAbsolutePath().normalize(),
                ManagedGitEnvironment.commonManagedRoot(first, second));
        assertThrows(
                PersistenceException.class,
                () -> ManagedGitEnvironment.commonManagedRoot(Path.of("/alpha/source"), Path.of("/beta/isolation")));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void executableFallbackAlwaysReturnsAPlatformCandidate() {
        String configured = System.getProperty("javaclaw.git.executable");
        System.clearProperty("javaclaw.git.executable");
        try {
            Path executable = ManagedGitEnvironment.executable();
            assertFalse(executable.toString().isBlank());
            assertTrue(executable.isAbsolute());
        } finally {
            restoreProperty("javaclaw.git.executable", configured);
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void executableSelectionKeepsWindowsAndUnixFallbacksDeterministic() {
        String configured = System.getProperty("javaclaw.git.executable");
        String operatingSystem = System.getProperty("os.name");
        System.clearProperty("javaclaw.git.executable");
        try {
            System.setProperty("os.name", "Windows 11");
            assertTrue(
                    ManagedGitEnvironment.executable().toString().toLowerCase().contains("git.exe"));

            System.setProperty("os.name", "Plan 9");
            assertEquals(Path.of("/usr/bin/git"), ManagedGitEnvironment.executable());
        } finally {
            restoreProperty("javaclaw.git.executable", configured);
            restoreProperty("os.name", operatingSystem);
        }
    }

    @Test
    void symbolicConfigurationAndHooksAreRejectedBeforeGitCanReadThem() throws Exception {
        Path outsideConfig = Files.writeString(temporaryDirectory.resolve("outside-config"), "");
        Path configRoot = Files.createDirectory(temporaryDirectory.resolve("linked-config-root"));
        Files.createSymbolicLink(configRoot.resolve(".gitconfig-empty"), outsideConfig);
        assertThrows(
                PersistenceException.class, () -> ManagedGitEnvironment.variables(Path.of("/usr/bin/git"), configRoot));

        Path outsideHooks = Files.createDirectory(temporaryDirectory.resolve("outside-hooks"));
        Path hooksRoot = Files.createDirectory(temporaryDirectory.resolve("linked-hooks-root"));
        Files.createSymbolicLink(hooksRoot.resolve("hooks-empty"), outsideHooks);
        assertThrows(
                PersistenceException.class, () -> ManagedGitEnvironment.variables(Path.of("/usr/bin/git"), hooksRoot));
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
