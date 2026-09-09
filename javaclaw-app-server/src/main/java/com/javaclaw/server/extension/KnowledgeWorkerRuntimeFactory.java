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
            return Optional.of(new KnowledgeWorkerClient(command(imageRoot, dataRoot), TIMEOUT));
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
        Path libraries = imageRoot.resolve("lib").toRealPath();
        requireInside(imageRoot, java, "Java runtime");
        requireInside(imageRoot, app, "Worker classpath");
        requireInside(imageRoot, libraries, "Java runtime libraries");
        if (!Files.isExecutable(java) || !Files.isDirectory(app) || !Files.isDirectory(libraries)) {
            throw new IOException("Knowledge Worker image is incomplete");
        }
        requireMarker(imageRoot.resolve("worker-image-v1.capability"), "worker-image-v1:knowledge");
        Path data = dataRoot.toRealPath();
        Path work = data.resolve("knowledge-worker").resolve("tmp");
        Files.createDirectories(work);
        work = work.toRealPath();
        requireInside(data, work, "Worker temporary directory");
        return new Layout(imageRoot, java, app, libraries, work);
    }

    static SandboxedWorkerCommand command(Path imageRoot, Path dataRoot) throws IOException {
        Layout layout = layout(imageRoot.toRealPath(), dataRoot);
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
                // 独立 jlink 镜像不在宿主 java.home 下；只读运行库也需要可执行映射，临时目录不授权。
                List.of(layout.java(), layout.libraries()),
                TIMEOUT,
                new ResourceLimits(1536L * 1024 * 1024, 20L * 1024 * 1024, 4, 512),
                Optional.empty());
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

    private record Layout(Path imageRoot, Path java, Path app, Path libraries, Path work) {}
}
