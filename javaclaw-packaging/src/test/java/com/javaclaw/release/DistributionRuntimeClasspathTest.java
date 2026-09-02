package com.javaclaw.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributionRuntimeClasspathTest {
    @Test
    void 主发行与Worker镜像都携带共享运行时契约() throws Exception {
        Path distribution = packagingRoot().resolve("target/distribution");

        assertContract(distribution.resolve("lib"), "javaclaw-api-", "com/javaclaw/api/Workspace.class");
        assertContract(
                distribution.resolve("lib"),
                "javaclaw-builtin-contracts-",
                "com/javaclaw/builtin/contracts/PlanContracts.class");
        for (String worker : new String[] {"browser", "knowledge"}) {
            Path application = distribution.resolve("workers").resolve(worker).resolve("app");
            assertContract(application, "javaclaw-api-", "com/javaclaw/api/Workspace.class");
            assertContract(
                    application, "javaclaw-builtin-contracts-", "com/javaclaw/builtin/contracts/PlanContracts.class");
        }
    }

    private static void assertContract(Path directory, String prefix, String classEntry) throws Exception {
        Path jar = findJar(directory, prefix);
        try (JarFile archive = new JarFile(jar.toFile())) {
            assertNotNull(archive.getJarEntry(classEntry), () -> jar + " 缺少 " + classEntry);
        }
    }

    private static Path findJar(Path directory, String prefix) throws IOException {
        assertTrue(Files.isDirectory(directory), () -> "发行目录不存在：" + directory);
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(directory + " 缺少 " + prefix + "*.jar"));
        }
    }

    private static Path packagingRoot() {
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ""))
                .toAbsolutePath()
                .normalize();
        return Files.isDirectory(root.resolve("javaclaw-packaging")) ? root.resolve("javaclaw-packaging") : root;
    }
}
