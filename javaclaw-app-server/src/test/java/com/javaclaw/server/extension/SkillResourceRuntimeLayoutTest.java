package com.javaclaw.server.extension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.nativehost.sandbox.SandboxedWorkerCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillResourceRuntimeLayoutTest {
    private static final String DIGEST = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    private final String originalImageRoot = System.getProperty(SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY);

    @AfterEach
    void 恢复发行镜像配置() {
        restoreImageRoot();
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void 完整发行镜像生成有界Java与JShell命令() throws Exception {
        Path image = prepareImage("valid-image", "worker-image-v1:skill");
        Path data = Files.createDirectories(temporaryDirectory.resolve("data-v5"));
        System.setProperty(SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY, image.toString());

        SkillResourceRuntimeLayout layout =
                SkillResourceRuntimeLayout.discover(data).orElseThrow();
        Path task = layout.createTaskDirectory();
        Path javaSource = task.resolve("Main.java");
        Path scriptSource = task.resolve("script.jsh");
        SkillContracts.Resource javaResource = resource("java", SkillContracts.JAVA_SOURCE_MEDIA_TYPE);
        SkillContracts.Resource jshellResource = resource("jshell", SkillContracts.JSHELL_MEDIA_TYPE);

        SandboxedWorkerCommand javaCommand = layout.execution(javaResource, javaSource, List.of("one", "two"));
        SandboxedWorkerCommand jshellCommand = layout.execution(jshellResource, scriptSource, List.of());
        SandboxedWorkerCommand probe = layout.probe();

        assertEquals("Main.java", layout.sourceFileName(javaResource));
        assertEquals("script.jsh", layout.sourceFileName(jshellResource));
        assertEquals(
                List.of("-XX:-UsePerfData", "--source", "25", "Main.java", "one", "two"),
                javaCommand.argv().subList(1, javaCommand.argv().size()));
        assertEquals(
                List.of("--execution", "local", "--no-startup", "script.jsh"),
                jshellCommand.argv().subList(1, jshellCommand.argv().size()));
        assertEquals("skill-resource", javaCommand.id());
        assertEquals(task.toRealPath(), javaCommand.workingDirectory());
        assertEquals(task.toString(), javaCommand.environment().get("TMPDIR"));
        assertEquals(SkillResourceRuntimeLayout.EXECUTION_TIMEOUT, javaCommand.lifetime());
        assertEquals(
                SkillResourceRuntimeLayout.MAXIMUM_OUTPUT_BYTES,
                javaCommand.limits().outputBytes());
        assertEquals(List.of("-version"), probe.argv().subList(1, probe.argv().size()));
        assertEquals("skill-resource-probe", probe.id());
        assertTrue(javaCommand.readRoots().contains(image.toRealPath()));
        assertTrue(javaCommand.writeRoots().contains(task.toRealPath()));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void JShell拒绝命令行参数() throws Exception {
        Path image = prepareImage("jshell-image", "worker-image-v1:skill");
        Path data = Files.createDirectories(temporaryDirectory.resolve("jshell-data-v5"));
        System.setProperty(SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY, image.toString());
        SkillResourceRuntimeLayout layout =
                SkillResourceRuntimeLayout.discover(data).orElseThrow();
        Path source = layout.createTaskDirectory().resolve("script.jsh");

        assertThrows(
                IllegalArgumentException.class,
                () -> layout.execution(resource("script", SkillContracts.JSHELL_MEDIA_TYPE), source, List.of("arg")));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void 缺失错误与符号链接回执均失败关闭() throws Exception {
        Path data = Files.createDirectories(temporaryDirectory.resolve("invalid-data-v5"));
        Path missing = prepareImage("missing-marker", null);
        System.setProperty(SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY, missing.toString());
        assertThrows(java.io.IOException.class, () -> SkillResourceRuntimeLayout.discover(data));

        Path invalid = prepareImage("invalid-marker", "worker-image-v1:other");
        System.setProperty(SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY, invalid.toString());
        assertThrows(java.io.IOException.class, () -> SkillResourceRuntimeLayout.discover(data));

        Path unsafe = prepareImage("unsafe-marker", null);
        Path target = Files.writeString(
                temporaryDirectory.resolve("marker-target"), "worker-image-v1:skill", StandardCharsets.US_ASCII);
        Files.createSymbolicLink(unsafe.resolve("worker-image-v1.capability"), target);
        System.setProperty(SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY, unsafe.toString());
        assertThrows(java.io.IOException.class, () -> SkillResourceRuntimeLayout.discover(data));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void 不可执行的Java或JShell启动器被拒绝() throws Exception {
        Path image = prepareImage("non-executable", "worker-image-v1:skill");
        Path java = image.resolve("bin").resolve(executableName("java"));
        assertTrue(java.toFile().setExecutable(false, false) || !Files.isExecutable(java));
        System.setProperty(SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY, image.toString());

        assertThrows(
                java.io.IOException.class,
                () -> SkillResourceRuntimeLayout.discover(
                        Files.createDirectories(temporaryDirectory.resolve("non-executable-data-v5"))));
    }

    private Path prepareImage(String name, String marker) throws Exception {
        Path image = Files.createDirectories(temporaryDirectory.resolve(name));
        Path bin = Files.createDirectories(image.resolve("bin"));
        createExecutable(bin.resolve(executableName("java")));
        createExecutable(bin.resolve(executableName("jshell")));
        if (marker != null) {
            Files.writeString(image.resolve("worker-image-v1.capability"), marker, StandardCharsets.US_ASCII);
        }
        return image;
    }

    private static void createExecutable(Path path) throws Exception {
        Files.writeString(path, "executable", StandardCharsets.US_ASCII);
        assertTrue(path.toFile().setExecutable(true, true) || Files.isExecutable(path));
    }

    private static SkillContracts.Resource resource(String id, String mediaType) {
        return new SkillContracts.Resource(id, mediaType, DIGEST, true);
    }

    private static String executableName(String basename) {
        return System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("windows")
                ? basename + ".exe"
                : basename;
    }

    private void restoreImageRoot() {
        if (originalImageRoot == null) {
            System.clearProperty(SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY);
        } else {
            System.setProperty(SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY, originalImageRoot);
        }
    }
}
