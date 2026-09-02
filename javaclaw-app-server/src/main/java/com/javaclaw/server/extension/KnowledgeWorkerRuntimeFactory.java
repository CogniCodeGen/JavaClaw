package com.javaclaw.server.extension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.ResourceLimits;
import com.javaclaw.nativehost.sandbox.SandboxedWorkerCommand;

/** 从签名发行镜像的独立目录创建 Knowledge Worker；IDEA classpath 不会被隐式授予。 */
final class KnowledgeWorkerRuntimeFactory {
    static final String IMAGE_ROOT_PROPERTY = "javaclaw.knowledge.worker.image-root";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private KnowledgeWorkerRuntimeFactory() {}

    static Optional<KnowledgeWorkerClient> create(Path dataRoot) {
        String configured = System.getProperty(IMAGE_ROOT_PROPERTY, "").strip();
        if (configured.isEmpty()) {
            return Optional.empty();
        }
        try {
            Path imageRoot = Path.of(configured).toRealPath();
            Layout layout = layout(imageRoot, dataRoot);
            return Optional.of(new KnowledgeWorkerClient(command(layout), TIMEOUT));
        } catch (IOException failure) {
            throw new IllegalStateException("Knowledge Worker packaged runtime layout is invalid", failure);
        }
    }

    private static Layout layout(Path imageRoot, Path dataRoot) throws IOException {
        String javaName = System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("windows")
                ? "java.exe"
                : "java";
        Path java = imageRoot.resolve("bin").resolve(javaName).toRealPath();
        Path app = imageRoot.resolve("app").toRealPath();
        requireInside(imageRoot, java, "Java runtime");
        requireInside(imageRoot, app, "Worker classpath");
        if (!Files.isExecutable(java) || !Files.isDirectory(app)) {
            throw new IOException("Knowledge Worker image is incomplete");
        }
        requireMarker(imageRoot.resolve("worker-image-v1.capability"), "worker-image-v1:knowledge");
        Path data = dataRoot.toRealPath();
        Path work = data.resolve("knowledge-worker").resolve("tmp");
        Files.createDirectories(work);
        work = work.toRealPath();
        requireInside(data, work, "Worker temporary directory");
        return new Layout(imageRoot, java, app, work);
    }

    private static SandboxedWorkerCommand command(Layout layout) {
        List<String> argv = List.of(
                layout.java().toString(),
                "-XX:-UsePerfData",
                "-cp",
                layout.app().resolve("*").toString(),
                "com.javaclaw.knowledge.worker.KnowledgeWorkerMain");
        return new SandboxedWorkerCommand(
                "knowledge-worker",
                argv,
                layout.work(),
                Map.of("TMPDIR", layout.work().toString()),
                List.of(layout.imageRoot()),
                List.of(layout.work()),
                List.of(layout.java()),
                TIMEOUT,
                new ResourceLimits(1536L * 1024 * 1024, 20L * 1024 * 1024, 4, 512));
    }

    private static void requireInside(Path root, Path candidate, String name) throws IOException {
        if (!candidate.startsWith(root)) {
            throw new IOException(name + " escapes its configured root");
        }
    }

    private static void requireMarker(Path marker, String expected) throws IOException {
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Knowledge Worker image marker is missing or unsafe");
        }
        String actual = Files.readString(marker, java.nio.charset.StandardCharsets.US_ASCII)
                .strip();
        if (!expected.equals(actual)) {
            throw new IOException("Knowledge Worker image marker is invalid");
        }
    }

    private record Layout(Path imageRoot, Path java, Path app, Path work) {}
}
