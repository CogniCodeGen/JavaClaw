package com.javaclaw.server.extension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import com.javaclaw.browser.client.BrowserWorkerClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserWorkerRuntimeFactoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void ideaClasspath未注入发行镜像时不创建Browser能力() {
        String name = BrowserWorkerRuntimeFactory.IMAGE_ROOT_PROPERTY;
        String previous = System.getProperty(name);
        try {
            System.clearProperty(name);
            assertTrue(BrowserWorkerRuntimeFactory.create(temporaryDirectory).isEmpty());
        } finally {
            restore(name, previous);
        }
    }

    @Test
    void 登录回执不能替代OAuth回执且错误平台回执失败关闭() throws Exception {
        Path image = temporaryDirectory.toRealPath();
        Files.writeString(
                image.resolve("browser-login-v1.capability"),
                "browser-login-v1:" + platformId(),
                StandardCharsets.US_ASCII);

        assertTrue(BrowserWorkerRuntimeFactory.capabilityVerified(
                image, "browser-login-v1.capability", "browser-login-v1"));
        assertFalse(BrowserWorkerRuntimeFactory.capabilityVerified(
                image, "browser-oauth-v1.capability", "browser-oauth-v1"));

        Files.writeString(
                image.resolve("browser-oauth-v1.capability"),
                "browser-oauth-v1:wrong-platform",
                StandardCharsets.US_ASCII);
        assertFalse(BrowserWorkerRuntimeFactory.capabilityVerified(
                image, "browser-oauth-v1.capability", "browser-oauth-v1"));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void 完整签名镜像构造Browser客户端并创建私有运行目录() throws Exception {
        String imageProperty = BrowserWorkerRuntimeFactory.IMAGE_ROOT_PROPERTY;
        String previousImage = System.getProperty(imageProperty);
        String previousOs = System.getProperty("os.name");
        Path image = temporaryDirectory.resolve("image");
        Path data = Files.createDirectories(temporaryDirectory.resolve("data-v5"));
        prepareImage(image, "java", "macos");
        try {
            System.setProperty(imageProperty, image.toString());
            System.setProperty("os.name", "Mac OS X");

            Optional<BrowserWorkerClient> created = BrowserWorkerRuntimeFactory.create(data);

            assertTrue(created.isPresent());
            created.orElseThrow().close();
            assertTrue(Files.isDirectory(data.resolve("browser-worker/tmp")));
            assertTrue(Files.isDirectory(data.resolve("browser-worker/control")));
        } finally {
            restore(imageProperty, previousImage);
            restore("os.name", previousOs);
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void 缺失镜像与WindowsLinux能力回执按平台失败关闭() throws Exception {
        String imageProperty = BrowserWorkerRuntimeFactory.IMAGE_ROOT_PROPERTY;
        String previousImage = System.getProperty(imageProperty);
        String previousOs = System.getProperty("os.name");
        try {
            System.setProperty(
                    imageProperty, temporaryDirectory.resolve("missing-image").toString());
            assertThrows(IllegalStateException.class, () -> BrowserWorkerRuntimeFactory.create(temporaryDirectory));

            Path markers = Files.createDirectories(temporaryDirectory.resolve("markers"))
                    .toRealPath();
            System.setProperty("os.name", "Windows 11");
            Files.writeString(
                    markers.resolve("browser-login-v1.capability"),
                    "browser-login-v1:windows",
                    StandardCharsets.US_ASCII);
            assertTrue(BrowserWorkerRuntimeFactory.capabilityVerified(
                    markers, "browser-login-v1.capability", "browser-login-v1"));

            System.setProperty("os.name", "Plan 9");
            Files.writeString(
                    markers.resolve("browser-login-v1.capability"),
                    "browser-login-v1:linux",
                    StandardCharsets.US_ASCII);
            assertTrue(BrowserWorkerRuntimeFactory.capabilityVerified(
                    markers, "browser-login-v1.capability", "browser-login-v1"));
            assertFalse(BrowserWorkerRuntimeFactory.capabilityVerified(
                    markers, "../browser-login-v1.capability", "browser-login-v1"));
            assertEquals("Plan 9", System.getProperty("os.name"));
        } finally {
            restore(imageProperty, previousImage);
            restore("os.name", previousOs);
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void Windows发行布局只接受JavaExe与对应平台回执() throws Exception {
        String imageProperty = BrowserWorkerRuntimeFactory.IMAGE_ROOT_PROPERTY;
        String previousImage = System.getProperty(imageProperty);
        String previousOs = System.getProperty("os.name");
        Path image = temporaryDirectory.resolve("windows-image");
        Path data = Files.createDirectories(temporaryDirectory.resolve("windows-data-v5"));
        prepareImage(image, "java.exe", "windows");
        try {
            System.setProperty(imageProperty, image.toString());
            System.setProperty("os.name", "Windows 11");

            BrowserWorkerClient client =
                    BrowserWorkerRuntimeFactory.create(data).orElseThrow();

            assertTrue(client.interactiveLoginAvailable());
            assertTrue(client.oauthAvailable());
            client.close();
        } finally {
            restore(imageProperty, previousImage);
            restore("os.name", previousOs);
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void 镜像与Playwright回执必须精确匹配锁定版本() throws Exception {
        String imageProperty = BrowserWorkerRuntimeFactory.IMAGE_ROOT_PROPERTY;
        String previousImage = System.getProperty(imageProperty);
        String previousOs = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Mac OS X");
            Path invalidImage = temporaryDirectory.resolve("invalid-image-marker");
            prepareImage(invalidImage, "java", "macos");
            Files.writeString(
                    invalidImage.resolve("worker-image-v1.capability"),
                    "worker-image-v1:other",
                    StandardCharsets.US_ASCII);
            System.setProperty(imageProperty, invalidImage.toString());
            assertThrows(
                    IllegalStateException.class,
                    () -> BrowserWorkerRuntimeFactory.create(
                            Files.createDirectories(temporaryDirectory.resolve("invalid-image-data-v5"))));

            Path invalidPlaywright = temporaryDirectory.resolve("invalid-playwright-marker");
            prepareImage(invalidPlaywright, "java", "macos");
            Files.writeString(
                    invalidPlaywright.resolve("browser/.javaclaw-playwright-version"),
                    "playwright:0.0.0",
                    StandardCharsets.US_ASCII);
            System.setProperty(imageProperty, invalidPlaywright.toString());
            assertThrows(
                    IllegalStateException.class,
                    () -> BrowserWorkerRuntimeFactory.create(
                            Files.createDirectories(temporaryDirectory.resolve("invalid-playwright-data-v5"))));
        } finally {
            restore(imageProperty, previousImage);
            restore("os.name", previousOs);
        }
    }

    @Test
    void 能力回执目录与符号链接都不可信() throws Exception {
        Path image = Files.createDirectories(temporaryDirectory.resolve("unsafe-capability-image"))
                .toRealPath();
        Files.createDirectory(image.resolve("directory.capability"));
        assertFalse(BrowserWorkerRuntimeFactory.capabilityVerified(image, "directory.capability", "browser-login-v1"));

        Path target = Files.writeString(
                temporaryDirectory.resolve("capability-target"),
                "browser-login-v1:" + platformId(),
                StandardCharsets.US_ASCII);
        Files.createSymbolicLink(image.resolve("linked.capability"), target);
        assertFalse(BrowserWorkerRuntimeFactory.capabilityVerified(image, "linked.capability", "browser-login-v1"));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void Browser镜像逐项拒绝不可执行Java非目录Classpath和Browser根() throws Exception {
        String imageProperty = BrowserWorkerRuntimeFactory.IMAGE_ROOT_PROPERTY;
        String previousImage = System.getProperty(imageProperty);
        String previousOs = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Mac OS X");
            Path data = Files.createDirectories(temporaryDirectory.resolve("incomplete-data"));

            Path noExecutable = incompleteImage("no-executable", false, true, true);
            System.setProperty(imageProperty, noExecutable.toString());
            assertThrows(IllegalStateException.class, () -> BrowserWorkerRuntimeFactory.create(data));

            Path fileClasspath = incompleteImage("file-classpath", true, false, true);
            System.setProperty(imageProperty, fileClasspath.toString());
            assertThrows(IllegalStateException.class, () -> BrowserWorkerRuntimeFactory.create(data));

            Path fileBrowser = incompleteImage("file-browser", true, true, false);
            System.setProperty(imageProperty, fileBrowser.toString());
            assertThrows(IllegalStateException.class, () -> BrowserWorkerRuntimeFactory.create(data));
        } finally {
            restore(imageProperty, previousImage);
            restore("os.name", previousOs);
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void 客户端能力同时受签名回执和本地显示设施收窄() throws Exception {
        String imageProperty = BrowserWorkerRuntimeFactory.IMAGE_ROOT_PROPERTY;
        String previousImage = System.getProperty(imageProperty);
        String previousOs = System.getProperty("os.name");
        try {
            Path macImage = temporaryDirectory.resolve("mac-no-login-capability");
            prepareImage(macImage, "java", "macos");
            Files.delete(macImage.resolve("browser-login-v1.capability"));
            System.setProperty("os.name", "Mac OS X");
            System.setProperty(imageProperty, macImage.toString());
            try (BrowserWorkerClient client = BrowserWorkerRuntimeFactory.create(
                            Files.createDirectories(temporaryDirectory.resolve("mac-capability-data")))
                    .orElseThrow()) {
                assertFalse(client.interactiveLoginAvailable());
                assertTrue(client.oauthAvailable());
            }

            Path linuxImage = temporaryDirectory.resolve("linux-no-display");
            prepareImage(linuxImage, "java", "linux");
            System.setProperty("os.name", "Plan 9");
            System.setProperty(imageProperty, linuxImage.toString());
            try (BrowserWorkerClient client = BrowserWorkerRuntimeFactory.create(
                            Files.createDirectories(temporaryDirectory.resolve("linux-capability-data")))
                    .orElseThrow()) {
                assertFalse(client.interactiveLoginAvailable());
                assertFalse(client.oauthAvailable());
            }
        } finally {
            restore(imageProperty, previousImage);
            restore("os.name", previousOs);
        }
    }

    private Path incompleteImage(String name, boolean executable, boolean appDirectory, boolean browserDirectory)
            throws Exception {
        Path image = Files.createDirectories(temporaryDirectory.resolve(name));
        Path bin = Files.createDirectories(image.resolve("bin"));
        Path java = Files.writeString(bin.resolve("java"), "#!/bin/sh\nexit 0\n", StandardCharsets.US_ASCII);
        if (executable) {
            assertTrue(java.toFile().setExecutable(true, true) || Files.isExecutable(java));
        }
        if (appDirectory) {
            Files.createDirectory(image.resolve("app"));
        } else {
            Files.writeString(image.resolve("app"), "not a directory");
        }
        if (browserDirectory) {
            Files.createDirectory(image.resolve("browser"));
        } else {
            Files.writeString(image.resolve("browser"), "not a directory");
        }
        return image;
    }

    private static void prepareImage(Path image, String javaName, String platform) throws Exception {
        Path bin = Files.createDirectories(image.resolve("bin"));
        Files.createDirectories(image.resolve("app"));
        Path browser = Files.createDirectories(image.resolve("browser"));
        Path java = Files.writeString(bin.resolve(javaName), "#!/bin/sh\nexit 0\n", StandardCharsets.US_ASCII);
        assertTrue(java.toFile().setExecutable(true, true) || Files.isExecutable(java));
        Files.writeString(
                image.resolve("worker-image-v1.capability"), "worker-image-v1:browser", StandardCharsets.US_ASCII);
        Files.writeString(
                browser.resolve(".javaclaw-playwright-version"), "playwright:1.52.0", StandardCharsets.US_ASCII);
        Files.writeString(
                image.resolve("browser-login-v1.capability"),
                "browser-login-v1:" + platform,
                StandardCharsets.US_ASCII);
        Files.writeString(
                image.resolve("browser-oauth-v1.capability"),
                "browser-oauth-v1:" + platform,
                StandardCharsets.US_ASCII);
    }

    private static String platformId() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("mac")) {
            return "macos";
        }
        if (os.contains("win")) {
            return "windows";
        }
        return "linux";
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
