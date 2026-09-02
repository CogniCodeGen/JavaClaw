package com.javaclaw.server.extension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeWorkerRuntimeFactoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void 未配置发行镜像时不授予KnowledgeWorker能力() {
        withImageProperty(
                null,
                () -> assertTrue(
                        KnowledgeWorkerRuntimeFactory.create(temporaryDirectory).isEmpty()));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void 完整镜像创建客户端和隔离临时目录() throws Exception {
        Path image = prepareImage(temporaryDirectory.resolve("image"), "java");
        Path data = Files.createDirectories(temporaryDirectory.resolve("data-v5"));

        withProperties(image, "Mac OS X", () -> {
            Optional<KnowledgeWorkerClient> created = KnowledgeWorkerRuntimeFactory.create(data);
            assertTrue(created.isPresent());
            created.orElseThrow().close();
        });

        assertTrue(Files.isDirectory(data.resolve("knowledge-worker/tmp")));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void Windows镜像使用JavaExe且错误标记失败关闭() throws Exception {
        Path windowsImage = prepareImage(temporaryDirectory.resolve("windows-image"), "java.exe");
        Path data = Files.createDirectories(temporaryDirectory.resolve("windows-data-v5"));
        withProperties(windowsImage, "Windows 11", () -> {
            try (KnowledgeWorkerClient ignored =
                    KnowledgeWorkerRuntimeFactory.create(data).orElseThrow()) {
                assertTrue(Files.isDirectory(data.resolve("knowledge-worker/tmp")));
            }
        });

        Path invalidImage = prepareImage(temporaryDirectory.resolve("invalid-image"), "java");
        Files.writeString(
                invalidImage.resolve("worker-image-v1.capability"),
                "worker-image-v1:browser",
                StandardCharsets.US_ASCII);
        withProperties(
                invalidImage,
                "Mac OS X",
                () -> assertThrows(IllegalStateException.class, () -> KnowledgeWorkerRuntimeFactory.create(data)));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void 缺失不完整和不安全镜像均拒绝() throws Exception {
        Path data = Files.createDirectories(temporaryDirectory.resolve("failure-data-v5"));
        Path missing = temporaryDirectory.resolve("missing");
        withProperties(
                missing,
                "Mac OS X",
                () -> assertThrows(IllegalStateException.class, () -> KnowledgeWorkerRuntimeFactory.create(data)));

        Path incomplete = Files.createDirectories(temporaryDirectory.resolve("incomplete"));
        Files.createDirectories(incomplete.resolve("bin"));
        Files.createDirectories(incomplete.resolve("app"));
        Files.writeString(
                incomplete.resolve("worker-image-v1.capability"),
                "worker-image-v1:knowledge",
                StandardCharsets.US_ASCII);
        withProperties(
                incomplete,
                "Mac OS X",
                () -> assertThrows(IllegalStateException.class, () -> KnowledgeWorkerRuntimeFactory.create(data)));

        Path unsafe = prepareImage(temporaryDirectory.resolve("unsafe"), "java");
        Files.delete(unsafe.resolve("worker-image-v1.capability"));
        Files.createSymbolicLink(
                unsafe.resolve("worker-image-v1.capability"), temporaryDirectory.resolve("outside-marker"));
        assertFalse(Files.isRegularFile(unsafe.resolve("worker-image-v1.capability")));
        withProperties(
                unsafe,
                "Mac OS X",
                () -> assertThrows(IllegalStateException.class, () -> KnowledgeWorkerRuntimeFactory.create(data)));
    }

    private static Path prepareImage(Path image, String javaName) throws Exception {
        Path bin = Files.createDirectories(image.resolve("bin"));
        Files.createDirectories(image.resolve("app"));
        Path java = Files.writeString(bin.resolve(javaName), "#!/bin/sh\nexit 0\n", StandardCharsets.US_ASCII);
        assertTrue(java.toFile().setExecutable(true, true) || Files.isExecutable(java));
        Files.writeString(
                image.resolve("worker-image-v1.capability"), "worker-image-v1:knowledge", StandardCharsets.US_ASCII);
        return image;
    }

    private static void withProperties(Path image, String osName, Runnable assertion) {
        String previousOs = System.getProperty("os.name");
        try {
            System.setProperty("os.name", osName);
            withImageProperty(image.toString(), assertion);
        } finally {
            restore("os.name", previousOs);
        }
    }

    private static void withImageProperty(String value, Runnable assertion) {
        String name = KnowledgeWorkerRuntimeFactory.IMAGE_ROOT_PROPERTY;
        String previous = System.getProperty(name);
        try {
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
            assertion.run();
        } finally {
            restore(name, previous);
        }
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
