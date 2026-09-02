package com.javaclaw.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserNativeCapabilityTest {
    @TempDir
    Path temporaryDirectory;

    private final String originalOperatingSystem = System.getProperty("os.name");

    @AfterEach
    void 恢复操作系统属性() {
        if (originalOperatingSystem == null) {
            System.clearProperty("os.name");
        } else {
            System.setProperty("os.name", originalOperatingSystem);
        }
    }

    @Test
    void 登录与OAuth分别发布只读平台回执且下次验证先清除() throws Exception {
        Path image = browserImage();

        for (BrowserNativeCapability capability : BrowserNativeCapability.values()) {
            capability.publish(image);
            Path marker = image.resolve(capability.fileName());
            assertEquals(
                    capability.receipt(),
                    Files.readString(marker, StandardCharsets.US_ASCII).strip());
            assertTrue(BrowserNativeCapability.isReadOnly(marker));

            capability.prepare(image);
            assertFalse(Files.exists(marker));
        }
    }

    @Test
    void 未组装镜像或重复发布时拒绝伪造分项能力() throws Exception {
        Path unverified = Files.createDirectories(temporaryDirectory.resolve("unverified"));
        assertThrows(IOException.class, () -> BrowserNativeCapability.MCP_OAUTH.publish(unverified));

        Path verified = browserImage();
        BrowserNativeCapability.MCP_OAUTH.publish(verified);
        assertThrows(IOException.class, () -> BrowserNativeCapability.MCP_OAUTH.publish(verified));
        assertFalse(Files.exists(verified.resolve(BrowserNativeCapability.INTERACTIVE_LOGIN.fileName())));
    }

    @Test
    void 平台回执稳定区分MacWindows和Linux() {
        System.setProperty("os.name", "Mac OS X");
        assertEquals("macos", BrowserNativeCapability.platformId());
        System.setProperty("os.name", "Windows 11");
        assertEquals("windows", BrowserNativeCapability.platformId());
        System.setProperty("os.name", "FreeBSD");
        assertEquals("linux", BrowserNativeCapability.platformId());
    }

    @Test
    void 重复清理安全回执保持幂等但目录标记被拒绝() throws Exception {
        Path image = browserImage("idempotent");
        BrowserNativeCapability capability = BrowserNativeCapability.INTERACTIVE_LOGIN;

        capability.prepare(image);
        Files.createDirectory(image.resolve(capability.fileName()));

        assertThrows(IOException.class, () -> capability.prepare(image));
    }

    @Test
    void 符号链接镜像根和组装标记均不能获得能力回执() throws Exception {
        Assumptions.assumeFalse(isWindows());
        Path image = browserImage("linked-marker");
        Path marker = image.resolve(WorkerImageAssemblerMain.IMAGE_MARKER);
        Files.delete(marker);
        Files.createSymbolicLink(marker, temporaryDirectory.resolve("outside-marker"));
        assertThrows(IOException.class, () -> BrowserNativeCapability.MCP_OAUTH.publish(image));

        Path linkedRoot = temporaryDirectory.resolve("linked-root");
        Files.createSymbolicLink(linkedRoot, image);
        assertThrows(IOException.class, () -> BrowserNativeCapability.MCP_OAUTH.publish(linkedRoot));
    }

    private Path browserImage() throws IOException {
        return browserImage("browser");
    }

    private Path browserImage(String name) throws IOException {
        Path image = Files.createDirectories(temporaryDirectory.resolve(name));
        Files.writeString(
                image.resolve(WorkerImageAssemblerMain.IMAGE_MARKER),
                "worker-image-v1:browser",
                StandardCharsets.US_ASCII);
        return image;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
