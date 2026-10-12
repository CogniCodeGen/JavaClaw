package com.javaclaw.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectAccessPolicyBehaviorTest {

    @TempDir
    Path outside;

    @Test
    void pathValidationRejectsEmptyMalformedTraversalAndReservedLocations() {
        assertTrue(ProjectAccessPolicy.strictIsolationEnabled());
        assertTrue(ProjectAccessPolicy.unconfinedExecutionDeniedReason().contains("严格项目文件隔离"));
        assertThrows(SecurityException.class, () -> ProjectAccessPolicy.resolveProjectPath(null));
        assertThrows(SecurityException.class, () -> ProjectAccessPolicy.resolveProjectPath(" "));
        assertThrows(SecurityException.class, () -> ProjectAccessPolicy.resolveProjectPath("\0"));
        assertThrows(SecurityException.class, () -> ProjectAccessPolicy.resolveProjectPath("~"));
        assertThrows(SecurityException.class, () -> ProjectAccessPolicy.resolveProjectPath("a/../b"));
        assertThrows(SecurityException.class, () -> ProjectAccessPolicy.requireProjectPath(null));
        assertThrows(SecurityException.class, () -> ProjectAccessPolicy.requireProjectPath(outside));
        assertFalse(ProjectAccessPolicy.isProjectPath(null));
        assertFalse(ProjectAccessPolicy.isProjectPath(outside));
        assertFalse(ProjectAccessPolicy.isProjectFilePath(null));
        assertFalse(ProjectAccessPolicy.isProjectFilePath(outside));

        for (String reserved : List.of(
                ".git/config", ".hg/store", ".svn/entries", ".javaclaw/private",
                "data", "data/database.mv.db")) {
            assertThrows(SecurityException.class,
                    () -> ProjectAccessPolicy.resolveProjectPath(reserved), reserved);
        }
        Path screenshot = ProjectAccessPolicy.resolveProjectPath("data/screenshots/capture.png");
        assertTrue(screenshot.endsWith("data/screenshots/capture.png"));
        assertTrue(ProjectAccessPolicy.isProjectFilePath(
                ProjectAccessPolicy.projectRoot().resolve("src/main/java")));
    }

    @Test
    void configuredManagedDirectoryRemainsPrivateExceptForScreenshotArtifacts() {
        String old = System.getProperty("javaclaw.data.dir");
        Path managed = ProjectAccessPolicy.projectRoot().resolve("target/policy-managed-data");
        System.setProperty("javaclaw.data.dir", managed.toString());
        try {
            assertThrows(SecurityException.class,
                    () -> ProjectAccessPolicy.requireProjectFilePath(managed));
            assertThrows(SecurityException.class,
                    () -> ProjectAccessPolicy.requireProjectFilePath(managed.resolve("settings.json")));
            assertEquals(managed.resolve("screenshots/image.png").normalize(),
                    ProjectAccessPolicy.requireProjectFilePath(
                            managed.resolve("screenshots/image.png")));

            System.setProperty("javaclaw.data.dir", outside.resolve("external-data").toString());
            Path ordinary = ProjectAccessPolicy.projectRoot().resolve("target/ordinary.txt");
            assertEquals(ordinary, ProjectAccessPolicy.requireProjectFilePath(ordinary));
        } finally {
            if (old == null) System.clearProperty("javaclaw.data.dir");
            else System.setProperty("javaclaw.data.dir", old);
        }
    }

    @Test
    void browserUrlsAllowSafeSchemesAndProjectFilesButRejectCredentials() throws Exception {
        assertNull(ProjectAccessPolicy.requireSafeBrowserUrl(null));
        assertEquals("https://example.com/path?page=2",
                ProjectAccessPolicy.requireSafeBrowserUrl(" https://example.com/path?page=2 "));
        assertEquals("http://93.184.216.34/",
                ProjectAccessPolicy.requireSafeBrowserUrl("http://93.184.216.34/"));
        assertEquals("about:blank", ProjectAccessPolicy.requireSafeBrowserUrl("about:blank"));
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.requireSafeBrowserUrl("https://user:pass@example.com/"));
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.requireSafeBrowserUrl("https://example.com/?api_key=secret-value"));
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.requireSafeBrowserUrl("mailto:user@example.com"));
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.requireSafeBrowserUrl("not a url"));

        Path directory = Files.createTempDirectory(
                ProjectAccessPolicy.projectRoot().resolve("target"), "safe-browser-");
        Path safe = Files.writeString(directory.resolve("safe.txt"), "public documentation");
        assertEquals(safe.toRealPath().toUri().toString(),
                ProjectAccessPolicy.requireSafeBrowserUrl(safe.toUri().toString()));
        Path secret = Files.writeString(directory.resolve("secret.txt"),
                "authorization: Bearer secret-value");
        assertThrows(SecurityException.class,
                () -> ProjectAccessPolicy.requireSafeBrowserUrl(secret.toUri().toString()));
        Path binary = Files.write(directory.resolve("binary.dat"), new byte[]{(byte) 0xc3, 0x28});
        assertEquals(binary.toRealPath().toUri().toString(),
                ProjectAccessPolicy.requireSafeBrowserUrl(binary.toUri().toString()));
        assertEquals(directory.toRealPath().toUri().toString(),
                ProjectAccessPolicy.requireSafeBrowserUrl(directory.toUri().toString()));
    }

    @Test
    void remoteMcpEndpointRejectsEveryLocalNetworkClassAndCredentialForm() {
        for (String invalid : List.of(
                "", "relative/path", "ftp://93.184.216.34/mcp",
                "https://user:pass@93.184.216.34/mcp",
                "https://93.184.216.34/mcp?token=secret-value",
                "http://localhost/mcp", "http://service.localhost/mcp",
                "http://localhost.localdomain/mcp", "http://ip6-localhost/mcp",
                "http://ip6-loopback/mcp", "http://printer.local/mcp",
                "http://0.0.0.0/mcp", "http://127.12.1.2/mcp",
                "http://10.1.2.3/mcp", "http://169.254.1.2/mcp",
                "http://172.16.1.2/mcp", "http://172.31.1.2/mcp",
                "http://192.168.1.2/mcp", "http://100.64.1.2/mcp",
                "http://224.0.0.1/mcp", "http://[::]/mcp",
                "http://[::1]/mcp", "http://[fc00::1]/mcp",
                "http://[fe80::1]/mcp", "http://[ff02::1]/mcp",
                "http://[::ffff:127.0.0.1]/mcp")) {
            assertThrows(SecurityException.class,
                    () -> ProjectAccessPolicy.requireRemoteMcpEndpoint(invalid), invalid);
        }
        assertEquals("93.184.216.34",
                ProjectAccessPolicy.requireRemoteMcpEndpoint(
                        "https://93.184.216.34:8443/mcp").getHost());
    }

    @Test
    void endpointSummariesNeverExposeAuthenticationOrQueryMaterial() {
        assertEquals("（无效 URL）", ProjectAccessPolicy.remoteEndpointSummary(null));
        assertEquals("（无效 URL）", ProjectAccessPolicy.remoteEndpointSummary("relative"));
        assertEquals("（无效 URL）", ProjectAccessPolicy.remoteEndpointSummary("bad url"));
        assertEquals("https://example.com",
                ProjectAccessPolicy.remoteEndpointSummary("HTTPS://example.com"));
        assertEquals("https://example.com:8443/mcp/v1",
                ProjectAccessPolicy.remoteEndpointSummary(
                        "https://user:pass@example.com:8443/mcp/v1?token=hidden#fragment"));
    }
}
