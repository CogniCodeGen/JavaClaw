package com.javaclaw.server.extension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.ResourceLimits;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.nativehost.sandbox.SandboxedWorkerCommand;

/** 只从签名发行镜像解析 Java/JShell，不使用 IDEA 或宿主 PATH 回退。 */
final class SkillResourceRuntimeLayout {
    static final String IMAGE_ROOT_PROPERTY = "javaclaw.skill.worker.image-root";
    static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(30);
    static final long MAXIMUM_OUTPUT_BYTES = 64L * 1024;

    private final Path imageRoot;
    private final Path java;
    private final Path jshell;
    private final Path workRoot;

    private SkillResourceRuntimeLayout(Path imageRoot, Path java, Path jshell, Path workRoot) {
        this.imageRoot = imageRoot;
        this.java = java;
        this.jshell = jshell;
        this.workRoot = workRoot;
    }

    static Optional<SkillResourceRuntimeLayout> discover(Path dataRoot) throws IOException {
        String configured = System.getProperty(IMAGE_ROOT_PROPERTY, "").strip();
        if (configured.isEmpty()) {
            return Optional.empty();
        }
        Path imageRoot = Path.of(configured).toRealPath();
        String executableSuffix = isWindows() ? ".exe" : "";
        Path java = imageRoot.resolve("bin/java" + executableSuffix).toRealPath();
        Path jshell = imageRoot.resolve("bin/jshell" + executableSuffix).toRealPath();
        requireInside(imageRoot, java, "Java runtime");
        requireInside(imageRoot, jshell, "JShell runtime");
        if (!Files.isExecutable(java) || !Files.isExecutable(jshell)) {
            throw new IOException("Skill runtime image does not contain executable Java and JShell launchers");
        }
        requireMarker(imageRoot.resolve("worker-image-v1.capability"), "worker-image-v1:skill");
        Path data = dataRoot.toRealPath();
        Path work = data.resolve("skill-worker/tmp");
        Files.createDirectories(work);
        work = work.toRealPath();
        requireInside(data, work, "Skill Worker temporary directory");
        return Optional.of(new SkillResourceRuntimeLayout(imageRoot, java, jshell, work));
    }

    Path createTaskDirectory() throws IOException {
        return Files.createTempDirectory(workRoot, "execution-").toRealPath();
    }

    SandboxedWorkerCommand execution(SkillContracts.Resource resource, Path source, List<String> arguments) {
        List<String> argv = SkillContracts.JAVA_SOURCE_MEDIA_TYPE.equals(resource.mediaType())
                ? javaCommand(source, arguments)
                : jshellCommand(source, arguments);
        return command("skill-resource", argv, source.getParent(), EXECUTION_TIMEOUT);
    }

    SandboxedWorkerCommand probe() {
        return command("skill-resource-probe", List.of(java.toString(), "-version"), workRoot, Duration.ofSeconds(5));
    }

    String sourceFileName(SkillContracts.Resource resource) {
        return SkillContracts.JAVA_SOURCE_MEDIA_TYPE.equals(resource.mediaType()) ? "Main.java" : "script.jsh";
    }

    private SandboxedWorkerCommand command(String id, List<String> argv, Path workingDirectory, Duration lifetime) {
        return new SandboxedWorkerCommand(
                id,
                argv,
                workingDirectory,
                Map.of("TMPDIR", workingDirectory.toString()),
                List.of(imageRoot, workingDirectory),
                List.of(workingDirectory),
                List.of(java, jshell),
                lifetime,
                new ResourceLimits(512L * 1024 * 1024, MAXIMUM_OUTPUT_BYTES, 4, 128));
    }

    private List<String> javaCommand(Path source, List<String> arguments) {
        ArrayList<String> argv = new ArrayList<>();
        argv.add(java.toString());
        argv.add("-XX:-UsePerfData");
        argv.add("--source");
        argv.add("25");
        argv.add(source.getFileName().toString());
        argv.addAll(arguments);
        return List.copyOf(argv);
    }

    private List<String> jshellCommand(Path source, List<String> arguments) {
        if (!arguments.isEmpty()) {
            throw new IllegalArgumentException("JShell resources do not accept command-line arguments");
        }
        return List.of(
                jshell.toString(),
                "--execution",
                "local",
                "--no-startup",
                source.getFileName().toString());
    }

    private static void requireInside(Path root, Path candidate, String name) throws IOException {
        if (!candidate.startsWith(root)) {
            throw new IOException(name + " escapes its configured root");
        }
    }

    private static void requireMarker(Path marker, String expected) throws IOException {
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Skill runtime image marker is missing or unsafe");
        }
        String actual = Files.readString(marker, StandardCharsets.US_ASCII).strip();
        if (!expected.equals(actual)) {
            throw new IOException("Skill runtime image marker is invalid");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
