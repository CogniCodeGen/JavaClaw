package com.javaclaw.nativehost.startup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserLoginStartupTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void macRegistrationEscapesLauncherAndRemovesExactPlist() throws Exception {
        Path launcher = Files.createFile(temporaryDirectory.resolve("Java&Claw"));
        UserLoginStartup startup =
                UserLoginStartup.createForTest("mac os x", temporaryDirectory, launcher, command -> 0);

        startup.setRequired(true);
        Path plist = temporaryDirectory.resolve("Library/LaunchAgents/com.javaclaw.app-server.plist");
        String content = Files.readString(plist);
        assertTrue(content.contains("Java&amp;Claw"));

        startup.setRequired(false);
        assertFalse(Files.exists(plist));
    }

    @Test
    void linuxRegistrationWritesUnitAndUsesUserSystemd() throws Exception {
        Path launcher = Files.createFile(temporaryDirectory.resolve("server"));
        java.util.ArrayList<List<String>> commands = new java.util.ArrayList<>();
        UserLoginStartup startup = UserLoginStartup.createForTest("linux", temporaryDirectory, launcher, command -> {
            commands.add(List.copyOf(command));
            return 0;
        });

        startup.setRequired(true);
        Path unit = temporaryDirectory.resolve(".config/systemd/user/javaclaw-app-server.service");
        assertTrue(Files.readString(unit).contains(launcher.toString()));
        startup.setRequired(false);

        assertFalse(Files.exists(unit));
        assertEquals("daemon-reload", commands.getFirst().getLast());
        assertEquals("enable", commands.get(1).get(2));
        assertEquals("disable", commands.get(2).get(2));
    }

    @Test
    void windowsRegistrationUsesOneExactTaskName() throws Exception {
        Path launcher = Files.createFile(temporaryDirectory.resolve("JavaClaw Server.exe"));
        java.util.ArrayList<List<String>> commands = new java.util.ArrayList<>();
        UserLoginStartup startup = UserLoginStartup.createForTest("windows", temporaryDirectory, launcher, command -> {
            commands.add(List.copyOf(command));
            return 0;
        });

        startup.setRequired(true);
        startup.setRequired(false);

        assertEquals(
                List.of("schtasks.exe", "/Create", "/TN", "JavaClaw App Server"),
                commands.getFirst().subList(0, 4));
        assertEquals(List.of("schtasks.exe", "/Delete", "/TN", "JavaClaw App Server", "/F"), commands.get(1));
    }

    @Test
    void unsupportedPlatformFailsOnlyWhenRegistrationIsRequested() {
        UserLoginStartup startup = UserLoginStartup.createForTest("plan 9", temporaryDirectory, null, command -> 0);

        startup.setRequired(false);

        assertThrows(IllegalStateException.class, () -> startup.setRequired(true));
    }

    @Test
    void registrationRequiresExistingAbsoluteLauncher() {
        UserLoginStartup missing = UserLoginStartup.createForTest("mac", temporaryDirectory, null, command -> 0);
        UserLoginStartup relative =
                UserLoginStartup.createForTest("mac", temporaryDirectory, Path.of("relative-launcher"), command -> 0);
        UserLoginStartup absent = UserLoginStartup.createForTest(
                "mac", temporaryDirectory, temporaryDirectory.resolve("absent"), command -> 0);

        assertThrows(IllegalStateException.class, () -> missing.setRequired(true));
        assertThrows(IllegalStateException.class, () -> relative.setRequired(true));
        assertThrows(IllegalStateException.class, () -> absent.setRequired(true));
    }

    @Test
    void statusHidesPathsAndReportsIdeaLauncherLimitation() throws Exception {
        UserLoginStartup unavailable = UserLoginStartup.createForTest("mac", temporaryDirectory, null, command -> 0);
        Path launcher = Files.createFile(temporaryDirectory.resolve("server"));
        UserLoginStartup available = UserLoginStartup.createForTest("mac", temporaryDirectory, launcher, command -> 0);

        assertFalse(unavailable.status(true).repairAvailable());
        assertTrue(unavailable.status(true).unavailableReason().orElseThrow().contains("IDEA"));
        assertTrue(available.status(true).repairAvailable());
        assertFalse(available.status(true).installed());
        available.setRequired(true);
        assertTrue(available.status(true).installed());
    }

    @Test
    void failedPlatformCommandStopsRegistration() throws Exception {
        Path launcher = Files.createFile(temporaryDirectory.resolve("server"));
        UserLoginStartup linux = UserLoginStartup.createForTest("linux", temporaryDirectory, launcher, command -> 3);
        UserLoginStartup windows =
                UserLoginStartup.createForTest("windows", temporaryDirectory, launcher, command -> 2);

        assertThrows(IllegalStateException.class, () -> linux.setRequired(true));
        assertThrows(IllegalStateException.class, () -> windows.setRequired(true));
    }

    @Test
    void removingAbsentLinuxUnitDoesNotInvokeSystemd() throws Exception {
        Path launcher = Files.createFile(temporaryDirectory.resolve("server"));
        java.util.ArrayList<List<String>> commands = new java.util.ArrayList<>();
        UserLoginStartup startup = UserLoginStartup.createForTest("linux", temporaryDirectory, launcher, command -> {
            commands.add(command);
            return 0;
        });

        startup.setRequired(false);

        assertTrue(commands.isEmpty());
    }

    @Test
    void linuxUnitEscapesQuotesAndBackslashes() throws Exception {
        Path launcher = Files.createFile(temporaryDirectory.resolve("server\\\"quoted"));
        UserLoginStartup startup = UserLoginStartup.createForTest("linux", temporaryDirectory, launcher, command -> 0);

        startup.setRequired(true);

        String unit = Files.readString(temporaryDirectory.resolve(".config/systemd/user/javaclaw-app-server.service"));
        assertTrue(unit.contains("server\\\\\\\"quoted"));
    }

    @Test
    void systemFactoryReadsEmptyAndConfiguredLauncherProperties() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalLauncher = System.getProperty(UserLoginStartup.LAUNCHER_PROPERTY);
        Path launcher = Files.createFile(temporaryDirectory.resolve("server"));
        try {
            System.setProperty("user.home", temporaryDirectory.toString());
            System.clearProperty(UserLoginStartup.LAUNCHER_PROPERTY);
            UserLoginStartup.fromSystemProperties();
            System.setProperty(UserLoginStartup.LAUNCHER_PROPERTY, launcher.toString());
            UserLoginStartup.fromSystemProperties();
        } finally {
            restore("user.home", originalHome);
            restore(UserLoginStartup.LAUNCHER_PROPERTY, originalLauncher);
        }
    }

    @Test
    void processCommandRunnerReturnsExitCodeAndWrapsStartFailure() {
        UserLoginStartup.ProcessCommandRunner runner = new UserLoginStartup.ProcessCommandRunner();
        String executable = System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("windows")
                ? "java.exe"
                : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);

        assertEquals(0, runner.run(List.of(java.toString(), "-version")));
        assertThrows(IllegalStateException.class, () -> runner.run(List.of("/javaclaw/missing-command")));
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
