package com.javaclaw.launcher;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupScriptsTest {
    @TempDir
    Path temporary;

    @Test
    void noBuildRejectsMissingArtifactsWithoutInvokingMaven() throws Exception {
        Path root = project();
        Result result = run(root, "--no-build");
        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("missing or incomplete"));
    }

    @Test
    void noBuildLaunchesFromAnotherWorkingDirectoryAndKeepsTheExitCode() throws Exception {
        Path root = project();
        stageDistribution(root, 7);
        Result result = run(root, "--no-build");
        assertEquals(7, result.exitCode());
        assertTrue(result.output().contains("distribution-started"));
        assertTrue(result.output().contains(root.toString()));
    }

    @Test
    void failedBuildNeverLaunchesTheExistingDistribution() throws Exception {
        Path root = project();
        stageDistribution(root, 0);
        Path tools = Files.createDirectories(root.resolve("test-tools"));
        executable(
                tools.resolve(windows() ? "mvn.cmd" : "mvn"),
                windows()
                        ? "@echo off\necho build-failed\nexit /b 17\n"
                        : "#!/bin/sh\nprintf '%s\\n' build-failed\nexit 17\n");
        Result result = run(root);
        assertEquals(17, result.exitCode());
        assertTrue(result.output().contains("build-failed"));
        assertFalse(result.output().contains("distribution-started"));
    }

    @Test
    void helpAndInvalidOptionsDoNotBuildOrLaunch() throws Exception {
        Path root = project();
        assertEquals(0, run(root, "--help").exitCode());
        assertEquals(2, run(root, "--unknown").exitCode());
        assertEquals(2, run(root, "--no-build", "--unknown").exitCode());
    }

    @Test
    void macPackagingChecksTheRealAppImageBeforeDmgAndPkgWrapping() throws Exception {
        String script = Files.readString(Path.of("src/main/packaging/package-macos.sh"));
        assertTrue(script.contains("--type app-image"));
        assertTrue(script.contains("$APP_LAUNCHER\" --help"));
        assertTrue(script.contains("--app-image \"$APP_IMAGE\""));
        assertTrue(script.contains("JavaClaw-app-image-smoke.log"));
        assertTrue(script.contains("-Djavaclaw.program.dir=$APPDIR"));
    }

    @Test
    void packagedLaunchersKeepDefaultStorageUnderTheirOwnProgramRoot() throws Exception {
        for (String name : List.of("javaclaw", "javaclaw-cli", "javaclaw.cmd", "javaclaw-cli.cmd")) {
            String launcher =
                    Files.readString(Path.of("src/main/distribution/bin").resolve(name));
            assertTrue(launcher.contains("JAVACLAW_PROGRAM_DIR"), name);
            assertFalse(launcher.contains("user.home"), name);
        }
    }

    private Path project() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("项目 with spaces"));
        String script = windows() ? "run.cmd" : "run.sh";
        Files.copy(Path.of("..").resolve(script), root.resolve(script));
        return root;
    }

    private void stageDistribution(Path root, int exitCode) throws Exception {
        Path distribution = root.resolve("javaclaw-packaging/target/distribution");
        Files.createDirectories(distribution.resolve("runtime/bin"));
        Files.createDirectories(distribution.resolve("lib"));
        Files.createDirectories(distribution.resolve("bin"));
        executable(distribution.resolve(windows() ? "runtime/bin/java.exe" : "runtime/bin/java"), "fixture");
        Files.writeString(distribution.resolve("lib/com.javaclaw.javaclaw-packaging.jar"), "fixture");
        executable(
                distribution.resolve(windows() ? "bin/javaclaw.cmd" : "bin/javaclaw"),
                windows()
                        ? "@echo off\necho distribution-started\necho program-dir=%JAVACLAW_PROGRAM_DIR%\nexit /b "
                                + exitCode + "\n"
                        : "#!/bin/sh\nprintf '%s\\n' distribution-started\nprintf 'program-dir=%s\\n' \"$JAVACLAW_PROGRAM_DIR\"\nexit "
                                + exitCode + "\n");
    }

    private Result run(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>(windows() ? List.of("cmd.exe", "/d", "/c") : List.of("/bin/sh"));
        command.add(root.resolve(windows() ? "run.cmd" : "run.sh").toString());
        command.addAll(List.of(args));
        Path output = temporary.resolve("script-output.txt");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(temporary.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        String path = builder.environment().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("PATH"))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElse("");
        builder.environment().remove("JAVACLAW_PROGRAM_DIR");
        builder.environment().put("PATH", root.resolve("test-tools") + File.pathSeparator + path);
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "startup script did not exit");
            return new Result(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
        }
    }

    private static void executable(Path path, String contents) throws Exception {
        Files.writeString(path, contents);
        assertTrue(path.toFile().setExecutable(true));
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private record Result(int exitCode, String output) {}
}
